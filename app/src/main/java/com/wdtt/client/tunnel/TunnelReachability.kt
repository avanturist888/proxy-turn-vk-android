package com.wdtt.client

import java.net.InetSocketAddress
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TunnelReachability {
    /** Bind to the VPN explicitly: its owner UID may otherwise use the underlay. */
    suspend fun check(context: Context): Boolean = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val vpn = cm.activeNetwork ?: return@withContext false
        if (cm.getNetworkCapabilities(vpn)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) {
            return@withContext false
        }
        listOf("1.1.1.1", "9.9.9.9").any { target ->
            runCatching {
                vpn.socketFactory.createSocket().use {
                    it.connect(InetSocketAddress(target, 443), 3000)
                }
                cm.activeNetwork == vpn &&
                    cm.getNetworkCapabilities(vpn)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }.getOrDefault(false)
        }
    }
}
