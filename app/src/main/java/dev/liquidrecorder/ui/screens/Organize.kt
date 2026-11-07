// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidrecorder.R
import dev.liquidrecorder.core.audio.AudioFormat
import dev.liquidrecorder.core.text.Names
import dev.liquidrecorder.data.Actions
import dev.liquidrecorder.data.Library
import dev.liquidrecorder.data.Recording
import dev.liquidrecorder.ui.components.DangerButton
import dev.liquidrecorder.ui.components.RenameField
import dev.liquidrecorder.ui.components.RenameWindow
import dev.liquidrecorder.ui.glass.DialogSpec
import dev.liquidrecorder.ui.glass.GlassButton
import dev.liquidrecorder.ui.glass.GlassChip
import dev.liquidrecorder.ui.glass.LocalActionSheet
import dev.liquidrecorder.ui.glass.LocalDialog
import dev.liquidrecorder.ui.glass.LocalGlass
import dev.liquidrecorder.ui.glass.SettingRow
import dev.liquidrecorder.ui.glass.SheetAction
import dev.liquidrecorder.ui.glass.SheetSpec
import dev.liquidrecorder.util.str

/** Las acciones sobre una grabación (menú, mover, etiquetas, exportar), listas para usar. */
class RecordingMenu(
    val open: (Recording) -> Unit,
    val edit: (Recording) -> Unit,
    val rename: (Recording) -> Unit,
    val move: (Recording) -> Unit,
    val tags: (Recording) -> Unit,
    val export: (Recording) -> Unit,
    val delete: (Recording) -> Unit,
    val sheet: (Recording) -> Unit,
)

@Composable
fun rememberRecordingMenu(onOpen: (Recording) -> Unit, onEdit: (Recording) -> Unit, afterDelete: () -> Unit = {}): RecordingMenu {
    val context = LocalContext.current
    val dialog = LocalDialog.current
    val sheet = LocalActionSheet.current
    val toast = LocalToast.current

    // Un selector de archivos por formato (el tipo se fija al registrarlo).
    var pendingExport by remember { mutableStateOf<Pair<Recording, AudioFormat>?>(null) }
    val launchers = AudioFormat.entries.associateWith { f ->
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(f.mime)) { uri ->
            val (r, format) = pendingExport ?: return@rememberLauncherForActivityResult
            pendingExport = null
            if (uri != null) Actions.exportTo(context, r, format, uri)
        }
    }

    val rename: (Recording) -> Unit = { r ->
        dialog(DialogSpec(str(R.string.rename)) { dismiss -> RenameWindow(r.title, str(R.string.name), dismiss) { Library.rename(r.id, it) } })
    }
    val move: (Recording) -> Unit = { r -> dialog(DialogSpec(str(R.string.move_to_folder)) { dismiss -> FolderPicker(r.folder, dismiss) { Library.move(listOf(r.id), it) } }) }
    val tags: (Recording) -> Unit = { r -> dialog(DialogSpec(str(R.string.tags)) { dismiss -> TagEditor(r.id, dismiss) }) }
    val export: (Recording) -> Unit = { r ->
        dialog(DialogSpec(str(R.string.export_as)) { dismiss ->
            Text(str(R.string.export_hint), Modifier.padding(bottom = 8.dp), fontSize = 14.sp, color = LocalGlass.current.contentSecondary)
            AudioFormat.entries.forEach { f ->
                SettingRow(
                    formatName(f),
                    subtitle = if (f == r.audioFormat) str(R.string.export_same) else str(formatHint(f)),
                    onClick = {
                        dismiss()
                        pendingExport = r to f
                        launchers.getValue(f).launch(Names.fileName(r.title, f.extension))
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
        })
    }
    val delete: (Recording) -> Unit = { r ->
        Library.trash(listOf(r.id))
        dev.liquidrecorder.audio.Playback.closeIf(r.id)
        toast(str(R.string.moved_to_trash))
        afterDelete()
    }
    val open = sheetOpen@{ r: Recording ->
        sheet(
            SheetSpec(
                r.title,
                buildList {
                    add(SheetAction(str(R.string.play), Icons.Rounded.PlayArrow) { onOpen(r) })
                    add(SheetAction(str(R.string.rename), Icons.Rounded.Edit) { rename(r) })
                    add(SheetAction(str(if (r.starred) R.string.unstar else R.string.star), if (r.starred) Icons.Rounded.StarOutline else Icons.Rounded.Star) { Library.toggleStar(r.id) })
                    add(SheetAction(str(R.string.move_to_folder), Icons.Rounded.Folder) { move(r) })
                    add(SheetAction(str(R.string.tags), Icons.AutoMirrored.Rounded.Label) { tags(r) })
                    add(SheetAction(str(R.string.edit_audio), Icons.Rounded.ContentCut) { onEdit(r) })
                    add(SheetAction(str(R.string.clean_title), Icons.Rounded.AutoFixHigh) {
                        dialog(DialogSpec(str(R.string.clean_title)) { d -> CleanWindow(listOf(r), d) })
                    })
                    add(SheetAction(str(R.string.share), Icons.Rounded.Share) { Actions.share(context, listOf(r)) })
                    add(SheetAction(str(R.string.export_as), Icons.Rounded.FileDownload) { export(r) })
                    if (r.markers.isNotEmpty()) add(SheetAction(str(R.string.share_markers), Icons.Rounded.Bookmarks) { Actions.shareMarkers(context, r) })
                    add(SheetAction(str(R.string.delete), Icons.Rounded.DeleteOutline, danger = true) { delete(r) })
                },
            ),
        )
    }
    return RecordingMenu(onOpen, onEdit, rename, move, tags, export, delete, open)
}

fun formatName(f: AudioFormat): String = when (f) {
    AudioFormat.FLAC -> "FLAC"
    AudioFormat.WAV -> "WAV"
    AudioFormat.AAC -> "AAC (M4A)"
    AudioFormat.OPUS -> "Opus (OGG)"
}

fun formatHint(f: AudioFormat): Int = when (f) {
    AudioFormat.FLAC -> R.string.format_flac_hint
    AudioFormat.WAV -> R.string.format_wav_hint
    AudioFormat.AAC -> R.string.format_aac_hint
    AudioFormat.OPUS -> R.string.format_opus_hint
}

/** Elegir carpeta (o ninguna, o una nueva). */
@Composable
fun FolderPicker(current: Long, dismiss: () -> Unit, onPick: (Long) -> Unit) {
    val lib by Library.state.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    if (creating) {
        RenameField("", str(R.string.folder_name), { name = it }, onDone = {
            if (name.isNotBlank()) { onPick(Library.addFolder(name)); dismiss() }
        })
        Spacer(Modifier.height(16.dp))
        GlassButton(str(R.string.create), {
            if (name.isNotBlank()) { onPick(Library.addFolder(name)); dismiss() }
        }, Modifier.fillMaxWidth(), prominent = true)
        Spacer(Modifier.height(8.dp))
        GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
        return
    }
    SettingRow(str(R.string.no_folder), subtitle = if (current == 0L) str(R.string.current) else null, onClick = { onPick(0); dismiss() })
    lib.folders.forEach { f ->
        SettingRow(f.name, subtitle = if (current == f.id) str(R.string.current) else null, onClick = { onPick(f.id); dismiss() })
    }
    Spacer(Modifier.height(12.dp))
    GlassButton(str(R.string.new_folder), { creating = true }, Modifier.fillMaxWidth(), prominent = true)
    Spacer(Modifier.height(8.dp))
    GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
}

/** Etiquetas de una grabación: tocar una la pone o la saca; abajo se crea una nueva. */
@Composable
fun TagEditor(id: Long, dismiss: () -> Unit) {
    val lib by Library.state.collectAsStateWithLifecycle()
    val r = lib.recordings.firstOrNull { it.id == id } ?: return
    var name by remember { mutableStateOf("") }
    var fieldKey by remember { mutableStateOf(0) }
    if (lib.tags.isEmpty()) {
        Text(str(R.string.tags_empty), Modifier.padding(bottom = 8.dp), fontSize = 14.sp, color = LocalGlass.current.contentSecondary)
    }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        lib.tags.forEach { t ->
            val on = t in r.tags
            GlassChip(t, on, { Library.setTags(id, if (on) r.tags - t else r.tags + t) })
        }
    }
    Spacer(Modifier.height(14.dp))
    val add = {
        val t = name.trim()
        if (t.isNotEmpty()) {
            Library.addTag(t)
            val real = Library.store.value.tags.first { it.equals(t, ignoreCase = true) }
            Library.setTags(id, r.tags + real)
            name = ""
            fieldKey++
        }
    }
    androidx.compose.runtime.key(fieldKey) {
        RenameField("", str(R.string.new_tag), { name = it }, autoFocus = false, onDone = add)
    }
    Spacer(Modifier.height(8.dp))
    GlassButton(str(R.string.add_tag), add, Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    GlassButton(str(R.string.done), dismiss, Modifier.fillMaxWidth(), prominent = true)
}

/** Etiquetas de varias grabaciones: marcada si la tienen todas; tocar la pone o la saca de todas. */
@Composable
fun BulkTagEditor(ids: Set<Long>, dismiss: () -> Unit) {
    val lib by Library.state.collectAsStateWithLifecycle()
    val recs = lib.recordings.filter { it.id in ids }
    var name by remember { mutableStateOf("") }
    var fieldKey by remember { mutableStateOf(0) }
    if (lib.tags.isEmpty()) {
        Text(str(R.string.tags_empty), Modifier.padding(bottom = 8.dp), fontSize = 14.sp, color = LocalGlass.current.contentSecondary)
    }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        lib.tags.forEach { t ->
            val all = recs.isNotEmpty() && recs.all { t in it.tags }
            GlassChip(t, all, { Library.setTagFor(ids, t, !all) })
        }
    }
    Spacer(Modifier.height(14.dp))
    val add = {
        val t = name.trim()
        if (t.isNotEmpty()) {
            Library.addTag(t)
            Library.setTagFor(ids, Library.store.value.tags.first { it.equals(t, ignoreCase = true) }, true)
            name = ""
            fieldKey++
        }
    }
    androidx.compose.runtime.key(fieldKey) {
        RenameField("", str(R.string.new_tag), { name = it }, autoFocus = false, onDone = add)
    }
    Spacer(Modifier.height(8.dp))
    GlassButton(str(R.string.add_tag), add, Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    GlassButton(str(R.string.done), dismiss, Modifier.fillMaxWidth(), prominent = true)
}

/**
 * Limpiar el ruido de una o varias grabaciones: nivel, pulido extra y volumen. Recuerda lo elegido.
 * El original no se toca: se crea una copia "(limpia)".
 */
@Composable
fun CleanWindow(recordings: List<Recording>, dismiss: () -> Unit, onDone: (Long?) -> Unit = {}) {
    val context = LocalContext.current
    val p = LocalGlass.current
    val s by dev.liquidrecorder.data.Stores.settings.state.collectAsStateWithLifecycle()
    val edit: ((dev.liquidrecorder.data.AppSettings) -> dev.liquidrecorder.data.AppSettings) -> Unit = { dev.liquidrecorder.data.Stores.settings.update(it) }
    Text(str(R.string.clean_hint), Modifier.padding(bottom = 6.dp), fontSize = 13.sp, color = p.contentSecondary)
    dev.liquidrecorder.ui.components.Dropdown(str(R.string.clean_level), listOf(1, 2, 3), s.cleanLevel, { noiseLevelName(it) }) { v -> edit { it.copy(cleanLevel = v) } }
    Text(str(listOf(R.string.noise_soft_hint, R.string.noise_medium_hint, R.string.noise_strong_hint)[s.cleanLevel.coerceIn(1, 3) - 1]), fontSize = 12.sp, color = p.contentSecondary)
    dev.liquidrecorder.ui.components.SwitchRow(str(R.string.clean_extra), s.cleanExtra, { v -> edit { it.copy(cleanExtra = v) } }, subtitle = str(R.string.clean_extra_hint))
    dev.liquidrecorder.ui.components.SwitchRow(str(R.string.clean_normalize), s.cleanNormalize, { v -> edit { it.copy(cleanNormalize = v) } }, subtitle = str(R.string.clean_normalize_hint))
    Spacer(Modifier.height(14.dp))
    GlassButton(str(R.string.clean_action), {
        dismiss()
        dev.liquidrecorder.data.Actions.clean(context, recordings, dev.liquidrecorder.data.Stores.settings.value.cleanOptions, onDone)
    }, Modifier.fillMaxWidth(), prominent = true)
    Spacer(Modifier.height(8.dp))
    GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
}

/** Confirmación para acciones que no se pueden deshacer. */
@Composable
fun ConfirmWindow(text: String, confirm: String, dismiss: () -> Unit, onConfirm: () -> Unit) {
    Text(text, fontSize = 15.sp, color = LocalGlass.current.contentSecondary)
    Spacer(Modifier.height(16.dp))
    DangerButton(confirm, { dismiss(); onConfirm() }, Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
    Box(Modifier.height(0.dp))
}
