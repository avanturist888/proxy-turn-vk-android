package com.wdtt.client

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializes the short interval in which a control WebView needs the underlay. */
class ControlPlaneBootstrap {
    private val mutex = Mutex()
    @Volatile var active = false
        private set

    suspend fun <T> run(
        release: suspend () -> Unit,
        restore: suspend () -> Unit,
        action: suspend () -> T,
    ): T = mutex.withLock {
        active = true
        try {
            release()
            action()
        } finally {
            withContext(NonCancellable) {
                try { restore() } finally { active = false }
            }
        }
    }
}
