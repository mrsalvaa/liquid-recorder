// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.core.audio

/** Formatos en que se puede grabar o exportar. */
enum class AudioFormat(val id: String, val extension: String, val mime: String, val lossless: Boolean) {
    FLAC("flac", "flac", "audio/flac", true),
    WAV("wav", "wav", "audio/wav", true),
    AAC("aac", "m4a", "audio/mp4", false),
    OPUS("opus", "ogg", "audio/ogg", false);

    companion object {
        fun of(id: String): AudioFormat = entries.firstOrNull { it.id == id } ?: FLAC
    }
}

/** Parámetros de una grabación o de una exportación. */
data class EncoderConfig(
    val format: AudioFormat,
    val sampleRate: Int = 48_000,
    val channels: Int = 1,
    /** Bits por muestra para WAV y FLAC (16 o 24). */
    val bitsPerSample: Int = 24,
    /** Bits por segundo para AAC y Opus. */
    val bitrate: Int = 256_000,
) {
    /** Los formatos con pérdida se alimentan siempre con 16 bits. */
    val pcmBits: Int get() = if (format.lossless) bitsPerSample else 16

    /** Bytes por segundo aproximados, para estimar el espacio. */
    val bytesPerSecond: Long
        get() = when (format) {
            AudioFormat.WAV -> sampleRate.toLong() * channels * (bitsPerSample / 8)
            // FLAC: en voz y ambiente suele quedar entre 50 y 60 % del WAV.
            AudioFormat.FLAC -> sampleRate.toLong() * channels * (bitsPerSample / 8) * 55 / 100
            AudioFormat.AAC, AudioFormat.OPUS -> bitrate / 8L
        }
}

/** Destino de muestras PCM enteras intercaladas (L R L R ...), con [EncoderConfig.pcmBits] bits. */
interface PcmSink {
    /** [frames] cuadros de [samples] (cada cuadro tiene un valor por canal). */
    fun write(samples: IntArray, frames: Int)

    /** Cierra el archivo dejándolo válido. */
    fun finish()

    /** Escribe lo pendiente para que el archivo sea legible aunque el proceso muera. */
    fun checkpoint() {}
}
