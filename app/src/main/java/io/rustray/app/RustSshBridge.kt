package io.rustray.app

import org.json.JSONObject

object RustSshBridge {

    @Volatile
    private var libraryLoaded = false

    @Volatile
    private var loadError: Throwable? = null

    private fun ensureLoaded() {
        if (libraryLoaded) return
        synchronized(this) {
            if (libraryLoaded) return
            try {
                System.loadLibrary("rustray_ssh")
                libraryLoaded = true
                loadError = null
            } catch (error: Throwable) {
                loadError = error
                throw IllegalStateException(
                    "Native SSH engine (librustray_ssh.so) is not bundled in this build",
                    error,
                )
            }
        }
    }

    fun start(profile: Profile, settings: AppSettings, vpnService: AppVpnService): Int {
        ensureLoaded()
        val payload = JSONObject()
            .put("host", profile.server)
            .put("port", profile.port)
            .put("username", profile.sshUsername)
            .put("password", profile.credential)
            .put("transportMode", profile.sshTransport.wire)
            .put("payload", profile.sshPayload)
            .put("proxy", profile.sshProxy)
            .put("sni", profile.sni)
            .put("wsHost", profile.host)
            .put("wsPath", profile.path.ifBlank { "/" })
            .put("udpgwPort", profile.sshUdpgwPort)
            .put("poolSize", profile.sshPoolSize)
            .put("udpMtu", settings.tunMtu)
            .put("enhanced", profile.sshEnhanced)
            .put("rrBlobBase64", profile.rrBlobBase64)
            .toString()

        val port = nativeStart(payload, vpnService)
        check(port in 1..65535) { "SSH bridge returned invalid local SOCKS port: $port" }
        return port
    }

    fun stop() {
        if (!libraryLoaded) return
        runCatching { nativeStop() }
    }

    fun rebind() {
        if (!libraryLoaded) return
        runCatching { nativeRebind() }
    }

    fun statsSummary(): String {
        if (!libraryLoaded) return ""
        return runCatching { nativeGetStats() }.getOrDefault("")
    }

    fun encryptRr(plainJson: String): ByteArray {
        ensureLoaded()
        return nativeEncryptRr(plainJson)
    }

    fun decryptRr(blob: ByteArray): String {
        ensureLoaded()
        return nativeDecryptRr(blob)
    }

    @JvmStatic
    private external fun nativeStart(configJson: String, vpnService: AppVpnService): Int

    @JvmStatic
    private external fun nativeStop()

    @JvmStatic
    private external fun nativeRebind()

    @JvmStatic
    private external fun nativeGetStats(): String

    @JvmStatic
    private external fun nativeEncryptRr(plainJson: String): ByteArray

    @JvmStatic
    private external fun nativeDecryptRr(blob: ByteArray): String
}
