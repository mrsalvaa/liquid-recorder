// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.ui.screens

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.role
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.background
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidrecorder.R
import dev.liquidrecorder.core.text.Names
import dev.liquidrecorder.core.text.TimeText
import dev.liquidrecorder.data.Library
import dev.liquidrecorder.data.Recording
import dev.liquidrecorder.ui.glass.DialogSpec
import dev.liquidrecorder.ui.glass.GlassChip
import dev.liquidrecorder.ui.glass.GlassIconButton
import dev.liquidrecorder.ui.glass.GlassTextField
import dev.liquidrecorder.ui.glass.LocalActionSheet
import dev.liquidrecorder.ui.glass.LocalDialog
import dev.liquidrecorder.ui.glass.LocalGlass
import dev.liquidrecorder.ui.glass.ScreenTitle
import dev.liquidrecorder.ui.glass.SheetAction
import dev.liquidrecorder.ui.glass.SheetSpec
import dev.liquidrecorder.ui.glass.styled
import dev.liquidrecorder.ui.components.RenameWindow
import dev.liquidrecorder.util.plural
import dev.liquidrecorder.util.str

/** Filtro de carpeta: -1 todas, -2 favoritas, 0 sin carpeta, >0 una carpeta. */
private const val ALL = -1L
private const val STARRED = -2L

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    padding: PaddingValues,
    onOpen: (Recording) -> Unit,
    onOpenFile: (android.net.Uri) -> Unit,
    onEdit: (Recording) -> Unit,
    onSettings: () -> Unit,
) {
    val p = LocalGlass.current
    val dialog = LocalDialog.current
    val sheet = LocalActionSheet.current
    val focus = LocalFocusManager.current
    val lib by Library.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var folder by rememberSaveable { mutableStateOf(ALL) }
    var tagFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val menu = rememberRecordingMenu(onOpen = { focus.clearFocus(); onOpen(it) }, onEdit = { focus.clearFocus(); onEdit(it) })
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    val selecting = selected.isNotEmpty()

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onOpenFile(uri)
    }

    val folderNames = lib.folders.associate { it.id to it.name }
    val shown = remember(lib, query, folder, tagFilter) {
        lib.active
            .filter {
                when (folder) {
                    ALL -> true
                    STARRED -> it.starred
                    else -> it.folder == folder
                }
            }
            .filter { tagFilter == null || tagFilter in it.tags }
            .filter { r -> Names.matches(query, listOf(r.title, folderNames[r.folder] ?: "") + r.tags + r.markers.map { it.name }) }
            .sortedByDescending { it.createdAt }
    }

    // Si una seleccionada desaparece (se borró), sale de la selección.
    androidx.compose.runtime.LaunchedEffect(lib) {
        val alive = lib.active.mapTo(HashSet()) { it.id }
        if (selected.any { it !in alive }) selected = selected.filterTo(HashSet()) { it in alive }
    }
    LibrarySelection(selected, shown.map { it.id }, onChange = { selected = it })

    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                ScreenTitle(
                    str(R.string.tab_library),
                    subtitle = plural(R.plurals.recordings_count, lib.active.size),
                    actions = {
                        GlassIconButton(Icons.Rounded.FolderOpen, str(R.string.open_file), { pickFile.launch(arrayOf("audio/*", "video/*")) })
                        GlassIconButton(Icons.Rounded.Settings, str(R.string.settings), onSettings)
                    },
                )
                GlassTextField(query, { query = it }, str(R.string.search_hint), Modifier.padding(top = 4.dp))
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NewFolderChip {
                    dialog(DialogSpec(str(R.string.new_folder)) { d -> RenameWindow("", str(R.string.folder_name), d) { if (it.isNotBlank()) folder = Library.addFolder(it) } })
                }
                GlassChip(str(R.string.all), folder == ALL, { folder = ALL })
                GlassChip(str(R.string.starred), folder == STARRED, { folder = STARRED })
                lib.folders.forEach { f ->
                    GlassChip(f.name, folder == f.id, { folder = f.id }, onLongClick = {
                        sheet(SheetSpec(f.name, listOf(
                            SheetAction(str(R.string.rename), Icons.Rounded.Edit) {
                                dialog(DialogSpec(str(R.string.rename_folder)) { d -> RenameWindow(f.name, str(R.string.folder_name), d) { Library.renameFolder(f.id, it) } })
                            },
                            SheetAction(str(R.string.delete_folder), Icons.Rounded.DeleteOutline, danger = true) {
                                dialog(DialogSpec(str(R.string.delete_folder)) { d ->
                                    ConfirmWindow(str(R.string.delete_folder_text), str(R.string.delete), d) {
                                        Library.deleteFolder(f.id)
                                        if (folder == f.id) folder = ALL
                                    }
                                })
                            },
                        )))
                    })
                }
            }
            if (lib.tags.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    lib.tags.forEach { t ->
                        GlassChip("#$t", tagFilter == t, { tagFilter = if (tagFilter == t) null else t }, onLongClick = {
                            sheet(SheetSpec("#$t", listOf(
                                SheetAction(str(R.string.rename), Icons.Rounded.Edit) {
                                    dialog(DialogSpec(str(R.string.rename_tag)) { d -> RenameWindow(t, str(R.string.new_tag), d) { n -> Library.renameTag(t, n); if (tagFilter == t) tagFilter = n } })
                                },
                                SheetAction(str(R.string.delete_tag), Icons.Rounded.DeleteOutline, danger = true) {
                                    Library.deleteTag(t)
                                    if (tagFilter == t) tagFilter = null
                                },
                            )))
                        })
                    }
                }
            }
        }
        if (shown.isEmpty()) {
            item {
                EmptyState(
                    str(if (lib.active.isEmpty()) R.string.library_empty else R.string.no_results),
                    str(if (lib.active.isEmpty()) R.string.library_empty_hint else R.string.no_results_hint),
                )
            }
        }
        items(shown, key = { it.id }) { r ->
            val toggle = { selected = if (r.id in selected) selected - r.id else selected + r.id }
            RecordingRow(
                r, folderNames[r.folder],
                onClick = { if (selecting) toggle() else menu.open(r) },
                onMenu = { menu.sheet(r) },
                onLongClick = { focus.clearFocus(); toggle() },
                selecting = selecting,
                selected = r.id in selected,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordingRow(
    r: Recording,
    folderName: String?,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    onLongClick: () -> Unit = onMenu,
    selecting: Boolean = false,
    selected: Boolean = false,
) {
    val p = LocalGlass.current
    val context = LocalContext.current
    val date = DateUtils.formatDateTime(context, r.createdAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .semantics { if (selecting) this.selected = selected }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = str(if (selecting) R.string.options else R.string.select))
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Ícono plano (sin cristal): en listas largas el cristal por fila traba el desplazamiento.
        // Seleccionada, se vuelve un círculo lleno con una marca.
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (selected) p.accentFg else p.accentFg.copy(alpha = if (p.dark) 0.14f else 0.12f))
                .combinedClickable(onClick = if (selecting) onClick else onLongClick, onLongClick = onLongClick)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(24.dp), tint = p.base)
            else Icon(styled(Icons.Rounded.GraphicEq), null, Modifier.size(22.dp), tint = p.accentFg)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.title, Modifier.weight(1f, fill = false), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (r.starred) {
                    Spacer(Modifier.width(6.dp))
                    Icon(styled(Icons.Rounded.Star), str(R.string.starred), Modifier.size(16.dp), tint = p.accentFg)
                }
            }
            Text(
                listOf(date, TimeText.format(r.durationMs), r.audioFormat.name, TimeText.size(r.sizeBytes)).joinToString(" · "),
                fontSize = 13.sp,
                color = p.contentSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val extra = buildList {
                if (folderName != null) add(folderName)
                addAll(r.tags.map { "#$it" })
            }
            if (extra.isNotEmpty() || r.markers.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (r.markers.isNotEmpty()) {
                        Icon(styled(Icons.Rounded.Bookmark), null, Modifier.size(13.dp), tint = p.contentSecondary)
                        Text(" ${r.markers.size}  ", fontSize = 12.sp, color = p.contentSecondary)
                    }
                    Text(extra.joinToString("  "), fontSize = 12.sp, color = p.accentFg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (!selecting) Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onMenu)
                .semantics { contentDescription = str(R.string.options_for, r.title) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(styled(Icons.Rounded.MoreVert), null, Modifier.size(22.dp), tint = p.contentSecondary)
        }
    }
    Spacer(Modifier.height(0.dp))
}

/**
 * Barras de la selección múltiple: favoritos, mover, etiquetas, compartir, exportar y borrar. Las
 * pinta la capa flotante de la app (ver Selection.kt). Atrás sale de la selección.
 */
@Composable
private fun LibrarySelection(selected: Set<Long>, visible: List<Long>, onChange: (Set<Long>) -> Unit) {
    val context = LocalContext.current
    val dialog = LocalDialog.current
    val toast = LocalToast.current
    val setBottom = LocalSelectionBottom.current
    val lib by Library.state.collectAsStateWithLifecycle()
    val active = selected.isNotEmpty()
    androidx.activity.compose.BackHandler(enabled = active) { onChange(emptySet()) }

    // Exportar varias: primero el formato y después la carpeta.
    var exportFormat by remember { mutableStateOf<dev.liquidrecorder.core.audio.AudioFormat?>(null) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val f = exportFormat
        exportFormat = null
        if (tree != null && f != null) {
            dev.liquidrecorder.data.Actions.exportAll(context, lib.recordings.filter { it.id in selected }, f, tree)
            onChange(emptySet())
        }
    }

    val ids = selected
    val recs = lib.recordings.filter { it.id in ids }
    val unstar = recs.isNotEmpty() && recs.all { it.starred }
    val items = androidx.compose.runtime.rememberUpdatedState(
        listOf(
            BarItem(if (unstar) Icons.Rounded.StarOutline else Icons.Rounded.Star, str(R.string.bar_favorite), str(if (unstar) R.string.unstar else R.string.star), active) {
                Library.setStarred(ids, !unstar)
                onChange(emptySet())
            },
            BarItem(Icons.Rounded.DriveFileMove, str(R.string.bar_move), str(R.string.move_to_folder), active) {
                val common = recs.map { it.folder }.distinct().singleOrNull() ?: -1L
                dialog(DialogSpec(str(R.string.move_to_folder)) { d -> FolderPicker(common, d) { Library.move(ids, it); onChange(emptySet()) } })
            },
            BarItem(Icons.AutoMirrored.Rounded.Label, str(R.string.bar_tags), str(R.string.tags), active) {
                dialog(DialogSpec(str(R.string.tags)) { d -> BulkTagEditor(ids, d) })
            },
            BarItem(Icons.Rounded.Share, str(R.string.share), str(R.string.share), active) {
                dev.liquidrecorder.data.Actions.share(context, recs)
                onChange(emptySet())
            },
            BarItem(Icons.Rounded.FileDownload, str(R.string.bar_export), str(R.string.export_as), active) {
                dialog(DialogSpec(str(R.string.export_as)) { d ->
                    Text(str(R.string.export_many_hint), Modifier.padding(bottom = 8.dp), fontSize = 14.sp, color = LocalGlass.current.contentSecondary)
                    dev.liquidrecorder.core.audio.AudioFormat.entries.forEach { f ->
                        dev.liquidrecorder.ui.glass.SettingRow(formatName(f), subtitle = str(formatHint(f)), onClick = {
                            d()
                            exportFormat = f
                            pickFolder.launch(null)
                        })
                    }
                    Spacer(Modifier.height(12.dp))
                    dev.liquidrecorder.ui.glass.GlassButton(str(R.string.cancel), d, Modifier.fillMaxWidth())
                })
            },
            BarItem(Icons.Rounded.AutoFixHigh, str(R.string.bar_clean), str(R.string.clean_title), active) {
                dialog(DialogSpec(str(R.string.clean_title)) { d -> CleanWindow(recs, d) })
                onChange(emptySet())
            },
            BarItem(Icons.Rounded.DeleteOutline, str(R.string.delete), str(R.string.delete), active) {
                ids.forEach { dev.liquidrecorder.audio.Playback.closeIf(it) }
                Library.trash(ids)
                toast(plural(R.plurals.moved_to_trash_count, ids.size))
                onChange(emptySet())
            },
        ),
    )
    val top = androidx.compose.runtime.rememberUpdatedState(
        SelectionTopState(ids.size, ids.size < visible.size, { onChange(visible.toSet()) }, { onChange(emptySet()) }),
    )
    androidx.compose.runtime.DisposableEffect(active) {
        if (active) setBottom(SelectionBottom(top, items))
        onDispose { setBottom(null) }
    }
}

/** Chip de carpeta nueva: los íconos + y carpeta, con las medidas de un GlassChip. */
@Composable
private fun NewFolderChip(onClick: () -> Unit) {
    val p = LocalGlass.current
    dev.liquidrecorder.ui.glass.GlassSurface(
        Modifier
            .height(40.dp)
            .semantics {
                contentDescription = str(R.string.new_folder)
                role = Role.Button
            },
        shape = CircleShape,
        frost = 4.dp,
        shadowRadius = 6.dp,
        onClick = onClick,
    ) {
        Row(Modifier.align(Alignment.Center).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(styled(Icons.Rounded.Add), null, Modifier.size(18.dp), tint = p.content)
            Icon(styled(Icons.Rounded.Folder), null, Modifier.size(20.dp), tint = p.content)
        }
    }
}
