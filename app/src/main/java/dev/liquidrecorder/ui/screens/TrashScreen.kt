// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidrecorder.R
import dev.liquidrecorder.data.Library
import dev.liquidrecorder.ui.components.DangerButton
import dev.liquidrecorder.ui.glass.DialogSpec
import dev.liquidrecorder.ui.glass.LocalActionSheet
import dev.liquidrecorder.ui.glass.LocalDialog
import dev.liquidrecorder.ui.glass.LocalGlass
import dev.liquidrecorder.ui.glass.SheetAction
import dev.liquidrecorder.ui.glass.SheetSpec
import dev.liquidrecorder.util.plural
import dev.liquidrecorder.util.str

@Composable
fun TrashScreen(padding: PaddingValues, onBack: () -> Unit) {
    val p = LocalGlass.current
    val dialog = LocalDialog.current
    val sheet = LocalActionSheet.current
    val lib by Library.state.collectAsStateWithLifecycle()
    val items = lib.trashed.sortedByDescending { it.trashedAt }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                BackTitle(str(R.string.trash), onBack, subtitle = plural(R.plurals.recordings_count, items.size))
                Text(str(R.string.trash_hint, Library.TRASH_DAYS), fontSize = 14.sp, color = p.contentSecondary)
                if (items.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    DangerButton(str(R.string.empty_trash), {
                        dialog(DialogSpec(str(R.string.empty_trash)) { d ->
                            ConfirmWindow(plural(R.plurals.empty_trash_text, items.size), str(R.string.delete_forever), d) {
                                Library.deleteForever(items.map { it.id })
                            }
                        })
                    }, Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        if (items.isEmpty()) item { EmptyState(str(R.string.trash_empty), str(R.string.trash_empty_hint)) }
        items(items, key = { it.id }) { r ->
            val daysLeft = (Library.TRASH_DAYS - (System.currentTimeMillis() - r.trashedAt) / 86_400_000L).coerceAtLeast(0).toInt()
            val open = {
                sheet(SheetSpec(r.title, listOf(
                    SheetAction(str(R.string.restore), Icons.Rounded.Restore) { Library.restore(listOf(r.id)) },
                    SheetAction(str(R.string.delete_forever), Icons.Rounded.DeleteForever, danger = true) {
                        dialog(DialogSpec(str(R.string.delete_forever)) { d ->
                            ConfirmWindow(str(R.string.delete_forever_text, r.title), str(R.string.delete_forever), d) { Library.deleteForever(listOf(r.id)) }
                        })
                    },
                ), subtitle = plural(R.plurals.days_left, daysLeft)))
            }
            RecordingRow(r, null, open, open)
        }
    }
}
