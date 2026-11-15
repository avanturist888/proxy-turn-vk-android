package com.wdtt.client

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Network changes wake recovery even while no subprocess or VPN is running. */
class UnderlyingNetworkState {
    data class Snapshot(val fingerprint: String = "", val revision: Long = 0) {
        val available: Boolean get() = fingerprint.isNotEmpty()
    }

    private val changes = MutableStateFlow(Snapshot())
    val current: Snapshot get() = changes.value

    @Synchronized fun update(fingerprint: String) {
        val previous = changes.value
        if (previous.fingerprint != fingerprint) {
            changes.value = Snapshot(fingerprint, previous.revision + 1)
        }
    }

    suspend fun awaitAvailable(): Snapshot = changes.first { it.available }

    suspend fun awaitChange(revision: Long, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { changes.first { it.revision != revision } } != null
}

data class UnderlyingNetworkCandidate<T>(val network: T, val validated: Boolean, val wifi: Boolean)

/** Prefer working Wi-Fi, but do not require validation to bootstrap a locked VPN. */
fun <T> selectUnderlyingNetwork(candidates: List<UnderlyingNetworkCandidate<T>>, preferred: T?): T? {
    candidates.firstOrNull { it.network == preferred && it.validated }?.let { return it.network }
    return candidates.maxByOrNull { (if (it.validated) 2 else 0) + (if (it.wifi) 1 else 0) }?.network
}
