package com.wdtt.client

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Identity, rather than a reusable socket name, defines service retirement. */
class OwnerLifetime<T : Any> {
    @Volatile var current: T? = null
        private set
    fun replace(owner: T?) { current = owner }
    suspend fun awaitReleased(owner: T, timeoutMs: Long = 3000) {
        withTimeout(timeoutMs) {
            while (current === owner) delay(25)
        }
    }
}
