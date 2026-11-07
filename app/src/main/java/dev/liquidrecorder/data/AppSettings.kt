// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.data

import dev.liquidrecorder.core.audio.AudioFormat
import dev.liquidrecorder.core.audio.EncoderConfig
import dev.liquidrecorder.ui.glass.GlassStyle
import kotlinx.serialization.Serializable

/**
 * Ajustes guardados en files/settings.json. Los 8 primeros son los de personalización de la suite:
 * mismos nombres, valores y valores por defecto que Liquid Calendar y Liquid Contacts. Los de la
 * grabadora van después.
 */
@Serializable
data class AppSettings(
    /** 0 = según el sistema, 1 = claro, 2 = oscuro. */
    val themeMode: Int = 0,
    /** 0 = colores en movimiento (COLOR_SETS), 1 = color de énfasis, 2 = liso con acento azul. */
    val backgroundMode: Int = 2,
    /** Índice en COLOR_SETS. */
    val colorSet: Int = 0,
    /** Color de énfasis en ARGB, para backgroundMode = 1. */
    val accentColor: Long = 0xFF0EA5E9,
    val animatedBackground: Boolean = true,
    /** Estilo de interfaz: 0 = Liquid Glass, 1 = sólido. */
    val uiStyle: Int = 0,
    /** Color de énfasis del estilo sólido, en ARGB (aparte del de Liquid Glass). */
    val solidAccent: Long = 0xFF0EA5E9,
    /** Índice del icono de la app (ver AppIcons). */
    val appIcon: Int = 0,

    // --- Grabadora ---

    /** Ver AudioFormat.id: flac, wav, aac, opus. */
    val format: String = "flac",
    /** 44100 o 48000. Opus siempre usa 48000. */
    val sampleRate: Int = 48_000,
    /** 1 = mono, 2 = estéreo. */
    val channels: Int = 1,
    /** WAV y FLAC: 16 o 24. */
    val bitsPerSample: Int = 24,
    /** AAC, en bits por segundo. */
    val aacBitrate: Int = 256_000,
    /** Opus, en bits por segundo. */
    val opusBitrate: Int = 128_000,
    /** Fuente de Android: 0 estándar (MIC), 1 sin procesar, 2 voz (VOICE_RECOGNITION), 3 cámara. */
    val audioSource: Int = 0,
    /** Id del micrófono elegido (AudioDeviceInfo.id), 0 = el del sistema. */
    val inputDevice: Int = 0,
    /** Nombre del micrófono elegido, para mostrarlo si no está conectado. */
    val inputDeviceName: String = "",
    /** Limpieza de ruido (la última elegida): 1 suave, 2 media, 3 fuerte. */
    val cleanLevel: Int = 2,
    /** Pulido extra (medias no locales): mejor y bastante más lento. */
    val cleanExtra: Boolean = false,
    /** Llevar el volumen a -16 LUFS al limpiar. */
    val cleanNormalize: Boolean = true,
    /** Ganancia digital en dB, de -6 a +24. */
    val gainDb: Int = 0,
    val keepScreenOn: Boolean = false,
    /** 0 = onda y nivel, 1 = espectrograma. */
    val visualizer: Int = 0,
    /** Segundos que saltan los botones de retroceder y avanzar. */
    val skipSeconds: Int = 10,
    val skipSilence: Boolean = false,
    /** Velocidad de reproducción, x100. */
    val speed: Int = 100,
) {
    val cleanOptions: dev.liquidrecorder.core.dsp.CleanOptions
        get() = dev.liquidrecorder.core.dsp.CleanOptions(level = cleanLevel, extra = cleanExtra, normalize = cleanNormalize)

    val glassStyle: GlassStyle get() = GlassStyle(themeMode, backgroundMode, colorSet, accentColor, uiStyle, solidAccent)

    val encoder: EncoderConfig
        get() {
            val f = AudioFormat.of(format)
            return EncoderConfig(
                format = f,
                sampleRate = if (f == AudioFormat.OPUS) 48_000 else sampleRate,
                channels = channels,
                bitsPerSample = bitsPerSample,
                bitrate = when (f) {
                    AudioFormat.AAC -> aacBitrate
                    AudioFormat.OPUS -> opusBitrate
                    else -> 0
                },
            )
        }
}
