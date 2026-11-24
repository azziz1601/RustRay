package io.rustray.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.xrayrust.mobile.XrayCoreException
import org.xrayrust.mobile.XrayNetworkRebindResult
import org.xrayrust.mobile.XrayTunInterface
import org.xrayrust.mobile.XrayUnderlyingNetwork
import org.xrayrust.mobile.XrayVpnRuntimeSnapshot
import org.xrayrust.mobile.XrayVpnService

class AppVpnService : XrayVpnService() {

    private var currentProfile: Profile? = null
    private val connectGeneration = AtomicLong(0L)
    @Volatile
    private var activeSettings: AppSettings = AppSettings()

    private val notifHandler = Handler(Looper.getMainLooper())
    private var notifLastUp = -1L
    private var notifLastDown = -1L
    private var notifLastAt = 0L
    private var tickCount = 0
    private val trafficTicker = object : Runnable {
        override fun run() {
            if (VpnState.phase != VpnPhase.CONNECTED) {
                return
            }
            val pm = getSystemService(PowerManager::class.java)
            val screenOn = pm?.isInteractive ?: true
            if (screenOn) {
                tickCount++
                publishTrafficSnapshot(updateNotif = activeSettings.notificationTraffic && (tickCount % 2 == 0))
            }
            notifHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        VpnState.init(this)
        EventLog.init(this)
        instance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> handleConnect(intent.getStringExtra(EXTRA_PROFILE_ID))
            ACTION_RECONNECT -> handleReconnect(intent.getStringExtra(EXTRA_PROFILE_ID))
            ACTION_STOP -> handleStop()
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun handleReconnect(profileId: String?) {
        connectGeneration.incrementAndGet()
        stopTrafficTicker()
        val targetId = profileId ?: currentProfile?.id ?: ProfileStore(this).selectedId()
        EventLog.add("Reconnecting tunnel…")
        runCatching { stopXrayTunnel() }
        RustSshBridge.stop()
        VpnState.stopped()
        currentProfile = null
        syncExternalSurfaces()
        handleConnect(targetId)
    }

    private fun handleConnect(profileId: String?) {
        if (VpnState.phase == VpnPhase.CONNECTED || VpnState.phase == VpnPhase.CONNECTING) {
            startForegroundCompat(
                buildNotification(
                    title = "RustRay · Connected",
                    text = VpnState.profileName.ifBlank { "VPN connected" },
                    ongoing = true,
                    withDisconnect = true,
                ),
            )
            return
        }
        // Satisfy the startForegroundService contract before any validation can fail.
        startForegroundCompat(
            buildNotification(
                title = "RustRay · Preparing",
                text = "Starting VPN…",
                ongoing = true,
                withDisconnect = true,
            ),
        )
        val profile = ProfileStore(this).find(profileId)
        if (profile == null) {
            fail("Profile not found")
            return
        }
        if (profile.isExpired()) {
            fail(getString(R.string.err_config_expired))
            return
        }
        val settings = SettingsStore(this).load()
        activeSettings = settings
        val attemptGen = connectGeneration.incrementAndGet()
        currentProfile = profile
        VpnState.connecting(profile)
        syncExternalSurfaces()
        val viaProxy = if ((profile.protocol == Protocol.SSH || profile.v2rayCustomPayload) &&
            !profile.rrLockPayload &&
            profile.sshProxy.isNotBlank()
        ) {
            " via ${profile.sshProxy}"
        } else {
            ""
        }
        EventLog.add("Starting ${profile.protocol.label} profile '${profile.name}' -> ${profile.endpoint()}$viaProxy")
        EventLog.add(settingsSummary(settings))
        if (settings.shizukuAutoCaptivePortal) {
            ShizukuCaptivePortal.applyOnVpnStartAsync(this)
        }
        startForegroundCompat(progressNotification("Connecting…", profile))

        if (profile.protocol == Protocol.SSH) {
            Thread({
                val socksPort = try {
                    RustSshBridge.start(profile, settings, this)
                } catch (error: Throwable) {
                    if (connectGeneration.get() != attemptGen) {
                        return@Thread
                    }
                    fail("SSH error: ${safeMessage(error)}")
                    return@Thread
                }
                if (connectGeneration.get() != attemptGen ||
                    VpnState.phase != VpnPhase.CONNECTING ||
                    currentProfile?.id != profile.id
                ) {
                    RustSshBridge.stop()
                    return@Thread
                }
                EventLog.add("SSH ready on 127.0.0.1:$socksPort (UDPGW ${profile.sshUdpgwPort}, pool ${profile.sshPoolSize})")
                val config = try {
                    ConfigBuilder.configForSsh(socksPort, settings)
                } catch (error: Throwable) {
                    RustSshBridge.stop()
                    if (connectGeneration.get() == attemptGen) {
                        fail("Cannot build SSH config: ${safeMessage(error)}")
                    }
                    return@Thread
                }
                if (connectGeneration.get() != attemptGen) {
                    RustSshBridge.stop()
                    return@Thread
                }
                try {
                    startXrayTunnel(
                        configJson = config,
                        tunRuntimeProfile = settings.runtimeProfile.toSdk(),
                        tunMtu = settings.tunMtu,
                    )
                } catch (error: Throwable) {
                    RustSshBridge.stop()
                    if (connectGeneration.get() == attemptGen) {
                        fail("Cannot start tunnel: ${safeMessage(error)}")
                    }
                }
            }, "rustray-ssh-start").apply { isDaemon = true }.start()
            return
        }

        val resolved = try {
            RrConfigCodec.resolveForConnect(profile)
        } catch (error: Throwable) {
            fail("Cannot resolve config: ${safeMessage(error)}")
            return
        }

        if (resolved.network == Network.HTTPUPGRADE && resolved.v2rayCustomPayload) {
            Thread({
                val bridgePort = try {
                    RustSshBridge.startPayloadBridge(resolved, this)
                } catch (error: Throwable) {
                    if (connectGeneration.get() != attemptGen) {
                        return@Thread
                    }
                    fail("Payload bridge error: ${safeMessage(error)}")
                    return@Thread
                }
                if (connectGeneration.get() != attemptGen ||
                    VpnState.phase != VpnPhase.CONNECTING ||
                    currentProfile?.id != profile.id
                ) {
                    RustSshBridge.stop()
                    return@Thread
                }
                EventLog.add("HTTPUpgrade custom payload bridge ready on 127.0.0.1:$bridgePort")
                val config = try {
                    ConfigBuilder.configForV2rayPayload(resolved, bridgePort, settings)
                } catch (error: Throwable) {
                    RustSshBridge.stop()
                    if (connectGeneration.get() == attemptGen) {
                        fail("Cannot build config: ${safeMessage(error)}")
                    }
                    return@Thread
                }
                if (connectGeneration.get() != attemptGen) {
                    RustSshBridge.stop()
                    return@Thread
                }
                try {
                    startXrayTunnel(
                        configJson = config,
                        tunRuntimeProfile = settings.runtimeProfile.toSdk(),
                        tunMtu = settings.tunMtu,
                    )
                } catch (error: Throwable) {
                    RustSshBridge.stop()
                    if (connectGeneration.get() == attemptGen) {
                        fail("Cannot start tunnel: ${safeMessage(error)}")
                    }
                }
            }, "rustray-v2ray-payload").apply { isDaemon = true }.start()
            return
        }

        val config = try {
            ConfigBuilder.configFor(resolved, settings)
        } catch (error: Throwable) {
            fail("Cannot build config: ${safeMessage(error)}")
            return
        }

        try {
            startXrayTunnel(
                configJson = config,
                tunRuntimeProfile = settings.runtimeProfile.toSdk(),
                tunMtu = settings.tunMtu,
            )
        } catch (error: Throwable) {
            fail("Cannot start tunnel: ${safeMessage(error)}")
        }
    }

    override fun buildTunnel(): Builder {
        val settings = activeSettings
        val builder = Builder()
            .setSession("RustRay")
            .addAddress(XrayTunInterface.IPV4_ADDRESS, XrayTunInterface.IPV4_PREFIX_LENGTH)
            .addRoute("0.0.0.0", 0)

        if (settings.ipv6Route) {
            builder
                .addAddress(XrayTunInterface.IPV6_ADDRESS, XrayTunInterface.IPV6_PREFIX_LENGTH)
                .addRoute("::", 0)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            builder.setMetered(false)
        }

        val targetApps = settings.splitTunnelApps.filter { it.isNotBlank() && it != packageName }
        when (settings.splitTunnelMode) {
            SplitTunnelMode.ONLY_SELECTED -> {
                var added = 0
                for (pkg in targetApps) {
                    if (runCatching { builder.addAllowedApplication(pkg) }.isSuccess) {
                        added++
                    }
                }
                if (added == 0) {
                    runCatching { builder.addDisallowedApplication(packageName) }
                }
            }

            SplitTunnelMode.BYPASS -> {
                runCatching { builder.addDisallowedApplication(packageName) }
                for (pkg in targetApps) {
                    runCatching { builder.addDisallowedApplication(pkg) }
                }
            }

            SplitTunnelMode.ALL -> {
                runCatching { builder.addDisallowedApplication(packageName) }
            }
        }
        return builder
    }

    private fun handleStop() {
        connectGeneration.incrementAndGet()
        if (VpnState.phase == VpnPhase.STOPPED || VpnState.phase == VpnPhase.ERROR) {
            stopForegroundCompat()
            stopSelf()
            return
        }
        stopTrafficTicker()
        VpnState.stopping()
        syncExternalSurfaces()
        EventLog.add("Stopping tunnel")
        runCatching { stopXrayTunnel() }
            .onFailure { EventLog.add("Stop failed: ${safeMessage(it)}") }
        RustSshBridge.stop()
        ShizukuCaptivePortal.restoreOnVpnStopAsync(this)
        VpnState.stopped()
        currentProfile = null
        syncExternalSurfaces()
        stopForegroundCompat()
        stopSelf()
    }

    override fun onXrayTunnelStarted() {
        if (activeSettings.bindUnderlyingNetwork) {
            bindActiveUnderlyingNetwork()
        } else {
            runCatching { setUnderlyingNetworks(null) }
        }
        val profile = currentProfile
        VpnState.connected()
        EventLog.add("Tunnel started")
        syncExternalSurfaces()
        startTrafficTicker()
        val notification = buildNotification(
            title = "RustRay · Connected",
            text = profile?.let { "${it.name} · ${it.endpoint()}" } ?: "VPN connected",
            ongoing = true,
            withDisconnect = true,
        )
        notifyCompat(NOTIFICATION_ID, notification)
    }

    override fun onXrayTunnelStartFailed(error: Throwable) {
        fail(startFailureMessage(error))
    }

    override fun onXrayTunnelStartCancelled() {
        if (VpnState.phase == VpnPhase.CONNECTING) {
            EventLog.add("Start cancelled")
            VpnState.stopped()
            stopForegroundCompat()
            stopSelf()
        }
    }

    override fun onXrayTunnelFatalError(error: Throwable) {
        EventLog.add("Fatal tunnel error: ${safeMessage(error)}")
        fail("Tunnel stopped unexpectedly: ${safeMessage(error)}")
    }

    override fun networkChangeRebindEnabled(): Boolean = activeSettings.networkRebind

    override fun selectUnderlyingNetwork(
        candidates: List<XrayUnderlyingNetwork>,
        previous: Network?,
    ): Network? {
        val chosen = super.selectUnderlyingNetwork(candidates, previous)
        if (activeSettings.bindUnderlyingNetwork) {
            runCatching { setUnderlyingNetworks(chosen?.let { arrayOf(it) }) }
        } else {
            runCatching { setUnderlyingNetworks(null) }
        }
        return chosen
    }

    private fun bindActiveUnderlyingNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val chosen = if (active != null &&
            caps != null &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        ) {
            active
        } else {
            @Suppress("DEPRECATION")
            cm.allNetworks.firstOrNull { net ->
                val c = cm.getNetworkCapabilities(net) ?: return@firstOrNull false
                !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }
        runCatching { setUnderlyingNetworks(chosen?.let { arrayOf(it) }) }
    }

    override fun onXrayNetworkRebind(result: XrayNetworkRebindResult) {
        EventLog.add("Network changed · tunnel rebind requested (generation ${result.sessionGeneration})")
        if (currentProfile?.protocol == Protocol.SSH) RustSshBridge.rebind()
    }

    override fun onXrayNetworkChangeError(error: Exception) {
        EventLog.add("Network change error: ${safeMessage(error)}")
    }

    override fun onRevoke() {
        connectGeneration.incrementAndGet()
        EventLog.add("VPN permission revoked by the system")
        runCatching { stopXrayTunnel() }
        RustSshBridge.stop()
        ShizukuCaptivePortal.restoreOnVpnStopAsync(this)
        stopTrafficTicker()
        VpnState.failed("VPN access was revoked")
        syncExternalSurfaces()
        currentProfile = null
        stopForegroundCompat()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        connectGeneration.incrementAndGet()
        stopTrafficTicker()
        RustSshBridge.stop()
        instance = null
        super.onDestroy()
        if (VpnState.phase == VpnPhase.CONNECTED ||
            VpnState.phase == VpnPhase.CONNECTING ||
            VpnState.phase == VpnPhase.STOPPING
        ) {
            VpnState.stopped()
        }
    }

    fun snapshot(): XrayVpnRuntimeSnapshot = xrayVpnRuntimeSnapshot()

    private fun settingsSummary(settings: AppSettings): String {
        val split = when (settings.splitTunnelMode) {
            SplitTunnelMode.ALL -> "Split: All"
            SplitTunnelMode.BYPASS -> "Split: Bypass ${settings.splitTunnelApps.size} apps"
            SplitTunnelMode.ONLY_SELECTED -> "Split: Only ${settings.splitTunnelApps.size} apps"
        }
        val dns = if (settings.fakeIpEnabled) "FakeIP" else "DNS(${settings.parsedDnsServers().ifEmpty { listOf("1.1.1.1") }.joinToString(",")})"
        val sniff = if (settings.sniffingEnabled) "Sniff:on" else "Sniff:off"
        val ipv6 = if (settings.ipv6Route) "IPv6:on" else "IPv6:off"
        val bind = if (settings.bindUnderlyingNetwork) "Bind:on" else "Bind:off"
        return "Tuning: MTU ${settings.tunMtu} · ${settings.runtimeProfile.name} · $ipv6 · $bind · $sniff · $dns · $split"
    }

    private fun fail(message: String) {
        RustSshBridge.stop()
        ShizukuCaptivePortal.restoreOnVpnStopAsync(this)
        stopTrafficTicker()
        VpnState.failed(message)
        syncExternalSurfaces()
        EventLog.add("Connection failed: $message")
        // Leave a dismissible failure notification so a background start is visible.
        val notification = buildNotification(
            title = "RustRay · Connection failed",
            text = message,
            ongoing = false,
            withDisconnect = false,
        )
        runCatching { notifyCompat(FAILURE_NOTIFICATION_ID, notification) }
        stopForegroundCompat()
        stopSelf()
    }

    private fun notifyCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // startForeground still works; only notification updates are skipped.
            return
        }
        NotificationManagerCompat.from(this).notify(id, notification)
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun progressNotification(text: String, profile: Profile): Notification =
        buildNotification(
            title = "RustRay · $text",
            text = "${profile.name} · ${profile.endpoint()}",
            ongoing = true,
            withDisconnect = true,
        )

    private fun buildNotification(
        title: String,
        text: String,
        ongoing: Boolean,
        withDisconnect: Boolean,
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setColor(ContextCompat.getColor(this, R.color.primary))
            .setContentIntent(contentIntent())
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (withDisconnect) {
            builder.addAction(0, getString(R.string.reconnect), reconnectIntent())
            builder.addAction(0, getString(R.string.disconnect), stopIntent())
        }
        return builder.build()
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, AppVpnService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun reconnectIntent(): PendingIntent = PendingIntent.getService(
        this,
        2,
        Intent(this, AppVpnService::class.java).setAction(ACTION_RECONNECT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun startTrafficTicker() {
        stopTrafficTicker()
        tickCount = 0
        notifLastUp = -1L
        notifLastDown = -1L
        notifLastAt = 0L
        notifHandler.postDelayed(trafficTicker, 1000L)
    }

    private fun stopTrafficTicker() {
        notifHandler.removeCallbacks(trafficTicker)
    }

    private fun publishTrafficSnapshot(updateNotif: Boolean) {
        val snap = runCatching { snapshot() }.getOrNull() ?: return
        val stats = snap.tunStats ?: return
        val up = stats.uplinkBytes
        val down = stats.downlinkBytes
        val sshSummary = if (currentProfile?.protocol == Protocol.SSH) RustSshBridge.statsSummary() else ""
        VpnState.updateTraffic(up, down, snap.activeConnections, sshSummary)
        if (!updateNotif) return
        val now = SystemClock.elapsedRealtime()
        val upRate: Long
        val downRate: Long
        if (notifLastUp < 0 || notifLastDown < 0 || now - notifLastAt > 6000) {
            upRate = 0L
            downRate = 0L
        } else {
            val dt = (now - notifLastAt).coerceAtLeast(1)
            upRate = ((up - notifLastUp).coerceAtLeast(0) * 1000) / dt
            downRate = ((down - notifLastDown).coerceAtLeast(0) * 1000) / dt
        }
        notifLastUp = up
        notifLastDown = down
        notifLastAt = now

        val profileName = currentProfile?.name ?: VpnState.profileName.ifBlank { "VPN" }
        val text = "↑ ${formatRateShort(upRate)} · ↓ ${formatRateShort(downRate)} | ↑ ${formatBytesShort(up)} · ↓ ${formatBytesShort(down)}"
        val notification = buildNotification(
            title = "RustRay · $profileName",
            text = text,
            ongoing = true,
            withDisconnect = true,
        )
        notifyCompat(NOTIFICATION_ID, notification)
    }

    private fun formatBytesShort(bytes: Long): String {
        val b = bytes.coerceAtLeast(0).toDouble()
        return when {
            b >= 1024 * 1024 * 1024 -> String.format("%.1f GB", b / (1024 * 1024 * 1024))
            b >= 1024 * 1024 -> String.format("%.1f MB", b / (1024 * 1024))
            b >= 1024 -> String.format("%.0f KB", b / 1024)
            else -> "${bytes.coerceAtLeast(0)} B"
        }
    }

    private fun formatRateShort(bps: Long): String = "${formatBytesShort(bps)}/s"

    private fun syncExternalSurfaces() {
        RustRayWidgetProvider.refreshAll(this)
        TileSync.requestUpdates(this)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN status", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Connection status and disconnect action"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun startFailureMessage(error: Throwable): String {
        val core = error as? XrayCoreException
        val detail = error.message?.lineSequence()?.firstOrNull()?.trim()
        return when {
            core != null && !detail.isNullOrEmpty() -> "Core error ${core.code}: ${detail.take(220)}"
            !detail.isNullOrEmpty() -> detail.take(260)
            else -> "Start failed (${error.javaClass.simpleName})"
        }
    }

    private fun safeMessage(error: Throwable): String =
        error.message?.lineSequence()?.firstOrNull()?.trim()?.take(220)
            ?: error.javaClass.simpleName

    companion object {
        const val ACTION_CONNECT = "io.rustray.app.CONNECT"
        const val ACTION_RECONNECT = "io.rustray.app.RECONNECT"
        const val ACTION_STOP = "io.rustray.app.STOP"
        const val EXTRA_PROFILE_ID = "profile_id"

        private const val CHANNEL_ID = "vpn_status"
        private const val NOTIFICATION_ID = 7001
        private const val FAILURE_NOTIFICATION_ID = 7002

        @Volatile
        var instance: AppVpnService? = null
            private set
    }
}
