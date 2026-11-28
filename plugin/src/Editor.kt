@file:OptIn(
    androidx.compose.runtime.InternalComposeApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    com.moriafly.salt.ui.UnstableSaltUiApi::class,
    com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class
)
package automix

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moriafly.salt.ui.BasicButton
import com.moriafly.salt.ui.ButtonColors
import com.moriafly.salt.ui.Card as SaltUiCard
import com.moriafly.salt.ui.ControlSize
import com.moriafly.salt.ui.ItemDivider
import com.moriafly.salt.ui.SaltColors
import com.moriafly.salt.ui.SaltConfigs
import com.moriafly.salt.ui.SaltDynamicColors
import com.moriafly.salt.ui.SaltShapes
import com.moriafly.salt.ui.SaltTextStyles
import com.moriafly.salt.ui.SaltTheme
import com.moriafly.salt.ui.Switcher
import com.moriafly.salt.ui.Text as SaltText
import com.moriafly.salt.ui.material.glass
import com.moriafly.salt.ui.material.glassSource
import com.moriafly.salt.ui.material.rememberGlassSource
import com.moriafly.salt.ui.popup.PopupMenuBackdrop
import com.moriafly.salt.ui.screen.BasicScreenDefaults
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.WorkshopApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*

/** Read the actual Salt composition locals in the existing Workshop page. */
internal data class Skin(
    val isDark: Boolean,
    val accent: Color,
    val onAccent: Color,
    val text: Color,
    val muted: Color,
    val background: Color,
    val surface: Color,
    val line: Color,
    val main: TextStyle,
    val sub: TextStyle,
    val cardShape: Shape,
    val pageBackground: Color = background,
    val popup: Color = surface,
    val success: Color = if (isDark) Color(0xFF34D399) else Color(0xFF10B981),
    val warning: Color = if (isDark) Color(0xFFFBBF24) else Color(0xFFF59E0B),
    val error: Color = if (isDark) Color(0xFFF87171) else Color(0xFFEF4444),
    val paragraph: TextStyle = sub,
    val largeTitle: TextStyle = main,
    val smallShape: Shape = RoundedCornerShape(8.dp),
    val largeShape: Shape = RoundedCornerShape(14.dp),
    val hover: Color = if (isDark) Color(0xFF383A40) else Color(0xFFE6E8EC),
    val selectedControl: Color = if (isDark) Color(0xFF3E414A) else Color.White
) {
    fun popupBackground(): Color {
        val base = if (isDark) Color(0xFF191D23) else Color.White
        return popup.compositeOver(background.compositeOver(base))
    }

    fun toSaltColors(): SaltColors = SaltColors(
        highlight = accent,
        text = text,
        subText = muted,
        background = background,
        subBackground = surface,
        popup = popupBackground(),
        stroke = line,
        onHighlight = onAccent,
        success = success,
        warning = warning,
        error = error
    )
}

private data class StagePalette(
    val wellBg: Color,
    val mixBg: Color,
    val mixTint: Color,
    val outsideDim: Color,
    val beatGrid: Color,
    val barGrid: Color,
    val beatTick: Color,
    val downbeatTick: Color,
    val waveLow: Color,
    val waveMid: Color,
    val waveHigh: Color,
    val curveVol: Color,
    val curveEq: Color,
    val curveFilter: Color,
    val snapCol: Color,
    val playhead: Color,
    val overviewBg: Color,
    val overviewPeak: Color,
    val overviewWin: Color
)

internal val LocalSkin = staticCompositionLocalOf<Skin> { error("Missing SPW theme") }

internal class HostSkin(symbols: HostSymbols) {
    private val theme = symbols.named("com.moriafly.salt.ui.SaltTheme")
    private val configType = symbols.named("com.moriafly.salt.ui.SaltConfigs")
    private val colorType = symbols.named("com.moriafly.salt.ui.SaltColors")
    private val styleType = symbols.named("com.moriafly.salt.ui.SaltTextStyles")
    private val shapeType = symbols.named("com.moriafly.salt.ui.SaltShapes")

    private fun accessor(symbols: HostSymbols, type: HostType) = symbols.method(theme.method("主题 CompositionLocal") {
        it.isStatic && it.symbol.descriptor == "(Landroidx/compose/runtime/Composer;)${type.descriptor}" &&
            it.calls.any { call -> call.owner == "androidx/compose/runtime/Composer" && call.name == "consume" }
    }.symbol)

    private fun getter(symbols: HostSymbols, type: HostType, field: HostSymbol, result: String) =
        symbols.method(type.method("主题属性读取") {
            it.isPublic && !it.isStatic && it.symbol.descriptor == "()$result" && it.reads == listOf(field)
        }.symbol)

    private val configs = accessor(symbols, configType)
    private val colors = accessor(symbols, colorType)
    private val styles = accessor(symbols, styleType)
    private val shapes = accessor(symbols, shapeType)
    private val isDarkFlag = symbols.method(configType.method("深色主题") {
        it.isPublic && !it.isStatic && it.symbol.descriptor == "()Z" && "isDarkTheme" in configType.original
    }.symbol)
    private val palette = colorType.constructorProperties("highlight", "text", "subText", "background", "subBackground",
        "popup", "stroke", "onHighlight", "success", "warning", "error").let { fields ->
        listOf("highlight", "text", "subText", "background", "subBackground", "stroke", "onHighlight", "popup",
            "success", "warning", "error")
            .map { getter(symbols, colorType, fields.getValue(it), "J") }
    }
    private val fonts = styleType.constructorProperties("main", "sub", "paragraph", "largeTitle").let { fields ->
        listOf("main", "sub", "paragraph", "largeTitle")
            .map { getter(symbols, styleType, fields.getValue(it), "Landroidx/compose/ui/text/TextStyle;") }
    }
    private val shapeGetters = shapeType.constructorProperties("small", "medium", "large").let { fields ->
        listOf("small", "medium", "large")
            .map { getter(symbols, shapeType, fields.getValue(it), "Landroidx/compose/ui/graphics/Shape;") }
    }

    fun read(composer: Composer): Skin {
        val cfg = configs.invoke(null, composer)
        val c = colors.invoke(null, composer)
        val s = styles.invoke(null, composer)
        val sh = shapes.invoke(null, composer)
        val p = palette.map { Color((it.invoke(c) as Long).toULong()) }
        val isDark = runCatching { isDarkFlag.invoke(cfg) as Boolean }.getOrElse {
            (p[4].red * 0.299f + p[4].green * 0.587f + p[4].blue * 0.114f) < 0.45f
        }
        val mainStyle = fonts[0].invoke(s) as TextStyle
        val subStyle = fonts[1].invoke(s) as TextStyle
        val paragraphStyle = runCatching { fonts[2].invoke(s) as TextStyle }.getOrDefault(subStyle)
        val largeTitleStyle = runCatching { fonts[3].invoke(s) as TextStyle }.getOrDefault(mainStyle)
        val smallShape = runCatching { shapeGetters[0].invoke(sh) as Shape }.getOrElse { RoundedCornerShape(8.dp) }
        val cardShape = runCatching { shapeGetters[1].invoke(sh) as Shape }.getOrElse { RoundedCornerShape(14.dp) }
        val largeShape = runCatching { shapeGetters[2].invoke(sh) as Shape }.getOrElse { RoundedCornerShape(14.dp) }
        // Match SPW's official light/dark card & control surface hierarchy while inheriting host accent/text/fonts.
        val pageBg = if (isDark) Color(0xFF232323) else Color(0xFFF8F8F8)
        val cardBg = if (isDark) Color(0xFF2A2A2A) else Color(0xFFFCFCFC)
        val controlBg = if (isDark) Color(0xFF32343A) else Color(0xFFEDEEF1)
        val strokeCol = if (isDark) Color(0xFF393B41) else Color(0xFFE7E8EC)
        val hoverCol = if (isDark) Color(0xFF383A40) else Color(0xFFE6E8EC)
        val selectedCtrl = if (isDark) Color(0xFF3E414A) else Color.White
        return Skin(
            isDark = isDark,
            accent = p[0],
            text = p[1],
            muted = p[2],
            background = controlBg,
            surface = cardBg,
            line = strokeCol,
            onAccent = p[6],
            main = mainStyle,
            sub = subStyle,
            cardShape = cardShape,
            pageBackground = pageBg,
            popup = p[7],
            success = p[8],
            warning = p[9],
            error = p[10],
            paragraph = paragraphStyle,
            largeTitle = largeTitleStyle,
            smallShape = smallShape,
            largeShape = largeShape,
            hover = hoverCol,
            selectedControl = selectedCtrl
        )
    }
}

internal fun fallbackSkin(dark: Boolean) = Skin(
    isDark = dark,
    accent = if (dark) Color(0xFF79B5FF) else Color(0xFF2777D8),
    text = if (dark) Color(0xFFEDEDEE) else Color(0xFF25272B),
    muted = if (dark) Color(0xFFA0A3AB) else Color(0xFF777B82),
    background = if (dark) Color(0xFF32343A) else Color(0xFFEDEEF1),
    surface = if (dark) Color(0xFF2A2A2A) else Color(0xFFFCFCFC),
    line = if (dark) Color(0xFF393B41) else Color(0xFFE7E8EC),
    onAccent = if (dark) Color(0xFF101923) else Color.White,
    main = TextStyle(fontSize = 14.sp),
    sub = TextStyle(fontSize = 12.sp),
    cardShape = RoundedCornerShape(14.dp),
    pageBackground = if (dark) Color(0xFF232323) else Color(0xFFF8F8F8),
    popup = if (dark) Color(0xFF2A2A2A) else Color(0xFFFCFCFC)
)

private fun stagePalette(skin: Skin): StagePalette {
    val material = skin.pageBackground.alpha < 1f
    return if (!skin.isDark) {
        StagePalette(
            wellBg = skin.background,
            mixBg = if (material) Color.White.copy(alpha = 0.22f) else Color(0xFFF8FAFC),
            mixTint = Color.Transparent,
            outsideDim = if (material) Color.White.copy(alpha = 0.34f) else skin.background.copy(alpha = 0.65f),
            beatGrid = skin.line.copy(alpha = 0.65f),
            barGrid = skin.muted.copy(alpha = 0.25f),
            beatTick = skin.muted.copy(alpha = 0.35f),
            downbeatTick = skin.accent.copy(alpha = 0.72f),
            waveLow = Color(0xFF2563EB),
            waveMid = Color(0xFFF59E0B),
            waveHigh = Color(0xFFFFFFFF),
            curveVol = Color(0xFF0EA5E9),
            curveEq = Color(0xFFFACC15),
            curveFilter = Color(0xFFD946EF),
            snapCol = skin.accent,
            playhead = Color(0xFF10B981),
            overviewBg = skin.background,
            overviewPeak = Color(0xFF3B82F6).copy(alpha = 0.65f),
            overviewWin = skin.accent.copy(alpha = 0.22f)
        )
    } else {
        StagePalette(
            wellBg = if (material) skin.background else Color(0xFF111317),
            mixBg = if (material) Color.White.copy(alpha = 0.10f) else Color(0xFF191D23),
            mixTint = Color.Transparent,
            outsideDim = Color(0x660B0D10),
            beatGrid = Color(0xFF252B33),
            barGrid = Color(0xFF38424E),
            beatTick = Color(0x559CA3AF),
            downbeatTick = skin.accent.copy(alpha = 0.80f),
            waveLow = Color(0xFF2563EB),
            waveMid = Color(0xFFD97706),
            waveHigh = Color(0xFFF8FAFC),
            curveVol = Color(0xFF38BDF8),
            curveEq = Color(0xFFFDE047),
            curveFilter = Color(0xFFE879F9),
            snapCol = skin.accent,
            playhead = Color(0xFF22C55E),
            overviewBg = if (material) skin.background else Color(0xFF111317),
            overviewPeak = Color(0xFF64748B),
            overviewWin = skin.accent.copy(alpha = 0.34f)
        )
    }
}

internal data class TrackWaves(
    val peaks: IntArray,
    val low: IntArray,
    val mid: IntArray,
    val high: IntArray,
    val beats: DoubleArray,
    val downbeats: DoubleArray,
    val duration: Double,
    val lufs: Double,
    val keyLabel: String?
)

internal data class PairAnalysisSnapshot(
    val waveA: TrackWaves,
    val waveB: TrackWaves,
    val autoPlan: Plan,
    val reasons: List<String>
)

internal data class PairSnapshotKey(val pair: String, val pathA: String, val pathB: String, val mode: MixAlgorithm)

internal data class EditorPair(
    val outgoing: MediaItem?,
    val incoming: MediaItem?,
    val rowKeys: Pair<String, String>? = null
) {
    companion object {
        fun displayed(queue: QueueView, pinned: EditorPair?) =
            pinned ?: EditorPair(queue.nextPair?.first, queue.nextPair?.second)
    }
}

internal class AutoMixUiCache {
    var library: List<MediaItem> = emptyList()
    var queue: QueueView = QueueView()
    var playlists: List<SpwPlaylist> = emptyList()
    var selectedPlaylistId: String? = null
    var pinnedPair: EditorPair? = null
    var playlistTab: String = "queue"
    var initialized: Boolean = false
    var queueScrolledToCurrent: Boolean = false
    val covers = PlaylistCoverStore()
    val trackMetadata = mutableStateMapOf<String, TrackAnalysisMetadata>()
    val pairCache = object : LinkedHashMap<PairSnapshotKey, PairAnalysisSnapshot>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PairSnapshotKey, PairAnalysisSnapshot>) = size > 12
    }
    val editHistories = mutableMapOf<String, TransitionEditHistory>()
}

private val transitionLengths = linkedMapOf(1 to "1 小节", 2 to "2 小节", 4 to "4 小节", 8 to "8 小节")

@Composable
private fun SaltCard(content: @Composable ColumnScope.() -> Unit) {
    SaltUiCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = SaltTheme.colors.subBackground,
        border = BorderStroke(1.dp, SolidColor(SaltTheme.colors.stroke))
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun SaltDivider() {
    ItemDivider(color = SaltTheme.colors.stroke)
}

@Composable
private fun Label(text: String, small: Boolean = false, color: Color? = null) {
    val base = if (small) SaltTheme.textStyles.sub else SaltTheme.textStyles.main
    val resolvedColor = color ?: if (small) SaltTheme.colors.subText else SaltTheme.colors.text
    BasicText(text, style = base.copy(color = resolvedColor))
}

@Composable
private fun Chip(
    text: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    primary: Boolean = false,
    compact: Boolean = false,
    quiet: Boolean = false,
    click: () -> Unit
) {
    val skin = LocalSkin.current
    val colors = SaltTheme.colors
    val isDark = SaltTheme.configs.isDarkTheme
    var hovered by remember { mutableStateOf(false) }
    val bg = when {
        primary -> colors.highlight
        selected -> colors.highlight.copy(alpha = if (isDark) 0.24f else 0.12f)
        hovered && enabled -> skin.hover
        quiet -> Color.Transparent
        else -> colors.background
    }
    val border = when {
        !enabled || quiet -> null
        primary -> BorderStroke(1.dp, SolidColor(colors.highlight))
        selected -> BorderStroke(1.dp, SolidColor(colors.highlight.copy(alpha = 0.45f)))
        else -> null
    }
    val textCol = when {
        primary -> colors.onHighlight
        selected -> colors.highlight
        else -> colors.text
    }
    BasicButton(
        onClick = click,
        modifier = Modifier
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false },
        enabled = enabled,
        size = if (compact) ControlSize.Small else ControlSize.Regular,
        shape = SaltTheme.shapes.small,
        colors = ButtonColors(
            containerColor = bg,
            contentColor = textCol,
            disabledContainerColor = if (quiet) Color.Transparent else colors.background,
            disabledContentColor = colors.subText
        ),
        border = border,
        contentPadding = PaddingValues(horizontal = if (compact) 10.dp else 13.dp, vertical = if (compact) 5.dp else 7.dp)
    ) {
        BasicText(
            text,
            style = SaltTheme.textStyles.sub.copy(
                color = if (enabled) textCol else colors.subText,
                fontWeight = if (primary || selected) FontWeight.Medium else FontWeight.Normal
            ),
            maxLines = 1
        )
    }
}

@Composable
private fun SegmentedTabs(
    options: List<Pair<String, String>>,
    selected: String,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit
) {
    val skin = LocalSkin.current
    val colors = SaltTheme.colors
    val outerShape = RoundedCornerShape(8.dp)
    val itemShape = RoundedCornerShape(5.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(outerShape)
            .background(colors.background)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for ((key, label) in options) {
            val active = key == selected
            var hovered by remember(key) { mutableStateOf(false) }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(itemShape)
                    .onPointerEvent(PointerEventType.Enter) { hovered = true }
                    .onPointerEvent(PointerEventType.Exit) { hovered = false }
                    .background(when {
                        active -> skin.selectedControl
                        hovered -> skin.hover
                        else -> Color.Transparent
                    })
                    .then(if (active) Modifier.border(1.dp, colors.stroke, itemShape) else Modifier)
                    .semantics {
                        this.selected = active
                    }
                    .clickable(role = Role.Tab) { onSelect(key) },
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    label,
                    style = SaltTheme.textStyles.sub.copy(
                        color = if (active) colors.text else colors.subText,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun EqualPresetTile(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    click: () -> Unit
) {
    val skin = LocalSkin.current
    val colors = SaltTheme.colors
    val isDark = SaltTheme.configs.isDarkTheme
    var hovered by remember { mutableStateOf(false) }
    val bg = when {
        selected -> colors.highlight.copy(alpha = if (isDark) 0.24f else 0.13f)
        hovered -> skin.hover
        else -> colors.background
    }
    val border = if (selected) BorderStroke(1.dp, SolidColor(colors.highlight.copy(alpha = 0.50f))) else null
    val textCol = if (selected) colors.highlight else colors.text
    BasicButton(
        onClick = click,
        modifier = modifier
            .height(34.dp)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false },
        size = ControlSize.Regular,
        shape = SaltTheme.shapes.small,
        colors = ButtonColors(
            containerColor = bg,
            contentColor = textCol,
            disabledContainerColor = colors.background,
            disabledContentColor = colors.subText
        ),
        border = border,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
    ) {
        BasicText(
            text,
            style = SaltTheme.textStyles.sub.copy(
                color = textCol,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SwitchTile(
    barColor: Color,
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit
) {
    val skin = LocalSkin.current
    val colors = SaltTheme.colors
    val shape = SaltTheme.shapes.small
    var hovered by remember { mutableStateOf(false) }
    Row(
        modifier
            .height(36.dp)
            .clip(shape)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .background(if (hovered && enabled) skin.hover else colors.background)
            .clickable(enabled = enabled, role = Role.Switch, onClick = onToggle)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(barColor))
            BasicText(
                title,
                style = SaltTheme.textStyles.sub.copy(
                    color = if (enabled) colors.text else colors.subText,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switcher(state = checked)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InlinePicker(
    tracks: List<MediaItem>,
    label: String,
    current: MediaItem?,
    choose: (MediaItem) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val s = LocalSkin.current
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Label(current?.let { "$label · ${it.title} · ${it.artist}" } ?: "$label · 请选择", small = true)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(s.surface)
                .border(0.5.dp, s.line, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            if (query.isBlank()) Label("输入歌名或歌手筛选…", small = true)
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "搜索$label" },
                singleLine = true,
                textStyle = s.sub.copy(color = s.text),
                cursorBrush = SolidColor(s.accent)
            )
        }
        val matches = remember(query, tracks) {
            tracks.asSequence()
                .filter { query.isBlank() || it.title.contains(query, true) || it.artist.contains(query, true) }
                .take(6)
                .toList()
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (t in matches) {
                Chip("${t.title} · ${t.artist}", selected = t.id == current?.id, compact = true) {
                    query = ""
                    choose(t)
                }
            }
        }
    }
}

internal data class SpwPlaylistTrack(
    val item: MediaItem,
    val syncTrackId: String,
    val memberIndex: Int = 0,
    val rowKey: String = syncTrackId
)
internal data class PlaylistMember(val syncTrackId: String, val path: String?)

private val playlistCoverSize = 38.dp
private val playlistTrackHeight = 56.dp
private val playlistTransitionHeight = 28.dp
private val playlistSidePadding = 10.dp
private val playlistRowPadding = 8.dp
private val playlistHandleWidth = 16.dp
private val playlistHandleHeight = 24.dp

private class PlaylistReorder(
    val tracks: List<MediaItem>,
    val keys: List<String>,
    val move: (Int, Int, onRejected: () -> Unit) -> Unit
)

private class PlaylistDragOrigin(val key: String, val index: Int, val track: MediaItem, val y: Float, val grab: Float)

private data class PlaylistDragPosition(val origin: PlaylistDragOrigin, val y: Float, val scrolled: Float = 0f) {
    fun offset(stride: Float, count: Int): Float =
        (y - origin.y + scrolled).coerceIn(-origin.index * stride, (count - 1 - origin.index) * stride)

    fun target(stride: Float, count: Int): Int = origin.index + (offset(stride, count) / stride).roundToInt()
}

private class PlaylistDrop(
    val position: PlaylistDragPosition,
    val previousKeys: List<String>,
    val expectedKeys: List<String>,
    val settleOffset: Float
)

private class PlaylistRowDrag {
    var position by mutableStateOf<PlaylistDragPosition?>(null)
    var drop by mutableStateOf<PlaylistDrop?>(null)

    fun displayPosition(keys: List<String>): PlaylistDragPosition? =
        position ?: drop?.takeIf { it.previousKeys == keys }?.position
}

private fun playlistRowKey(id: String) = "track:$id"

internal fun playlistDragScrollVelocity(y: Float, height: Float, edge: Float, maxSpeed: Float): Float {
    val zone = minOf(edge, height / 3f)
    if (zone <= 0f) return 0f
    val up = ((zone - y) / zone).coerceIn(0f, 1f)
    val down = ((y - (height - zone)) / zone).coerceIn(0f, 1f)
    return maxSpeed * (down * down - up * up)
}

@Composable
private fun PlaylistScrollArea(
    state: LazyListState,
    modifier: Modifier,
    drag: PlaylistRowDrag,
    reorder: PlaylistReorder?,
    rowKeys: List<String>,
    covers: PlaylistCoverStore,
    content: LazyListScope.() -> Unit
) {
    val skin = LocalSkin.current
    val density = LocalDensity.current
    val currentReorder by rememberUpdatedState(reorder)
    val origin = drag.position?.origin
    val pendingDrop = drag.drop
    LaunchedEffect(pendingDrop, rowKeys) {
        if (pendingDrop != null && rowKeys != pendingDrop.previousKeys) {
            // Let the newly positioned row consume its settle offset even when other rows are lazy.
            withFrameNanos { }
            if (drag.drop === pendingDrop) drag.drop = null
        }
    }
    LaunchedEffect(state, origin, density) {
        if (origin == null) return@LaunchedEffect
        val edge = with(density) { 56.dp.toPx() }
        val maxSpeed = with(density) { 720.dp.toPx() }
        var previous = withFrameNanos { it }
        while (drag.position?.origin === origin) {
            val now = withFrameNanos { it }
            val seconds = ((now - previous) / 1_000_000_000f).coerceIn(0f, 0.04f)
            previous = now
            val position = drag.position?.takeIf { it.origin === origin } ?: break
            val speed = playlistDragScrollVelocity(position.y, state.layoutInfo.viewportSize.height.toFloat(), edge, maxSpeed)
            if ((speed < 0f && state.canScrollBackward) || (speed > 0f && state.canScrollForward)) {
                var consumed = 0f
                state.scroll { consumed = scrollBy(speed * seconds) }
                drag.position?.takeIf { it.origin === origin }?.let {
                    drag.position = it.copy(scrolled = it.scrolled + consumed)
                }
            }
        }
    }
    Box(modifier.clipToBounds().pointerInput(state, reorder?.keys) {
        val stride = (playlistTrackHeight.roundToPx() + playlistTransitionHeight.roundToPx()).toFloat()
        val rowHeight = playlistTrackHeight.roundToPx().toFloat()
        val handleLeft = (playlistSidePadding.roundToPx() + playlistRowPadding.roundToPx()).toFloat()
        val handleRight = handleLeft + playlistHandleWidth.roundToPx()
        val handleHalfHeight = playlistHandleHeight.roundToPx() / 2f
        awaitPointerEventScope {
            while (true) {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val source = currentReorder?.takeIf { it.keys.size > 1 } ?: continue
                if (drag.drop != null || down.isConsumed || down.position.x !in handleLeft..handleRight ||
                    (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed)) continue
                val layout = state.layoutInfo
                val item = layout.visibleItemsInfo.firstOrNull {
                    val center = it.offset - layout.viewportStartOffset + rowHeight / 2f
                    (it.key as? String)?.startsWith("track:") == true && abs(down.position.y - center) <= handleHalfHeight
                } ?: continue
                val rowKey = (item.key as String).removePrefix("track:")
                val index = source.keys.indexOf(rowKey)
                val track = source.tracks.getOrNull(index) ?: continue
                val grab = down.position.y - (item.offset - layout.viewportStartOffset)
                val picked = PlaylistDragOrigin(rowKey, index, track, down.position.y, grab)
                down.consume()

                // The viewport owns the gesture because the original lazy row can leave composition.
                try {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) break
                        change.consume()
                        val y = change.position.y.coerceIn(0f, size.height.toFloat())
                        if (!change.pressed || (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed)) {
                            val released = drag.position?.takeIf { it.origin === picked }?.copy(y = y) ?: break
                            val target = released.target(stride, source.keys.size)
                            drag.position = null
                            val latest = currentReorder?.takeIf { it.keys == source.keys }
                            if (latest != null && target != index) {
                                val expected = source.keys.toMutableList().apply { add(target, removeAt(index)) }
                                val drop = PlaylistDrop(released, source.keys, expected,
                                    released.offset(stride, source.keys.size) - (target - index) * stride)
                                drag.drop = drop
                                latest.move(index, target) { if (drag.drop === drop) drag.drop = null }
                            }
                            break
                        }
                        val active = drag.position
                        if (active?.origin === picked) {
                            drag.position = active.copy(y = y)
                        } else if (abs(change.position.y - down.position.y) >= viewConfiguration.touchSlop) {
                            drag.position = PlaylistDragPosition(picked, y)
                        }
                    }
                } finally {
                    if (drag.position?.origin === picked) drag.position = null
                }
            }
        }
    }) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(start = playlistSidePadding, end = playlistSidePadding, top = 8.dp, bottom = 16.dp),
            content = content
        )
        if (state.canScrollForward || state.canScrollBackward) {
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(state),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                    .padding(top = 4.dp, end = 2.dp, bottom = 4.dp)
                    .semantics { contentDescription = "播放列表滚动条" },
                style = ScrollbarStyle(
                    minimalHeight = 24.dp,
                    thickness = 7.dp,
                    shape = RoundedCornerShape(4.dp),
                    hoverDurationMillis = 150,
                    unhoverColor = skin.muted.copy(alpha = 0.52f),
                    hoverColor = skin.text.copy(alpha = 0.72f)
                )
            )
        }
        drag.displayPosition(rowKeys)?.let { position ->
            val shape = RoundedCornerShape(8.dp)
            Row(
                Modifier.fillMaxWidth().offset {
                    val bottom = (state.layoutInfo.viewportSize.height - playlistTrackHeight.roundToPx()).coerceAtLeast(0)
                    IntOffset(0, (position.y - position.origin.grab).roundToInt().coerceIn(0, bottom))
                }.padding(horizontal = playlistSidePadding).height(playlistTrackHeight)
                    .graphicsLayer {
                        shadowElevation = 8.dp.toPx()
                        this.shape = shape
                        clip = false
                    }.background(skin.surface, shape).border(1.dp, skin.accent, shape)
                    .padding(horizontal = playlistRowPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val track = position.origin.track
                PlaylistCover(track.id, covers, playlistCoverSize)
                Column(Modifier.weight(1f)) {
                    BasicText(track.title, style = skin.sub.copy(color = skin.text, fontWeight = FontWeight.Medium),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(track.artist, style = skin.sub.copy(color = skin.muted),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun PlaylistTrackDetails(duration: Long, metadata: TrackAnalysisMetadata?) {
    val skin = LocalSkin.current
    Column(
        Modifier.width(78.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(Modifier.height(16.dp), contentAlignment = Alignment.CenterEnd) {
            metadata?.bpmLabel?.let { bpm ->
                BasicText(bpm, style = skin.sub.copy(color = skin.text, fontSize = 11.sp, fontWeight = FontWeight.Medium))
            }
        }
        Row(
            Modifier.height(18.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            metadata?.camelot?.let { code ->
                val shape = RoundedCornerShape(4.dp)
                BasicText(
                    code,
                    modifier = Modifier.clip(shape).background(skin.accent.copy(alpha = 0.15f))
                        .border(0.5.dp, skin.accent.copy(alpha = 0.25f), shape).padding(horizontal = 4.dp, vertical = 1.dp),
                    style = skin.sub.copy(color = skin.accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                )
            }
            BasicText(fmtClock(duration / 1000.0), style = skin.sub.copy(color = skin.muted, fontSize = 11.sp))
        }
    }
}

@Composable
private fun SavedTransitionRow(
    itemA: MediaItem,
    itemB: MediaItem,
    plan: Plan?,
    selected: Boolean,
    covers: PlaylistCoverStore,
    onSelect: () -> Unit
) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(10.dp)
    var hovered by remember(itemA.id, itemB.id) { mutableStateOf(false) }
    val background = when {
        selected -> skin.accent.copy(alpha = if (skin.isDark) 0.22f else 0.10f).compositeOver(skin.surface)
        hovered -> if (skin.pageBackground.alpha < 1f) skin.hover else skin.hover.copy(alpha = 0.45f).compositeOver(skin.surface)
        else -> skin.surface
    }
    val border = if (selected) skin.accent.copy(alpha = 0.55f) else skin.line
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .background(background)
            .border(1.dp, border, shape)
            .clickable(role = Role.RadioButton, onClick = onSelect)
            .semantics { this[SemanticsProperties.Selected] = selected }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (track in listOf(itemA, itemB)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    PlaylistCover(track.id, covers, 24.dp)
                    BasicText(
                        track.title,
                        style = skin.sub.copy(color = skin.text, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (plan != null) {
            Column(
                Modifier.widthIn(max = 88.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                BasicText(
                    planName(plan),
                    style = skin.sub.copy(color = if (selected) skin.accent else skin.text, fontSize = 11.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (plan.hasOverlap) {
                    BasicText("${plan.bars} 小节", style = skin.sub.copy(color = skin.muted, fontSize = 11.sp), maxLines = 1)
                }
            }
        }
    }
}

internal data class SpwPlaylist(
    val id: String,
    val title: String,
    val tracks: List<SpwPlaylistTrack>,
    val members: List<PlaylistMember>,
    val trackSort: Int = 0,
    val descending: Boolean = false
)

private fun fmtClock(sec: Double): String {
    val total = max(0, sec.roundToInt())
    return "%d:%02d".format(java.util.Locale.ROOT, total / 60, total % 60)
}

private val Plan.hasOverlap get() = !useHost && !noMix

private fun planName(p: Plan): String = when {
    p.useHost -> "沿用 SPW"
    p.noMix -> "无过渡效果"
    else -> Plan.presets.firstOrNull { it.matches(p) }?.label ?: Plan.volumes[p.volume] ?: "自定义"
}

private fun planSummary(p: Plan): String =
    if (p.hasOverlap) "${planName(p)} · ${p.bars} 小节" else planName(p)

private fun nearestIn(arr: DoubleArray, target: Double): Pair<Double, Double>? {
    if (arr.isEmpty()) return null
    val index = arr.binarySearch(target)
    if (index >= 0) return arr[index] to 0.0
    val next = -index - 1
    val before = arr[(next - 1).coerceAtLeast(0)]
    val after = arr[next.coerceAtMost(arr.lastIndex)]
    val nearest = if (abs(before - target) <= abs(after - target)) before else after
    return nearest to abs(nearest - target)
}

private fun usesMovableSwap(plan: Plan): Boolean = plan.usesSwap

private fun swapRange(window: TransitionWindow?): ClosedFloatingPointRange<Double> {
    val lo = max(0.05, window?.phaseStart ?: 0.05)
    val hi = min(0.95, window?.phaseEnd ?: 0.95)
    return if (hi - lo >= 0.02) lo..hi else 0.05..0.95
}

private const val maxDownbeatSnapSeconds = 0.045
private const val maxBeatSnapSeconds = 0.018

private data class CueAlignment(val cue: Double, val strong: Boolean) {
    fun result() = cue to if (strong) "已对齐交接区强拍" else "已对齐交接区拍点"
}

private fun snapAlignedCue(
    movingIdx: Int,
    rawCue: Double,
    minCue: Double,
    maxCue: Double,
    plan: Plan,
    waveA: TrackWaves,
    waveB: TrackWaves,
    downbeatTolSec: Double,
    beatTolSec: Double
): CueAlignment? {
    val dur = plan.duration
    val moving = if (movingIdx == 0) waveA else waveB
    val fixed = if (movingIdx == 0) waveB else waveA
    val fixedCue = if (movingIdx == 0) plan.cueB else plan.cueA
    val lengthA = (waveA.duration * 1000.0).roundToLong()
    val lengthB = (waveB.duration * 1000.0).roundToLong()

    fun findBest(movingArr: DoubleArray, fixedArr: DoubleArray, tolSec: Double): Double? {
        var best: Double? = null
        var cueDistance = Double.POSITIVE_INFINITY
        var centerDistance = Double.POSITIVE_INFINITY
        for (fixedBeat in fixedArr) {
            val offset = fixedBeat - fixedCue
            if (offset < 0.0) continue
            if (offset > dur) break
            val movingBeat = nearestIn(movingArr, rawCue + offset) ?: break
            if (movingBeat.second > tolSec) continue
            val cue = movingBeat.first - offset
            if (cue !in minCue..maxCue) continue
            val candidate = if (movingIdx == 0) plan.copy(cueA = cue) else plan.copy(cueB = cue)
            val window = candidate.window(lengthA, lengthB) ?: continue
            val start = window.leadMs / 1000.0
            val end = (window.leadMs + window.durationMs) / 1000.0
            if (offset !in start..end) continue
            val distance = abs(offset - (start + end) * 0.5)
            if (movingBeat.second < cueDistance - 0.001 ||
                (abs(movingBeat.second - cueDistance) <= 0.001 && distance < centerDistance)) {
                best = cue
                cueDistance = movingBeat.second
                centerDistance = distance
            }
        }
        return best
    }

    findBest(moving.downbeats, fixed.downbeats, downbeatTolSec)?.let { return CueAlignment(it, strong = true) }
    return findBest(moving.beats, fixed.beats, beatTolSec)?.let { CueAlignment(it, strong = false) }
}

private fun snapCue(
    movingIdx: Int,
    rawCue: Double,
    minCue: Double,
    maxCue: Double,
    plan: Plan,
    waveA: TrackWaves,
    waveB: TrackWaves,
    downbeatTolSec: Double,
    beatTolSec: Double
): Pair<Double, String>? {
    snapAlignedCue(movingIdx, rawCue, minCue, maxCue, plan, waveA, waveB, downbeatTolSec, beatTolSec)
        ?.let { return it.result() }
    val moving = if (movingIdx == 0) waveA else waveB

    fun localBeat(beats: DoubleArray, tolerance: Double, label: String): Pair<Double, String>? {
        val (time, distance) = nearestIn(beats, rawCue) ?: return null
        return if (distance <= tolerance && time in minCue..maxCue) time to label else null
    }

    return localBeat(moving.downbeats, downbeatTolSec, "仅吸附本轨强拍")
        ?: localBeat(moving.beats, beatTolSec, "仅吸附本轨拍点")
}

private suspend fun PointerInputScope.detectHistoryDragGestures(
    onDragStart: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDrag: (PointerInputChange, Offset) -> Unit,
) {
    var dragging = false
    fun finish() {
        if (!dragging) return
        dragging = false
        onDragEnd()
    }
    try {
        detectDragGestures(
            onDragStart = {
                dragging = true
                onDragStart(it)
            },
            onDragEnd = ::finish,
            onDragCancel = ::finish,
            onDrag = onDrag,
        )
    } finally {
        finish()
    }
}

@Composable
private fun StackedTimelineMinimap(
    waveA: TrackWaves,
    waveB: TrackWaves,
    plan: Plan,
    palette: StagePalette,
    skin: Skin,
    onJumpCue: (idx: Int, rawSec: Double, snap: Boolean) -> Unit,
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit
) {
    val curPlan by rememberUpdatedState(plan)
    val mover by rememberUpdatedState(onJumpCue)
    val startDrag by rememberUpdatedState(onDragStart)
    val endDrag by rememberUpdatedState(onDragEnd)
    val envelopeA = remember(waveA.peaks) { WaveformEnvelope(waveA.peaks, useRms = true) }
    val envelopeB = remember(waveB.peaks) { WaveformEnvelope(waveB.peaks, useRms = true) }

    val centerSec = plan.cueA + plan.duration * 0.5
    val halfSpan = maxOf(waveA.duration, waveB.duration, 60.0) * 1.02
    val tMin = centerSec - halfSpan
    val totalSpan = 2.0 * halfSpan
    val bStart = plan.cueA - plan.cueB

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(palette.overviewBg)
            .border(0.5.dp, skin.line, RoundedCornerShape(8.dp))
            .pointerInput(waveA, waveB) {
                detectTapGestures { pos ->
                    if (size.width <= 0) return@detectTapGestures
                    val p = curPlan
                    val pMin = (p.cueA + p.duration * 0.5) - halfSpan
                    val tHit = pMin + (pos.x / size.width) * totalSpan
                    val halfDur = p.duration * 0.5
                    if (pos.y <= size.height / 2f) {
                        mover(0, tHit - halfDur, true)
                    } else {
                        mover(1, tHit - halfDur - (p.cueA - p.cueB), true)
                    }
                }
            }
            .pointerInput(waveA, waveB) {
                var dragIdx = 0
                var dragStartCue = 0.0
                var cumDx = 0f
                detectHistoryDragGestures(
                    onDragStart = { start ->
                        startDrag()
                        dragIdx = if (start.y <= size.height / 2f) 0 else 1
                        dragStartCue = if (dragIdx == 0) curPlan.cueA else curPlan.cueB
                        cumDx = 0f
                    },
                    onDragEnd = { endDrag() }
                ) { change, delta ->
                    if (size.width <= 0) return@detectHistoryDragGestures
                    change.consume()
                    cumDx += delta.x
                    val rawCue = dragStartCue - (cumDx / size.width) * totalSpan
                    mover(dragIdx, rawCue, curPlan.snap)
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val edgeInset = 1.dp.toPx()
        if (w <= 0f || h <= 2 * edgeInset) return@Canvas
        val halfH = h / 2f
        clipRect {
            drawPath(envelopeA.path(tMin, totalSpan, w, halfH * 0.5f, halfH * 0.38f), palette.overviewPeak)
            drawPath(envelopeB.path(tMin - bStart, totalSpan, w, halfH * 1.5f, halfH * 0.38f),
                palette.waveMid.copy(alpha = 0.72f))
        }

        drawLine(skin.line.copy(alpha = 0.6f), Offset(0f, halfH), Offset(w, halfH), 0.5.dp.toPx())

        val winW = maxOf((plan.duration / totalSpan * w).toFloat(), 16.dp.toPx()).coerceAtMost(w)
        val winX = (w - winW) * 0.5f
        val cr = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        drawRoundRect(palette.overviewWin, Offset(winX, edgeInset), Size(winW, h - 2 * edgeInset), cr)
        drawRoundRect(
            skin.accent.copy(alpha = 0.72f),
            Offset(winX, edgeInset),
            Size(winW, h - 2 * edgeInset),
            cr,
            style = Stroke(width = 1.2.dp.toPx())
        )
    }
}

@Composable
private fun BeatGridSeam(
    waveA: TrackWaves,
    waveB: TrackWaves,
    plan: Plan,
    palette: StagePalette,
    skin: Skin,
    playheadSec: Double?,
    previewMixStart: Double
) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(palette.wellBg)
    ) {
        val w = size.width
        val h = size.height
        val mixL = 0.12f * w
        val mixW = 0.76f * w
        drawRect(palette.mixBg, Offset(mixL, 0f), Size(mixW, h))
        drawRect(palette.mixTint, Offset(mixL, 0f), Size(mixW, h))

        val totalBeats = 4 * plan.bars
        for (b in 0..totalBeats) {
            val gx = mixL + mixW * b / totalBeats
            drawLine(
                if (b % 4 == 0) palette.barGrid else palette.beatGrid,
                Offset(gx, 0f),
                Offset(gx, h),
                if (b % 4 == 0) 1.0f else 0.7f
            )
        }

        val dur = plan.duration
        val uMin = -0.16 * dur
        val uMax = 1.16 * dur
        fun uToX(u: Double): Float = (0.12f + 0.76f * (u / dur).toFloat()) * w

        val dotYA = 6.75.dp.toPx()
        val dotYB = 17.25.dp.toPx()
        val beatDotCol = skin.muted.copy(alpha = 0.34f)

        fun drawBeatGrid(wave: TrackWaves, cue: Double, centerY: Float) {
            val halfTick = 3.75.dp.toPx()
            for (beat in wave.beats) {
                val offset = beat - cue
                if (offset < uMin) continue
                if (offset > uMax) break
                val x = uToX(offset)
                if (wave.downbeats.binarySearch(beat) >= 0) {
                    drawLine(
                        palette.playhead,
                        Offset(x, centerY - halfTick),
                        Offset(x, centerY + halfTick),
                        2.8.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                } else {
                    drawCircle(beatDotCol, 1.7.dp.toPx(), Offset(x, centerY))
                }
            }
        }

        drawRect(palette.outsideDim, Offset.Zero, Size(mixL, h))
        drawRect(palette.outsideDim, Offset(mixL + mixW, 0f), Size(w - mixL - mixW, h))

        drawBeatGrid(waveA, plan.cueA, dotYA)
        drawBeatGrid(waveB, plan.cueB, dotYB)

        val edgeCol = skin.accent.copy(alpha = 0.28f)
        drawLine(edgeCol, Offset(mixL, 0f), Offset(mixL, h), 1.dp.toPx())
        drawLine(edgeCol, Offset(mixL + mixW, 0f), Offset(mixL + mixW, h), 1.dp.toPx())

        if (playheadSec != null) {
            val p = (playheadSec - previewMixStart) / dur
            val px = mixL + mixW * p.toFloat()
            if (px in 0f..w) {
                drawLine(palette.playhead, Offset(px, 0f), Offset(px, h), 2.0.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeckStage(
    trackA: MediaItem,
    trackB: MediaItem,
    covers: PlaylistCoverStore,
    waveA: TrackWaves,
    waveB: TrackWaves,
    deckHeight: Dp,
    tempoPair: String?,
    plan: Plan,
    editPlan: Plan,
    audibleWindow: TransitionWindow?,
    lengthA: Long,
    lengthB: Long,
    canAudition: Boolean,
    snapEnabled: Boolean,
    emphasizedParameter: TransitionParameter?,
    playheadSec: Double?,
    previewMixStart: Double,
    isPlaying: Boolean,
    rendering: Boolean,
    onToggleAudition: () -> Unit,
    onUpdate: (Plan) -> Unit,
    onEditStart: () -> Unit,
    onEditEnd: () -> Unit,
    onSeekPreview: (Double) -> Unit,
    editActions: @Composable () -> Unit
) {
    val skin = LocalSkin.current
    val palette = remember(skin) { stagePalette(skin) }
    val curPlan by rememberUpdatedState(editPlan)
    val gainKnots = remember(plan, audibleWindow) { audibleWindow?.gains(plan).orEmpty() }
    val setter by rememberUpdatedState(onUpdate)
    val beginEdit by rememberUpdatedState(onEditStart)
    val endEdit by rememberUpdatedState(onEditEnd)
    val seeker by rememberUpdatedState(onSeekPreview)
    val snapOn by rememberUpdatedState(snapEnabled)
    var activeSnapMsg by remember { mutableStateOf<String?>(null) }
    var draggingDeck by remember { mutableStateOf(false) }
    var snapMsgSeq by remember { mutableStateOf(0) }

    LaunchedEffect(activeSnapMsg, snapMsgSeq, draggingDeck) {
        if (activeSnapMsg != null && !draggingDeck) {
            delay(2000L)
            activeSnapMsg = null
        }
    }

    fun moveCue(idx: Int, pos: Double, p: Plan): Plan {
        val clamped = pos.coerceIn(p.cueBounds(idx, lengthA, lengthB))
        return if (idx == 0) p.copy(cueA = clamped) else p.copy(cueB = clamped)
    }

    @Composable
    fun TrackInfoRow(
        idx: Int,
        tw: TrackWaves,
        track: MediaItem
    ) {
        val cue = if (idx == 0) plan.cueA else plan.cueB
        val bpm = if (idx == 0) plan.bpmA else plan.bpmB
        val cueLabel = when {
            idx == 1 && cue <= -1.0 -> "0:00（延迟 ${(-cue).toInt()} 秒）"
            idx == 1 && cue < -0.05 -> "0:00（延迟不到 1 秒）"
            else -> fmtClock(floor(cue))
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlaylistCover(track.id, covers, playlistCoverSize)
                Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText(
                        track.title,
                        style = skin.sub.copy(color = skin.text, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (track.artist.isNotBlank()) {
                        BasicText(
                            track.artist,
                            style = skin.sub.copy(color = skin.muted, fontSize = 11.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                BasicText(
                    cueLabel,
                    style = skin.sub.copy(color = skin.muted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText("${bpm.roundToInt()} BPM", style = skin.sub.copy(color = skin.text, fontWeight = FontWeight.Medium))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(skin.accent.copy(alpha = if (tw.keyLabel != null) 0.12f else 0f))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    BasicText(
                        tw.keyLabel ?: "调性未知",
                        style = skin.sub.copy(color = if (tw.keyLabel == null) skin.muted else skin.text, fontSize = 11.sp),
                        maxLines = 1
                    )
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(skin.background)
                        .border(0.5.dp, skin.line, RoundedCornerShape(5.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    BasicText("${"%.1f".format(tw.lufs)} LUFS", style = skin.sub.copy(color = skin.muted, fontSize = 11.sp))
                }
            }
        }
    }

    @Composable
    fun DeckCanvas(idx: Int, tw: TrackWaves) {
        val cue = if (idx == 0) plan.cueA else plan.cueB
        val span = plan.duration / 0.76
        val left = cue - 0.12 * span
        val low = remember(tw) {
            WaveformEnvelope(IntArray(tw.peaks.size) { i -> max(tw.peaks[i], tw.low.getOrElse(i) { 0 }) })
        }
        val mid = remember(tw.mid) { WaveformEnvelope(tw.mid) }
        val high = remember(tw.high) { WaveformEnvelope(tw.high) }

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(deckHeight)
                .background(palette.wellBg)
                .semantics { contentDescription = if (idx == 0) "拖动A轨波形调整退出点" else "拖动B轨波形调整进入点" }
                .pointerInput(idx, tw) {
                    detectTapGestures { pos ->
                        if (size.width <= 0) return@detectTapGestures
                        val p = (pos.x / size.width - 0.12f) / 0.76f
                        seeker(p * curPlan.duration)
                    }
                }
                .pointerInput(idx, tw, lengthA, lengthB) {
                    var dragStartCue = 0.0
                    var dragStartSwap = 0.5
                    var draggingSwap = false
                    var cumDx = 0f
                    detectHistoryDragGestures(
                        onDragStart = { startOffset ->
                            beginEdit()
                            draggingDeck = true
                            dragStartCue = if (idx == 0) curPlan.cueA else curPlan.cueB
                            val range = swapRange(curPlan.window(lengthA, lengthB))
                            dragStartSwap = curPlan.swap.coerceIn(range)
                            cumDx = 0f
                            val sx = (0.12f + 0.76f * dragStartSwap.toFloat()) * size.width
                            val hitX = 18.dp.toPx()
                            val hitY = 20.dp.toPx()
                            val nearHandleEdge = if (idx == 0) startOffset.y < hitY else startOffset.y > size.height - hitY
                            draggingSwap = usesMovableSwap(curPlan) &&
                                abs(startOffset.x - sx) < hitX && nearHandleEdge
                        },
                        onDragEnd = {
                            draggingDeck = false
                            activeSnapMsg = null
                            endEdit()
                        }
                    ) { change, delta ->
                        if (size.width <= 0) return@detectHistoryDragGestures
                        change.consume()
                        cumDx += delta.x
                        val mixWidth = size.width * 0.76
                        if (draggingSwap) {
                            val range = swapRange(curPlan.window(lengthA, lengthB))
                            var rawSwap = (dragStartSwap + cumDx / mixWidth).coerceIn(range)
                            var snapMsg: String? = null
                            if (snapOn) {
                                for (k in 1 until curPlan.bars) {
                                    val anchor = k.toDouble() / curPlan.bars
                                    if (anchor in range && abs(rawSwap - anchor) * mixWidth <= 6.dp.toPx()) {
                                        rawSwap = anchor
                                        snapMsg = if (k * 2 == curPlan.bars) "已吸附中点 50%" else "已吸附第 $k 小节"
                                        break
                                    }
                                }
                            }
                            activeSnapMsg = snapMsg
                            setter(curPlan.copy(swap = rawSwap))
                        } else {
                            val curSpan = curPlan.duration / 0.76
                            val bounds = curPlan.cueBounds(idx, lengthA, lengthB)
                            val minC = bounds.start
                            val maxC = bounds.endInclusive
                            val rawCue = (dragStartCue - cumDx / size.width * curSpan).coerceIn(minC, maxC)
                            var finalCue = rawCue
                            var snapMsg: String? = null
                            if (snapOn) {
                                val secondsPerPixel = curPlan.duration / mixWidth
                                val dbTol = minOf(6.dp.toPx() * secondsPerPixel, maxDownbeatSnapSeconds)
                                val bTol = minOf(3.dp.toPx() * secondsPerPixel, maxBeatSnapSeconds)
                                val hit = snapCue(idx, rawCue, minC, maxC, curPlan, waveA, waveB, dbTol, bTol)
                                if (hit != null) {
                                    finalCue = hit.first
                                    snapMsg = hit.second
                                }
                            }
                            activeSnapMsg = snapMsg
                            setter(moveCue(idx, finalCue, curPlan))
                        }
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            val mixL = 0.12f * w
            val mixW = 0.76f * w
            val mixR = mixL + mixW
            drawRect(palette.mixBg, Offset(mixL, 0f), Size(mixW, h))
            drawRect(palette.mixTint, Offset(mixL, 0f), Size(mixW, h))

            val deckBpm = if (idx == 0) plan.bpmA else plan.bpmB
            val beatStep = 60.0 / deckBpm.coerceIn(30.0, 300.0)
            val maxBeats = floor(plan.duration / beatStep + 1e-6).toInt()
            for (b in 0..maxBeats) {
                val frac = (b * beatStep / plan.duration).toFloat()
                if (frac > 1.001f) break
                val gx = mixL + mixW * frac.coerceIn(0f, 1f)
                drawLine(
                    if (b % 4 == 0) palette.barGrid else palette.beatGrid,
                    Offset(gx, 0f),
                    Offset(gx, h),
                    if (b % 4 == 0) 1.2f else 0.7f
                )
            }

            val midY = h / 2f
            val sourceStartX = ((-left / span) * w).toFloat().coerceIn(0f, w)
            val sourceEndX = (((tw.duration - left) / span) * w).toFloat().coerceIn(0f, w)
            if (sourceStartX < sourceEndX) {
                clipRect(left = sourceStartX, right = sourceEndX) {
                    drawPath(low.path(left, span, w, midY, h * 0.44f), palette.waveLow)
                    drawPath(mid.path(left, span, w, midY, h * 0.39f), palette.waveMid)
                    drawPath(high.path(left, span, w, midY, h * 0.25f), palette.waveHigh)
                }
            }
            drawLine(skin.line, Offset(0f, midY), Offset(w, midY), 0.5.dp.toPx())

            drawRect(palette.outsideDim, Offset.Zero, Size(mixL, h))
            drawRect(palette.outsideDim, Offset(mixR, 0f), Size(w - mixR, h))

            val edgeCol = skin.accent.copy(alpha = 0.28f)
            drawLine(edgeCol, Offset(mixL, 0f), Offset(mixL, h), 1.dp.toPx())
            drawLine(edgeCol, Offset(mixR, 0f), Offset(mixR, h), 1.dp.toPx())

            fun opacity(parameter: TransitionParameter): Float =
                if (emphasizedParameter == null || emphasizedParameter == parameter) 1f else 0.22f

            fun plotCurve(parameter: TransitionParameter, color: Color, points: List<Pair<Double, Double>>) {
                if (points.isEmpty()) return
                val alpha = opacity(parameter)
                val width = if (emphasizedParameter == parameter) 2.8.dp else 2.2.dp
                val curveStroke = Stroke(width.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val haloStroke = Stroke((width + 1.6.dp).toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val haloColor = if (skin.isDark) Color(0xA6000000) else Color(0x700F172A)
                val path = Path()
                points.forEachIndexed { index, (progress, v) ->
                    val pt = Offset(mixL + mixW * progress.toFloat(), h * (0.91f - 0.82f * v.toFloat()))
                    if (index == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                }
                drawPath(path, haloColor.copy(alpha = haloColor.alpha * alpha), style = haloStroke)
                drawPath(path, color.copy(alpha = color.alpha * alpha), style = curveStroke)
            }

            val flat = listOf(0.0 to 0.50, 1.0 to 0.50)
            if (audibleWindow != null) {
                // Keep the bars fixed on screen while placing the audible envelope at its actual phase.
                val gains = buildList {
                    if (audibleWindow.phaseStart > 0.0) add(0.0 to if (idx == 0) 1.0 else 0.0)
                    for ((progress, a, b) in gainKnots) add(audibleWindow.phase(progress) to if (idx == 0) a else b)
                    if (audibleWindow.phaseEnd < 1.0) add(1.0 to if (idx == 0) 0.0 else 1.0)
                }
                plotCurve(TransitionParameter.VOLUME, palette.curveVol, gains)
                if (plan.eq != "none") {
                    val bands = if (plan.previewOnlyEq) EqBand.entries.asReversed() else listOf(EqBand.Low)
                    for (band in bands) {
                        val color = when (band) {
                            EqBand.Low -> palette.curveEq
                            EqBand.Mid -> palette.curveEq.copy(alpha = 0.72f)
                            EqBand.High -> palette.curveEq.copy(alpha = 0.48f)
                        }
                        val knots = audibleWindow.eqKnots(plan, idx, band)
                        plotCurve(TransitionParameter.EQ, color, knots.map { (progress, db) ->
                            audibleWindow.phase(progress) to (0.08 + 0.42 * 10.0.pow(db / 20.0))
                        })
                    }
                } else if (emphasizedParameter == TransitionParameter.EQ) {
                    plotCurve(TransitionParameter.EQ, palette.curveEq, flat)
                }
                val sweep = plan.filterSweep(idx)
                if (sweep != null) {
                    val direction = if (sweep.lowPass) -0.40 else 0.40
                    plotCurve(TransitionParameter.FILTER, palette.curveFilter, audibleWindow.filterKnots(plan, idx).map { (progress, hz) ->
                        audibleWindow.phase(progress) to (0.50 + direction * sweep.strengthAt(hz))
                    })
                } else if (plan.noiseTail && idx == 0) {
                    val points = (0..32).map { step ->
                        val progress = audibleWindow.phase(step / 32.0)
                        progress to (0.50 + (10.0 * Plan.noiseGain(progress)).coerceAtMost(0.40))
                    }
                    plotCurve(TransitionParameter.FILTER, palette.curveFilter, points)
                } else if (emphasizedParameter == TransitionParameter.FILTER) {
                    plotCurve(TransitionParameter.FILTER, palette.curveFilter, flat)
                }
                if (idx == 0 && (plan.effect != TransitionEffect.None || emphasizedParameter == TransitionParameter.EFFECT)) {
                    val wet = audibleWindow.effectKnots(plan.effect)
                    val scale = if (plan.effect.reverb) 0.4 else 1.0
                    plotCurve(TransitionParameter.EFFECT, skin.muted, wet.map { (progress, level) ->
                        audibleWindow.phase(progress) to (0.10 + scale * level)
                    })
                }
            }

            if (usesMovableSwap(plan)) {
                val displaySwap = plan.swap.coerceIn(swapRange(audibleWindow))
                val sx = mixL + mixW * displaySwap.toFloat()
                val handleAlpha = opacity(
                    if (plan.eq != "none") TransitionParameter.EQ
                    else if (plan.filter != "none") TransitionParameter.FILTER
                    else TransitionParameter.VOLUME
                )
                drawLine(palette.curveEq.copy(alpha = 0.65f * handleAlpha), Offset(sx, 0f), Offset(sx, h), 1.2.dp.toPx())
                val hw = 12.dp.toPx()
                val hh = 5.dp.toPx()
                drawRoundRect(
                    palette.curveEq.copy(alpha = handleAlpha),
                    topLeft = Offset(sx - hw / 2f, if (idx == 0) 2.dp.toPx() else h - hh - 2.dp.toPx()),
                    size = Size(hw, hh),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
            }

            if (playheadSec != null) {
                val p = (playheadSec - previewMixStart) / plan.duration
                val px = (mixL + mixW * p.toFloat())
                if (px in 0f..w) {
                    drawLine(palette.playhead, Offset(px, 0f), Offset(px, h), 2.0.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        }
    }

    SaltCard {
        // 1. Centered-box draggable overview minimap at the top.
        StackedTimelineMinimap(
            waveA,
            waveB,
            plan,
            palette,
            skin,
            onJumpCue = { idx, rawSec, doSnap ->
                val bounds = curPlan.cueBounds(idx, lengthA, lengthB)
                val minC = bounds.start
                val maxC = bounds.endInclusive
                val clamped = rawSec.coerceIn(minC, maxC)
                val hit = if (doSnap && snapOn) {
                    snapCue(idx, clamped, minC, maxC, curPlan, waveA, waveB, maxDownbeatSnapSeconds, maxBeatSnapSeconds)
                } else null
                activeSnapMsg = hit?.second
                snapMsgSeq++
                setter(moveCue(idx, hit?.first ?: clamped, curPlan))
            },
            onDragStart = {
                beginEdit()
                draggingDeck = true
            },
            onDragEnd = {
                draggingDeck = false
                activeSnapMsg = null
                endEdit()
            }
        )

        // 2. Track A info row directly above Deck A.
        TrackInfoRow(0, waveA, trackA)

        // 3. Panoramic Dual-Deck Well with center Play/Stop and bottom Bar pill.
        val wellShape = RoundedCornerShape(10.dp)
        val glassReady = !LocalAutoMixPageEntering.current && !LocalAutoMixPageClosing.current
        val stageGlass = rememberGlassSource()
        Box(
            Modifier
                .fillMaxWidth()
                .clip(wellShape)
                .border(1.dp, skin.line, wellShape)
        ) {
            Column(Modifier.fillMaxWidth().then(if (glassReady) Modifier.glassSource(stageGlass) else Modifier)) {
                DeckCanvas(0, waveA)
                BeatGridSeam(waveA, waveB, plan, palette, skin, playheadSec, previewMixStart)
                DeckCanvas(1, waveB)
            }

            // Center Floating Audition Pill (Play triangle / Stop square) with SaltUI Glass.
            val btnShape = RoundedCornerShape(10.dp)
            var auditionHovered by remember { mutableStateOf(false) }
            BasicButton(
                onClick = onToggleAudition,
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(42.dp)
                    .height(30.dp)
                    .then(if (glassReady) Modifier.glass(stageGlass, shape = btnShape) else Modifier)
                    .onPointerEvent(PointerEventType.Enter) { auditionHovered = true }
                    .onPointerEvent(PointerEventType.Exit) { auditionHovered = false }
                    .semantics { contentDescription = if (isPlaying || rendering) "停止试听" else "试听过渡" },
                enabled = canAudition,
                size = ControlSize.Small,
                shape = btnShape,
                colors = ButtonColors(
                    containerColor = when {
                        glassReady && auditionHovered && canAudition -> skin.hover.copy(alpha = 0.45f)
                        glassReady -> Color.Transparent
                        auditionHovered && canAudition -> skin.hover.compositeOver(skin.popupBackground())
                        else -> skin.popupBackground().copy(alpha = 0.94f)
                    },
                    contentColor = skin.text,
                    disabledContainerColor = if (glassReady) Color.Transparent else skin.popupBackground(),
                    disabledContentColor = skin.muted
                ),
                border = if (glassReady) BorderStroke(0.5.dp, SolidColor(skin.line.copy(alpha = 0.65f)))
                    else BorderStroke(1.dp, SolidColor(skin.line)),
                contentPadding = PaddingValues(0.dp),
                indication = null
            ) {
                Canvas(Modifier.size(12.dp)) {
                    val w = size.width
                    val h = size.height
                    if (isPlaying || rendering) {
                        drawRoundRect(
                            color = skin.accent,
                            topLeft = Offset(w * 0.16f, h * 0.16f),
                            size = Size(w * 0.68f, h * 0.68f),
                            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                        )
                    } else {
                        val tri = Path().apply {
                            moveTo(w * 0.22f, h * 0.12f)
                            lineTo(w * 0.88f, h * 0.50f)
                            lineTo(w * 0.22f, h * 0.88f)
                            close()
                        }
                        drawPath(tri, color = skin.text)
                    }
                }
            }

            // Bottom-Center Floating Bar Count Pill (cycles 2 -> 4 -> 8 -> 2) with SaltUI Glass.
            val barShape = RoundedCornerShape(14.dp)
            var barHovered by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
                    .then(if (glassReady) Modifier.glass(stageGlass, shape = barShape) else Modifier)
                    .clip(barShape)
                    .onPointerEvent(PointerEventType.Enter) { barHovered = true }
                    .onPointerEvent(PointerEventType.Exit) { barHovered = false }
                    .background(when {
                        glassReady && barHovered -> skin.hover.copy(alpha = 0.45f)
                        glassReady -> Color.Transparent
                        else -> skin.popupBackground().copy(alpha = 0.92f)
                    })
                    .border(
                        if (glassReady) 0.5.dp else 0.8.dp,
                        if (glassReady) skin.line.copy(alpha = 0.65f) else skin.line,
                        barShape
                    )
                    .clickable {
                        val nextBars = when (curPlan.bars) {
                            1 -> 2
                            2 -> 4
                            4 -> 8
                            else -> 1
                        }
                        onUpdate(curPlan.withBars(nextBars, lengthA, lengthB))
                    }
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                BasicText(
                    "${plan.bars} 个小节",
                    style = skin.sub.copy(color = skin.text, fontWeight = FontWeight.Medium)
                )
            }
        }

        // 4. Track B info row directly below Deck B.
        TrackInfoRow(1, waveB, trackB)

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                val effectLegend = if (plan.effect != TransitionEffect.None || emphasizedParameter == TransitionParameter.EFFECT) " · 灰效果" else ""
                val swapLegend = if (usesMovableSwap(plan)) " · 竖线拖动调交接点" else ""
                Label("波形：蓝低频 · 橙中频 · 白高频  |  曲线：青音量 · 黄均衡 · 紫滤波$effectLegend$swapLegend", small = true)
                (activeSnapMsg ?: tempoPair)?.let { msg ->
                    BasicText(msg, style = skin.sub.copy(color = skin.accent, fontWeight = FontWeight.Medium))
                }
            }
            editActions()
        }
    }
}

private fun normPathStr(p: String): String = p.trim().replace('/', '\\').lowercase()

@Composable
private fun EditorHeader(
    skin: Skin,
    plugin: MainPlugin,
    statusText: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = if (onBack != null) 12.dp else 16.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                BasicScreenDefaults.BackButton(
                    onBack = onBack,
                    modifier = Modifier.semantics { contentDescription = "返回" }
                )
            }
            SaltText(
                "AutoMix",
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            var statusHovered by remember { mutableStateOf(false) }
            Row(
                Modifier
                    .weight(1f, fill = false)
                    .clip(skin.smallShape)
                    .onPointerEvent(PointerEventType.Enter) { statusHovered = true }
                    .onPointerEvent(PointerEventType.Exit) { statusHovered = false }
                    .background(if (statusHovered) skin.hover else Color.Transparent)
                    .clickable { MainPlugin.showStatus() }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val activeDot = plugin.bridge != null
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (activeDot) skin.success else skin.muted.copy(alpha = 0.5f)))
                BasicText(
                    plugin.status,
                    style = skin.sub.copy(color = skin.muted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (statusText.isNotBlank()) {
                BasicText(
                    statusText,
                    modifier = Modifier.widthIn(max = 220.dp).padding(end = 4.dp),
                    style = skin.sub.copy(color = skin.muted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            actions()
            plugin.updates?.let { AutoMixAboutAction(plugin.pluginContext.pluginVersion, it) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NativeEditor(plugin: MainPlugin, skin: Skin, onBack: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val savedPlans by plugin.plans.collectAsState()
    val host by plugin.hosts.collectAsState()
    val saltColors = remember(skin) { skin.toSaltColors() }
    val saltTextStyles = remember(skin) {
        SaltTextStyles(
            main = skin.main,
            sub = skin.sub,
            paragraph = skin.paragraph,
            largeTitle = skin.largeTitle
        )
    }
    val saltShapes = remember(skin) {
        SaltShapes(
            small = skin.smallShape,
            medium = skin.cardShape,
            large = skin.largeShape
        )
    }
    SaltTheme(
        configs = SaltConfigs.default(isDarkTheme = skin.isDark),
        dynamicColors = SaltDynamicColors(light = saltColors, dark = saltColors),
        textStyles = saltTextStyles,
        shapes = saltShapes
    ) {
    CompositionLocalProvider(LocalSkin provides skin) {
        val cache = plugin.uiCache
        val algorithm by plugin.algorithm.collectAsState()
        var algorithmNote by remember { mutableStateOf("") }
        val pageEntering = LocalAutoMixPageEntering.current
        val pageClosing = LocalAutoMixPageClosing.current
        val canCommitState by rememberUpdatedState(!pageEntering && !pageClosing)

        var library by remember(cache) { mutableStateOf(cache.library) }
        var queue by remember(cache) { mutableStateOf(cache.queue) }
        val queueEntries = queue.entries
        val queueTracks = remember(queueEntries) { queueEntries.map { it.item } }
        var playlists by remember(cache) { mutableStateOf(cache.playlists) }
        var selectedPlaylistId by remember(cache) { mutableStateOf(cache.selectedPlaylistId) }
        var playlistNote by remember { mutableStateOf("") }
        var scanJob by remember { mutableStateOf<Job?>(null) }
        var scanProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        var scanningQueue by remember { mutableStateOf(false) }
        var applyingQueueOrder by remember { mutableStateOf(false) }
        var scanNote by remember { mutableStateOf("") }
        var pinnedPair by remember(cache) { mutableStateOf(cache.pinnedPair) }
        val following = pinnedPair == null
        val (a, b, selectedPairKeys) = EditorPair.displayed(queue, pinnedPair)
        var playlistTab by remember(cache) { mutableStateOf(cache.playlistTab) }
        val queueScroll = rememberLazyListState()
        val playlistScroll = if (playlistTab == "queue") queueScroll else key(
            playlistTab, selectedPlaylistId.takeIf { playlistTab == "playlists" }
        ) {
            rememberLazyListState()
        }
        val trackMetadata = cache.trackMetadata
        var metadataRevision by remember { mutableStateOf(0) }
        val covers = cache.covers
        var message by remember(cache) { mutableStateOf(if (cache.initialized) "" else "正在读取队列与曲库…") }
        var queueNote by remember { mutableStateOf("") }
        var refreshing by remember(cache) { mutableStateOf(!cache.initialized) }
        var movingQueue by remember { mutableStateOf(false) }
        var movingPlaylist by remember { mutableStateOf(false) }
        fun showPair(selection: EditorPair?) {
            pinnedPair = selection
            cache.pinnedPair = selection
        }

        fun selectPair(pair: Pair<MediaItem, MediaItem>, keys: Pair<String, String>? = null) {
            showPair(EditorPair(pair.first, pair.second, keys))
        }

        fun selectTab(tab: String) {
            playlistTab = tab
            cache.playlistTab = tab
        }

        suspend fun awaitMotionSettled() {
            if (!canCommitState) snapshotFlow { canCommitState }.first { it }
        }

        suspend fun buildSpwPlaylists(bridge: HostPlayback, tracks: List<MediaItem>): List<SpwPlaylist> =
            withContext(Dispatchers.IO) {
                val sources = bridge.loadPlaylists()
                val byId = tracks.associateBy { it.id }
                val byPath = tracks.associateBy { normPathStr(it.path) }
                sources.map { source ->
                    val entries = source.tracks.map { track ->
                        val item = (track.id?.let(byId::get)) ?: track.path?.let { byPath[normPathStr(it)] }
                        PlaylistMember(track.syncTrackId, item?.path ?: track.path?.takeIf { it.isNotBlank() }) to item
                    }
                    val seenSyncIds = mutableMapOf<String, Int>()
                    val mapped = entries.mapIndexedNotNull { memberIndex, (entry, item) ->
                        item?.let {
                            val occurrence = seenSyncIds[entry.syncTrackId] ?: 0
                            seenSyncIds[entry.syncTrackId] = occurrence + 1
                            SpwPlaylistTrack(it, entry.syncTrackId, memberIndex, "${entry.syncTrackId}#$occurrence")
                        }
                    }
                    SpwPlaylist(source.id, source.title, mapped, entries.map { it.first }, source.sort, source.descending)
                }
            }

        suspend fun loadSpwPlaylists(tracks: List<MediaItem>) {
            if (tracks.isEmpty()) {
                val cleared = playlists.map { playlist ->
                    playlist.copy(tracks = emptyList(), members = playlist.members.map { it.copy(path = null) })
                }
                playlists = cleared
                cache.playlists = cleared
            }
            val bridge = host ?: return
            try {
                val parsed = buildSpwPlaylists(bridge, tracks)
                awaitMotionSettled()
                playlists = parsed
                cache.playlists = parsed
                if (selectedPlaylistId == null || parsed.none { it.id == selectedPlaylistId }) {
                    val nextId = (parsed.firstOrNull { it.tracks.size >= 2 } ?: parsed.firstOrNull())?.id
                    selectedPlaylistId = nextId
                    cache.selectedPlaylistId = nextId
                }
                playlistNote = ""
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                playlistNote = e.message ?: "读取歌单失败"
            }
        }

        suspend fun refreshLists(deferCommit: Boolean = false) {
            if (!cache.initialized || !deferCommit) refreshing = true
            try {
                val tracks = withContext(Dispatchers.IO) {
                    WorkshopApi.library.getAllTracks().toCompletableFuture().get()
                        .filter { it.path.isNotBlank() && it.id.isNotBlank() }
                }
                if (deferCommit) awaitMotionSettled()
                library = tracks
                cache.library = tracks
                cache.initialized = true
                message = if (tracks.size < 2) "曲库不足两首歌曲" else ""
                loadSpwPlaylists(tracks)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                message = "读取曲库失败，请检查曲库读取权限"
            } finally {
                refreshing = false
            }
        }

        LaunchedEffect(plugin, host) { refreshLists(deferCommit = true) }

        LaunchedEffect(host, library) {
            val bridge = host ?: return@LaunchedEffect
            val states = bridge.queueStates()
            states.collect { state ->
                val next = withContext(Dispatchers.Default) { bridge.queueView(state, library) }
                awaitMotionSettled()
                // The state can advance while mapping the library or waiting for the page animation.
                if (states.value == state) {
                    queue = next
                    cache.queue = next
                }
            }
        }

        val savedPairs = remember(savedPlans, library) {
            val byId = library.associateBy { it.id }
            library.flatMap { m1 ->
                val p1 = "${m1.id.length}:${m1.id}"
                savedPlans.keys.asSequence()
                    .filter { it.startsWith(p1) }
                    .mapNotNull { k ->
                        val id2 = k.substring(p1.length)
                        byId[id2]?.let { m2 -> m1 to m2 }
                    }.toList()
            }
        }

        val activePlaylist = playlists.firstOrNull { it.id == selectedPlaylistId }
        val activePlaylistItems = remember(activePlaylist) { activePlaylist?.tracks?.map { it.item }.orEmpty() }
        val metadataPathsByKey = remember(playlistTab, queueEntries, activePlaylist) {
            when (playlistTab) {
                "queue" -> queueEntries.associate { playlistRowKey(it.queueId.toString()) to it.item.path }
                "playlists" -> activePlaylist?.tracks.orEmpty().associate { playlistRowKey(it.rowKey) to it.item.path }
                else -> emptyMap()
            }
        }
        val metadataRequest by rememberUpdatedState(Triple(metadataRevision, playlistScroll, metadataPathsByKey))
        val selectedMetadataPaths by rememberUpdatedState(listOfNotNull(a?.path, b?.path))
        LaunchedEffect(plugin) {
            snapshotFlow<Pair<Int, Set<String>>?> {
                if (!canCommitState) return@snapshotFlow null
                val (revision, scroll, pathsByKey) = metadataRequest
                revision to buildSet<String> {
                    addAll(selectedMetadataPaths)
                    scroll.layoutInfo.visibleItemsInfo.forEach { item ->
                        (item.key as? String)?.let(pathsByKey::get)?.let(::add)
                    }
                }
            }.collect { request ->
                val (_, paths) = request ?: return@collect
                for (batch in paths.chunked(30)) {
                    val before = batch.associateWith { trackMetadata[it] }
                    val cached = try {
                        plugin.worker.cachedTrackMetadata(batch)
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Exception) {
                        break
                    }
                    awaitMotionSettled()
                    val toPut = mutableMapOf<String, TrackAnalysisMetadata>()
                    val toRemove = mutableListOf<String>()
                    for (path in batch) {
                        // A pair can finish analysis while the cache lookup is in progress.
                        if (trackMetadata[path] === before[path]) {
                            val value = cached[path]
                            if (value == null) {
                                if (path in trackMetadata) toRemove += path
                            } else if (trackMetadata[path] != value) {
                                toPut[path] = value
                            }
                        }
                    }
                    if (toPut.isNotEmpty()) trackMetadata.putAll(toPut)
                    for (path in toRemove) trackMetadata.remove(path)
                }
            }
        }

        fun handleMoveQueue(fromIdx: Int, toIdx: Int, onRejected: () -> Unit = {}) {
            val bridge = plugin.bridge
            if (bridge == null || movingQueue || scanningQueue || fromIdx !in queueEntries.indices ||
                toIdx !in queueEntries.indices || fromIdx == toIdx) {
                onRejected()
                return
            }
            val from = queueEntries[fromIdx]
            val to = queueEntries[toIdx]
            val order = queue.order
            movingQueue = true
            queueNote = "正在同步队列顺序…"
            scope.launch {
                var confirmed = false
                try {
                    confirmed = bridge.moveQueueItem(order, from, to)
                    if (confirmed) bridge.rescheduleAutomix()
                    queueNote = if (confirmed) "已同步 SPW 播放队列顺序" else "队列顺序未确认，请刷新后重试"
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    queueNote = "播放队列调整失败 · ${e.cause?.message ?: e.message ?: e.javaClass.simpleName}"
                } finally {
                    if (!confirmed) onRejected()
                    movingQueue = false
                }
            }
        }

        fun handleMovePlaylist(playlistId: String, fromIdx: Int, toIdx: Int, onRejected: () -> Unit = {}) {
            if (movingPlaylist || plugin.bridge?.canReorderPlaylists != true) {
                onRejected()
                return
            }
            val pl = playlists.firstOrNull { it.id == playlistId }
            if (pl == null || scanJob != null || pl.tracks.size != pl.members.size ||
                fromIdx !in pl.tracks.indices || toIdx !in pl.tracks.indices || fromIdx == toIdx) {
                onRejected()
                return
            }
            val nextTracks = pl.tracks.toMutableList()
                .apply { add(toIdx, removeAt(fromIdx)) }
                .mapIndexed { idx, track -> track.copy(memberIndex = idx) }
            val switchedSort = pl.trackSort != 0 || pl.descending
            movingPlaylist = true
            playlistNote = "正在同步歌单顺序…"
            playlists = playlists.map {
                if (it.id == playlistId) it.copy(
                    tracks = nextTracks,
                    members = nextTracks.map { track -> PlaylistMember(track.syncTrackId, track.item.path) },
                    trackSort = 0,
                    descending = false
                ) else it
            }
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        plugin.bridge?.reorderPlaylist(playlistId, nextTracks.map { it.syncTrackId })
                            ?: Result.failure(IllegalStateException("宿主桥接未就绪"))
                    }
                    result.onSuccess {
                        playlistNote = if (switchedSort) {
                            "已同步 SPW 歌单顺序（已切换为自定义正序）"
                        } else {
                            "已同步 SPW 歌单顺序"
                        }
                    }.onFailure { err ->
                        playlistNote = "歌单排序写回失败：${err.message ?: err.javaClass.simpleName}"
                        loadSpwPlaylists(library)
                    }
                } finally {
                    movingPlaylist = false
                }
            }
        }

        fun scanTracks(paths: List<String?>, queueOrder: QueueOrder? = null) {
            if (scanJob != null) return
            val bridge = plugin.bridge
            val scanAlgorithm = algorithm
            val total = paths.size
            scanningQueue = queueOrder != null
            if (scanningQueue) queueNote = "" else scanNote = ""
            scanProgress = 0 to total
            fun report(text: String) {
                if (queueOrder == null) scanNote = text else queueNote = text
            }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val scan = plugin.worker.scanPlaylist(paths, scanAlgorithm) { done ->
                        scanProgress = done to total
                    }
                    metadataRevision++
                    if (scan.analyzed == 0) {
                        report(listOfNotNull("没有可分析的歌曲", scan.firstTrackError).joinToString(" · "))
                        return@launch
                    }
                    if (queueOrder == null) {
                        report("扫描完成 · 已分析 ${scan.analyzed}/$total 首" +
                            if (scan.analyzed < total) " · ${total - scan.analyzed} 首无法分析" else "")
                        return@launch
                    }

                    val anchored = queueOrder.fixedPrefix > 0
                    val result = plugin.worker.sequenceTracks(paths, scanAlgorithm, anchorFirst = anchored)
                    if (result.analyzed < 2) {
                        report("可分析的歌曲不足两首")
                        return@launch
                    }
                    val fixed = queueOrder.fixedPrefix
                    val offset = (fixed - 1).coerceAtLeast(0)
                    val target = queueOrder.ids.take(fixed) + result.all.map { it + offset }
                        .filter { it >= fixed }.map { queueOrder.ids[it] }
                    ensureActive()
                    applyingQueueOrder = true
                    val outcome = withContext(NonCancellable + Dispatchers.IO) {
                        checkNotNull(bridge).reorderUpcoming(queueOrder, target)
                    }
                    report(when {
                        !outcome.complete && outcome.moves > 0 -> "宿主队列已变化，已停止重排 · 已确认 ${outcome.moves} 次移动"
                        !outcome.complete -> "宿主队列已变化，请重新重排"
                        outcome.moves == 0 -> "当前队列已是推荐顺序"
                        else -> "已重排接下来 ${target.size - fixed} 首歌曲"
                    })
                } catch (e: CancellationException) {
                    if (!applyingQueueOrder) report("扫描已取消")
                    throw e
                } catch (e: Exception) {
                    val error = if (queueOrder == null) "扫描失败" else "队列重排失败"
                    report("$error · ${e.cause?.message ?: e.message ?: e.javaClass.simpleName}")
                } finally {
                    applyingQueueOrder = false
                    scanningQueue = false
                    scanProgress = null
                    scanJob = null
                }
            }
            scanJob = job
            job.start()
        }

        fun reorderUpcomingQueue() {
            if (movingQueue || refreshing || plugin.bridge?.canReorderQueue != true) return
            val snapshot = QueueOrder(queue.order, queue.currentIndex, queue.shuffled)
            if (snapshot.shuffled || snapshot.ids.size - snapshot.fixedPrefix < 2) return
            val pathsById = queue.entries.associate { it.queueId to it.item.path }
            val keys = snapshot.ids.drop((snapshot.fixedPrefix - 1).coerceAtLeast(0))
            scanTracks(keys.map(pathsById::get), snapshot)
        }

        fun playFromList(action: (HostPlayback) -> Unit) {
            val bridge = plugin.bridge ?: return
            try {
                plugin.worker.stopPreview()
                action(bridge)
                showPair(null)
            } catch (e: Exception) {
                val reason = e.cause?.message ?: e.message ?: "宿主播放接口不可用"
                WorkshopApi.ui.toast("播放歌曲失败 · $reason", WorkshopApi.Ui.ToastType.Error)
            }
        }

        fun resolveHighlightedPair(
            tracks: List<MediaItem>,
            rowKeys: List<String>,
            currentTrackIndex: Int,
            isAdjacent: (Int) -> Boolean,
            wrapAround: Boolean
        ): Pair<Int, Int> {
            val itemA = a
            val itemB = b
            if (itemA == null && itemB == null) return -1 to -1
            if (itemA != null && itemB != null) {
                selectedPairKeys?.let { (keyA, keyB) ->
                    val idxA = rowKeys.indexOf(keyA)
                    val idxB = rowKeys.indexOf(keyB)
                    if (idxA in tracks.indices && idxB in tracks.indices &&
                        tracks[idxA].id == itemA.id && tracks[idxB].id == itemB.id) {
                        return idxA to idxB
                    }
                }
                if (currentTrackIndex in tracks.indices && tracks[currentTrackIndex].id == itemA.id) {
                    val nextIdx = currentTrackIndex + 1
                    if (nextIdx < tracks.size && isAdjacent(currentTrackIndex) && tracks[nextIdx].id == itemB.id) {
                        return currentTrackIndex to nextIdx
                    }
                    if (wrapAround && currentTrackIndex == tracks.lastIndex && tracks.size > 1 && tracks.first().id == itemB.id) {
                        return currentTrackIndex to 0
                    }
                }
                for (i in 0 until tracks.lastIndex) {
                    if (isAdjacent(i) && tracks[i].id == itemA.id && tracks[i + 1].id == itemB.id) {
                        return i to (i + 1)
                    }
                }
                if (wrapAround && tracks.size > 1 && tracks.last().id == itemA.id && tracks.first().id == itemB.id) {
                    return tracks.lastIndex to 0
                }
            }
            val idxA = itemA?.let { target -> tracks.indexOfFirst { it.id == target.id } } ?: -1
            val idxB = itemB?.let { target -> tracks.indexOfFirst { it.id == target.id && it.id != itemA?.id } } ?: -1
            return idxA to idxB
        }

        @OptIn(ExperimentalFoundationApi::class)
        fun LazyListScope.mixTrackItems(
            tracks: List<MediaItem>,
            rowKeys: List<String>,
            drag: PlaylistRowDrag,
            rowNumbers: List<Int> = emptyList(),
            currentTrackIndex: Int = -1,
            canReorder: Boolean,
            moveEnabled: Boolean = true,
            isAdjacent: (Int) -> Boolean = { true },
            wrapAround: Boolean = false,
            onPlay: ((Int) -> Unit)? = null,
            onMove: (fromIdx: Int, toIdx: Int) -> Unit
        ) {
            val (highlightIdxA, highlightIdxB) = resolveHighlightedPair(
                tracks, rowKeys, currentTrackIndex, isAdjacent, wrapAround
            )
            items(tracks.size, key = { playlistRowKey(rowKeys[it]) }) { idx ->
                val track = tracks[idx]
                val rowKey = rowKeys[idx]
                val palette = remember(skin) { stagePalette(skin) }
                val stridePx = with(LocalDensity.current) {
                    (playlistTrackHeight.roundToPx() + playlistTransitionHeight.roundToPx()).toFloat()
                }
                val dragPosition = drag.displayPosition(rowKeys)
                val dragOrigin = dragPosition?.origin?.index ?: -1
                val dragTargetIdx = dragPosition?.target(stridePx, tracks.size) ?: -1
                val isA = idx == highlightIdxA
                val isB = idx == highlightIdxB
                val isDragging = dragPosition?.origin?.key == rowKey
                val animY = remember { Animatable(0f) }
                var prevIdx by remember { mutableStateOf(idx) }
                val curIsDragging by rememberUpdatedState(isDragging)
                val completedDrop = drag.drop?.takeIf { it.position.origin.key == rowKey && it.expectedKeys == rowKeys }

                val dragShiftTarget = when {
                    isDragging || dragOrigin < 0 || dragTargetIdx < 0 -> 0f
                    dragOrigin < dragTargetIdx && idx in (dragOrigin + 1)..dragTargetIdx -> -stridePx
                    dragOrigin > dragTargetIdx && idx in dragTargetIdx until dragOrigin -> stridePx
                    else -> 0f
                }

                LaunchedEffect(idx, dragShiftTarget) {
                    if (prevIdx != idx) {
                        val oldIdx = prevIdx
                        prevIdx = idx
                        if (completedDrop != null) {
                            animY.snapTo(completedDrop.settleOffset)
                            animY.animateTo(0f, spring(dampingRatio = 0.80f, stiffness = 520f))
                        } else if (!curIsDragging) {
                            val continuousStart = animY.value + (oldIdx - idx) * stridePx
                            animY.snapTo(continuousStart)
                            if (abs(continuousStart - dragShiftTarget) > 0.5f) {
                                animY.animateTo(dragShiftTarget, spring(dampingRatio = 0.82f, stiffness = 500f))
                            } else {
                                animY.snapTo(dragShiftTarget)
                            }
                        }
                    } else if (!curIsDragging) {
                        if (abs(animY.value - dragShiftTarget) > 0.5f) {
                            animY.animateTo(dragShiftTarget, spring(dampingRatio = 0.82f, stiffness = 500f))
                        } else {
                            animY.snapTo(dragShiftTarget)
                        }
                    }
                }

                val rowShape = RoundedCornerShape(10.dp)
                var rowHovered by remember(rowKey) { mutableStateOf(false) }
                val rowBg = when {
                    isA -> palette.curveVol.copy(alpha = if (skin.isDark) 0.16f else 0.08f).compositeOver(skin.surface)
                    isB -> palette.curveFilter.copy(alpha = if (skin.isDark) 0.16f else 0.08f).compositeOver(skin.surface)
                    rowHovered -> if (skin.pageBackground.alpha < 1f) skin.hover
                        else skin.hover.copy(alpha = 0.45f).compositeOver(skin.surface)
                    else -> skin.surface
                }
                val rowBorder = when {
                    isA -> palette.curveVol.copy(alpha = 0.48f)
                    isB -> palette.curveFilter.copy(alpha = 0.48f)
                    else -> skin.line
                }
                val hasNext = idx + 1 < tracks.size && isAdjacent(idx) && track.id != tracks[idx + 1].id
                val hasWrap = wrapAround && idx == tracks.lastIndex && tracks.size > 1 && track.id != tracks.first().id
                val hasPrevious = idx > 0 && isAdjacent(idx - 1) && track.id != tracks[idx - 1].id
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(playlistTrackHeight)
                            .graphicsLayer {
                                translationY = animY.value
                                alpha = if (isDragging) 0f else 1f
                            }
                            .clip(rowShape)
                            .onPointerEvent(PointerEventType.Enter) { rowHovered = true }
                            .onPointerEvent(PointerEventType.Exit) { rowHovered = false }
                            .background(rowBg)
                            .border(1.dp, rowBorder, rowShape)
                            .combinedClickable(
                                enabled = (hasNext || hasWrap || hasPrevious || onPlay != null) && dragPosition == null,
                                onDoubleClick = onPlay?.let { play -> { play(idx) } },
                                onClick = {
                                    if (hasNext) selectPair(track to tracks[idx + 1], rowKey to rowKeys[idx + 1])
                                    else if (hasWrap) selectPair(track to tracks.first(), rowKey to rowKeys.first())
                                    else if (hasPrevious) selectPair(tracks[idx - 1] to track, rowKeys[idx - 1] to rowKey)
                                }
                            )
                            .padding(horizontal = playlistRowPadding, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (canReorder) {
                                Box(
                                    Modifier
                                        .width(playlistHandleWidth)
                                        .height(playlistHandleHeight)
                                        .clip(RoundedCornerShape(4.dp))
                                        .semantics { contentDescription = "拖动调整歌曲位置" },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Canvas(Modifier.size(8.dp, 13.dp)) {
                                        val dotCol = skin.muted.copy(alpha = if (rowHovered || isA || isB) 0.78f else 0.34f)
                                        val r = 1.2.dp.toPx()
                                        val xs = floatArrayOf(size.width * 0.25f, size.width * 0.75f)
                                        val ys = floatArrayOf(size.height * 0.20f, size.height * 0.50f, size.height * 0.80f)
                                        for (x in xs) for (y in ys) {
                                            drawCircle(dotCol, radius = r, center = Offset(x, y))
                                        }
                                    }
                                }
                            }
                            Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) {
                                if (idx == currentTrackIndex) {
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(skin.accent.copy(alpha = if (skin.isDark) 0.24f else 0.14f))
                                            .padding(horizontal = 3.dp, vertical = 1.dp)
                                    ) {
                                        BasicText(
                                            "当前",
                                            style = skin.sub.copy(color = skin.accent, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                                        )
                                    }
                                } else {
                                    BasicText(
                                        "${rowNumbers.getOrNull(idx) ?: (idx + 1)}",
                                        style = skin.sub.copy(
                                            color = if (isA || isB) skin.text else skin.muted,
                                            fontSize = 11.sp,
                                            fontWeight = if (isA || isB || isDragging) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                    )
                                }
                            }
                            PlaylistCover(track.id, covers, playlistCoverSize)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                BasicText(
                                    track.title,
                                    style = skin.sub.copy(
                                        color = if (idx == currentTrackIndex) skin.accent else skin.text,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (isA) {
                                        Box(
                                            Modifier
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(palette.curveVol.copy(alpha = if (skin.isDark) 0.24f else 0.14f))
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            BasicText("A", style = skin.sub.copy(color = palette.curveVol, fontSize = 9.sp, fontWeight = FontWeight.SemiBold))
                                        }
                                    }
                                    if (isB) {
                                        Box(
                                            Modifier
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(palette.curveFilter.copy(alpha = if (skin.isDark) 0.24f else 0.14f))
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            BasicText("B", style = skin.sub.copy(color = palette.curveFilter, fontSize = 9.sp, fontWeight = FontWeight.SemiBold))
                                        }
                                    }
                                    BasicText(
                                        track.artist,
                                        modifier = Modifier.weight(1f, fill = false),
                                        style = skin.sub.copy(color = skin.muted, fontSize = 11.sp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            PlaylistTrackDetails(track.duration, trackMetadata[track.path])
                        }
                        if (canReorder) {
                            val showArrows = rowHovered || isA || isB
                            Column(
                                Modifier.graphicsLayer { alpha = if (showArrows) 1f else 0f },
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                val upEnabled = showArrows && moveEnabled && dragPosition == null && idx > 0
                                val downEnabled = showArrows && moveEnabled && dragPosition == null && idx + 1 < tracks.size
                                var upHovered by remember(rowKey) { mutableStateOf(false) }
                                var downHovered by remember(rowKey) { mutableStateOf(false) }
                                Box(
                                    Modifier
                                        .size(20.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .onPointerEvent(PointerEventType.Enter) { upHovered = true }
                                        .onPointerEvent(PointerEventType.Exit) { upHovered = false }
                                        .background(if (upHovered && upEnabled) skin.background else Color.Transparent)
                                        .clickable(enabled = upEnabled) { onMove(idx, idx - 1) }
                                        .semantics { contentDescription = "上移歌曲" },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Canvas(Modifier.size(7.dp)) {
                                        val col = if (upEnabled) skin.text else skin.muted.copy(alpha = 0.35f)
                                        val stroke = 1.3.dp.toPx()
                                        drawLine(col, Offset(size.width * 0.15f, size.height * 0.70f), Offset(size.width * 0.50f, size.height * 0.25f), stroke, StrokeCap.Round)
                                        drawLine(col, Offset(size.width * 0.50f, size.height * 0.25f), Offset(size.width * 0.85f, size.height * 0.70f), stroke, StrokeCap.Round)
                                    }
                                }
                                Box(
                                    Modifier
                                        .size(20.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .onPointerEvent(PointerEventType.Enter) { downHovered = true }
                                        .onPointerEvent(PointerEventType.Exit) { downHovered = false }
                                        .background(if (downHovered && downEnabled) skin.background else Color.Transparent)
                                        .clickable(enabled = downEnabled) { onMove(idx, idx + 1) }
                                        .semantics { contentDescription = "下移歌曲" },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Canvas(Modifier.size(7.dp)) {
                                        val col = if (downEnabled) skin.text else skin.muted.copy(alpha = 0.35f)
                                        val stroke = 1.3.dp.toPx()
                                        drawLine(col, Offset(size.width * 0.15f, size.height * 0.30f), Offset(size.width * 0.50f, size.height * 0.75f), stroke, StrokeCap.Round)
                                        drawLine(col, Offset(size.width * 0.50f, size.height * 0.75f), Offset(size.width * 0.85f, size.height * 0.30f), stroke, StrokeCap.Round)
                                    }
                                }
                            }
                        }
                    }

                    if (hasNext || hasWrap) {
                        val nextIdx = if (hasNext) idx + 1 else 0
                        val t1 = track
                        val t2 = tracks[nextIdx]
                        val transitionKey = pairKey(t1.id, t2.id)
                        val savedPlan = savedPlans[transitionKey]
                        val unsaved = plugin.drafts[transitionKey]?.let { it != savedPlan } == true
                        val isEditing = idx == highlightIdxA && nextIdx == highlightIdxB
                        val emphasized = isEditing || savedPlan != null || unsaved
                        val prefix = if (hasWrap) "循环过渡" else "过渡"
                        val pillShape = RoundedCornerShape(7.dp)
                        var connHovered by remember(transitionKey, idx) { mutableStateOf(false) }
                        val pillBg = when {
                            isEditing -> skin.accent.copy(alpha = if (skin.isDark) 0.26f else 0.14f)
                            savedPlan != null || unsaved -> skin.accent.copy(alpha = if (skin.isDark) 0.16f else 0.09f)
                            connHovered -> skin.surface
                            else -> Color.Transparent
                        }
                        val pillBorder = when {
                            isEditing -> skin.accent.copy(alpha = 0.55f)
                            savedPlan != null || unsaved -> skin.accent.copy(alpha = 0.35f)
                            connHovered -> skin.line
                            else -> Color.Transparent
                        }
                        val pillTextCol = when {
                            isEditing || savedPlan != null || unsaved -> skin.accent
                            connHovered -> skin.text
                            else -> skin.muted
                        }
                        val pillText = when {
                            unsaved -> "$prefix · 未保存修改"
                            isEditing && savedPlan != null -> "正在编辑 · ${planSummary(savedPlan)}"
                            isEditing -> "正在编辑 · 自动规划"
                            savedPlan != null -> "${planSummary(savedPlan)} · 已保存"
                            hasWrap -> "循环过渡 · 自动规划"
                            else -> "自动规划"
                        }
                        val connAlpha by animateFloatAsState(
                            targetValue = if (dragPosition != null) 0.18f else 1f
                        )
                        val stemCol = when {
                            emphasized -> skin.accent.copy(alpha = 0.72f)
                            connHovered -> skin.muted.copy(alpha = 0.65f)
                            else -> skin.muted.copy(alpha = 0.28f)
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(playlistTransitionHeight)
                                .graphicsLayer { alpha = connAlpha }
                                .padding(horizontal = playlistRowPadding),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (canReorder) {
                                Spacer(Modifier.width(playlistHandleWidth))
                            }
                            Spacer(Modifier.width(26.dp))
                            Box(Modifier.width(playlistCoverSize).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                Box(Modifier.width(1.5.dp).fillMaxHeight().background(stemCol))
                                Box(
                                    Modifier
                                        .size(if (emphasized) 6.dp else 5.dp)
                                        .clip(CircleShape)
                                        .background(if (emphasized) skin.accent else if (connHovered) skin.muted else skin.line)
                                        .border(1.dp, skin.pageBackground, CircleShape)
                                )
                            }
                            Row(
                                Modifier
                                    .clip(pillShape)
                                    .onPointerEvent(PointerEventType.Enter) { connHovered = true }
                                    .onPointerEvent(PointerEventType.Exit) { connHovered = false }
                                    .background(pillBg)
                                    .then(if (pillBorder != Color.Transparent) Modifier.border(0.8.dp, pillBorder, pillShape) else Modifier)
                                    .clickable { selectPair(t1 to t2, rowKey to rowKeys[nextIdx]) }
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BasicText(
                                    pillText,
                                    modifier = Modifier.weight(1f, fill = false),
                                    style = skin.sub.copy(
                                        color = pillTextCol,
                                        fontSize = 11.sp,
                                        fontWeight = if (emphasized) FontWeight.Medium else FontWeight.Normal
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Canvas(Modifier.size(5.dp, 7.dp)) {
                                    val middle = Offset(size.width * 0.80f, size.height * 0.50f)
                                    val stroke = 1.dp.toPx()
                                    drawLine(pillTextCol, Offset(size.width * 0.25f, size.height * 0.10f), middle, stroke, StrokeCap.Round)
                                    drawLine(pillTextCol, middle, Offset(size.width * 0.25f, size.height * 0.90f), stroke, StrokeCap.Round)
                                }
                            }
                        }
                    } else if (idx + 1 < tracks.size) {
                        Spacer(Modifier.height(playlistTransitionHeight))
                    }
                }
            }
        }

        @Composable
        fun PlaylistPanel(modifier: Modifier = Modifier) {
            val curQueueIdx = queueEntries.indexOfFirst { it.displayIndex == queue.currentIndex }
            LaunchedEffect(playlistTab, curQueueIdx, pageEntering) {
                if (playlistTab == "queue" && curQueueIdx >= 0 && !pageEntering && !cache.queueScrolledToCurrent) {
                    cache.queueScrolledToCurrent = true
                    // Item 0 is queue-header; scrolling to curQueueIdx keeps the current track near the top with context.
                    queueScroll.scrollToItem(curQueueIdx)
                }
            }
            val hasQueueWrap = queueEntries.size > 1 &&
                queueEntries.first().displayIndex == 0 &&
                queueEntries.last().displayIndex == queue.order.lastIndex &&
                queueEntries.last().item.id != queueEntries.first().item.id &&
                queue.currentIndex == queue.order.lastIndex &&
                queue.nextPair?.let { it.first.id == queueEntries.last().item.id && it.second.id == queueEntries.first().item.id } == true
            val cardSummary = when (playlistTab) {
                "queue" -> {
                    val posText = if (queue.currentIndex in queue.order.indices) {
                        "当前 ${queue.currentIndex + 1}/${queue.order.size}"
                    } else "${queue.order.size} 首队列"
                    val adjacentSaved = queueTracks.zipWithNext().withIndex().count { (idx, pair) ->
                        queueEntries[idx].displayIndex + 1 == queueEntries[idx + 1].displayIndex &&
                            pairKey(pair.first.id, pair.second.id) in savedPlans
                    }
                    val wrapSaved = if (hasQueueWrap && pairKey(queueTracks.last().id, queueTracks.first().id) in savedPlans) 1 else 0
                    val savedCount = adjacentSaved + wrapSaved
                    if (savedCount == 0) posText else "$posText · 已自定义 $savedCount 处"
                }
                "saved" -> "${savedPairs.size} 组已保存过渡"
                else -> null
            }

            Column(modifier) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(
                            "播放列表",
                            modifier = Modifier.weight(1f),
                            style = skin.main.copy(color = skin.text, fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Chip(if (following) "跟随播放" else "当前转场", compact = true, quiet = true, selected = following) {
                                showPair(null)
                                selectTab("queue")
                                queueNote = ""
                                // The queue status above the tracks occupies the first list item.
                                queueScroll.requestScrollToItem(if (curQueueIdx >= 0) curQueueIdx + 1 else 0)
                            }
                            MaterialSymbolButton(MaterialSymbol.Refresh, enabled = !refreshing && !movingQueue && !movingPlaylist && scanJob == null) {
                                refreshing = true
                                queueNote = ""
                                scope.launch {
                                    refreshLists()
                                    metadataRevision++
                                }
                            }
                        }
                    }
                    SegmentedTabs(
                        options = MixAlgorithm.entries.map { it.name to it.label },
                        selected = algorithm.name
                    ) { name ->
                        algorithmNote = runCatching { plugin.selectAlgorithm(MixAlgorithm.valueOf(name)) }
                            .exceptionOrNull()?.message.orEmpty()
                    }
                    if (algorithmNote.isNotEmpty()) Label(algorithmNote, small = true)
                    SegmentedTabs(
                        options = listOf("queue" to "队列", "playlists" to "歌单", "saved" to "已保存", "custom" to "自选"),
                        selected = playlistTab,
                        onSelect = ::selectTab
                    )
                    cardSummary?.let { Label(it, small = true) }
                    if (playlistTab == "queue") {
                        if (scanningQueue) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                val progress = if (applyingQueueOrder) "正在同步队列…" else scanProgress
                                    ?.let { (done, total) -> "扫描 $done/$total" }.orEmpty()
                                Label(progress, small = true)
                                if (!applyingQueueOrder) Chip("取消", compact = true) { scanJob?.cancel() }
                            }
                        } else {
                            val fixed = (queue.currentIndex + 1).coerceIn(0, queue.order.size)
                            Chip(
                                "智能重排", primary = true, compact = true,
                                enabled = host?.canReorderQueue == true && !queue.shuffled && !refreshing &&
                                    !movingQueue && scanJob == null && queue.order.size - fixed >= 2,
                                click = ::reorderUpcomingQueue
                            )
                        }
                    }
                    if (playlistTab == "playlists") {
                        val pl = activePlaylist
                        if (playlists.isNotEmpty()) {
                            PlaylistToolbar(
                                playlists = playlists,
                                current = pl,
                                canPick = scanJob == null && !movingPlaylist,
                                canScan = !refreshing && !movingPlaylist && scanJob == null && (pl?.tracks?.size ?: 0) >= 2,
                                activity = scanProgress?.takeUnless { scanningQueue }?.let { (done, total) -> "扫描 $done/$total" },
                                onCancel = if (scanJob != null && !scanningQueue) ({ scanJob?.cancel() }) else null,
                                onPick = {
                                    selectedPlaylistId = it.id
                                    cache.selectedPlaylistId = it.id
                                    scanNote = ""
                                    playlistNote = ""
                                },
                                onScan = { pl?.let { scanTracks(it.members.map(PlaylistMember::path)) } }
                            )
                        }
                        val note = when {
                            scanNote.isNotBlank() -> scanNote
                            playlistNote.isNotBlank() -> playlistNote
                            playlists.isEmpty() -> "未读取到 SPW 歌单，可点击刷新"
                            pl != null && pl.tracks.size < 2 -> "编辑转场需要至少两首本地歌曲"
                            pl != null && pl.tracks.size != pl.members.size -> "含未匹配本地曲目，暂不支持调序"
                            else -> null
                        }
                        note?.let { Label(it, small = true) }
                    }
                }

                SaltDivider()

                val rowDrag = remember(playlistScroll) { PlaylistRowDrag() }
                val queueKeys = remember(queueEntries) { queueEntries.map { it.queueId.toString() } }
                val playlistKeys = remember(activePlaylist) { activePlaylist?.tracks?.map { it.rowKey }.orEmpty() }
                val canMoveQueue = !queue.shuffled && !movingQueue && !refreshing && !scanningQueue
                val canReorderPlaylist = activePlaylist?.let {
                    host?.canReorderPlaylists == true && it.tracks.size == it.members.size
                } == true
                val canMovePlaylist = !movingPlaylist && !refreshing && scanJob == null
                val reorder = when {
                    playlistTab == "queue" && canMoveQueue -> PlaylistReorder(queueTracks, queueKeys, ::handleMoveQueue)
                    playlistTab == "playlists" && activePlaylist != null && canReorderPlaylist && canMovePlaylist ->
                        PlaylistReorder(activePlaylistItems, playlistKeys) { from, to, onRejected ->
                            handleMovePlaylist(activePlaylist.id, from, to, onRejected)
                        }
                    else -> null
                }
                val visibleKeys = when (playlistTab) {
                    "queue" -> queueKeys
                    "playlists" -> playlistKeys
                    else -> emptyList()
                }
                PlaylistScrollArea(playlistScroll, Modifier.weight(1f).fillMaxWidth(), rowDrag, reorder, visibleKeys, covers) {
                    when (playlistTab) {
                        "queue" -> {
                            item("queue-header") {
                                Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (queue.shuffled) Label("随机播放顺序 · 关闭随机播放后可调序", small = true)
                                    Label(queue.nextPairNote, small = true)
                                    if (queueNote.isNotBlank() && queueNote != queue.nextPairNote) Label(queueNote, small = true)
                                    if (queueTracks.isEmpty() && queue.order.isNotEmpty()) {
                                        Label("队列里的歌曲未匹配到本地曲库", small = true)
                                    }
                                }
                            }
                            mixTrackItems(
                                tracks = queueTracks,
                                rowKeys = queueKeys,
                                drag = rowDrag,
                                rowNumbers = queueEntries.map { it.displayIndex + 1 },
                                currentTrackIndex = curQueueIdx,
                                canReorder = !queue.shuffled,
                                moveEnabled = canMoveQueue,
                                isAdjacent = { i -> queueEntries[i].displayIndex + 1 == queueEntries[i + 1].displayIndex },
                                wrapAround = hasQueueWrap,
                                onPlay = if (host?.canPlayTracks == true) { index ->
                                    playFromList { it.playQueueTrack(queueEntries[index]) }
                                } else null,
                                onMove = { fromIdx, toIdx -> handleMoveQueue(fromIdx, toIdx) }
                            )
                        }
                        "playlists" -> {
                            val pl = activePlaylist
                            if (pl != null && pl.tracks.isNotEmpty()) {
                                mixTrackItems(
                                    tracks = activePlaylistItems,
                                    rowKeys = playlistKeys,
                                    drag = rowDrag,
                                    rowNumbers = pl.tracks.map { it.memberIndex + 1 },
                                    canReorder = canReorderPlaylist,
                                    moveEnabled = canMovePlaylist,
                                    isAdjacent = { i -> pl.tracks[i].memberIndex + 1 == pl.tracks[i + 1].memberIndex },
                                    onPlay = if (host?.canPlayTracks == true) { index ->
                                        playFromList { it.playPlaylistTrack(activePlaylistItems, index) }
                                    } else null,
                                    onMove = { fromIdx, toIdx -> handleMovePlaylist(pl.id, fromIdx, toIdx) }
                                )
                            }
                        }
                        "saved" -> {
                            if (savedPairs.isEmpty()) {
                                item("saved-empty") { Label("暂无已保存的曲对过渡", small = true) }
                            } else {
                                items(savedPairs.size, key = { index ->
                                    val (sA, sB) = savedPairs[index]
                                    "saved:" + pairKey(sA.id, sB.id)
                                }) { idx ->
                                    val (sA, sB) = savedPairs[idx]
                                    val isSel = a?.id == sA.id && b?.id == sB.id
                                    Box(Modifier.padding(bottom = 6.dp)) {
                                        SavedTransitionRow(sA, sB, savedPlans[pairKey(sA.id, sB.id)], isSel, covers) {
                                            selectPair(sA to sB)
                                        }
                                    }
                                }
                            }
                        }
                        "custom" -> item("custom-picker") {
                            if (library.isNotEmpty()) {
                                Column(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(skin.background)
                                        .border(0.5.dp, skin.line, RoundedCornerShape(8.dp)).padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    InlinePicker(library.filter { it.id != b?.id }, "A · 退出曲", a) {
                                        showPair(EditorPair(it, b))
                                    }
                                    InlinePicker(library.filter { it.id != a?.id }, "B · 进入曲", b) {
                                        showPair(EditorPair(a, it))
                                    }
                                }
                            } else {
                                Label("请先等待曲库加载完成", small = true)
                            }
                        }
                    }
                }
            }
        }

        PopupMenuBackdrop(Modifier.fillMaxSize(), enabled = !pageEntering && !pageClosing) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val playlistModifier = Modifier.width((maxWidth * 0.31f).coerceIn(310.dp, 376.dp)).fillMaxHeight()
                val aa = a
                val bb = b
                if (aa != null && bb != null) {
                    key(aa.id, bb.id) {
                        PairWorkspace(
                            plugin = plugin,
                            a = aa,
                            b = bb,
                            covers = covers,
                            tempoPair = trackMetadata[aa.path]?.let { from ->
                                trackMetadata[bb.path]?.let(from::tempoPair)
                            },
                            savedPlan = savedPlans[pairKey(aa.id, bb.id)],
                            algorithm = algorithm,
                            onBack = onBack,
                            onTrackAnalyzed = { path, metadata -> trackMetadata[path] = metadata },
                            playlistPanel = { PlaylistPanel(playlistModifier) }
                        )
                    }
                } else {
                    val scroll = rememberScrollState()
                    Column(Modifier.fillMaxSize()) {
                        EditorHeader(skin = skin, plugin = plugin, statusText = message, onBack = onBack)
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            Box(
                                Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll),
                                contentAlignment = Alignment.TopCenter
                            ) {
                                Column(
                                    Modifier
                                        .widthIn(max = 1100.dp)
                                        .fillMaxWidth()
                                        .padding(start = 20.dp, end = 10.dp, top = 8.dp, bottom = 20.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    val emptyNote = if (following) queue.nextPairNote else "从右侧选齐两首歌后即可编辑转场"
                                    SaltCard { Label(message.ifBlank { emptyNote }, small = true) }
                                }
                            }
                            PlaylistPanel(playlistModifier)
                        }
                    }
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PairWorkspace(
    plugin: MainPlugin,
    a: MediaItem,
    b: MediaItem,
    covers: PlaylistCoverStore,
    tempoPair: String?,
    savedPlan: Plan?,
    algorithm: MixAlgorithm,
    onBack: (() -> Unit)?,
    onTrackAnalyzed: (String, TrackAnalysisMetadata) -> Unit,
    playlistPanel: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val skin = LocalSkin.current
    val scroll = rememberScrollState()
    val host by plugin.hosts.collectAsState()
    val liveHighPass = host?.liveHighPass?.collectAsState()?.value == true
    val pageEntering = LocalAutoMixPageEntering.current
    val pageClosing = LocalAutoMixPageClosing.current
    val canCommitState by rememberUpdatedState(!pageEntering && !pageClosing)

    val planKey = pairKey(a.id, b.id)
    val history = remember(plugin.uiCache, planKey) {
        plugin.uiCache.editHistories[planKey] ?: TransitionEditHistory()
    }
    val snapshotKey = PairSnapshotKey(planKey, a.path, b.path, algorithm)
    val cachedPair = remember(plugin.uiCache, snapshotKey) { plugin.uiCache.pairCache[snapshotKey] }
    var analyzing by remember(snapshotKey) { mutableStateOf(cachedPair == null) }
    var rendering by remember { mutableStateOf(false) }
    var waveA by remember(planKey) { mutableStateOf(cachedPair?.waveA) }
    var waveB by remember(planKey) { mutableStateOf(cachedPair?.waveB) }
    var autoPlan by remember(snapshotKey) { mutableStateOf(cachedPair?.autoPlan) }
    var reasons by remember(snapshotKey) { mutableStateOf(cachedPair?.reasons.orEmpty()) }
    var openParam by remember { mutableStateOf<TransitionParameter?>(null) }
    var hoveredPlan by remember { mutableStateOf<Plan?>(null) }
    var showReasons by remember { mutableStateOf(false) }
    var task by remember { mutableStateOf<Job?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var playheadSec by remember { mutableStateOf<Double?>(null) }
    var writingPlan by remember { mutableStateOf(false) }
    val pendingPosition = remember { AtomicReference<Double?>(null) }
    val draft = plugin.drafts[planKey]
    val p = draft ?: savedPlan ?: autoPlan
    fun effectiveLengthMs(item: MediaItem, wave: TrackWaves?): Long {
        val decodedMs = wave?.duration?.takeIf { it > 0.0 }?.let { (it * 1000.0).roundToLong() }
        return when {
            decodedMs == null -> item.duration
            item.duration <= 0L -> decodedMs
            abs(item.duration - decodedMs) <= 2500L -> min(item.duration, decodedMs)
            else -> decodedMs
        }
    }
    val lengthA = effectiveLengthMs(a, waveA)
    val lengthB = effectiveLengthMs(b, waveB)
    val window = p?.window(lengthA, lengthB)
    val dirty = draft != null && draft != savedPlan
    val idleStatus by rememberUpdatedState(when {
        dirty -> "有未保存的修改"
        savedPlan != null -> "已加载保存设置"
        else -> "已自动规划"
    })
    var status by remember(planKey) {
        mutableStateOf(if (cachedPair != null) idleStatus else "正在分析双轨…")
    }

    fun setOpenParameter(parameter: TransitionParameter?) {
        hoveredPlan = null
        openParam = parameter
    }

    fun currentEdit(): TransitionEdit {
        val plan = plugin.drafts[planKey] ?: plugin.plans.value[planKey] ?: autoPlan
        return TransitionEdit(plan, history.selection)
    }

    DisposableEffect(plugin, a.id, b.id) {
        onDispose {
            history.finishGesture(currentEdit())
            plugin.worker.stopPreview()
        }
    }

    LaunchedEffect(plugin, a.id, b.id, pageEntering, pageClosing) {
        if (pageEntering || pageClosing) return@LaunchedEffect
        var wasPlaying = false
        while (true) {
            val running = plugin.worker.isPlaying()
            if (wasPlaying && !running && status == "试听中") {
                status = idleStatus
            }
            wasPlaying = running
            isPlaying = running
            playheadSec = if (running) plugin.worker.positionSeconds() else null
            delay(if (running) 33L else 180L)
        }
    }

    LaunchedEffect(snapshotKey) {
        task?.cancel()
        plugin.worker.stopPreview()
        rendering = false
        isPlaying = false
        playheadSec = null
        hoveredPlan = null
        analyzing = cachedPair == null
        status = if (analyzing) "正在自动分析双轨…" else idleStatus
        try {
            val data = plugin.worker.analyzePair(a.path, b.path, algorithm)
            fun DspTrack.metadata() = TrackAnalysisMetadata(profile.bpm, profile.keyName, profile.camelot)
            fun DspTrack.displayWave(metadata: TrackAnalysisMetadata): TrackWaves {
                return TrackWaves(
                    peaks = waveform.peaks,
                    low = waveform.low,
                    mid = waveform.mid,
                    high = waveform.high,
                    beats = profile.beats,
                    downbeats = profile.downbeats,
                    duration = profile.duration,
                    lufs = profile.lufs,
                    keyLabel = metadata.keyLabel
                )
            }
            val metadataA = data.a.metadata()
            val metadataB = data.b.metadata()
            val nextWaveA = data.a.displayWave(metadataA)
            val nextWaveB = data.b.displayWave(metadataB)
            val snapshot = PairAnalysisSnapshot(nextWaveA, nextWaveB, data.recommendation.plan, data.recommendation.reasons)
            if (!canCommitState) snapshotFlow { canCommitState }.first { it }
            plugin.uiCache.pairCache[snapshotKey] = snapshot
            waveA = nextWaveA
            waveB = nextWaveB
            autoPlan = snapshot.autoPlan
            onTrackAnalyzed(a.path, metadataA)
            onTrackAnalyzed(b.path, metadataB)
            reasons = snapshot.reasons
            status = idleStatus
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            status = e.cause?.message ?: e.message ?: "分析失败"
        } finally {
            analyzing = false
        }
    }

    fun stopAudition() {
        task?.cancel()
        task = null
        plugin.worker.stopPreview()
        isPlaying = false
        playheadSec = null
        rendering = false
    }

    val isAutoMatch = autoPlan != null && p == autoPlan
    val matchedPreset = p?.let { current -> Plan.presets.firstOrNull { it.matches(current) } }
    val editingCustom = history.selection.custom
    val preferPresetId = history.selection.presetId
    val preferredPreset = preferPresetId?.let { id -> matchedPreset?.takeIf { it.id == id } }
    val isAutoSelected = !editingCustom && isAutoMatch && preferredPreset == null
    val activePresetId = when {
        editingCustom || isAutoSelected -> null
        preferredPreset != null -> preferredPreset.id
        else -> matchedPreset?.id
    }
    val isCustom = editingCustom || (!isAutoSelected && activePresetId == null)

    fun applyPlan(next: Plan?, note: String? = null) {
        stopAudition()
        val committed = plugin.plans.value[planKey]
        val clean = next == (committed ?: autoPlan)
        if (clean || next == null) plugin.drafts.remove(planKey)
        else plugin.drafts[planKey] = next
        status = when {
            note != null -> note
            next == null -> if (analyzing) "正在自动分析双轨…" else "暂无可用的转场方案"
            clean -> if (committed != null) "已加载保存设置" else "已应用自动推荐"
            else -> "有未保存的修改"
        }
    }

    fun recordEdit(before: TransitionEdit, after: TransitionEdit) {
        if (before != after) {
            plugin.uiCache.editHistories[planKey] = history
            history.record(before, after)
        }
    }

    fun adjust(next: Plan, note: String? = null, selection: TransitionSelection? = null) {
        val before = currentEdit()
        val after = TransitionEdit(next, selection ?: before.selection.afterManualChange(before.plan, next))
        recordEdit(before, after)
        if (next != before.plan || note != null) applyPlan(next, note)
    }

    fun restoreEdit(redo: Boolean) {
        val current = currentEdit()
        val restored = (if (redo) history.redo(current) else history.undo(current)) ?: return
        setOpenParameter(null)
        applyPlan(restored.plan)
    }

    @Composable
    fun <T : Any> PlanParameterMenu(
        parameter: TransitionParameter,
        color: Color,
        selected: T,
        options: Map<T, String>,
        modifier: Modifier,
        selectedLabel: String = options[selected].orEmpty(),
        withValue: (T) -> Plan
    ) {
        ParameterMenu(
            parameter = parameter,
            color = color,
            selected = selected,
            options = options,
            openParameter = if (LocalAutoMixPageClosing.current) null else openParam,
            onOpenChange = ::setOpenParameter,
            modifier = modifier,
            selectedLabel = selectedLabel,
            onHover = { option ->
                if (openParam == parameter) hoveredPlan = option?.let(withValue)
            },
            onSelect = { adjust(withValue(it)) }
        )
    }

    fun startAudition(position: Double? = null) {
        val plan = p ?: return
        val mix = window ?: return
        pendingPosition.set(position)
        if ((position != null && plugin.worker.seekSeconds(position)) || rendering) {
            return
        }
        rendering = true
        status = "正在准备试听…"
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                plugin.worker.preview(a.path, b.path, plan, mix, pendingPosition::get)
                isPlaying = plugin.worker.isPlaying()
                status = if (isPlaying) "试听中" else idleStatus
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                status = e.cause?.message ?: e.message ?: "试听失败"
            } finally {
                if (task === coroutineContext[Job]) {
                    rendering = false
                    task = null
                }
            }
        }
        task = job
        job.start()
    }

    fun toggleAudition() {
        if (plugin.worker.isPlaying() || rendering) {
            stopAudition()
            status = "已停止试听"
        } else {
            startAudition()
        }
    }

    LaunchedEffect(pageClosing) {
        if (pageClosing) {
            history.finishGesture(currentEdit())
            setOpenParameter(null)
            val auditionWasActive = rendering || plugin.worker.isPlaying()
            stopAudition()
            if (auditionWasActive) status = idleStatus
        }
    }

    val canSave = p != null && (p.useHost || p.noMix || window != null)
    val saveAction: @Composable () -> Unit = {
        if (p != null) {
            val saveLabel = when {
                writingPlan -> "写入中…"
                savedPlan == p -> "已保存"
                savedPlan != null -> "保存修改"
                else -> "保存"
            }
            Chip(saveLabel, primary = true, compact = true, enabled = !analyzing && !writingPlan && canSave && savedPlan != p) {
                writingPlan = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { plugin.save(a.id, b.id, p) }
                        if (plugin.drafts[planKey] == p) plugin.drafts.remove(planKey)
                        status = if (planKey in plugin.drafts) "已保存 · 还有未保存的修改" else "已保存"
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        status = e.message ?: "保存失败"
                    } finally {
                        writingPlan = false
                    }
                }
            }
        }
    }

    val editActions: @Composable () -> Unit = {
        val ready = !analyzing && !writingPlan && !pageClosing
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            MaterialSymbolButton(MaterialSymbol.Undo, enabled = ready && history.canUndo) { restoreEdit(redo = false) }
            MaterialSymbolButton(MaterialSymbol.Redo, enabled = ready && history.canRedo) { restoreEdit(redo = true) }
            saveAction()
        }
    }

    @Composable
    fun WaveformPlaceholder(text: String) {
        SaltCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f)) { Label(text, small = true) }
                editActions()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        EditorHeader(
            skin = skin,
            plugin = plugin,
            statusText = status,
            onBack = onBack,
            actions = {
                if (p != null && savedPlan != null) {
                    Chip("删除", compact = true, quiet = true, enabled = !writingPlan) {
                        writingPlan = true
                        scope.launch {
                            try {
                                val beforeDelete = currentEdit()
                                withContext(Dispatchers.IO) { plugin.remove(a.id, b.id) }
                                val remainingDraft = plugin.drafts[planKey]?.takeUnless { it == savedPlan }
                                if (remainingDraft != null && remainingDraft != autoPlan) {
                                    applyPlan(remainingDraft, "已删除保存方案 · 保留未保存的修改")
                                } else {
                                    val baseline = TransitionEdit(autoPlan, TransitionSelection())
                                    recordEdit(beforeDelete, baseline)
                                    applyPlan(baseline.plan, if (autoPlan == null) "已删除保存方案" else "已删除保存方案 · 恢复自动推荐")
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                status = e.message ?: "删除失败"
                            } finally {
                                writingPlan = false
                            }
                        }
                    }
                }
            }
        )

        Row(Modifier.weight(1f).fillMaxWidth()) {
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.TopCenter
            ) {
                val deckHeight = ((maxHeight - 405.dp) / 2).coerceIn(104.dp, 164.dp)
                Column(
                    Modifier
                        .widthIn(max = 1100.dp)
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                        .padding(start = 20.dp, end = 10.dp, top = 8.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                val wA = waveA
                val wB = waveB
                if (p != null && wA != null && wB != null) {
                    val palette = remember(skin) { stagePalette(skin) }

                    // 1. Hero Panoramic Stage (Centered-box draggable minimap -> Track A -> Waveforms -> Track B).
                    if (p.useHost) {
                        WaveformPlaceholder("此曲对由 SPW 按播放器设置切换，可在 SPW 播放时试听。")
                    } else if (p.noMix) {
                        WaveformPlaceholder("无过渡效果。保存后，上一首播到曲尾，下一首从头开始。")
                    } else {
                        val shown = hoveredPlan ?: p
                        val shownWindow = shown.window(lengthA, lengthB)
                        val showsAuditionTiming = shown.duration == p.duration && shown.cueA == p.cueA && shown.cueB == p.cueB
                        DeckStage(
                            trackA = a,
                            trackB = b,
                            covers = covers,
                            waveA = wA,
                            waveB = wB,
                            deckHeight = deckHeight,
                            tempoPair = tempoPair,
                            plan = shown,
                            editPlan = p,
                            audibleWindow = shownWindow,
                            lengthA = lengthA,
                            lengthB = lengthB,
                            canAudition = window != null,
                            snapEnabled = p.snap,
                            emphasizedParameter = openParam?.takeIf { it.hasCurve },
                            playheadSec = playheadSec.takeIf { showsAuditionTiming },
                            previewMixStart = window?.previewOrigin ?: 0.0,
                            isPlaying = isPlaying,
                            rendering = rendering,
                            onToggleAudition = { toggleAudition() },
                            onUpdate = { next -> adjust(next) },
                            onEditStart = { history.beginGesture(currentEdit()) },
                            onEditEnd = { history.finishGesture(currentEdit()) },
                            onSeekPreview = { sec ->
                                window?.let { startAudition((it.previewOrigin + sec).coerceAtLeast(0.0)) }
                            },
                            editActions = editActions
                        )
                        if (shownWindow == null) {
                            Label("过渡需要至少 0.2 秒可用重叠，请移动切点或调整长度。", small = true)
                        } else if (shownWindow.durationMs != shownWindow.nominalMs) {
                            val seconds = shownWindow.durationMs / 1000L
                            Label(if (seconds == 0L) "实际重叠不到 1 秒" else "实际重叠 $seconds 秒", small = true)
                        }
                    }

                    SaltCard {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicText(
                                "过渡预设",
                                style = SaltTheme.textStyles.main.copy(color = skin.text, fontWeight = FontWeight.Medium)
                            )
                            if (reasons.isNotEmpty()) {
                                Chip(if (showReasons) "收起依据" else "分析依据", selected = showReasons, compact = true, quiet = !showReasons) {
                                    showReasons = !showReasons
                                }
                            }
                        }

                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val buttonCount = Plan.presets.size + if (autoPlan == null) 1 else 2
                            val spacing = 5.dp
                            val minTileWidth = with(LocalDensity.current) {
                                (Plan.presets.maxOf { it.label.length } * 11).sp.toDp() + 12.dp
                            }
                            val contentWidth = maxOf(maxWidth, minTileWidth * buttonCount + spacing * (buttonCount - 1))
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()).width(contentWidth),
                                horizontalArrangement = Arrangement.spacedBy(spacing)
                            ) {
                                val presetTile = Modifier.weight(1f)
                                EqualPresetTile("自定义", selected = isCustom, modifier = presetTile) {
                                    setOpenParameter(null)
                                    val next = when {
                                        p.noMix -> p.copy(volume = "smooth", useHost = false)
                                        p.useHost -> p.copy(useHost = false)
                                        else -> p
                                    }
                                    adjust(next, selection = TransitionSelection(custom = true))
                                }
                                if (autoPlan != null) {
                                    EqualPresetTile("自动", selected = isAutoSelected, modifier = presetTile) {
                                        setOpenParameter(null)
                                        autoPlan?.let { adjust(it, "已应用自动推荐", TransitionSelection()) }
                                    }
                                }
                                for (preset in Plan.presets) {
                                    EqualPresetTile(
                                        preset.label,
                                        selected = activePresetId == preset.id,
                                        modifier = presetTile
                                    ) {
                                        setOpenParameter(null)
                                        adjust(preset.apply(p), "预设：${preset.label}", TransitionSelection(presetId = preset.id))
                                    }
                                }
                            }
                        }

                        if (!p.useHost && !p.noMix) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PlanParameterMenu(
                                    parameter = TransitionParameter.EFFECT,
                                    color = skin.muted,
                                    selected = p.effect,
                                    options = TransitionEffect.options,
                                    modifier = Modifier.weight(1f),
                                    selectedLabel = p.effect.label
                                ) { p.copy(effect = it) }

                                PlanParameterMenu(
                                    parameter = TransitionParameter.LENGTH,
                                    color = skin.accent,
                                    selected = p.bars,
                                    options = transitionLengths,
                                    modifier = Modifier.weight(1f)
                                ) { p.withBars(it, lengthA, lengthB) }

                                SwitchTile(
                                    barColor = palette.playhead,
                                    title = "拍点磁吸",
                                    checked = p.snap,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    val next = !p.snap
                                    val nextPlan = p.copy(snap = next)
                                    adjust(nextPlan, if (next) "已开启原速拍点磁吸" else "已关闭拍点磁吸")
                                }
                            }

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PlanParameterMenu(
                                    parameter = TransitionParameter.VOLUME,
                                    color = palette.curveVol,
                                    selected = p.volume,
                                    options = Plan.volumes,
                                    modifier = Modifier.weight(1f)
                                ) { p.copy(volume = it) }

                                PlanParameterMenu(
                                    parameter = TransitionParameter.EQ,
                                    color = palette.curveEq,
                                    selected = p.eq,
                                    options = Plan.eqs,
                                    modifier = Modifier.weight(1f)
                                ) { p.copy(eq = it, bass = it != "none") }

                                PlanParameterMenu(
                                    parameter = TransitionParameter.FILTER,
                                    color = palette.curveFilter,
                                    selected = p.filter,
                                    options = Plan.filterOptions(liveHighPass),
                                    modifier = Modifier.weight(1f)
                                ) { p.copy(filter = it) }
                            }
                            if (p.hasPreviewOnlyEffects(liveHighPass)) {
                                val info = if (p.effect.reverb) "混响仅在编辑器试听时生效。"
                                    else "标有「仅试听」的效果在编辑器试听时播放。"
                                Label(info, small = true)
                            }
                        }

                        if (showReasons && reasons.isNotEmpty()) {
                            SaltDivider()
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                for (r in reasons) Label("· $r", small = true)
                            }
                        }
                    }
                } else {
                    WaveformPlaceholder("${a.title}  →  ${b.title} · $status")
                }
            }
        }
            playlistPanel()
        }
    }
}
