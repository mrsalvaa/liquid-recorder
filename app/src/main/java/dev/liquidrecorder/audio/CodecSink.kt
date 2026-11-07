// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import dev.liquidrecorder.core.audio.AudioFormat
import dev.liquidrecorder.core.audio.EncoderConfig
import dev.liquidrecorder.core.audio.FlacWriter
import dev.liquidrecorder.core.audio.PcmSink
import dev.liquidrecorder.core.audio.WavWriter
import java.io.File

/** El destino que corresponde a cada formato. */
fun openSink(file: File, cfg: EncoderConfig): PcmSink = when (cfg.format) {
    AudioFormat.WAV -> WavWriter(file, cfg.sampleRate, cfg.channels, cfg.bitsPerSample)
    AudioFormat.FLAC -> FlacWriter(file, cfg.sampleRate, cfg.channels, cfg.bitsPerSample)
    AudioFormat.AAC, AudioFormat.OPUS -> CodecSink(file, cfg)
}

/**
 * AAC (en .m4a) u Opus (en .ogg) con el codificador del sistema (los "c2.android" de AOSP, sin
 * nada de Google Play). Recibe PCM de 16 bits.
 */
class CodecSink(file: File, private val cfg: EncoderConfig) : PcmSink {
    private val mime = if (cfg.format == AudioFormat.AAC) MediaFormat.MIMETYPE_AUDIO_AAC else MediaFormat.MIMETYPE_AUDIO_OPUS
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private var track = -1
    private var started = false
    private var framesIn = 0L
    private val info = MediaCodec.BufferInfo()
    private val frameBytes = cfg.channels * 2
    private var bytes = ByteArray(0)

    init {
        val format = MediaFormat.createAudioFormat(mime, cfg.sampleRate, cfg.channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, cfg.bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            if (cfg.format == AudioFormat.AAC) setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        codec = MediaCodec.createEncoderByType(mime)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        muxer = MediaMuxer(
            file.absolutePath,
            if (cfg.format == AudioFormat.AAC) MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 else MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG,
        )
    }

    override fun write(samples: IntArray, frames: Int) {
        val n = frames * cfg.channels
        if (bytes.size < n * 2) bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = samples[i]
            bytes[2 * i] = v.toByte()
            bytes[2 * i + 1] = (v shr 8).toByte()
        }
        var offset = 0
        val total = n * 2
        while (offset < total) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf = codec.getInputBuffer(idx)!!
                buf.clear()
                val take = minOf(total - offset, buf.remaining() / frameBytes * frameBytes)
                buf.put(bytes, offset, take)
                codec.queueInputBuffer(idx, 0, take, framesIn * 1_000_000L / cfg.sampleRate, 0)
                framesIn += take / frameBytes
                offset += take
            }
            drain(false)
        }
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && started) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
            }
        }
    }

    override fun finish() {
        try {
            var idx: Int
            do { idx = codec.dequeueInputBuffer(10_000); if (idx < 0) drain(false) } while (idx < 0)
            codec.queueInputBuffer(idx, 0, 0, framesIn * 1_000_000L / cfg.sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
        } finally {
            codec.stop()
            codec.release()
            if (started) muxer.stop()
            muxer.release()
        }
    }
}
