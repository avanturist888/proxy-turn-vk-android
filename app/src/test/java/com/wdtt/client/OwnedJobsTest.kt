package com.wdtt.client

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OwnedJobsTest {
    @Test fun cancelledReaderRemainsOwnedUntilItsChildRestoreCompletes() = runBlocking {
        val owned = OwnedJobs<String>()
        val entered = CompletableDeferred<Unit>()
        val releaseRestore = CompletableDeferred<Unit>()
        val reader = launch(start = CoroutineStart.LAZY) {
            launch {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { releaseRestore.await() }
                }
            }
            awaitCancellation()
        }
        owned.track(reader, "old-process")
        reader.start()
        try {
            entered.await()
            val first = owned.cancelSnapshot()
            assertEquals(listOf(reader to "old-process"), first)
            yield()
            assertFalse(reader.isCompleted)
            // A replacement/Stop still owns this work after its first waiter is cancelled.
            assertEquals(first, owned.cancelSnapshot())
            releaseRestore.complete(Unit)
            reader.join()
            assertTrue(owned.cancelSnapshot().isEmpty())
        } finally {
            releaseRestore.complete(Unit)
            reader.cancelAndJoin()
        }
    }

    @Test fun cancelledBeforeDispatchDoesNotLeakItsRegistryEntry() = runBlocking {
        val owned = OwnedJobs<String>()
        val reader = launch(start = CoroutineStart.LAZY) { fail("cancelled work must not run") }
        owned.track(reader, "never-started")
        assertEquals(listOf(reader to "never-started"), owned.cancelSnapshot())
        reader.join()
        assertTrue(reader.isCancelled)
        assertTrue(owned.cancelSnapshot().isEmpty())
    }
}
