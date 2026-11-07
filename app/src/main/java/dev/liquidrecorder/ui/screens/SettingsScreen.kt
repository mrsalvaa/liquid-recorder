// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidrecorder.BuildConfig
import dev.liquidrecorder.R
import dev.liquidrecorder.audio.Recorder
import dev.liquidrecorder.core.audio.AudioFormat
import dev.liquidrecorder.core.text.TimeText
import dev.liquidrecorder.data.AppSettings
import dev.liquidrecorder.data.Library
import dev.liquidrecorder.data.Stores
import dev.liquidrecorder.ui.components.Dropdown
import dev.liquidrecorder.ui.components.SwitchRow
import dev.liquidrecorder.ui.glass.DialogSpec
import dev.liquidrecorder.ui.glass.GlassButton
import dev.liquidrecorder.ui.glass.GlassCard
import dev.liquidrecorder.ui.glass.LocalDialog
import dev.liquidrecorder.ui.glass.LocalGlass
import dev.liquidrecorder.ui.glass.SectionLabel
import dev.liquidrecorder.ui.glass.SettingRow
import dev.liquidrecorder.util.plural
import dev.liquidrecorder.util.str

@Composable
fun SettingsScreen(padding: PaddingValues, onBack: () -> Unit, onTrash: () -> Unit) {
    val p = LocalGlass.current
    val context = LocalContext.current
    val dialog = LocalDialog.current
    val s by Stores.settings.state.collectAsStateWithLifecycle()
    val rec by Recorder.state.collectAsStateWithLifecycle()
    val lib by Library.state.collectAsStateWithLifecycle()
    val edit: ((AppSettings) -> AppSettings) -> Unit = { Stores.settings.update(it) }
    val format = AudioFormat.of(s.format)

    @Composable
    fun Label(text: String) = Text(text, Modifier.padding(top = 10.dp), fontSize = 16.sp, fontWeight = FontWeight.Medium)

    @Composable
    fun Hint(text: String) = Text(text, Modifier.padding(top = 2.dp, bottom = 4.dp), fontSize = 13.sp, color = p.contentSecondary)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp)) {
        BackTitle(str(R.string.settings), onBack)

        SectionLabel(str(R.string.section_recording))
        GlassCard {
            if (rec.active) Hint(str(R.string.settings_next_recording))
            Dropdown(str(R.string.format), AudioFormat.entries, format, { formatName(it) }) { f -> edit { it.copy(format = f.id) } }
            Hint(str(formatHint(format)))

            when (format) {
                AudioFormat.FLAC, AudioFormat.WAV -> {
                    Dropdown(str(R.string.quality), listOf(16, 24), s.bitsPerSample, { "$it bits" }) { v -> edit { it.copy(bitsPerSample = v) } }
                    Hint(str(R.string.bits_hint))
                }
                AudioFormat.AAC -> Dropdown(str(R.string.quality), listOf(128_000, 192_000, 256_000, 320_000), s.aacBitrate, { "${it / 1000} kbps" }) { v -> edit { it.copy(aacBitrate = v) } }
                AudioFormat.OPUS -> Dropdown(str(R.string.quality), listOf(64_000, 96_000, 128_000, 192_000, 256_000), s.opusBitrate, { "${it / 1000} kbps" }) { v -> edit { it.copy(opusBitrate = v) } }
            }

            if (format != AudioFormat.OPUS) {
                Dropdown(str(R.string.sample_rate), listOf(44_100, 48_000), s.sampleRate, { if (it == 44_100) "44,1 kHz" else "48 kHz" }) { v -> edit { it.copy(sampleRate = v) } }
            }

            Label(str(R.string.channels))
            ChipRow(listOf(1, 2), s.channels, { str(if (it == 2) R.string.stereo else R.string.mono) }) { v -> edit { it.copy(channels = v) } }
            Hint(str(R.string.channels_hint))
            Hint(str(R.string.size_estimate, TimeText.size(s.encoder.bytesPerSecond * 60)))
        }

        SectionLabel(str(R.string.section_microphone))
        GlassCard {
            val inputs = remember { Recorder.inputs(context) }
            val chosen = inputs.firstOrNull { it.id == s.inputDevice }
            SettingRow(
                str(R.string.microphone),
                subtitle = chosen?.let { Recorder.deviceLabel(context, it) }
                    ?: if (s.inputDevice != 0 && s.inputDeviceName.isNotEmpty()) str(R.string.mic_disconnected, s.inputDeviceName) else str(R.string.mic_auto),
                onClick = {
                    dialog(DialogSpec(str(R.string.microphone)) { dismiss ->
                        SettingRow(str(R.string.mic_auto), subtitle = str(R.string.mic_auto_hint), onClick = {
                            edit { it.copy(inputDevice = 0, inputDeviceName = "") }; dismiss()
                        })
                        Recorder.inputs(context).forEach { d ->
                            val label = Recorder.deviceLabel(context, d)
                            SettingRow(label, onClick = { edit { it.copy(inputDevice = d.id, inputDeviceName = label) }; dismiss() })
                        }
                        Text(str(R.string.mic_bluetooth_hint), Modifier.padding(top = 8.dp), fontSize = 13.sp, color = p.contentSecondary)
                        Spacer(Modifier.height(12.dp))
                        GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
                    })
                },
            )
            Label(str(R.string.audio_source))
            val unprocessed = remember { Recorder.unprocessedSupported(context) }
            val sources = if (unprocessed) listOf(0, 1, 2, 3) else listOf(0, 2, 3)
            ChipRow(sources, s.audioSource, { str(listOf(R.string.source_standard, R.string.source_raw, R.string.source_voice, R.string.source_camera)[it]) }) { v ->
                edit { it.copy(audioSource = v) }
            }
            Hint(str(listOf(R.string.source_standard_hint, R.string.source_raw_hint, R.string.source_voice_hint, R.string.source_camera_hint)[s.audioSource]))

            Dropdown(str(R.string.gain), listOf(-6, -3, 0, 3, 6, 9, 12, 18, 24), s.gainDb, { if (it > 0) "+$it dB" else "$it dB" }) { v -> edit { it.copy(gainDb = v) } }
            Hint(str(R.string.gain_hint))
        }

        SectionLabel(str(R.string.section_while_recording))
        GlassCard {
            SwitchRow(str(R.string.keep_screen_on), s.keepScreenOn, { v -> edit { it.copy(keepScreenOn = v) } }, subtitle = str(R.string.keep_screen_on_hint))
            Hint(str(R.string.quick_access_hint))
        }

        SectionLabel(str(R.string.section_playback))
        GlassCard {
            Dropdown(str(R.string.skip_amount), listOf(5, 10, 15, 30), s.skipSeconds, { "$it s" }) { v -> edit { it.copy(skipSeconds = v) } }
        }

        SectionLabel(str(R.string.appearance))
        AppearanceCard(s, edit)

        SectionLabel(str(R.string.section_storage))
        GlassCard {
            val used = remember(lib) { Library.usedBytes() }
            SettingRow(
                str(R.string.storage_used),
                subtitle = str(R.string.storage_used_value, plural(R.plurals.recordings_count, lib.active.size), TimeText.size(used), TimeText.size(Recorder.freeBytes())),
            )
            SettingRow(
                str(R.string.trash),
                subtitle = if (lib.trashed.isEmpty()) str(R.string.trash_empty_short) else plural(R.plurals.recordings_count, lib.trashed.size),
                onClick = onTrash,
            )
            Hint(str(R.string.privacy_note))
        }

        SectionLabel(str(R.string.section_about))
        GlassCard {
            SettingRow(str(R.string.app_name), subtitle = str(R.string.about_version, BuildConfig.VERSION_NAME))
            Hint(str(R.string.about_license))
        }
        Spacer(Modifier.height(24.dp))
    }
}

fun noiseLevelName(level: Int): String = str(
    when (level) {
        1 -> R.string.noise_soft
        2 -> R.string.noise_medium
        3 -> R.string.noise_strong
        else -> R.string.noise_off
    },
)
