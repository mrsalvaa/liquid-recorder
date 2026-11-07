// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liquidrecorder.R
import dev.liquidrecorder.ui.glass.FlatNavBarHeight
import dev.liquidrecorder.ui.glass.GlassSurface
import dev.liquidrecorder.ui.glass.LocalGlass
import dev.liquidrecorder.ui.glass.pillVeil
import dev.liquidrecorder.ui.glass.styled
import dev.liquidrecorder.util.plural
import dev.liquidrecorder.util.str

// Selección múltiple, con el mismo diseño que la de Liquid Contacts (ContactActions.kt): la
// píldora de pestañas cambia a las opciones y arriba aparece otra con la cantidad.

data class BarItem(val icon: ImageVector, val label: String, val description: String, val enabled: Boolean, val onClick: () -> Unit)

data class SelectionTopState(val count: Int, val canSelectAll: Boolean, val onSelectAll: () -> Unit, val onExit: () -> Unit)

/** Lo que muestra la lista que está seleccionando; lo pinta la capa flotante de la app. */
class SelectionBottom(val top: State<SelectionTopState>, val items: State<List<BarItem>>)

val LocalSelectionBottom = staticCompositionLocalOf<(SelectionBottom?) -> Unit> { {} }

private val PILL_FROST = 4.dp

/** La píldora de arriba durante la selección, con el mismo cristal que la de pestañas. */
@Composable
fun SelectionTop(bottom: SelectionBottom, flat: Boolean, topInset: Dp, modifier: Modifier = Modifier) {
    val p = LocalGlass.current
    val state = bottom.top.value
    val row: @Composable (Modifier) -> Unit = { rowModifier ->
        Row(
            rowModifier.clickable(remember { MutableInteractionSource() }, null) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarAction(Icons.Rounded.Close, null, str(R.string.selection_exit), state.onExit)
            Text(
                plural(R.plurals.selected_count, state.count, state.count),
                Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() },
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = p.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BarAction(Icons.Rounded.SelectAll, str(R.string.select_all), str(R.string.select_all), state.onSelectAll, enabled = state.canSelectAll)
        }
    }
    if (flat) {
        row(
            modifier
                .fillMaxWidth()
                .background(p.levels.firstOrNull() ?: p.base)
                .drawBehind { drawLine(p.outline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx()) }
                .padding(top = topInset + 4.dp, bottom = 4.dp, start = 8.dp, end = 8.dp),
        )
    } else {
        GlassSurface(
            modifier.padding(top = topInset + 20.dp, start = 16.dp, end = 16.dp).fillMaxWidth(),
            shape = CircleShape,
            strong = true,
            tint = p.pillVeil(),
            frost = PILL_FROST,
        ) {
            row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
}

/** La píldora de abajo durante la selección: las mismas medidas que la de pestañas. */
@Composable
fun SelectionActions(bottom: SelectionBottom, flat: Boolean, modifier: Modifier = Modifier) {
    val p = LocalGlass.current
    val row: @Composable (Modifier) -> Unit = { rowModifier ->
        Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
            bottom.items.value.forEach { BarAction(it.icon, it.label, it.description, it.onClick, Modifier.weight(1f), it.enabled) }
        }
    }
    if (flat) {
        Column(
            modifier
                .fillMaxWidth()
                .background(p.levels.firstOrNull() ?: p.base)
                .drawBehind { drawLine(p.outline, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx()) }
                .navigationBarsPadding(),
        ) {
            row(Modifier.fillMaxWidth().height(FlatNavBarHeight))
        }
    } else {
        GlassSurface(
            modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().height(70.dp),
            shape = CircleShape,
            strong = true,
            tint = p.pillVeil(),
            frost = PILL_FROST,
        ) {
            row(Modifier.fillMaxSize().padding(horizontal = 6.dp))
        }
    }
}

/** Botón plano con ícono y texto. Apagado se ve tenue y no responde. */
@Composable
private fun BarAction(icon: ImageVector, label: String?, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val p = LocalGlass.current
    val color = if (enabled) p.content else p.contentSecondary.copy(alpha = 0.45f)
    Column(
        modifier
            .widthIn(min = 48.dp)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 0.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(styled(icon), null, Modifier.size(22.dp), tint = color)
        // Hasta 7 opciones a lo ancho: texto chico y sin "…", que se ajusta solo si no entra.
        if (label != null) Text(
            label,
            color = color,
            maxLines = 1,
            textAlign = TextAlign.Center,
            softWrap = false,
            style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 10.sp),
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 10.sp),
        )
    }
}
