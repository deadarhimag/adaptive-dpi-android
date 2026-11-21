package org.adaptivedpi.android.engine

import java.io.Closeable

interface PacketEngine {
    /** При успехе сессия принимает владение дескриптором TUN. При ошибке его закрывает вызывающая сторона. */
    suspend fun start(tunnel: PacketTunnel, socketProtector: SocketProtector): PacketEngineSession
}

interface PacketTunnel : Closeable {
    val fileDescriptor: Int
}

interface PacketEngineSession : Closeable

fun interface SocketProtector {
    /** Применяется до подключения сокета или первой отправки. false запрещает отправку. */
    fun protect(fileDescriptor: Int): Boolean
}
