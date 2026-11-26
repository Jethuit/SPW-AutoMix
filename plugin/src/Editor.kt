@file:OptIn(androidx.compose.runtime.InternalComposeApi::class, com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
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
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.WorkshopApi
import kotlinx.coroutines.*
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
    val popup: Color = surface
) {
    fun popupBackground(): Color {
        val base = if (isDark) Color(0xFF191D23) else Color.White
        return popup.compositeOver(background.compositeOver(base))
    }
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
        listOf("highlight", "text", "subText", "background", "subBackground", "stroke", "onHighlight", "popup")
            .map { getter(symbols, colorType, fields.getValue(it), "J") }
    }
    private val fonts = styleType.constructorProperties("main", "sub", "paragraph", "largeTitle").let { fields ->
        listOf("main", "sub").map { getter(symbols, styleType, fields.getValue(it), "Landroidx/compose/ui/text/TextStyle;") }
    }
    private val mediumShape = getter(symbols, shapeType,
        shapeType.constructorProperties("small", "medium", "large").getValue("medium"), "Landroidx/compose/ui/graphics/Shape;")

    fun read(composer: Composer): Skin {
        val cfg = configs.invoke(null, composer)
        val c = colors.invoke(null, composer)
        val s = styles.invoke(null, composer)
        val sh = shapes.invoke(null, composer)
        val p = palette.map { Color((it.invoke(c) as Long).toULong()) }
        val isDark = runCatching { isDarkFlag.invoke(cfg) as Boolean }.getOrElse {
            (p[4].red * 0.299f + p[4].green * 0.587f + p[4].blue * 0.114f) < 0.45f
        }
        val cardShape = runCatching { mediumShape.invoke(sh) as Shape }.getOrElse { RoundedCornerShape(12.dp) }
        return Skin(
            isDark = isDark,
            accent = p[0],
            text = p[1],
            muted = p[2],
            background = p[3],
            surface = p[4],
            line = p[5],
            onAccent = p[6],
            main = fonts[0].invoke(s) as TextStyle,
            sub = fonts[1].invoke(s) as TextStyle,
            cardShape = cardShape,
            pageBackground = p[3].compositeOver(if (isDark) Color(0xFF181B20) else Color(0xFFF5F6F8)),
            popup = p[7]
        )
    }
}

internal fun fallbackSkin(dark: Boolean) = Skin(
    isDark = dark,
    accent = if (dark) Color(0xFF60A5FA) else Color(0xFF2563EB),
    text = if (dark) Color(0xFFF1F5F9) else Color(0xFF17202D),
    muted = if (dark) Color(0xFFADB7C6) else Color(0xFF526071),
    background = if (dark) Color(0xFF181B20) else Color(0xFFF5F6F8),
    surface = if (dark) Color(0xFF22262D) else Color.White,
    line = if (dark) Color(0xFF38404A) else Color(0xFFDADFE6),
    onAccent = if (dark) Color(0xFF101923) else Color.White,
    main = TextStyle(fontSize = 14.sp),
    sub = TextStyle(fontSize = 12.sp),
    cardShape = RoundedCornerShape(12.dp)
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

private data class TrackWaves(
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

private val transitionEffects = linkedMapOf(false to "无效果", true to "半拍回声")
private val transitionLengths = linkedMapOf(2 to "2 小节", 4 to "4 小节", 8 to "8 小节")

@Composable
private fun SaltCard(content: @Composable ColumnScope.() -> Unit) {
    val s = LocalSkin.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(s.cardShape)
            .background(s.surface)
            .border(0.5.dp, s.line, s.cardShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
private fun SaltDivider() {
    val s = LocalSkin.current
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(s.line))
}

@Composable
private fun Label(text: String, small: Boolean = false, color: Color? = null) {
    val s = LocalSkin.current
    val base = if (small) s.sub else s.main
    BasicText(text, style = base.copy(color = color ?: if (small) s.muted else s.text))
}

@Composable
private fun Chip(
    text: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    primary: Boolean = false,
    compact: Boolean = false,
    description: String? = null,
    click: () -> Unit
) {
    val s = LocalSkin.current
    val shape = RoundedCornerShape(7.dp)
    val bg = when {
        primary -> s.accent
        selected -> s.accent.copy(alpha = if (s.isDark) 0.24f else 0.12f)
        else -> s.background
    }
    val borderCol = when {
        primary -> s.accent
        selected -> s.accent.copy(alpha = 0.55f)
        else -> s.line
    }
    val textCol = when {
        !enabled -> s.muted
        primary -> s.onAccent
        selected -> s.accent
        else -> s.text
    }
    Box(
        Modifier
            .clip(shape)
            .background(bg)
            .border(0.5.dp, borderCol, shape)
            .clickable(enabled = enabled, onClick = click)
            .then(if (description == null) Modifier else Modifier.semantics { contentDescription = description })
            .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 4.dp else 6.dp)
    ) {
        BasicText(
            text,
            style = s.sub.copy(
                color = textCol,
                fontWeight = if (primary || selected) FontWeight.Medium else FontWeight.Normal
            )
        )
    }
}

@Composable
private fun EqualPresetTile(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    click: () -> Unit
) {
    val s = LocalSkin.current
    val shape = RoundedCornerShape(8.dp)
    val bg = if (selected) s.accent else s.background
    val borderCol = if (selected) s.accent else s.line
    val textCol = if (selected) s.onAccent else s.text
    Box(
        modifier
            .height(32.dp)
            .clip(shape)
            .background(bg)
            .border(0.5.dp, borderCol, shape)
            .clickable(onClick = click),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text,
            style = s.sub.copy(
                color = textCol,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
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
    val s = LocalSkin.current
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .clip(shape)
            .background(s.background)
            .border(0.5.dp, s.line, shape)
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(13.dp).clip(RoundedCornerShape(2.dp)).background(barColor))
            BasicText(title, style = s.sub.copy(color = if (enabled) s.text else s.muted, fontSize = 11.sp, fontWeight = FontWeight.Medium))
        }
        val trackCol = when {
            !enabled -> s.line.copy(alpha = 0.5f)
            checked -> s.accent
            else -> s.line
        }
        Box(
            Modifier
                .width(36.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(trackCol)
                .padding(2.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (enabled) Color.White else s.surface)
            )
        }
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

internal data class SpwPlaylistTrack(val item: MediaItem, val syncTrackId: String)
internal data class PlaylistMember(val syncTrackId: String, val path: String?)

internal enum class PlaylistScanMode(val label: String, val description: String, val suffix: String?) {
    Matched("挑歌并新建", "只留下适合衔接的歌", "精选"),
    All("全单重排并新建", "保留原歌单的每首歌", "全部"),
    ScanOnly("仅扫描", "分析歌曲并保留结果", null)
}

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

internal data class SpwPlaylist(
    val id: String,
    val title: String,
    val tracks: List<SpwPlaylistTrack>,
    val members: List<PlaylistMember>,
    val trackSort: Int = 0,
    val descending: Boolean = false
)

private fun fmtTime(s: Double): String {
    val sign = if (s < 0.0) "-" else ""
    val safe = abs(s)
    return "$sign%d:%06.3f".format(java.util.Locale.ROOT, safe.toInt() / 60, safe % 60.0)
}

private fun fmtClock(sec: Double): String {
    val total = max(0, sec.roundToInt())
    return "%d:%02d".format(java.util.Locale.ROOT, total / 60, total % 60)
}

private fun planSummary(p: Plan): String {
    if (p.useHost) return "沿用 SPW"
    val label = Plan.presets.firstOrNull { it.matches(p) }?.label
        ?: Plan.volumes[p.volume]
        ?: "自定义"
    return "$label · ${p.bars} 小节"
}

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

private fun usesMovableSwap(plan: Plan): Boolean {
    if (plan.eq == "bass-swap" || plan.eq == "fast-bass-cut") return true
    if (plan.eq == "bass-swap-start" || plan.eq == "bass-swap-end") return false
    return plan.filter != "none" || plan.volume in setOf("fade-cut", "cut-fade", "overlap", "mid-cut")
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

@Composable
private fun StackedTimelineMinimap(
    waveA: TrackWaves,
    waveB: TrackWaves,
    plan: Plan,
    palette: StagePalette,
    skin: Skin,
    onJumpCue: (idx: Int, rawSec: Double, snap: Boolean) -> Unit,
    onDragEnd: () -> Unit
) {
    val curPlan by rememberUpdatedState(plan)
    val mover by rememberUpdatedState(onJumpCue)
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
                    val p = curPlan
                    val pMin = (p.cueA + p.duration * 0.5) - halfSpan
                    val tHit = pMin + (pos.x / size.width) * totalSpan
                    if (pos.y <= size.height / 2f) {
                        mover(0, tHit, true)
                    } else {
                        mover(1, tHit - (p.cueA - p.cueB), true)
                    }
                }
            }
            .pointerInput(waveA, waveB) {
                var dragIdx = 0
                var dragStartCue = 0.0
                var cumDx = 0f
                detectDragGestures(
                    onDragStart = { start ->
                        dragIdx = if (start.y <= size.height / 2f) 0 else 1
                        dragStartCue = if (dragIdx == 0) curPlan.cueA else curPlan.cueB
                        cumDx = 0f
                    },
                    onDragEnd = { endDrag() },
                    onDragCancel = { endDrag() }
                ) { change, delta ->
                    change.consume()
                    cumDx += delta.x
                    val rawCue = dragStartCue - (cumDx / size.width) * totalSpan
                    mover(dragIdx, rawCue, false)
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val halfH = h / 2f
        clipRect {
            drawPath(envelopeA.path(tMin, totalSpan, w, halfH * 0.5f, halfH * 0.38f), palette.overviewPeak)
            drawPath(envelopeB.path(tMin - bStart, totalSpan, w, halfH * 1.5f, halfH * 0.38f),
                palette.waveMid.copy(alpha = 0.72f))
        }

        drawLine(skin.line.copy(alpha = 0.6f), Offset(0f, halfH), Offset(w, halfH), 0.5.dp.toPx())

        val winW = (plan.duration / totalSpan * w).toFloat().coerceAtLeast(16.dp.toPx())
        val winX = (w * 0.5f - winW * 0.5f).coerceIn(0f, w - winW)
        val cr = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        drawRoundRect(palette.overviewWin, Offset(winX, 1.dp.toPx()), Size(winW, h - 2.dp.toPx()), cr)
        drawRoundRect(
            skin.accent.copy(alpha = 0.72f),
            Offset(winX, 1.dp.toPx()),
            Size(winW, h - 2.dp.toPx()),
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
    titleA: String,
    artistA: String,
    titleB: String,
    artistB: String,
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
    onSeekPreview: (Double) -> Unit
) {
    val skin = LocalSkin.current
    val palette = remember(skin) { stagePalette(skin) }
    val curPlan by rememberUpdatedState(editPlan)
    val gainKnots = remember(plan, audibleWindow) { audibleWindow?.gains(plan).orEmpty() }
    val setter by rememberUpdatedState(onUpdate)
    val seeker by rememberUpdatedState(onSeekPreview)
    val snapOn by rememberUpdatedState(snapEnabled)
    var activeSnapMsg by remember { mutableStateOf<String?>(null) }

    fun moveCue(idx: Int, pos: Double, p: Plan): Plan {
        val clamped = pos.coerceIn(p.cueBounds(idx, lengthA, lengthB))
        return if (idx == 0) p.copy(cueA = clamped) else p.copy(cueB = clamped)
    }

    @Composable
    fun TrackInfoRow(
        idx: Int,
        tw: TrackWaves,
        title: String,
        artist: String,
        colorTag: Color
    ) {
        val cue = if (idx == 0) plan.cueA else plan.cueB
        val bpm = if (idx == 0) plan.bpmA else plan.bpmB
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(colorTag))
                BasicText(
                    if (artist.isBlank()) title else "$title · $artist",
                    modifier = Modifier.weight(1f, fill = false),
                    style = skin.sub.copy(color = skin.text, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                BasicText(
                    fmtTime(cue),
                    style = skin.sub.copy(color = skin.muted)
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
                        val p = (pos.x / size.width - 0.12f) / 0.76f
                        seeker(p * curPlan.duration)
                    }
                }
                .pointerInput(idx, tw, lengthA, lengthB) {
                    var dragStartCue = 0.0
                    var dragStartSwap = 0.5
                    var draggingSwap = false
                    var cumDx = 0f
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            dragStartCue = if (idx == 0) curPlan.cueA else curPlan.cueB
                            dragStartSwap = curPlan.swap
                            cumDx = 0f
                            val sx = (0.12f + 0.76f * curPlan.swap.toFloat()) * size.width
                            draggingSwap = usesMovableSwap(curPlan) &&
                                abs(startOffset.x - sx) < 22f && (startOffset.y < 24f || startOffset.y > size.height - 24f)
                        },
                        onDragEnd = { activeSnapMsg = null },
                        onDragCancel = { activeSnapMsg = null }
                    ) { change, delta ->
                        change.consume()
                        cumDx += delta.x
                        val mixWidth = size.width * 0.76
                        if (draggingSwap) {
                            var rawSwap = (dragStartSwap + cumDx / mixWidth).coerceIn(0.05, 0.95)
                            var snapMsg: String? = null
                            if (snapOn) {
                                for (k in 1 until curPlan.bars) {
                                    val anchor = k.toDouble() / curPlan.bars
                                    if (abs(rawSwap - anchor) * mixWidth <= 6.dp.toPx()) {
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
                    plotCurve(TransitionParameter.EQ, palette.curveEq, audibleWindow.eqKnots(plan, idx).map { (progress, db) ->
                        audibleWindow.phase(progress) to (0.08 + 0.42 * 10.0.pow(db / 20.0))
                    })
                } else if (emphasizedParameter == TransitionParameter.EQ) {
                    plotCurve(TransitionParameter.EQ, palette.curveEq, flat)
                }
                if (plan.filter != "none") {
                    val range = ln(Plan.HOST_LPF_MAX_HZ / Plan.HOST_LPF_MIN_HZ)
                    plotCurve(TransitionParameter.FILTER, palette.curveFilter, audibleWindow.filterKnots(plan, idx).map { (progress, hz) ->
                        audibleWindow.phase(progress) to (0.10 + 0.40 * ln(hz / Plan.HOST_LPF_MIN_HZ) / range)
                    })
                } else if (emphasizedParameter == TransitionParameter.FILTER) {
                    plotCurve(TransitionParameter.FILTER, palette.curveFilter, flat)
                }
                if (idx == 0 && (plan.echo || emphasizedParameter == TransitionParameter.EFFECT)) {
                    val wet = if (plan.echo) audibleWindow.echoWetKnots() else listOf(0.0 to 0.0, 1.0 to 0.0)
                    plotCurve(TransitionParameter.EFFECT, skin.muted, wet.map { (progress, level) ->
                        audibleWindow.phase(progress) to (0.10 + level)
                    })
                }
            }

            if (usesMovableSwap(plan)) {
                val sx = mixL + mixW * plan.swap.toFloat()
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
                setter(moveCue(idx, hit?.first ?: clamped, curPlan))
            },
            onDragEnd = { activeSnapMsg = null }
        )

        // 2. Track A info row directly above Deck A.
        TrackInfoRow(0, waveA, titleA, artistA, palette.curveVol)

        // 3. Panoramic Dual-Deck Well with center Play/Stop and bottom Bar pill.
        val wellShape = RoundedCornerShape(10.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(wellShape)
                .border(0.5.dp, skin.line, wellShape)
        ) {
            Column(Modifier.fillMaxWidth()) {
                DeckCanvas(0, waveA)
                BeatGridSeam(waveA, waveB, plan, palette, skin, playheadSec, previewMixStart)
                DeckCanvas(1, waveB)
            }

            // Center Floating Audition Pill (Play triangle / Stop square).
            val btnShape = RoundedCornerShape(8.dp)
            Box(
                Modifier
                    .align(Alignment.Center)
                    .width(38.dp)
                    .height(28.dp)
                    .clip(btnShape)
                    .background(skin.popupBackground())
                    .border(0.8.dp, skin.line, btnShape)
                    .clickable(enabled = canAudition, onClick = onToggleAudition)
                    .semantics { contentDescription = if (isPlaying || rendering) "停止试听" else "试听过渡" },
                contentAlignment = Alignment.Center
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

            // Bottom-Center Floating Bar Count Pill (cycles 2 -> 4 -> 8 -> 2).
            val barShape = RoundedCornerShape(999.dp)
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
                    .clip(barShape)
                    .background(skin.popupBackground())
                    .border(0.8.dp, skin.line, barShape)
                    .clickable {
                        val nextBars = when (curPlan.bars) {
                            2 -> 4
                            4 -> 8
                            else -> 2
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
        TrackInfoRow(1, waveB, titleB, artistB, palette.curveFilter)

        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            val effectLegend = if (plan.echo || emphasizedParameter == TransitionParameter.EFFECT) " · 灰回声" else ""
            Label("波形：蓝低频 · 橙中频 · 白高频  |  曲线：青音量 · 黄低音 · 紫低通$effectLegend", small = true)
            (activeSnapMsg ?: tempoPair)?.let { msg ->
                BasicText(msg, style = skin.sub.copy(color = skin.accent, fontWeight = FontWeight.Medium))
            }
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
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.size(16.dp)) {
                        val w = size.width
                        val h = size.height
                        val strokeWidth = 1.8.dp.toPx()
                        drawLine(skin.text, Offset(w * 0.20f, h * 0.50f), Offset(w * 0.85f, h * 0.50f), strokeWidth)
                        drawLine(skin.text, Offset(w * 0.20f, h * 0.50f), Offset(w * 0.48f, h * 0.22f), strokeWidth)
                        drawLine(skin.text, Offset(w * 0.20f, h * 0.50f), Offset(w * 0.48f, h * 0.78f), strokeWidth)
                    }
                }
            }
            BasicText(
                "AutoMix",
                style = skin.main.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = skin.text)
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(skin.surface)
                    .clickable { MainPlugin.showStatus() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                BasicText(plugin.status, style = skin.sub.copy(color = skin.muted))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (statusText.isNotBlank()) {
                Label(statusText, small = true)
            }
            actions()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NativeEditor(plugin: MainPlugin, skin: Skin, onBack: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val savedPlans by plugin.plans.collectAsState()
    val host by plugin.hosts.collectAsState()
    CompositionLocalProvider(LocalSkin provides skin) {
        var library by remember { mutableStateOf(emptyList<MediaItem>()) }
        var queue by remember { mutableStateOf(QueueView()) }
        val queueEntries = queue.entries
        val queueTracks = remember(queueEntries) { queueEntries.map { it.item } }
        var playlists by remember { mutableStateOf(emptyList<SpwPlaylist>()) }
        var selectedPlaylistId by remember { mutableStateOf<String?>(null) }
        var playlistNote by remember { mutableStateOf("") }
        var scanJob by remember { mutableStateOf<Job?>(null) }
        var scanProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        var writingPlaylist by remember { mutableStateOf(false) }
        var scanNote by remember { mutableStateOf("") }
        var a by remember { mutableStateOf<MediaItem?>(null) }
        var b by remember { mutableStateOf<MediaItem?>(null) }
        var playlistTab by remember { mutableStateOf("queue") }
        val queueScroll = rememberLazyListState()
        val playlistScroll = if (playlistTab == "queue") queueScroll else key(
            playlistTab, selectedPlaylistId.takeIf { playlistTab == "playlists" }
        ) {
            rememberLazyListState()
        }
        val trackMetadata = remember(plugin) { mutableStateMapOf<String, TrackAnalysisMetadata>() }
        var metadataRevision by remember { mutableStateOf(0) }
        val covers = remember(plugin, metadataRevision) { PlaylistCoverStore() }
        var message by remember { mutableStateOf("正在读取队列与曲库…") }
        var queueNote by remember { mutableStateOf("") }
        var refreshing by remember { mutableStateOf(true) }
        var movingQueue by remember { mutableStateOf(false) }

        fun selectPair(pair: Pair<MediaItem, MediaItem>) {
            a = pair.first
            b = pair.second
        }

        suspend fun loadSpwPlaylists(tracks: List<MediaItem>) {
            if (tracks.isEmpty()) {
                playlists = playlists.map { playlist ->
                    playlist.copy(tracks = emptyList(), members = playlist.members.map { it.copy(path = null) })
                }
            }
            val bridge = host ?: return
            try {
                val sources = bridge.loadPlaylists()
                val currentTracks = library
                val byId = currentTracks.associateBy { it.id }
                val byPath = currentTracks.associateBy { normPathStr(it.path) }
                val parsed = sources.map { source ->
                    val entries = source.tracks.map { track ->
                        val item = (track.id?.let(byId::get)) ?: track.path?.let { byPath[normPathStr(it)] }
                        PlaylistMember(track.syncTrackId, item?.path ?: track.path?.takeIf { it.isNotBlank() }) to item
                    }
                    val mapped = entries.mapNotNull { (entry, item) -> item?.let { SpwPlaylistTrack(it, entry.syncTrackId) } }
                    SpwPlaylist(source.id, source.title, mapped, entries.map { it.first }, source.sort, source.descending)
                }
                playlists = parsed
                if (selectedPlaylistId == null || parsed.none { it.id == selectedPlaylistId }) {
                    selectedPlaylistId = (parsed.firstOrNull { it.tracks.size >= 2 } ?: parsed.firstOrNull())?.id
                }
                playlistNote = ""
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                playlistNote = e.message ?: "读取歌单失败"
            }
        }

        suspend fun refreshLists(selectInitialPair: Boolean) {
            refreshing = true
            try {
                val tracks = withContext(Dispatchers.IO) {
                    WorkshopApi.library.getAllTracks().toCompletableFuture().get()
                        .filter { it.path.isNotBlank() && it.id.isNotBlank() }
                }
                library = tracks
                val bridge = host
                if (bridge != null) {
                    queue = bridge.queueView(bridge.queueStates().value, tracks)
                    if (selectInitialPair && a == null && b == null) queue.nextPair?.let(::selectPair)
                }
                message = if (tracks.size < 2) "曲库不足两首歌曲" else ""
                loadSpwPlaylists(tracks)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                message = "读取曲库失败，请检查曲库读取权限"
            } finally {
                refreshing = false
            }
        }

        LaunchedEffect(plugin, host) { refreshLists(selectInitialPair = true) }

        LaunchedEffect(host, library) {
            val bridge = host ?: return@LaunchedEffect
            bridge.queueStates().collect { state ->
                queue = bridge.queueView(state, library)
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
                "playlists" -> activePlaylist?.tracks.orEmpty().associate { playlistRowKey(it.syncTrackId) to it.item.path }
                else -> emptyMap()
            }
        }
        val metadataRequest by rememberUpdatedState(Triple(metadataRevision, playlistScroll, metadataPathsByKey))
        val selectedMetadataPaths by rememberUpdatedState(listOfNotNull(a?.path, b?.path))
        LaunchedEffect(plugin) {
            snapshotFlow {
                val (revision, scroll, pathsByKey) = metadataRequest
                revision to buildSet {
                    addAll(selectedMetadataPaths)
                    scroll.layoutInfo.visibleItemsInfo.forEach { item ->
                        (item.key as? String)?.let(pathsByKey::get)?.let(::add)
                    }
                }
            }.collect { (_, paths) ->
                for (batch in paths.chunked(30)) {
                    val before = batch.associateWith { trackMetadata[it] }
                    val cached = try {
                        plugin.worker.cachedTrackMetadata(batch)
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Exception) {
                        break
                    }
                    for (path in batch) {
                        // A pair can finish analysis while the cache lookup is in progress.
                        if (trackMetadata[path] === before[path]) {
                            val value = cached[path]
                            if (value == null) trackMetadata.remove(path) else trackMetadata[path] = value
                        }
                    }
                }
            }
        }

        fun handleMoveQueue(fromIdx: Int, toIdx: Int, onRejected: () -> Unit = {}) {
            val bridge = plugin.bridge
            if (bridge == null || movingQueue || fromIdx !in queueEntries.indices ||
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
                    queue = bridge.queueView(bridge.queueStates().value, library)
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

        fun handleMovePlaylist(playlistId: String, fromIdx: Int, toIdx: Int): Boolean {
            if (plugin.bridge?.canReorderPlaylists != true) return false
            val pl = playlists.firstOrNull { it.id == playlistId } ?: return false
            if (scanJob != null || pl.tracks.size != pl.members.size ||
                fromIdx !in pl.tracks.indices || toIdx !in pl.tracks.indices || fromIdx == toIdx) return false
            val nextTracks = pl.tracks.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
            val switchedSort = pl.trackSort != 0 || pl.descending
            playlists = playlists.map {
                if (it.id == playlistId) it.copy(
                    tracks = nextTracks,
                    members = nextTracks.map { track -> PlaylistMember(track.syncTrackId, track.item.path) },
                    trackSort = 0,
                    descending = false
                ) else it
            }
            scope.launch {
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
            }
            return true
        }

        fun runPlaylistAction(source: SpwPlaylist, mode: PlaylistScanMode) {
            val bridge = plugin.bridge
            val nameSuffix = mode.suffix
            if (scanJob != null || source.members.isEmpty()) return
            if (nameSuffix != null && (source.members.size < 2 || bridge?.canCreatePlaylists != true)) return
            val total = source.members.size
            val paths = source.members.map { it.path }
            scanNote = ""
            scanProgress = 0 to total
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val scan = plugin.worker.scanPlaylist(paths) { done ->
                        scanProgress = done to total
                    }
                    metadataRevision++
                    if (scan.analyzed == 0) {
                        scanNote = listOfNotNull("没有可分析的歌曲", scan.firstTrackError).joinToString(" · ")
                        return@launch
                    }
                    if (nameSuffix == null) {
                        scanNote = "扫描完成 · 已分析 ${scan.analyzed}/$total 首" +
                            if (scan.analyzed < total) " · ${total - scan.analyzed} 首无法分析" else ""
                        return@launch
                    }
                    val result = plugin.worker.sequencePlaylist(paths)
                    if (result.analyzed == 0) {
                        scanNote = "扫描后歌曲文件已变化，请重新扫描"
                        return@launch
                    }
                    val indices = if (mode == PlaylistScanMode.Matched) result.matched else result.all
                    if (indices.size < 2) {
                        scanNote = "未找到可连续匹配的曲目，可选择「${PlaylistScanMode.All.label}」"
                        return@launch
                    }
                    val baseName = "${source.title} · AutoMix $nameSuffix"
                    val existingNames = playlists.mapTo(mutableSetOf()) { it.title }
                    var title = baseName
                    var suffix = 2
                    while (title in existingNames) title = "$baseName (${suffix++})"
                    writingPlaylist = true
                    val id = withContext(Dispatchers.IO) {
                        checkNotNull(bridge).createPlaylist(source.id, title, indices.map { source.members[it].syncTrackId })
                    }
                    loadSpwPlaylists(library)
                    if (playlists.any { it.id == id }) {
                        selectedPlaylistId = id
                    }
                    val details = buildList {
                        add("已创建「$title」 · ${indices.size}/$total 首")
                        if (mode == PlaylistScanMode.All && result.weakLinks > 0) add("${result.weakLinks} 处衔接较弱")
                        if (result.analyzed < total) add("${total - result.analyzed} 首未分析")
                    }
                    scanNote = details.joinToString(" · ")
                } catch (e: CancellationException) {
                    scanNote = "扫描已取消"
                    throw e
                } catch (e: Exception) {
                    val error = if (nameSuffix == null) "扫描失败" else "自动歌单生成失败"
                    scanNote = "$error · ${e.cause?.message ?: e.message ?: e.javaClass.simpleName}"
                } finally {
                    writingPlaylist = false
                    scanProgress = null
                    scanJob = null
                }
            }
            scanJob = job
            job.start()
        }

        fun playFromList(action: (HostPlayback) -> Unit) {
            val bridge = plugin.bridge ?: return
            try {
                plugin.worker.stopPreview()
                action(bridge)
            } catch (e: Exception) {
                val reason = e.cause?.message ?: e.message ?: "宿主播放接口不可用"
                WorkshopApi.ui.toast("播放歌曲失败 · $reason", WorkshopApi.Ui.ToastType.Error)
            }
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
            onPlay: ((Int) -> Unit)? = null,
            onMove: (fromIdx: Int, toIdx: Int) -> Unit
        ) {
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
                val isA = a?.id == track.id
                val isB = b?.id == track.id
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

                val rowShape = RoundedCornerShape(8.dp)
                val rowBg = when {
                    isA -> palette.curveVol.copy(alpha = if (skin.isDark) 0.14f else 0.08f)
                    isB -> palette.curveFilter.copy(alpha = if (skin.isDark) 0.14f else 0.08f)
                    else -> skin.background
                }
                val rowBorder = when {
                    isA -> palette.curveVol.copy(alpha = 0.45f)
                    isB -> palette.curveFilter.copy(alpha = 0.45f)
                    else -> skin.line
                }
                val hasNext = idx + 1 < tracks.size && isAdjacent(idx) && track.id != tracks[idx + 1].id
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
                            .background(rowBg)
                            .border(0.5.dp, rowBorder, rowShape)
                            .combinedClickable(
                                enabled = (hasNext || hasPrevious || onPlay != null) && dragPosition == null,
                                onDoubleClick = onPlay?.let { play -> { play(idx) } },
                                onClick = {
                                    if (hasNext) selectPair(track to tracks[idx + 1])
                                    else if (hasPrevious) selectPair(tracks[idx - 1] to track)
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
                                        val dotCol = skin.muted.copy(alpha = 0.65f)
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
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                val upEnabled = moveEnabled && dragPosition == null && idx > 0
                                val downEnabled = moveEnabled && dragPosition == null && idx + 1 < tracks.size
                                Box(
                                    Modifier
                                        .size(20.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(skin.surface)
                                        .border(0.5.dp, skin.line, RoundedCornerShape(5.dp))
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
                                        .background(skin.surface)
                                        .border(0.5.dp, skin.line, RoundedCornerShape(5.dp))
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

                    if (hasNext) {
                        val t1 = track
                        val t2 = tracks[idx + 1]
                        val transitionKey = pairKey(t1.id, t2.id)
                        val savedPlan = savedPlans[transitionKey]
                        val unsaved = plugin.drafts[transitionKey]?.let { it != savedPlan } == true
                        val isEditing = a?.id == t1.id && b?.id == t2.id
                        val pillShape = RoundedCornerShape(999.dp)
                        val pillBg = when {
                            isEditing -> skin.accent
                            savedPlan != null -> skin.accent.copy(alpha = if (skin.isDark) 0.20f else 0.10f)
                            else -> skin.background
                        }
                        val pillBorder = when {
                            isEditing -> skin.accent
                            savedPlan != null -> skin.accent.copy(alpha = 0.50f)
                            else -> skin.line
                        }
                        val pillTextCol = when {
                            isEditing -> skin.onAccent
                            savedPlan != null -> skin.accent
                            else -> skin.muted
                        }
                        val pillText = when {
                            unsaved -> "过渡 · 有未保存的修改"
                            isEditing && savedPlan != null -> "正在编辑 · ${planSummary(savedPlan)} · 已保存"
                            isEditing -> "正在编辑 · 自动规划"
                            savedPlan != null -> "过渡 · ${planSummary(savedPlan)} · 已保存"
                            else -> "过渡 · 自动规划"
                        }
                        val connAlpha by animateFloatAsState(
                            targetValue = if (dragPosition != null) 0.18f else 1f
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(playlistTransitionHeight)
                                .graphicsLayer { alpha = connAlpha }
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (canReorder) {
                                Spacer(Modifier.width(16.dp))
                            }
                            Spacer(Modifier.width(26.dp))
                            Box(Modifier.width(playlistCoverSize).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                Box(
                                    Modifier
                                        .width(1.5.dp)
                                        .fillMaxHeight()
                                        .background(if (isEditing || savedPlan != null) skin.accent else skin.line)
                                )
                            }
                            Row(
                                Modifier
                                    .clip(pillShape)
                                    .background(pillBg)
                                    .border(0.5.dp, pillBorder, pillShape)
                                    .clickable { a = t1; b = t2 }
                                    .padding(horizontal = 9.dp, vertical = 3.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BasicText(
                                    pillText,
                                    modifier = Modifier.weight(1f, fill = false),
                                    style = skin.sub.copy(
                                        color = pillTextCol,
                                        fontSize = 11.sp,
                                        fontWeight = if (isEditing || savedPlan != null) FontWeight.Medium else FontWeight.Normal
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Canvas(Modifier.size(6.dp, 8.dp)) {
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
        fun PlaylistRowItem(
            idxTag: String,
            itemA: MediaItem,
            itemB: MediaItem,
            selected: Boolean,
            plan: Plan?,
            onSelect: () -> Unit
        ) {
            val rowShape = RoundedCornerShape(8.dp)
            val bg = if (selected) skin.accent.copy(alpha = if (skin.isDark) 0.22f else 0.10f) else skin.background
            val border = if (selected) skin.accent.copy(alpha = 0.55f) else skin.line
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(rowShape)
                    .background(bg)
                    .border(0.5.dp, border, rowShape)
                    .clickable(onClick = onSelect)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (selected) skin.accent else skin.surface)
                            .border(0.5.dp, if (selected) skin.accent else skin.line, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        BasicText(
                            idxTag,
                            style = skin.sub.copy(
                                color = if (selected) skin.onAccent else skin.muted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                    if (plan != null) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(skin.surface)
                                .border(0.5.dp, skin.line, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            BasicText(planSummary(plan), style = skin.sub.copy(color = skin.muted, fontSize = 10.sp))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlaylistCover(itemA.id, covers, 22.dp)
                    BasicText(
                        itemA.title,
                        style = skin.sub.copy(color = if (selected) skin.accent else skin.text, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlaylistCover(itemB.id, covers, 22.dp)
                    BasicText(
                        "→ ${itemB.title}",
                        style = skin.sub.copy(color = skin.muted),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        @Composable
        fun PlaylistPanel(modifier: Modifier = Modifier) {
            val curQueueIdx = queueEntries.indexOfFirst { it.rawIndex == queue.currentIndex }
            val cardSummary = when (playlistTab) {
                "queue" -> {
                    val posText = if (queue.currentIndex in queue.order.indices) {
                        "当前 ${queue.currentIndex + 1}/${queue.order.size}"
                    } else "${queue.order.size} 首队列"
                    val savedCount = queueTracks.zipWithNext().withIndex().count { (idx, pair) ->
                        queueEntries[idx].rawIndex + 1 == queueEntries[idx + 1].rawIndex &&
                            pairKey(pair.first.id, pair.second.id) in savedPlans
                    }
                    if (savedCount == 0) posText else "$posText · 已自定义 $savedCount 处"
                }
                "saved" -> "${savedPairs.size} 组已保存过渡"
                else -> null
            }

            Column(modifier.background(skin.surface)) {
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
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Chip("当前转场", compact = true, enabled = curQueueIdx >= 0 || queue.nextPair != null) {
                                playlistTab = "queue"
                                queue.nextPair?.let(::selectPair)
                                // The queue status above the tracks occupies the first list item.
                                if (curQueueIdx >= 0) queueScroll.requestScrollToItem(curQueueIdx + 1)
                            }
                            Chip("↻", compact = true, description = "刷新", enabled = !refreshing && !movingQueue && scanJob == null) {
                                refreshing = true
                                queueNote = ""
                                scope.launch {
                                    refreshLists(selectInitialPair = false)
                                    metadataRevision++
                                }
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Chip("队列", selected = playlistTab == "queue", compact = true) {
                            playlistTab = "queue"
                        }
                        Chip("歌单", selected = playlistTab == "playlists", compact = true) {
                            playlistTab = "playlists"
                        }
                        Chip("已保存", selected = playlistTab == "saved", compact = true) {
                            playlistTab = "saved"
                        }
                        Chip("自选", selected = playlistTab == "custom", compact = true) {
                            playlistTab = "custom"
                        }
                    }
                    cardSummary?.let { Label(it, small = true) }
                    if (playlistTab == "playlists") {
                        val pl = activePlaylist
                        val canCreate = plugin.bridge?.canCreatePlaylists == true
                        if (playlists.isNotEmpty()) {
                            PlaylistToolbar(
                                playlists = playlists,
                                current = pl,
                                canPick = scanJob == null,
                                canScan = !refreshing && scanJob == null && pl?.members?.isNotEmpty() == true,
                                canGenerate = canCreate && (pl?.members?.size ?: 0) >= 2,
                                activity = if (writingPlaylist) "创建中…" else scanProgress?.let { (done, total) -> "扫描 $done/$total" },
                                onCancel = if (scanJob != null && !writingPlaylist) ({ scanJob?.cancel() }) else null,
                                onPick = {
                                    selectedPlaylistId = it.id
                                    scanNote = ""
                                },
                                onAction = { mode -> pl?.let { runPlaylistAction(it, mode) } }
                            )
                        }
                        val note = when {
                            scanNote.isNotBlank() -> scanNote
                            playlistNote.isNotBlank() -> playlistNote
                            playlists.isEmpty() -> "未读取到 SPW 歌单，可点击刷新"
                            pl != null && pl.members.size >= 2 && !canCreate -> "当前 SPW 版本的自动新建歌单接口不可用"
                            pl != null && pl.tracks.size < 2 -> "编辑转场需要至少两首本地歌曲"
                            else -> null
                        }
                        note?.let { Label(it, small = true) }
                    }
                }

                SaltDivider()

                val rowDrag = remember(playlistScroll) { PlaylistRowDrag() }
                val queueKeys = remember(queueEntries) { queueEntries.map { it.queueId.toString() } }
                val playlistKeys = remember(activePlaylist) { activePlaylist?.tracks?.map { it.syncTrackId }.orEmpty() }
                val canMoveQueue = !movingQueue && !refreshing
                val canReorderPlaylist = activePlaylist?.let {
                    host?.canReorderPlaylists == true && it.tracks.size == it.members.size
                } == true
                val canMovePlaylist = scanJob == null
                val reorder = when {
                    playlistTab == "queue" && canMoveQueue -> PlaylistReorder(queueTracks, queueKeys, ::handleMoveQueue)
                    playlistTab == "playlists" && activePlaylist != null && canReorderPlaylist && canMovePlaylist ->
                        PlaylistReorder(activePlaylistItems, playlistKeys) { from, to, onRejected ->
                            if (!handleMovePlaylist(activePlaylist.id, from, to)) onRejected()
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
                                    Label(queue.nextPairNote, small = true)
                                    if (queueNote.isNotBlank()) Label(queueNote, small = true)
                                    if (queueTracks.isEmpty() && queue.order.isNotEmpty()) {
                                        Label("队列里的歌曲未匹配到本地曲库", small = true)
                                    }
                                }
                            }
                            mixTrackItems(
                                tracks = queueTracks,
                                rowKeys = queueKeys,
                                drag = rowDrag,
                                rowNumbers = queueEntries.map { it.rawIndex + 1 },
                                currentTrackIndex = curQueueIdx,
                                canReorder = true,
                                moveEnabled = canMoveQueue,
                                isAdjacent = { i -> queueEntries[i].rawIndex + 1 == queueEntries[i + 1].rawIndex },
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
                                    canReorder = canReorderPlaylist,
                                    moveEnabled = canMovePlaylist,
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
                                        PlaylistRowItem("#${idx + 1}", sA, sB, isSel, savedPlans[pairKey(sA.id, sB.id)]) {
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
                                    InlinePicker(library.filter { it.id != b?.id }, "A · 退出曲", a) { a = it }
                                    InlinePicker(library.filter { it.id != a?.id }, "B · 进入曲", b) { b = it }
                                }
                            } else {
                                Label("请先等待曲库加载完成", small = true)
                            }
                        }
                    }
                }
            }
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val playlistModifier = Modifier.width((maxWidth * 0.32f).coerceIn(310.dp, 380.dp)).fillMaxHeight()
            val aa = a
            val bb = b
            if (aa != null && bb != null) {
                key(aa.id, bb.id) {
                    PairWorkspace(
                        plugin = plugin,
                        a = aa,
                        b = bb,
                        tempoPair = trackMetadata[aa.path]?.let { from ->
                            trackMetadata[bb.path]?.let(from::tempoPair)
                        },
                        savedPlan = savedPlans[pairKey(aa.id, bb.id)],
                        onBack = onBack,
                        onTrackAnalyzed = { path, metadata -> trackMetadata[path] = metadata },
                        playlistPanel = { PlaylistPanel(playlistModifier) }
                    )
                }
            } else {
                val scroll = rememberScrollState()
                Column(Modifier.fillMaxSize()) {
                    EditorHeader(skin = skin, plugin = plugin, statusText = message, onBack = onBack)
                    SaltDivider()
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        Box(
                            Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Column(
                                Modifier
                                    .widthIn(max = 1100.dp)
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                SaltCard { Label(message.ifBlank { "选择当前转场，或从右侧选择一组歌曲" }, small = true) }
                            }
                        }
                        Box(Modifier.width(0.5.dp).fillMaxHeight().background(skin.line))
                        PlaylistPanel(playlistModifier)
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
    tempoPair: String?,
    savedPlan: Plan?,
    onBack: (() -> Unit)?,
    onTrackAnalyzed: (String, TrackAnalysisMetadata) -> Unit,
    playlistPanel: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val skin = LocalSkin.current
    val scroll = rememberScrollState()

    var analyzing by remember { mutableStateOf(true) }
    var rendering by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("正在分析双轨…") }
    var waveA by remember { mutableStateOf<TrackWaves?>(null) }
    var waveB by remember { mutableStateOf<TrackWaves?>(null) }
    var autoPlan by remember { mutableStateOf<Plan?>(null) }
    var reasons by remember { mutableStateOf(emptyList<String>()) }
    var openParam by remember { mutableStateOf<TransitionParameter?>(null) }
    var hoveredPlan by remember { mutableStateOf<Plan?>(null) }
    var editingCustom by remember { mutableStateOf(false) }
    var showReasons by remember { mutableStateOf(false) }
    var task by remember { mutableStateOf<Job?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var playheadSec by remember { mutableStateOf<Double?>(null) }
    var writingPlan by remember { mutableStateOf(false) }
    val pendingPosition = remember { AtomicReference<Double?>(null) }
    val planKey = pairKey(a.id, b.id)
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

    fun setOpenParameter(parameter: TransitionParameter?) {
        hoveredPlan = null
        openParam = parameter
    }

    DisposableEffect(plugin, a.id, b.id) {
        onDispose { plugin.worker.stopPreview() }
    }

    LaunchedEffect(plugin, a.id, b.id) {
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

    LaunchedEffect(a.id, b.id) {
        analyzing = true
        status = "正在自动分析双轨…"
        try {
            val data = plugin.worker.analyzePair(a.path, b.path)
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
            waveA = data.a.displayWave(metadataA)
            waveB = data.b.displayWave(metadataB)
            autoPlan = data.recommendation.plan
            onTrackAnalyzed(a.path, metadataA)
            onTrackAnalyzed(b.path, metadataB)
            reasons = data.recommendation.reasons
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

    fun adjust(next: Plan, note: String = "有未保存的修改") {
        stopAudition()
        if (next == (savedPlan ?: autoPlan)) plugin.drafts.remove(planKey)
        else plugin.drafts[planKey] = next
        status = note
    }

    @Composable
    fun <T : Any> PlanParameterMenu(
        parameter: TransitionParameter,
        color: Color,
        selected: T,
        options: Map<T, String>,
        modifier: Modifier,
        withValue: (T) -> Plan
    ) {
        ParameterMenu(
            parameter = parameter,
            color = color,
            selected = selected,
            options = options,
            openParameter = openParam,
            onOpenChange = ::setOpenParameter,
            modifier = modifier,
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

    val canSave = p != null && (p.useHost || window != null)

    Column(Modifier.fillMaxSize()) {
        // Pinned Header Bar with Save/Delete actions on the right.
        EditorHeader(
            skin = skin,
            plugin = plugin,
            statusText = status,
            onBack = onBack,
            actions = {
                if (p != null) {
                    val saveLabel = when {
                        writingPlan -> "写入中…"
                        savedPlan == p -> "已保存"
                        savedPlan != null -> "保存修改"
                        else -> "保存"
                    }
                    Chip(saveLabel, primary = true, enabled = !analyzing && !writingPlan && canSave && savedPlan != p) {
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
                    if (savedPlan != null) {
                        Chip("删除", compact = true, enabled = !writingPlan) {
                            writingPlan = true
                            scope.launch {
                                try {
                                    val draftBeforeDelete = plugin.drafts[planKey]
                                    withContext(Dispatchers.IO) { plugin.remove(a.id, b.id) }
                                    if (plugin.drafts[planKey] == draftBeforeDelete) {
                                        stopAudition()
                                        plugin.drafts.remove(planKey)
                                        editingCustom = false
                                        status = "已删除自定义，恢复自动推荐"
                                    } else {
                                        status = "已删除保存方案 · 保留新修改"
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
            }
        )

        SaltDivider()

        Row(Modifier.weight(1f).fillMaxWidth()) {
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.TopCenter
            ) {
                val deckHeight = ((maxHeight - 390.dp) / 2).coerceIn(104.dp, 164.dp)
                Column(
                    Modifier
                        .widthIn(max = 1100.dp)
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                val wA = waveA
                val wB = waveB
                if (p != null && wA != null && wB != null) {
                    val palette = remember(skin) { stagePalette(skin) }

                    // 1. Hero Panoramic Stage (Centered-box draggable minimap -> Track A -> Waveforms -> Track B).
                    if (p.useHost) {
                        SaltCard { Label("此曲对由 SPW 按播放器设置切换，可在 SPW 播放时试听。") }
                    } else {
                        val shown = hoveredPlan ?: p
                        val showsAuditionTiming = shown.duration == p.duration && shown.cueA == p.cueA && shown.cueB == p.cueB
                        DeckStage(
                            titleA = a.title,
                            artistA = a.artist,
                            titleB = b.title,
                            artistB = b.artist,
                            waveA = wA,
                            waveB = wB,
                            deckHeight = deckHeight,
                            tempoPair = tempoPair,
                            plan = shown,
                            editPlan = p,
                            audibleWindow = shown.window(lengthA, lengthB),
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
                            onUpdate = { adjust(it) },
                            onSeekPreview = { sec ->
                                window?.let { startAudition((it.previewOrigin + sec).coerceAtLeast(0.0)) }
                            }
                        )
                        if (window == null) {
                            Label("过渡需要至少 0.2 秒可用重叠，请移动切点或调整长度。", small = true)
                        } else if (window.durationMs != window.nominalMs) {
                            val seconds = "%.2f".format(window.durationMs / 1000.0)
                            Label("实际重叠 $seconds 秒", small = true)
                        }
                    }

                    SaltCard {
                        Box(Modifier.fillMaxWidth()) {
                            Box(Modifier.align(Alignment.Center)) {
                                Label("过渡预设", small = true, color = skin.muted)
                            }
                            if (reasons.isNotEmpty()) {
                                Box(Modifier.align(Alignment.CenterEnd)) {
                                    Chip(if (showReasons) "收起依据" else "分析依据", selected = showReasons, compact = true) {
                                        showReasons = !showReasons
                                    }
                                }
                            }
                        }

                        val isAutoMatch = autoPlan != null && p == autoPlan
                        val matchedPreset = Plan.presets.firstOrNull { it.matches(p) }
                        val isCustom = editingCustom || (matchedPreset == null && !isAutoMatch)

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            EqualPresetTile("自定义", selected = isCustom, modifier = Modifier.weight(1f)) {
                                setOpenParameter(null)
                                editingCustom = true
                                if (p.useHost) adjust(p.copy(useHost = false))
                            }
                            if (autoPlan != null) {
                                EqualPresetTile("自动", selected = !editingCustom && isAutoMatch, modifier = Modifier.weight(1f)) {
                                    setOpenParameter(null)
                                    editingCustom = false
                                    autoPlan?.let { adjust(it, "已应用自动推荐") }
                                }
                            }
                            for (preset in Plan.presets) {
                                EqualPresetTile(
                                    preset.label,
                                    selected = !editingCustom && preset.matches(p) && !isAutoMatch,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    setOpenParameter(null)
                                    editingCustom = false
                                    adjust(preset.apply(p), "预设：${preset.label}")
                                }
                            }
                        }

                        if (!p.useHost) {
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
                                    options = Plan.filters,
                                    modifier = Modifier.weight(1f)
                                ) { p.copy(filter = it) }
                            }

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PlanParameterMenu(
                                    parameter = TransitionParameter.EFFECT,
                                    color = skin.muted,
                                    selected = p.echo,
                                    options = transitionEffects,
                                    modifier = Modifier.weight(1f)
                                ) { p.copy(echo = it) }

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
                                    adjust(p.copy(snap = next), if (next) "已开启原速拍点磁吸" else "已关闭拍点磁吸")
                                }
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
                    SaltCard {
                        Label("${a.title}  →  ${b.title} · $status", small = true)
                    }
                }
            }
        }
            Box(Modifier.width(0.5.dp).fillMaxHeight().background(skin.line))
            playlistPanel()
        }
    }
}
