@file:OptIn(com.moriafly.salt.ui.UnstableSaltUiApi::class)
package automix

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.moriafly.salt.ui.SaltTheme
import com.moriafly.salt.ui.popup.PopupMenu
import com.moriafly.salt.ui.popup.PopupMenuDefaults
import com.moriafly.salt.ui.popup.PopupMenuMaterial
import kotlin.math.ceil
import kotlin.math.floor

internal enum class TransitionParameter(val label: String, val hasCurve: Boolean = false) {
    VOLUME("音量", true),
    EQ("均衡器", true),
    FILTER("滤波", true),
    EFFECT("效果", true),
    LENGTH("过渡长度")
}

internal class ParameterMenuPosition(
    private val gap: Int,
    private val margin: Int,
    private val preferBelow: Boolean = false
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(0)
        val maxY = (windowSize.height - popupContentSize.height - margin).coerceAtLeast(0)
        val minX = minOf(margin, maxX)
        val minY = minOf(margin, maxY)
        val anchorX = when (layoutDirection) {
            LayoutDirection.Ltr -> anchorBounds.left
            LayoutDirection.Rtl -> anchorBounds.right - popupContentSize.width
        }
        val above = anchorBounds.top - gap - popupContentSize.height
        val below = anchorBounds.bottom + gap
        val y = when {
            preferBelow && below <= maxY -> below
            above >= minY -> above
            below <= maxY -> below
            anchorBounds.top >= windowSize.height - anchorBounds.bottom -> above
            else -> below
        }
        return IntOffset(anchorX.coerceIn(minX, maxX), y.coerceIn(minY, maxY))
    }
}

internal fun availablePopupHeight(
    anchorBounds: Rect?,
    windowHeightPx: Int,
    gap: Dp,
    margin: Dp,
    minHeight: Dp,
    maxHeight: Dp,
    density: Density
): Dp = with(density) {
    if (windowHeightPx <= 0) return maxHeight
    val marginPx = margin.roundToPx()
    val gapPx = gap.roundToPx()
    val windowAvailable = (windowHeightPx - marginPx * 2).coerceAtLeast(1).toDp()
    val upper = minOf(maxHeight, windowAvailable)
    val lower = minOf(minHeight, upper)
    if (anchorBounds == null) return upper
    val spaceAbove = floor(anchorBounds.top).toInt() - gapPx - marginPx
    val spaceBelow = windowHeightPx - ceil(anchorBounds.bottom).toInt() - gapPx - marginPx
    maxOf(spaceAbove, spaceBelow).toDp().coerceIn(lower, upper)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun <T : Any> ParameterMenu(
    parameter: TransitionParameter,
    color: Color,
    selected: T,
    options: Map<T, String>,
    openParameter: TransitionParameter?,
    onOpenChange: (TransitionParameter?) -> Unit,
    modifier: Modifier = Modifier,
    selectedLabel: String = options[selected].orEmpty(),
    onHover: (T?) -> Unit,
    onSelect: (T) -> Unit
) {
    val skin = LocalSkin.current
    val colors = SaltTheme.colors
    val subStyle = SaltTheme.textStyles.sub
    val expanded = openParameter == parameter
    val shape = SaltTheme.shapes.small
    var anchorBounds by remember { mutableStateOf<Rect?>(null) }
    var triggerHovered by remember { mutableStateOf(false) }
    Box(modifier.onGloballyPositioned { anchorBounds = it.boundsInWindow() }) {
        Row(
            Modifier.fillMaxWidth()
                .height(36.dp)
                .clip(shape)
                .onPointerEvent(PointerEventType.Enter) { triggerHovered = true }
                .onPointerEvent(PointerEventType.Exit) { triggerHovered = false }
                .background(when {
                    expanded -> color.copy(alpha = if (SaltTheme.configs.isDarkTheme) 0.16f else 0.10f)
                    triggerHovered -> skin.hover
                    else -> colors.background
                })
                .border(
                    if (expanded) 1.dp else 0.dp,
                    if (expanded) color.copy(alpha = 0.65f) else Color.Transparent,
                    shape
                )
                .clickable(role = Role.Button) { onOpenChange(if (expanded) null else parameter) }
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(999.dp)).background(color))
            BasicText(
                parameter.label,
                style = subStyle.copy(color = colors.text, fontWeight = FontWeight.Medium),
                maxLines = 1
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                BasicText(
                    selectedLabel,
                    style = subStyle.copy(color = if (expanded) colors.text else colors.subText),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Canvas(Modifier.size(9.dp, 5.dp)) {
                val edge = if (expanded) size.height * 0.85f else size.height * 0.15f
                val middle = size.height - edge
                val tint = if (expanded) colors.text else colors.subText
                val width = 1.3.dp.toPx()
                drawLine(tint, Offset(0f, edge), Offset(size.width / 2, middle), width, StrokeCap.Round)
                drawLine(tint, Offset(size.width / 2, middle), Offset(size.width, edge), width, StrokeCap.Round)
            }
        }
        if (!expanded) return@Box

        val density = LocalDensity.current
        val windowSize = LocalWindowInfo.current.containerSize
        val position = remember(density) {
            with(density) { ParameterMenuPosition(6.dp.roundToPx(), 10.dp.roundToPx()) }
        }
        val menuWidth = with(density) {
            if (windowSize.width > 0) (windowSize.width.toDp() - 20.dp).coerceIn(1.dp, 268.dp) else 268.dp
        }
        val menuHeight = availablePopupHeight(
            anchorBounds = anchorBounds,
            windowHeightPx = windowSize.height,
            gap = 6.dp,
            margin = 10.dp,
            minHeight = 72.dp,
            maxHeight = 176.dp,
            density = density
        )
        PopupMenu(
            expanded = true,
            onDismissRequest = { onOpenChange(null) },
            modifier = Modifier.width(menuWidth).heightIn(max = menuHeight),
            properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
            style = PopupMenuDefaults.style(material = PopupMenuMaterial.Glass),
            popupPositionProvider = position,
            scrollable = false
        ) {
            val optionList = remember(options) { options.keys.toList() }
            if (optionList.isEmpty()) return@PopupMenu
            val initialOption = if (selected in options) selected else optionList.first()
            val focusRequesters = remember(optionList) { optionList.associateWith { FocusRequester() } }
            var activeOption by remember { mutableStateOf<T?>(initialOption) }
            var hovered by remember { mutableStateOf<T?>(null) }
            var keyboardNavigation by remember { mutableStateOf(false) }
            val preview by rememberUpdatedState(onHover)
            DisposableEffect(Unit) { onDispose { preview(null) } }
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = menuHeight)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionUp, Key.DirectionDown -> {
                                keyboardNavigation = true
                                hovered = null
                                val currentIndex = activeOption?.let { optionList.indexOf(it) }?.takeIf { it >= 0 }
                                    ?: optionList.indexOf(initialOption).coerceAtLeast(0)
                                val delta = if (event.key == Key.DirectionDown) 1 else -1
                                val nextOption = optionList[(currentIndex + delta).coerceIn(0, optionList.lastIndex)]
                                activeOption = nextOption
                                preview(nextOption)
                                focusRequesters[nextOption]?.requestFocus()
                                true
                            }
                            Key.Tab -> {
                                keyboardNavigation = true
                                hovered = null
                                false
                            }
                            Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
                                val target = activeOption ?: return@onPreviewKeyEvent true
                                onOpenChange(null)
                                if (target != selected) onSelect(target)
                                true
                            }
                            else -> false
                        }
                    }
                    .padding(5.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                BasicText(
                    parameter.label,
                    Modifier.padding(start = 10.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
                    style = subStyle.copy(color = colors.subText, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                )
                val scrollState = rememberScrollState()
                Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    Column(
                        Modifier.fillMaxWidth()
                            .verticalScroll(scrollState)
                            .padding(end = if (scrollState.maxValue > 0) 10.dp else 0.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        for ((value, label) in options) {
                            key(value) {
                                val checked = value == selected
                                val keyboardFocused = keyboardNavigation && activeOption == value
                                val mouseHovered = !keyboardNavigation && hovered == value
                                val optionShape = SaltTheme.shapes.small
                                Row(
                                    Modifier.fillMaxWidth()
                                        .focusRequester(focusRequesters.getValue(value))
                                        .onFocusChanged {
                                            if (it.isFocused && keyboardNavigation && activeOption != value) {
                                                activeOption = value
                                                preview(value)
                                            }
                                        }
                                        .onPointerEvent(PointerEventType.Enter) {
                                            keyboardNavigation = false
                                            hovered = value
                                            activeOption = value
                                            preview(value)
                                        }
                                        .onPointerEvent(PointerEventType.Exit) {
                                            if (!keyboardNavigation && hovered == value) {
                                                hovered = null
                                                activeOption = null
                                                preview(null)
                                            }
                                        }
                                        .clip(optionShape)
                                        .background(when {
                                            checked -> colors.highlight.copy(alpha = if (SaltTheme.configs.isDarkTheme) 0.24f else 0.12f)
                                            keyboardFocused || mouseHovered -> colors.background
                                            else -> Color.Transparent
                                        })
                                        .border(
                                            1.dp,
                                            if (keyboardFocused) colors.highlight.copy(alpha = 0.52f) else Color.Transparent,
                                            optionShape
                                        )
                                        .semantics { this[SemanticsProperties.Selected] = checked }
                                        .clickable(role = Role.RadioButton) {
                                            onOpenChange(null)
                                            if (!checked) onSelect(value)
                                        }
                                        .defaultMinSize(minHeight = 32.dp)
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    BasicText(
                                        label,
                                        Modifier.weight(1f),
                                        style = subStyle.copy(color = colors.text,
                                            fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal)
                                    )
                                    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                                        if (checked) {
                                            Canvas(Modifier.fillMaxSize()) {
                                                val middle = Offset(size.width * 0.40f, size.height * 0.73f)
                                                val width = 1.8.dp.toPx()
                                                drawLine(colors.highlight, Offset(size.width * 0.14f, size.height * 0.48f), middle, width, StrokeCap.Round)
                                                drawLine(colors.highlight, middle, Offset(size.width * 0.87f, size.height * 0.24f), width, StrokeCap.Round)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (scrollState.maxValue > 0) {
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(scrollState),
                            modifier = Modifier.align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .padding(top = 2.dp, end = 1.dp, bottom = 2.dp),
                            style = ScrollbarStyle(
                                minimalHeight = 20.dp,
                                thickness = 6.dp,
                                shape = RoundedCornerShape(3.dp),
                                hoverDurationMillis = 150,
                                unhoverColor = colors.subText.copy(alpha = 0.52f),
                                hoverColor = colors.text.copy(alpha = 0.72f)
                            )
                        )
                    }
                }
            }
            LaunchedEffect(initialOption) { focusRequesters[initialOption]?.requestFocus() }
        }
    }
}
