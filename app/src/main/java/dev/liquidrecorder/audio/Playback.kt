// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import dev.liquidrecorder.service.PlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lo que se está reproduciendo: una grabación de la biblioteca o un archivo de afuera. */
data class PlayItem(val recordingId: Long = 0, val uri: Uri, val title: String)

/**
 * Un único reproductor para toda la app, compartido con [PlaybackService] (controles en la
 * notificación y la pantalla de bloqueo). De los videos solo se reproduce el audio: la pista de
 * video está desactivada y nunca se decodifica.
 */
object Playback {
    private var player: ExoPlayer? = null
    private val _item = MutableStateFlow<PlayItem?>(null)
    val item = _item.asStateFlow()

    fun get(context: Context): ExoPlayer = player ?: ExoPlayer.Builder(context.applicationContext)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .setSeekForwardIncrementMs(10_000)
        .setSeekBackIncrementMs(10_000)
        .build()
        .also { p ->
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            player = p
        }

    /** Carga [item] (si no es el actual) y lo deja listo, en pausa o sonando. */
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    fun open(context: Context, item: PlayItem, play: Boolean, speed: Float, skipSilence: Boolean) {
        val p = get(context)
        if (_item.value?.uri != item.uri) {
            p.setMediaItem(
                MediaItem.Builder().setUri(item.uri).setMediaId(item.uri.toString())
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).build())
                    .build(),
            )
            p.prepare()
            _item.value = item
        }
        p.playbackParameters = PlaybackParameters(speed)
        p.skipSilenceEnabled = skipSilence
        if (play) {
            startService(context)
            p.play()
        }
    }

    fun startService(context: Context) {
        runCatching { context.startService(Intent(context, PlaybackService::class.java)) }
    }

    /** Si se borra o cambia el archivo que está cargado. */
    fun closeIf(recordingId: Long) {
        if (_item.value?.recordingId == recordingId) stop()
    }

    fun stop() {
        player?.stop()
        player?.clearMediaItems()
        _item.value = null
    }

    fun pause() {
        player?.pause()
    }
}
