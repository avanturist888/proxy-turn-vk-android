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
        if (intent == null || intent.action == VpnService.SERVICE_INTERFACE) {
            TunnelControl.startAutomatic(applicationContext)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        TransportSocketProtector.stop(this)
        super.onDestroy()
    }
}
