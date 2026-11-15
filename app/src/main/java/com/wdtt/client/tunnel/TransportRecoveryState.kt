package com.wdtt.client

import kotlinx.coroutines.flow.MutableStateFlow

/** Transient transport state belongs to a session; terminal Stop clears it all. */
class TransportRecoveryState {
    val reconnecting = MutableStateFlow(false)
    @Volatile var restarting = false
    @Volatile var preservingVpn = false
    @Volatile var keepWireGuardOnNextConfig = false
    @Volatile var preservedWireGuardConfig: String? = null

    fun reset() {
        reconnecting.value = false
        restarting = false
        preservingVpn = false
        keepWireGuardOnNextConfig = false
        preservedWireGuardConfig = null
    }
}
