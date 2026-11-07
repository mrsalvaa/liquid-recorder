// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import dev.liquidrecorder.R
import dev.liquidrecorder.ui.glass.GlassButton
import dev.liquidrecorder.ui.glass.GlassPalette
import dev.liquidrecorder.ui.glass.GlassSurface
import dev.liquidrecorder.ui.glass.GlassSwitch
import dev.liquidrecorder.ui.glass.LocalGlass
import dev.liquidrecorder.ui.glass.SettingRow
import dev.liquidrecorder.ui.glass.styled
import dev.liquidrecorder.util.str

/** Fila con interruptor: toda la fila lo cambia y TalkBack la lee como una sola cosa (de Contacts). */
@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, subtitle: String? = null) {
    SettingRow(
        title,
        modifier.semantics(mergeDescendants = true) { },
        subtitle = subtitle,
        onClick = { onChange(!checked) },
        trailing = { GlassSwitch(checked, onChange) },
    )
}

/**
 * Rojo de los botones destructivos (copiado de Contacts): el `danger` de ui/glass es translúcido y
 * con texto blanco no llega a 4,5:1 en tema claro.
 */
val DangerFill = Color(0xFF9F1818)

/** Rojo del botón de grabar: un círculo grande con ícono blanco (contraste de gráfico, 3:1). */
val RecordRed = Color(0xFFD92D3A)

/** Texto de error sobre una tarjeta. */
val GlassPalette.dangerText: Color get() = if (dark) danger else Color(0xFFB3261E)

@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    GlassSurface(
        modifier
            .heightIn(min = 54.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (!enabled) disabled()
            },
        shape = CircleShape,
        strong = true,
        tint = DangerFill,
        onClick = if (enabled) onClick else null,
    ) {
        Row(
            Modifier.align(Alignment.Center).padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(styled(icon), null, Modifier.size(20.dp), tint = Color.White)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Campo de texto de cristal que arranca con un valor y todo seleccionado (para renombrar). El
 * GlassTextField de ui/glass trabaja con String y no puede seleccionar.
 */
@Composable
fun RenameField(
    initial: String,
    placeholder: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
    focus: FocusRequester = remember { FocusRequester() },
    autoFocus: Boolean = true,
    onDone: () -> Unit = {},
) {
    val p = LocalGlass.current
    var value by remember(initial) { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val style = TextStyle(fontSize = 17.sp, color = p.content)
    GlassSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), frost = 12.dp, refraction = false) {
        Box(Modifier.padding(horizontal = 18.dp, vertical = 15.dp)) {
            BasicTextField(
                value = value,
                onValueChange = { value = it; onValue(it.text) },
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(p.accentFg),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                decorationBox = { inner ->
                    Box {
                        if (value.text.isEmpty()) Text(placeholder, style = style, color = p.contentSecondary)
                        inner()
                    }
                },
            )
        }
    }
}

/** Ventana para renombrar: campo con el nombre actual seleccionado, Guardar y Cancelar. */
@Composable
fun RenameWindow(initial: String, placeholder: String, dismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val save = {
        dismiss()
        onSave(text.trim())
    }
    RenameField(initial, placeholder, { text = it }, onDone = save)
    Box(Modifier.height(16.dp))
    GlassButton(str(R.string.save), save, Modifier.fillMaxWidth(), prominent = true)
    Box(Modifier.height(8.dp))
    GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
}
