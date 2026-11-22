package io.rustray.app

import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

object ShareLinkParser {

    fun parse(rawLink: String): Profile {
        val link = rawLink.trim()
        return when {
            link.startsWith("vmess://", ignoreCase = true) -> parseVmess(link)
            link.startsWith("vless://", ignoreCase = true) -> parseVlessOrTrojan(link, Protocol.VLESS)
            link.startsWith("trojan://", ignoreCase = true) -> parseVlessOrTrojan(link, Protocol.TROJAN)
            else -> throw IllegalArgumentException("Unsupported link scheme")
        }
    }

    private fun parseVmess(link: String): Profile {
        val payload = link.substringAfter("://").substringBefore('#').trim()
        val decoded = decodeBase64Flexible(payload)
            ?: throw IllegalArgumentException("Invalid VMess Base64 payload")
        val json = JSONObject(decoded)

        val server = json.optString("add").trim()
        val port = json.optString("port").trim().toIntOrNull()
            ?: json.optInt("port", 443)
        val uuid = json.optString("id").trim()
        if (server.isBlank()) throw IllegalArgumentException("VMess link missing server (add)")
        if (uuid.isBlank()) throw IllegalArgumentException("VMess link missing UUID (id)")
        if (port !in 1..65535) throw IllegalArgumentException("Invalid VMess port: $port")

        val name = json.optString("ps").trim().ifBlank { "$server:$port" }
        val alterId = json.optString("aid").trim().toIntOrNull() ?: json.optInt("aid", 0)
        val vmessSecurity = json.optString("scy").trim()
            .ifBlank { json.optString("security").trim() }
            .ifBlank { "auto" }

        val netWire = json.optString("net").trim().lowercase()
        val network = parseNetwork(netWire)
        val rawPath = json.optString("path").trim()
        val host = json.optString("host").trim()
        val path = if (network == Network.GRPC) "/" else rawPath.ifBlank { "/" }
        val serviceName = if (network == Network.GRPC) rawPath.removePrefix("/") else ""

        val tlsWire = json.optString("tls").trim().lowercase()
        val security = when (tlsWire) {
            "tls" -> Security.TLS
            "reality" -> Security.REALITY
            "none", "" -> Security.NONE
            else -> Security.TLS
        }
        val sni = json.optString("sni").trim().ifBlank { if (security != Security.NONE) host else "" }
        val fp = json.optString("fp").trim().ifBlank { "chrome" }
        val alpn = json.optString("alpn").trim()

        return Profile(
            name = name,
            protocol = Protocol.VMESS,
            server = server,
            port = port,
            credential = uuid,
            vmessSecurity = vmessSecurity,
            alterId = alterId.coerceIn(0, 65535),
            security = security,
            sni = sni,
            fingerprint = fp,
            alpn = alpn,
            network = network,
            path = path,
            host = host,
            serviceName = serviceName,
            importedConfig = null,
            plaintext = security == Security.NONE,
        )
    }

    private fun parseVlessOrTrojan(link: String, protocol: Protocol): Profile {
        val uri = URI(link)
        val host = uri.host?.removeSurrounding("[", "]")?.trim()
            ?: throw IllegalArgumentException("Link has no host")
        val port = uri.port.takeIf { it in 1..65535 } ?: 443
        val credential = uri.rawUserInfo?.let { decodeUrl(it) }?.trim().orEmpty()
        if (credential.isBlank()) {
            throw IllegalArgumentException(
                if (protocol == Protocol.VLESS) "VLESS link missing UUID" else "Trojan link missing password",
            )
        }

        val query = parseQuery(uri.rawQuery)
        val name = uri.rawFragment
            ?.let { decodeUrl(it) }
            ?.trim()
            ?.ifBlank { null }
            ?: "$host:$port"

        val network = parseNetwork(query["type"].orEmpty())
        val defaultSec = if (protocol == Protocol.TROJAN) "tls" else "none"
        val secWire = query["security"]?.lowercase()?.ifBlank { defaultSec } ?: defaultSec
        val security = when (secWire) {
            "tls" -> Security.TLS
            "reality" -> Security.REALITY
            "none" -> Security.NONE
            else -> if (protocol == Protocol.TROJAN) Security.TLS else Security.NONE
        }

        val reqHost = query["host"].orEmpty().trim()
        val sni = query["sni"]?.trim()?.ifBlank { null }
            ?: query["peer"]?.trim()?.ifBlank { null }
            ?: if (security != Security.NONE) reqHost else ""
        val rawPath = query["path"].orEmpty().trim()
        val serviceName = query["servicename"]?.trim()?.ifBlank { null }
            ?: if (network == Network.GRPC) rawPath.removePrefix("/") else ""
        val path = if (network == Network.GRPC) "/" else rawPath.ifBlank { "/" }
        val flow = if (protocol == Protocol.VLESS) query["flow"].orEmpty().trim() else ""
        val fingerprint = query["fp"]?.trim()?.ifBlank { null } ?: "chrome"
        val alpn = query["alpn"].orEmpty().trim()
        val publicKey = query["pbk"].orEmpty().trim()
        val shortId = query["sid"].orEmpty().trim()
        val spiderX = query["spx"]?.trim()?.ifBlank { null } ?: "/"

        return Profile(
            name = name,
            protocol = protocol,
            server = host,
            port = port,
            credential = credential,
            flow = flow,
            security = security,
            sni = sni,
            fingerprint = fingerprint,
            alpn = alpn,
            publicKey = publicKey,
            shortId = shortId,
            spiderX = spiderX,
            network = network,
            path = path,
            host = reqHost,
            serviceName = serviceName,
            importedConfig = null,
            plaintext = security == Security.NONE,
        )
    }

    private fun parseNetwork(wire: String): Network = when (wire.lowercase()) {
        "ws", "websocket" -> Network.WS
        "httpupgrade" -> Network.HTTPUPGRADE
        "grpc" -> Network.GRPC
        "xhttp", "splithttp" -> Network.XHTTP
        else -> Network.TCP
    }

    private fun decodeBase64Flexible(input: String): String? {
        val cleaned = input.replace("\n", "").replace("\r", "").replace(" ", "")
        val flagsList = listOf(
            Base64.DEFAULT,
            Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        for (flags in flagsList) {
            val decoded = runCatching {
                String(Base64.decode(cleaned, flags), Charsets.UTF_8)
            }.getOrNull()
            if (decoded != null && decoded.trimStart().startsWith("{")) {
                return decoded
            }
        }
        return null
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (part in raw.split('&')) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            val key = decodeUrl(if (eq >= 0) part.substring(0, eq) else part).lowercase()
            val value = decodeUrl(if (eq >= 0) part.substring(eq + 1) else "")
            result.putIfAbsent(key, value)
        }
        return result
    }

    private fun decodeUrl(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
