package automix

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

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
    onHover: (T?) -> Unit,
    onSelect: (T) -> Unit
) {
    val skin = LocalSkin.current
    val expanded = openParameter == parameter
    val shape = RoundedCornerShape(8.dp)
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth()
                .clip(shape)
                .background(if (expanded) color.copy(alpha = 0.12f) else skin.background)
                .border(if (expanded) 1.dp else 0.5.dp, if (expanded) color else skin.line, shape)
                .clickable(role = Role.Button) { onOpenChange(if (expanded) null else parameter) }
                .padding(horizontal = 8.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(3.dp, 13.dp).clip(RoundedCornerShape(2.dp)).background(color))
            BasicText(
                parameter.label,
                style = skin.sub.copy(color = skin.text, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                maxLines = 1
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                BasicText(
                    options[selected].orEmpty(),
                    style = skin.sub.copy(color = if (expanded) skin.text else skin.muted, fontSize = 11.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Canvas(Modifier.size(9.dp, 6.dp)) {
                val edge = if (expanded) size.height * 0.8f else size.height * 0.2f
                val middle = size.height - edge
                val tint = if (expanded) skin.text else skin.muted
                val width = 1.2.dp.toPx()
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
            if (windowSize.width > 0) (windowSize.width.toDp() - 20.dp).coerceIn(1.dp, 224.dp) else 224.dp
        }
        val menuHeight = with(density) {
            if (windowSize.height > 0) (windowSize.height.toDp() - 20.dp).coerceIn(1.dp, 316.dp) else 316.dp
        }
        Popup(
            popupPositionProvider = position,
            onDismissRequest = { onOpenChange(null) },
            properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true)
        ) {
            var hovered by remember { mutableStateOf<T?>(null) }
            var keyboardNavigation by remember { mutableStateOf(false) }
            val preview by rememberUpdatedState(onHover)
            DisposableEffect(Unit) { onDispose { preview(null) } }
            val firstFocus = remember { FocusRequester() }
            val focusManager = LocalFocusManager.current
            val initialOption = if (selected in options) selected else options.keys.first()
            val menuShape = RoundedCornerShape(12.dp)
            val menuColor = skin.popupBackground()
            Column(
                Modifier.width(menuWidth)
                    .heightIn(max = menuHeight)
                    .shadow(12.dp, menuShape)
                    .clip(menuShape)
                    .background(menuColor)
                    .border(1.dp, skin.line, menuShape)
                    .verticalScroll(rememberScrollState())
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionUp, Key.DirectionDown -> {
                                keyboardNavigation = true
                                val direction = if (event.key == Key.DirectionUp) FocusDirection.Up else FocusDirection.Down
                                focusManager.moveFocus(direction)
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
                    style = skin.sub.copy(color = skin.muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                )
                for ((value, label) in options) {
                    key(value) {
                        var focused by remember { mutableStateOf(false) }
                        val checked = value == selected
                        val requestFocus = if (value == initialOption) Modifier.focusRequester(firstFocus) else Modifier
                        val optionShape = RoundedCornerShape(7.dp)
                        Row(
                            Modifier.fillMaxWidth()
                                .then(requestFocus)
                                .onFocusChanged {
                                    focused = it.isFocused
                                    if (focused && keyboardNavigation) {
                                        hovered = value
                                        preview(value)
                                    }
                                }
                                .onPointerEvent(PointerEventType.Enter) {
                                    keyboardNavigation = false
                                    hovered = value
                                    preview(value)
                                }
                                .onPointerEvent(PointerEventType.Exit) {
                                    if (!keyboardNavigation && hovered == value) {
                                        hovered = null
                                        preview(null)
                                    }
                                }
                                .clip(optionShape)
                                .background(when {
                                    checked -> skin.accent.copy(alpha = if (skin.isDark) 0.24f else 0.12f)
                                    focused || hovered == value -> skin.background
                                    else -> Color.Transparent
                                })
                                .border(1.dp, if (focused) skin.accent.copy(alpha = 0.52f) else Color.Transparent, optionShape)
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
                                style = skin.sub.copy(color = skin.text,
                                    fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal)
                            )
                            Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                                if (checked) {
                                    Canvas(Modifier.fillMaxSize()) {
                                        val middle = Offset(size.width * 0.40f, size.height * 0.73f)
                                        val width = 1.8.dp.toPx()
                                        drawLine(skin.accent, Offset(size.width * 0.14f, size.height * 0.48f), middle, width, StrokeCap.Round)
                                        drawLine(skin.accent, middle, Offset(size.width * 0.87f, size.height * 0.24f), width, StrokeCap.Round)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            LaunchedEffect(firstFocus) { firstFocus.requestFocus() }
        }
    }
}
