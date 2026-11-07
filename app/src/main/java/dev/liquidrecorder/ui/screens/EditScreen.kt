// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.ui.screens

import android.net.Uri
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import dev.liquidrecorder.R
import dev.liquidrecorder.audio.PlayItem
import dev.liquidrecorder.audio.Playback
import dev.liquidrecorder.core.edit.Edits
import dev.liquidrecorder.core.edit.Segment
import dev.liquidrecorder.core.text.TimeText
import dev.liquidrecorder.data.Actions
import dev.liquidrecorder.data.Library
import dev.liquidrecorder.data.Stores
import dev.liquidrecorder.ui.components.EditIcons
import dev.liquidrecorder.ui.glass.DialogSpec
import dev.liquidrecorder.ui.glass.GlassButton
import dev.liquidrecorder.ui.glass.GlassCard
import dev.liquidrecorder.ui.glass.GlassChip
import dev.liquidrecorder.ui.glass.GlassIconButton
import dev.liquidrecorder.ui.glass.LocalDialog
import dev.liquidrecorder.ui.glass.LocalGlass
import dev.liquidrecorder.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
fun EditScreen(id: Long, padding: PaddingValues, onBack: () -> Unit, onDone: (Long?) -> Unit) {
    val p = LocalGlass.current
    val context = LocalContext.current
    val dialog = LocalDialog.current
    val lib by Library.state.collectAsStateWithLifecycle()
    val r = lib.recordings.firstOrNull { it.id == id }
    if (r == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val duration = r.durationMs
    val player = remember { Playback.get(context) }
    val uri = remember(r.file) { Uri.fromFile(Library.fileOf(r)) }
    // Al editar se escucha a velocidad normal y sin saltear silencios; al salir vuelve lo elegido.
    LaunchedEffect(uri) {
        Playback.open(context, PlayItem(r.id, uri, r.title), play = false, speed = 1f, skipSilence = false)
        player.pause()
    }
    DisposableEffect(Unit) {
        onDispose {
            val s = Stores.settings.value
            player.setPlaybackSpeed(s.speed / 100f)
            player.skipSilenceEnabled = s.skipSilence
        }
    }

    var peaks by remember { mutableStateOf(ByteArray(0)) }
    LaunchedEffect(r.id) { withContext(Dispatchers.IO) { peaks = Actions.peaks(r) } }

    var start by remember { mutableLongStateOf(duration / 4) }
    var end by remember { mutableLongStateOf(duration * 3 / 4) }
    var position by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var previewEnd by remember { mutableLongStateOf(-1L) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.pause() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            val now = player.currentPosition
            if (kotlin.math.abs(now - position) >= minOf(100L, maxOf(8L, duration / 1080))) position = now
            if (previewEnd >= 0 && position >= previewEnd) {
                player.pause()
                previewEnd = -1
            }
        }
    }
    fun seek(ms: Long) {
        player.seekTo(ms.coerceIn(0, duration))
        position = ms
    }

    /** Pregunta si guardar como copia o reemplazar, y lo hace. */
    fun confirm(title: String, text: String, run: (replace: Boolean) -> Unit) {
        dialog(DialogSpec(title) { dismiss ->
            Text(text, fontSize = 15.sp, color = p.contentSecondary)
            Spacer(Modifier.height(16.dp))
            GlassButton(str(R.string.save_copy), { dismiss(); run(false) }, Modifier.fillMaxWidth(), prominent = true)
            Spacer(Modifier.height(8.dp))
            GlassButton(str(R.string.replace_original), { dismiss(); run(true) }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
        })
    }

    val selection = Segment(minOf(start, end), maxOf(start, end))
    val validSelection = selection.lengthMs >= 300
    val dur by rememberUpdatedState(duration)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp)) {
        BackTitle(str(R.string.edit_audio), onBack, subtitle = r.title)

        GlassCard(Modifier.padding(top = 8.dp)) {
            Text(str(R.string.edit_hint), fontSize = 13.sp, color = p.contentSecondary)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(150.dp)) {
                FileWaveform(peaks, duration, { position }, r.markers, ::seek, Modifier.fillMaxSize(), selection = selection, seekEnabled = false)
                // Arrastrar mueve el borde más cercano de la selección; tocar mueve la posición.
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { o -> seek((o.x / size.width * dur).toLong()) }
                        }
                        .pointerInput(Unit) {
                            var movingStart = true
                            detectDragGestures(
                                onDragStart = { o ->
                                    val t = (o.x / size.width * dur).toLong()
                                    movingStart = abs(t - start) <= abs(t - end)
                                },
                                onDrag = { change, _ ->
                                    val t = (change.position.x / size.width * dur).toLong().coerceIn(0, dur)
                                    if (movingStart) start = t else end = t
                                },
                            )
                        },
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                PositionText { position }
                Spacer(Modifier.weight(1f))
                Text(TimeText.format(duration), fontSize = 14.sp, color = p.contentSecondary)
            }
            Text(
                str(R.string.selection_range, TimeText.format(selection.startMs, true), TimeText.format(selection.endMs, true), TimeText.format(selection.lengthMs, true)),
                Modifier.padding(top = 6.dp),
                fontSize = 14.sp,
                color = p.accentFg,
                fontWeight = FontWeight.Medium,
            )
            // [▶ empieza en la posición, ◀] termina en la posición.
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp, androidx.compose.ui.Alignment.CenterHorizontally),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                GlassIconButton(EditIcons.SelectionStart, str(R.string.start_here), { start = position; if (end < start) end = duration }, size = 52.dp)
                GlassIconButton(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, str(if (playing) R.string.pause else R.string.play), {
                    if (player.isPlaying) player.pause() else { previewEnd = -1; player.play() }
                }, size = 52.dp)
                GlassIconButton(EditIcons.SelectionEnd, str(R.string.end_here), { end = position; if (start > end) start = 0 }, size = 52.dp)
            }
            GlassButton(str(R.string.play_selection), {
                seek(selection.startMs)
                previewEnd = selection.endMs
                player.play()
            }, Modifier.fillMaxWidth().padding(top = 10.dp), enabled = validSelection)
            if (r.markers.isNotEmpty()) {
                Text(str(R.string.go_to_marker), Modifier.padding(top = 12.dp), fontSize = 14.sp, color = p.contentSecondary)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.markers.forEachIndexed { i, m ->
                        GlassChip(TimeText.format(m.atMs) + " " + m.name.ifBlank { str(R.string.marker_n, i + 1) }, false, { seek(m.atMs) })
                    }
                }
            }
        }

        GlassCard(Modifier.padding(top = 12.dp)) {
            // Recortar y borrar se aplican directo: el original va a la papelera (30 días).
            GlassButton(str(R.string.trim_keep), {
                Actions.edit(context, r, Actions.EditKind.KEEP, selection, replace = true) { onDone(it) }
            }, Modifier.fillMaxWidth(), icon = Icons.Rounded.ContentCut, enabled = validSelection)
            Spacer(Modifier.height(8.dp))
            GlassButton(str(R.string.delete_selection), {
                Actions.edit(context, r, Actions.EditKind.DELETE, selection, replace = true) { onDone(it) }
            }, Modifier.fillMaxWidth(), icon = Icons.Rounded.DeleteSweep, enabled = validSelection && selection.lengthMs < duration - 300)
            Spacer(Modifier.height(8.dp))
            GlassButton(str(R.string.split_here, TimeText.format(position)), {
                confirm(str(R.string.split), str(R.string.split_text)) { replace ->
                    Actions.split(context, r, position, replace) { onDone(null) }
                }
            }, Modifier.fillMaxWidth(), icon = Icons.Rounded.CallSplit, enabled = Edits.canSplit(duration, position))
            Text(str(R.string.edit_replace_hint), Modifier.padding(top = 10.dp), fontSize = 12.sp, color = p.contentSecondary)
            Text(str(R.string.edit_quality_hint), Modifier.padding(top = 4.dp), fontSize = 12.sp, color = p.contentSecondary)
        }

        // Limpiar el ruido de ambiente de una grabación ya hecha (crea una copia).
        GlassCard(Modifier.padding(top = 12.dp)) {
            Text(str(R.string.clean_title), fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(str(R.string.clean_card_hint), Modifier.padding(top = 2.dp, bottom = 10.dp), fontSize = 13.sp, color = p.contentSecondary)
            GlassButton(str(R.string.clean_open), {
                dialog(DialogSpec(str(R.string.clean_title)) { d -> CleanWindow(listOf(r), d) { id -> onDone(id) } })
            }, Modifier.fillMaxWidth(), icon = Icons.Rounded.AutoFixHigh)
        }
        Spacer(Modifier.height(24.dp))
    }
}
