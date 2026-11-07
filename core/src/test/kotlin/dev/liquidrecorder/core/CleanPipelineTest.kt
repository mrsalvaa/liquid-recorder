// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.core

import dev.liquidrecorder.core.dsp.AudioPass
import dev.liquidrecorder.core.dsp.CleanOptions
import dev.liquidrecorder.core.dsp.CleanPipeline
import dev.liquidrecorder.core.dsp.LoudnessMeter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CleanPipelineTest {
    /** Corre el pipeline entero en memoria (el temporal es una lista). */
    private fun clean(x: FloatArray, channels: Int, rate: Int, options: CleanOptions): Pair<FloatArray, dev.liquidrecorder.core.dsp.CleanReport?> {
        val frames = x.size / channels
        fun pass(data: () -> FloatArray, n: Int) = AudioPass { onChunk ->
            val d = data()
            var i = 0
            while (i < n) {
                val c = minOf(4800, n - i)
                if (!onChunk(d.copyOfRange(i * channels, (i + c) * channels), c)) return@AudioPass false
                i += c
            }
            true
        }
        val temp = ArrayList<Float>()
        val out = ArrayList<Float>()
        val report = CleanPipeline(channels, rate, options).run(
            original = pass({ x }, frames),
            temp = pass({ temp.toFloatArray() }, frames),
            tempOut = { d, n -> for (k in 0 until n * channels) temp += d[k] },
            tempDone = {},
            out = { d, n -> for (k in 0 until n * channels) out += d[k] },
            totalFrames = frames.toLong(),
            progress = { true },
        )
        return out.toFloatArray() to report
    }

    private fun rms(a: FloatArray, from: Int, to: Int) = kotlin.math.sqrt((from until to).sumOf { (a[it] * a[it]).toDouble() } / (to - from)).toFloat()

    @Test
    fun pipelineCompleto() {
        val rnd = kotlin.random.Random(11)
        val n = 48_000 * 8
        val x = FloatArray(n) { i ->
            if (i < 12_000) 0f else {
                val noise = (rnd.nextFloat() - 0.5f) * 0.02f
                val talking = (i / 48_000) % 2 == 1
                noise + if (talking) (kotlin.math.sin(2 * Math.PI * 500 * i / 48_000) * 0.2).toFloat() else 0f
            }
        }
        val (y, report) = clean(x, 1, 48_000, CleanOptions(level = 2))
        assertNotNull(report)
        assertEquals(x.size, y.size, "mismo largo que el original")
        // Volumen: −16 LUFS y nunca por encima de −1,5 dBFS.
        val m = LoudnessMeter(48_000, 1)
        m.push(y, y.size)
        assertEquals(-16.0, m.integrated(), 0.6)
        assertTrue(y.maxOf { kotlin.math.abs(it) } <= 0.8415f)
        // Relación entre voz y ruido: mejora más de 12 dB (la ganancia sube los dos por igual).
        val snrBefore = 20 * kotlin.math.log10(rms(x, 50_000, 90_000) / rms(x, 100_000, 140_000))
        val snrAfter = 20 * kotlin.math.log10(rms(y, 50_000, 90_000) / rms(y, 100_000, 140_000))
        assertTrue(snrAfter - snrBefore > 12f, "mejoró ${snrAfter - snrBefore} dB")
        // Alineado: la voz empieza en el mismo lugar (segundo 1).
        assertTrue(rms(y, 48_200, 49_000) > 10 * rms(y, 46_000, 47_800))
    }

    @Test
    fun estereoYPulidoExtra() {
        val rnd = kotlin.random.Random(2)
        val n = 48_000 * 4
        val x = FloatArray(n * 2) { (rnd.nextFloat() - 0.5f) * 0.01f + if ((it / 2 / 48_000) % 2 == 1) (kotlin.math.sin(2 * Math.PI * 300 * (it / 2) / 48_000) * 0.2).toFloat() else 0f }
        val (y, report) = clean(x, 2, 48_000, CleanOptions(level = 1, extra = true, normalize = false))
        assertNotNull(report)
        assertEquals(x.size, y.size)
    }

    /** Herramienta manual: -Pliquid.clean=archivo.f32 (mono, 48 kHz, float) escribe .clean{1,2,3}. */
    @Test
    fun archivoReal() {
        val path = System.getProperty("liquid.clean") ?: return
        val fb = ByteBuffer.wrap(File(path).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val x = FloatArray(fb.remaining()).also { fb.get(it) }
        for (level in 1..3) {
            val (y, r) = clean(x, 1, 48_000, CleanOptions(level = level, extra = System.getProperty("liquid.extra") == "1"))
            println("nivel $level: $r")
            val bb = ByteBuffer.allocate(y.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            y.forEach { bb.putFloat(it) }
            File("$path.clean$level").writeBytes(bb.array())
        }
    }
}
