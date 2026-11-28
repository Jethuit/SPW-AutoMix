@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, com.moriafly.salt.ui.UnstableSaltUiApi::class)

package automix

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.moriafly.salt.ui.BasicButton
import com.moriafly.salt.ui.ButtonColors
import com.moriafly.salt.ui.ControlSize

// Google's Material Symbols Outlined (24 px). SVG paths from
// https://github.com/google/material-design-icons/tree/master/symbols/web
// Licensed under Apache-2.0. See META-INF/LICENSE-Material-Symbols.
internal enum class MaterialSymbol(val label: String, private val svgPath: String) {
    Undo("撤销", "M280-200v-80h284q63 0 109.5-40T720-420q0-60-46.5-100T564-560H312l104 104-56 56-200-200 200-200 56 56-104 104h252q97 0 166.5 63T800-420q0 94-69.5 157T564-200H280Z"),
    Redo("重做", "M396-200q-97 0-166.5-63T160-420q0-94 69.5-157T396-640h252L544-744l56-56 200 200-200 200-56-56 104-104H396q-63 0-109.5 40T240-420q0 60 46.5 100T396-280h284v80H396Z"),
    Refresh("刷新", "M480-160q-134 0-227-93t-93-227q0-134 93-227t227-93q69 0 132 28.5T720-690v-110h80v280H520v-80h168q-32-56-87.5-88T480-720q-100 0-170 70t-70 170q0 100 70 170t170 70q77 0 139-44t87-116h84q-28 106-114 173t-196 67Z");

    val path by lazy { PathParser().parsePathString(svgPath).toPath() }
}

@Composable
internal fun MaterialSymbolButton(symbol: MaterialSymbol, enabled: Boolean, onClick: () -> Unit) {
    val skin = LocalSkin.current
    var hovered by remember { mutableStateOf(false) }
    BasicButton(
        onClick = onClick,
        modifier = Modifier.size(30.dp)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .semantics { contentDescription = symbol.label },
        size = ControlSize.Small,
        enabled = enabled,
        shape = skin.smallShape,
        colors = ButtonColors(
            containerColor = if (hovered && enabled) skin.hover else Color.Transparent,
            contentColor = skin.text,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = skin.muted,
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Canvas(Modifier.size(22.dp)) {
            withTransform({
                scale(size.width / 960f, size.height / 960f, pivot = Offset.Zero)
                translate(top = 960f)
            }) {
                drawPath(symbol.path, if (enabled) skin.text else skin.muted.copy(alpha = 0.55f))
            }
        }
    }
}
