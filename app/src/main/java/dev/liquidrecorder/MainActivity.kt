// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import dev.liquidrecorder.audio.Playback
import dev.liquidrecorder.data.Actions
import dev.liquidrecorder.data.Jobs
import dev.liquidrecorder.data.Stores
import dev.liquidrecorder.ui.glass.ActionSheetHost
import dev.liquidrecorder.ui.glass.DialogHost
import dev.liquidrecorder.ui.glass.DialogSpec
import dev.liquidrecorder.ui.glass.FlatNavBar
import dev.liquidrecorder.ui.glass.FlatNavBarHeight
import dev.liquidrecorder.ui.glass.GlassIconButton
import dev.liquidrecorder.ui.glass.GlassNavBar
import dev.liquidrecorder.ui.glass.GlassRoot
import dev.liquidrecorder.ui.glass.GlassSurface
import dev.liquidrecorder.ui.glass.GlassTheme
import dev.liquidrecorder.ui.glass.LocalActionSheet
import dev.liquidrecorder.ui.glass.LocalDialog
import dev.liquidrecorder.ui.glass.LocalGlass
import dev.liquidrecorder.ui.glass.LocalPrompt
import dev.liquidrecorder.ui.glass.LocalReducedMotion
import dev.liquidrecorder.ui.glass.LocalTabActive
import dev.liquidrecorder.ui.glass.NavItem
import dev.liquidrecorder.ui.glass.PromptHost
import dev.liquidrecorder.ui.glass.PromptSpec
import dev.liquidrecorder.ui.glass.SheetSpec
import dev.liquidrecorder.ui.glass.isDarkTheme
import dev.liquidrecorder.ui.glass.pillVeil
import dev.liquidrecorder.ui.glass.styled
import dev.liquidrecorder.ui.screens.EditScreen
import dev.liquidrecorder.ui.screens.LibraryScreen
import dev.liquidrecorder.ui.screens.LocalToast
import dev.liquidrecorder.ui.screens.PlayTarget
import dev.liquidrecorder.ui.screens.PlayerScreen
import dev.liquidrecorder.ui.screens.RecordRequests
import dev.liquidrecorder.ui.screens.RecordScreen
import dev.liquidrecorder.ui.screens.RecordDock
import androidx.compose.foundation.layout.ime
import dev.liquidrecorder.ui.screens.SettingsScreen
import dev.liquidrecorder.ui.screens.ToastHost
import dev.liquidrecorder.ui.screens.TrashScreen
import dev.liquidrecorder.util.preferHighestRefreshRate
import dev.liquidrecorder.util.str
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Pantallas que se abren encima de las pestañas. */
sealed interface Overlay {
    data object Settings : Overlay
    data object Trash : Overlay
    data class Player(val target: PlayTarget) : Overlay
    data class Edit(val id: Long) : Overlay
}

private const val TAB_RECORD = 0
private const val TAB_LIBRARY = 1

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        preferHighestRefreshRate()
        handle(intent)
        setContent {
            val settings by Stores.settings.state.collectAsStateWithLifecycle()
            SystemBarsFor(isDarkTheme(settings.themeMode))
            GlassTheme(settings.glassStyle) {
                RecorderUi(settings.animatedBackground, flatNav = settings.uiStyle == 1)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        Actions.cleanShared(this)
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            ACTION_RECORD -> {
                RecordRequests.pending.value = true
                Navigation.toRecord.value = true
                intent.action = null
            }
        }
    }

    companion object {
        const val ACTION_RECORD = "dev.liquidrecorder.RECORD"
    }
}

/** Pedidos de navegación desde afuera de Compose (intents). */
object Navigation {
    val toRecord = MutableStateFlow(false)
}

@Composable
fun SystemBarsFor(dark: Boolean) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    LaunchedEffect(dark) {
        val style = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}

/**
 * Toda la app. Con [external] (un archivo abierto desde otra app) solo se muestra su reproductor,
 * sin pestañas, y [onExit] cierra al volver.
 */
@Composable
fun RecorderUi(animated: Boolean, flatNav: Boolean, external: Uri? = null, onExit: () -> Unit = {}) {
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    val stack = remember { mutableStateListOf<Overlay>().apply { if (external != null) add(Overlay.Player(PlayTarget.External(external))) } }
    val holder = rememberSaveableStateHolder()
    var sheet by remember { mutableStateOf<SheetSpec?>(null) }
    var prompt by remember { mutableStateOf<PromptSpec?>(null) }
    var dialog by remember { mutableStateOf<DialogSpec?>(null) }
    var toast by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var selectionBottom by remember { mutableStateOf<dev.liquidrecorder.ui.screens.SelectionBottom?>(null) }
    val reduced = LocalReducedMotion.current
    val isExternal = external != null

    fun push(o: Overlay) { stack += o }
    fun pop() {
        if (stack.isEmpty()) return
        val key = "${stack.lastIndex}:${stack.last()}"
        stack.removeAt(stack.lastIndex)
        holder.removeState(key)
        if (isExternal && stack.isEmpty()) onExit()
    }
    BackHandler(enabled = stack.isNotEmpty() && sheet == null && prompt == null && dialog == null) { pop() }
    BackHandler(enabled = !isExternal && stack.isEmpty() && pager.currentPage != TAB_RECORD) { scope.launch { pager.animateScrollToPage(TAB_RECORD) } }

    val toRecord by Navigation.toRecord.collectAsStateWithLifecycle()
    LaunchedEffect(toRecord) {
        if (toRecord) {
            Navigation.toRecord.value = false
            stack.clear()
            pager.scrollToPage(TAB_RECORD)
        }
    }
    val recovered by RecorderApp.RecoveryNotice.collectAsStateWithLifecycle()
    LaunchedEffect(recovered) {
        val (ok, lost) = recovered ?: return@LaunchedEffect
        RecorderApp.RecoveryNotice.value = null
        toast = (if (lost == 0) dev.liquidrecorder.util.plural(R.plurals.recovered, ok) else str(R.string.recovered_lost, ok, lost)) to System.nanoTime()
    }
    val jobMessage by Jobs.messages.collectAsStateWithLifecycle()
    LaunchedEffect(jobMessage) {
        jobMessage?.let { toast = it to System.nanoTime() }
        Jobs.messages.value = null
    }

    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val dir = LocalLayoutDirection.current
    val navBar = if (flatNav) FlatNavBarHeight else 70.dp + 12.dp
    val navHeight = navBar + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val tabPadding = PaddingValues(
        top = insets.calculateTopPadding(),
        bottom = navHeight + 16.dp,
        start = insets.calculateStartPadding(dir),
        end = insets.calculateEndPadding(dir),
    )
    val overlayPadding = PaddingValues(
        top = insets.calculateTopPadding(),
        bottom = insets.calculateBottomPadding() + 16.dp,
        start = insets.calculateStartPadding(dir),
        end = insets.calculateEndPadding(dir),
    )
    val openSaved: (Long) -> Unit = { id -> push(Overlay.Player(PlayTarget.Saved(id))) }

    CompositionLocalProvider(
        LocalActionSheet provides { sheet = it },
        LocalPrompt provides { prompt = it },
        LocalDialog provides { dialog = it },
        LocalToast provides { toast = it to System.nanoTime() },
        dev.liquidrecorder.ui.screens.LocalSelectionBottom provides { selectionBottom = it },
    ) {
        GlassRoot(
            animated = animated,
            floating = {
                if (!isExternal) {
                    AnimatedVisibility(
                        stack.isEmpty() && selectionBottom == null,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        enter = fadeIn() + if (reduced) fadeIn() else slideInVertically { it / 2 },
                        exit = fadeOut() + if (reduced) fadeOut() else slideOutVertically { it / 2 },
                    ) {
                        Column {
                            // Controles de grabación flotantes, solo en la pestaña Grabar y sin teclado.
                            val keyboard = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
                            AnimatedVisibility(
                                pager.currentPage == TAB_RECORD && !keyboard,
                                Modifier.align(Alignment.CenterHorizontally),
                                enter = fadeIn() + scaleIn(initialScale = 0.9f),
                                exit = fadeOut(),
                            ) {
                                RecordDock()
                            }
                            // En Grabar ya están los controles de grabación: lo que suena se ve en Grabaciones.
                            if (pager.currentPage == TAB_LIBRARY) NowPlaying(Modifier.padding(horizontal = 16.dp), onOpen = { item ->
                                push(Overlay.Player(if (item.recordingId != 0L) PlayTarget.Saved(item.recordingId) else PlayTarget.External(item.uri)))
                            })
                            val items = listOf(
                                NavItem(str(R.string.tab_record), Icons.Rounded.Mic),
                                NavItem(str(R.string.tab_library), Icons.Rounded.LibraryMusic),
                            )
                            val select: (Int) -> Unit = { scope.launch { pager.animateScrollToPage(it) } }
                            if (flatNav) {
                                FlatNavBar(items, selected = pager.currentPage, onSelect = select)
                            } else {
                                GlassNavBar(
                                    items = items,
                                    selected = pager.targetPage,
                                    onSelect = select,
                                    dragPosition = { if (pager.isScrollInProgress) pager.currentPage + pager.currentPageOffsetFraction else null },
                                    modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                                )
                            }
                        }
                    }
                }
                // Selección múltiple: la píldora de pestañas cambia a las opciones y arriba aparece otra.
                var lastSelection by remember { mutableStateOf(selectionBottom) }
                if (selectionBottom != null) lastSelection = selectionBottom
                AnimatedVisibility(
                    selectionBottom != null && stack.isEmpty(),
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = fadeIn() + if (reduced) fadeIn() else slideInVertically { it / 2 },
                    exit = fadeOut() + if (reduced) fadeOut() else slideOutVertically { it / 2 },
                ) {
                    lastSelection?.let { dev.liquidrecorder.ui.screens.SelectionActions(it, flatNav) }
                }
                AnimatedVisibility(
                    selectionBottom != null && stack.isEmpty(),
                    modifier = Modifier.align(Alignment.TopCenter),
                    enter = fadeIn() + if (reduced) fadeIn() else slideInVertically { -it / 2 },
                    exit = fadeOut() + if (reduced) fadeOut() else slideOutVertically { -it / 2 },
                ) {
                    lastSelection?.let { dev.liquidrecorder.ui.screens.SelectionTop(it, flatNav, insets.calculateTopPadding()) }
                }
                JobHost(Modifier.align(Alignment.TopCenter).padding(top = insets.calculateTopPadding() + 20.dp, start = 24.dp, end = 24.dp))
                ToastHost(toast) { toast = null }
                ActionSheetHost(sheet) { sheet = null }
                PromptHost(prompt) { prompt = null }
                DialogHost(dialog) { dialog = null }
            },
        ) {
            val top = stack.lastOrNull()
            val tabsAlpha by animateFloatAsState(if (top == null) 1f else 0f, tween(if (reduced) 0 else if (top == null) 220 else 120), label = "tabs")
            if (!isExternal) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = tabsAlpha }
                        .drawWithContent { if (tabsAlpha > 0.01f) drawContent() }
                        .then(if (top != null) Modifier.clearAndSetSemantics { } else Modifier),
                ) {
                    HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, userScrollEnabled = top == null && selectionBottom == null) { page ->
                        CompositionLocalProvider(LocalTabActive provides (page == pager.currentPage && top == null)) {
                            when (page) {
                                TAB_RECORD -> RecordScreen(tabPadding, onSettings = { push(Overlay.Settings) }, onOpen = openSaved)
                                else -> LibraryScreen(
                                    tabPadding,
                                    onOpen = { openSaved(it.id) },
                                    onOpenFile = { push(Overlay.Player(PlayTarget.External(it))) },
                                    onEdit = { push(Overlay.Edit(it.id)) },
                                    onSettings = { push(Overlay.Settings) },
                                )
                            }
                        }
                    }
                }
            }

            val topKey = if (top == null) "none" else "${stack.lastIndex}:$top"
            AnimatedContent(
                targetState = top to topKey,
                transitionSpec = {
                    if (reduced) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    else (fadeIn(tween(200)) + scaleIn(tween(220), initialScale = 0.97f)) togetherWith fadeOut(tween(120))
                },
                contentKey = { it.second },
                label = "overlay",
            ) { (current, key) ->
                if (current == null) {
                    Box(Modifier.fillMaxSize())
                    return@AnimatedContent
                }
                holder.SaveableStateProvider(key) {
                    when (current) {
                        Overlay.Settings -> SettingsScreen(overlayPadding, ::pop, onTrash = { push(Overlay.Trash) })
                        Overlay.Trash -> TrashScreen(overlayPadding, ::pop)
                        is Overlay.Player -> PlayerScreen(
                            current.target, overlayPadding, ::pop,
                            onEdit = { push(Overlay.Edit(it.id)) },
                            onImported = { id ->
                                if (isExternal) {
                                    toast = str(R.string.imported) to System.nanoTime()
                                } else {
                                    pop()
                                    openSaved(id)
                                }
                            },
                        )
                        is Overlay.Edit -> EditScreen(current.id, overlayPadding, ::pop, onDone = { newId ->
                            // Vuelve a la lista o abre el resultado en lugar del original.
                            pop()
                            if (stack.lastOrNull() is Overlay.Player) pop()
                            if (newId != null) openSaved(newId)
                        })
                    }
                }
            }
        }
    }
}

/** Píldora de lo que está sonando, sobre la barra de pestañas. */
@Composable
private fun NowPlaying(modifier: Modifier, onOpen: (dev.liquidrecorder.audio.PlayItem) -> Unit) {
    val p = LocalGlass.current
    val context = LocalContext.current
    val item by Playback.item.collectAsStateWithLifecycle()
    val current = item ?: return
    val player = remember { Playback.get(context) }
    var playing by remember { mutableStateOf(player.isPlaying) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    // Solo se muestra si está sonando o en pausa a mitad (no recién abierto).
    if (!playing && player.currentPosition == 0L) return
    GlassSurface(
        modifier.fillMaxWidth().height(60.dp),
        shape = CircleShape,
        strong = true,
        tint = p.pillVeil(),
        frost = 4.dp,
        onClick = { onOpen(current) },
    ) {
        Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, str(if (playing) R.string.pause else R.string.play), {
                if (player.isPlaying) player.pause() else { Playback.startService(context); player.play() }
            }, size = 44.dp, prominent = true)
            Spacer(Modifier.width(12.dp))
            Icon(styled(Icons.Rounded.GraphicEq), null, Modifier.size(18.dp), tint = p.accentFg)
            Spacer(Modifier.width(6.dp))
            // "Grabación 9 · 2:11 / 3:12": el nombre se acorta si no entra, el tiempo siempre se ve.
            var pos by remember { mutableStateOf(0L to 0L) }
            LaunchedEffect(player, playing) {
                while (true) {
                    val d = player.duration.takeIf { it > 0 } ?: 0L
                    pos = player.currentPosition to d
                    if (!playing) break
                    kotlinx.coroutines.delay(250)
                }
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(current.title, Modifier.weight(1f, fill = false), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    " · " + dev.liquidrecorder.core.text.TimeText.format(pos.first) + " / " + dev.liquidrecorder.core.text.TimeText.format(pos.second),
                    fontSize = 15.sp,
                    color = p.contentSecondary,
                    maxLines = 1,
                    style = androidx.compose.material3.LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
                )
            }
            Spacer(Modifier.width(8.dp))
            GlassIconButton(Icons.Rounded.Close, str(R.string.close_player), { Playback.stop() }, size = 44.dp)
        }
    }
}

/** Tarea larga en curso (exportar, importar, editar), con su avance y Cancelar. */
@Composable
private fun JobHost(modifier: Modifier) {
    val job by Jobs.current.collectAsStateWithLifecycle()
    var last by remember { mutableStateOf(job) }
    if (job != null) last = job
    AnimatedVisibility(job != null, modifier, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
        val j = last ?: return@AnimatedVisibility
        val p = LocalGlass.current
        GlassSurface(Modifier.fillMaxWidth(), shape = CircleShape, strong = true, frost = 12.dp) {
            Row(Modifier.padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val context = LocalContext.current
                    // Se recalcula cada segundo para que el tiempo restante avance aunque el % no.
                    var tick by remember { mutableStateOf(0) }
                    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); tick++ } }
                    val remaining = remember(tick, j) { j.remainingMs?.let { dev.liquidrecorder.service.JobService.remainingText(context, it) } }
                    Text(j.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull("${(j.progress * 100).toInt()} %", remaining).joinToString(" · "),
                        fontSize = 12.sp,
                        color = p.contentSecondary,
                    )
                    LinearProgressIndicator(
                        progress = { j.progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        color = p.accentFg,
                        trackColor = p.content.copy(alpha = 0.12f),
                        strokeCap = StrokeCap.Round,
                    )
                }
                Spacer(Modifier.width(8.dp))
                GlassIconButton(Icons.Rounded.Close, str(R.string.cancel), { Jobs.cancel() }, size = 44.dp)
            }
        }
    }
}
