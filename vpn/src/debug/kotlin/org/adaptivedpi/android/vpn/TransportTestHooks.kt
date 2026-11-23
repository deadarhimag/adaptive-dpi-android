package org.adaptivedpi.android.vpn

import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Синхронизация инструментальных тестов внутри debug-процесса. */
object TransportTestHooks {
    init { System.loadLibrary("adaptive-hev") }

    @Volatile var afterTcpPrepare: ((Socket) -> Unit)? = null

    internal fun tcpPrepared(socket: Socket) {
        afterTcpPrepare?.invoke(socket)
    }

    fun holdNativePollLock(entered: CountDownLatch, release: CountDownLatch) =
        withNativePollLock(Runnable {
            entered.countDown()
            check(release.await(15, TimeUnit.SECONDS)) { "Тест не освободил native mutex за 15 секунд" }
        })

    private external fun withNativePollLock(action: Runnable)
}
