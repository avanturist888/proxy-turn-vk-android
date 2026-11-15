package com.wdtt.client

import kotlinx.coroutines.Job

/** Cancellation requests retirement; only completion releases ownership. */
class OwnedJobs<T> {
    private val jobs = mutableMapOf<Job, T>()

    @Synchronized fun track(job: Job, value: T) {
        jobs[job] = value
        job.invokeOnCompletion { synchronized(this) { jobs.remove(job) } }
    }

    fun cancelSnapshot(): List<Pair<Job, T>> {
        val entries = synchronized(this) { jobs.map { it.key to it.value } }
        entries.forEach { it.first.cancel() }
        return entries
    }
}
