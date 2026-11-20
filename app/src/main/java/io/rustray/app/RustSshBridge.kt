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
            .put("sni", profile.sni)
            .put("wsHost", profile.host)
            .put("wsPath", profile.path.ifBlank { "/" })
            .put("udpgwPort", profile.sshUdpgwPort)
            .put("poolSize", profile.sshPoolSize)
            .put("udpMtu", settings.tunMtu)
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

    @JvmStatic
    private external fun nativeStart(configJson: String, vpnService: AppVpnService): Int

    @JvmStatic
    private external fun nativeStop()

    @JvmStatic
    private external fun nativeRebind()
}
