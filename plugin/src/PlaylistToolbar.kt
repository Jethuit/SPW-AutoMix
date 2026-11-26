package automix

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.*
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
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

private enum class PlaylistToolbarMenu { Playlists, Mix }

@Composable
internal fun PlaylistToolbar(
    playlists: List<SpwPlaylist>,
    current: SpwPlaylist?,
    canPick: Boolean,
    canScan: Boolean,
    canGenerate: Boolean,
    activity: String?,
    onCancel: (() -> Unit)?,
    onPick: (SpwPlaylist) -> Unit,
    onAction: (PlaylistScanMode) -> Unit
) {
    var openMenu by remember { mutableStateOf<PlaylistToolbarMenu?>(null) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f)) {
            val expanded = openMenu == PlaylistToolbarMenu.Playlists && canPick
            ToolbarButton(
                title = current?.title ?: "选择歌单",
                detail = current?.let { "${it.members.size} 首" },
                modifier = Modifier.fillMaxWidth(),
                enabled = canPick && playlists.isNotEmpty(),
                dropdown = true,
                expanded = expanded,
                description = "切换歌单"
            ) { openMenu = if (expanded) null else PlaylistToolbarMenu.Playlists }
            if (expanded) {
                PlaylistPickerPopup(playlists, current, onDismiss = { openMenu = null }) {
                    openMenu = null
                    onPick(it)
                }
            }
        }
        Box {
            val expanded = openMenu == PlaylistToolbarMenu.Mix && canScan && activity == null
            ToolbarButton(
                title = activity ?: "生成混音",
                enabled = canScan && activity == null,
                primary = activity == null,
                dropdown = activity == null,
                expanded = expanded
            ) { openMenu = if (expanded) null else PlaylistToolbarMenu.Mix }
            if (expanded) {
                ToolbarPopup(156.dp, onDismiss = { openMenu = null }) {
                    for (mode in PlaylistScanMode.entries) {
                        ToolbarOption(
                            mode.label,
                            description = mode.description,
                            enabled = mode == PlaylistScanMode.ScanOnly || canGenerate
                        ) {
                            openMenu = null
                            onAction(mode)
                        }
                    }
                }
            }
        }
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
    primary: Boolean = false,
    dropdown: Boolean = false,
    expanded: Boolean = false,
    description: String? = null,
    onClick: () -> Unit
) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(7.dp)
    val filled = primary && enabled
    val text = when {
        filled -> skin.onAccent
        enabled -> skin.text
        else -> skin.muted
    }
    Row(
        modifier.height(32.dp)
            .clip(shape)
            .background(if (filled) skin.accent else skin.background)
            .border(0.5.dp, if (filled || expanded) skin.accent else skin.line, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (description == null) Modifier else Modifier.semantics { contentDescription = description })
            .padding(horizontal = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            title,
            modifier = if (detail == null) Modifier else Modifier.weight(1f),
            style = skin.sub.copy(color = text, fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        detail?.let { BasicText(it, style = skin.sub.copy(color = skin.muted), maxLines = 1) }
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

@Composable
private fun ToolbarPopup(width: Dp, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val skin = LocalSkin.current
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val position = remember(density) {
        with(density) { ParameterMenuPosition(6.dp.roundToPx(), 10.dp.roundToPx(), preferBelow = true) }
    }
    val popupWidth = with(density) {
        if (window.width > 0) (window.width.toDp() - 20.dp).coerceIn(1.dp, width) else width
    }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true)
    ) {
        val shape = RoundedCornerShape(9.dp)
        Column(
            Modifier.width(popupWidth).shadow(10.dp, shape).clip(shape)
                .background(skin.popupBackground()).border(1.dp, skin.line, shape).padding(5.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            content = content
        )
    }
}

@Composable
private fun PlaylistPickerPopup(
    playlists: List<SpwPlaylist>,
    current: SpwPlaylist?,
    onDismiss: () -> Unit,
    onPick: (SpwPlaylist) -> Unit
) {
    val skin = LocalSkin.current
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val matches = remember(playlists, query) { playlists.filter { it.title.contains(query.trim(), ignoreCase = true) } }
    val availableHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val listHeight = if (availableHeight > 0.dp) (availableHeight - 70.dp).coerceIn(32.dp, 300.dp) else 300.dp
    ToolbarPopup(276.dp, onDismiss) {
        val shape = RoundedCornerShape(6.dp)
        Box(
            Modifier.fillMaxWidth().clip(shape).background(skin.background)
                .border(0.5.dp, skin.line, shape).padding(horizontal = 9.dp, vertical = 7.dp)
        ) {
            if (query.isEmpty()) BasicText("搜索歌单", style = skin.sub.copy(color = skin.muted))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    .semantics { contentDescription = "搜索歌单" }
                    .onPreviewKeyEvent { event ->
                        val match = matches.firstOrNull()
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && match != null) {
                            onPick(match)
                            true
                        } else false
                    },
                singleLine = true,
                textStyle = skin.sub.copy(color = skin.text),
                cursorBrush = SolidColor(skin.accent)
            )
        }
        if (matches.isEmpty()) {
            BasicText("未找到歌单", Modifier.padding(9.dp), style = skin.sub.copy(color = skin.muted))
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = listHeight)) {
                items(matches, key = { it.id }) { playlist ->
                    ToolbarOption(playlist.title, "${playlist.members.size} 首", playlist.id == current?.id) {
                        onPick(playlist)
                    }
                }
            }
        }
        LaunchedEffect(focus) { focus.requestFocus() }
    }
}

@Composable
private fun ToolbarOption(
    title: String,
    detail: String? = null,
    checked: Boolean = false,
    description: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val skin = LocalSkin.current
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 32.dp).clip(RoundedCornerShape(6.dp))
            .background(if (checked) skin.accent.copy(alpha = 0.16f) else Color.Transparent)
            .semantics { selected = checked }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val color = when {
                !enabled -> skin.muted
                checked -> skin.accent
                else -> skin.text
            }
            BasicText(title, style = skin.sub.copy(color = color), maxLines = 1, overflow = TextOverflow.Ellipsis)
            description?.let { BasicText(it, style = skin.sub.copy(color = skin.muted, fontSize = 10.sp)) }
        }
        detail?.let { BasicText(it, style = skin.sub.copy(color = skin.muted), maxLines = 1) }
    }
}
