// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.service

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.liquidrecorder.MainActivity
import dev.liquidrecorder.audio.Playback

/** Reproducción en segundo plano con controles en la notificación y la pantalla de bloqueo. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, Playback.get(this)).setSessionActivity(open).build().also { addSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            p?.pause()
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
