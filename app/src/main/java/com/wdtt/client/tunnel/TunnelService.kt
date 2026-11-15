package com.wdtt.client

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.LinkProperties
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest

private const val TUNNEL_NOTIFICATION_CHANNEL_ID = NotificationHelper.TUNNEL_CHANNEL_ID
private const val TUNNEL_NOTIFICATION_ID = 1
private const val RAW_WAKE_RESTART_THRESHOLD_MS = 60_000L

class TunnelService : Service() {
    private var automaticJob: Job? = null
    private var automaticStopJob: Job? = null
    private val recoveryRequest = RecoveryCommandGate()
    @Volatile private var automaticRequested = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var updateJob: Job? = null
    private var lastNotificationText: String? = null
    
    // Network Monitoring
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val underlyingNetwork = UnderlyingNetworkState()
    private var lastNetworkRecoveryAttemptMs = 0L
    private var screenStateReceiver: BroadcastReceiver? = null
    private var wakeRecoveryJob: Job? = null
    private var screenOffAtMs = 0L
    private var deviceWasInteractive = true
    private val activeNetworks = java.util.concurrent.ConcurrentHashMap.newKeySet<Network>()
    private val networkFingerprints = java.util.concurrent.ConcurrentHashMap<Network, String>()
    private var lastVpnReconnectAttemptMs = 0L
    private var wasOnWifi = false

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureTunnelChannel(this)
        createNotificationChannel()
        // Сразу берем лок при создании
        acquireWakeLock()
        setupNetworkCallback()
        setupScreenStateReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            if (RecoveryIntentStore.requested(this)) startAutomatic() else stopSelf()
            return if (RecoveryIntentStore.requested(this)) START_STICKY else START_NOT_STICKY
        }

        when (intent.action) {
            "START_AUTOMATIC" -> startAutomatic()
            "START", "START_FORCED", "START_SAVED" -> {
                val notification = createNotification("Запуск...")
                startPersistentForeground(notification)

                val appContext = applicationContext
                val automatic = intent.getBooleanExtra("automatic", false)
                var retiredLoop: Job? = null
                val commandRequest = if (automatic) intent.getLongExtra("recovery_request", -1L) else {
                    // Revoke the loop before the first settings read, not after it finishes.
                    val accepted = recoveryRequest.beginManual()
                    automaticRequested = false
                    retiredLoop = retireAutomaticLoop()
                    accepted
                }
                TunnelManager.scope.launch {
                    try {
                        retiredLoop?.join()
                        if (commandRequest != recoveryRequest.get()) return@launch
                        val store = SettingsStore(appContext)
                        SettingsStore.awaitMigrations(appContext)
                        val basePeer = intent.getStringExtra("peer")?.takeIf { it.isNotEmpty() } ?: store.peer.first()
                        val manualPortsEnabled = store.manualPortsEnabled.first()
                        val connectionMode = SettingsStore.normalizeConnectionMode(
                            intent.getStringExtra("connection_mode")?.takeIf { it.isNotEmpty() }
                                ?: store.connectionMode.first()
                        )
                        val isRawTun = connectionMode == SettingsStore.CONNECTION_MODE_RAWTUN
                        val serverDtlsPort = if (manualPortsEnabled) store.serverDtlsPort.first() else 56000
                        // Raw и так всегда без DTLS (инвариант архитектуры, не опция) — если этот
                        // переключатель читать безусловно, сохранённое значение от классического
                        // VPN-режима "утекает" в rawtun: лишний -notls в cmd и лишняя плашка
                        // "[СЕТЬ] Транспорт: без DTLS" на экране логов при активном Raw.
                        val noDtlsEnabled = !isRawTun && store.noDtlsEnabled.first()
                        val serverDirectPort = if (manualPortsEnabled) store.serverDirectPort.first() else 56002
                        val serverRawPort = if (manualPortsEnabled) store.serverRawPort.first() else 56003
                        val effectiveServerPort = when {
                            isRawTun -> serverRawPort
                            noDtlsEnabled -> serverDirectPort
                            else -> serverDtlsPort
                        }
                        val peerWithPort = if (basePeer.isBlank()) basePeer
                            else if (isRawTun || noDtlsEnabled) PeerAddress.withPort(basePeer, effectiveServerPort)
                            else PeerAddress.ensurePort(basePeer, effectiveServerPort)
                        val vkAnonPath = SettingsStore.normalizeVkAnonPath(
                            intent.getStringExtra("vk_anon_path")?.takeIf { it.isNotEmpty() }
                                ?: store.vkAnonPath.first()
                        )
                        val goDnsArg = intent.getStringExtra("go_dns_arg")?.takeIf { it.isNotEmpty() }
                            ?: store.resolveGoDnsArg()
                        val obfsMode = SettingsStore.normalizeObfsMode(
                            intent.getStringExtra("obfs_mode")?.takeIf { it.isNotEmpty() }
                                ?: store.obfsMode.first()
                        )
                        val socksPort = SettingsStore.normalizeSocksPort(
                            intent.getIntExtra("socks_port", 0).takeIf { it > 0 }
                                ?: store.socksPort.first()
                        )
                        val socksAuthEnabled = if (intent.hasExtra("socks_auth_enabled")) {
                            intent.getBooleanExtra("socks_auth_enabled", false)
                        } else {
                            store.socksAuthEnabled.first()
                        }
                        val socksUsername = intent.getStringExtra("socks_username")
                            ?: store.socksUsername.first()
                        val socksPassword = intent.getStringExtra("socks_password")
                            ?: store.socksPassword.first()
                        
                        val params = TunnelParams(
                            peer = peerWithPort,
                            vkHashes = intent.getStringExtra("vk_hashes")?.takeIf { it.isNotEmpty() } ?: store.vkHashes.first(),
                            secondaryVkHash = intent.getStringExtra("secondary_vk_hash")?.takeIf { it.isNotEmpty() } ?: store.secondaryVkHash.first(),
                            workersPerHash = intent.getIntExtra("workers_per_hash", 0).takeIf { it > 0 } ?: store.workersPerHash.first(),
                            port = intent.getIntExtra("port", 0).takeIf { it > 0 } ?: store.listenPort.first(),
                            sni = intent.getStringExtra("sni")?.takeIf { it.isNotEmpty() } ?: store.sni.first(),
                            connectionPassword = intent.getStringExtra("connection_password")?.takeIf { it.isNotEmpty() } ?: store.connectionPassword.first(),
                            protocol = intent.getStringExtra("protocol")?.takeIf { it.isNotEmpty() } ?: store.protocol.first(),
                            captchaMode = sanitizeCaptchaMode(intent.getStringExtra("captcha_mode")?.takeIf { it.isNotEmpty() } ?: store.captchaMode.first()),
                            captchaSolveMethod = intent.getStringExtra("captcha_solve_method")?.takeIf { it.isNotEmpty() } ?: store.captchaSolveMethod.first(),
                            vkAuthMode = intent.getStringExtra("vk_auth_mode")?.takeIf { it.isNotEmpty() } ?: store.vkAuthMode.first(),
                            vkAnonPath = vkAnonPath,
                            goDnsArg = goDnsArg,
                            obfsMode = obfsMode,
                            connectionMode = connectionMode,
                            socksPort = socksPort,
                            socksAuthEnabled = socksAuthEnabled,
                            socksUsername = socksUsername,
                            socksPassword = socksPassword,
                            noDtls = noDtlsEnabled,
                            turnTcp = store.turnTcpEnabled.first(),
                            detailedLogs = store.detailedLogs.first()
                        )
                        launch(Dispatchers.Main) {
                            if (commandRequest != recoveryRequest.get() ||
                                (intent.getBooleanExtra("automatic", false) && !automaticRequested)) return@launch
                            if (!automatic && !recoveryRequest.finishManual(commandRequest)) return@launch
                            startTunnel(
                                params,
                                forceStart = intent.action == "START_FORCED" || intent.action == "START_SAVED",
                                automaticAttempt = intent.getBooleanExtra("automatic", false)
                            )
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (!intent.getBooleanExtra("automatic", false)) {
                            launch(Dispatchers.Main) { if (commandRequest == recoveryRequest.get()) stopTunnel() }
                        } else {
                            TunnelManager.addNetworkLog("Saved server could not start; trying the next server")
                        }
                    }
                }
            }
            "STOP" -> stopTunnel()
            "DEPLOY_START" -> {
                val notification = createNotification("Установка на сервер...", "DEPLOY_CANCEL", "Отменить")
                startPersistentForeground(notification)
                acquireWakeLock()
            }
            "DEPLOY_CANCEL" -> {
                DeployManager.writeError("[!] ❌ Установка отменена пользователем")
                DeployManager.stopDeploy("error: Отменена пользователем")
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            "DEPLOY_STOP" -> {
                if (!TunnelManager.running.value) {
                    stopTunnel()
                } else {
                    updateNotification("Туннель активен")
                }
            }
        }
        return START_STICKY
    }

    private fun restoreTunnel() {
        val notification = createNotification("Восстановление соединения...")
        startPersistentForeground(notification)
        
        val appContext = applicationContext
        TunnelManager.scope.launch {
            try {
                val store = SettingsStore(appContext)
                SettingsStore.awaitMigrations(appContext)
                val basePeer = store.peer.first()
                val manualPortsEnabled = store.manualPortsEnabled.first()
                val connectionModeRestore = SettingsStore.normalizeConnectionMode(store.connectionMode.first())
                val isRawTunRestore = connectionModeRestore == SettingsStore.CONNECTION_MODE_RAWTUN
                val serverDtlsPort = if (manualPortsEnabled) store.serverDtlsPort.first() else 56000
                val noDtlsEnabled = !isRawTunRestore && store.noDtlsEnabled.first()
                val serverDirectPort = if (manualPortsEnabled) store.serverDirectPort.first() else 56002
                val serverRawPort = if (manualPortsEnabled) store.serverRawPort.first() else 56003
                val effectiveServerPort = when {
                    isRawTunRestore -> serverRawPort
                    noDtlsEnabled -> serverDirectPort
                    else -> serverDtlsPort
                }
                val peerWithPort = if (basePeer.isBlank()) basePeer
                    else if (isRawTunRestore || noDtlsEnabled) PeerAddress.withPort(basePeer, effectiveServerPort)
                    else PeerAddress.ensurePort(basePeer, effectiveServerPort)
                val params = TunnelParams(
                    peer = peerWithPort,
                    vkHashes = store.vkHashes.first(),
                    secondaryVkHash = store.secondaryVkHash.first(),
                    workersPerHash = store.workersPerHash.first(),
                    port = store.listenPort.first(),
                    sni = store.sni.first(),
                    connectionPassword = store.connectionPassword.first(),
                    captchaMode = sanitizeCaptchaMode(store.captchaMode.first()),
                    captchaSolveMethod = store.captchaSolveMethod.first(),
                    vkAuthMode = store.vkAuthMode.first(),
                    vkAnonPath = SettingsStore.normalizeVkAnonPath(store.vkAnonPath.first()),
                    goDnsArg = store.resolveGoDnsArg(),
                    obfsMode = SettingsStore.normalizeObfsMode(store.obfsMode.first()),
                    connectionMode = SettingsStore.normalizeConnectionMode(store.connectionMode.first()),
                    socksPort = SettingsStore.normalizeSocksPort(store.socksPort.first()),
                    socksAuthEnabled = store.socksAuthEnabled.first(),
                    socksUsername = store.socksUsername.first(),
                    socksPassword = store.socksPassword.first(),
                    noDtls = noDtlsEnabled,
                    turnTcp = store.turnTcpEnabled.first(),
                    detailedLogs = store.detailedLogs.first()
                )
                if (params.peer.isNotEmpty() && params.vkHashes.isNotEmpty()) {
                    launch(Dispatchers.Main) {
                        startTunnel(params)
                    }
                } else {
                    launch(Dispatchers.Main) {
                        stopTunnel()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    stopTunnel()
                }
            }
        }
    }

    private fun startTunnel(params: TunnelParams, forceStart: Boolean = false, automaticAttempt: Boolean = false) {
        if (!automaticAttempt) {
            TunnelManager.stop(force = true)
        }
        RecoveryIntentStore.setRequested(this, true)
        // Учитываем и невалидированный Wi‑Fi: иначе через секунду VALIDATED
        // выглядит как «переход на Wi‑Fi» и стоп по опции убивает ручной старт.
        wasOnWifi = isUnderlyingWifiActive() || isUnderlyingWifiPresent()
        updateNotification("Подключение...")
        acquireWakeLock()
        acquireWifiLock()

        // Подготавливаем CaptchaWebViewManager (не создаёт WebView — просто сохраняет контекст)
        // Вызываем всегда — дёшево, а WebView создаётся на лету при каждом запросе капчи
        CaptchaWebViewManager.onTunnelStart(applicationContext)

        TunnelManager.start(this, params, isSwitching = false, forceStart = forceStart,
            allowInteractiveAuth = !automaticAttempt)
        startStatsUpdater()
        if (!automaticAttempt) startAutomatic(params)
    }

    private fun startAutomatic(initialParams: TunnelParams? = null) {
        if (automaticJob?.isActive == true) return // Also covers OS enabling always-on on an active VPN.
        val loop = recoveryRequest.beginLoop() ?: return // A manual command is still loading settings.
        startPersistentForeground(createNotification("Automatic VPN recovery"))
        RecoveryIntentStore.setRequested(this, true)
        automaticRequested = true
        val retiredLoop = automaticStopJob
        automaticJob = TunnelManager.scope.launch {
            retiredLoop?.join()
            SettingsStore.awaitMigrations(applicationContext)
            val profiles = ProfilesStore(applicationContext)
            val selected = SettingsStore(applicationContext).currentProfileId.first()
            var firstAttempt = initialParams != null
            recoveryCycle@ while (isActive && automaticRequested) {
                if (!underlyingNetwork.current.available) {
                    // Requested connectivity outlives any individual process or network.
                    // Do not spend server budgets or leave old sockets alive while offline.
                    TunnelManager.stop(force = true)
                    firstAttempt = false
                    updateNotification("Ожидание сети...")
                    underlyingNetwork.awaitAvailable()
                }
                // Coalesce address, route and capability updates for the same transition.
                var networkSnapshot = underlyingNetwork.current
                while (underlyingNetwork.awaitChange(networkSnapshot.revision, 2_000)) {
                    networkSnapshot = underlyingNetwork.current
                }
                if (!networkSnapshot.available) continue@recoveryCycle
                val networkRevision = networkSnapshot.revision
                val candidates = profiles.profiles.first().filter { it.peer.isNotBlank() }
                val savedOrder = if (initialParams == null) candidates.map { it.id }
                    else recoveryProfileOrder(candidates.map { it.id }, selected)
                val targets: List<String?> = if (initialParams != null) listOf(null) + savedOrder
                    else savedOrder.ifEmpty { listOf(null) }
                for (id in targets) {
                    if (!isActive || !automaticRequested) break
                    val request = recoveryRequest.nextAttempt(loop) ?: return@launch
                    val explicitFirstAttempt = firstAttempt
                    try {
                        if (!firstAttempt) {
                            TunnelManager.stop(force = true)
                            if (id == null && initialParams != null) {
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    startTunnel(initialParams, forceStart = true, automaticAttempt = true)
                                }
                            } else {
                                if (id != null) profiles.applyProfile(applicationContext, id)
                                startService(Intent(applicationContext, TunnelService::class.java).apply {
                                    action = "START_SAVED"
                                    putExtra("automatic", true)
                                    putExtra("recovery_request", request)
                                })
                            }
                        }
                        firstAttempt = false
                        val budget = RecoveryBudget(android.os.SystemClock.elapsedRealtime(),
                            if (explicitFirstAttempt) 180_000L else 60_000L,
                            if (explicitFirstAttempt) 120_000L else 20_000L)
                        var connected = false
                        while (isActive && automaticRequested) {
                            if (underlyingNetwork.awaitChange(networkRevision, 1000)) break
                            if (budget.expired(android.os.SystemClock.elapsedRealtime(),
                                    TunnelManager.controlPlaneBootstrapInProgress)) break
                            if (!TunnelManager.controlPlaneBootstrapInProgress && TunnelManager.hasSessionVpn() &&
                                TunnelReachability.check(applicationContext)) { connected = true; break }
                        }
                        val policy = RecoveryPolicy()
                        var busyBudget: RecoveryBudget? = null
                        while (isActive && automaticRequested && connected) {
                            if (underlyingNetwork.awaitChange(networkRevision, 5000)) break
                            val now = android.os.SystemClock.elapsedRealtime()
                            val busy = TunnelManager.isConnecting.value || TunnelManager.isReconnecting.value ||
                                TunnelManager.controlPlaneBootstrapInProgress
                            if (busy) {
                                val limit = busyBudget ?: RecoveryBudget(now).also { busyBudget = it }
                                if (limit.expired(now, TunnelManager.controlPlaneBootstrapInProgress)) break
                            } else {
                                busyBudget = null
                                if (policy.record(TunnelReachability.check(applicationContext), now)) break
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        TunnelManager.addNetworkLog("Saved server unavailable; trying the next server")
                    } finally {
                        if (recoveryRequest.get() == request) TunnelManager.stop(force = true)
                    }
                    if (underlyingNetwork.current.revision != networkRevision) {
                        TunnelManager.addNetworkLog("[СЕТЬ] Сеть изменилась. Запускаем новую попытку подключения.")
                        continue@recoveryCycle
                    }
                }
                // Bad credentials/interactive challenges do not create repeated prompt loops.
                if (automaticRequested) underlyingNetwork.awaitChange(networkRevision, 60_000)
            }
        }
    }

    /** A replaced command may be cancelled; its retirement barrier must survive it. */
    private fun retireAutomaticLoop(): Job {
        val previousStop = automaticStopJob
        val previousLoop = automaticJob
        automaticJob = null
        previousLoop?.cancel()
        return TunnelManager.scope.launch {
            previousStop?.join()
            previousLoop?.join()
        }.also { automaticStopJob = it }
    }

    private fun stopTunnel(explicit: Boolean = true) {
        if (explicit) RecoveryIntentStore.setRequested(this, false)
        recoveryRequest.invalidate()
        automaticRequested = false
        retireAutomaticLoop()
        updateJob?.cancel()

        // Уничтожаем текущий WebView (если капча решается) и чистим контекст
        CaptchaWebViewManager.onTunnelStop()

        TunnelManager.stop(force = true)
        releaseWakeLock()
        releaseWifiLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(TUNNEL_NOTIFICATION_ID)
        TunnelWidgetProvider.updateWidgetState(applicationContext, false, "Нажмите для подключения")
        QuickToggleTileService.requestTileUpdate(applicationContext)
        AppShortcuts.refreshAsync(applicationContext)
        stopSelf()
    }

    private fun setupNetworkCallback() {
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        activeNetworks.clear()
        connectivityManager?.allNetworks?.filter { network ->
            val caps = connectivityManager?.getNetworkCapabilities(network)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }?.let { activeNetworks.addAll(it) }
        noteUnderlyingNetworkChange()
        
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                activeNetworks.add(network)
                rememberNetworkFingerprint(network)
                checkWifiStopOnTransition()
                noteUnderlyingNetworkChange()
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                activeNetworks.remove(network)
                networkFingerprints.remove(network)
                checkWifiStopOnTransition()
                noteUnderlyingNetworkChange()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                if (network !in activeNetworks) return
                val prev = networkFingerprints[network]
                val next = rememberNetworkFingerprint(network)
                checkWifiStopOnTransition()
                if (prev != null && prev != next) {
                    noteUnderlyingNetworkChange()
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                super.onLinkPropertiesChanged(network, linkProperties)
                if (network !in activeNetworks) return
                checkWifiStopOnTransition()
                noteUnderlyingNetworkChange()
            }
        }

        // ВАЖНО: Слушаем только реальные (не VPN) сети с доступом в интернет.
        // Иначе интерфейс VPN (tun0) считается активной сетью, и при "Режиме полёта" activeNetworks не падает до 0.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
            
        connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        wasOnWifi = isUnderlyingWifiPresent()
    }

    /**
     * Wi‑Fi для stop-on-Wi‑Fi: только валидированная сеть.
     * Иначе на OnePlus Wi‑Fi уже в activeNetworks до переключения трафика.
     */
    private fun isUnderlyingWifiActive(): Boolean {
        val cm = connectivityManager ?: return false
        return activeNetworks.any { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@any false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }

    /** Wi‑Fi уже всплыл, но ещё может быть без VALIDATED (момент перед стопом). */
    private fun isUnderlyingWifiPresent(): Boolean {
        val cm = connectivityManager ?: return false
        return activeNetworks.any { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@any false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    private fun networkCapabilityFingerprint(caps: NetworkCapabilities): String {
        val transports = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cell")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("eth")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
        }.joinToString("+")
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        return "$transports|validated=$validated|internet=$internet"
    }

    private fun rememberNetworkFingerprint(network: Network): String {
        val cm = connectivityManager ?: return ""
        val caps = cm.getNetworkCapabilities(network) ?: return ""
        val fingerprint = networkCapabilityFingerprint(caps)
        networkFingerprints[network] = fingerprint
        return fingerprint
    }

    private fun activeUnderlyingFingerprint(): String {
        val cm = connectivityManager ?: return ""
        return activeNetworks.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
            "$network:${networkCapabilityFingerprint(caps)}:${cm.getLinkProperties(network)}"
        }.sorted().joinToString("|")
    }

    private fun noteUnderlyingNetworkChange() {
        underlyingNetwork.update(activeUnderlyingFingerprint())
    }

    private fun checkWifiStopOnTransition() {
        val nowOnWifi = isUnderlyingWifiPresent()
        val transitionedToWifi = nowOnWifi && !wasOnWifi
        wasOnWifi = nowOnWifi
        if (!transitionedToWifi || !TunnelManager.running.value) return

        TunnelManager.scope.launch {
            if (FORCE_FULL_TUNNEL || !SettingsStore(applicationContext).stopOnWifi.first()) return@launch
            Log.d("TunnelService", "Подключились к Wi‑Fi — отключаем туннель по настройке")
            TunnelManager.addNetworkLog("[СЕТЬ] Wi‑Fi: туннель отключён (опция «Отключать на Wi‑Fi»)")
            launch(Dispatchers.Main) { stopTunnel() }
        }
    }

    private fun hasUsableUnderlyingNetwork(): Boolean {
        val cm = connectivityManager ?: return false
        return activeNetworks.any { network ->
            cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
    }

    private fun setupScreenStateReceiver() {
        deviceWasInteractive = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        if (!deviceWasInteractive) screenOffAtMs = System.currentTimeMillis()
        screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        deviceWasInteractive = false
                        screenOffAtMs = System.currentTimeMillis()
                        wakeRecoveryJob?.cancel()
                        wakeRecoveryJob = null
                        TunnelManager.addNetworkLog("[СОН] Экран выключен. Не перезапускаем VPN из-за фоновых событий сети.")
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        deviceWasInteractive = true
                        val now = System.currentTimeMillis()
                        val screenOffDurationMs = if (screenOffAtMs > 0L) now - screenOffAtMs else 0L
                        screenOffAtMs = 0L
                        scheduleWakeRecovery(screenOffDurationMs)
                    }
                }
            }
        }
        registerReceiver(screenStateReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
    }

    private fun scheduleWakeRecovery(screenOffDurationMs: Long) {
        wakeRecoveryJob?.cancel()
        val restartRawTransport = TunnelManager.isRawTunModeActive() &&
            screenOffDurationMs >= RAW_WAKE_RESTART_THRESHOLD_MS
        wakeRecoveryJob = TunnelManager.scope.launch {
            if (!TunnelManager.running.value) return@launch
            if (restartRawTransport) {
                TunnelManager.addNetworkLog("[СОН] RAW был в фоне ${screenOffDurationMs / 1000} сек. Обновляем транспортные потоки.")
                val validationDeadline = System.currentTimeMillis() + 20_000L
                while (!hasUsableUnderlyingNetwork() && System.currentTimeMillis() < validationDeadline) {
                    if (!deviceWasInteractive || !TunnelManager.running.value) return@launch
                    delay(500)
                }
                if (!deviceWasInteractive || !TunnelManager.running.value ||
                    !hasUsableUnderlyingNetwork()) return@launch
                delay(2_000)
                if (!deviceWasInteractive || !TunnelManager.running.value) return@launch
                lastNetworkRecoveryAttemptMs = System.currentTimeMillis()
                updateNotification("Восстановление после сна...")
                TunnelManager.restartTransport("пробуждение RAW после сна", force = true)
                return@launch
            }
            TunnelManager.addNetworkLog("[СОН] Устройство проснулось; даём текущему VPN восстановиться без перезапуска.")
            delay(30_000)
            if (!deviceWasInteractive || !TunnelManager.running.value || !hasUsableUnderlyingNetwork()) return@launch
            if (TunnelManager.hasRecentTransportActivity(45_000L)) {
                TunnelManager.addNetworkLog("[СОН] VPN подал свежие признаки жизни после пробуждения.")
                return@launch
            }
            val now = System.currentTimeMillis()
            if (now - lastNetworkRecoveryAttemptMs < 120_000L) return@launch
            lastNetworkRecoveryAttemptMs = now
            updateNotification("Восстановление после сна...")
            TunnelManager.addNetworkLog("[СОН] VPN не ожил после пробуждения. Мягко переподключаем транспорт.")
            TunnelManager.restartTransport()
        }
    }

    private fun sanitizeCaptchaMode(mode: String?): String {
        return when (mode?.lowercase()) {
            "auto" -> "auto"
            "rjs" -> "rjs"
            "wv" -> "wv"
            else -> "auto"
        }
    }


    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "wdtt:tunnel_cpu"
        ).apply { 
            setReferenceCounted(false)
            acquire() 
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        
        // Используем WIFI_MODE_FULL_LOW_LATENCY для Android 10+, 
        // это предотвращает отключение радиомодуля при выключенном экране
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        
        wifiLock = wm.createWifiLock(mode, "wdtt:wifi_perf").apply { 
            setReferenceCounted(false)
            acquire() 
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }

    private fun releaseWifiLock() {
        if (wifiLock?.isHeld == true) {
            wifiLock?.release()
        }
        wifiLock = null
    }

    private fun startStatsUpdater() {
        updateJob?.cancel()
        updateJob = TunnelManager.scope.launch(Dispatchers.Main) {
            // Сторож следит за пропажей уже поднятого VPN-интерфейса, а не за его
            // отсутствием во время подключения (капча, VK-креды и т.д.).
            var wasEverUp = false
            delay(1000)

            val reconnectObserver = launch {
                TunnelManager.isReconnecting.collectLatest { reconnecting ->
                    if (reconnecting && TunnelManager.running.value) {
                        updateNotification("Переподключение...")
                    }
                }
            }

            try {
                while (isActive) {
                    if (automaticRequested && !TunnelManager.running.value) {
                        updateNotification("Ожидание доступного сервера")
                        delay(2000)
                        continue
                    }
                    if (!TunnelManager.running.value) {
                        if (TunnelManager.isReconnecting.value || TunnelManager.transportRestartInProgress) {
                            delay(2000)
                            continue
                        }
                        // Старт ещё идёт (вход по аккаунту VK, DNS, капча…) — FGS нельзя убивать,
                        // иначе Android снимает уведомление и усыпляет приложение.
                        if (TunnelManager.isConnecting.value) {
                            val connectingText = TunnelManager.stats.value.trim().ifEmpty { "Подключение..." }
                            updateNotification(connectingText)
                            delay(2000)
                            continue
                        }
                        // Туннель полностью остановлен (не на паузе) — убиваем сервис
                        stopSelf()
                        break
                    }
                    if (TunnelManager.running.value && !TunnelManager.isSocksModeActive() &&
                        !TunnelManager.controlPlaneBootstrapInProgress) {
                        val helper = WireGuardHelper(applicationContext)
                        when (helper.watchdogState()) {
                            WireGuardHelper.WatchdogState.UP -> wasEverUp = true
                            WireGuardHelper.WatchdogState.DISABLED_BY_EMPTY_WHITELIST -> Unit
                            WireGuardHelper.WatchdogState.DOWN -> {
                                if (wasEverUp) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastVpnReconnectAttemptMs >= 15_000) {
                                        lastVpnReconnectAttemptMs = now
                                        Log.w(
                                            "TunnelService",
                                            "VPN-интерфейс пропал — пробуем переподключение"
                                        )
                                        updateNotification("Переподключение VPN...")
                                        TunnelManager.reconnectAll("пропал VPN-интерфейс")
                                        wasEverUp = false
                                    }
                                }
                            }
                        }
                    }
                    if (!TunnelManager.isReconnecting.value) {
                        updateNotification(buildTunnelNotificationText())
                    }
                    delay(2000)
                }
            } finally {
                reconnectObserver.cancel()
            }
        }
    }

    private fun buildTunnelNotificationText(): String {
        val socks = TunnelManager.activeSocksListenAddress()
        if (socks != null) {
            val statsText = TunnelManager.stats.value.trim()
            return when {
                statsText.isEmpty() || statsText == "Ожидание данных..." || statsText.startsWith("SOCKS") ->
                    "SOCKS $socks"
                else -> "$statsText · $socks"
            }
        }
        val statsText = TunnelManager.stats.value.trim()
        return when {
            statsText.isEmpty() -> "Туннель активен"
            statsText == "Ожидание данных..." -> "Туннель активен"
            else -> statsText
        }
    }

    private fun createNotificationChannel() {
        NotificationHelper.ensureTunnelChannel(this)
    }

    private fun createNotification(text: String, actionName: String = "STOP", actionTitle: String = "Отключить"): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val stopIntent = PendingIntent.getService(
            this, if (actionName == "STOP") 1 else 2,
            Intent(this, TunnelService::class.java).apply { action = actionName },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, TUNNEL_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("qWDTT")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_connected)
            .setOngoing(true)
            .setLocalOnly(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_stop, actionTitle, stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFAULT)
            // ВАЖНО: Делаем уведомление публичным (видимым на локскрине)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Категория SERVICE помогает системе понять важность
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(true)
            .setUsesChronometer(false)
            .setWhen(System.currentTimeMillis())
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun startPersistentForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(TUNNEL_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(TUNNEL_NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        if (lastNotificationText == text) return
        lastNotificationText = text
        val notification = createNotification(text)
        // Обновляем через startForeground — так надёжнее на Android 13+ и китайских прошивках.
        startPersistentForeground(notification)
        TunnelWidgetProvider.updateWidgetState(applicationContext, TunnelManager.running.value, text)
        QuickToggleTileService.requestTileUpdate(applicationContext)
        AppShortcuts.refreshAsync(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeRecoveryJob?.cancel()
        screenStateReceiver?.let { unregisterReceiver(it) }
        networkCallback?.let {
            connectivityManager?.unregisterNetworkCallback(it)
        }
        stopTunnel(explicit = false)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
