package io.rustray.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class Protocol(val wire: String, val label: String) {
    VLESS("vless", "VLESS"),
    VMESS("vmess", "VMess"),
    TROJAN("trojan", "Trojan");

    companion object {
        fun fromWire(value: String?): Protocol =
            entries.firstOrNull { it.wire == value } ?: VLESS
    }
}

enum class Network(val wire: String, val label: String) {
    TCP("tcp", "TCP"),
    WS("ws", "WebSocket"),
    HTTPUPGRADE("httpupgrade", "HTTPUpgrade"),
    GRPC("grpc", "gRPC"),
    XHTTP("xhttp", "XHTTP");

    companion object {
        fun fromWire(value: String?): Network =
            entries.firstOrNull { it.wire == value } ?: TCP
    }
}

enum class Security(val wire: String, val label: String) {
    NONE("none", "None"),
    TLS("tls", "TLS"),
    REALITY("reality", "REALITY");

    companion object {
        fun fromWire(value: String?): Security =
            entries.firstOrNull { it.wire == value } ?: TLS
    }
}

data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: Protocol,
    val server: String,
    val port: Int,
    val credential: String,
    val flow: String = "",
    val vmessSecurity: String = "auto",
    val alterId: Int = 0,
    val security: Security = Security.TLS,
    val sni: String = "",
    val fingerprint: String = "chrome",
    val alpn: String = "",
    val publicKey: String = "",
    val shortId: String = "",
    val spiderX: String = "",
    val network: Network = Network.TCP,
    val path: String = "/",
    val host: String = "",
    val serviceName: String = "",
    val importedConfig: String? = null,
    val plaintext: Boolean = false,
) {
    val isImported: Boolean get() = importedConfig != null

    fun endpoint(): String = if (port in 1..65535) "$server:$port" else server

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("protocol", protocol.wire)
        .put("server", server)
        .put("port", port)
        .put("credential", credential)
        .put("flow", flow)
        .put("vmessSecurity", vmessSecurity)
        .put("alterId", alterId)
        .put("security", security.wire)
        .put("sni", sni)
        .put("fingerprint", fingerprint)
        .put("alpn", alpn)
        .put("publicKey", publicKey)
        .put("shortId", shortId)
        .put("spiderX", spiderX)
        .put("network", network.wire)
        .put("path", path)
        .put("host", host)
        .put("serviceName", serviceName)
        .put("plaintext", plaintext)
        .apply { importedConfig?.let { put("importedConfig", it) } }

    companion object {
        fun fromJson(json: JSONObject): Profile = Profile(
            id = json.optString("id").ifEmpty { UUID.randomUUID().toString() },
            name = json.optString("name"),
            protocol = Protocol.fromWire(json.optString("protocol")),
            server = json.optString("server"),
            port = json.optInt("port", 443),
            credential = json.optString("credential"),
            flow = json.optString("flow"),
            vmessSecurity = json.optString("vmessSecurity", "auto"),
            alterId = json.optInt("alterId", 0),
            security = Security.fromWire(json.optString("security")),
            sni = json.optString("sni"),
            fingerprint = json.optString("fingerprint", "chrome"),
            alpn = json.optString("alpn"),
            publicKey = json.optString("publicKey"),
            shortId = json.optString("shortId"),
            spiderX = json.optString("spiderX"),
            network = Network.fromWire(json.optString("network")),
            path = json.optString("path", "/"),
            host = json.optString("host"),
            serviceName = json.optString("serviceName"),
            importedConfig = json.optString("importedConfig")
                .takeIf { it.isNotEmpty() && it != "null" },
            plaintext = json.optBoolean("plaintext", false),
        )

        fun encodeList(list: List<Profile>): String {
            val array = JSONArray()
            list.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        fun decodeList(raw: String?): List<Profile> {
            if (raw.isNullOrEmpty()) return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (index in 0 until array.length()) {
                        runCatching { array.getJSONObject(index) }
                            .getOrNull()
                            ?.let { add(fromJson(it)) }
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
}
