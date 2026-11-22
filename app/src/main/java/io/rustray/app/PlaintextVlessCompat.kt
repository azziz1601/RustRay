package io.rustray.app

import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * Compatibility layer for providers that publish plaintext VLESS links
 * (usually HTTPUpgrade on port 80). xray-rust deliberately refuses a plaintext
 * VLESS outbound whose server is a public address.
 *
 * The core exempts single-label domains from that rule, so the link is imported
 * with a synthetic single-label address and the real destination is restored
 * through `dns.hosts`. This does not add encryption: the profile is marked as
 * plaintext and the UI warns about it.
 */
object PlaintextVlessCompat {

    data class Prepared(
        val importLink: String,
        val originalHost: String,
        val port: Int,
        val alias: String?,
        val target: String?,
        val security: String,
        val network: String,
        val name: String,
    ) {
        val plaintext: Boolean get() = alias != null
    }

    fun prepare(link: String): Prepared {
        val fallback = Prepared(link, "", 443, null, null, "tls", "tcp", "")
        val uri = runCatching { URI(link) }.getOrNull() ?: return fallback
        val host = uri.host?.removeSurrounding("[", "]") ?: return fallback
        if (host.isBlank()) return fallback
        val port = uri.port.takeIf { it in 1..65535 } ?: 443
        val query = parseQuery(uri.rawQuery)
        val security = query["security"]?.lowercase().orEmpty().ifEmpty { "none" }
        val encryption = query["encryption"]?.lowercase().orEmpty().ifEmpty { "none" }
        val network = query["type"]?.lowercase().orEmpty().ifEmpty { "tcp" }
        val name = uri.rawFragment
            ?.let { decode(it) }
            ?.takeIf { it.isNotBlank() }
            ?: "$host:$port"
        val needsAlias = security == "none" &&
            encryption == "none" &&
            !isExemptHost(host)
        if (!needsAlias) {
            return Prepared(link, host, port, null, null, security, network, name)
        }
        val alias = aliasFor(host, port)
        return Prepared(
            importLink = replaceAuthorityHost(link, alias),
            originalHost = host,
            port = port,
            alias = alias,
            target = host,
            security = security,
            network = network,
            name = name,
        )
    }

    /**
     * Rewrites any plaintext VLESS outbound that points at a public address.
     * Imported profiles are prepared earlier, but manually created profiles
     * reach the core through this path.
     */
    fun rewriteConfig(configJson: String): String {
        val root = JSONObject(configJson)
        val outbounds = root.optJSONArray("outbounds") ?: return configJson
        val dns = root.optJSONObject("dns") ?: JSONObject()
        val hosts = dns.optJSONObject("hosts") ?: JSONObject()
        var changed = false
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            val proto = outbound.optString("protocol").lowercase()
            if (proto !in listOf("vless", "vmess", "trojan")) continue
            val stream = outbound.optJSONObject("streamSettings") ?: continue
            if (!stream.optString("security", "none").equals("none", ignoreCase = true)) continue
            val settings = outbound.optJSONObject("settings") ?: continue
            val servers = if (proto == "trojan") {
                settings.optJSONArray("servers")
            } else {
                settings.optJSONArray("vnext")
            } ?: continue
            for (serverIndex in 0 until servers.length()) {
                val server = servers.optJSONObject(serverIndex) ?: continue
                if (proto == "vless") {
                    val user = server.optJSONArray("users")?.optJSONObject(0) ?: continue
                    if (!user.optString("encryption", "none").equals("none", ignoreCase = true)) continue
                }
                val address = server.optString("address")
                if (address.isBlank() || isExemptHost(address)) continue
                val port = server.optInt("port", 443)
                val alias = aliasFor(address, port)
                server.put("address", alias)
                hosts.put(alias, address)
                addMissingHostHeader(stream, address)
                changed = true
            }
        }
        if (!changed) return configJson
        dns.put("hosts", hosts)
        root.put("dns", dns)
        return root.toString(2)
    }

    /** Adds the alias mapping and keeps the original Host header if the link omitted it. */
    fun injectHosts(
        configJson: String,
        alias: String,
        target: String,
        originalHost: String,
    ): String {
        val root = JSONObject(configJson)
        val dns = root.optJSONObject("dns") ?: JSONObject()
        val hosts = dns.optJSONObject("hosts") ?: JSONObject()
        hosts.put(alias, target)
        dns.put("hosts", hosts)
        root.put("dns", dns)

        val outbounds = root.optJSONArray("outbounds")
        if (outbounds != null) {
            for (index in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(index) ?: continue
                if (!outbound.optString("protocol").equals("vless", ignoreCase = true)) continue
                addMissingHostHeader(outbound.optJSONObject("streamSettings"), originalHost)
                break
            }
        }
        return root.toString(2)
    }

    fun isPlaintextConfig(configJson: String): Boolean {
        return try {
            val root = JSONObject(configJson)
            val outbounds = root.optJSONArray("outbounds") ?: return false
            for (index in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(index) ?: continue
                if (!outbound.optString("protocol").equals("vless", ignoreCase = true)) continue
                val stream = outbound.optJSONObject("streamSettings") ?: continue
                if (!stream.optString("security", "none").equals("none", ignoreCase = true)) continue
                val server = outbound.optJSONObject("settings")
                    ?.optJSONArray("vnext")?.optJSONObject(0) ?: continue
                val user = server.optJSONArray("users")?.optJSONObject(0) ?: continue
                if (user.optString("encryption", "none").equals("none", ignoreCase = true)) {
                    return true
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun addMissingHostHeader(stream: JSONObject?, host: String) {
        if (stream == null || host.isBlank()) return
        when (stream.optString("network").lowercase()) {
            "httpupgrade" -> {
                val settings = stream.optJSONObject("httpupgradeSettings") ?: JSONObject()
                if (settings.optString("host").isBlank()) settings.put("host", host)
                stream.put("httpupgradeSettings", settings)
            }

            "ws", "websocket" -> {
                val settings = stream.optJSONObject("wsSettings") ?: JSONObject()
                val headers = settings.optJSONObject("headers") ?: JSONObject()
                var hasHost = false
                val keys = headers.keys()
                while (keys.hasNext()) {
                    if (keys.next().equals("Host", ignoreCase = true)) {
                        hasHost = true
                        break
                    }
                }
                if (!hasHost) headers.put("Host", host)
                settings.put("headers", headers)
                stream.put("wsSettings", settings)
            }

            "xhttp", "splithttp" -> {
                val settings = stream.optJSONObject("xhttpSettings") ?: JSONObject()
                if (settings.optString("host").isBlank()) settings.put("host", host)
                stream.put("xhttpSettings", settings)
            }

            "grpc" -> {
                val settings = stream.optJSONObject("grpcSettings") ?: JSONObject()
                if (settings.optString("authority").isBlank()) settings.put("authority", host)
                stream.put("grpcSettings", settings)
            }
        }
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (part in raw.split('&')) {
            if (part.isEmpty()) continue
            val equals = part.indexOf('=')
            val key = decode(if (equals >= 0) part.substring(0, equals) else part)
            val value = decode(if (equals >= 0) part.substring(equals + 1) else "")
            result.putIfAbsent(key.lowercase(), value)
        }
        return result
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun aliasFor(host: String, port: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$host:$port".toByteArray(Charsets.UTF_8))
        val suffix = digest.take(6).joinToString("") { "%02x".format(it) }
        return "rr-$suffix"
    }

    private fun replaceAuthorityHost(link: String, alias: String): String {
        val scheme = link.indexOf("://")
        if (scheme < 0) return link
        val authorityStart = scheme + 3
        val at = link.indexOf('@', authorityStart)
        val hostStart = if (at >= 0) at + 1 else authorityStart
        if (hostStart >= link.length) return link
        val hostEnd = if (link[hostStart] == '[') {
            val close = link.indexOf(']', hostStart)
            if (close < 0) return link
            close + 1
        } else {
            var index = hostStart
            while (index < link.length &&
                link[index] != ':' &&
                link[index] != '/' &&
                link[index] != '?' &&
                link[index] != '#'
            ) {
                index++
            }
            index
        }
        if (hostEnd <= hostStart) return link
        return link.substring(0, hostStart) + alias + link.substring(hostEnd)
    }

    private fun isExemptHost(host: String): Boolean {
        val normalized = host.trimEnd('.').lowercase()
        if (normalized.isEmpty()) return false
        val looksLikeIp = normalized.contains(':') ||
            normalized.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))
        if (looksLikeIp) {
            val address = runCatching { InetAddress.getByName(normalized) }.getOrNull()
                ?: return false
            return isExemptAddress(address)
        }
        return isExemptDomain(normalized)
    }

    private fun isExemptAddress(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress

        is Inet6Address -> address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            isUniqueLocal(address)

        else -> false
    }

    private fun isUniqueLocal(address: Inet6Address): Boolean {
        val bytes = address.address
        return bytes.isNotEmpty() && (bytes[0].toInt() and 0xfe) == 0xfc
    }

    private fun isExemptDomain(domain: String): Boolean {
        val forbidden = listOf(
            "lan",
            "localdomain",
            "example",
            "invalid",
            "localhost",
            "test",
            "local",
            "home.arpa",
            "internal",
        )
        if (forbidden.any { domain == it || domain.endsWith(".$it") }) return true
        if (domain.length > 63 || domain[0] !in 'a'..'z') return false
        if (domain.length == 1) return true
        val middle = domain.drop(1).dropLast(1)
        val middleValid = middle.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
        val lastValid = domain.last() in 'a'..'z' || domain.last() in '0'..'9'
        return middleValid && lastValid
    }
}
