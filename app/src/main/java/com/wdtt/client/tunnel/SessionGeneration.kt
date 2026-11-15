package com.wdtt.client

import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicLong

/** Stop and each accepted start invalidate every queued operation from the past. */
class SessionGeneration {
    private val value = AtomicLong()
    fun get(): Long = value.get()
    @Synchronized fun incrementAndGet(): Long = value.incrementAndGet()
    fun requireCurrent(expected: Long) {
        if (get() != expected) throw CancellationException("Connection session changed")
    }

    /** Publication and revocation share one monitor, without a check/write gap. */
    @Synchronized fun <T> withCurrent(expected: Long, publish: () -> T): T {
        requireCurrent(expected)
        return publish()
    }
}
