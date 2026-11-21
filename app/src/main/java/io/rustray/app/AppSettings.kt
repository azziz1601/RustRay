package io.rustray.app

import android.content.Context
import androidx.annotation.StringRes
import org.json.JSONArray
import org.json.JSONObject
import org.xrayrust.mobile.XrayTunInterface
import org.xrayrust.mobile.XrayTunRuntimeProfile
import java.io.File

enum class AppLanguage(val tag: String, val label: String) {
    EN("en", "English"),
    ID("id", "Bahasa Indonesia");

    companion object {
        fun fromTag(value: String?): AppLanguage =
            entries.firstOrNull { it.tag.equals(value, ignoreCase = true) } ?: EN
    }
}

enum class TunProfileOption(val wire: String, val label: String) {
    MOBILE_PLUS("mobile_plus", "MobilePlus"),
    THROUGHPUT("throughput", "Throughput"),
    MOBILE("mobile", "Mobile"),
    DEFAULT("default", "Default"),
    LOW_MEMORY("low_memory", "LowMemory"),
    DESKTOP("desktop", "Desktop");

    fun toSdk(): XrayTunRuntimeProfile = when (this) {
        MOBILE_PLUS -> XrayTunRuntimeProfile.MobilePlus
        THROUGHPUT -> XrayTunRuntimeProfile.Throughput
        MOBILE -> XrayTunRuntimeProfile.Mobile
        DEFAULT -> XrayTunRuntimeProfile.Default
        LOW_MEMORY -> XrayTunRuntimeProfile.LowMemory
        DESKTOP -> XrayTunRuntimeProfile.Desktop
    }

    companion object {
        fun fromWire(value: String?): TunProfileOption =
            entries.firstOrNull { it.wire == value } ?: MOBILE_PLUS
    }
}

enum class SplitTunnelMode(val wire: String, @StringRes val labelRes: Int) {
    ALL("all", R.string.split_all),
    BYPASS("bypass", R.string.split_bypass),
    ONLY_SELECTED("only_selected", R.string.split_only);

    companion object {
        fun fromWire(value: String?): SplitTunnelMode =
            entries.firstOrNull { it.wire == value } ?: ALL
    }
}

data class AppSettings(
    val language: AppLanguage = AppLanguage.EN,
    val tunMtu: Int = XrayTunInterface.MTU,
    val runtimeProfile: TunProfileOption = TunProfileOption.MOBILE_PLUS,
    val ipv6Route: Boolean = false,
    val networkRebind: Boolean = true,
    val bindUnderlyingNetwork: Boolean = true,
    val notificationTraffic: Boolean = true,
    val shizukuAutoCaptivePortal: Boolean = false,
    val sniffingEnabled: Boolean = true,
    val sniffingMetadataOnly: Boolean = false,
    val fakeIpEnabled: Boolean = true,
    val dnsServers: String = DEFAULT_DNS_SERVERS,
    val dnsQueryStrategy: String = "UseIPv4",
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.ALL,
    val splitTunnelApps: Set<String> = emptySet(),
) {
    fun parsedDnsServers(): List<String> = dnsServers
        .split(',', '\n', ' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(8)

    fun effectiveQueryStrategy(): String =
        if (fakeIpEnabled && dnsQueryStrategy == "UseIPv6") "UseIPv4" else dnsQueryStrategy

    companion object {
        const val DEFAULT_DNS_SERVERS = "1.1.1.1, 8.8.8.8"
        const val GAMING_MTU = 1380
        val QUERY_STRATEGIES = listOf("UseIPv4", "UseIP", "UseIPv6")

        fun gamingPreset(
            existingApps: Set<String>,
            existingMode: SplitTunnelMode,
            language: AppLanguage = AppLanguage.EN,
        ): AppSettings =
            AppSettings(
                language = language,
                tunMtu = GAMING_MTU,
                runtimeProfile = TunProfileOption.THROUGHPUT,
                ipv6Route = false,
                networkRebind = true,
                bindUnderlyingNetwork = true,
                notificationTraffic = true,
                shizukuAutoCaptivePortal = false,
                sniffingEnabled = false,
                sniffingMetadataOnly = false,
                fakeIpEnabled = false,
                dnsServers = DEFAULT_DNS_SERVERS,
                dnsQueryStrategy = "UseIPv4",
                splitTunnelMode = existingMode,
                splitTunnelApps = existingApps,
            )
    }
}

class SettingsStore(context: Context) {
    private val appCtx = context.applicationContext
    private val settingsFile = File(appCtx.filesDir, "settings.json")

    init {
        migrateLegacyPrefsIfNeeded()
    }

    private fun migrateLegacyPrefsIfNeeded() {
        if (settingsFile.exists()) return
        runCatching {
            val prefs = appCtx.getSharedPreferences("rustray.settings", Context.MODE_PRIVATE)
            if (prefs.all.isEmpty()) return
            val legacy = AppSettings(
                language = AppLanguage.fromTag(prefs.getString(KEY_LANGUAGE, null)),
                tunMtu = prefs.getInt(KEY_MTU, XrayTunInterface.MTU)
                    .coerceIn(XrayTunInterface.MIN_MTU, XrayTunInterface.MAX_MTU),
                runtimeProfile = TunProfileOption.fromWire(prefs.getString(KEY_RUNTIME_PROFILE, null)),
                ipv6Route = prefs.getBoolean(KEY_IPV6_ROUTE, false),
                networkRebind = prefs.getBoolean(KEY_NETWORK_REBIND, true),
                bindUnderlyingNetwork = prefs.getBoolean(KEY_BIND_UNDERLYING, true),
                notificationTraffic = prefs.getBoolean(KEY_NOTIF_TRAFFIC, true),
                shizukuAutoCaptivePortal = prefs.getBoolean(KEY_SHIZUKU_AUTO_CAPTIVE, false),
                sniffingEnabled = prefs.getBoolean(KEY_SNIFFING_ENABLED, true),
                sniffingMetadataOnly = prefs.getBoolean(KEY_SNIFFING_METADATA_ONLY, false),
                fakeIpEnabled = prefs.getBoolean(KEY_FAKE_IP_ENABLED, true),
                dnsServers = prefs.getString(KEY_DNS_SERVERS, AppSettings.DEFAULT_DNS_SERVERS)
                    ?: AppSettings.DEFAULT_DNS_SERVERS,
                dnsQueryStrategy = prefs.getString(KEY_DNS_STRATEGY, "UseIPv4")
                    ?.takeIf { it in AppSettings.QUERY_STRATEGIES }
                    ?: "UseIPv4",
                splitTunnelMode = SplitTunnelMode.fromWire(prefs.getString(KEY_SPLIT_MODE, null)),
                splitTunnelApps = prefs.getStringSet(KEY_SPLIT_APPS, emptySet())?.toSet() ?: emptySet(),
            )
            save(legacy)
        }
    }

    fun load(): AppSettings {
        if (!settingsFile.exists()) return AppSettings()
        return runCatching {
            val json = JSONObject(settingsFile.readText())
            val appsArr = json.optJSONArray(KEY_SPLIT_APPS)
            val apps = buildSet {
                if (appsArr != null) {
                    for (i in 0 until appsArr.length()) {
                        appsArr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
            }
            AppSettings(
                language = AppLanguage.fromTag(json.optString(KEY_LANGUAGE, null)),
                tunMtu = json.optInt(KEY_MTU, XrayTunInterface.MTU)
                    .coerceIn(XrayTunInterface.MIN_MTU, XrayTunInterface.MAX_MTU),
                runtimeProfile = TunProfileOption.fromWire(json.optString(KEY_RUNTIME_PROFILE, null)),
                ipv6Route = json.optBoolean(KEY_IPV6_ROUTE, false),
                networkRebind = json.optBoolean(KEY_NETWORK_REBIND, true),
                bindUnderlyingNetwork = json.optBoolean(KEY_BIND_UNDERLYING, true),
                notificationTraffic = json.optBoolean(KEY_NOTIF_TRAFFIC, true),
                shizukuAutoCaptivePortal = json.optBoolean(KEY_SHIZUKU_AUTO_CAPTIVE, false),
                sniffingEnabled = json.optBoolean(KEY_SNIFFING_ENABLED, true),
                sniffingMetadataOnly = json.optBoolean(KEY_SNIFFING_METADATA_ONLY, false),
                fakeIpEnabled = json.optBoolean(KEY_FAKE_IP_ENABLED, true),
                dnsServers = json.optString(KEY_DNS_SERVERS, AppSettings.DEFAULT_DNS_SERVERS)
                    .ifBlank { AppSettings.DEFAULT_DNS_SERVERS },
                dnsQueryStrategy = json.optString(KEY_DNS_STRATEGY, "UseIPv4")
                    .takeIf { it in AppSettings.QUERY_STRATEGIES }
                    ?: "UseIPv4",
                splitTunnelMode = SplitTunnelMode.fromWire(json.optString(KEY_SPLIT_MODE, null)),
                splitTunnelApps = apps,
            )
        }.getOrDefault(AppSettings())
    }

    fun save(settings: AppSettings) {
        runCatching {
            val json = JSONObject()
                .put(KEY_LANGUAGE, settings.language.tag)
                .put(
                    KEY_MTU,
                    settings.tunMtu.coerceIn(XrayTunInterface.MIN_MTU, XrayTunInterface.MAX_MTU),
                )
                .put(KEY_RUNTIME_PROFILE, settings.runtimeProfile.wire)
                .put(KEY_IPV6_ROUTE, settings.ipv6Route)
                .put(KEY_NETWORK_REBIND, settings.networkRebind)
                .put(KEY_BIND_UNDERLYING, settings.bindUnderlyingNetwork)
                .put(KEY_NOTIF_TRAFFIC, settings.notificationTraffic)
                .put(KEY_SHIZUKU_AUTO_CAPTIVE, settings.shizukuAutoCaptivePortal)
                .put(KEY_SNIFFING_ENABLED, settings.sniffingEnabled)
                .put(KEY_SNIFFING_METADATA_ONLY, settings.sniffingMetadataOnly)
                .put(KEY_FAKE_IP_ENABLED, settings.fakeIpEnabled)
                .put(KEY_DNS_SERVERS, settings.dnsServers.trim())
                .put(KEY_DNS_STRATEGY, settings.effectiveQueryStrategy())
                .put(KEY_SPLIT_MODE, settings.splitTunnelMode.wire)
                .put(KEY_SPLIT_APPS, JSONArray(settings.splitTunnelApps.toList()))
            val tmp = File(settingsFile.parentFile, "settings.json.tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(settingsFile)) {
                settingsFile.writeText(json.toString())
                tmp.delete()
            }
        }
    }

    companion object {
        private const val KEY_LANGUAGE = "app_language"
        private const val KEY_MTU = "tun_mtu"
        private const val KEY_RUNTIME_PROFILE = "runtime_profile"
        private const val KEY_IPV6_ROUTE = "ipv6_route"
        private const val KEY_NETWORK_REBIND = "network_rebind"
        private const val KEY_BIND_UNDERLYING = "bind_underlying_network"
        private const val KEY_NOTIF_TRAFFIC = "notification_traffic"
        private const val KEY_SHIZUKU_AUTO_CAPTIVE = "shizuku_auto_captive"
        private const val KEY_SNIFFING_ENABLED = "sniffing_enabled"
        private const val KEY_SNIFFING_METADATA_ONLY = "sniffing_metadata_only"
        private const val KEY_FAKE_IP_ENABLED = "fake_ip_enabled"
        private const val KEY_DNS_SERVERS = "dns_servers"
        private const val KEY_DNS_STRATEGY = "dns_strategy"
        private const val KEY_SPLIT_MODE = "split_mode"
        private const val KEY_SPLIT_APPS = "split_apps"
    }
}
