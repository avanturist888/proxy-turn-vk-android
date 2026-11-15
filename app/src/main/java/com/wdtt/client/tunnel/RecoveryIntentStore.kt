package com.wdtt.client

import android.content.Context

/** Survives process death, but an explicit Stop prevents an accidental sticky start. */
object RecoveryIntentStore {
    fun requested(context: Context): Boolean = context.getSharedPreferences("recovery", Context.MODE_PRIVATE)
        .getBoolean("requested", false)
    fun setRequested(context: Context, requested: Boolean) {
        context.getSharedPreferences("recovery", Context.MODE_PRIVATE).edit()
            .putBoolean("requested", requested).commit()
    }
}
