package io.rustray.app

import android.util.Base64
import org.json.JSONObject
import java.util.UUID

data class RrExportOptions(
    val fileName: String,
    val lockServer: Boolean,
    val lockPayload: Boolean,
    val expiryEpochMs: Long,
    val notesHtml: String,
)

data class RrImportResult(
    val profile: Profile,
    val notesHtml: String,
)

object RrConfigCodec {

    private const val FORMAT_VERSION = 1

    fun exportToBytes(profile: Profile, options: RrExportOptions): ByteArray {
        val hydrated = ConfigBuilder.hydrateFromImportedConfig(profile)
        val cleanProfile = hydrated.copy(
            id = "",
            name = options.fileName.removeSuffix(".rr").trim().ifBlank { hydrated.name },
            rrBlobBase64 = "",
            rrLockServer = false,
            rrLockPayload = false,
            rrExpiryEpochMs = 0L,
            rrNotesHtml = "",
        )
        val envelope = JSONObject()
            .put("v", FORMAT_VERSION)
            .put("lockServer", options.lockServer)
            .put("lockPayload", options.lockPayload)
            .put("expiryEpochMs", options.expiryEpochMs)
            .put("notesHtml", options.notesHtml.trim())
            .put("profile", cleanProfile.toJson())

        return RustSshBridge.encryptRr(envelope.toString())
    }

    fun importFromBytes(blob: ByteArray, fallbackName: String): RrImportResult {
        val plainJson = RustSshBridge.decryptRr(blob)
        val root = JSONObject(plainJson)
        val expiry = root.optLong("expiryEpochMs", 0L)
        if (expiry > 0L && System.currentTimeMillis() > expiry) {
            throw IllegalStateException("Config expired")
        }

        val lockServer = root.optBoolean("lockServer", false)
        val lockPayload = root.optBoolean("lockPayload", false)
        val notesHtml = root.optString("notesHtml", "").trim()
        val rawProfileJson = root.getJSONObject("profile")
        val rawProfile = Profile.fromJson(rawProfileJson)
        val baseName = rawProfile.name.ifBlank { fallbackName.removeSuffix(".rr") }.ifBlank { "RustRay Config" }

        val blobBase64 = if (lockServer || lockPayload || expiry > 0L) {
            Base64.encodeToString(blob, Base64.NO_WRAP)
        } else {
            ""
        }

        // Never persist locked fields in plaintext inside ProfileStore; keep only the encrypted .rr blob.
        val sanitized = rawProfile.copy(
            id = UUID.randomUUID().toString(),
            name = baseName,
            server = if (lockServer) "" else rawProfile.server,
            port = if (lockServer) 0 else rawProfile.port,
            credential = if (lockServer) "" else rawProfile.credential,
            sshUsername = if (lockServer) "" else rawProfile.sshUsername,
            sshPayload = if (lockPayload) "" else rawProfile.sshPayload,
            sshProxy = if (lockPayload) "" else rawProfile.sshProxy,
            v2rayCustomPayload = if (lockPayload) false else rawProfile.v2rayCustomPayload,
            sni = if (lockPayload) "" else rawProfile.sni,
            host = if (lockPayload) "" else rawProfile.host,
            path = if (lockPayload) "/" else rawProfile.path,
            serviceName = if (lockPayload) "" else rawProfile.serviceName,
            publicKey = if (lockPayload) "" else rawProfile.publicKey,
            shortId = if (lockPayload) "" else rawProfile.shortId,
            spiderX = if (lockPayload) "/" else rawProfile.spiderX,
            importedConfig = if (lockServer || lockPayload) null else rawProfile.importedConfig,
            rrBlobBase64 = blobBase64,
            rrLockServer = lockServer,
            rrLockPayload = lockPayload,
            rrExpiryEpochMs = expiry,
            rrNotesHtml = notesHtml,
        )

        return RrImportResult(profile = sanitized, notesHtml = notesHtml)
    }

    /**
     * Resolves locked fields for V2Ray (VLESS/VMess/Trojan) profiles immediately before
     * starting Xray, checking expiry and keeping user edits on unlocked fields.
     */
    fun resolveForConnect(profile: Profile): Profile {
        if (profile.rrExpiryEpochMs > 0L && System.currentTimeMillis() > profile.rrExpiryEpochMs) {
            throw IllegalStateException("Config expired")
        }
        if (profile.rrBlobBase64.isBlank()) return profile

        val raw = Base64.decode(profile.rrBlobBase64, Base64.DEFAULT)
        val plainJson = RustSshBridge.decryptRr(raw)
        val root = JSONObject(plainJson)
        val expiry = root.optLong("expiryEpochMs", 0L)
        if (expiry > 0L && System.currentTimeMillis() > expiry) {
            throw IllegalStateException("Config expired")
        }
        val lockServer = root.optBoolean("lockServer", false)
        val lockPayload = root.optBoolean("lockPayload", false)
        val locked = Profile.fromJson(root.getJSONObject("profile"))

        return profile.copy(
            server = if (lockServer) locked.server else profile.server,
            port = if (lockServer) locked.port else profile.port,
            credential = if (lockServer) locked.credential else profile.credential,
            sshUsername = if (lockServer) locked.sshUsername else profile.sshUsername,
            flow = if (lockServer) locked.flow else profile.flow,
            vmessSecurity = if (lockServer) locked.vmessSecurity else profile.vmessSecurity,
            alterId = if (lockServer) locked.alterId else profile.alterId,
            sshTransport = if (lockPayload) locked.sshTransport else profile.sshTransport,
            sshPayload = if (lockPayload) locked.sshPayload else profile.sshPayload,
            sshProxy = if (lockPayload) locked.sshProxy else profile.sshProxy,
            v2rayCustomPayload = if (lockPayload) locked.v2rayCustomPayload else profile.v2rayCustomPayload,
            network = if (lockPayload) locked.network else profile.network,
            security = if (lockPayload) locked.security else profile.security,
            sni = if (lockPayload) locked.sni else profile.sni,
            host = if (lockPayload) locked.host else profile.host,
            path = if (lockPayload) locked.path else profile.path,
            serviceName = if (lockPayload) locked.serviceName else profile.serviceName,
            fingerprint = if (lockPayload) locked.fingerprint else profile.fingerprint,
            alpn = if (lockPayload) locked.alpn else profile.alpn,
            publicKey = if (lockPayload) locked.publicKey else profile.publicKey,
            shortId = if (lockPayload) locked.shortId else profile.shortId,
            spiderX = if (lockPayload) locked.spiderX else profile.spiderX,
            importedConfig = if (lockServer && lockPayload) locked.importedConfig else null,
            plaintext = locked.plaintext,
        )
    }
}
