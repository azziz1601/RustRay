package io.rustray.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.xrayrust.mobile.XrayCoreException
import org.xrayrust.mobile.XrayNetworkRebindResult
import org.xrayrust.mobile.XrayTunInterface
import org.xrayrust.mobile.XrayVpnRuntimeSnapshot
import org.xrayrust.mobile.XrayVpnService

class AppVpnService : XrayVpnService() {

    private var currentProfile: Profile? = null
    @Volatile
    private var activeSettings: AppSettings = AppSettings()

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> handleConnect(intent.getStringExtra(EXTRA_PROFILE_ID))
            ACTION_STOP -> handleStop()
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
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
        val settings = SettingsStore(this).load()
        activeSettings = settings
        currentProfile = profile
        VpnState.connecting(profile)
        EventLog.add("Starting ${profile.protocol.label} profile '${profile.name}' -> ${profile.endpoint()}")
        EventLog.add(settingsSummary(settings))
        startForegroundCompat(progressNotification("Connecting…", profile))

        val config = try {
            ConfigBuilder.configFor(profile, settings)
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
        if (VpnState.phase == VpnPhase.STOPPED || VpnState.phase == VpnPhase.ERROR) {
            stopForegroundCompat()
            stopSelf()
            return
        }
        VpnState.stopping()
        EventLog.add("Stopping tunnel")
        runCatching { stopXrayTunnel() }
            .onFailure { EventLog.add("Stop failed: ${safeMessage(it)}") }
        VpnState.stopped()
        currentProfile = null
        stopForegroundCompat()
        stopSelf()
    }

    override fun onXrayTunnelStarted() {
        val profile = currentProfile
        VpnState.connected()
        EventLog.add("Tunnel started")
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

    override fun onXrayNetworkRebind(result: XrayNetworkRebindResult) {
        EventLog.add("Network changed · tunnel rebind requested (generation ${result.sessionGeneration})")
    }

    override fun onXrayNetworkChangeError(error: Exception) {
        EventLog.add("Network change error: ${safeMessage(error)}")
    }

    override fun onRevoke() {
        EventLog.add("VPN permission revoked by the system")
        runCatching { stopXrayTunnel() }
        VpnState.failed("VPN access was revoked")
        currentProfile = null
        stopForegroundCompat()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
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
        return "Tuning: MTU ${settings.tunMtu} · ${settings.runtimeProfile.name} · $ipv6 · $sniff · $dns · $split"
    }

    private fun fail(message: String) {
        VpnState.failed(message)
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
            builder.addAction(0, "Disconnect", stopIntent())
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
