package io.rustray.app

enum class VpnPhase { STOPPED, CONNECTING, CONNECTED, STOPPING, ERROR }

object VpnState {
    @Volatile var phase: VpnPhase = VpnPhase.STOPPED
    @Volatile var profileName: String = ""
    @Volatile var server: String = ""
    @Volatile var error: String? = null
    @Volatile var startedAtElapsed: Long = 0L
    @Volatile var profileId: String? = null

    @Synchronized
    fun reset() {
        phase = VpnPhase.STOPPED
        error = null
        startedAtElapsed = 0L
        profileId = null
        profileName = ""
        server = ""
    }

    @Synchronized
    fun connecting(profile: Profile) {
        phase = VpnPhase.CONNECTING
        profileId = profile.id
        profileName = profile.name
        server = profile.endpoint()
        error = null
        startedAtElapsed = 0L
    }

    @Synchronized
    fun connected() {
        phase = VpnPhase.CONNECTED
        error = null
        startedAtElapsed = android.os.SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun stopping() {
        phase = VpnPhase.STOPPING
    }

    @Synchronized
    fun stopped() {
        phase = VpnPhase.STOPPED
        error = null
        startedAtElapsed = 0L
        profileId = null
        profileName = ""
        server = ""
    }

    @Synchronized
    fun failed(message: String) {
        phase = VpnPhase.ERROR
        error = message
        startedAtElapsed = 0L
    }
}
