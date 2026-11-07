// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** FFT compleja in situ, radix 2. [size] tiene que ser potencia de dos. */
class Fft(val size: Int) {
    private val levels = Integer.numberOfTrailingZeros(size)
    private val cosT = FloatArray(size / 2) { cos(2 * PI * it / size).toFloat() }
    private val sinT = FloatArray(size / 2) { sin(2 * PI * it / size).toFloat() }
    private val rev = IntArray(size) { Integer.reverse(it) ushr (32 - levels) }

    init {
        require(size >= 2 && size and (size - 1) == 0) { "La FFT necesita una potencia de dos" }
    }

    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until size) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var i = 0
            while (i < size) {
                var k = 0
                for (j in i until i + half) {
                    val c = cosT[k]
                    val s = sinT[k]
                    val l = j + half
                    val tre = re[l] * c + im[l] * s
                    val tim = -re[l] * s + im[l] * c
                    re[l] = re[j] - tre
                    im[l] = im[j] - tim
                    re[j] += tre
                    im[j] += tim
                    k += step
                }
                i += len
            }
            len *= 2
        }
    }
}

/**
 * Columnas de espectrograma a partir de audio mono. Cada columna tiene [rows] valores entre 0 y 1
 * (de [floorDb] a [ceilDb] por banda), con las frecuencias en escala logarítmica de [minHz] a [maxHz]:
 * así la voz ocupa buena parte de la altura en vez de quedar apretada abajo.
 */
class Spectrogram(
    private val sampleRate: Int,
    val rows: Int = 96,
    private val fftSize: Int = 2048,
    private val minHz: Float = 50f,
    maxHz: Float = 16_000f,
    /** Rango por banda: el ruido de fondo de un cuarto queda cerca del piso, la voz arriba. */
    private val floorDb: Float = -110f,
    private val ceilDb: Float = -20f,
) {
    private val fft = Fft(fftSize)
    private val window = FloatArray(fftSize) { (0.5 - 0.5 * cos(2 * PI * it / (fftSize - 1))).toFloat() }
    /** Ganancia de la ventana de Hann: un seno a 0 dBFS queda a 0 dB. */
    private val norm = 2f / window.sum()
    private val history = FloatArray(fftSize)
    private var filled = 0
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val maxHz = minOf(maxHz, sampleRate / 2f)

    /** Para cada fila, el rango de bins de FFT que la forman. */
    private val rowFrom = IntArray(rows)
    private val rowTo = IntArray(rows)

    init {
        val binHz = sampleRate.toFloat() / fftSize
        val lnMin = ln(minHz)
        val lnMax = ln(this.maxHz)
        for (r in 0 until rows) {
            val f0 = exp(lnMin + (lnMax - lnMin) * r / rows)
            val f1 = exp(lnMin + (lnMax - lnMin) * (r + 1) / rows)
            val b0 = (f0 / binHz).toInt().coerceIn(1, fftSize / 2 - 1)
            val b1 = (f1 / binHz).toInt().coerceIn(b0, fftSize / 2 - 1)
            rowFrom[r] = b0
            rowTo[r] = b1
        }
    }

    /** Suma [count] muestras nuevas; devuelve true cuando ya hay una ventana completa. */
    fun push(samples: FloatArray, count: Int): Boolean {
        if (count >= fftSize) {
            System.arraycopy(samples, count - fftSize, history, 0, fftSize)
        } else {
            System.arraycopy(history, count, history, 0, fftSize - count)
            System.arraycopy(samples, 0, history, fftSize - count, count)
        }
        filled = minOf(fftSize, filled + count)
        return filled == fftSize
    }

    /** Calcula la columna de las últimas [fftSize] muestras y la deja en [out] (fila 0 = graves). */
    fun column(out: FloatArray) {
        for (i in 0 until fftSize) {
            re[i] = history[i] * window[i]
            im[i] = 0f
        }
        fft.transform(re, im)
        for (r in 0 until rows) {
            var peak = 0f
            for (b in rowFrom[r]..rowTo[r]) {
                val m = re[b] * re[b] + im[b] * im[b]
                if (m > peak) peak = m
            }
            val db = 10f * log10(peak * norm * norm + 1e-20f)
            out[r] = ((db - floorDb) / (ceilDb - floorDb)).coerceIn(0f, 1f)
        }
    }

    /** Frecuencia (Hz) en el centro de la fila [row], para dibujar referencias. */
    fun rowHz(row: Float): Float = exp(ln(minHz) + (ln(maxHz) - ln(minHz)) * row / rows)
}

/** Nivel de un tramo de audio en dBFS. */
data class Level(val peakDb: Float, val rmsDb: Float, val clipped: Boolean)

object Levels {
    const val SILENCE_DB = -90f

    fun toDb(amplitude: Float): Float = if (amplitude <= 1e-9f) SILENCE_DB else (20f * log10(amplitude)).coerceAtLeast(SILENCE_DB)

    fun measure(samples: FloatArray, count: Int, gain: Float = 1f): Level {
        var peak = 0f
        var sum = 0.0
        for (i in 0 until count) {
            val v = samples[i] * gain
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
            sum += v * v
        }
        val rms = if (count == 0) 0f else sqrt(sum / count).toFloat()
        return Level(toDb(peak), toDb(rms), peak >= 0.999f)
    }
}

/**
 * Picos de la forma de onda: un valor por cada [msPerPeak] ms, de 0 a 255 en escala de dB (de -60 a
 * 0 dBFS), que se ve mejor que la amplitud lineal para voz. Se guardan junto a cada grabación.
 */
class PeakCollector(private val sampleRate: Int, val msPerPeak: Int = PEAK_MS) {
    private val perPeak = sampleRate * msPerPeak / 1000
    private var current = 0f
    private var counted = 0
    private var data = ByteArray(1024)
    var size = 0
        private set

    fun push(mono: FloatArray, count: Int) {
        for (i in 0 until count) {
            val a = if (mono[i] < 0) -mono[i] else mono[i]
            if (a > current) current = a
            if (++counted == perPeak) emit()
        }
    }

    private fun emit() {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = encode(current).toByte()
        current = 0f
        counted = 0
    }

    fun toByteArray(): ByteArray = data.copyOf(size)

    /** Copia de los últimos [n] picos (para la onda en vivo). */
    fun tail(n: Int): ByteArray {
        val from = maxOf(0, size - n)
        return data.copyOfRange(from, size)
    }

    companion object {
        const val PEAK_MS = 25

        fun encode(amplitude: Float): Int {
            val db = Levels.toDb(amplitude)
            return (((db + 60f) / 60f).coerceIn(0f, 1f) * 255f).toInt()
        }

        /** De vuelta a 0..1 para dibujar. */
        fun height(b: Byte): Float = (b.toInt() and 0xFF) / 255f
    }
}
