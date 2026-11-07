// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.ui.screens

import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import dev.liquidrecorder.R
import dev.liquidrecorder.audio.AudioDecoder
import dev.liquidrecorder.audio.PlayItem
import dev.liquidrecorder.audio.Playback
import dev.liquidrecorder.audio.Transcoder
import dev.liquidrecorder.core.text.TimeText
import dev.liquidrecorder.data.Actions
import dev.liquidrecorder.data.Library
import dev.liquidrecorder.data.Marker
import dev.liquidrecorder.data.Recording
import dev.liquidrecorder.data.Stores
import dev.liquidrecorder.ui.components.RenameWindow
import dev.liquidrecorder.ui.components.SwitchRow
import dev.liquidrecorder.ui.glass.DialogSpec
import dev.liquidrecorder.ui.glass.GlassButton
import dev.liquidrecorder.ui.glass.GlassCard
import dev.liquidrecorder.ui.glass.GlassChip
import dev.liquidrecorder.ui.glass.GlassIconButton
import dev.liquidrecorder.ui.glass.LocalDialog
import dev.liquidrecorder.ui.glass.LocalGlass
import dev.liquidrecorder.ui.glass.styled
import dev.liquidrecorder.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Qué se reproduce: una grabación o un archivo de afuera (audio o video). */
sealed interface PlayTarget {
    data class Saved(val id: Long) : PlayTarget
    data class External(val uri: Uri) : PlayTarget
}

val SPEEDS = listOf(50, 75, 100, 125, 150, 175, 200)

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
fun PlayerScreen(
    target: PlayTarget,
    padding: PaddingValues,
    onBack: () -> Unit,
    onEdit: (Recording) -> Unit,
    onImported: (Long) -> Unit,
) {
    val p = LocalGlass.current
    val context = LocalContext.current
    val dialog = LocalDialog.current
    val settings by Stores.settings.state.collectAsStateWithLifecycle()
    val lib by Library.state.collectAsStateWithLifecycle()
    val recording = (target as? PlayTarget.Saved)?.let { t -> lib.recordings.firstOrNull { it.id == t.id } }
    if (target is PlayTarget.Saved && recording == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val uri = remember(target, recording?.file) {
        when (target) {
            is PlayTarget.Saved -> Uri.fromFile(Library.fileOf(recording!!))
            is PlayTarget.External -> target.uri
        }
    }
    val title = recording?.title ?: remember(uri) { Actions.displayName(context, uri) }
    val player = remember { Playback.get(context) }
    val menu = rememberRecordingMenu(onOpen = {}, onEdit = onEdit, afterDelete = onBack)

    LaunchedEffect(uri) {
        Playback.open(context, PlayItem(recording?.id ?: 0, uri, title), play = false, speed = settings.speed / 100f, skipSilence = settings.skipSilence)
    }
    LaunchedEffect(settings.speed, settings.skipSilence) {
        player.setPlaybackSpeed(settings.speed / 100f)
        player.skipSilenceEnabled = settings.skipSilence
    }

    var playing by remember { mutableStateOf(player.isPlaying) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(recording?.durationMs ?: 0L) }
    var error by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) { error = true }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(player) {
        while (true) {
            withFrameNanos { }
            if (Playback.item.value?.uri == uri) {
                if (player.duration != C.TIME_UNSET && player.duration > 0) duration = player.duration
                // Solo cuando el cabezal se movió un píxel (o una décima para el texto): redibujar
                // toda la pantalla en cada cuadro sin que nada cambie hacía que se trabara.
                val now = player.currentPosition
                if (kotlin.math.abs(now - position) >= minOf(100L, maxOf(8L, duration / 1080))) position = now
            }
        }
    }

    // Onda: guardada para las grabaciones; para un archivo de afuera se calcula de a poco.
    var peaks by remember(uri) { mutableStateOf(ByteArray(0)) }
    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            if (recording != null) {
                peaks = Actions.peaks(recording)
            } else {
                runCatching {
                    AudioDecoder.open(context, uri).use { d ->
                        if (duration == 0L) duration = d.durationUs / 1000
                        peaks = Transcoder.peaks(d) { partial -> peaks = partial; true }
                    }
                }.onFailure { android.util.Log.w("LiquidRecorder", "Onda", it) }
            }
        }
    }

    fun seek(ms: Long) {
        player.seekTo(ms.coerceIn(0, duration.coerceAtLeast(0)))
        position = ms
    }
    fun toggle() {
        if (player.isPlaying) player.pause() else {
            Playback.startService(context)
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }
    val markers = recording?.markers ?: emptyList()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp)) {
        BackTitle(
            title,
            {
                if (recording == null) Playback.stop()
                onBack()
            },
            subtitle = recording?.let {
                DateUtils.formatDateTime(context, it.createdAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
            } ?: str(R.string.external_file),
            actions = {
                if (recording != null) GlassIconButton(Icons.Rounded.MoreVert, str(R.string.options), { menu.sheet(recording) })
            },
        )

        GlassCard(Modifier.padding(top = 8.dp)) {
            if (error) {
                Text(str(R.string.play_error), Modifier.padding(vertical = 40.dp), fontSize = 15.sp, color = p.contentSecondary)
            } else {
                FileWaveform(peaks, duration, { position }, markers, ::seek, Modifier.fillMaxWidth().height(150.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                PositionText { position }
                Spacer(Modifier.weight(1f))
                Text(TimeText.format(duration), fontSize = 14.sp, color = p.contentSecondary)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (markers.isNotEmpty()) {
                    GlassIconButton(Icons.Rounded.SkipPrevious, str(R.string.prev_marker), {
                        seek(markers.lastOrNull { it.atMs < position - 1500 }?.atMs ?: 0)
                    })
                }
                SkipButton(replayIcon(settings.skipSeconds), str(R.string.back_seconds, settings.skipSeconds), back = true, seconds = settings.skipSeconds) { seek(position - settings.skipSeconds * 1000L) }
                GlassIconButton(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, str(if (playing) R.string.pause else R.string.play), ::toggle, size = 72.dp, prominent = true)
                SkipButton(forwardIcon(settings.skipSeconds), str(R.string.forward_seconds, settings.skipSeconds), back = false, seconds = settings.skipSeconds) { seek(position + settings.skipSeconds * 1000L) }
                if (markers.isNotEmpty()) {
                    GlassIconButton(Icons.Rounded.SkipNext, str(R.string.next_marker), {
                        markers.firstOrNull { it.atMs > position + 100 }?.let { seek(it.atMs) }
                    })
                }
            }
        }

        // Orden: marcadores, velocidad y, al final, recortar y dividir.
        if (recording != null) MarkersEditor(recording, { position }, ::seek)
        GlassCard(Modifier.padding(top = 12.dp)) {
            dev.liquidrecorder.ui.components.Dropdown(str(R.string.speed), SPEEDS, settings.speed, { speedLabel(it) }) { v -> Stores.settings.update { it.copy(speed = v) } }
            SwitchRow(str(R.string.skip_silence), settings.skipSilence, { v -> Stores.settings.update { it.copy(skipSilence = v) } })
        }
        if (recording != null) {
            GlassCard(Modifier.padding(top = 12.dp)) {
                GlassButton(str(R.string.edit_audio), { onEdit(recording) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.ContentCut)
                Spacer(Modifier.height(10.dp))
                Text(
                    listOf(
                        formatName(recording.audioFormat),
                        "${recording.sampleRate} Hz",
                        if (recording.audioFormat.lossless) "${recording.bitsPerSample} bits" else "${recording.bitrate / 1000} kbps",
                        str(if (recording.channels == 2) R.string.stereo else R.string.mono),
                        TimeText.size(recording.sizeBytes),
                    ).joinToString(" · "),
                    fontSize = 13.sp,
                    color = p.contentSecondary,
                )
                if (recording.importedFrom.isNotEmpty()) {
                    Text(str(R.string.imported_from, recording.importedFrom), fontSize = 13.sp, color = p.contentSecondary)
                }
            }
        } else {
            GlassCard(Modifier.padding(top = 12.dp)) {
                Text(str(R.string.external_hint), fontSize = 14.sp, color = p.contentSecondary)
                Spacer(Modifier.height(12.dp))
                GlassButton(str(R.string.save_to_library), {
                    Actions.import(context, uri) { id -> onImported(id) }
                }, Modifier.fillMaxWidth(), icon = Icons.Rounded.LibraryAdd, prominent = true)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

fun speedLabel(s: Int): String = (if (s % 100 == 0) "${s / 100}" else if (s % 10 == 0) "${s / 100},${(s % 100) / 10}" else "${s / 100},${s % 100}") + "×"

/** Marcadores de una grabación guardada: ir, renombrar, borrar y agregar en la posición actual. */
@Composable
private fun MarkersEditor(r: Recording, position: () -> Long, seek: (Long) -> Unit) {
    val p = LocalGlass.current
    val dialog = LocalDialog.current
    GlassCard(Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                str(R.string.markers) + if (r.markers.isEmpty()) "" else " (${r.markers.size})",
                Modifier.weight(1f).semantics { heading() },
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            GlassIconButton(Icons.Rounded.BookmarkAdd, str(R.string.add_marker_here), {
                val id = (r.markers.maxOfOrNull { it.id } ?: 0) + 1
                val m = Marker(id, position(), "")
                Library.setMarkers(r.id, r.markers + m)
                dialog(DialogSpec(str(R.string.name_marker)) { d -> RenameWindow("", str(R.string.marker_n, r.markers.size + 1), d) { name -> renameMarker(r.id, id, name) } })
            })
        }
        if (r.markers.isEmpty()) Text(str(R.string.markers_player_hint), Modifier.padding(top = 4.dp), fontSize = 13.sp, color = p.contentSecondary)
        r.markers.forEachIndexed { i, m ->
            val label = m.name.ifBlank { str(R.string.marker_n, i + 1) }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = str(R.string.go_to_marker)) { seek(m.atMs) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(TimeText.format(m.atMs, tenths = true), Modifier.width(64.dp), fontSize = 14.sp, color = p.accentFg, fontWeight = FontWeight.SemiBold)
                Text(label, Modifier.weight(1f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (m.name.isBlank()) p.contentSecondary else p.content)
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .clickable(role = Role.Button) {
                            dialog(DialogSpec(str(R.string.rename_marker)) { d -> RenameWindow(m.name, label, d) { name -> renameMarker(r.id, m.id, name) } })
                        }
                        .semantics { contentDescription = str(R.string.rename_marker_named, label) },
                    contentAlignment = Alignment.Center,
                ) { Icon(styled(Icons.Rounded.Edit), null, Modifier.size(20.dp), tint = p.contentSecondary) }
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .clickable(role = Role.Button) { Library.setMarkers(r.id, r.markers.filter { it.id != m.id }) }
                        .semantics { contentDescription = str(R.string.delete_marker, label) },
                    contentAlignment = Alignment.Center,
                ) { Icon(styled(Icons.Rounded.Close), null, Modifier.size(20.dp), tint = p.contentSecondary) }
            }
        }
    }
}

private fun renameMarker(recordingId: Long, markerId: Long, name: String) {
    val r = Library.get(recordingId) ?: return
    Library.setMarkers(recordingId, r.markers.map { if (it.id == markerId) it.copy(name = name.trim()) else it })
}

private fun replayIcon(s: Int) = when (s) {
    5 -> Icons.Rounded.Replay5
    10 -> Icons.Rounded.Replay10
    30 -> Icons.Rounded.Replay30
    else -> Icons.Rounded.Replay
}

private fun forwardIcon(s: Int) = when (s) {
    5 -> Icons.Rounded.Forward5
    10 -> Icons.Rounded.Forward10
    30 -> Icons.Rounded.Forward30
    else -> Icons.Rounded.FastForward
}

/**
 * Botón de retroceder o avanzar con respuesta visible: la flecha gira hacia su lado y vuelve con
 * rebote, y un "−10 s" / "+10 s" sube y se desvanece. Tocar varias veces seguidas suma la cuenta.
 * Con "quitar animaciones" solo aparece el texto, sin moverse.
 */
@Composable
private fun SkipButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, back: Boolean, seconds: Int, onClick: () -> Unit) {
    val p = LocalGlass.current
    val reduced = dev.liquidrecorder.ui.glass.LocalReducedMotion.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val rotation = remember { androidx.compose.animation.core.Animatable(0f) }
    val label = remember { androidx.compose.animation.core.Animatable(0f) }
    var streak by remember { mutableStateOf(0) }
    var lastTap by remember { mutableLongStateOf(0L) }
    Box(contentAlignment = Alignment.Center) {
        dev.liquidrecorder.ui.glass.GlassSurface(
            Modifier.size(54.dp).semantics {
                role = Role.Button
                contentDescription = description
            },
            shape = CircleShape,
            strong = true,
            onClick = {
                onClick()
                val now = android.os.SystemClock.uptimeMillis()
                streak = if (now - lastTap < 900) streak + 1 else 1
                lastTap = now
                scope.launch {
                    label.snapTo(0f)
                    label.animateTo(1f, androidx.compose.animation.core.tween(if (reduced) 900 else 700))
                }
                if (!reduced) scope.launch {
                    rotation.animateTo(if (back) -45f else 45f, androidx.compose.animation.core.tween(90))
                    rotation.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.35f, stiffness = 500f))
                }
            },
        ) {
            Icon(
                styled(icon),
                null,
                Modifier.align(Alignment.Center).size(26.dp).graphicsLayer { rotationZ = rotation.value },
                tint = p.content,
            )
        }
        // El texto flota por encima del botón mientras dura la animación.
        val t = label.value
        if (t > 0f && t < 1f) {
            Text(
                (if (back) "−" else "+") + (seconds * streak) + " s",
                Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        translationY = if (reduced) -30.dp.toPx() else -(18.dp.toPx() + 22.dp.toPx() * t)
                        alpha = if (t < 0.15f) t / 0.15f else 1f - ((t - 0.15f) / 0.85f)
                    }
                    .clearAndSetSemantics { },
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = p.accentFg,
            )
        }
    }
}

/** La posición en texto: se recompone sola, sin arrastrar al resto de la pantalla en cada cuadro. */
@Composable
fun PositionText(position: () -> Long) {
    // Solo cambia cuando cambian las décimas, no en cada cuadro.
    val text by remember { androidx.compose.runtime.derivedStateOf { TimeText.format(position(), tenths = true) } }
    // Para TalkBack, solo los segundos: si la semántica cambiara 10 veces por segundo, Android
    // recalcularía el árbol de accesibilidad cada vez (unos 16 ms) y la pantalla se trabaría.
    val spoken by remember { androidx.compose.runtime.derivedStateOf { TimeText.format(position()) } }
    Text(
        text,
        Modifier.clearAndSetSemantics { contentDescription = spoken },
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        style = androidx.compose.material3.LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
    )
}
