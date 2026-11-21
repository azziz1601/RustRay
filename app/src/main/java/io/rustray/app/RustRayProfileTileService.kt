package io.rustray.app

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.ContextThemeWrapper
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

class RustRayProfileTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        val store = ProfileStore(this)
        val profiles = store.load()
        if (profiles.isEmpty()) {
            openMain()
            return
        }

        val phase = VpnState.phase
        val isActive = phase == VpnPhase.CONNECTED || phase == VpnPhase.CONNECTING
        val currentId = if (isActive && VpnState.profileId != null) {
            VpnState.profileId
        } else {
            store.selectedId()
        }

        val labels = mutableListOf<String>()
        if (isActive) {
            labels.add("↻ ${getString(R.string.reconnect)}")
        }
        for (p in profiles) {
            val mark = if (p.id == currentId) "✓ " else ""
            labels.add("$mark${p.name} (${p.protocol.label})")
        }

        val themed = ContextThemeWrapper(this, R.style.Theme_RustRay)
        val dialog = AlertDialog.Builder(themed)
            .setTitle(R.string.tile_profile_label)
            .setItems(labels.toTypedArray()) { _, which ->
                if (isActive && which == 0) {
                    val targetId = currentId ?: profiles.first().id
                    triggerReconnect(targetId)
                } else {
                    val idx = if (isActive) which - 1 else which
                    val chosen = profiles.getOrNull(idx) ?: return@setItems
                    store.select(chosen.id)
                    RustRayWidgetProvider.refreshAll(this)
                    if (isActive) {
                        triggerReconnect(chosen.id)
                    } else if (runCatching { VpnService.prepare(this) }.getOrNull() == null) {
                        val svc = Intent(this, AppVpnService::class.java)
                            .setAction(AppVpnService.ACTION_CONNECT)
                            .putExtra(AppVpnService.EXTRA_PROFILE_ID, chosen.id)
                        ContextCompat.startForegroundService(this, svc)
                    } else {
                        openMain()
                    }
                }
                syncTile()
                TileSync.requestUpdates(this)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        showDialog(dialog)
    }

    private fun triggerReconnect(profileId: String) {
        val svc = Intent(this, AppVpnService::class.java)
            .setAction(AppVpnService.ACTION_RECONNECT)
            .putExtra(AppVpnService.EXTRA_PROFILE_ID, profileId)
        ContextCompat.startForegroundService(this, svc)
    }

    private fun openMain() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this,
                302,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun syncTile() {
        val tile = qsTile ?: return
        val store = ProfileStore(this)
        val profiles = store.load()
        val phase = VpnState.phase
        val isActive = phase == VpnPhase.CONNECTED || phase == VpnPhase.CONNECTING
        val profile = if (isActive && VpnState.profileId != null) {
            profiles.firstOrNull { it.id == VpnState.profileId }
        } else {
            store.selectedId()?.let { id -> profiles.firstOrNull { it.id == id } }
                ?: profiles.firstOrNull()
        }

        tile.state = when {
            profiles.isEmpty() -> Tile.STATE_UNAVAILABLE
            isActive -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.tile_profile_label)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = profile?.name ?: getString(R.string.tile_no_profiles)
        }
        tile.updateTile()
    }
}
