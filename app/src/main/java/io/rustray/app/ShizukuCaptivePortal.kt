package io.rustray.app

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

object ShizukuCaptivePortal {

    private const val PREFS_NAME = "rustray.shizuku_backup"
    private const val KEY_ACTIVE = "backup_active"
    private const val KEY_ORIG_MODE = "orig_captive_portal_mode"
    private const val KEY_ORIG_DETECT = "orig_captive_portal_detection_enabled"
    private const val SENTINEL_UNSET = "__UNSET__"

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "rustray-shizuku").apply { isDaemon = true }
    }

    enum class Status {
        NOT_RUNNING,
        NO_PERMISSION,
        READY,
    }

    fun status(): Status {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) return Status.NOT_RUNNING
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return if (granted) Status.READY else Status.NO_PERMISSION
    }

    fun humanStateSummary(context: Context): String {
        val resolver = context.contentResolver
        val mode = runCatching {
            Settings.Global.getString(resolver, "captive_portal_mode")
        }.getOrNull()
        val detect = runCatching {
            Settings.Global.getString(resolver, "captive_portal_detection_enabled")
        }.getOrNull()
        return when {
            mode == "0" && detect == "0" -> context.getString(R.string.captive_disabled)
            mode == "1" || detect == "1" -> context.getString(R.string.captive_enabled)
            else -> context.getString(R.string.captive_default)
        }
    }

    fun currentStateSummary(context: Context): String {
        val resolver = context.contentResolver
        val mode = runCatching {
            Settings.Global.getString(resolver, "captive_portal_mode")
        }.getOrNull() ?: "default"
        val detect = runCatching {
            Settings.Global.getString(resolver, "captive_portal_detection_enabled")
        }.getOrNull() ?: "default"
        return "mode=$mode, detection=$detect"
    }

    /**
     * Disables Android captive portal checks via Shizuku and backs up the original values
     * once so they can be cleanly restored when the VPN disconnects.
     */
    fun applyOnVpnStartAsync(context: Context) {
        val appContext = context.applicationContext
        worker.execute {
            if (status() != Status.READY) {
                EventLog.add("Shizuku: dilewati saat connect (status ${status().name})")
                return@execute
            }
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_ACTIVE, false)) {
                val resolver = appContext.contentResolver
                val origMode = runCatching {
                    Settings.Global.getString(resolver, "captive_portal_mode")
                }.getOrNull() ?: SENTINEL_UNSET
                val origDetect = runCatching {
                    Settings.Global.getString(resolver, "captive_portal_detection_enabled")
                }.getOrNull() ?: SENTINEL_UNSET
                prefs.edit()
                    .putBoolean(KEY_ACTIVE, true)
                    .putString(KEY_ORIG_MODE, origMode)
                    .putString(KEY_ORIG_DETECT, origDetect)
                    .apply()
            }
            val ok = runShell(
                "settings put global captive_portal_mode 0 && " +
                    "settings put global captive_portal_detection_enabled 0",
            )
            if (ok) {
                EventLog.add("Shizuku: captive_portal dinonaktifkan (0)")
            } else {
                EventLog.add("Shizuku: gagal menonaktifkan captive_portal")
            }
        }
    }

    /**
     * Restores the previously backed up captive portal values via Shizuku when the VPN stops.
     */
    fun restoreOnVpnStopAsync(context: Context) {
        val appContext = context.applicationContext
        worker.execute {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_ACTIVE, false)) return@execute
            if (status() != Status.READY) {
                EventLog.add("Shizuku: tidak dapat memulihkan captive_portal (Shizuku tidak aktif)")
                return@execute
            }
            val origMode = prefs.getString(KEY_ORIG_MODE, SENTINEL_UNSET) ?: SENTINEL_UNSET
            val origDetect = prefs.getString(KEY_ORIG_DETECT, SENTINEL_UNSET) ?: SENTINEL_UNSET
            val cmdMode = restoreCommand("captive_portal_mode", origMode)
            val cmdDetect = restoreCommand("captive_portal_detection_enabled", origDetect)
            val ok = runShell("$cmdMode && $cmdDetect")
            if (ok) {
                prefs.edit().putBoolean(KEY_ACTIVE, false).apply()
                EventLog.add("Shizuku: captive_portal dipulihkan ($origMode / $origDetect)")
            } else {
                EventLog.add("Shizuku: gagal memulihkan captive_portal")
            }
        }
    }

    /** Manual one-shot toggle from SettingsActivity (0 = disable, 1 = default enabled). */
    fun setManualAsync(value: Int, onDone: (Boolean) -> Unit) {
        val safeValue = if (value == 0) 0 else 1
        worker.execute {
            val ok = if (status() == Status.READY) {
                runShell(
                    "settings put global captive_portal_mode $safeValue && " +
                        "settings put global captive_portal_detection_enabled $safeValue",
                )
            } else {
                false
            }
            onDone(ok)
        }
    }

    private fun restoreCommand(key: String, saved: String): String {
        val clean = saved.trim()
        return if (clean == SENTINEL_UNSET || !clean.matches(Regex("[0-9]+"))) {
            "settings delete global $key"
        } else {
            "settings put global $key $clean"
        }
    }

    private fun runShell(command: String): Boolean {
        return runCatching {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            method.isAccessible = true
            val process = method.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null,
            ) as Process
            val exitCode = process.waitFor()
            runCatching { process.destroy() }
            exitCode == 0
        }.getOrDefault(false)
    }
}
