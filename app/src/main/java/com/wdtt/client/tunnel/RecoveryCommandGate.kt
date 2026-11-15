package com.wdtt.client

/** Manual settings loading owns its command until it can start a new recovery loop. */
class RecoveryCommandGate {
    private var request = 0L
    private var loop = 0L
    private var manualPending = false

    @Synchronized fun get(): Long = request

    @Synchronized fun beginManual(): Long {
        loop++
        manualPending = true
        return ++request
    }

    @Synchronized fun finishManual(expected: Long): Boolean {
        if (!manualPending || request != expected) return false
        manualPending = false
        return true
    }

    @Synchronized fun beginLoop(): Long? = if (manualPending) null else ++loop

    @Synchronized fun nextAttempt(owner: Long): Long? {
        if (manualPending || loop != owner) return null
        return ++request
    }

    @Synchronized fun invalidate() {
        loop++
        manualPending = false
        request++
    }
}
