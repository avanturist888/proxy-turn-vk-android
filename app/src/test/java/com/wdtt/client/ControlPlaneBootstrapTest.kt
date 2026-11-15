package com.wdtt.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ControlPlaneBootstrapTest {
    @Test fun restoresAfterFailedAuthentication() = runBlocking {
        val gate = ControlPlaneBootstrap()
        val events = mutableListOf<String>()
        try {
            gate.run(
                release = { events += "release" },
                restore = { assertTrue(gate.active); events += "restore" },
            ) { assertTrue(gate.active); throw IllegalStateException("auth failed") }
            fail("failure expected")
        } catch (_: IllegalStateException) { }
        assertEquals(listOf("release", "restore"), events)
        assertFalse(gate.active)
    }

    @Test fun cancellationStillRestoresAndClearsGate() = runBlocking {
        val gate = ControlPlaneBootstrap()
        var restored = false
        val started = CompletableDeferred<Unit>()
        val job = launch {
            gate.run(release = {}, restore = { restored = true }) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(restored)
        assertFalse(gate.active)
    }

    @Test fun failedReleaseAlsoCleansUp() = runBlocking {
        val gate = ControlPlaneBootstrap()
        var restored = false
        try {
            gate.run(release = { error("warmup failed") }, restore = { restored = true }) {
                fail("authentication must not start")
            }
            fail("failure expected")
        } catch (_: IllegalStateException) { }
        assertTrue(restored)
        assertFalse(gate.active)
    }

    @Test fun nextControlFlowWaitsForRestoration() = runBlocking {
        val gate = ControlPlaneBootstrap()
        val opened = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val first = launch {
            gate.run(release = { events += "release-1" }, restore = { events += "restore-1" }) {
                opened.complete(Unit)
                finish.await()
            }
        }
        opened.await()
        val second = launch {
            gate.run(release = { events += "release-2" }, restore = { events += "restore-2" }) { }
        }
        finish.complete(Unit)
        first.join()
        second.join()
        assertEquals(listOf("release-1", "restore-1", "release-2", "restore-2"), events)
        assertFalse(gate.active)
    }
}
