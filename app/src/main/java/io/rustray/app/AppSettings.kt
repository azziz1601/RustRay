package io.rustray.app

import android.content.Context
import org.xrayrust.mobile.XrayTunInterface
import org.xrayrust.mobile.XrayTunRuntimeProfile

enum class TunProfileOption(val wire: String, val label: String) {
    MOBILE_PLUS("mobile_plus", "MobilePlus (Seimbang)"),
    THROUGHPUT("throughput", "Throughput (Gaming / Tinggi)"),
    MOBILE("mobile", "Mobile (Hemat)"),
    DEFAULT("default", "Default"),
    LOW_MEMORY("low_memory", "LowMemory (RAM Kecil)"),
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

enum class SplitTunnelMode(val wire: String, val label: String) {
    ALL("all", "Semua aplikasi"),
    BYPASS("bypass", "Bypass aplikasi terpilih"),
    ONLY_SELECTED("only_selected", "Hanya aplikasi terpilih (Mode Game)");

    companion object {
        fun fromWire(value: String?): SplitTunnelMode =
            entries.firstOrNull { it.wire == value } ?: ALL
    }
}

data class AppSettings(
    val tunMtu: Int = XrayTunInterface.MTU,
    val runtimeProfile: TunProfileOption = TunProfileOption.MOBILE_PLUS,
    val ipv6Route: Boolean = false,
    val networkRebind: Boolean = true,
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
        val QUERY_STRATEGIES = listOf("UseIPv4", "UseIP", "UseIPv6")

        fun gamingPreset(existingApps: Set<String>, existingMode: SplitTunnelMode): AppSettings =
            AppSettings(
                tunMtu = XrayTunInterface.MIN_MTU,
                runtimeProfile = TunProfileOption.THROUGHPUT,
                ipv6Route = false,
                networkRebind = true,
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
    private val prefs = context.getSharedPreferences("rustray.settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        tunMtu = prefs.getInt(KEY_MTU, XrayTunInterface.MTU)
            .coerceIn(XrayTunInterface.MIN_MTU, XrayTunInterface.MAX_MTU),
        runtimeProfile = TunProfileOption.fromWire(prefs.getString(KEY_RUNTIME_PROFILE, null)),
        ipv6Route = prefs.getBoolean(KEY_IPV6_ROUTE, false),
        networkRebind = prefs.getBoolean(KEY_NETWORK_REBIND, true),
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

    fun save(settings: AppSettings) {
        prefs.edit()
            .putInt(
                KEY_MTU,
                settings.tunMtu.coerceIn(XrayTunInterface.MIN_MTU, XrayTunInterface.MAX_MTU),
            )
            .putString(KEY_RUNTIME_PROFILE, settings.runtimeProfile.wire)
            .putBoolean(KEY_IPV6_ROUTE, settings.ipv6Route)
            .putBoolean(KEY_NETWORK_REBIND, settings.networkRebind)
            .putBoolean(KEY_SNIFFING_ENABLED, settings.sniffingEnabled)
            .putBoolean(KEY_SNIFFING_METADATA_ONLY, settings.sniffingMetadataOnly)
            .putBoolean(KEY_FAKE_IP_ENABLED, settings.fakeIpEnabled)
            .putString(KEY_DNS_SERVERS, settings.dnsServers.trim())
            .putString(KEY_DNS_STRATEGY, settings.effectiveQueryStrategy())
            .putString(KEY_SPLIT_MODE, settings.splitTunnelMode.wire)
            .putStringSet(KEY_SPLIT_APPS, settings.splitTunnelApps.toSet())
            .apply()
    }

    companion object {
        private const val KEY_MTU = "tun_mtu"
        private const val KEY_RUNTIME_PROFILE = "runtime_profile"
        private const val KEY_IPV6_ROUTE = "ipv6_route"
        private const val KEY_NETWORK_REBIND = "network_rebind"
        private const val KEY_SNIFFING_ENABLED = "sniffing_enabled"
        private const val KEY_SNIFFING_METADATA_ONLY = "sniffing_metadata_only"
        private const val KEY_FAKE_IP_ENABLED = "fake_ip_enabled"
        private const val KEY_DNS_SERVERS = "dns_servers"
        private const val KEY_DNS_STRATEGY = "dns_strategy"
        private const val KEY_SPLIT_MODE = "split_mode"
        private const val KEY_SPLIT_APPS = "split_apps"
    }
}
