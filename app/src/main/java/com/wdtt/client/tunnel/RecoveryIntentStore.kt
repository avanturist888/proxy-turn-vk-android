package com.wdtt.client

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Survives process death, but an explicit Stop prevents an accidental sticky start. */
object RecoveryIntentStore {
    private val requestedState = MutableStateFlow(false)
    private val observable = requestedState.asStateFlow()

    @Synchronized fun requested(context: Context): Boolean =
        context.getSharedPreferences("recovery", Context.MODE_PRIVATE)
            .getBoolean("requested", false).also { requestedState.value = it }

    fun observe(context: Context): StateFlow<Boolean> {
        requested(context)
        return observable
    }

    @Synchronized fun setRequested(context: Context, requested: Boolean) {
        context.getSharedPreferences("recovery", Context.MODE_PRIVATE).edit()
            .putBoolean("requested", requested).commit()
        requestedState.value = requested
    }
}
