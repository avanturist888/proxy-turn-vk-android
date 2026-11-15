package com.wdtt.client

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class TransportRecoveryStateTest {
    @Test fun terminalStopClearsTheFlagsThatPreviouslyKeptTheButtonInStopMode() {
        val state = TransportRecoveryState()
        val generation = SessionGeneration()
        val reconnect = generation.incrementAndGet()
        generation.withCurrent(reconnect) {
            state.reconnecting.value = true
            state.restarting = true
            state.preservingVpn = true
            state.keepWireGuardOnNextConfig = true
            state.preservedWireGuardConfig = "synthetic-config"
        }
        generation.incrementAndGet()
        state.reset()
        assertFalse(state.reconnecting.value)
        assertFalse(state.restarting)
        assertFalse(state.preservingVpn)
        assertFalse(state.keepWireGuardOnNextConfig)
        assertNull(state.preservedWireGuardConfig)
        try {
            generation.withCurrent(reconnect) { state.reconnecting.value = true }
            fail("retired reconnect cannot republish transient state")
        } catch (_: CancellationException) { }
        assertFalse(state.reconnecting.value)
    }

    @Test fun oldReconnectCompletionCannotClearReplacementState() {
        val state = TransportRecoveryState()
        val generation = SessionGeneration()
        val old = generation.incrementAndGet()
        generation.incrementAndGet()
        state.reset()
        val replacement = generation.incrementAndGet()
        generation.withCurrent(replacement) { state.reconnecting.value = true }
        try {
            generation.withCurrent(old) { state.reset() }
            fail("retired completion cannot clear the replacement")
        } catch (_: CancellationException) { }
        assertTrue(state.reconnecting.value)
    }
}
