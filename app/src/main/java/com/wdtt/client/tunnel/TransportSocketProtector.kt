package com.wdtt.client

import android.net.LocalServerSocket
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
    @Volatile private var service: VpnService? = null
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
                        val protected = descriptors.size == 1 && service === owner &&
                            ParcelFileDescriptor.dup(descriptors.single()).use { fd -> owner.protect(fd.fd) }
                        it.outputStream.write(if (protected) 1 else 0)
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
