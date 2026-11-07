// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.ui.screens

import android.graphics.Bitmap
import android.graphics.Paint
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liquidrecorder.R
import dev.liquidrecorder.audio.LiveMeter
import dev.liquidrecorder.audio.Recorder
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableStateOf
import dev.liquidrecorder.core.dsp.Levels
import dev.liquidrecorder.core.dsp.PeakCollector
import dev.liquidrecorder.core.edit.Segment
import dev.liquidrecorder.data.Marker
import dev.liquidrecorder.ui.components.RecordRed
import dev.liquidrecorder.ui.glass.LocalGlass
import dev.liquidrecorder.util.str
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Reloj de cuadros: cambia en cada cuadro de la pantalla (120 Hz) mientras [running]. Se lee solo al
 * dibujar, así que no recompone nada: solo se vuelve a pintar el lienzo.
 */
@Composable
private fun rememberFrameClock(running: Boolean): androidx.compose.runtime.State<Long> {
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (true) withFrameNanos { frame.longValue = it }
    }
    return frame
}

private const val PEAK_MS = PeakCollector.PEAK_MS

/** Cada barra de la onda en vivo resume 75 ms (3 picos): unos 6 s a lo ancho. */
private const val BAR_MS = 75L
private const val PEAKS_PER_BAR = (BAR_MS / PEAK_MS).toInt()

/**
 * Onda en vivo: lo más nuevo a la derecha, deslizándose de forma continua (se interpola la
 * posición entre bloques de audio), con los marcadores.
 */
@Composable
fun LiveWaveform(live: LiveMeter, markers: List<Marker>, running: Boolean, modifier: Modifier = Modifier, fade: Boolean = false) {
    val p = LocalGlass.current
    val frame = rememberFrameClock(running)
    val markerColor = RecordRed
    Canvas(
        modifier
            .graphicsLayer { if (fade) compositingStrategy = CompositingStrategy.Offscreen }
            .clearAndSetSemantics { contentDescription = str(R.string.visual_wave) },
    ) {
        frame.value
        val bar = 2.5.dp.toPx()
        val step = 4.dp.toPx()
        val n = (size.width / step).toInt() + 2
        val collector = live.peaks
        val (tail, total) = if (collector == null) ByteArray(0) to 0 else synchronized(collector) { collector.tail(n * PEAKS_PER_BAR + PEAKS_PER_BAR) to collector.size }
        val now = live.nowMs().toFloat()
        val mid = size.height / 2
        drawLine(p.contentSecondary.copy(alpha = 0.25f), Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
        val maxH = size.height / 2 * 0.92f
        // Barras completas: la k-ésima cubre [k·100, (k+1)·100) ms.
        val firstPeak = total - tail.size
        val completeBars = total / PEAKS_PER_BAR
        for (k in maxOf(0, completeBars - n) until completeBars) {
            var v = 0
            for (j in 0 until PEAKS_PER_BAR) {
                val idx = k * PEAKS_PER_BAR + j - firstPeak
                if (idx in tail.indices) v = max(v, tail[idx].toInt() and 0xFF)
            }
            val endMs = (k + 1) * BAR_MS
            val x = size.width - (now - endMs) / BAR_MS * step - bar
            if (x < -bar) continue
            val h = max(1.5.dp.toPx(), v / 255f * maxH)
            drawRoundRect(p.accentFg, Offset(x, mid - h), Size(bar, h * 2), CornerRadius(bar / 2))
        }
        for (m in markers) {
            val x = size.width - (now - m.atMs) / BAR_MS * step
            if (x < 0 || x > size.width) continue
            drawLine(markerColor, Offset(x, 6.dp.toPx()), Offset(x, size.height), 1.5.dp.toPx())
            drawCircle(markerColor, 4.dp.toPx(), Offset(x, 6.dp.toPx()))
        }
        if (fade) fadeEdges()
    }
}

/** Bordes izquierdo y derecho que se funden con el fondo (para lo que va suelto, sin tarjeta). */
private fun DrawScope.fadeEdges() {
    val edge = 36.dp.toPx()
    drawRect(
        Brush.horizontalGradient(0f to Color.Transparent, edge / size.width to Color.Black, 1f - edge / size.width to Color.Black, 1f to Color.Transparent),
        blendMode = BlendMode.DstIn,
    )
}

/** Mapa de color del espectrograma: transparente → acento → casi blanco cálido. */
private fun spectrumLut(accent: Color, dark: Boolean): IntArray {
    val hot = if (dark) Color(0xFFFFF4D6) else Color(0xFF3B0A0A)
    return IntArray(256) { i ->
        // Curva para que el ruido de fondo quede oscuro y la voz resalte.
        val v = (i / 255f).let { it * it }
        val c = when {
            v < 0.25f -> accent.copy(alpha = v / 0.25f * 0.35f)
            v < 0.7f -> lerp(accent.copy(alpha = 0.35f), accent, (v - 0.25f) / 0.45f)
            else -> lerp(accent, hot, (v - 0.7f) / 0.3f)
        }
        c.toArgb()
    }
}

/**
 * Espectrograma en vivo: tiempo de izquierda a derecha (unos 10 s), graves abajo en escala
 * logarítmica. Se desliza de forma continua entre columna y columna. Con [fade], los bordes se
 * funden con el fondo (cuando va suelto, sin tarjeta).
 */
@Composable
fun LiveSpectrogram(live: LiveMeter, running: Boolean, modifier: Modifier = Modifier, fade: Boolean = false) {
    val p = LocalGlass.current
    val frame = rememberFrameClock(running)
    val lut = remember(p.accentFg, p.dark) { spectrumLut(p.accentFg, p.dark) }
    val bitmap = remember { Bitmap.createBitmap(live.columns, live.rows, Bitmap.Config.ARGB_8888) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val pixels = remember { IntArray(live.columns * live.rows) }
    val lastHead = remember { longArrayOf(-1L) }
    val label = remember(p.contentSecondary) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.contentSecondary.toArgb() }
    }
    Canvas(
        modifier
            .clearAndSetSemantics { contentDescription = str(R.string.visual_spectrum) }
            .graphicsLayer { if (fade) compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        frame.value
        val cols = live.columns
        val rows = live.rows
        val head = live.head
        // El mapa de bits solo se rearma cuando llega una columna nueva (25 por segundo).
        if (head != lastHead[0]) {
            lastHead[0] = head
            for (c in 0 until cols) {
                val idx = head - cols + c
                if (idx < 0) {
                    for (r in 0 until rows) pixels[(rows - 1 - r) * cols + c] = 0
                    continue
                }
                val col = live.spectrum[(idx % cols).toInt()]
                for (r in 0 until rows) pixels[(rows - 1 - r) * cols + c] = lut[(col[r] * 255).toInt().coerceIn(0, 255)]
            }
            bitmap.setPixels(pixels, 0, cols, 0, 0, cols, rows)
        }
        // Corrimiento fino: cuánto de la próxima columna ya pasó.
        val colPx = size.width / (cols - 1)
        val frac = ((live.nowMs() - live.lastColumnMs).toFloat() / Recorder.COLUMN_MS).coerceIn(0f, 1f)
        val x = size.width - cols * colPx - frac * colPx + colPx
        drawImage(
            image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(cols, rows),
            dstOffset = IntOffset(x.roundToInt(), 0),
            dstSize = IntSize((cols * colPx).roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.Low,
        )
        // Referencias de frecuencia (escala de 50 Hz a 16 kHz).
        label.textSize = 10.sp.toPx()
        val lnMin = kotlin.math.ln(50f)
        val lnMax = kotlin.math.ln(16_000f)
        listOf(100f to "100", 1_000f to "1k", 5_000f to "5k", 10_000f to "10k").forEach { (hz, text) ->
            val y = size.height * (1 - (kotlin.math.ln(hz) - lnMin) / (lnMax - lnMin))
            drawLine(p.contentSecondary.copy(alpha = 0.18f), Offset(0f, y), Offset(size.width, y), 1f)
            drawContext.canvas.nativeCanvas.drawText(text, 16.dp.toPx(), y - 3.dp.toPx(), label)
        }
        if (fade) {
            // Bordes que se funden: sin marco, como si el espectrograma saliera del fondo.
            val edge = 36.dp.toPx()
            drawRect(
                Brush.horizontalGradient(0f to Color.Transparent, edge / size.width to Color.Black, 1f - edge / size.width to Color.Black, 1f to Color.Transparent),
                blendMode = BlendMode.DstIn,
            )
            val v = 18.dp.toPx()
            drawRect(
                Brush.verticalGradient(0f to Color.Transparent, v / size.height to Color.Black, 1f - v / size.height to Color.Black, 1f to Color.Transparent),
                blendMode = BlendMode.DstIn,
            )
        }
    }
}

/**
 * Medidor de nivel en dBFS: barra con el promedio (RMS) y el pico, la marca del pico máximo de
 * los últimos instantes y el aviso de saturación. Acento hasta -12, ámbar hasta -3, rojo arriba.
 * Sube al instante y baja suave (unos 24 dB por segundo), dibujado en cada cuadro.
 */
@Composable
fun LevelMeter(live: LiveMeter, running: Boolean, modifier: Modifier = Modifier) {
    val p = LocalGlass.current
    val frame = rememberFrameClock(running)
    // [rms mostrado, pico mostrado, pico retenido, momento del pico retenido, último cuadro]
    val st = remember { floatArrayOf(-90f, -90f, -90f, 0f, 0f) }
    var shownPeak by remember { mutableFloatStateOf(Levels.SILENCE_DB) }
    var clipping by remember { mutableStateOf(false) }
    LaunchedEffect(running) {
        if (!running) { st[2] = -90f; shownPeak = Levels.SILENCE_DB }
        while (running) {
            shownPeak = st[2]
            clipping = live.clipUntil > SystemClock.uptimeMillis()
            kotlinx.coroutines.delay(125)
        }
    }
    val amber = Color(0xFFF5A524)
    val red = Color(0xFFE5484D)
    Box(modifier.clearAndSetSemantics { contentDescription = str(R.string.visual_meter) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.weight(1f).height(6.dp).graphicsLayer()) {
                frame.value
                // Sin grabar (o en pausa) el medidor vuelve a cero en lugar de quedar congelado.
                if (!running) { st[0] = -90f; st[1] = -90f; st[2] = -90f; st[4] = 0f }
                val now = SystemClock.uptimeMillis().toFloat()
                val dt = if (st[4] == 0f) 0.016f else ((now - st[4]) / 1000f).coerceIn(0f, 0.1f)
                st[4] = now
                val fall = 24f * dt
                // Sin grabar no se leen los valores en vivo (quedan los del último bloque grabado).
                val rmsNow = if (running) live.rmsDb else Levels.SILENCE_DB
                val peakNow = if (running) live.peakDb else Levels.SILENCE_DB
                st[0] = if (rmsNow > st[0]) rmsNow else max(rmsNow, st[0] - fall)
                st[1] = if (peakNow > st[1]) peakNow else max(peakNow, st[1] - fall * 1.5f)
                if (peakNow >= st[2]) { st[2] = peakNow; st[3] = now } else if (now - st[3] > 1200) st[2] = max(st[1], st[2] - fall)
                val w = size.width
                val h = size.height
                fun x(db: Float) = ((db + 60f) / 60f).coerceIn(0f, 1f) * w
                drawRoundRect(p.content.copy(alpha = if (p.dark) 0.12f else 0.08f), cornerRadius = CornerRadius(h / 2))
                fun segment(fromDb: Float, toDb: Float, upTo: Float, alpha: Float) {
                    val a = x(fromDb)
                    val b = minOf(x(toDb), x(upTo))
                    if (b > a) drawRect(
                        (if (fromDb >= -3f) red else if (fromDb >= -12f) amber else p.accentFg).copy(alpha = alpha),
                        Offset(a, 0f), Size(b - a, h),
                    )
                }
                clipRounded(h) {
                    for ((from, to) in listOf(-60f to -12f, -12f to -3f, -3f to 0f)) {
                        segment(from, to, st[1], 0.45f)
                        segment(from, to, st[0], 1f)
                    }
                }
                val hold = st[2]
                val hx = x(hold)
                if (hold > -60f) drawLine(if (hold >= -3f) red else p.content, Offset(hx, 0f), Offset(hx, h), 2.dp.toPx(), StrokeCap.Round)
                for (db in listOf(-48f, -24f, -12f, -6f, -3f)) {
                    drawLine(p.base.copy(alpha = 0.6f), Offset(x(db), 0f), Offset(x(db), h), 1f)
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                if (shownPeak <= -59.5f) "−∞ dB" else String.format(java.util.Locale.ROOT, "%.0f dB", shownPeak).replace('-', '−'),
                Modifier.width(56.dp),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (shownPeak >= -3f) red else p.contentSecondary,
            )
        }
        if (clipping) {
            Text(
                str(R.string.clipping),
                Modifier.align(Alignment.CenterStart).padding(start = 6.dp).clip(CircleShape).background(red).padding(horizontal = 8.dp, vertical = 1.dp),
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private inline fun DrawScope.clipRounded(h: Float, block: DrawScope.() -> Unit) {
    val path = androidx.compose.ui.graphics.Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, CornerRadius(h / 2)))
    }
    drawContext.canvas.save()
    drawContext.canvas.clipPath(path)
    block()
    drawContext.canvas.restore()
}

/**
 * Onda de un archivo entero ajustada al ancho: lo reproducido en el color de énfasis, la posición,
 * los marcadores (tocarlos lleva ahí) y, al editar, la selección. Tocar o arrastrar busca.
 */
@Composable
fun FileWaveform(
    peaks: ByteArray,
    durationMs: Long,
    positionMs: () -> Long,
    markers: List<Marker>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    selection: Segment? = null,
    seekEnabled: Boolean = true,
) {
    val p = LocalGlass.current
    val seek by rememberUpdatedState(onSeek)
    val duration by rememberUpdatedState(durationMs)
    Canvas(
        modifier
            .graphicsLayer()
            .clearAndSetSemantics { contentDescription = str(R.string.visual_wave) }
            .then(
                if (seekEnabled) Modifier
                    .pointerInput(Unit) {
                        detectTapGestures { o -> seek((o.x / size.width * duration).toLong().coerceIn(0, duration)) }
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, _ ->
                            seek((change.position.x / size.width * duration).toLong().coerceIn(0, duration))
                        }
                    }
                else Modifier,
            ),
    ) {
        val bar = 2.dp.toPx()
        val gap = 1.5.dp.toPx()
        val n = max(1, (size.width / (bar + gap)).toInt())
        val mid = size.height / 2
        val maxH = size.height / 2 * 0.9f
        val totalPeaks = max(peaks.size, ((durationMs + PEAK_MS - 1) / PEAK_MS).toInt())
        val played = if (durationMs > 0) positionMs().toFloat() / durationMs else 0f
        if (selection != null && durationMs > 0) {
            val a = selection.startMs.toFloat() / durationMs * size.width
            val b = selection.endMs.toFloat() / durationMs * size.width
            drawRect(p.accentFg.copy(alpha = 0.16f), Offset(a, 0f), Size(b - a, size.height))
        }
        for (i in 0 until n) {
            val from = (i.toLong() * totalPeaks / n).toInt()
            val to = max(from + 1, ((i + 1).toLong() * totalPeaks / n).toInt())
            var v = 0
            for (k in from until minOf(to, peaks.size)) v = max(v, peaks[k].toInt() and 0xFF)
            val h = max(1.dp.toPx(), v / 255f * maxH)
            val x = i * (bar + gap)
            val done = (i + 0.5f) / n <= played
            drawRoundRect(
                if (done) p.accentFg else p.contentSecondary.copy(alpha = 0.45f),
                Offset(x, mid - h), Size(bar, h * 2), CornerRadius(bar / 2),
            )
        }
        if (durationMs > 0) {
            for (m in markers) {
                val x = m.atMs.toFloat() / durationMs * size.width
                drawLine(RecordRed, Offset(x, 5.dp.toPx()), Offset(x, size.height), 1.5.dp.toPx())
                drawCircle(RecordRed, 4.dp.toPx(), Offset(x, 5.dp.toPx()))
            }
            val x = played.coerceIn(0f, 1f) * size.width
            drawLine(p.content, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}
