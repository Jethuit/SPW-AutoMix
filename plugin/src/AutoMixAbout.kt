@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, com.moriafly.salt.ui.UnstableSaltUiApi::class)

package automix

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.moriafly.salt.ui.BasicButton
import com.moriafly.salt.ui.ButtonColors
import com.moriafly.salt.ui.ControlSize
import com.moriafly.salt.ui.Switcher
import java.awt.Desktop
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AutoMixAboutAction(version: String, updates: AutoMixUpdates) {
    val skin = LocalSkin.current
    val closing = LocalAutoMixPageClosing.current
    var showAbout by remember { mutableStateOf(false) }
    val notice by updates.notice.collectAsState()
    val hasUpdate = notice.available != null
    LaunchedEffect(closing) {
        if (closing) showAbout = false
    }

    var hovered by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(40.dp)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
    ) {
        BasicButton(
            onClick = { showAbout = true },
            modifier = Modifier.size(40.dp).semantics {
                contentDescription = if (hasUpdate) "关于 AutoMix，有可用更新" else "关于 AutoMix"
            },
            size = ControlSize.Regular,
            enabled = !closing,
            shape = skin.smallShape,
            colors = ButtonColors(
                containerColor = if (hovered && !closing) skin.hover else Color.Transparent,
                contentColor = skin.text,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = skin.muted
            ),
            contentPadding = PaddingValues(0.dp)
        ) {
            Canvas(Modifier.size(19.dp)) {
                val stroke = 1.4.dp.toPx()
                drawCircle(skin.text, radius = size.minDimension * 0.42f, style = Stroke(stroke))
                drawCircle(skin.text, radius = size.minDimension * 0.055f, center = Offset(center.x, size.height * 0.33f))
                drawLine(skin.text, Offset(center.x, size.height * 0.48f),
                    Offset(center.x, size.height * 0.67f), strokeWidth = stroke, cap = StrokeCap.Round)
            }
        }
        if (hasUpdate) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp)
                .size(6.dp).background(skin.accent, CircleShape))
        }
    }

    if (showAbout && !closing) {
        AutoMixAboutDialog(version, notice, onClose = { showAbout = false },
            onCheck = updates::checkManually, onToggle = updates::setEnabled)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutoMixAboutDialog(
    version: String,
    notice: AutoMixUpdateNotice,
    onClose: () -> Unit,
    onCheck: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val skin = LocalSkin.current
    val scope = rememberCoroutineScope()
    var openingLink by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf(false) }

    fun openLink(uri: URI) {
        if (openingLink) return
        openingLink = true
        linkError = false
        scope.launch {
            try {
                linkError = withContext(Dispatchers.IO) {
                    runCatching { Desktop.getDesktop().browse(uri) }.isFailure
                }
            } finally {
                openingLink = false
            }
        }
    }

    val checkedTime = remember(notice.checkedAt) {
        if (notice.checkedAt > 0) DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(notice.checkedAt))
        else "尚无记录"
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(
        dismissOnBackPress = true, dismissOnClickOutside = true, usePlatformDefaultWidth = false
    )) {
        val shape = skin.largeShape
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(max = 440.dp).clip(shape)
                .background(skin.popupBackground()).border(1.dp, skin.line, shape)
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BasicText("关于 AutoMix", Modifier.weight(1f), style = skin.main.copy(color = skin.text, fontWeight = FontWeight.SemiBold))
                BasicText("v${version.removePrefix("v")}", style = skin.sub.copy(color = skin.muted))
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AboutActionButton("项目主页 ↗", enabled = !openingLink, link = true) { openLink(URI(AUTO_MIX_PROJECT)) }
                    AboutActionButton("问题反馈 ↗", enabled = !openingLink, link = true) { openLink(URI("$AUTO_MIX_PROJECT/issues")) }
                    AboutActionButton("开源许可", enabled = !openingLink, link = true) { openLink(URI("$AUTO_MIX_PROJECT/blob/HEAD/LICENSE")) }
                }
                Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicText("作者", style = skin.sub.copy(color = skin.muted))
                    BasicText("Jethuit", style = skin.sub.copy(color = skin.text))
                }
            }

            Row(
                Modifier.fillMaxWidth().clip(skin.smallShape).background(skin.background)
                    .border(1.dp, skin.line, skin.smallShape)
                    .clickable(enabled = notice.ready, role = Role.Switch) { onToggle(!notice.enabled) }
                    .semantics { toggleableState = if (notice.enabled) ToggleableState.On else ToggleableState.Off }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText("自动检查更新", style = skin.main.copy(color = skin.text))
                    BasicText("每天检查一次，检查失败时不打扰", style = skin.sub.copy(color = skin.muted))
                }
                Switcher(state = notice.enabled)
            }

            val available = notice.available
            val status = when {
                notice.checking -> "正在检查…"
                notice.checkError != null -> "检查失败"
                available != null -> "有可用更新 v${available.version}"
                notice.result is AutoMixReleaseCheck.Current -> "已是最新版本"
                notice.result == AutoMixReleaseCheck.NoRelease -> "暂无正式版本"
                else -> "尚未检查"
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BasicText("上次成功检查  $checkedTime", Modifier.weight(1f), style = skin.sub.copy(color = skin.muted))
                BasicText(status, style = skin.sub.copy(color = when {
                    notice.checking -> skin.muted
                    notice.checkError != null -> skin.error
                    available != null -> skin.accent
                    else -> skin.muted
                }))
            }
            if (!notice.checking) notice.checkError?.let { BasicText(it, style = skin.sub.copy(color = skin.error)) }
            if (notice.preferenceError) BasicText("更新设置无法保存或读取，请重试。", style = skin.sub.copy(color = skin.error))
            if (linkError) BasicText("无法打开浏览器。", style = skin.sub.copy(color = skin.error))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AboutActionButton("检查更新", enabled = !notice.checking, onClick = onCheck)
                AboutActionButton("查看发布页", enabled = !openingLink) {
                    openLink(available?.releasePage ?: URI(AUTO_MIX_RELEASES))
                }
                Spacer(Modifier.weight(1f))
                AboutActionButton("关闭", primary = true, onClick = onClose)
            }
        }
    }
}

@Composable
private fun AboutActionButton(label: String, enabled: Boolean = true, primary: Boolean = false, link: Boolean = false, onClick: () -> Unit) {
    val skin = LocalSkin.current
    var hovered by remember { mutableStateOf(false) }
    val bg = when {
        primary && enabled -> skin.accent
        hovered && enabled -> skin.hover
        link -> Color.Transparent
        else -> skin.background
    }
    val fg = when {
        primary && enabled -> skin.onAccent
        link && enabled -> skin.accent
        enabled -> skin.text
        else -> skin.muted
    }
    BasicButton(
        onClick = onClick,
        modifier = Modifier
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false },
        enabled = enabled,
        size = ControlSize.Small,
        shape = if (link) skin.smallShape else CircleShape,
        colors = ButtonColors(bg, fg, if (link) Color.Transparent else skin.background, skin.muted),
        contentPadding = PaddingValues(horizontal = if (link) 8.dp else 12.dp, vertical = 6.dp)
    ) {
        BasicText(label, style = skin.sub.copy(color = fg, fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal))
    }
}
