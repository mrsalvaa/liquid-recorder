// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.core

import dev.liquidrecorder.core.audio.FlacWriter
import dev.liquidrecorder.core.audio.PcmConverter
import dev.liquidrecorder.core.audio.WavWriter
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioWritersTest {
    private val dir = File(System.getProperty("liquid.tmp") ?: System.getProperty("java.io.tmpdir")).apply { mkdirs() }

    private fun tool(name: String): Boolean = listOf("/usr/bin/$name", "/usr/local/bin/$name").any { File(it).canExecute() }

    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    /** Señal de prueba: tono + ruido + silencio + un tramo constante + saturación. */
    private fun signal(frames: Int, channels: Int, bits: Int, seed: Int = 3): IntArray {
        val rnd = Random(seed)
        val max = (1 shl (bits - 1)) - 1
        val min = -(1 shl (bits - 1))
        return IntArray(frames * channels) { i ->
            val f = i / channels
            val c = i % channels
            val t = f / 48_000.0
            when {
                f < frames / 5 -> (sin(2 * PI * (440 + c * 110) * t) * max * 0.5).toInt() + rnd.nextInt(-200, 200)
                f < 2 * frames / 5 -> 0
                f < 3 * frames / 5 -> rnd.nextInt(min, max)
                f < 4 * frames / 5 -> 1234 * (c + 1)
                else -> if ((f / 50) % 2 == 0) max else min
            }
        }
    }

    private fun writeFlac(name: String, channels: Int, bits: Int, frames: Int, chunk: Int = 960): Pair<File, IntArray> {
        val f = File(dir, name)
        val pcm = signal(frames, channels, bits)
        val w = FlacWriter(f, 48_000, channels, bits)
        var pos = 0
        while (pos < frames) {
            val n = minOf(chunk, frames - pos)
            w.write(pcm.copyOfRange(pos * channels, (pos + n) * channels), n)
            pos += n
        }
        w.finish()
        return f to pcm
    }

    @Test
    fun flacMono16PassesFlacTest() = flacCase("m16.flac", 1, 16, 48_000 * 3 + 123)

    @Test
    fun flacStereo24PassesFlacTest() = flacCase("s24.flac", 2, 24, 48_000 * 2 + 4095)

    @Test
    fun flacStereo16Short() = flacCase("short.flac", 2, 16, 10)

    private fun flacCase(name: String, channels: Int, bits: Int, frames: Int) {
        val (file, pcm) = writeFlac(name, channels, bits, frames)
        assertTrue(file.length() > FlacWriter.HEADER_SIZE)
        if (!tool("flac")) return
        val (code, out) = run("flac", "-t", "-s", file.absolutePath)
        assertEquals(0, code, out)
        // Decodificado tiene que ser idéntico bit a bit.
        val raw = File(dir, "$name.raw")
        val (c2, o2) = run("flac", "-d", "-f", "-s", "--force-raw-format", "--endian=little", "--sign=signed", "-o", raw.absolutePath, file.absolutePath)
        assertEquals(0, c2, o2)
        val bytes = raw.readBytes()
        val bps = bits / 8
        assertEquals(pcm.size * bps, bytes.size)
        for (i in pcm.indices) {
            var v = 0
            for (b in 0 until bps) v = v or ((bytes[i * bps + b].toInt() and 0xFF) shl (8 * b))
            v = (v shl (32 - bits)) shr (32 - bits)
            assertEquals(pcm[i], v, "muestra $i")
        }
    }

    @Test
    fun flacSurvivesCrashAfterCheckpoint() {
        val f = File(dir, "crash.flac")
        val pcm = signal(48_000 * 2, 1, 24)
        val w = FlacWriter(f, 48_000, 1, 24)
        w.write(pcm, 48_000 * 2)
        w.checkpoint()
        val safe = w.safeLength
        val samples = w.writtenSamples
        // Se corta a mitad de un cuadro: bytes de más al final, sin finish().
        java.io.RandomAccessFile(f, "rw").use { it.seek(it.length()); it.write(ByteArray(300) { 7 }) }
        FlacWriter.repair(f, safe)
        assertEquals(safe, f.length())
        if (!tool("flac")) return
        val (code, out) = run("flac", "-t", "-s", f.absolutePath)
        assertEquals(0, code, out)
        val (c2, info) = run("metaflac", "--show-total-samples", f.absolutePath)
        assertEquals(0, c2, info)
        assertEquals(samples, info.trim().toLong())
    }

    @Test
    fun flacCompressesSilence() {
        val f = File(dir, "silence.flac")
        val w = FlacWriter(f, 48_000, 2, 24)
        w.write(IntArray(48_000 * 2 * 10), 48_000 * 10)
        w.finish()
        assertTrue(f.length() < 10_000, "10 s de silencio ocupan ${f.length()} bytes")
    }

    @Test
    fun wavHeaderAndRepair() {
        val f = File(dir, "t.wav")
        val w = WavWriter(f, 48_000, 2, 24)
        w.write(signal(48_000, 2, 24), 48_000)
        w.checkpoint()
        w.finish()
        assertEquals(44L + 48_000 * 2 * 3, f.length())
        if (tool("ffprobe")) {
            val (code, out) = run("ffprobe", "-v", "error", "-show_entries", "stream=sample_rate,channels,duration", "-of", "csv=p=0", f.absolutePath)
            assertEquals(0, code, out)
            assertTrue(out.startsWith("48000,2,1.0"), out)
        }
        // Encabezado roto (como si se hubiera cortado la grabación) y un cuadro incompleto al final.
        f.appendBytes(byteArrayOf(1, 2))
        java.io.RandomAccessFile(f, "rw").use { it.seek(4); it.write(ByteArray(4)); it.seek(40); it.write(ByteArray(4)) }
        WavWriter.repair(f, 48_000, 2, 24)
        assertEquals(44L + 48_000 * 2 * 3, f.length())
        val h = f.readBytes().copyOfRange(40, 44)
        val data = (h[0].toInt() and 0xFF) or ((h[1].toInt() and 0xFF) shl 8) or ((h[2].toInt() and 0xFF) shl 16) or ((h[3].toInt() and 0xFF) shl 24)
        assertEquals(48_000 * 2 * 3, data)
    }

    @Test
    fun converterClipsAndCounts() {
        val c = PcmConverter(24)
        val out = IntArray(4)
        c.convert(floatArrayOf(0f, 0.5f, 1.5f, -2f), 4, 1f, out)
        assertEquals(0, out[0])
        assertEquals(1 shl 22, out[1])
        assertEquals((1 shl 23) - 1, out[2])
        assertEquals(-(1 shl 23), out[3])
        assertEquals(2, c.takeClipped())
        assertEquals(0, c.takeClipped())
    }
}
