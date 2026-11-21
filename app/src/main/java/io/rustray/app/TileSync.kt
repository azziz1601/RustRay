package io.rustray.app

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService

object TileSync {
    fun requestUpdates(context: Context) {
        runCatching {
            TileService.requestListeningState(
                context,
                ComponentName(context, RustRayVpnTileService::class.java),
            )
        }
        runCatching {
            TileService.requestListeningState(
                context,
                ComponentName(context, RustRayProfileTileService::class.java),
            )
        }
    }
}
