package io.rustray.app

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

class RustRayVpnTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        when (VpnState.phase) {
            VpnPhase.CONNECTED, VpnPhase.CONNECTING -> {
                startService(
                    Intent(this, AppVpnService::class.java).setAction(AppVpnService.ACTION_STOP),
                )
            }

            VpnPhase.STOPPING -> Unit

            else -> {
                val store = ProfileStore(this)
                val profiles = store.load()
                val profile = store.selectedId()?.let { id -> profiles.firstOrNull { it.id == id } }
                    ?: profiles.firstOrNull()
                val consent = runCatching { VpnService.prepare(this) }.getOrNull()
                if (profile != null && consent == null) {
                    if (store.selectedId() != profile.id) store.select(profile.id)
                    val svc = Intent(this, AppVpnService::class.java)
                        .setAction(AppVpnService.ACTION_CONNECT)
                        .putExtra(AppVpnService.EXTRA_PROFILE_ID, profile.id)
                    ContextCompat.startForegroundService(this, svc)
                } else {
                    openMain(autoConnect = true)
                }
            }
        }
        syncTile()
    }

    private fun openMain(autoConnect: Boolean) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_AUTO_CONNECT, autoConnect)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this,
                301,
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
        val active = phase == VpnPhase.CONNECTED || phase == VpnPhase.CONNECTING
        val profile = if (active && VpnState.profileId != null) {
            profiles.firstOrNull { it.id == VpnState.profileId }
        } else {
            store.selectedId()?.let { id -> profiles.firstOrNull { it.id == id } }
                ?: profiles.firstOrNull()
        }

        tile.state = when (phase) {
            VpnPhase.CONNECTED, VpnPhase.CONNECTING -> Tile.STATE_ACTIVE
            else -> if (profile == null) Tile.STATE_UNAVAILABLE else Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.tile_vpn_label)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when {
                phase == VpnPhase.CONNECTING -> getString(R.string.status_connecting)
                active && VpnState.profileName.isNotBlank() -> VpnState.profileName
                profile != null -> profile.name
                else -> getString(R.string.tile_no_profiles)
            }
        }
        tile.updateTile()
    }
}
