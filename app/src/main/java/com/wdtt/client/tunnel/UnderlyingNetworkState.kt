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

    /** An unused network changing must not retire a working transport. */
    suspend fun awaitUnavailable(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { changes.first { !it.available } } != null

    suspend fun awaitChange(revision: Long, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { changes.first { it.revision != revision } } != null
}
