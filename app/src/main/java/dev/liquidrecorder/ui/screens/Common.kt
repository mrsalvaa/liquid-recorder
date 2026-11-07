// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liquidrecorder.R
import dev.liquidrecorder.ui.glass.GlassChip
import dev.liquidrecorder.ui.glass.GlassIconButton
import dev.liquidrecorder.ui.glass.GlassSurface
import dev.liquidrecorder.ui.glass.LocalGlass
import dev.liquidrecorder.ui.glass.TitleTopPadding
import dev.liquidrecorder.util.str
import kotlinx.coroutines.delay

/** Título de una pantalla abierta encima de las pestañas, con el botón de volver. */
@Composable
fun BackTitle(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable () -> Unit = {},
) {
    val p = LocalGlass.current
    Row(
        modifier.fillMaxWidth().padding(top = TitleTopPadding, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, str(R.string.back), onBack)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                Modifier.semantics { heading() },
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) Text(subtitle, fontSize = 14.sp, color = p.contentSecondary, maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { actions() }
    }
}

/** Chips para elegir una opción; los que no entran pasan a la línea siguiente (nada queda cortado). */
@Composable
fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    FlowRow(
        modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            GlassChip(label(option), option == selected, { onSelect(option) })
        }
    }
}

@Composable
fun EmptyState(title: String, subtitle: String, modifier: Modifier = Modifier) {
    val p = LocalGlass.current
    Box(modifier.fillMaxWidth().padding(vertical = 80.dp, horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(
                subtitle,
                Modifier.padding(top = 6.dp),
                fontSize = 14.sp,
                color = p.contentSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Aviso breve con forma de píldora de cristal. */
val LocalToast = staticCompositionLocalOf<(String) -> Unit> { {} }

@Composable
fun BoxScope.ToastHost(message: Pair<String, Long>?, onDone: () -> Unit) {
    var last by remember { mutableStateOf(message?.first) }
    if (message != null) last = message.first
    LaunchedEffect(message) {
        if (message != null) {
            delay(2200)
            onDone()
        }
    }
    AnimatedVisibility(
        message != null,
        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 100.dp, start = 24.dp, end = 24.dp),
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
    ) {
        GlassSurface(shape = CircleShape, strong = true, frost = 12.dp) {
            Text(
                last ?: "",
                Modifier
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
