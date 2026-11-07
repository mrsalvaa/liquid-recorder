// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.core

import dev.liquidrecorder.core.dsp.Fft
import dev.liquidrecorder.core.dsp.Levels
import dev.liquidrecorder.core.dsp.PeakCollector
import dev.liquidrecorder.core.dsp.Spectrogram
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DspTest {
    @Test
    fun fftFindsTone() {
        val n = 1024
        val fft = Fft(n)
        val re = FloatArray(n) { sin(2 * PI * 37 * it / n).toFloat() }
        val im = FloatArray(n)
        fft.transform(re, im)
        val mags = FloatArray(n / 2) { re[it] * re[it] + im[it] * im[it] }
        assertEquals(37, mags.indices.maxBy { mags[it] })
    }

    @Test
    fun spectrogramPutsToneInRightRow() {
        val s = Spectrogram(48_000, rows = 64)
        val buf = FloatArray(2048) { sin(2 * PI * 1000 * it / 48_000).toFloat() * 0.5f }
        assertTrue(s.push(buf, buf.size))
        val col = FloatArray(64)
        s.column(col)
        val row = col.indices.maxBy { col[it] }
        val hz = s.rowHz(row + 0.5f)
        assertTrue(hz in 850f..1180f, "fila $row = $hz Hz")
        // -6 dBFS está sobre el techo: al máximo.
        assertTrue(col[row] > 0.99f, "valor ${col[row]}")
        // Lejos del tono, casi nada.
        assertTrue(col[0] < 0.3f)
    }

    @Test
    fun spectrogramNeedsFullWindow() {
        val s = Spectrogram(48_000)
        assertTrue(!s.push(FloatArray(960), 960))
        assertTrue(s.push(FloatArray(1200), 1200))
    }

    @Test
    fun levels() {
        val l = Levels.measure(floatArrayOf(0.5f, -0.5f, 0.5f, -0.5f), 4)
        assertEquals(-6.02f, l.peakDb, 0.05f)
        assertEquals(-6.02f, l.rmsDb, 0.05f)
        assertTrue(!l.clipped)
        assertTrue(Levels.measure(floatArrayOf(1f), 1).clipped)
        assertEquals(Levels.SILENCE_DB, Levels.measure(FloatArray(10), 10).peakDb)
    }

    @Test
    fun peaksOnePer25ms() {
        val p = PeakCollector(48_000)
        val second = FloatArray(48_000) { if (it < 24_000) 1f else 0f }
        p.push(second, second.size)
        assertEquals(40, p.size)
        val bytes = p.toByteArray()
        assertEquals(255, bytes[0].toInt() and 0xFF)
        assertEquals(0, bytes[39].toInt() and 0xFF)
        assertEquals(5, p.tail(5).size)
    }
}

class ResampleTest {
    @Test
    fun resamplesLengthAndTone() {
        val r = dev.liquidrecorder.core.audio.Resampler(44_100, 48_000, 1)
        var produced = 0
        val out = ArrayList<Float>()
        repeat(10) { block ->
            val input = FloatArray(4410) { i -> kotlin.math.sin(2 * Math.PI * 1000 * (block * 4410 + i) / 44_100).toFloat() }
            val (o, n) = r.process(input, 4410)
            for (i in 0 until n) out += o[i]
            produced += n
        }
        assertTrue(kotlin.math.abs(produced - 48_000) < 10, "salieron $produced")
        // El tono sigue siendo 1 kHz a 48 kHz: comparar con el seno ideal (sin fase inicial).
        var err = 0.0
        for (i in 100 until 47_000) err = maxOf(err, kotlin.math.abs(out[i] - kotlin.math.sin(2 * Math.PI * 1000 * i / 48_000)).toDouble())
        assertTrue(err < 0.01, "error $err")
    }

    @Test
    fun channels() {
        val m = dev.liquidrecorder.core.audio.remapChannels(floatArrayOf(1f, 0f, 0.5f, 0.5f), 2, 2, 1)
        assertEquals(listOf(0.5f, 0.5f), m.toList())
        val s = dev.liquidrecorder.core.audio.remapChannels(floatArrayOf(0.2f), 1, 1, 2)
        assertEquals(listOf(0.2f, 0.2f), s.toList())
    }
}

class CleaningTest {
    private fun sine(n: Int, hz: Double, amp: Float, rate: Int = 48_000) = FloatArray(n) { (kotlin.math.sin(2 * Math.PI * hz * it / rate) * amp).toFloat() }
    private fun rms(a: FloatArray, from: Int, to: Int) = kotlin.math.sqrt((from until to).sumOf { (a[it] * a[it]).toDouble() } / (to - from)).toFloat()
    private fun db(x: Float) = 20 * kotlin.math.log10(x)

    /** Pasa [x] (mono) por un proceso en bloques de 960, con su vaciado al final. */
    private fun stream(x: FloatArray, process: (FloatArray, Int) -> Pair<FloatArray, Int>, flush: () -> Pair<FloatArray, Int>): FloatArray {
        val out = ArrayList<Float>()
        var i = 0
        while (i < x.size) {
            val n = minOf(960, x.size - i)
            val (o, c) = process(x.copyOfRange(i, i + n), n)
            for (k in 0 until c) out += o[k]
            i += n
        }
        val (o, c) = flush()
        for (k in 0 until c) out += o[k]
        return out.toFloatArray()
    }

    private fun profileOf(x: FloatArray): FloatArray {
        val energies = ArrayList<Float>()
        dev.liquidrecorder.core.dsp.FrameStream(1) { f ->
            energies += if (dev.liquidrecorder.core.dsp.FrameStream.isDigitalSilence(f)) Float.NaN else dev.liquidrecorder.core.dsp.FrameStream.energy(f)
        }.push(x, x.size)
        val thr = dev.liquidrecorder.core.dsp.NoiseProfile.threshold(energies.toFloatArray(), energies.size)
        val prof = dev.liquidrecorder.core.dsp.NoiseProfile()
        var idx = 0
        dev.liquidrecorder.core.dsp.FrameStream(1) { f ->
            val e = energies[idx++]
            if (!e.isNaN() && e <= thr) prof.accumulate(f)
        }.push(x, x.size)
        return prof.profile()
    }

    @Test
    fun sinRuidoLaLimpiezaNoCambiaNada() {
        val c = dev.liquidrecorder.core.dsp.SpectralCleaner(1, FloatArray(1025), 12f)
        val x = sine(48_000, 440.0, 0.5f)
        val y = stream(x, c::process, c::flush)
        var err = 0f
        for (i in 0 until x.size - 100) err = maxOf(err, kotlin.math.abs(y[i + c.latency] - x[i]))
        assertTrue(err < 1e-4f, "error $err")
    }

    @Test
    fun dosPasadasBajanElRuidoYConservanLaVoz() {
        val rnd = kotlin.random.Random(5)
        val n = 48_000 * 6
        // Medio segundo de silencio digital, ruido todo el tiempo y un tono (la "voz") en pausas.
        val x = FloatArray(n) { i ->
            if (i < 24_000) 0f else {
                val noise = (rnd.nextFloat() - 0.5f) * 0.02f
                val talking = (i / 48_000) % 2 == 1
                noise + if (talking) (kotlin.math.sin(2 * Math.PI * 700 * i / 48_000) * 0.3).toFloat() else 0f
            }
        }
        val prof = profileOf(x)
        val db = dev.liquidrecorder.core.dsp.SpectralCleaner.passDb(2)
        val c1 = dev.liquidrecorder.core.dsp.SpectralCleaner(1, prof, db)
        // La segunda pasada ve el ruido que dejó la primera.
        val floor = 10f.pow(-db / 10f)
        val c2 = dev.liquidrecorder.core.dsp.SpectralCleaner(1, FloatArray(prof.size) { prof[it] * floor }, db, alphaMax = 2f)
        val y1 = stream(x, c1::process, c1::flush)
        val y = stream(y1, c2::process, c2::flush)
        val lat = c1.latency + c2.latency
        // Ruido solo: segundo 2 (pausa). Voz: segundo 3.
        val noiseDrop = db(rms(x, 100_000, 140_000) / rms(y, 100_000 + lat, 140_000 + lat))
        val voiceChange = db(rms(x, 150_000, 185_000) / rms(y, 150_000 + lat, 185_000 + lat))
        assertTrue(noiseDrop > 12f, "el ruido bajó $noiseDrop dB")
        assertTrue(kotlin.math.abs(voiceChange) < 1f, "la voz cambió $voiceChange dB")
    }

    @Test
    fun nlMeansBajaElRuidoSinCorrerElAudio() {
        val rnd = kotlin.random.Random(9)
        val n = 48_000
        val clean = sine(n, 300.0, 0.3f)
        val sigma = 0.01f
        val x = FloatArray(n) { clean[it] + (rnd.nextFloat() - 0.5f) * sigma * 3.46f }
        val nl = dev.liquidrecorder.core.dsp.NlMeans(1, 48_000, sigma)
        val y = stream(x, nl::process, nl::flush)
        assertEquals(x.size, y.size)
        val before = rms(FloatArray(n) { x[it] - clean[it] }, 1000, n - 1000)
        val after = rms(FloatArray(n) { y[it] - clean[it] }, 1000, n - 1000)
        assertTrue(db(before / after) > 3f, "mejoró ${db(before / after)} dB")
    }

    @Test
    fun volumenSegunEbuR128() {
        // Un seno de 997 Hz a -20 dBFS en un canal mide -23,0 LUFS (BS.1770).
        val m = dev.liquidrecorder.core.dsp.LoudnessMeter(48_000, 1)
        val x = sine(48_000 * 10, 997.0, 0.1f)
        m.push(x, x.size)
        assertEquals(-23.01, m.integrated(), 0.15)
        val m44 = dev.liquidrecorder.core.dsp.LoudnessMeter(44_100, 1)
        val y = sine(44_100 * 10, 997.0, 0.1f, 44_100)
        m44.push(y, y.size)
        assertEquals(-23.01, m44.integrated(), 0.15)
    }

    @Test
    fun limitadorNoPasaDelTecho() {
        val lim = dev.liquidrecorder.core.dsp.Limiter(1, 48_000, 0.84f)
        val x = FloatArray(48_000) { i -> if (i in 20_000..20_100) 2f else (kotlin.math.sin(2 * Math.PI * 200 * i / 48_000) * 0.3).toFloat() }
        val y = stream(x, lim::process, lim::flush)
        val out = y.copyOfRange(lim.latency, y.size)
        assertTrue(out.maxOf { kotlin.math.abs(it) } <= 0.8401f, "pico ${out.maxOf { kotlin.math.abs(it) }}")
        // Lejos del pico, intacto.
        assertEquals(x[5_000], out[5_000], 1e-5f)
    }

    @Test
    fun umbralDeRuido() {
        val e = FloatArray(200) { it.toFloat() + 1 }.also { it[0] = Float.NaN }
        // 7,5 % de 199 válidos = 14 → el 14.º más chico (15, porque el 1 es NaN).
        assertEquals(15f, dev.liquidrecorder.core.dsp.NoiseProfile.threshold(e, e.size))
    }
}

private fun Float.pow(x: Float) = Math.pow(this.toDouble(), x.toDouble()).toFloat()
