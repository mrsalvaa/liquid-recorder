// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.core.dsp

import kotlin.math.pow
import kotlin.math.sqrt

/** Opciones de la limpieza. [level]: 1 suave, 2 media, 3 fuerte. */
data class CleanOptions(
    val level: Int = 2,
    /** Medias no locales (anlmdn): mejor resultado, mucho más lento. */
    val extra: Boolean = false,
    /** Llevar el volumen a [targetLufs] (EBU R128) con limitador a [ceilingDb]. */
    val normalize: Boolean = true,
    val targetLufs: Double = -16.0,
    val ceilingDb: Float = -1.5f,
)

/** Lo que pasó, para mostrarlo. */
data class CleanReport(val noiseBlocks: Int, val noiseDb: Float, val loudnessBefore: Double, val gainDb: Double)

/** Una pasada completa por un audio, en bloques intercalados. Devuelve false si se canceló. */
fun interface AudioPass {
    fun run(onChunk: (FloatArray, Int) -> Boolean): Boolean
}

/**
 * El pipeline completo de limpieza (ver Cleaning.kt), en cuatro pasadas sobre el archivo:
 *
 * 1. Análisis: energía de cada bloque, ya con el pasa-altos, para elegir los más silenciosos.
 * 2. Perfil: espectro promedio de esos bloques.
 * 3. Limpieza: pasa-altos → sustracción espectral ×2 → (medias no locales) → archivo temporal,
 *    midiendo el volumen de lo que sale.
 * 4. Final: ganancia hasta el volumen objetivo y limitador → [out].
 *
 * El resultado queda alineado con el original muestra a muestra (se descuenta el retraso de cada
 * etapa): los marcadores siguen en su lugar. El temporal guarda la señal a la mitad (−6 dB) para
 * que quepa en enteros de 24 bits sin recortar picos.
 */
class CleanPipeline(private val channels: Int, private val sampleRate: Int, private val options: CleanOptions) {
    /** [progress] recibe de 0 a 1 en total y devuelve false para cancelar. */
    fun run(
        original: AudioPass,
        temp: AudioPass,
        tempOut: (FloatArray, Int) -> Unit,
        tempDone: () -> Unit,
        out: (FloatArray, Int) -> Unit,
        totalFrames: Long,
        progress: (Float) -> Boolean,
    ): CleanReport? {
        val heavy = if (options.extra) 0.75f else 0.55f
        val weights = floatArrayOf(0.1f, 0.1f, heavy, 1f - 0.2f - heavy)
        val starts = floatArrayOf(0f, 0.1f, 0.2f, 0.2f + heavy)
        fun stage(i: Int, done: Long): Boolean =
            progress(starts[i] + weights[i] * (done.toFloat() / totalFrames.coerceAtLeast(1)).coerceIn(0f, 1f))

        // 1. Análisis.
        var energies = FloatArray(4096)
        var count = 0
        run {
            val hp = HighPass(sampleRate, 80f, channels)
            val frames = FrameStream(channels) { f ->
                if (count == energies.size) energies = energies.copyOf(count * 2)
                energies[count++] = if (FrameStream.isDigitalSilence(f)) Float.NaN else FrameStream.energy(f)
            }
            var done = 0L
            if (!original.run { chunk, n -> hp.process(chunk, n); frames.push(chunk, n); done += n; stage(0, done) }) return null
        }
        val threshold = NoiseProfile.threshold(energies, count)
        var noiseEnergy = 0.0
        var noiseBlocks = 0
        for (i in 0 until count) if (!energies[i].isNaN() && energies[i] <= threshold) { noiseEnergy += energies[i]; noiseBlocks++ }
        val sigma = sqrt(noiseEnergy / noiseBlocks.coerceAtLeast(1)).toFloat()

        // 2. Perfil.
        val profiler = NoiseProfile()
        run {
            val hp = HighPass(sampleRate, 80f, channels)
            var idx = 0
            val frames = FrameStream(channels) { f ->
                val e = if (idx < count) energies[idx] else Float.NaN
                idx++
                if (!e.isNaN() && e <= threshold) profiler.accumulate(f)
            }
            var done = 0L
            if (!original.run { chunk, n -> hp.process(chunk, n); frames.push(chunk, n); done += n; stage(1, done) }) return null
        }
        val profile = if (profiler.frameCount > 0) profiler.profile() else FloatArray(NoiseProfile().profile().size)

        // 3. Limpieza.
        val passDb = SpectralCleaner.passDb(options.level)
        val c1 = SpectralCleaner(channels, profile, passDb)
        // La segunda pasada ve el ruido que dejó la primera, y resta con más suavidad.
        val residual = 10f.pow(-passDb / 10f)
        val c2 = SpectralCleaner(channels, FloatArray(profile.size) { profile[it] * residual }, passDb, alphaMax = 2f)
        val nl = if (options.extra) NlMeans(channels, sampleRate, sigma * 10f.pow(-2 * passDb / 20f)) else null
        val meter = LoudnessMeter(sampleRate, channels)
        var skip = (c1.latency + c2.latency).toLong()
        var written = 0L
        var inputFrames = 0L
        val half = FloatArray(0).let { arrayOf(it) }
        fun emit(data: FloatArray, n: Int) {
            var from = 0
            if (skip > 0) {
                val s = minOf(skip, n.toLong()).toInt()
                skip -= s
                from = s
            }
            val take = minOf((n - from).toLong(), inputFrames - written).toInt()
            if (take <= 0) return
            val chunk = if (from == 0 && take == n) data else data.copyOfRange(from * channels, (from + take) * channels)
            meter.push(chunk, take)
            if (half[0].size < take * channels) half[0] = FloatArray(take * channels)
            for (i in 0 until take * channels) half[0][i] = chunk[i] * 0.5f
            tempOut(half[0], take)
            written += take
        }
        fun after2(data: FloatArray, n: Int) {
            if (nl == null) emit(data, n) else { val (o, c) = nl.process(data, n); emit(o, c) }
        }
        fun after1(data: FloatArray, n: Int) {
            val (o, c) = c2.process(data, n)
            after2(o, c)
        }
        run {
            val hp = HighPass(sampleRate, 80f, channels)
            if (!original.run { chunk, n ->
                    inputFrames += n
                    hp.process(chunk, n)
                    val (o, c) = c1.process(chunk, n)
                    after1(o, c)
                    stage(2, inputFrames)
                }) return null
            val (o1, n1) = c1.flush()
            after1(o1, n1)
            val (o2, n2) = c2.flush()
            after2(o2, n2)
            if (nl != null) { val (o3, n3) = nl.flush(); emit(o3, n3) }
            tempDone()
        }

        // 4. Volumen y limitador.
        val before = meter.integrated()
        val gainDb = if (options.normalize && before.isFinite()) (options.targetLufs - before).coerceIn(-20.0, 24.0) else 0.0
        val gain = 10.0.pow(gainDb / 20).toFloat() * 2f // ×2: el temporal está a la mitad
        val ceiling = 10f.pow((if (options.normalize) options.ceilingDb else -0.1f) / 20f)
        val limiter = Limiter(channels, sampleRate, ceiling)
        var outFrames = 0L
        // El limitador ya entrega cada muestra en su lugar (su retraso se recupera con flush()).
        fun final(data: FloatArray, n: Int) {
            val take = minOf(n.toLong(), written - outFrames).toInt()
            if (take <= 0) return
            out(if (take == n) data else data.copyOf(take * channels), take)
            outFrames += take
        }
        var scaled = FloatArray(0)
        if (!temp.run { chunk, n ->
                if (scaled.size < n * channels) scaled = FloatArray(n * channels)
                for (i in 0 until n * channels) scaled[i] = chunk[i] * gain
                val (o, c) = limiter.process(scaled, n)
                final(o, c)
                stage(3, outFrames)
            }) return null
        val (o, c) = limiter.flush()
        final(o, c)
        progress(1f)
        val noiseDb = if (sigma > 0) 20 * kotlin.math.log10(sigma) else -120f
        return CleanReport(noiseBlocks, noiseDb, before, gainDb)
    }
}
