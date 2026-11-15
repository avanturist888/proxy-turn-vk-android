package com.wdtt.client

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UnderlyingNetworkStateTest {
    @Test fun networkReturnWakesRecoveryWithoutARunningProcess() = runBlocking {
        val state = UnderlyingNetworkState()
        state.update("cell:first")
        val first = state.current
        val waiting = async { state.awaitChange(first.revision, 60_000) }
        yield()
        state.update("")
        assertTrue(waiting.await())
        assertFalse(state.current.available)
        val returning = async { state.awaitAvailable() }
        yield()
        assertFalse(returning.isCompleted)
        state.update("cell:second")
        assertEquals("cell:second", withTimeout(1000) { returning.await() }.fingerprint)
    }

    @Test fun aChangeBeforeCooldownStartsIsNotLost() = runBlocking {
        val state = UnderlyingNetworkState()
        state.update("wifi:first")
        val attempted = state.current.revision
        state.update("cell:second")
        assertTrue(withTimeout(1000) { state.awaitChange(attempted, 60_000) })
        val current = state.current.revision
        state.update("cell:second")
        assertEquals(current, state.current.revision)
        assertFalse(state.awaitChange(current, 10))
    }

    @Test fun repeatedOutagesAndAddressChangesEachInvalidateOldSockets() = runBlocking {
        val state = UnderlyingNetworkState()
        for (fingerprint in listOf("cell:one", "", "cell:two", "wifi:one", "wifi:two", "", "cell:three")) {
            val old = state.current.revision
            state.update(fingerprint)
            assertTrue(state.awaitChange(old, 1000))
            assertEquals(fingerprint.isNotEmpty(), state.current.available)
        }
    }

    @Test fun explicitStopCancelsNetworkWaitAndLaterEventsDoNotResumeIt() = runBlocking {
        val state = UnderlyingNetworkState()
        var started = false
        val loop = launch { state.awaitAvailable(); started = true }
        yield()
        loop.cancelAndJoin()
        state.update("cell:new")
        yield()
        assertFalse(started)
    }

    @Test fun transportUsesPreferredWorkingPhysicalNetworkAndCanBootstrapBeforeValidation() {
        val wifi = UnderlyingNetworkCandidate("wifi", validated = true, wifi = true)
        val cell = UnderlyingNetworkCandidate("cell", validated = true, wifi = false)
        assertEquals("cell", selectUnderlyingNetwork(listOf(wifi, cell), "cell"))
        assertEquals("wifi", selectUnderlyingNetwork(listOf(cell, wifi), "vpn"))
        val captiveWifi = wifi.copy(validated = false)
        assertEquals("cell", selectUnderlyingNetwork(listOf(captiveWifi, cell), "vpn"))
        assertEquals("cell", selectUnderlyingNetwork(listOf(cell.copy(validated = false)), "vpn"))
        assertNull(selectUnderlyingNetwork(emptyList<UnderlyingNetworkCandidate<String>>(), "vpn"))
    }
}
