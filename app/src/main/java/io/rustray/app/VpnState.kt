package io.rustray.app

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.File

enum class VpnPhase { STOPPED, CONNECTING, CONNECTED, STOPPING, ERROR }

object VpnState {
    @Volatile var phase: VpnPhase = VpnPhase.STOPPED
    @Volatile var profileName: String = ""
    @Volatile var server: String = ""
    @Volatile var error: String? = null
    @Volatile var startedAtElapsed: Long = 0L
    @Volatile var profileId: String? = null
    @Volatile var uplinkBytes: Long = 0L
    @Volatile var downlinkBytes: Long = 0L
    @Volatile var activeConnections: Int = 0
    @Volatile var sshStats: String = ""

    @Volatile
    private var stateFile: File? = null

    fun init(context: Context) {
        if (stateFile == null) {
            stateFile = File(context.applicationContext.cacheDir, "vpn_state.json")
        }
    }

    @Synchronized
    fun reset() {
        phase = VpnPhase.STOPPED
        error = null
        startedAtElapsed = 0L
        profileId = null
        profileName = ""
        server = ""
        uplinkBytes = 0L
        downlinkBytes = 0L
        activeConnections = 0
        sshStats = ""
        persist()
    }

    @Synchronized
    fun connecting(profile: Profile) {
        phase = VpnPhase.CONNECTING
        profileId = profile.id
        profileName = profile.name
        server = profile.endpoint()
        error = null
        startedAtElapsed = 0L
        uplinkBytes = 0L
        downlinkBytes = 0L
        activeConnections = 0
        sshStats = ""
        persist()
    }

    @Synchronized
    fun connected() {
        phase = VpnPhase.CONNECTED
        error = null
        startedAtElapsed = SystemClock.elapsedRealtime()
        persist()
    }

    @Synchronized
    fun stopping() {
        phase = VpnPhase.STOPPING
        persist()
    }

    @Synchronized
    fun stopped() {
        phase = VpnPhase.STOPPED
        error = null
        startedAtElapsed = 0L
        profileId = null
        profileName = ""
        server = ""
        uplinkBytes = 0L
        downlinkBytes = 0L
        activeConnections = 0
        sshStats = ""
        persist()
    }

    @Synchronized
    fun failed(message: String) {
        phase = VpnPhase.ERROR
        error = message
        startedAtElapsed = 0L
        uplinkBytes = 0L
        downlinkBytes = 0L
        activeConnections = 0
        persist()
    }

    @Synchronized
    fun updateTraffic(up: Long, down: Long, connections: Int, sshSummary: String) {
        uplinkBytes = up
        downlinkBytes = down
        activeConnections = connections
        sshStats = sshSummary
        persist()
    }

    @Synchronized
    fun syncFromDisk(context: Context) {
        init(context)
        val file = stateFile ?: return
        if (!file.exists()) return
        runCatching {
            val json = JSONObject(file.readText())
            val parsedPhase = runCatching { VpnPhase.valueOf(json.optString("phase", "STOPPED")) }
                .getOrDefault(VpnPhase.STOPPED)
            val started = json.optLong("startedAtElapsed", 0L)
            // If device rebooted after state file was written, treat stale active state as STOPPED.
            if (started > SystemClock.elapsedRealtime() + 5000L) {
                phase = VpnPhase.STOPPED
                profileId = null
                profileName = ""
                server = ""
                error = null
                startedAtElapsed = 0L
                uplinkBytes = 0L
                downlinkBytes = 0L
                activeConnections = 0
                sshStats = ""
                return
            }
            phase = parsedPhase
            profileId = json.optString("profileId").takeIf { it.isNotEmpty() }
            profileName = json.optString("profileName")
            server = json.optString("server")
            error = json.optString("error").takeIf { it.isNotEmpty() }
            startedAtElapsed = started
            uplinkBytes = json.optLong("uplinkBytes", 0L)
            downlinkBytes = json.optLong("downlinkBytes", 0L)
            activeConnections = json.optInt("activeConnections", 0)
            sshStats = json.optString("sshStats")
        }
    }

    private fun persist() {
        val file = stateFile ?: return
        runCatching {
            val json = JSONObject()
                .put("phase", phase.name)
                .put("profileId", profileId.orEmpty())
                .put("profileName", profileName)
                .put("server", server)
                .put("error", error.orEmpty())
                .put("startedAtElapsed", startedAtElapsed)
                .put("uplinkBytes", uplinkBytes)
                .put("downlinkBytes", downlinkBytes)
                .put("activeConnections", activeConnections)
                .put("sshStats", sshStats)
            val tmp = File(file.parentFile, "vpn_state.json.tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(json.toString())
                tmp.delete()
            }
        }
    }
}
