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
import android.os.Build
import android.os.IBinder
import dev.liquidrecorder.MainActivity
import dev.liquidrecorder.R
import dev.liquidrecorder.data.Job
import dev.liquidrecorder.data.Jobs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Mientras dura una tarea larga (limpiar ruido, exportar, editar), una notificación con la barra de
 * avance, el tiempo que falta y Cancelar. Como servicio en primer plano, Android no la corta si se
 * sale de la app. Tipo "procesamiento de medios" (Android 15) o "sincronización" (Android 14).
 */
class JobService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            Jobs.cancel()
            return START_NOT_STICKY
        }
        ensureChannel(this)
        val job = Jobs.current.value
        if (job == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val type = when {
            Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        runCatching {
            if (type != 0) startForeground(NOTIFICATION_ID, build(job), type) else startForeground(NOTIFICATION_ID, build(job))
        }.onFailure {
            stopSelf()
            return START_NOT_STICKY
        }
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        scope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            while (true) {
                val j = Jobs.current.value ?: break
                nm.notify(NOTIFICATION_ID, build(j))
                delay(1000)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(job: Job): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, 2, Intent(this, JobService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        val percent = (job.progress * 100).toInt().coerceIn(0, 100)
        val text = buildList {
            add("$percent %")
            job.remainingMs?.let { add(remainingText(this@JobService, it)) }
        }.joinToString(" · ")
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(job.label)
            .setContentText(text)
            .setProgress(100, percent, job.progress < 0.01f)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, getString(R.string.cancel), cancel).build())
            .build()
    }

    companion object {
        const val CHANNEL = "tasks"
        const val NOTIFICATION_ID = 2
        const val ACTION_CANCEL = "dev.liquidrecorder.CANCEL_JOB"

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.channel_tasks), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
            }
        }

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, JobService::class.java)) }
        }

        /** "Faltan unos 3 min" / "Faltan unos segundos". */
        fun remainingText(context: Context, ms: Long): String = when {
            ms < 60_000 -> context.getString(R.string.remaining_seconds)
            ms < 3_600_000 -> context.getString(R.string.remaining_minutes, ((ms + 30_000) / 60_000).toInt())
            else -> context.getString(R.string.remaining_hours, ms / 3_600_000, (ms % 3_600_000) / 60_000)
        }
    }
}
