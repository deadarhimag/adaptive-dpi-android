package org.adaptivedpi.android.vpn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.adaptivedpi.android.vpn.diagnostics.CleanupStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VpnShutdownTest {
    @Test
    fun blockedWorkerDoesNotHoldCommandOrDeadlineAndIsReleasedOnlyOnce() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val allowRelease = CountDownLatch(1)
        val timedOut = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Result<Int>>()
        var attempts = 0
        val shutdown = VpnShutdown(
            scope = this,
            delayMillis = 200,
            clock = { System.nanoTime() / 1_000_000 },
            release = {
                attempts++
                withContext(Dispatchers.IO) {
                    entered.complete(Unit)
                    check(allowRelease.await(5, TimeUnit.SECONDS))
                    42
                }
            },
            onDelay = { timedOut.complete(Unit) },
            onComplete = { completed.complete(it) },
        )
        try {
            shutdown.start()
            shutdown.start()
            assertTrue(shutdown.started)
            withTimeout(3000) {
                entered.await()
                timedOut.await()
            }
            assertEquals(1, attempts)
            assertFalse(completed.isCompleted)
        } finally {
            allowRelease.countDown()
        }
        assertEquals(42, withTimeout(3000) { completed.await().getOrThrow() })
    }

    @Test
    fun completionCancelsDeadlineObserver() = runBlocking {
        var timedOut = false
        val completed = CompletableDeferred<Result<String>>()
        val shutdown = VpnShutdown(this, 4_000, { 0L }, { "released" }, { timedOut = true }, { completed.complete(it) })
        shutdown.start()
        assertEquals("released", withTimeout(3000) { completed.await().getOrThrow() })
        assertFalse(timedOut)
    }

    @Test
    fun idleRequiresBothResourceAndServiceConfirmationAt100ms3sAnd6s() = runBlocking {
        for (elapsed in listOf(100L, 3_000L, 6_000L)) {
            var now = 0L
            var warnings = 0
            val resourcesReleased = CompletableDeferred<Unit>()
            val serviceDestroyed = CompletableDeferred<Unit>()
            val completed = CompletableDeferred<Unit>()
            val lifecycle = VpnLifecycle()
            val id = "session"
            val shutdown = VpnShutdown(this, 4_000, { now }, {
                resourcesReleased.await()
                serviceDestroyed.await()
            }, {
                warnings++
                lifecycle.cleanupDelayed(id, "Android")
                assertEquals(VpnState.Stopping, lifecycle.state.value)
                assertFalse(lifecycle.beginConsent("overlap"))
            }, {
                it.getOrThrow()
                lifecycle.serviceEnded(id, VpnEvent.ServiceDestroyed)
                completed.complete(Unit)
            })
            assertTrue(lifecycle.beginConsent(id))
            lifecycle.update(id, VpnEvent.StartRequested)
            assertTrue(lifecycle.attachService(id, shutdown::start))
            lifecycle.stop()
            resourcesReleased.complete(Unit)
            yield()
            assertFalse(completed.isCompleted)
            assertFalse(lifecycle.beginConsent("still-stopping"))

            now = elapsed
            serviceDestroyed.complete(Unit)
            withTimeout(3_000) { completed.await() }
            assertEquals(if (elapsed >= 4_000) 1 else 0, warnings)
            assertEquals(VpnState.Idle, lifecycle.state.value)
            assertEquals(null, lifecycle.cleanupDelay.value)
            assertTrue(lifecycle.beginConsent("restart"))
        }
    }

    @Test
    fun blockedTransportPollDoesNotDelayStopSignalOrReleaseOwnershipEarly() = runBlocking {
        val pollingIo = Dispatchers.IO.limitedParallelism(1)
        val stopIo = Dispatchers.IO.limitedParallelism(1)
        val pollEntered = CompletableDeferred<Unit>()
        val stopReached = CompletableDeferred<Unit>()
        val releasePoll = CountDownLatch(1)
        val poll = launch(pollingIo) {
            pollEntered.complete(Unit)
            check(releasePoll.await(5, TimeUnit.SECONDS))
        }
        try {
            withTimeout(3_000) { pollEntered.await() }
            val stopping = launch {
                requestStopAndAwaitPoll(poll) {
                    withContext(stopIo) { stopReached.complete(Unit) }
                }
            }
            withTimeout(3_000) { stopReached.await() }
            assertFalse(stopping.isCompleted)
            releasePoll.countDown()
            withTimeout(3_000) { stopping.join() }
            assertTrue(poll.isCompleted)
        } finally {
            releasePoll.countDown()
        }
    }

    @Test
    fun oneFailedReleaseDoesNotPreventOtherCleanupStepsOrReporting() {
        val steps = mutableListOf<String>()
        releaseVpnResources(
            { step, _ -> steps += "cleanup error ${step.name}" },
            CleanupStep.TunClose to { throw IOException("Тестовый отказ") },
            CleanupStep.ForegroundNotification to { steps += "foreground" },
            CleanupStep.ActiveMarker to { steps += "marker" },
        )
        assertEquals(listOf("foreground", "marker", "cleanup error TunClose"), steps)
    }

    @Test
    fun criticalStopMakesManualRestartAvailableAfterTheOldServiceEnds() {
        val lifecycle = VpnLifecycle()
        assertTrue(lifecycle.beginConsent("running"))
        lifecycle.update("running", VpnEvent.StartRequested)
        assertTrue(lifecycle.attachService("running") {})
        lifecycle.update("running", VpnEvent.ServiceStarted)
        lifecycle.serviceEnded("running", VpnEvent.CriticalFailure)
        assertEquals(VpnState.Failed, lifecycle.state.value)
        assertTrue(lifecycle.beginConsent("manual"))
        lifecycle.update("manual", VpnEvent.StartRequested)
        assertEquals(VpnState.Starting, lifecycle.state.value)
    }
}
