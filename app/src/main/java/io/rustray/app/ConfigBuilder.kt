package io.rustray.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the self-contained TUN client config consumed by [AppVpnService].
 * Imported profiles already carry a complete config from the SDK importer,
 * then user tuning from [AppSettings] is applied on top.
 */
object ConfigBuilder {


    /**
     * Populates editable [Profile] fields from [Profile.importedConfig] so imported
     * profiles can be opened and modified in [EditProfileActivity] with all fields pre-filled.
     */
    fun hydrateFromImportedConfig(profile: Profile): Profile {
        val raw = profile.importedConfig?.takeIf { it.isNotBlank() } ?: return profile
        return runCatching {
            val root = JSONObject(raw)
            val outbounds = root.optJSONArray("outbounds") ?: return profile
            var proxy: JSONObject? = null
            for (i in 0 until outbounds.length()) {
                val ob = outbounds.optJSONObject(i) ?: continue
                val proto = ob.optString("protocol").lowercase()
                if (ob.optString("tag") == "proxy" || proto in listOf("vless", "vmess", "trojan")) {
                    proxy = ob
                    break
                }
            }
            proxy ?: return profile

            val proto = when (proxy.optString("protocol").lowercase()) {
                "vless" -> Protocol.VLESS
                "vmess" -> Protocol.VMESS
                "trojan" -> Protocol.TROJAN
                else -> profile.protocol
            }
            val settings = proxy.optJSONObject("settings") ?: JSONObject()

            var server = profile.server
            var port = profile.port
            var credential = profile.credential
            var flow = profile.flow
            var vmessSecurity = profile.vmessSecurity
            var alterId = profile.alterId

            when (proto) {
                Protocol.VLESS, Protocol.VMESS -> {
                    val vnext = settings.optJSONArray("vnext")?.optJSONObject(0)
                    if (vnext != null) {
                        val addr = vnext.optString("address")
                        if (server.isBlank() && addr.isNotBlank()) server = addr
                        val p = vnext.optInt("port", 0)
                        if (p in 1..65535) port = p
                        val user = vnext.optJSONArray("users")?.optJSONObject(0)
                        if (user != null) {
                            credential = user.optString("id").ifBlank { credential }
                            if (proto == Protocol.VLESS) {
                                flow = user.optString("flow").ifBlank { flow }
                            } else {
                                vmessSecurity = user.optString("security").ifBlank { vmessSecurity }
                                alterId = user.optInt("alterId", alterId)
                            }
                        }
                    }
                }

                Protocol.TROJAN -> {
                    val srv = settings.optJSONArray("servers")?.optJSONObject(0)
                    if (srv != null) {
                        val addr = srv.optString("address")
                        if (server.isBlank() && addr.isNotBlank()) server = addr
                        val p = srv.optInt("port", 0)
                        if (p in 1..65535) port = p
                        credential = srv.optString("password").ifBlank { credential }
                    }
                }

                Protocol.SSH -> Unit
            }

            val stream = proxy.optJSONObject("streamSettings") ?: JSONObject()
            val netWire = stream.optString("network").lowercase()
            val network = when (netWire) {
                "ws", "websocket" -> Network.WS
                "httpupgrade" -> Network.HTTPUPGRADE
                "grpc" -> Network.GRPC
                "xhttp", "splithttp" -> Network.XHTTP
                "tcp", "raw" -> Network.TCP
                else -> profile.network
            }

            var path = profile.path
            var host = profile.host
            var serviceName = profile.serviceName

            when (network) {
                Network.WS -> {
                    val ws = stream.optJSONObject("wsSettings")
                    if (ws != null) {
                        path = ws.optString("path").ifBlank { path }
                        val headerHost = ws.optJSONObject("headers")?.optString("Host").orEmpty()
                        host = ws.optString("host").ifBlank { headerHost }.ifBlank { host }
                    }
                }

                Network.HTTPUPGRADE -> {
                    val hu = stream.optJSONObject("httpupgradeSettings")
                    if (hu != null) {
                        path = hu.optString("path").ifBlank { path }
                        host = hu.optString("host").ifBlank { host }
                    }
                }

                Network.XHTTP -> {
                    val xh = stream.optJSONObject("xhttpSettings")
                        ?: stream.optJSONObject("splithttpSettings")
                    if (xh != null) {
                        path = xh.optString("path").ifBlank { path }
                        host = xh.optString("host").ifBlank { host }
                    }
                }

                Network.GRPC -> {
                    val grpc = stream.optJSONObject("grpcSettings")
                    if (grpc != null) {
                        serviceName = grpc.optString("serviceName").ifBlank { serviceName }
                    }
                }

                Network.TCP -> Unit
            }

            val secWire = stream.optString("security").lowercase()
            val security = when (secWire) {
                "tls" -> Security.TLS
                "reality" -> Security.REALITY
                "none", "" -> if (stream.has("security")) Security.NONE else profile.security
                else -> profile.security
            }

            var sni = profile.sni
            var fingerprint = profile.fingerprint
            var alpn = profile.alpn
            var publicKey = profile.publicKey
            var shortId = profile.shortId
            var spiderX = profile.spiderX

            when (security) {
                Security.TLS -> {
                    val tls = stream.optJSONObject("tlsSettings")
                    if (tls != null) {
                        sni = tls.optString("serverName").ifBlank { sni }
                        fingerprint = tls.optString("fingerprint").ifBlank { fingerprint }
                        val alpnArr = tls.optJSONArray("alpn")
                        if (alpnArr != null && alpnArr.length() > 0) {
                            alpn = (0 until alpnArr.length())
                                .mapNotNull { alpnArr.optString(it).takeIf { v -> v.isNotBlank() } }
                                .joinToString(",")
                        }
                    }
                }

                Security.REALITY -> {
                    val reality = stream.optJSONObject("realitySettings")
                    if (reality != null) {
                        sni = reality.optString("serverName").ifBlank { sni }
                        fingerprint = reality.optString("fingerprint").ifBlank { fingerprint }
                        publicKey = reality.optString("publicKey").ifBlank { publicKey }
                        shortId = reality.optString("shortId").ifBlank { shortId }
                        spiderX = reality.optString("spiderX").ifBlank { spiderX }
                    }
                }

                Security.NONE -> Unit
            }

            profile.copy(
                protocol = proto,
                server = server,
                port = port,
                credential = credential,
                flow = flow,
                vmessSecurity = vmessSecurity,
                alterId = alterId,
                security = security,
                sni = sni,
                fingerprint = fingerprint,
                alpn = alpn,
                publicKey = publicKey,
                shortId = shortId,
                spiderX = spiderX,
                network = network,
                path = path,
                host = host,
                serviceName = serviceName,
                plaintext = proto == Protocol.VLESS && security == Security.NONE,
            )
        }.getOrDefault(profile)
    }

    fun configFor(profile: Profile, settings: AppSettings = AppSettings()): String {
        val root = profile.importedConfig
            ?.takeIf { it.isNotBlank() }
            ?.let { JSONObject(it) }
            ?: build(profile)
        applySettings(root, settings)
        return PlaintextVlessCompat.rewriteConfig(root.toString(2))
    }

    /**
     * Builds an Xray TUN config whose VLESS/VMess/Trojan outbound connects via raw TCP to the
     * local Rust custom-payload HTTPUpgrade bridge on 127.0.0.1:[bridgePort].
     */
    fun configForV2rayPayload(
        profile: Profile,
        bridgePort: Int,
        settings: AppSettings = AppSettings(),
    ): String {
        val bridgedProfile = profile.copy(
            server = "127.0.0.1",
            port = bridgePort,
            network = Network.TCP,
            security = Security.NONE,
            importedConfig = null,
            plaintext = false,
        )
        val root = build(bridgedProfile)
        applySettings(root, settings)
        return root.toString(2)
    }

    fun configForSsh(socksPort: Int, settings: AppSettings = AppSettings()): String {
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
            .put("protocol", "socks")
            .put(
                "settings",
                JSONObject().put(
                    "servers",
                    JSONArray().put(
                        JSONObject()
                            .put("address", "127.0.0.1")
                            .put("port", socksPort),
                    ),
                ),
            )

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
                        .put("ip", JSONArray(listOf("geoip:private", "127.0.0.0/8", "fd00::/8")))
                        .put("outboundTag", "direct"),
                ),
            )

        val dns = JSONObject()
            .put("queryStrategy", "UseIPv4")
            .put("servers", JSONArray(listOf("1.1.1.1", "8.8.8.8")))
            .put(
                "fakeIp",
                JSONObject()
                    .put("enabled", true)
                    .put("ipv4Pool", "198.19.0.0/16")
                    .put("poolSize", 4096)
                    .put("ttl", 60),
            )

        val root = JSONObject()
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", JSONArray().put(proxy).put(direct))
            .put("routing", routing)
            .put("dns", dns)
        applySettings(root, settings)
        return root.toString(2)
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
                .put("poolSize", existingFakeIp.optInt("poolSize", 4096))
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
                    .put("poolSize", 4096)
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

        Protocol.SSH -> throw IllegalStateException("Use ConfigBuilder.configForSsh for SSH profiles")
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
