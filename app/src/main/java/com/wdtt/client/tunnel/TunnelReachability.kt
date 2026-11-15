package com.wdtt.client

import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TunnelReachability {
    /** Unprotected application sockets: these probes must pass through the VPN. */
    suspend fun check(): Boolean = withContext(Dispatchers.IO) {
        listOf("1.1.1.1", "9.9.9.9").any { target ->
            runCatching { Socket().use { it.connect(InetSocketAddress(target, 443), 3000) }; true }.getOrDefault(false)
        }
    }
}
