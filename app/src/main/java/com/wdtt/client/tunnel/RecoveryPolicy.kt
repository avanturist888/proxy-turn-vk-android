package com.wdtt.client

/** A successful statistics timer is not proof of a working data path. */
class RecoveryPolicy {
    private var failures = 0
    private var lastAttempt = Long.MIN_VALUE / 2

    fun record(reachable: Boolean, nowMs: Long): Boolean {
        if (reachable) { failures = 0; return false }
        failures++
        if (failures < 2 || nowMs - lastAttempt < 15_000L) return false
        failures = 0
        lastAttempt = nowMs
        return true
    }
}
