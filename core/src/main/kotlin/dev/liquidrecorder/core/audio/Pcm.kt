// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.core.audio

import kotlin.math.pow
import kotlin.random.Random

/** Conversión de muestras de coma flotante a enteros, con ganancia y recorte. */
class PcmConverter(bits: Int, private val random: Random = Random(1)) {
    private val max = (1 shl (bits - 1)) - 1
    private val min = -(1 shl (bits - 1))
    private val scale = (1 shl (bits - 1)).toFloat()

    /** Con 16 bits se agrega dither triangular (TPDF) para que los pasajes suaves no suenen ásperos. */
    private val dither = bits <= 16

    /** Cuántas muestras se recortaron (pasaron de 0 dBFS) desde la última lectura. */
    var clipped = 0
        private set

    fun takeClipped(): Int = clipped.also { clipped = 0 }

    /** Convierte [count] muestras de [input] multiplicadas por [gain] y las deja en [output]. */
    fun convert(input: FloatArray, count: Int, gain: Float, output: IntArray) {
        for (i in 0 until count) {
            var v = input[i] * gain * scale
            if (dither) v += random.nextFloat() - random.nextFloat()
            val r = Math.round(v)
            output[i] = when {
                r > max -> { clipped++; max }
                r < min -> { clipped++; min }
                else -> r
            }
        }
    }

    companion object {
        fun dbToGain(db: Float): Float = 10f.pow(db / 20f)
    }
}

/** Mezcla un búfer intercalado a mono (promedio de canales). */
fun downmix(input: FloatArray, frames: Int, channels: Int, output: FloatArray) {
    if (channels == 1) {
        System.arraycopy(input, 0, output, 0, frames)
        return
    }
    for (f in 0 until frames) {
        var s = 0f
        for (c in 0 until channels) s += input[f * channels + c]
        output[f] = s / channels
    }
}

/**
 * Adapta audio intercalado a otra cantidad de canales: estéreo a mono promedia, mono a estéreo
 * duplica y más de dos canales (videos 5.1) se mezclan con los dos primeros como izquierda/derecha.
 */
fun remapChannels(input: FloatArray, frames: Int, from: Int, to: Int): FloatArray {
    if (from == to) return input
    val out = FloatArray(frames * to)
    for (f in 0 until frames) {
        if (to == 1) {
            var s = 0f
            for (c in 0 until from) s += input[f * from + c]
            out[f] = s / from
        } else {
            val l = input[f * from]
            val r = if (from > 1) input[f * from + 1] else l
            out[f * to] = l
            out[f * to + 1] = r
        }
    }
    return out
}

/**
 * Cambio de frecuencia de muestreo con interpolación de Hermite (4 puntos): bastante mejor que la
 * lineal y sin el costo de un filtro polifásico. Solo se usa al exportar o importar a otra
 * frecuencia (por ejemplo, Opus pide 48 kHz); las grabaciones nunca pasan por acá.
 */
class Resampler(private val from: Int, private val to: Int, private val channels: Int) {
    private val step = from.toDouble() / to
    private var pos = 0.0
    /** Últimos 3 cuadros del bloque anterior, para interpolar en el borde. */
    private val tail = FloatArray(3 * channels)
    private var tailFrames = 0

    val passthrough: Boolean get() = from == to

    fun process(input: FloatArray, frames: Int): Pair<FloatArray, Int> {
        if (passthrough) return input to frames
        val total = tailFrames + frames
        val buf = FloatArray(total * channels)
        System.arraycopy(tail, 0, buf, 0, tailFrames * channels)
        System.arraycopy(input, 0, buf, tailFrames * channels, frames * channels)
        val outMax = ((total - pos) / step).toInt() + 2
        val out = FloatArray(outMax * channels)
        var n = 0
        // Se necesitan los puntos i-1, i, i+1, i+2.
        while (pos + 2 < total) {
            val i = pos.toInt()
            val t = (pos - i).toFloat()
            for (c in 0 until channels) {
                val ym1 = buf[maxOf(i - 1, 0) * channels + c]
                val y0 = buf[i * channels + c]
                val y1 = buf[(i + 1) * channels + c]
                val y2 = buf[(i + 2) * channels + c]
                val c1 = 0.5f * (y1 - ym1)
                val c2 = ym1 - 2.5f * y0 + 2f * y1 - 0.5f * y2
                val c3 = 0.5f * (y2 - ym1) + 1.5f * (y0 - y1)
                out[n * channels + c] = ((c3 * t + c2) * t + c1) * t + y0
            }
            n++
            pos += step
        }
        // Guardar los últimos 3 cuadros y correr la posición.
        val keep = minOf(3, total)
        System.arraycopy(buf, (total - keep) * channels, tail, 0, keep * channels)
        pos -= (total - keep)
        tailFrames = keep
        return out to n
    }
}
