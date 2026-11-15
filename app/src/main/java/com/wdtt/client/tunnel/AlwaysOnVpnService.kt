package com.wdtt.client

import android.content.Intent
import android.net.VpnService
import com.wireguard.android.backend.GoBackend

/** The sole Android VPN entry point, including system always-on starts. */
class AlwaysOnVpnService : GoBackend.VpnService() {
    override fun onCreate() {
        super.onCreate()
        TransportSocketProtector.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val systemStart = intent?.action == VpnService.SERVICE_INTERFACE ||
            (android.os.Build.VERSION.SDK_INT >= 29 && isAlwaysOn)
        if (systemStart) RecoveryIntentStore.setRequested(this, true)
        val requested = RecoveryIntentStore.requested(this)
        if (shouldResumeConnection(systemStart, intent == null, requested)) {
            TunnelControl.startAutomatic(applicationContext)
        }
        if (intent == null && !requested) stopSelf()
        return if (requested) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        TransportSocketProtector.stop(this)
    }
}
