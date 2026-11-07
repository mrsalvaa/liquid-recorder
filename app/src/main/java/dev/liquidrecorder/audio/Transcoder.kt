// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.audio

import dev.liquidrecorder.core.audio.EncoderConfig
import dev.liquidrecorder.core.audio.PcmConverter
import dev.liquidrecorder.core.audio.Resampler
import dev.liquidrecorder.core.audio.downmix
import dev.liquidrecorder.core.audio.remapChannels
import dev.liquidrecorder.core.dsp.PeakCollector
import dev.liquidrecorder.core.edit.Segment
import java.io.File

/**
 * Copia tramos de un archivo a otro, en el formato que se pida: sirve para recortar, dividir,
 * exportar a otro formato e importar el audio de otro archivo (también de un video).
 */
object Transcoder {
    class Result(val durationMs: Long, val peaks: ByteArray)

    /** Algo que transforma el audio en el camino (puede retrasarlo y vaciarse al final). */
    interface Processor {
        fun process(data: FloatArray, frames: Int): Pair<FloatArray, Int>
        fun flush(): Pair<FloatArray, Int>
    }

    /**
     * [segments] null = todo el archivo. [progress] recibe de 0 a 1 y devuelve false para cancelar
     * (en ese caso se borra lo escrito y se devuelve null).
     */
    fun render(
        decoder: AudioDecoder,
        segments: List<Segment>?,
        cfg: EncoderConfig,
        out: File,
        progress: (Float) -> Boolean = { true },
        /** Procesamiento opcional (reducción de ruido) sobre el audio ya en el formato de salida. */
        processor: Processor? = null,
    ): Result? {
        val sink = openSink(out, cfg)
        val converter = PcmConverter(cfg.pcmBits)
        val peaks = PeakCollector(cfg.sampleRate)
        var resampler: Resampler? = null
        var ints = IntArray(0)
        var mono = FloatArray(0)
        var written = 0L
        val totalUs = segments?.sumOf { it.lengthMs * 1000 } ?: decoder.durationUs
        var doneUs = 0L
        var cancelled = false
        try {
            for (s in segments ?: listOf(null)) {
                val startUs = s?.startMs?.times(1000) ?: 0L
                val endUs = s?.endMs?.times(1000) ?: Long.MAX_VALUE
                decoder.decode(startUs, endUs) { chunk, frames ->
                    val r = resampler ?: Resampler(decoder.sampleRate, cfg.sampleRate, cfg.channels).also { resampler = it }
                    val mapped = remapChannels(chunk, frames, decoder.channels, cfg.channels)
                    val (resampled, rn) = r.process(mapped, frames)
                    val (data, n) = processor?.process(resampled, rn) ?: (resampled to rn)
                    if (n > 0) {
                        val count = n * cfg.channels
                        if (ints.size < count) ints = IntArray(count)
                        if (mono.size < n) mono = FloatArray(n)
                        converter.convert(data, count, 1f, ints)
                        sink.write(ints, n)
                        downmix(data, n, cfg.channels, mono)
                        peaks.push(mono, n)
                        written += n
                    }
                    doneUs += frames * 1_000_000L / decoder.sampleRate
                    val ok = progress(if (totalUs > 0) (doneUs.toFloat() / totalUs).coerceIn(0f, 1f) else 0f)
                    if (!ok) cancelled = true
                    ok
                }
                if (cancelled) break
            }
            // Lo que quedó dentro del procesador.
            if (!cancelled && processor != null) {
                val (tail, n) = processor.flush()
                if (n > 0) {
                    val t = IntArray(n * cfg.channels)
                    converter.convert(tail, n * cfg.channels, 1f, t)
                    sink.write(t, n)
                    downmix(tail, n, cfg.channels, FloatArray(n).also { mono = it })
                    peaks.push(mono, n)
                    written += n
                }
            }
        } finally {
            runCatching { sink.finish() }
        }
        if (cancelled) {
            out.delete()
            return null
        }
        return Result(written * 1000 / cfg.sampleRate, peaks.toByteArray())
    }

    /** Solo la forma de onda (para reproducir un archivo que no está en la biblioteca). */
    fun peaks(decoder: AudioDecoder, onUpdate: (ByteArray) -> Boolean): ByteArray {
        var collector: PeakCollector? = null
        var mono = FloatArray(0)
        var count = 0
        decoder.decode { chunk, frames ->
            val c = collector ?: PeakCollector(decoder.sampleRate).also { collector = it }
            if (mono.size < frames) mono = FloatArray(frames)
            downmix(chunk, frames, decoder.channels, mono)
            c.push(mono, frames)
            if (++count % 200 == 0) onUpdate(c.toByteArray()) else true
        }
        return collector?.toByteArray() ?: ByteArray(0)
    }
}
