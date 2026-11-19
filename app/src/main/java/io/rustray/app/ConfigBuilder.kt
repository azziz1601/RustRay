package io.rustray.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the self-contained TUN client config consumed by [AppVpnService].
 * Imported profiles already carry a complete config from the SDK importer,
 * then user tuning from [AppSettings] is applied on top.
 */
object ConfigBuilder {

    fun configFor(profile: Profile, settings: AppSettings = AppSettings()): String {
        val root = profile.importedConfig
            ?.takeIf { it.isNotBlank() }
            ?.let { JSONObject(it) }
            ?: build(profile)
        applySettings(root, settings)
        return PlaintextVlessCompat.rewriteConfig(root.toString(2))
    }

    private fun applySettings(root: JSONObject, settings: AppSettings) {
        val inbounds = root.optJSONArray("inbounds")
        if (inbounds != null) {
            for (index in 0 until inbounds.length()) {
                val inbound = inbounds.optJSONObject(index) ?: continue
                if (!inbound.optString("protocol").equals("tun", ignoreCase = true)) continue
                val existingSniffing = inbound.optJSONObject("sniffing") ?: JSONObject()
                val destOverride = existingSniffing.optJSONArray("destOverride")
                    ?.takeIf { it.length() > 0 }
                    ?: JSONArray(listOf("http", "tls", "quic"))
                existingSniffing
                    .put("enabled", settings.sniffingEnabled)
                    .put("destOverride", destOverride)
                    .put("metadataOnly", settings.sniffingMetadataOnly)
                inbound.put("sniffing", existingSniffing)
            }
        }

        val dns = root.optJSONObject("dns") ?: JSONObject()
        dns.put("queryStrategy", settings.effectiveQueryStrategy())

        val servers = settings.parsedDnsServers()
        if (settings.fakeIpEnabled) {
            val existingFakeIp = dns.optJSONObject("fakeIp") ?: JSONObject()
            existingFakeIp
                .put("enabled", true)
                .put("ipv4Pool", existingFakeIp.optString("ipv4Pool").ifBlank { "198.19.0.0/16" })
                .put("poolSize", existingFakeIp.optInt("poolSize", 32768))
                .put("ttl", existingFakeIp.optInt("ttl", 60))
            dns.put("fakeIp", existingFakeIp)
            if (servers.isNotEmpty() && dns.has("servers")) {
                dns.put("servers", JSONArray(servers))
            }
        } else {
            val existingFakeIp = dns.optJSONObject("fakeIp")
            if (existingFakeIp != null) {
                existingFakeIp.put("enabled", false)
                dns.put("fakeIp", existingFakeIp)
            }
            val effectiveServers = servers.ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
            dns.put("servers", JSONArray(effectiveServers))
        }
        root.put("dns", dns)
    }

    private fun build(profile: Profile): JSONObject {
        val inbound = JSONObject()
            .put("tag", "tun-in")
            .put("protocol", "tun")
            .put("listen", "127.0.0.1")
            .put("port", 0)
            .put("settings", JSONObject())
            .put(
                "sniffing",
                JSONObject()
                    .put("enabled", true)
                    .put("destOverride", JSONArray(listOf("http", "tls", "quic")))
                    .put("metadataOnly", false),
            )

        val proxy = JSONObject()
            .put("tag", "proxy")
            .put("protocol", profile.protocol.wire)
            .put("settings", outboundSettings(profile))
            .put("streamSettings", streamSettings(profile))

        val direct = JSONObject()
            .put("tag", "direct")
            .put("protocol", "freedom")
            .put("settings", JSONObject())

        val routing = JSONObject()
            .put("domainStrategy", "AsIs")
            .put(
                "rules",
                JSONArray().put(
                    JSONObject()
                        .put("type", "field")
                        .put(
                            "ip",
                            JSONArray(listOf("geoip:private", "127.0.0.0/8", "fd00::/8")),
                        )
                        .put("outboundTag", "direct"),
                ),
            )

        val dns = JSONObject()
            .put("queryStrategy", "UseIPv4")
            .put(
                "fakeIp",
                JSONObject()
                    .put("enabled", true)
                    .put("ipv4Pool", "198.19.0.0/16")
                    .put("poolSize", 32768)
                    .put("ttl", 60),
            )

        return JSONObject()
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", JSONArray().put(proxy).put(direct))
            .put("routing", routing)
            .put("dns", dns)
    }

    private fun outboundSettings(profile: Profile): JSONObject = when (profile.protocol) {
        Protocol.VLESS -> {
            val user = JSONObject()
                .put("id", profile.credential)
                .put("encryption", "none")
            if (profile.flow.isNotBlank()) user.put("flow", profile.flow)
            JSONObject().put(
                "vnext",
                JSONArray().put(
                    JSONObject()
                        .put("address", profile.server)
                        .put("port", profile.port)
                        .put("users", JSONArray().put(user)),
                ),
            )
        }

        Protocol.VMESS -> JSONObject().put(
            "vnext",
            JSONArray().put(
                JSONObject()
                    .put("address", profile.server)
                    .put("port", profile.port)
                    .put(
                        "users",
                        JSONArray().put(
                            JSONObject()
                                .put("id", profile.credential)
                                .put("alterId", profile.alterId)
                                .put("security", profile.vmessSecurity.ifBlank { "auto" }),
                        ),
                    ),
            ),
        )

        Protocol.TROJAN -> JSONObject().put(
            "servers",
            JSONArray().put(
                JSONObject()
                    .put("address", profile.server)
                    .put("port", profile.port)
                    .put("password", profile.credential),
            ),
        )
    }

    private fun streamSettings(profile: Profile): JSONObject {
        val stream = JSONObject()
        when (profile.security) {
            Security.NONE -> stream.put("security", "none")
            Security.TLS -> {
                val tls = JSONObject()
                    .put("serverName", profile.sni.ifBlank { profile.server })
                if (profile.fingerprint.isNotBlank()) tls.put("fingerprint", profile.fingerprint)
                val alpn = profile.alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (alpn.isNotEmpty()) tls.put("alpn", JSONArray(alpn))
                stream.put("security", "tls").put("tlsSettings", tls)
            }

            Security.REALITY -> {
                val reality = JSONObject()
                    .put("serverName", profile.sni.ifBlank { profile.server })
                    .put("fingerprint", profile.fingerprint.ifBlank { "chrome" })
                    .put("publicKey", profile.publicKey)
                    .put("shortId", profile.shortId)
                    .put("spiderX", profile.spiderX.ifBlank { "/" })
                stream.put("security", "reality").put("realitySettings", reality)
            }
        }

        when (profile.network) {
            Network.TCP -> stream.put("network", "tcp")
            Network.WS -> {
                val ws = JSONObject().put("path", profile.path.ifBlank { "/" })
                if (profile.host.isNotBlank()) ws.put("host", profile.host)
                stream.put("network", "ws").put("wsSettings", ws)
            }

            Network.HTTPUPGRADE -> {
                val upgrade = JSONObject().put("path", profile.path.ifBlank { "/" })
                if (profile.host.isNotBlank()) upgrade.put("host", profile.host)
                stream.put("network", "httpupgrade").put("httpupgradeSettings", upgrade)
            }

            Network.GRPC -> {
                val grpc = JSONObject()
                if (profile.serviceName.isNotBlank()) grpc.put("serviceName", profile.serviceName)
                stream.put("network", "grpc").put("grpcSettings", grpc)
            }

            Network.XHTTP -> {
                val xhttp = JSONObject().put("path", profile.path.ifBlank { "/" })
                if (profile.host.isNotBlank()) xhttp.put("host", profile.host)
                stream.put("network", "xhttp").put("xhttpSettings", xhttp)
            }
        }
        return stream
    }
}
