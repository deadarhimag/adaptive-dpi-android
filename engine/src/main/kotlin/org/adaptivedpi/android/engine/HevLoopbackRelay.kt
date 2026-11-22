package org.adaptivedpi.android.engine

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
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

/** Одноразовый локальный интерфейс HEV. UDP проходит здесь в кадрах поверх TCP. */
class HevLoopbackRelay(
    private val transport: OutboundTransport,
    private val destination: InetAddress?,
    maxSessions: Int = 16,
    private val failures: TransportFailureReporter = TransportFailureReporter(),
    private val onListenerFailure: () -> Unit,
) : Closeable {
    private val lock = Any()
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val listener = ServerSocket(0, maxSessions, loopback)
    private val permits = Semaphore(maxSessions)
    private val clients = mutableSetOf<Socket>()
    private val workers = ThreadPoolExecutor(0, maxSessions * 2, 15, TimeUnit.SECONDS, SynchronousQueue())
    private val secret = ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    @Volatile private var closing = false
    private val acceptThread = Thread(::accept, "HEV accept").apply { start() }

    val port: Int = listener.localPort
    val configSecret: String get() = secret
    private fun accept() {
        try {
            while (!listener.isClosed) {
                val socket = listener.accept()
                synchronized(lock) {
                    if (closing || !permits.tryAcquire()) {
                        if (!closing) failures.record(TransportFailure(ACCEPT, TransportCategory.LIMIT, TransportCode.SESSION_LIMIT))
                        closeRelay(socket)
                    } else {
                        clients.add(socket)
                        try {
                            workers.execute { handle(socket) }
                        } catch (_: RejectedExecutionException) {
                            failures.record(TransportFailure(ACCEPT, TransportCategory.LIMIT, TransportCode.WORKER_REJECTED))
                            clients.remove(socket)
                            permits.release()
                            closeRelay(socket)
                        }
                    }
                }
            }
        } catch (error: IOException) {
            failures.recordError(ACCEPT, error, closing)
            if (synchronized(lock) { !closing }) onListenerFailure()
        }
    }

    private fun handle(socket: Socket) {
        try {
            val (input, output) = observe(AUTH, socket) {
                socket.soTimeout = 5_000
                DataInputStream(socket.getInputStream()) to DataOutputStream(socket.getOutputStream())
            }
            observe(AUTH, socket) {
                authenticate(input, output)
            }
            val (command, endpoint) = observe(CONTROL_READ, socket) {
                requireProtocol(input.readUnsignedByte() == 5)
                val command = input.readUnsignedByte()
                requireProtocol(input.readUnsignedByte() == 0)
                val endpoint = readEndpoint(input)
                socket.soTimeout = 60_000
                requireProtocol(command == 1 || command == 5)
                command to endpoint
            }
            when (command) {
                1 -> tcp(socket, input, output, endpoint)
                5 -> udp(socket, input, output)
            }
        } catch (_: Exception) {
            // Отказ регистрирует владелец операции. Ошибки внешних сокетов уже учтены в OutboundTransport.
        } finally {
            closeRelay(socket)
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
        observe(TCP_READ, socket) { checkDestination(endpoint) }
        val connection = try {
            transport.connectTcp(endpoint.address, endpoint.port)
        } catch (error: IOException) {
            observe(CONTROL_WRITE, socket) { reply(output, 5) }
            throw error
        }
        connection.use {
            observe(CONTROL_WRITE, socket) { reply(output, 0) }
            val response = submit(socket) {
                try {
                    val buffer = ByteArray(TCP_BUFFER)
                    while (true) {
                        val size = connection.read(buffer)
                        if (size < 0) break
                        observe(TCP_WRITE, socket) { output.write(buffer, 0, size) }
                    }
                    observe(TCP_CLOSE, socket) { socket.shutdownOutput() }
                } catch (error: IOException) {
                    closeRelay(socket)
                    throw error
                }
            }
            try {
                val buffer = ByteArray(TCP_BUFFER)
                while (true) {
                    val size = observe(TCP_READ, socket) { input.read(buffer) }
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
        observe(CONTROL_WRITE, socket) { reply(output, 0) }
        val payload = ByteArray(MAX_DATAGRAM)
        val first = observe(UDP_READ, socket) {
            readDatagram(input, payload)?.also { checkDestination(it.endpoint) }
        } ?: return
        transport.connectUdp(first.endpoint.address, first.endpoint.port).use { connection ->
            val response = submit(socket) {
                try {
                    val buffer = ByteArray(MAX_DATAGRAM + 1)
                    while (!socket.isClosed) {
                        val size = connection.receive(buffer)
                        observe(UDP_WRITE, socket) {
                            output.writeShort(size)
                            output.writeByte(10)
                            output.writeByte(1)
                            output.write(first.endpoint.address.address)
                            output.writeShort(first.endpoint.port)
                            output.write(buffer, 0, size)
                        }
                    }
                } catch (error: IOException) {
                    closeRelay(socket)
                    throw error
                }
            }
            try {
                connection.send(payload, length = first.length)
                while (!socket.isClosed) {
                    val next = observe(UDP_READ, socket) {
                        readDatagram(input, payload)?.also { requireProtocol(it.endpoint == first.endpoint) }
                    } ?: break
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
        val address = endpoint.address
        requireProtocol(endpoint.port != 0 && if (destination != null) address == destination else {
            address is Inet4Address && !address.isAnyLocalAddress && !address.isLoopbackAddress &&
                !address.isMulticastAddress && (address.address[0].toInt() and 255) < 240
        })
    }

    private fun reply(output: DataOutputStream, status: Byte) {
        output.write(byteArrayOf(5, status, 0, 1, 0, 0, 0, 0, 0, 0))
    }

    private inline fun <T> observe(site: TransportSite, socket: Socket, operation: () -> T): T = try {
        operation()
    } catch (error: Exception) {
        failures.recordError(site, error, closing || socket.isClosed)
        throw error
    }

    private fun submit(socket: Socket, operation: () -> Unit) = try {
        workers.submit(operation)
    } catch (error: RejectedExecutionException) {
        val category = if (closing || socket.isClosed) TransportCategory.CANCELLED else TransportCategory.LIMIT
        failures.record(TransportFailure(CONTROL_READ, category, TransportCode.WORKER_REJECTED))
        throw error
    }

    private fun closeRelay(socket: Closeable) {
        runCatching(socket::close).onFailure {
            failures.recordError(CLOSE, it, fallback = TransportCategory.CLEANUP)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closing) return
            closing = true
            closeRelay(listener)
            clients.forEach(::closeRelay)
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
        const val TCP_BUFFER = 8 * 1_024
        const val MAX_DATAGRAM = 1_252
        val ACCEPT = TransportSite(TransportSubsystem.HEV, TransportOperation.ACCEPT)
        val AUTH = TransportSite(TransportSubsystem.HEV, TransportOperation.AUTHENTICATE)
        val CLOSE = TransportSite(TransportSubsystem.HEV, TransportOperation.CLOSE)
        val CONTROL_READ = TransportSite(TransportSubsystem.HEV, TransportOperation.READ)
        val CONTROL_WRITE = TransportSite(TransportSubsystem.HEV, TransportOperation.WRITE)
        val TCP_READ = TransportSite(TransportSubsystem.HEV, TransportOperation.READ, TransportProtocol.TCP, TransportDirection.OUTBOUND)
        val TCP_WRITE = TransportSite(TransportSubsystem.HEV, TransportOperation.WRITE, TransportProtocol.TCP, TransportDirection.INBOUND)
        val TCP_CLOSE = TransportSite(TransportSubsystem.HEV, TransportOperation.CLOSE, TransportProtocol.TCP, TransportDirection.INBOUND)
        val UDP_READ = TransportSite(TransportSubsystem.HEV, TransportOperation.READ, TransportProtocol.UDP, TransportDirection.OUTBOUND)
        val UDP_WRITE = TransportSite(TransportSubsystem.HEV, TransportOperation.WRITE, TransportProtocol.UDP, TransportDirection.INBOUND)

        fun requireProtocol(condition: Boolean) {
            if (!condition) throw ProtocolException("Недопустимые данные локального релея.")
        }
    }
}
