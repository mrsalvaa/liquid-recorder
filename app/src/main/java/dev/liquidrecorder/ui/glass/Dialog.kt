// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.ui.glass

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Diálogo de cristal con contenido libre; [content] recibe la función para cerrarlo. */
class DialogSpec(val title: String, val content: @Composable (dismiss: () -> Unit) -> Unit)

val LocalDialog = staticCompositionLocalOf<(DialogSpec) -> Unit> { {} }

@Composable
fun BoxScope.DialogHost(spec: DialogSpec?, onDismiss: () -> Unit) {
    var last by remember { mutableStateOf(spec) }
    if (spec != null && spec !== last) last = spec
    GlassWindow(spec != null, onDismiss) {
        val s = last ?: return@GlassWindow
        WindowTitle(s.title)
        s.content(onDismiss)
    }
}

/**
 * El molde de todas las ventanas emergentes (diálogos, menús y pedidos de texto): el mismo velo, la
 * misma tarjeta de cristal centrada y la misma animación. El contenido se desplaza si no entra, por
 * ejemplo con el teclado abierto o con el celular en horizontal.
 */
@Composable
fun BoxScope.GlassWindow(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(enabled = visible, onBack = onDismiss)
    val reduced = LocalReducedMotion.current

    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        visible,
        modifier = Modifier.align(Alignment.Center).safeDrawingPadding().padding(20.dp),
        enter = fadeIn() + if (reduced) EnterTransition.None else scaleIn(initialScale = 0.92f),
        exit = fadeOut() + if (reduced) ExitTransition.None else scaleOut(targetScale = 0.92f),
    ) {
        GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(34.dp), strong = true, frost = 16.dp) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp), content = content)
        }
    }
}

/** El título de una ventana emergente, con un subtítulo opcional. */
@Composable
fun WindowTitle(title: String, subtitle: String? = null) {
    Text(title, Modifier.semantics { heading() }.padding(bottom = if (subtitle == null) 12.dp else 2.dp), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    if (subtitle != null) Text(subtitle, Modifier.padding(bottom = 12.dp), fontSize = 14.sp, color = LocalGlass.current.contentSecondary)
}
