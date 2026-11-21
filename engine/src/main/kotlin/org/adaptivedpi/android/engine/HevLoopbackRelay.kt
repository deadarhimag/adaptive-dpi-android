package org.adaptivedpi.android.engine

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ProtocolException
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Semaphore
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Одноразовый локальный интерфейс HEV. UDP проходит здесь в кадрах поверх TCP. */
class HevLoopbackRelay(
    private val transport: OutboundTransport,
    private val destination: InetAddress,
    private val onListenerFailure: () -> Unit,
) : Closeable {
    private val lock = Any()
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val listener = ServerSocket(0, MAX_SESSIONS, loopback)
    private val permits = Semaphore(MAX_SESSIONS)
    private val clients = mutableSetOf<Socket>()
    private val workers = ThreadPoolExecutor(0, MAX_SESSIONS * 2, 15, TimeUnit.SECONDS, SynchronousQueue())
    private val secret = ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val errors = AtomicLong()
    private var closing = false
    private val acceptThread = Thread(::accept, "HEV accept").apply { start() }

    val port: Int = listener.localPort
    val configSecret: String get() = secret
    val failedSessions: Long get() = errors.get()

    private fun accept() {
        try {
            while (!listener.isClosed) {
                val socket = listener.accept()
                synchronized(lock) {
                    if (closing || !permits.tryAcquire()) {
                        socket.close()
                    } else {
                        clients.add(socket)
                        try {
                            workers.execute { handle(socket) }
                        } catch (_: RejectedExecutionException) {
                            clients.remove(socket)
                            permits.release()
                            socket.close()
                        }
                    }
                }
            }
        } catch (_: IOException) {
            if (synchronized(lock) { !closing }) onListenerFailure()
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use {
                socket.soTimeout = 5_000
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                authenticate(input, output)
                requireProtocol(input.readUnsignedByte() == 5)
                val command = input.readUnsignedByte()
                requireProtocol(input.readUnsignedByte() == 0)
                val endpoint = readEndpoint(input)
                socket.soTimeout = 60_000
                when (command) {
                    1 -> tcp(socket, input, output, endpoint)
                    5 -> udp(socket, input, output)
                    else -> throw ProtocolException("Неподдерживаемая команда локального релея.")
                }
            }
        } catch (_: Exception) {
            if (synchronized(lock) { !closing }) errors.incrementAndGet()
        } finally {
            synchronized(lock) { clients.remove(socket) }
            permits.release()
        }
    }

    private fun authenticate(input: DataInputStream, output: DataOutputStream) {
        requireProtocol(input.readUnsignedByte() == 5)
        val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
        if (2.toByte() !in methods) {
            output.write(byteArrayOf(5, -1))
            throw ProtocolException("Для локального релея нужна аутентификация.")
        }
        output.write(byteArrayOf(5, 2))
        requireProtocol(input.readUnsignedByte() == 1)
        val user = ByteArray(input.readUnsignedByte()).also(input::readFully)
        val password = ByteArray(input.readUnsignedByte()).also(input::readFully)
        val accepted = MessageDigest.isEqual(USER, user) &&
            MessageDigest.isEqual(secret.toByteArray(Charsets.US_ASCII), password)
        output.write(byteArrayOf(1, if (accepted) 0 else 1))
        requireProtocol(accepted)
    }

    private fun tcp(socket: Socket, input: DataInputStream, output: DataOutputStream, endpoint: Endpoint) {
        checkDestination(endpoint)
        val connection = try {
            transport.connectTcp(endpoint.address, endpoint.port)
        } catch (error: IOException) {
            reply(output, 5)
            throw error
        }
        connection.use {
            reply(output, 0)
            val response = workers.submit {
                try {
                    val buffer = ByteArray(TCP_BUFFER)
                    while (true) {
                        val size = connection.read(buffer)
                        if (size < 0) break
                        output.write(buffer, 0, size)
                    }
                    socket.shutdownOutput()
                } catch (error: IOException) {
                    runCatching(socket::close)
                    throw error
                }
            }
            try {
                val buffer = ByteArray(TCP_BUFFER)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    connection.write(buffer, 0, size)
                }
                connection.shutdownOutput()
                response.get()
            } finally {
                response.cancel(true)
            }
        }
    }

    private fun udp(socket: Socket, input: DataInputStream, output: DataOutputStream) {
        reply(output, 0)
        val payload = ByteArray(MAX_DATAGRAM)
        val first = readDatagram(input, payload) ?: return
        checkDestination(first.endpoint)
        transport.connectUdp(first.endpoint.address, first.endpoint.port).use { connection ->
            val response = workers.submit {
                try {
                    val buffer = ByteArray(MAX_DATAGRAM + 1)
                    while (!socket.isClosed) {
                        val size = connection.receive(buffer)
                        output.writeShort(size)
                        output.writeByte(10)
                        output.writeByte(1)
                        output.write(first.endpoint.address.address)
                        output.writeShort(first.endpoint.port)
                        output.write(buffer, 0, size)
                    }
                } catch (error: IOException) {
                    runCatching(socket::close)
                    throw error
                }
            }
            try {
                connection.send(payload, length = first.length)
                while (!socket.isClosed) {
                    val next = readDatagram(input, payload) ?: break
                    requireProtocol(next.endpoint == first.endpoint)
                    connection.send(payload, length = next.length)
                }
            } finally {
                response.cancel(true)
            }
        }
    }

    private fun readDatagram(input: DataInputStream, payload: ByteArray): Frame? {
        val firstByte = input.read()
        if (firstByte == -1) return null
        val length = firstByte * 256 + input.readUnsignedByte()
        val headerLength = input.readUnsignedByte()
        requireProtocol(length <= payload.size)
        val endpoint = readEndpoint(input, headerLength)
        input.readFully(payload, 0, length)
        return Frame(endpoint, length)
    }

    private fun readEndpoint(input: DataInputStream, headerLength: Int? = null): Endpoint {
        val size = when (input.readUnsignedByte()) {
            1 -> 4
            4 -> 16
            else -> throw ProtocolException("Локальный релей принимает числовые адреса.")
        }
        requireProtocol(headerLength == null || headerLength == size + 6)
        val address = InetAddress.getByAddress(ByteArray(size).also(input::readFully))
        return Endpoint(address, input.readUnsignedShort())
    }

    private fun checkDestination(endpoint: Endpoint) {
        requireProtocol(endpoint.address == destination && endpoint.port != 0)
    }

    private fun reply(output: DataOutputStream, status: Byte) {
        output.write(byteArrayOf(5, status, 0, 1, 0, 0, 0, 0, 0, 0))
    }

    override fun close() {
        synchronized(lock) {
            if (closing) return
            closing = true
            runCatching(listener::close)
            clients.forEach { runCatching(it::close) }
        }
        transport.close()
        workers.shutdownNow()
    }

    /** Вызывается рабочим потоком после закрытия и до освобождения сеанса. */
    fun awaitShutdown(): Boolean {
        acceptThread.join(2_000)
        return !acceptThread.isAlive && workers.awaitTermination(2, TimeUnit.SECONDS)
    }

    private data class Endpoint(val address: InetAddress, val port: Int)
    private data class Frame(val endpoint: Endpoint, val length: Int)

    private companion object {
        val USER = "adaptive".toByteArray(Charsets.US_ASCII)
        const val MAX_SESSIONS = 16
        const val TCP_BUFFER = 8 * 1_024
        const val MAX_DATAGRAM = 1_252

        fun requireProtocol(condition: Boolean) {
            if (!condition) throw ProtocolException("Недопустимые данные локального релея.")
        }
    }
}
