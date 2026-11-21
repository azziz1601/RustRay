package io.rustray.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews
import androidx.core.content.ContextCompat

class RustRayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val views = buildViews(context)
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            handleToggle(context)
        }
    }

    private fun handleToggle(context: Context) {
        when (VpnState.phase) {
            VpnPhase.CONNECTED, VpnPhase.CONNECTING -> {
                context.startService(
                    Intent(context, AppVpnService::class.java).setAction(AppVpnService.ACTION_STOP),
                )
            }

            VpnPhase.STOPPING -> Unit

            else -> {
                val store = ProfileStore(context)
                val profiles = store.load()
                val profile = store.selectedId()?.let { id -> profiles.firstOrNull { it.id == id } }
                    ?: profiles.firstOrNull()
                val consent = runCatching { VpnService.prepare(context) }.getOrNull()
                if (profile != null && consent == null) {
                    if (store.selectedId() != profile.id) store.select(profile.id)
                    val svc = Intent(context, AppVpnService::class.java)
                        .setAction(AppVpnService.ACTION_CONNECT)
                        .putExtra(AppVpnService.EXTRA_PROFILE_ID, profile.id)
                    ContextCompat.startForegroundService(context, svc)
                } else {
                    val launch = Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_AUTO_CONNECT, true)
                    context.startActivity(launch)
                }
            }
        }
        refreshAll(context)
    }

    companion object {
        const val ACTION_TOGGLE = "io.rustray.app.WIDGET_TOGGLE"

        fun refreshAll(context: Context) {
            runCatching {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, RustRayWidgetProvider::class.java))
                if (ids.isNotEmpty()) {
                    val views = buildViews(context)
                    for (id in ids) {
                        mgr.updateAppWidget(id, views)
                    }
                }
            }
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_rustray)
            val store = ProfileStore(context)
            val profiles = store.load()
            val phase = VpnState.phase
            val active = phase == VpnPhase.CONNECTED || phase == VpnPhase.CONNECTING
            val profile = if (active && VpnState.profileId != null) {
                profiles.firstOrNull { it.id == VpnState.profileId }
            } else {
                store.selectedId()?.let { id -> profiles.firstOrNull { it.id == id } }
                    ?: profiles.firstOrNull()
            }

            val name = when {
                active && VpnState.profileName.isNotBlank() -> VpnState.profileName
                profile != null -> profile.name
                else -> context.getString(R.string.no_profile_selected)
            }
            val statusText = when (phase) {
                VpnPhase.CONNECTED -> context.getString(R.string.status_connected)
                VpnPhase.CONNECTING -> context.getString(R.string.status_connecting)
                VpnPhase.STOPPING -> context.getString(R.string.status_disconnecting)
                VpnPhase.ERROR -> context.getString(R.string.status_failed)
                VpnPhase.STOPPED -> context.getString(R.string.status_disconnected)
            }
            val statusColor = ContextCompat.getColor(
                context,
                when (phase) {
                    VpnPhase.CONNECTED -> R.color.primary
                    VpnPhase.CONNECTING, VpnPhase.STOPPING -> R.color.warning
                    VpnPhase.ERROR -> R.color.error
                    VpnPhase.STOPPED -> R.color.text_dim
                },
            )

            views.setTextViewText(R.id.tvWidgetProfile, name)
            views.setTextViewText(R.id.tvWidgetStatus, statusText)
            views.setTextColor(R.id.tvWidgetStatus, statusColor)

            views.setTextViewText(
                R.id.btnWidgetToggle,
                if (active) context.getString(R.string.disconnect) else context.getString(R.string.connect),
            )
            views.setInt(
                R.id.btnWidgetToggle,
                "setBackgroundResource",
                if (active) R.drawable.bg_widget_btn_danger else R.drawable.bg_widget_btn_primary,
            )
            views.setTextColor(
                R.id.btnWidgetToggle,
                ContextCompat.getColor(context, if (active) android.R.color.white else R.color.on_primary),
            )

            val toggleIntent = Intent(context, RustRayWidgetProvider::class.java).setAction(ACTION_TOGGLE)
            val togglePi = PendingIntent.getBroadcast(
                context,
                201,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.btnWidgetToggle, togglePi)

            val openIntent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val openPi = PendingIntent.getActivity(
                context,
                202,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widgetInfo, openPi)
            return views
        }
    }
}
