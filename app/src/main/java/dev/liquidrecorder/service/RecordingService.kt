// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.content.ComponentName
import dev.liquidrecorder.MainActivity
import dev.liquidrecorder.R
import dev.liquidrecorder.audio.Playback
import dev.liquidrecorder.audio.RecState
import dev.liquidrecorder.audio.RecStatus
import dev.liquidrecorder.audio.Recorder
import dev.liquidrecorder.data.Stores
import dev.liquidrecorder.widget.RecordWidget

/**
 * Mantiene la grabación con la pantalla apagada o la app en segundo plano: servicio en primer plano
 * de tipo micrófono, con la notificación obligatoria (pausar, marcador y detener).
 */
class RecordingService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_PAUSE -> Recorder.pause()
            ACTION_RESUME -> Recorder.resume()
            ACTION_MARKER -> Recorder.addMarker()
            ACTION_STOP -> Recorder.stop()
            else -> if (!Recorder.state.value.active) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        ensureChannel(this)
        startForeground(NOTIFICATION_ID, build(Recorder.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        foreground = true
        Playback.pause()
        val error = Recorder.start(this, Stores.settings.value)
        if (error != null) {
            Recorder.setListener(null)
            finish()
            RecorderErrors.post(error)
            return
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LiquidRecorder:recording")
            .apply { setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L) }
        Recorder.setListener { s -> onState(s) }
        onState(Recorder.state.value)
    }

    private var lastShown: Triple<RecStatus, Int, Boolean>? = null

    private fun onState(s: RecState) {
        refreshOutside(this)
        if (s.status == RecStatus.IDLE) {
            finish()
            return
        }
        // La notificación solo cambia si cambió algo que muestra (el reloj corre solo).
        val key = Triple(s.status, s.markers.size, s.silenced)
        if (key == lastShown) return
        lastShown = key
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(s))
    }

    private fun finish() {
        Recorder.setListener(null)
        wakeLock?.release()
        wakeLock = null
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
        refreshOutside(this)
    }

    override fun onDestroy() {
        // Si Android cierra el servicio, la grabación se guarda en lugar de perderse.
        if (Recorder.state.value.active) Recorder.stop()
        wakeLock?.release()
        super.onDestroy()
    }

    private fun build(s: RecState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun action(icon: Int, label: Int, act: String) = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, icon),
            getString(label),
            PendingIntent.getService(this, act.hashCode(), Intent(this, RecordingService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE),
        ).build()
        val paused = s.status == RecStatus.PAUSED
        val text = buildList {
            add(getString(if (paused) R.string.notif_paused else R.string.notif_recording))
            if (s.markers.isNotEmpty()) add(resources.getQuantityString(R.plurals.markers_count, s.markers.size, s.markers.size))
            if (s.silenced) add(getString(R.string.silenced_short))
        }.joinToString(" · ")
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(s.title.ifEmpty { getString(R.string.notif_recording) })
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setUsesChronometer(!paused)
            .setShowWhen(!paused)
            .setWhen(System.currentTimeMillis() - s.elapsedMs)
            .addAction(if (paused) action(R.drawable.ic_notif_record, R.string.resume, ACTION_RESUME) else action(R.drawable.ic_notif_pause, R.string.pause, ACTION_PAUSE))
            .addAction(action(R.drawable.ic_notif_marker, R.string.marker, ACTION_MARKER))
            .addAction(action(R.drawable.ic_notif_stop, R.string.stop, ACTION_STOP))
            .build()
    }

    companion object {
        const val CHANNEL = "recording"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "dev.liquidrecorder.START"
        const val ACTION_PAUSE = "dev.liquidrecorder.PAUSE"
        const val ACTION_RESUME = "dev.liquidrecorder.RESUME"
        const val ACTION_MARKER = "dev.liquidrecorder.MARKER"
        const val ACTION_STOP = "dev.liquidrecorder.STOP"

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, context.getString(R.string.channel_recording), NotificationManager.IMPORTANCE_LOW).apply {
                        setShowBadge(false)
                    },
                )
            }
        }

        /** Empezar a grabar. Hay que llamarlo con la app visible y el permiso del micrófono dado. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, RecordingService::class.java).setAction(ACTION_START))
        }

        fun command(context: Context, action: String) {
            runCatching { context.startService(Intent(context, RecordingService::class.java).setAction(action)) }
        }

        /** Widget y ajuste rápido muestran el estado. */
        fun refreshOutside(context: Context) {
            RecordWidget.update(context)
            runCatching { TileService.requestListeningState(context, ComponentName(context, RecordTileService::class.java)) }
        }
    }
}

/** Errores al arrancar desde el servicio, para mostrarlos en la pantalla. */
object RecorderErrors {
    val last = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    fun post(e: String) { last.value = e }
}
