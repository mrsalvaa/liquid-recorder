// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import dev.liquidrecorder.MainActivity
import dev.liquidrecorder.R
import dev.liquidrecorder.audio.RecStatus
import dev.liquidrecorder.audio.Recorder
import dev.liquidrecorder.core.text.TimeText
import dev.liquidrecorder.data.Library
import dev.liquidrecorder.service.RecordingService

/**
 * Widget de la pantalla de inicio: botón para grabar (abre la app y empieza) o, mientras se graba,
 * el tiempo corriendo y los botones de marcador y detener, que funcionan sin abrir la app.
 */
class RecordWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(context)) }
    }

    companion object {
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, RecordWidget::class.java))
            if (ids.isEmpty()) return
            val v = views(context)
            ids.forEach { manager.updateAppWidget(it, v) }
        }

        private fun views(context: Context): RemoteViews {
            val s = Recorder.state.value
            val v = RemoteViews(context.packageName, R.layout.widget_record)
            val record = PendingIntent.getActivity(
                context, 10,
                Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_RECORD).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val open = PendingIntent.getActivity(
                context, 11, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
            )
            fun service(action: String) = PendingIntent.getService(
                context, action.hashCode(), Intent(context, RecordingService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.widget_root, open)
            if (s.active) {
                val paused = s.status == RecStatus.PAUSED
                v.setViewVisibility(R.id.widget_idle, View.GONE)
                v.setViewVisibility(R.id.widget_active, View.VISIBLE)
                v.setChronometer(R.id.widget_timer, SystemClock.elapsedRealtime() - s.elapsedMs, null, !paused)
                v.setTextViewText(R.id.widget_status, context.getString(if (paused) R.string.notif_paused else R.string.notif_recording))
                v.setImageViewResource(R.id.widget_pause, if (paused) R.drawable.ic_notif_record else R.drawable.ic_notif_pause)
                v.setContentDescription(R.id.widget_pause, context.getString(if (paused) R.string.resume else R.string.pause))
                v.setOnClickPendingIntent(R.id.widget_pause, service(if (paused) RecordingService.ACTION_RESUME else RecordingService.ACTION_PAUSE))
                v.setOnClickPendingIntent(R.id.widget_marker, service(RecordingService.ACTION_MARKER))
                v.setOnClickPendingIntent(R.id.widget_stop, service(RecordingService.ACTION_STOP))
            } else {
                v.setViewVisibility(R.id.widget_idle, View.VISIBLE)
                v.setViewVisibility(R.id.widget_active, View.GONE)
                v.setOnClickPendingIntent(R.id.widget_record, record)
                val last = runCatching { Library.store.value.active.maxByOrNull { it.createdAt } }.getOrNull()
                v.setTextViewText(
                    R.id.widget_last,
                    if (last == null) context.getString(R.string.widget_tap_to_record)
                    else last.title + " · " + TimeText.format(last.durationMs),
                )
            }
            return v
        }
    }
}
