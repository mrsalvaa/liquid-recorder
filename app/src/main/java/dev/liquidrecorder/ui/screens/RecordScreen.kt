// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidrecorder.R
import dev.liquidrecorder.audio.RecStatus
import dev.liquidrecorder.audio.Recorder
import dev.liquidrecorder.core.audio.AudioFormat
import dev.liquidrecorder.core.audio.EncoderConfig
import dev.liquidrecorder.core.text.TimeText
import dev.liquidrecorder.data.Library
import dev.liquidrecorder.data.Marker
import dev.liquidrecorder.data.Stores
import dev.liquidrecorder.service.RecorderErrors
import dev.liquidrecorder.service.RecordingService
import dev.liquidrecorder.ui.components.RecordRed
import dev.liquidrecorder.ui.components.RenameField
import dev.liquidrecorder.ui.components.RenameWindow
import dev.liquidrecorder.ui.components.dangerText
import dev.liquidrecorder.ui.glass.DialogSpec
import dev.liquidrecorder.ui.glass.GlassButton
import dev.liquidrecorder.ui.glass.GlassCard
import dev.liquidrecorder.ui.glass.GlassChip
import dev.liquidrecorder.ui.glass.GlassIconButton
import dev.liquidrecorder.ui.glass.GlassSurface
import dev.liquidrecorder.ui.glass.LocalDialog
import dev.liquidrecorder.ui.glass.LocalGlass
import dev.liquidrecorder.ui.glass.LocalReducedMotion
import dev.liquidrecorder.ui.glass.ScreenTitle
import dev.liquidrecorder.ui.glass.pillVeil
import dev.liquidrecorder.ui.glass.styled
import dev.liquidrecorder.util.plural
import dev.liquidrecorder.util.str
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Pedidos de empezar a grabar desde afuera (ajuste rápido, widget, acceso directo). */
object RecordRequests {
    val pending = MutableStateFlow(false)
}

/** Resumen del formato: "FLAC · 48 kHz · 24 bits · Mono". */
fun formatSummary(cfg: EncoderConfig): String = listOfNotNull(
    cfg.format.name,
    if (cfg.sampleRate % 1000 == 0) "${cfg.sampleRate / 1000} kHz" else String.format(java.util.Locale.getDefault(), "%.1f kHz", cfg.sampleRate / 1000f),
    if (cfg.format.lossless) "${cfg.bitsPerSample} bits" else "${cfg.bitrate / 1000} kbps",
    str(if (cfg.channels == 2) R.string.stereo else R.string.mono),
).joinToString(" · ")

@Composable
fun RecordScreen(padding: PaddingValues, onSettings: () -> Unit, onOpen: (Long) -> Unit) {
    val p = LocalGlass.current
    val context = LocalContext.current
    val dialog = LocalDialog.current
    val toast = LocalToast.current
    // Todo menos el tiempo, que cambia 10 veces por segundo y se lee aparte (ElapsedText).
    val stateFlow = remember { Recorder.state.map { it.copy(elapsedMs = 0) }.distinctUntilChanged().stateIn(Recorder.scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, Recorder.state.value.copy(elapsedMs = 0)) }
    val state by stateFlow.collectAsStateWithLifecycle()
    val settings by Stores.settings.state.collectAsStateWithLifecycle()
    val active = state.active
    val cfg = state.config ?: settings.encoder

    // Pantalla encendida mientras se graba, si se pidió.
    val view = LocalView.current
    DisposableEffect(active, settings.keepScreenOn) {
        view.keepScreenOn = active && settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    fun openAppSettings() {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            RecordingService.start(context)
        } else {
            dialog(DialogSpec(str(R.string.mic_permission_title)) { dismiss ->
                Text(str(R.string.mic_permission_text), fontSize = 15.sp, color = p.contentSecondary)
                Spacer(Modifier.height(16.dp))
                GlassButton(str(R.string.open_settings), { dismiss(); openAppSettings() }, Modifier.fillMaxWidth(), prominent = true)
                Spacer(Modifier.height(8.dp))
                GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
            })
        }
    }

    fun startRecording() {
        if (Recorder.freeBytes() < Recorder.MIN_FREE_BYTES) {
            toast(str(R.string.error_no_space))
            return
        }
        val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notif = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (mic && notif) {
            RecordingService.start(context)
        } else {
            // La notificación no es obligatoria: si se niega, se graba igual.
            permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    val pending by RecordRequests.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        if (pending) {
            RecordRequests.pending.value = false
            if (!Recorder.state.value.active) startRecording()
        }
    }

    val startError by RecorderErrors.last.collectAsStateWithLifecycle()
    LaunchedEffect(startError, state.error) {
        val e = startError ?: state.error ?: return@LaunchedEffect
        toast(e)
        RecorderErrors.last.value = null
        Recorder.clearError()
    }

    // Al terminar: la grabación ya está guardada; se ofrece ponerle nombre o abrirla.
    LaunchedEffect(state.lastSaved) {
        val id = state.lastSaved
        if (id == 0L) return@LaunchedEffect
        Recorder.clearLastSaved()
        val r = Library.get(id) ?: return@LaunchedEffect
        dialog(DialogSpec(str(R.string.saved_title)) { dismiss ->
            Text(
                TimeText.format(r.durationMs) + " · " + plural(R.plurals.markers_count, r.markers.size),
                Modifier.padding(bottom = 12.dp),
                fontSize = 14.sp,
                color = p.contentSecondary,
            )
            var name by remember { mutableStateOf(r.title) }
            RenameField(r.title, str(R.string.name), { name = it }, autoFocus = false, onDone = { Library.rename(id, name); dismiss() })
            Spacer(Modifier.height(16.dp))
            GlassButton(str(R.string.done), { Library.rename(id, name); dismiss() }, Modifier.fillMaxWidth(), prominent = true)
            Spacer(Modifier.height(8.dp))
            GlassButton(str(R.string.open), { Library.rename(id, name); dismiss(); onOpen(id) }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            // Descartar: va a la papelera (se puede recuperar durante 30 días).
            dev.liquidrecorder.ui.components.DangerButton(str(R.string.discard), {
                dismiss()
                Library.trash(listOf(id))
                toast(str(R.string.discarded))
            }, Modifier.fillMaxWidth())
        })
    }

    // Con el teclado abierto (nombre de un marcador) se deja lugar para él; la barra de controles
    // flota abajo (MainActivity) y el contenido pasa por debajo de ella al desplazarse.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val keyboard = imeBottom > 0
    val bottom = if (keyboard) with(density) { imeBottom.toDp() } else padding.calculateBottomPadding() + RecordDockHeight
    val running = state.status == RecStatus.RECORDING
    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).verticalScroll(rememberScrollState()).padding(bottom = bottom)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            ScreenTitle(
                str(R.string.tab_record),
                subtitle = formatSummary(cfg),
                actions = { GlassIconButton(Icons.Rounded.Settings, str(R.string.settings), onSettings) },
            )

            // Tiempo.
            Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ElapsedText(paused = state.status == RecStatus.PAUSED)
                StatusLine(state.status, state.title, state.deviceName, onRename = {
                    dialog(DialogSpec(str(R.string.rename)) { dismiss ->
                        RenameWindow(state.title, str(R.string.name), dismiss) { Recorder.renameCurrent(it) }
                    })
                })
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                GlassChip(str(R.string.visual_wave), settings.visualizer == 0, { Stores.settings.update { it.copy(visualizer = 0) } })
                GlassChip(str(R.string.visual_spectrum), settings.visualizer == 1, { Stores.settings.update { it.copy(visualizer = 1) } })
            }
        }

        val showing = active || state.status == RecStatus.SAVING
        val hint: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {
            Text(
                str(R.string.record_hint),
                Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                textAlign = TextAlign.Center,
                fontSize = 15.sp,
                color = p.contentSecondary,
            )
        }
        if (settings.visualizer == 1) {
            // Espectrograma suelto: de borde a borde, sin tarjeta, fundido con el fondo.
            Box(Modifier.fillMaxWidth().height(230.dp)) {
                if (showing) LiveSpectrogram(Recorder.live, running, Modifier.fillMaxSize(), fade = true) else hint()
            }
            LevelMeter(Recorder.live, running, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp))
        } else {
            // La onda también suelta, de borde a borde y fundida con el fondo.
            Box(Modifier.fillMaxWidth().height(230.dp)) {
                if (showing) LiveWaveform(Recorder.live, state.markers, running, Modifier.fillMaxSize(), fade = true) else hint()
            }
            LevelMeter(Recorder.live, running, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp))
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            AnimatedVisibility(state.silenced) {
                GlassCard(Modifier.padding(top = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(styled(Icons.Rounded.Warning), null, Modifier.size(22.dp), tint = p.dangerText)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            str(R.string.silenced),
                            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            fontSize = 14.sp,
                        )
                    }
                }
            }

            if (active || state.markers.isNotEmpty()) MarkersCard(state.markers, active)

            if (!active) {
                val free = Recorder.freeBytes()
                Text(
                    str(R.string.free_space, hoursText(free / cfg.bytesPerSecond.coerceAtLeast(1))),
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp,
                    color = p.contentSecondary,
                )
            }
        }

    }
}

/** "38 h" o "12 min": cuánto se puede grabar con el espacio libre. */
private fun hoursText(seconds: Long): String =
    if (seconds >= 3600) str(R.string.hours_short, seconds / 3600) else str(R.string.minutes_short, seconds / 60)

/** El marcador recién agregado, para abrirle el campo del nombre. */
object MarkerBus {
    val added = MutableStateFlow<Long?>(null)
}

@Composable
private fun StatusLine(status: RecStatus, title: String, device: String, onRename: () -> Unit) {
    val p = LocalGlass.current
    val reduced = LocalReducedMotion.current
    if (status == RecStatus.IDLE) {
        Text(str(R.string.ready), fontSize = 15.sp, color = p.contentSecondary)
        return
    }
    val blink = rememberInfiniteTransition(label = "rec")
    val alpha by blink.animateFloat(1f, if (reduced) 1f else 0.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot")
    Row(
        Modifier
            .clip(CircleShape)
            .clickable(remember { MutableInteractionSource() }, null, role = Role.Button, onClick = onRename)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(10.dp).graphicsLayer { this.alpha = if (status == RecStatus.RECORDING) alpha else 1f }
                .clip(CircleShape).background(if (status == RecStatus.RECORDING) RecordRed else p.contentSecondary),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            title + " · " + str(
                when (status) {
                    RecStatus.PAUSED -> R.string.notif_paused
                    RecStatus.SAVING -> R.string.saving
                    else -> R.string.notif_recording
                },
            ),
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(6.dp))
        Icon(styled(Icons.Rounded.Edit), str(R.string.rename), Modifier.size(16.dp), tint = p.contentSecondary)
    }
    if (device.isNotEmpty()) Text(device, fontSize = 13.sp, color = p.contentSecondary)
}

/**
 * Marcadores de la grabación en curso. Al crear uno se abre la ventana emergente de la suite para
 * ponerle nombre; la grabación sigue mientras tanto. Tocar uno lo renombra; la cruz lo borra.
 */
@Composable
private fun MarkersCard(markers: List<Marker>, active: Boolean) {
    val p = LocalGlass.current
    val dialog = LocalDialog.current
    val added by MarkerBus.added.collectAsStateWithLifecycle()
    fun nameWindow(m: Marker, fallback: String) {
        dialog(DialogSpec(str(R.string.marker_at, TimeText.format(m.atMs, tenths = true))) { dismiss ->
            RenameWindow(m.name, fallback, dismiss) { Recorder.renameMarker(m.id, it) }
        })
    }
    LaunchedEffect(added) {
        val id = added ?: return@LaunchedEffect
        MarkerBus.added.value = null
        val all = Recorder.state.value.markers
        val m = all.firstOrNull { it.id == id } ?: return@LaunchedEffect
        nameWindow(m, str(R.string.marker_n, all.indexOf(m) + 1))
    }
    GlassCard(Modifier.padding(top = 12.dp)) {
        Text(
            str(R.string.markers) + if (markers.isEmpty()) "" else " (${markers.size})",
            Modifier.semantics { heading() },
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (markers.isEmpty()) {
            Text(str(R.string.markers_hint), Modifier.padding(top = 4.dp), fontSize = 13.sp, color = p.contentSecondary)
        }
        markers.forEachIndexed { i, m ->
            val fallback = str(R.string.marker_n, i + 1)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(enabled = active, role = Role.Button, onClickLabel = str(R.string.rename)) { nameWindow(m, fallback) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(TimeText.format(m.atMs, tenths = true), Modifier.width(64.dp), fontSize = 14.sp, color = p.accentFg, fontWeight = FontWeight.SemiBold)
                Text(
                    m.name.ifBlank { fallback },
                    Modifier.weight(1f),
                    fontSize = 15.sp,
                    color = if (m.name.isBlank()) p.contentSecondary else p.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (active) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape)
                            .clickable(role = Role.Button) { Recorder.deleteMarker(m.id) }
                            .semantics { contentDescription = str(R.string.delete_marker, m.name.ifBlank { fallback }) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(styled(Icons.Rounded.Close), null, Modifier.size(20.dp), tint = p.contentSecondary)
                    }
                }
            }
        }
    }
}

/** Alto que ocupa la barra de controles flotante (con su margen). */
val RecordDockHeight = 112.dp

/**
 * Controles de grabación en una barra de cristal flotante, sobre la barra de pestañas. Va en la
 * capa flotante de GlassRoot: lo que se desplaza por debajo se ve refractado, también con fondo
 * liso. Sin grabar es solo el botón rojo; grabando, marcador, pausa y detener.
 */
@Composable
fun RecordDock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val p = LocalGlass.current
    val state by Recorder.state.collectAsStateWithLifecycle()
    val status = state.status
    val active = state.active
    val main: @Composable () -> Unit = {
        val recordLike = !active || status == RecStatus.PAUSED
        GlassSurface(
            Modifier
                .size(76.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = str(
                        when (status) {
                            RecStatus.RECORDING -> R.string.pause
                            RecStatus.PAUSED -> R.string.resume
                            else -> R.string.tile_record
                        },
                    )
                },
            shape = CircleShape,
            strong = true,
            tint = if (recordLike) RecordRed else null,
            onClick = when (status) {
                RecStatus.SAVING -> null
                RecStatus.RECORDING -> ({ RecordingService.command(context, RecordingService.ACTION_PAUSE) })
                RecStatus.PAUSED -> ({ RecordingService.command(context, RecordingService.ACTION_RESUME) })
                else -> ({ RecordRequests.pending.value = true })
            },
        ) {
            Icon(
                styled(if (status == RecStatus.RECORDING) Icons.Rounded.Pause else Icons.Rounded.Mic),
                null,
                Modifier.align(Alignment.Center).size(34.dp),
                tint = if (recordLike) Color.White else p.content,
            )
        }
    }
    // Una sola pieza que se transforma: el cristal se estira desde el botón hacia los costados y
    // los botones de marcador y detener salen del centro. Nada se recorta durante la animación.
    val reduced = dev.liquidrecorder.ui.glass.LocalReducedMotion.current
    val progress by androidx.compose.animation.core.animateFloatAsState(
        if (active) 1f else 0f,
        if (reduced) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 380f),
        label = "dock",
    )
    val side = 86.dp
    Box(modifier.width(272.dp).height(96.dp), contentAlignment = Alignment.Center) {
        if (progress > 0.001f) {
            GlassSurface(
                Modifier
                    .width(androidx.compose.ui.unit.lerp(92.dp, 268.dp, progress.coerceAtLeast(0f)))
                    .height(92.dp)
                    .graphicsLayer { alpha = progress.coerceIn(0f, 1f) },
                shape = CircleShape,
                strong = true,
                tint = p.pillVeil(),
                frost = 4.dp,
            ) {}
        }
        if (progress > 0.02f) {
            val a = ((progress - 0.25f) / 0.75f).coerceIn(0f, 1f)
            val scale = 0.6f + 0.4f * progress.coerceAtMost(1.05f)
            Box(Modifier.offset(x = -side * progress).graphicsLayer { alpha = a; scaleX = scale; scaleY = scale }) {
                GlassIconButton(Icons.Rounded.Bookmark, str(R.string.add_marker), { MarkerBus.added.value = Recorder.addMarker()?.id }, size = 56.dp, enabled = active)
            }
            Box(Modifier.offset(x = side * progress).graphicsLayer { alpha = a; scaleX = scale; scaleY = scale }) {
                GlassIconButton(Icons.Rounded.Stop, str(R.string.stop_save), { RecordingService.command(context, RecordingService.ACTION_STOP) }, size = 56.dp, enabled = active)
            }
        }
        main()
    }
}

/** El tiempo grande: es lo único que se recompone 10 veces por segundo. */
@Composable
private fun ElapsedText(paused: Boolean) {
    val p = LocalGlass.current
    val elapsed by Recorder.elapsed.collectAsStateWithLifecycle()
    val spoken by remember { androidx.compose.runtime.derivedStateOf { TimeText.format(elapsed) } }
    Text(
        TimeText.format(elapsed, tenths = true),
        // Para TalkBack, solo los segundos (ver PositionText).
        Modifier.clearAndSetSemantics { contentDescription = spoken },
        fontSize = 64.sp,
        fontWeight = FontWeight.Light,
        style = androidx.compose.material3.LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
        color = if (paused) p.contentSecondary else p.content,
    )
}
