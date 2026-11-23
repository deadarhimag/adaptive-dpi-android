package org.adaptivedpi.android.vpn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TransportPollTest {
    @Test(timeout = 8_000)
    fun warnsOnOwnerContextWhileIoIsBlockedThenReturnsActualResult() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val warned = CompletableDeferred<Thread>()
        val allowReturn = CountDownLatch(1)
        val ownerThread = Thread.currentThread()
        var warnings = 0
        val result = async {
            observeTransportPoll(30, { warnings++; warned.complete(Thread.currentThread()) }) {
                withContext(Dispatchers.IO) {
                    entered.complete(Unit)
                    check(allowReturn.await(4, TimeUnit.SECONDS))
                    42
                }
            }
        }
        try {
            withTimeout(2_000) {
                entered.await()
                assertEquals(ownerThread, warned.await())
            }
            assertFalse(result.isCompleted)
        } finally {
            allowReturn.countDown()
        }
        assertEquals(42, result.await())
        assertEquals(1, warnings)
    }

    @Test
    fun fastPollCancelsWarning() = runBlocking {
        var warnings = 0
        assertEquals(7, observeTransportPoll(1_000, { warnings++ }) { 7 })
        assertEquals(0, warnings)
    }
}
