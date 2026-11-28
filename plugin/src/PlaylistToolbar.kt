@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, com.moriafly.salt.ui.UnstableSaltUiApi::class)
package automix

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.moriafly.salt.ui.BasicButton
import com.moriafly.salt.ui.ButtonColors
import com.moriafly.salt.ui.ControlSize
import com.moriafly.salt.ui.SaltTheme
import com.moriafly.salt.ui.popup.PopupMenu
import com.moriafly.salt.ui.popup.PopupMenuDefaults
import com.moriafly.salt.ui.popup.PopupMenuMaterial
import kotlinx.coroutines.launch

@Composable
internal fun PlaylistToolbar(
    playlists: List<SpwPlaylist>,
    current: SpwPlaylist?,
    canPick: Boolean,
    canScan: Boolean,
    activity: String?,
    onCancel: (() -> Unit)?,
    onPick: (SpwPlaylist) -> Unit,
    onScan: () -> Unit
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val pageClosing = LocalAutoMixPageClosing.current
    LaunchedEffect(pageClosing) { if (pageClosing) pickerOpen = false }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        var playlistAnchorBounds by remember { mutableStateOf<Rect?>(null) }
        Box(Modifier.weight(1f).onGloballyPositioned { playlistAnchorBounds = it.boundsInWindow() }) {
            val expanded = !pageClosing && pickerOpen && canPick
            ToolbarButton(
                title = current?.title ?: "选择歌单",
                detail = current?.let { "${it.members.size} 首" },
                modifier = Modifier.fillMaxWidth(),
                enabled = canPick && playlists.isNotEmpty(),
                dropdown = true,
                expanded = expanded,
                description = "切换歌单"
            ) { pickerOpen = !expanded }
            if (expanded) {
                PlaylistPickerPopup(playlists, current, playlistAnchorBounds, onDismiss = { pickerOpen = false }) {
                    pickerOpen = false
                    onPick(it)
                }
            }
        }
        ToolbarButton(
            title = activity ?: "仅扫描",
            enabled = canScan && activity == null,
            onClick = onScan
        )
        if (onCancel != null) {
            ToolbarButton("取消", description = "取消扫描", onClick = onCancel)
        }
    }
}

@Composable
private fun ToolbarButton(
    title: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dropdown: Boolean = false,
    expanded: Boolean = false,
    description: String? = null,
    onClick: () -> Unit
) {
    val skin = LocalSkin.current
    val colors = SaltTheme.colors
    val subStyle = SaltTheme.textStyles.sub
    var hovered by remember { mutableStateOf(false) }
    val text = if (enabled) colors.text else colors.subText
    BasicButton(
        onClick = onClick,
        modifier = modifier
            .height(34.dp)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .then(if (description == null) Modifier else Modifier.semantics { contentDescription = description }),
        enabled = enabled,
        size = ControlSize.Regular,
        shape = SaltTheme.shapes.small,
        colors = ButtonColors(
            containerColor = when {
                hovered && enabled -> skin.hover
                else -> colors.background
            },
            contentColor = text,
            disabledContainerColor = colors.background,
            disabledContentColor = colors.subText
        ),
        border = if (expanded) BorderStroke(1.dp, SolidColor(colors.highlight.copy(alpha = 0.65f))) else null,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                title,
                modifier = if (detail == null) Modifier else Modifier.weight(1f),
                style = subStyle.copy(color = text, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            detail?.let { BasicText(it, style = subStyle.copy(color = colors.subText), maxLines = 1) }
            if (dropdown) {
                Canvas(Modifier.size(8.dp, 5.dp)) {
                    val edgeY = if (expanded) size.height else 0f
                    val vertex = Offset(size.width / 2f, size.height - edgeY)
                    val stroke = 1.25.dp.toPx()
                    drawLine(text, Offset(0f, edgeY), vertex, stroke, StrokeCap.Round)
                    drawLine(text, vertex, Offset(size.width, edgeY), stroke, StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun ToolbarPopup(
    width: Dp,
    onDismiss: () -> Unit,
    maxHeight: Dp = Dp.Unspecified,
    content: @Composable ColumnScope.() -> Unit
) {
    val skin = LocalSkin.current
    val colors = SaltTheme.colors
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val position = remember(density) {
        with(density) { ParameterMenuPosition(6.dp.roundToPx(), 10.dp.roundToPx(), preferBelow = true) }
    }
    val popupWidth = with(density) {
        if (window.width > 0) (window.width.toDp() - 20.dp).coerceIn(1.dp, width) else width
    }
    PopupMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(popupWidth)
            .then(if (maxHeight != Dp.Unspecified) Modifier.heightIn(max = maxHeight) else Modifier),
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
        style = PopupMenuDefaults.style(material = PopupMenuMaterial.Glass),
        popupPositionProvider = position,
        scrollable = false
    ) {
        Column(
            Modifier.fillMaxWidth()
                .then(if (maxHeight != Dp.Unspecified) Modifier.heightIn(max = maxHeight) else Modifier)
                .padding(5.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            content = content
        )
    }
}

@Composable
private fun PlaylistPickerPopup(
    playlists: List<SpwPlaylist>,
    current: SpwPlaylist?,
    anchorBounds: Rect?,
    onDismiss: () -> Unit,
    onPick: (SpwPlaylist) -> Unit
) {
    val skin = LocalSkin.current
    val density = LocalDensity.current
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    var query by remember { mutableStateOf("") }
    var highlightedIndex by remember { mutableStateOf<Int?>(null) }
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val matches = remember(playlists, query) { playlists.filter { it.title.contains(query.trim(), ignoreCase = true) } }
    val activeIndex = when {
        matches.isEmpty() -> null
        highlightedIndex != null -> highlightedIndex!!.coerceIn(0, matches.lastIndex)
        query.isNotBlank() -> 0
        else -> null
    }
    val popupMaxHeight = availablePopupHeight(
        anchorBounds = anchorBounds,
        windowHeightPx = windowHeightPx,
        gap = 6.dp,
        margin = 10.dp,
        minHeight = 96.dp,
        maxHeight = 352.dp,
        density = density
    )
    LaunchedEffect(query) {
        if (matches.isNotEmpty()) listState.scrollToItem(0)
    }
    ToolbarPopup(276.dp, onDismiss, maxHeight = popupMaxHeight) {
        val colors = SaltTheme.colors
        val subStyle = SaltTheme.textStyles.sub
        val shape = SaltTheme.shapes.small
        Box(
            Modifier.fillMaxWidth().clip(shape).background(colors.background)
                .border(0.5.dp, colors.stroke, shape).padding(horizontal = 9.dp, vertical = 7.dp)
        ) {
            if (query.isEmpty()) BasicText("搜索歌单", style = subStyle.copy(color = colors.subText))
            BasicTextField(
                value = query,
                onValueChange = {
                    query = it
                    highlightedIndex = null
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    .semantics { contentDescription = "搜索歌单" }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown, Key.DirectionUp -> {
                                if (matches.isEmpty()) return@onPreviewKeyEvent true
                                val delta = if (event.key == Key.DirectionDown) 1 else -1
                                val nextIndex = if (activeIndex != null) {
                                    (activeIndex + delta).coerceIn(0, matches.lastIndex)
                                } else {
                                    val currentIndex = current?.let { c -> matches.indexOfFirst { it.id == c.id } }
                                        ?.takeIf { it >= 0 }
                                    when {
                                        currentIndex != null -> (currentIndex + delta).coerceIn(0, matches.lastIndex)
                                        delta > 0 -> 0
                                        else -> matches.lastIndex
                                    }
                                }
                                highlightedIndex = nextIndex
                                scope.launch { listState.ensureItemVisible(nextIndex) }
                                true
                            }
                            Key.Enter, Key.NumPadEnter -> {
                                when {
                                    activeIndex != null -> onPick(matches[activeIndex])
                                    query.isBlank() -> onDismiss()
                                }
                                true
                            }
                            else -> false
                        }
                    },
                singleLine = true,
                textStyle = subStyle.copy(color = colors.text),
                cursorBrush = SolidColor(colors.highlight)
            )
        }
        if (matches.isEmpty()) {
            BasicText("未找到歌单", Modifier.padding(9.dp), style = subStyle.copy(color = colors.subText))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
            ) {
                itemsIndexed(matches, key = { _, playlist -> playlist.id }) { index, playlist ->
                    ToolbarOption(
                        title = playlist.title,
                        detail = "${playlist.members.size} 首",
                        checked = playlist.id == current?.id,
                        highlighted = index == activeIndex
                    ) {
                        onPick(playlist)
                    }
                }
            }
        }
        LaunchedEffect(focus) { focus.requestFocus() }
    }
}

private suspend fun LazyListState.ensureItemVisible(index: Int) {
    val info = layoutInfo
    val visible = info.visibleItemsInfo.firstOrNull { it.index == index }
    val viewportStart = info.viewportStartOffset
    val viewportEnd = info.viewportEndOffset
    when {
        visible != null && visible.offset < viewportStart -> scrollToItem(index)
        visible != null && visible.offset + visible.size > viewportEnd ->
            scroll { scrollBy((visible.offset + visible.size - viewportEnd).toFloat()) }
        visible == null -> {
            val lastVisibleIndex = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (index > lastVisibleIndex && info.visibleItemsInfo.isNotEmpty()) {
                val fullCount = info.visibleItemsInfo.count {
                    it.offset >= viewportStart && it.offset + it.size <= viewportEnd
                }.coerceAtLeast(1)
                scrollToItem((index - fullCount + 1).coerceAtLeast(0))
            } else {
                scrollToItem(index)
            }
        }
    }
}

@Composable
private fun ToolbarOption(
    title: String,
    detail: String? = null,
    checked: Boolean = false,
    highlighted: Boolean = false,
    description: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val skin = LocalSkin.current
    val colors = SaltTheme.colors
    val subStyle = SaltTheme.textStyles.sub
    val shape = SaltTheme.shapes.small
    var hovered by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 32.dp).clip(shape)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .background(when {
                checked -> colors.highlight.copy(alpha = if (SaltTheme.configs.isDarkTheme) 0.22f else 0.12f)
                highlighted || (hovered && enabled) -> skin.hover
                else -> Color.Transparent
            })
            .border(1.dp, if (highlighted) colors.highlight.copy(alpha = 0.52f) else Color.Transparent, shape)
            .semantics { selected = checked }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val color = when {
                !enabled -> colors.subText
                checked -> colors.highlight
                else -> colors.text
            }
            BasicText(title, style = subStyle.copy(color = color), maxLines = 1, overflow = TextOverflow.Ellipsis)
            description?.let { BasicText(it, style = subStyle.copy(color = colors.subText, fontSize = 10.sp)) }
        }
        detail?.let { BasicText(it, style = subStyle.copy(color = colors.subText), maxLines = 1) }
    }
}
