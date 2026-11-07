// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.core.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * WAV PCM de 16 o 24 bits. El encabezado se reescribe en cada [checkpoint] con el tamaño real: si
 * el proceso muere a mitad de grabación, el archivo sigue siendo válido hasta el último punto.
 */
class WavWriter(file: File, private val sampleRate: Int, private val channels: Int, private val bits: Int) : PcmSink {
    private val raf = RandomAccessFile(file, "rw")
    private val bytesPerSample = bits / 8
    private var buffer = ByteArray(0)
    private var dataBytes = 0L

    init {
        require(bits == 16 || bits == 24) { "WAV: 16 o 24 bits" }
        raf.setLength(0)
        raf.write(header(0))
    }

    override fun write(samples: IntArray, frames: Int) {
        val n = frames * channels
        val size = n * bytesPerSample
        if (buffer.size < size) buffer = ByteArray(size)
        var o = 0
        for (i in 0 until n) {
            val v = samples[i]
            buffer[o++] = v.toByte()
            buffer[o++] = (v shr 8).toByte()
            if (bytesPerSample == 3) buffer[o++] = (v shr 16).toByte()
        }
        raf.write(buffer, 0, size)
        dataBytes += size
    }

    override fun checkpoint() {
        val pos = raf.filePointer
        raf.seek(0)
        raf.write(header(dataBytes))
        raf.seek(pos)
        raf.fd.sync()
    }

    override fun finish() {
        raf.seek(0)
        raf.write(header(dataBytes))
        raf.close()
    }

    private fun header(data: Long): ByteArray = wavHeader(sampleRate, channels, bits, data)

    companion object {
        const val HEADER_SIZE = 44

        fun wavHeader(sampleRate: Int, channels: Int, bits: Int, dataBytes: Long): ByteArray {
            val h = ByteArray(HEADER_SIZE)
            fun str(at: Int, s: String) = s.forEachIndexed { i, c -> h[at + i] = c.code.toByte() }
            fun le32(at: Int, v: Long) { for (i in 0..3) h[at + i] = (v shr (8 * i)).toByte() }
            fun le16(at: Int, v: Int) { h[at] = v.toByte(); h[at + 1] = (v shr 8).toByte() }
            val data = dataBytes.coerceAtMost(0xFFFFFFFFL - 36)
            str(0, "RIFF"); le32(4, 36 + data); str(8, "WAVE")
            str(12, "fmt "); le32(16, 16); le16(20, 1); le16(22, channels)
            le32(24, sampleRate.toLong()); le32(28, sampleRate.toLong() * channels * bits / 8)
            le16(32, channels * bits / 8); le16(34, bits)
            str(36, "data"); le32(40, data)
            return h
        }

        /** Arregla el encabezado de un WAV que quedó a medias (por ejemplo, si se cortó la luz). */
        fun repair(file: File, sampleRate: Int, channels: Int, bits: Int) {
            RandomAccessFile(file, "rw").use { raf ->
                val frame = channels * bits / 8
                val data = ((raf.length() - HEADER_SIZE).coerceAtLeast(0) / frame) * frame
                raf.seek(0)
                raf.write(wavHeader(sampleRate, channels, bits, data))
                raf.setLength(HEADER_SIZE + data)
            }
        }
    }
}
