package com.wdtt.client

import android.net.LocalServerSocket
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Process
import android.os.ParcelFileDescriptor
import android.system.Os
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Per-socket transport exception; never excludes the application's UID. */
object TransportSocketProtector {
    // A WireGuard reconfiguration recreates VpnService. Existing Go processes
    // must reconnect to the new owner using the same private endpoint name.
    private val endpointName = "wdtt_protect_${UUID.randomUUID()}"
    private val owners = OwnerLifetime<VpnService>()
    private var service: VpnService?
        get() = owners.current
        set(value) { owners.replace(value) }
    @Volatile private var socketName: String? = null
    private var server: LocalServerSocket? = null
    private var listener: Job? = null

    @Synchronized fun start(owner: VpnService) {
        stopCurrent()
        val name = endpointName
        val socket = LocalServerSocket(name)
        service = owner
        server = socket
        socketName = name
        listener = TunnelManager.scope.launch(Dispatchers.IO) {
            while (service === owner) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use {
                    it.soTimeout = 3000
                    var descriptors = emptyArray<java.io.FileDescriptor>()
                    try {
                        if (it.peerCredentials.uid != Process.myUid()) return@use
                        if (it.inputStream.read() != 1) return@use
                        descriptors = it.ancillaryFileDescriptors ?: emptyArray()
                        val result = if (descriptors.size != 1 || service !== owner) 0 else {
                            val network = selectNetwork(owner)
                            if (network == null) 2 else ParcelFileDescriptor.dup(descriptors.single()).use { fd ->
                                if (!owner.protect(fd.fd)) 4 else {
                                    try {
                                        // Protect skips the VPN; bind also selects the fresh physical network.
                                        // Neither operation changes routing for other application sockets.
                                        network.bindSocket(fd.fileDescriptor)
                                        if (service === owner && isUnderlyingNetwork(owner, network)) 1 else 5
                                    } catch (_: Exception) { 3 }
                                }
                            }
                        }
                        it.outputStream.write(result)
                        it.outputStream.flush()
                    } catch (_: Exception) {
                        // Reject this socket without losing the protection listener.
                    } finally {
                        descriptors.forEach { fd -> runCatching { Os.close(fd) } }
                    }
                }
            }
        }
    }

    fun name(): String? = socketName

    fun ownerIdentity(): VpnService? = service

    private fun isUnderlyingNetwork(owner: VpnService, network: Network): Boolean {
        val caps = owner.getSystemService(ConnectivityManager::class.java).getNetworkCapabilities(network)
            ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun selectNetwork(owner: VpnService): Network? {
        val cm = owner.getSystemService(ConnectivityManager::class.java)
        val choices = cm.allNetworks.sortedBy { it.networkHandle }.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!isUnderlyingNetwork(owner, network)) return@mapNotNull null
            UnderlyingNetworkCandidate(network,
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        }
        return selectUnderlyingNetwork(choices, cm.activeNetwork)
    }

    /** Completion is published only after GoBackend's onDestroy resets its future. */
    suspend fun awaitOwnerReleased(owner: VpnService) {
        owners.awaitReleased(owner)
    }

    @Synchronized fun stop(owner: VpnService) {
        if (service === owner) stopCurrent()
    }

    private fun stopCurrent() {
        socketName = null
        service = null
        runCatching { server?.close() }
        server = null
        listener?.cancel()
        listener = null
    }
}
