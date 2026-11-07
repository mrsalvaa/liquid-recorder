// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Filtro de graves (pasa altos de Butterworth de segundo orden), por canal; como `highpass` de FFmpeg. */
class HighPass(sampleRate: Int, cutoff: Float, private val channels: Int) {
    private val b0: Float
    private val b1: Float
    private val b2: Float
    private val a1: Float
    private val a2: Float
    private val z1 = FloatArray(channels)
    private val z2 = FloatArray(channels)

    init {
        val w = 2 * PI * cutoff / sampleRate
        val q = 1 / sqrt(2.0)
        val alpha = sin(w) / (2 * q)
        val cosw = cos(w)
        val a0 = 1 + alpha
        b0 = ((1 + cosw) / 2 / a0).toFloat()
        b1 = (-(1 + cosw) / a0).toFloat()
        b2 = ((1 + cosw) / 2 / a0).toFloat()
        a1 = (-2 * cosw / a0).toFloat()
        a2 = ((1 - alpha) / a0).toFloat()
    }

    /** Filtra en el lugar [frames] cuadros intercalados. */
    fun process(buf: FloatArray, frames: Int) {
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val i = f * channels + c
                val x = buf[i]
                // Forma directa II transpuesta.
                val y = b0 * x + z1[c]
                z1[c] = b1 * x - a1 * y + z2[c]
                z2[c] = b2 * x - a2 * y
                buf[i] = y
            }
        }
    }
}
