// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Limpieza de ruido de una grabación ya hecha, solo con procesamiento de señal clásico (sin IA).
// Es el pipeline de FFmpeg + libspecbleach llevado a Kotlin, en el mismo orden:
//   1. HighPass a 80 Hz                         (highpass de FFmpeg)
//   2. NoiseProfile: perfil con los bloques más silenciosos del archivo
//   3. SpectralCleaner ×2: sustracción espectral con ese perfil fijo (libspecbleach, modo manual)
//   4. NlMeans: medias no locales en el tiempo, opcional (anlmdn de FFmpeg)
//   5. LoudnessMeter + Limiter: volumen EBU R128 (loudnorm de FFmpeg)

/**
 * Corta audio intercalado en bloques de [size] muestras cada [hop], igual en todas las pasadas
 * (análisis, perfil y limpieza), para que el bloque N sea siempre el mismo. Arranca con
 * `size - hop` ceros: así el primer bloque ya incluye la primera muestra.
 */
class FrameStream(private val channels: Int, val size: Int = 2048, val hop: Int = 512, private val onFrame: (Array<FloatArray>) -> Unit) {
    private val frame = Array(channels) { FloatArray(size) }
    private var filled = size - hop
    var count = 0L
        private set

    fun push(input: FloatArray, frames: Int) {
        var i = 0
        while (i < frames) {
            val n = minOf(size - filled, frames - i)
            for (k in 0 until n) for (c in 0 until channels) frame[c][filled + k] = input[(i + k) * channels + c]
            filled += n
            i += n
            if (filled == size) {
                onFrame(frame)
                count++
                for (c in 0 until channels) System.arraycopy(frame[c], hop, frame[c], 0, size - hop)
                filled = size - hop
            }
        }
    }

    companion object {
        /** Un micrófono real nunca da muestras exactamente en cero: varias juntas son silencio digital. */
        fun isDigitalSilence(frame: Array<FloatArray>): Boolean {
            var zeros = 0
            for (v in frame[0]) if (v == 0f) zeros++
            return zeros >= 16
        }

        /** Energía media (sin ventana) de la mezcla mono del bloque. */
        fun energy(frame: Array<FloatArray>): Float {
            var sum = 0.0
            val n = frame[0].size
            for (k in 0 until n) {
                var m = 0f
                for (c in frame.indices) m += frame[c][k]
                m /= frame.size
                sum += m * m
            }
            return (sum / n).toFloat()
        }
    }
}

/** Ventana de raíz de Hann periódica: aplicada al analizar y al sintetizar reconstruye exacto. */
internal fun sqrtHann(size: Int) = FloatArray(size) { sqrt(0.5 - 0.5 * cos(2 * PI * it / size)).toFloat() }

/**
 * Perfil de ruido automático: entre los bloques que no son silencio digital, toma el [fraction]
 * (7,5 % por defecto) más silencioso. Primero [threshold] con las energías de todo el archivo;
 * después, en otra pasada, [accumulate] suma el espectro de esos bloques y [profile] da el promedio.
 */
class NoiseProfile(private val size: Int = 2048) {
    private val fft = Fft(size)
    private val window = sqrtHann(size)
    private val re = FloatArray(size)
    private val im = FloatArray(size)
    private val sum = DoubleArray(size / 2 + 1)
    private var frames = 0

    fun accumulate(frame: Array<FloatArray>) {
        for (k in 0 until size) {
            var m = 0f
            for (c in frame.indices) m += frame[c][k]
            re[k] = m / frame.size * window[k]
            im[k] = 0f
        }
        fft.transform(re, im)
        for (b in sum.indices) sum[b] += (re[b] * re[b] + im[b] * im[b]).toDouble()
        frames++
    }

    val frameCount: Int get() = frames

    fun profile(): FloatArray = FloatArray(sum.size) { (sum[it] / max(frames, 1)).toFloat() }

    companion object {
        /**
         * Energía máxima que cuenta como ruido. [energies] son las de cada bloque (NaN = silencio
         * digital, no cuenta). Si hay pocos bloques, se toman al menos 10.
         */
        fun threshold(energies: FloatArray, count: Int, fraction: Float = 0.075f): Float {
            val valid = (0 until count).map { energies[it] }.filter { !it.isNaN() }.sorted()
            if (valid.isEmpty()) return 0f
            val n = maxOf(minOf(10, valid.size), (valid.size * fraction).toInt())
            return valid[n - 1]
        }
    }
}

/**
 * Sustracción espectral con un perfil de ruido fijo (como libspecbleach en modo manual).
 *
 * - STFT de [size] (43 ms a 48 kHz) con 75 % de solapamiento y raíz de Hann: sin ruido, la salida es
 *   la entrada retrasada [latency] muestras.
 * - Sobre-resta adaptativa (Berouti): donde la señal apenas supera al ruido se resta más (hasta
 *   [alphaMax] veces el perfil); donde la voz está muy por encima, casi nada.
 * - La ganancia sube al instante y baja suave, y se promedia con las bandas vecinas: así no
 *   aparece "ruido musical" (pitidos sueltos que van y vienen).
 * - Nunca baja más de [reductionDb]: un piso de ruido natural suena mejor que silencio artificial.
 *
 * En estéreo la ganancia sale de la mezcla y se aplica igual a los dos canales.
 */
class SpectralCleaner(
    private val channels: Int,
    profile: FloatArray,
    reductionDb: Float,
    private val alphaMax: Float = 3f,
    private val size: Int = 2048,
    /**
     * Además del perfil, seguir el ruido del momento (mínimo de los últimos ~2,7 s): si el ruido
     * sube en algún tramo (un ventilador que arranca, gente de fondo), también se baja ahí.
     */
    private val adaptive: Boolean = true,
) {
    private val hop = size / 4
    val latency: Int get() = size - hop

    private val fft = Fft(size)
    private val window = sqrtHann(size)
    private val bins = size / 2 + 1
    private val noise = profile.copyOf()
    private val floor = 10f.pow(-reductionDb / 20f)
    private val frame = Array(channels) { FloatArray(size) }
    private val overlap = Array(channels) { FloatArray(size) }
    private var filled = size - hop
    private val re = Array(channels) { FloatArray(size) }
    private val im = Array(channels) { FloatArray(size) }
    private val gain = FloatArray(bins)
    private val smooth = FloatArray(bins) { 1f }
    private val prevGain = FloatArray(bins) { 1f }
    private val prevPost = FloatArray(bins)
    private var out = FloatArray(0)
    private var outCount = 0

    // Seguimiento local del ruido: mínimo de la energía suavizada en 8 tramos de 32 ventanas.
    private val power = FloatArray(bins)
    private val curMin = FloatArray(bins) { Float.MAX_VALUE }
    private val ring = Array(SUBS) { FloatArray(bins) }
    private var ringPos = 0
    private var ringFilled = 0
    private var inSub = 0
    private var frames = 0L

    init {
        require(profile.size == bins) { "El perfil tiene que tener ${'$'}bins bandas" }
    }

    fun process(input: FloatArray, frames: Int): Pair<FloatArray, Int> {
        ensureOut(frames + hop)
        outCount = 0
        var i = 0
        while (i < frames) {
            val n = minOf(size - filled, frames - i)
            for (k in 0 until n) for (c in 0 until channels) frame[c][filled + k] = input[(i + k) * channels + c]
            filled += n
            i += n
            if (filled == size) {
                processFrame()
                for (c in 0 until channels) System.arraycopy(frame[c], hop, frame[c], 0, size - hop)
                filled = size - hop
            }
        }
        return out to outCount
    }

    /** Vacía lo que queda adentro: después de esto salió todo lo que entró (más [latency]). */
    fun flush(): Pair<FloatArray, Int> = process(FloatArray((latency + hop) * channels), latency + hop)

    private fun ensureOut(frames: Int) {
        if (out.size < frames * channels) out = out.copyOf(frames * channels)
    }

    private fun processFrame() {
        for (c in 0 until channels) {
            for (k in 0 until size) {
                re[c][k] = frame[c][k] * window[k]
                im[c][k] = 0f
            }
            fft.transform(re[c], im[c])
        }
        // Silencio digital: no cuenta para el ruido del momento.
        var zeros = 0
        for (v in frame[0]) if (v == 0f) zeros++
        val track = adaptive && zeros < 16
        for (b in 0 until bins) {
            // Potencia de la mezcla, en la misma escala que el perfil (calculado sobre la mezcla).
            var mr = 0f
            var mi = 0f
            for (c in 0 until channels) { mr += re[c][b]; mi += im[c][b] }
            mr /= channels; mi /= channels
            val p = mr * mr + mi * mi
            var local = 0f
            if (track) {
                power[b] = if (frames == 0L) p else 0.6f * power[b] + 0.4f * p
                if (power[b] < curMin[b]) curMin[b] = power[b]
                var m = curMin[b]
                for (k in 0 until ringFilled) if (ring[k][b] < m) m = ring[k][b]
                // Hasta tener ~1 s de historia, el perfil manda solo.
                if (ringFilled >= 3) local = m * MIN_BIAS
            }
            val n = max(max(noise[b], local), 1e-20f)
            val post = p / n
            // Sobre-resta adaptativa (Berouti): más donde la señal apenas supera al ruido.
            val snrDb = 10f * log10(max(post - 1f, 1e-3f))
            val alpha = (alphaMax - (alphaMax - 1f) * snrDb / 20f).coerceIn(1f, alphaMax)
            // SNR a priori "dirigida por decisión" (Ephraim–Malah): mira también la ganancia del
            // bloque anterior, así el ruido no titila y no quedan pitidos sueltos.
            val prio = 0.98f * prevGain[b] * prevGain[b] * prevPost[b] + 0.02f * max(post - 1f, 0f)
            prevPost[b] = post
            val g = prio / (prio + alpha)
            gain[b] = max(g, floor)
        }
        if (track) {
            frames++
            if (++inSub == SUB_LEN) {
                System.arraycopy(curMin, 0, ring[ringPos], 0, bins)
                ringPos = (ringPos + 1) % SUBS
                if (ringFilled < SUBS) ringFilled++
                java.util.Arrays.fill(curMin, Float.MAX_VALUE)
                inSub = 0
            }
        }
        for (b in 0 until bins) {
            val f = (gain[maxOf(0, b - 1)] + 2 * gain[b] + gain[minOf(bins - 1, b + 1)]) / 4f
            // Sube al instante (no se come el ataque de las consonantes) y baja suave.
            val g = if (f >= smooth[b]) f else 0.5f * smooth[b] + 0.5f * f
            smooth[b] = max(g, floor)
            val gg = smooth[b]
            prevGain[b] = gg
            for (c in 0 until channels) {
                re[c][b] *= gg; im[c][b] *= gg
                if (b in 1 until size / 2) { re[c][size - b] *= gg; im[c][size - b] *= gg }
            }
        }
        // Vuelta al tiempo (IFFT por conjugado). Con raíz de Hann al 75 % las ventanas suman 2.
        for (c in 0 until channels) {
            val r = re[c]
            val m = im[c]
            for (k in 0 until size) m[k] = -m[k]
            fft.transform(r, m)
            val o = overlap[c]
            for (k in 0 until size) o[k] += r[k] / size * window[k] * 0.5f
            for (k in 0 until hop) out[(outCount + k) * channels + c] = o[k]
            System.arraycopy(o, hop, o, 0, size - hop)
            java.util.Arrays.fill(o, size - hop, size, 0f)
        }
        outCount += hop
    }

    companion object {
        private const val SUB_LEN = 32
        private const val SUBS = 8
        /** En ~256 ventanas el mínimo de la energía del ruido queda cerca de 1/3,5 de su promedio. */
        private const val MIN_BIAS = 3.5f

        /** Reducción por pasada de cada nivel (son dos pasadas): 1 suave, 2 media, 3 fuerte. */
        fun passDb(level: Int): Float = when (level) {
            1 -> 5f
            2 -> 8f
            else -> 12f
        }
    }
}

/**
 * Medias no locales en el tiempo (el algoritmo de anlmdn de FFmpeg): cada muestra se reemplaza por
 * un promedio de las muestras cercanas cuyo entorno ([patchMs]) se parece al suyo, buscando en
 * ±[researchMs]. Lo que se repite (la voz) se conserva; el ruido, que no se parece a nada, se
 * promedia y baja. [sigma] es el ruido que queda (desvío estándar en el tiempo).
 *
 * Es lento (cientos de comparaciones por muestra): por eso es opcional.
 */
class NlMeans(
    private val channels: Int,
    sampleRate: Int,
    private val sigma: Float,
    private val strength: Float = 1f,
    patchMs: Float = 2f,
    researchMs: Float = 6f,
) {
    private val p = (sampleRate * patchMs / 1000f).toInt().coerceAtLeast(1)
    private val r = (sampleRate * researchMs / 1000f).toInt().coerceAtLeast(1)
    private val margin = r + p
    private val block = 4096
    /** Historia por canal: [margin] de contexto atrás + lo pendiente. */
    private val buf = Array(channels) { FloatArray(0) }
    private var len = 0
    private val lut = FloatArray(LUT_SIZE + 1) { exp(-it * LUT_MAX / LUT_SIZE.toFloat()) }
    private val norm = 1f / (2f * max(sigma * sigma, 1e-14f) * (2 * p + 1))
    private var out = FloatArray(0)
    private var outCount = 0

    init {
        // Empieza con un margen de ceros atrás.
        for (c in 0 until channels) buf[c] = FloatArray(margin + block + margin)
        len = margin
    }

    fun process(input: FloatArray, frames: Int): Pair<FloatArray, Int> {
        outCount = 0
        if (out.size < (frames + block) * channels) out = FloatArray((frames + block) * channels)
        var i = 0
        while (i < frames) {
            val room = margin + block + margin - len
            val n = minOf(room, frames - i)
            for (k in 0 until n) for (c in 0 until channels) buf[c][len + k] = input[(i + k) * channels + c]
            len += n
            i += n
            if (len == margin + block + margin) runBlock(block)
        }
        return out to outCount
    }

    /** Lo pendiente al terminar: devuelve exactamente lo que faltaba (sin retraso que corregir). */
    fun flush(): Pair<FloatArray, Int> {
        outCount = 0
        val pending = len - margin
        if (pending <= 0) return out to 0
        if (out.size < pending * channels) out = FloatArray(pending * channels)
        for (c in 0 until channels) java.util.Arrays.fill(buf[c], len, buf[c].size, 0f)
        runBlock(pending)
        return out to outCount
    }

    private fun runBlock(count: Int) {
        val weights = FloatArray(count)
        val acc = FloatArray(count)
        val s = DoubleArray(count + 2 * p + 1)
        for (c in 0 until channels) {
            val x = buf[c]
            java.util.Arrays.fill(weights, 0f)
            java.util.Arrays.fill(acc, 0f)
            for (k in -r..r) {
                // Suma acumulada de (x[j] - x[j+k])² desde j = margin - p.
                val j0 = margin - p
                s[0] = 0.0
                for (t in 0 until count + 2 * p) {
                    val j = j0 + t
                    val d = x[j] - x[j + k]
                    s[t + 1] = s[t] + d * d
                }
                for (n in 0 until count) {
                    // Distancia del entorno de n (±p) contra el de n+k.
                    val dist = (s[n + 2 * p + 1] - s[n]).toFloat()
                    val z = max(dist * norm - 1f, 0f) / strength
                    val w = if (z >= LUT_MAX) 0f else lut[(z / LUT_MAX * LUT_SIZE).toInt()]
                    weights[n] += w
                    acc[n] += w * x[margin + n + k]
                }
            }
            for (n in 0 until count) out[(outCount + n) * channels + c] = acc[n] / weights[n]
        }
        outCount += count
        // Correr: queda el margen de atrás para el próximo bloque.
        for (c in 0 until channels) System.arraycopy(buf[c], count, buf[c], 0, len - count)
        len -= count
    }

    private companion object {
        const val LUT_SIZE = 2048
        const val LUT_MAX = 8f
    }
}

/**
 * Volumen percibido según la norma EBU R128 / ITU-R BS.1770-4 (lo que mide loudnorm de FFmpeg):
 * filtro de ponderación K, bloques de 400 ms solapados al 75 %, puerta absoluta de −70 LUFS y
 * relativa de −10 LU.
 */
class LoudnessMeter(private val sampleRate: Int, private val channels: Int) {
    private val shelf = Array(channels) { kShelf(sampleRate) }
    private val highPass = Array(channels) { kHighPass(sampleRate) }
    private val subLen = sampleRate / 10
    private var subSum = 0.0
    private var subCount = 0
    /** Energía de cada tramo de 100 ms (suma de canales). */
    private var subs = DoubleArray(1024)
    private var nSubs = 0

    fun push(input: FloatArray, frames: Int) {
        for (f in 0 until frames) {
            var e = 0.0
            for (c in 0 until channels) {
                val y = highPass[c].step(shelf[c].step(input[f * channels + c].toDouble()))
                e += y * y
            }
            subSum += e
            if (++subCount == subLen) {
                if (nSubs == subs.size) subs = subs.copyOf(nSubs * 2)
                subs[nSubs++] = subSum / subLen
                subSum = 0.0
                subCount = 0
            }
        }
    }

    /** Volumen integrado en LUFS (o -inf si todo es silencio). */
    fun integrated(): Double {
        if (nSubs < 4) return Double.NEGATIVE_INFINITY
        val blocks = DoubleArray(nSubs - 3) { (subs[it] + subs[it + 1] + subs[it + 2] + subs[it + 3]) / 4 }
        fun lufs(z: Double) = -0.691 + 10 * log10(z)
        val abs = blocks.filter { it > 0 && lufs(it) > -70.0 }
        if (abs.isEmpty()) return Double.NEGATIVE_INFINITY
        val gate = lufs(abs.average()) - 10.0
        val rel = abs.filter { lufs(it) > gate }
        return lufs(rel.average())
    }

    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        var z1 = 0.0
        var z2 = 0.0
        fun step(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }

    private companion object {
        // Ponderación K para cualquier frecuencia, como libebur128 (la que usa FFmpeg): a 48 kHz da
        // exactamente los coeficientes de la norma ITU-R BS.1770.
        fun kShelf(fs: Int): Biquad {
            val f0 = 1681.974450955533
            val g = 3.999843853973347
            val q = 0.7071752369554196
            val k = kotlin.math.tan(PI * f0 / fs)
            val vh = 10.0.pow(g / 20)
            val vb = vh.pow(0.4996667741545416)
            val a0 = 1 + k / q + k * k
            return Biquad((vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
        }

        fun kHighPass(fs: Int): Biquad {
            val f0 = 38.13547087602444
            val q = 0.5003270373238773
            val k = kotlin.math.tan(PI * f0 / fs)
            val a0 = 1 + k / q + k * k
            return Biquad(1.0, -2.0, 1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
        }
    }
}

/**
 * Limitador con anticipación: nunca deja pasar de [ceiling] (lineal) y lo hace sin chasquidos: la
 * ganancia baja en rampa durante [lookaheadMs] antes de cada pico y vuelve despacio ([releaseMs]).
 * Retrasa la salida [latency] cuadros.
 */
class Limiter(private val channels: Int, sampleRate: Int, private val ceiling: Float, lookaheadMs: Float = 5f, releaseMs: Float = 80f) {
    val latency = (sampleRate * lookaheadMs / 1000f).toInt().coerceAtLeast(1)
    private val l = latency
    private val release = exp(-1f / (sampleRate * releaseMs / 1000f))
    private val xs = Array(channels) { FloatArray(2 * l + 2) }
    private val mins = FloatArray(l + 1)
    private var minSum = 0.0
    private val dequeIdx = LongArray(2 * l + 2)
    private val dequeVal = FloatArray(2 * l + 2)
    private var dHead = 0
    private var dTail = 0
    private var t = 0L
    private var gain = 1f
    private var out = FloatArray(0)

    init { java.util.Arrays.fill(mins, 1f); minSum = (l + 1).toDouble() }

    fun process(input: FloatArray, frames: Int): Pair<FloatArray, Int> {
        if (out.size < frames * channels) out = FloatArray(frames * channels)
        var produced = 0
        val cap = 2 * l + 2
        for (f in 0 until frames) {
            var peak = 0f
            for (c in 0 until channels) {
                val v = input[f * channels + c]
                xs[c][(t % cap).toInt()] = v
                val a = if (v < 0) -v else v
                if (a > peak) peak = a
            }
            val r = if (peak > ceiling) ceiling / peak else 1f
            // Mínimo de la ganancia necesaria en los próximos l cuadros (deque monótona).
            while (dTail > dHead && dequeVal[(dTail - 1) % cap] >= r) dTail--
            dequeIdx[dTail % cap] = t
            dequeVal[dTail % cap] = r
            dTail++
            val n = t - l
            if (n >= 0) {
                while (dequeIdx[dHead % cap] < n) dHead++
                val m = dequeVal[dHead % cap]
                // Promedio de los mínimos: rampa suave que igual queda por debajo de lo necesario.
                val slot = (n % (l + 1)).toInt()
                minSum += m - mins[slot]
                mins[slot] = m
                val target = (minSum / (l + 1)).toFloat()
                gain = if (target < gain) target else minOf(target, 1f - (1f - gain) * release)
                for (c in 0 until channels) out[produced * channels + c] = xs[c][(n % cap).toInt()] * gain
                produced++
            }
            t++
        }
        return out to produced
    }

    fun flush(): Pair<FloatArray, Int> = process(FloatArray(l * channels), l)
}
