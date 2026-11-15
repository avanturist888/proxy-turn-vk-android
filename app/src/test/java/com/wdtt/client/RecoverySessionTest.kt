package com.wdtt.client

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RecoverySessionTest {
    @Test fun stopInvalidatesReconnectEvenWhenCleanupIgnoresCancellation() = runBlocking {
        val session = SessionGeneration()
        val old = session.incrementAndGet()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val reconnect = launch {
            try {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                    session.requireCurrent(old)
                    events += "old-start"
                }
            } catch (_: CancellationException) { events += "old-retired" }
        }
        entered.await()
        session.incrementAndGet() // Stop revokes the old work before cancellation/join.
        reconnect.cancel()
        val replacement = launch {
            reconnect.join()
            val current = session.incrementAndGet()
            session.requireCurrent(current)
            events += "new-start"
        }
        yield()
        assertTrue(events.isEmpty()) // Replacement cannot overlap retiring work.
        release.complete(Unit)
        replacement.join()
        assertEquals(listOf("old-retired", "new-start"), events)
    }

    @Test fun bufferedOldConfigCannotBorrowNewSessionIdentity() = runBlocking {
        val session = SessionGeneration()
        val capturedAtReaderCreation = session.incrementAndGet()
        val queued = CompletableDeferred<Unit>()
        val apply = CompletableDeferred<Unit>()
        var applied = false
        val callback = launch {
            queued.complete(Unit)
            apply.await()
            session.requireCurrent(capturedAtReaderCreation)
            applied = true
        }
        queued.await()
        session.incrementAndGet() // Fallback accepts another server before callback runs.
        apply.complete(Unit)
        callback.join()
        assertFalse(applied)
    }

    @Test fun repeatedChallengesCannotExtendHardAttemptLimit() {
        val budget = RecoveryBudget(0)
        assertFalse(budget.expired(10_000, true))
        assertFalse(budget.expired(15_000, false))
        assertFalse(budget.expired(30_000, true))
        assertFalse(budget.expired(35_000, false))
        assertTrue(budget.expired(60_000, true))
        val connected = RecoveryBudget(1000)
        assertFalse(connected.expired(2000, true))
        assertTrue(connected.expired(22_000, true))
    }

    @Test fun explicitParametersStayFirstAndSavedProfilesContinueAfterSelection() {
        assertEquals(listOf("third", "first"), recoveryProfileOrder(listOf("first", "second", "third"), "second"))
        assertEquals(listOf("first", "second"), recoveryProfileOrder(listOf("first", "second"), "custom"))
        assertEquals(emptyList<String>(), recoveryProfileOrder(listOf("only"), "only"))
    }

    @Test fun stickyServiceCannotUndoExplicitStopButSystemAlwaysOnCanStart() {
        assertFalse(shouldResumeConnection(false, true, false))
        assertTrue(shouldResumeConnection(false, true, true))
        assertTrue(shouldResumeConnection(true, false, false))
        assertFalse(shouldResumeConnection(false, false, true)) // Internal warmup is not a connect request.
    }

    @Test fun ownerRetirementWaitsForIdentityAndTimesOutWithoutWarmup() = runBlocking {
        val owners = OwnerLifetime<Any>()
        val old = Any()
        val next = Any()
        owners.replace(old)
        var warmed = false
        val waiting = launch { owners.awaitReleased(old); warmed = true }
        yield()
        assertFalse(warmed)
        owners.replace(next)
        waiting.join()
        assertTrue(warmed)
        assertSame(next, owners.current)
        try {
            owners.awaitReleased(next, 10)
            fail("live owner cannot be declared retired")
        } catch (_: TimeoutCancellationException) { }
        assertSame(next, owners.current)
    }

    @Test fun stopDuringControlFlowDoesNotRestoreOldVpn() = runBlocking {
        val session = SessionGeneration()
        val control = ControlPlaneBootstrap()
        val old = session.incrementAndGet()
        val entered = CompletableDeferred<Unit>()
        var restored = false
        val job = launch {
            control.run(release = {}, restore = { if (session.get() == old) restored = true }) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        session.incrementAndGet()
        job.cancelAndJoin()
        assertFalse(restored)
        assertFalse(control.active)
    }
}
