// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.Closeable
import java.io.File
import java.nio.ByteOrder

class NoAudioException : Exception("Sin pista de audio")

/**
 * Decodifica la pista de audio de cualquier archivo que entienda Android (FLAC, WAV, M4A, Ogg, MP3,
 * y también MP4, MKV o WebM de video, de los que solo se toma el audio) a muestras flotantes.
 */
class AudioDecoder private constructor(private val extractor: MediaExtractor) : Closeable {
    private val track: Int
    private val inputFormat: MediaFormat
    val mime: String
    var sampleRate: Int
        private set
    var channels: Int
        private set
    val durationUs: Long

    init {
        track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { extractor.release(); throw NoAudioException() }
        inputFormat = extractor.getTrackFormat(track)
        mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) inputFormat.getLong(MediaFormat.KEY_DURATION) else 0
        extractor.selectTrack(track)
    }

    /**
     * Entrega el audio entre [startUs] y [endUs] en bloques intercalados. [onChunk] devuelve false
     * para cortar antes (por ejemplo, si se canceló).
     */
    fun decode(startUs: Long = 0, endUs: Long = Long.MAX_VALUE, onChunk: (FloatArray, Int) -> Boolean) {
        val codec = MediaCodec.createDecoderByType(mime)
        val format = MediaFormat(inputFormat).apply { setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT) }
        codec.configure(format, null, null, 0)
        codec.start()
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var out = FloatArray(0)
        try {
            while (true) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        val size = extractor.readSampleData(buf, 0)
                        val time = extractor.sampleTime
                        // Margen de 200 ms tras el final pedido (sin desbordar si es "hasta el final").
                        if (size < 0 || (endUs != Long.MAX_VALUE && time > endUs + 200_000)) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(idx, 0, size, time, 0)
                            extractor.advance()
                        }
                    }
                }
                val idx = codec.dequeueOutputBuffer(info, 10_000)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                } else if (idx >= 0) {
                    val buf = codec.getOutputBuffer(idx)!!.order(ByteOrder.LITTLE_ENDIAN)
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val bytesPerSample = when (encoding) {
                        AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
                        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                        AudioFormat.ENCODING_PCM_8BIT -> 1
                        else -> 2
                    }
                    val samples = info.size / bytesPerSample
                    val frames = samples / channels
                    if (out.size < samples) out = FloatArray(samples)
                    for (i in 0 until samples) {
                        out[i] = when (encoding) {
                            AudioFormat.ENCODING_PCM_FLOAT -> buf.getFloat()
                            AudioFormat.ENCODING_PCM_32BIT -> buf.getInt() / 2147483648f
                            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                                val b0 = buf.get().toInt() and 0xFF
                                val b1 = buf.get().toInt() and 0xFF
                                val b2 = buf.get().toInt()
                                ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
                            }
                            AudioFormat.ENCODING_PCM_8BIT -> ((buf.get().toInt() and 0xFF) - 128) / 128f
                            else -> buf.getShort() / 32768f
                        }
                    }
                    codec.releaseOutputBuffer(idx, false)
                    // Recortar al rango pedido según el tiempo de cada cuadro.
                    val t0 = info.presentationTimeUs
                    val usPerFrame = 1_000_000.0 / sampleRate
                    var first = 0
                    if (t0 < startUs) first = ((startUs - t0) / usPerFrame).toInt().coerceIn(0, frames)
                    var last = frames
                    if (endUs != Long.MAX_VALUE) {
                        val endFrame = ((endUs - t0) / usPerFrame).toInt()
                        last = endFrame.coerceIn(first, frames)
                    }
                    if (last > first) {
                        val chunk = if (first == 0) out else out.copyOfRange(first * channels, last * channels)
                        if (!onChunk(chunk, last - first)) return
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    if (endUs != Long.MAX_VALUE && t0 + frames * usPerFrame >= endUs) return
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }
    }

    override fun close() = extractor.release()

    companion object {
        fun open(file: File): AudioDecoder = AudioDecoder(MediaExtractor().apply { setDataSource(file.absolutePath) })
        fun open(context: Context, uri: Uri): AudioDecoder = AudioDecoder(MediaExtractor().apply { setDataSource(context, uri, null) })
    }
}
