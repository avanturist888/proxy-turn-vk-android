package com.wdtt.client

/** Monotonic hard limits; control activity never extends an attempt. */
class RecoveryBudget(private val startedAt: Long, private val attemptMs: Long = 60_000L,
                     private val controlMs: Long = 20_000L) {
    private var controlStartedAt: Long? = null
    fun expired(now: Long, controlActive: Boolean): Boolean {
        if (controlActive && controlStartedAt == null) controlStartedAt = now
        if (!controlActive) controlStartedAt = null
        return now - startedAt >= attemptMs ||
            (controlStartedAt?.let { now - it >= controlMs } == true)
    }
}

/** Keep the explicit/manual parameters first, then cycle saved profiles after it. */
fun recoveryProfileOrder(ids: List<String>, selected: String): List<String> {
    val index = ids.indexOf(selected)
    return if (index < 0) ids else ids.drop(index + 1) + ids.take(index)
}

fun shouldResumeConnection(systemStart: Boolean, stickyRestart: Boolean, requested: Boolean): Boolean =
    systemStart || (stickyRestart && requested)
