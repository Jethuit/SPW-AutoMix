@file:OptIn(androidx.compose.runtime.InternalComposeApi::class, com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package automix

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.WorkshopApi
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
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
    val cardShape: Shape
)

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

internal object HostSkin {
    private val theme = Class.forName("androidx.compose.ui.ʰ", false, WorkshopApi.instance.javaClass.classLoader)
    private val configs = theme.getMethod("Ϳ", Composer::class.java)
    private val isDarkFlag = configs.returnType.getMethod("Ϳ")
    private val colors = theme.getMethod("Ԩ", Composer::class.java)
    private val styles = theme.getMethod("ԩ", Composer::class.java)
    private val shapes = theme.getMethod("ԫ", Composer::class.java)
    private val palette = listOf("Ϳ", "Ԩ", "ԩ", "Ԫ", "ԫ", "ԭ", "Ԯ").map { colors.returnType.getMethod(it) }
    private val fonts = listOf("Ϳ", "Ԩ").map { styles.returnType.getMethod(it) }
    private val mediumShape = shapes.returnType.getMethod("Ԩ")

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
            cardShape = cardShape
        )
    }
}

private fun stagePalette(skin: Skin): StagePalette = if (!skin.isDark) {
    StagePalette(
        wellBg = skin.background,
        mixBg = Color(0xFFF8FAFC),
        mixTint = Color.Transparent,
        outsideDim = skin.background.copy(alpha = 0.65f),
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
        wellBg = Color(0xFF111317),
        mixBg = Color(0xFF191D23),
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
        overviewBg = Color(0xFF111317),
        overviewPeak = Color(0xFF64748B),
        overviewWin = skin.accent.copy(alpha = 0.34f)
    )
}

private data class TrackWaves(
    val peaks: IntArray,
    val low: IntArray,
    val mid: IntArray,
    val high: IntArray,
    val beats: DoubleArray,
    val downbeats: DoubleArray,
    val duration: Double,
    val lufs: Double
)

private fun shortTitle(s: String, maxLen: Int = 22): String =
    if (s.length <= maxLen) s else s.take(maxLen - 1) + "…"

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
private fun ParamTile(
    barColor: Color,
    title: String,
    value: String,
    expanded: Boolean = false,
    enabled: Boolean = true,
    showChevron: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val s = LocalSkin.current
    val shape = RoundedCornerShape(8.dp)
    val borderCol = if (expanded) s.accent else s.line
    Row(
        modifier
            .clip(shape)
            .background(s.background)
            .border(if (expanded) 1.dp else 0.5.dp, borderCol, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(13.dp).clip(RoundedCornerShape(2.dp)).background(barColor))
            BasicText(title, style = s.sub.copy(color = if (enabled) s.text else s.muted, fontSize = 11.sp, fontWeight = FontWeight.Medium))
        }
        val suffix = if (showChevron) (if (expanded) " ▴" else " ▾") else ""
        BasicText("$value$suffix", style = s.sub.copy(color = if (expanded) s.accent else s.muted, fontSize = 11.sp))
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

private data class SpwPlaylistTrack(val item: MediaItem, val syncTrackId: String)

private data class SpwPlaylist(
    val id: String,
    val title: String,
    val tracks: List<SpwPlaylistTrack>,
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
    var best = arr[0]
    var dist = abs(best - target)
    for (i in 1 until arr.size) {
        val d = abs(arr[i] - target)
        if (d < dist) {
            best = arr[i]
            dist = d
        }
    }
    return best to dist
}

private fun eqLevel(p: Double, deckIdx: Int, plan: Plan): Double {
    if (plan.eq == "none") return 0.50
    val beatsTotal = 4.0 * plan.bars
    val wSwap = (if (plan.eq == "fast-bass-cut") 0.125 else 0.25) / beatsTotal
    var t = ((p - plan.eqCenter) / wSwap + 0.5).coerceIn(0.0, 1.0)
    t = t * t * (3.0 - 2.0 * t)
    val cut = 1.0 - 10.0.pow(-24.0 / 20.0)
    return 0.08 + 0.42 * (1.0 - cut * (if (deckIdx == 0) t else 1.0 - t))
}

private fun filterLevel(p: Double, deckIdx: Int, plan: Plan): Double? {
    val pv = plan.pivot.coerceIn(0.05, 0.95)
    return when {
        deckIdx == 0 && (plan.filter == "low-out" || plan.filter == "both-low") ->
            if (p <= pv) 0.50 else 0.50 - 0.40 * (p - pv) / max(0.05, 1.0 - pv)
        deckIdx == 1 && (plan.filter == "low-in" || plan.filter == "both-low") ->
            if (p >= pv) 0.50 else 0.50 - 0.40 * (pv - p) / max(0.05, pv)
        else -> null
    }
}

private fun snapCueToOtherTrack(
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
    val dur = plan.duration
    val midU = dur * plan.pivot
    val sp = plan.speed

    fun findBest(movingArr: DoubleArray, fixedArr: DoubleArray, tolSec: Double): Double? {
        if (movingArr.isEmpty() || fixedArr.isEmpty()) return null
        val fixedU = ArrayList<Double>(16)
        for (tF in fixedArr) {
            val uF = if (movingIdx == 0) (tF - plan.cueB) / sp else tF - plan.cueA
            if (uF in -0.05..(dur + 0.05)) fixedU += uF
        }
        if (fixedU.isEmpty()) return null

        var bestCand: Double? = null
        var bestScore = Double.POSITIVE_INFINITY
        for (tM in movingArr) {
            val uM = if (movingIdx == 0) tM - rawCue else (tM - rawCue) / sp
            if (uM !in -0.25..(dur + 0.25)) continue
            for (uF in fixedU) {
                val delta = uM - uF
                val dist = abs(delta)
                if (dist <= tolSec) {
                    val cand = rawCue + if (movingIdx == 0) delta else delta * sp
                    if (cand in minCue..maxCue) {
                        val score = dist + 0.002 * abs(uF - midU)
                        if (score < bestScore) {
                            bestScore = score
                            bestCand = cand
                        }
                    }
                }
            }
        }
        return bestCand
    }

    val movingDown = if (movingIdx == 0) waveA.downbeats else waveB.downbeats
    val fixedDown = if (movingIdx == 0) waveB.downbeats else waveA.downbeats
    findBest(movingDown, fixedDown, downbeatTolSec)?.let { return it to "已对齐双轨强拍" }

    val movingBeats = if (movingIdx == 0) waveA.beats else waveB.beats
    val fixedBeats = if (movingIdx == 0) waveB.beats else waveA.beats
    findBest(movingBeats, fixedBeats, beatTolSec)?.let { return it to "已对齐双轨拍点" }

    return null
}

private fun sampleEnvelope(arr: IntArray, left: Double, span: Double, w: Float, step: Float): FloatArray {
    val n = (w / step).toInt() + 1
    val raw = FloatArray(n)
    if (arr.isEmpty() || w <= 0f || span <= 0.0) return raw
    val maxIdx = arr.lastIndex
    for (i in 0 until n) {
        val x = min(w, i * step)
        val f0 = (left + x / w * span) * 50.0
        val f1 = (left + min(w, x + step) / w * span) * 50.0
        raw[i] = if (f1 - f0 <= 1.25) {
            if (f0 < 0.0 || f0 >= arr.size) 0f
            else {
                val i0 = f0.toInt().coerceIn(0, maxIdx)
                val i1 = (i0 + 1).coerceIn(0, maxIdx)
                val frac = (f0 - i0).toFloat().coerceIn(0f, 1f)
                (arr[i0] * (1f - frac) + arr[i1] * frac) / 255f
            }
        } else {
            val a = floor(f0).toInt().coerceIn(0, arr.size)
            val b = ceil(f1).toInt().coerceIn(0, arr.size)
            var pk = 0
            for (k in a until b) if (arr[k] > pk) pk = arr[k]
            pk / 255f
        }
    }
    return raw
}

private fun smoothAndShape(raw: FloatArray): FloatArray {
    val n = raw.size
    if (n == 0) return raw
    val out = FloatArray(n)
    for (i in 0 until n) {
        val prev = raw[max(0, i - 1)]
        val cur = raw[i]
        val next = raw[min(n - 1, i + 1)]
        val v = (0.18f * prev + 0.64f * cur + 0.18f * next).coerceIn(0f, 1f)
        out[i] = if (v > 0f) v.pow(0.88f) else 0f
    }
    return out
}

private fun buildMirroredPath(amps: FloatArray, step: Float, w: Float, midY: Float, scaleY: Float): Path {
    val path = Path()
    val n = amps.size
    if (n == 0) return path
    path.moveTo(0f, midY - amps[0] * scaleY)
    for (i in 1 until n) {
        path.lineTo(min(w, i * step), midY - amps[i] * scaleY)
    }
    for (i in (n - 1) downTo 0) {
        path.lineTo(min(w, i * step), midY + amps[i] * scaleY)
    }
    path.close()
    return path
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

    val sp = plan.speed
    val centerSec = plan.cueA + plan.duration * 0.5
    val halfSpan = maxOf(waveA.duration, waveB.duration / sp, 60.0) * 1.02
    val tMin = centerSec - halfSpan
    val totalSpan = 2.0 * halfSpan
    val bStart = plan.cueA - plan.cueB / sp

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
                    val pSp = p.speed
                    val pHalf = maxOf(waveA.duration, waveB.duration / pSp, 60.0) * 1.02
                    val pMin = (p.cueA + p.duration * 0.5) - pHalf
                    val pSpan = 2.0 * pHalf
                    val pBStart = p.cueA - p.cueB / pSp
                    val tHit = pMin + (pos.x / size.width) * pSpan
                    if (pos.y <= size.height / 2f) {
                        mover(0, tHit, true)
                    } else {
                        mover(1, (tHit - pBStart) * pSp, true)
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
                    val p = curPlan
                    val pSp = if (dragIdx == 0) 1.0 else p.speed
                    val curSpan = 2.0 * maxOf(waveA.duration, waveB.duration / p.speed, 60.0) * 1.02
                    val rawCue = dragStartCue - (cumDx / size.width) * curSpan * pSp
                    mover(dragIdx, rawCue, false)
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val halfH = h / 2f
        val step = 2f

        val envA = smoothAndShape(sampleEnvelope(waveA.peaks, tMin, totalSpan, w, step))
        drawPath(buildMirroredPath(envA, step, w, halfH * 0.5f, halfH * 0.38f), palette.overviewPeak)

        val envB = smoothAndShape(sampleEnvelope(waveB.peaks, (tMin - bStart) * sp, totalSpan * sp, w, step))
        drawPath(buildMirroredPath(envB, step, w, halfH + halfH * 0.5f, halfH * 0.38f), palette.waveMid.copy(alpha = 0.72f))

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
private fun BeatAlignmentSeam(
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
        val sp = plan.speed
        val uMin = -0.16 * dur
        val uMax = 1.16 * dur
        fun uToX(u: Double): Float = (0.12f + 0.76f * (u / dur).toFloat()) * w

        val uADown = waveA.downbeats.asSequence().map { it - plan.cueA }.filter { it in uMin..uMax }.toList()
        val uBDown = waveB.downbeats.asSequence().map { (it - plan.cueB) / sp }.filter { it in uMin..uMax }.toList()
        val uABeats = waveA.beats.asSequence().map { it - plan.cueA }.filter { it in uMin..uMax }.toList()
        val uBBeats = waveB.beats.asSequence().map { (it - plan.cueB) / sp }.filter { it in uMin..uMax }.toList()

        val barTopA = 3.dp.toPx()
        val barBotA = 10.5.dp.toPx()
        val barTopB = 13.5.dp.toPx()
        val barBotB = 21.dp.toPx()
        val dotYA = 6.75.dp.toPx()
        val dotYB = 17.25.dp.toPx()
        val dotR = 1.7.dp.toPx()
        val alignedStroke = 2.8.dp.toPx()
        val unalignedStroke = 1.8.dp.toPx()
        val unalignedBarCol = skin.muted.copy(alpha = 0.42f)
        val alignedDotCol = skin.text.copy(alpha = 0.75f)
        val unalignedDotCol = skin.muted.copy(alpha = 0.34f)

        for (uA in uADown) {
            val x = uToX(uA)
            val aligned = uBDown.any { abs(it - uA) <= 0.045 }
            drawLine(
                if (aligned) palette.playhead else unalignedBarCol,
                Offset(x, barTopA),
                Offset(x, barBotA),
                if (aligned) alignedStroke else unalignedStroke,
                cap = StrokeCap.Round
            )
        }
        for (uA in uABeats) {
            if (uADown.any { abs(it - uA) <= 0.035 }) continue
            val aligned = uBBeats.any { abs(it - uA) <= 0.040 }
            drawCircle(
                if (aligned) alignedDotCol else unalignedDotCol,
                radius = dotR,
                center = Offset(uToX(uA), dotYA)
            )
        }

        for (uB in uBDown) {
            val x = uToX(uB)
            val aligned = uADown.any { abs(it - uB) <= 0.045 }
            drawLine(
                if (aligned) palette.playhead else unalignedBarCol,
                Offset(x, barTopB),
                Offset(x, barBotB),
                if (aligned) alignedStroke else unalignedStroke,
                cap = StrokeCap.Round
            )
        }
        for (uB in uBBeats) {
            if (uBDown.any { abs(it - uB) <= 0.035 }) continue
            val aligned = uABeats.any { abs(it - uB) <= 0.040 }
            drawCircle(
                if (aligned) alignedDotCol else unalignedDotCol,
                radius = dotR,
                center = Offset(uToX(uB), dotYB)
            )
        }

        drawRect(palette.outsideDim, Offset.Zero, Size(mixL, h))
        drawRect(palette.outsideDim, Offset(mixL + mixW, 0f), Size(w - mixL - mixW, h))

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

@Composable
private fun DeckStage(
    titleA: String,
    artistA: String,
    titleB: String,
    artistB: String,
    waveA: TrackWaves,
    waveB: TrackWaves,
    plan: Plan,
    window: TransitionWindow?,
    snapEnabled: Boolean,
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
    val curPlan by rememberUpdatedState(plan)
    val setter by rememberUpdatedState(onUpdate)
    val seeker by rememberUpdatedState(onSeekPreview)
    val snapOn by rememberUpdatedState(snapEnabled)
    var activeSnapMsg by remember { mutableStateOf<String?>(null) }

    fun minCue(idx: Int, p: Plan): Double =
        if (idx == 0) 0.0 else -(p.duration * p.speed - 0.5).coerceAtLeast(0.0)

    fun maxCueFor(idx: Int): Double =
        max(0.0, (if (idx == 0) waveA.duration else waveB.duration) - 0.5)

    fun clampCue(idx: Int, pos: Double, p: Plan): Double =
        pos.coerceIn(minCue(idx, p), maxCueFor(idx))

    fun moveCue(idx: Int, pos: Double, p: Plan): Plan {
        val clamped = clampCue(idx, pos, p)
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
        val beat = 60.0 / bpm
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
                    shortTitle(title, 26),
                    style = skin.sub.copy(color = skin.text, fontWeight = FontWeight.SemiBold)
                )
                BasicText(
                    "· ${shortTitle(artist, 18)}  ${fmtTime(cue)}",
                    style = skin.sub.copy(color = skin.muted)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip("-1拍", compact = true) { onUpdate(moveCue(idx, cue - beat, plan)) }
                    Chip("+1拍", compact = true) { onUpdate(moveCue(idx, cue + beat, plan)) }
                    Chip("吸附", compact = true) {
                        val mn = minCue(idx, plan)
                        val mx = maxCueFor(idx)
                        val hit = snapCueToOtherTrack(idx, cue, mn, mx, plan, waveA, waveB, 0.65, 0.45)
                        val target = hit?.first ?: nearestIn(tw.downbeats, cue)?.takeIf { it.second <= 0.6 }?.first
                        target?.let {
                            activeSnapMsg = hit?.second ?: "已吸附强拍"
                            onUpdate(moveCue(idx, it, plan))
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText("${bpm.roundToInt()} BPM", style = skin.sub.copy(color = skin.text, fontWeight = FontWeight.Medium))
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
        val speed = if (idx == 0) 1.0 else plan.speed
        val span = plan.duration * speed / 0.76
        val left = cue - 0.12 * span

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(104.dp)
                .background(palette.wellBg)
                .semantics { contentDescription = if (idx == 0) "拖动A轨波形调整退出点" else "拖动B轨波形调整进入点" }
                .pointerInput(idx, tw) {
                    detectTapGestures { pos ->
                        val p = (pos.x / size.width - 0.12f) / 0.76f
                        seeker(p * curPlan.duration)
                    }
                }
                .pointerInput(idx, tw) {
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
                            draggingSwap = abs(startOffset.x - sx) < 22f && (startOffset.y < 24f || startOffset.y > size.height - 24f)
                        },
                        onDragEnd = { activeSnapMsg = null },
                        onDragCancel = { activeSnapMsg = null }
                    ) { change, delta ->
                        change.consume()
                        cumDx += delta.x
                        if (draggingSwap) {
                            var rawSwap = (dragStartSwap + cumDx / (size.width * 0.76)).coerceIn(0.05, 0.95)
                            var snapMsg: String? = null
                            if (snapOn) {
                                for (k in 1 until curPlan.bars) {
                                    val anchor = k.toDouble() / curPlan.bars
                                    if (abs(rawSwap - anchor) <= 0.025) {
                                        rawSwap = anchor
                                        snapMsg = if (k * 2 == curPlan.bars) "已吸附中点 50%" else "已吸附第 $k 小节"
                                        break
                                    }
                                }
                            }
                            activeSnapMsg = snapMsg
                            setter(curPlan.copy(swap = rawSwap))
                        } else {
                            val sp = if (idx == 0) 1.0 else curPlan.speed
                            val curSpan = curPlan.duration * sp / 0.76
                            val minC = minCue(idx, curPlan)
                            val maxC = maxCueFor(idx)
                            val rawCue = (dragStartCue - cumDx / size.width * curSpan).coerceIn(minC, maxC)
                            var finalCue = rawCue
                            var snapMsg: String? = null
                            if (snapOn) {
                                val pxPerSec = size.width / max(curPlan.duration / 0.76, 0.5)
                                val dbTol = (14.0 / pxPerSec).coerceIn(0.045, 0.13)
                                val bTol = (8.0 / pxPerSec).coerceIn(0.025, 0.075)
                                val hit = snapCueToOtherTrack(idx, rawCue, minC, maxC, curPlan, waveA, waveB, dbTol, bTol)
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
            val activeL = mixL + mixW * (window?.phaseStart?.toFloat() ?: 0f)
            val activeR = mixL + mixW * (window?.phaseEnd?.toFloat() ?: 0f)
            if (window != null) {
                drawRect(palette.mixBg, Offset(activeL, 0f), Size(activeR - activeL, h))
                drawRect(palette.mixTint, Offset(activeL, 0f), Size(activeR - activeL, h))
            }

            val totalBeats = 4 * plan.bars
            for (b in 0..totalBeats) {
                val gx = mixL + mixW * b / totalBeats
                drawLine(
                    if (b % 4 == 0) palette.barGrid else palette.beatGrid,
                    Offset(gx, 0f),
                    Offset(gx, h),
                    if (b % 4 == 0) 1.2f else 0.7f
                )
            }

            val step = 2f
            val rawPk = sampleEnvelope(tw.peaks, left, span, w, step)
            val rawLo = sampleEnvelope(tw.low, left, span, w, step)
            val rawMd = sampleEnvelope(tw.mid, left, span, w, step)
            val rawHi = sampleEnvelope(tw.high, left, span, w, step)
            val lowEnv = smoothAndShape(FloatArray(rawPk.size) { i -> max(rawPk[i], rawLo[i]) })
            val midEnv = smoothAndShape(rawMd)
            val highEnv = smoothAndShape(rawHi)

            val midY = h / 2f
            drawPath(buildMirroredPath(lowEnv, step, w, midY, h * 0.44f), palette.waveLow)
            drawPath(buildMirroredPath(midEnv, step, w, midY, h * 0.39f), palette.waveMid)
            drawPath(buildMirroredPath(highEnv, step, w, midY, h * 0.25f), palette.waveHigh)
            drawLine(palette.waveHigh.copy(alpha = 0.75f), Offset(0f, midY), Offset(w, midY), 1.dp.toPx())

            drawRect(palette.outsideDim, Offset.Zero, Size(activeL, h))
            drawRect(palette.outsideDim, Offset(activeR, 0f), Size(w - activeR, h))

            val edgeCol = skin.accent.copy(alpha = 0.28f)
            if (window != null) {
                drawLine(edgeCol, Offset(activeL, 0f), Offset(activeL, h), 1.dp.toPx())
                drawLine(edgeCol, Offset(activeR, 0f), Offset(activeR, h), 1.dp.toPx())
            }

            val haloStroke = Stroke(width = 3.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val haloCol = if (!skin.isDark) Color(0x700F172A) else Color(0xA6000000)
            val curveStroke = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun plotCurve(col: Color, fn: (Double) -> Double?) {
                if (window == null) return
                val path = Path()
                var started = false
                for (k in 0..96) {
                    val t = window.phase(k / 96.0)
                    val v = fn(t)
                    if (v == null) {
                        if (started) {
                            drawPath(path, haloCol, style = haloStroke)
                            drawPath(path, col, style = curveStroke)
                            path.reset()
                            started = false
                        }
                        continue
                    }
                    val pt = Offset(mixL + mixW * t.toFloat(), h * (0.91f - 0.82f * v.toFloat()))
                    if (!started) {
                        path.moveTo(pt.x, pt.y)
                        started = true
                    } else {
                        path.lineTo(pt.x, pt.y)
                    }
                }
                if (started) {
                    drawPath(path, haloCol, style = haloStroke)
                    drawPath(path, col, style = curveStroke)
                }
            }

            plotCurve(palette.curveVol) { t ->
                val g = plan.gains(t)
                if (idx == 0) g.first else g.second
            }
            if (plan.eq != "none") {
                plotCurve(palette.curveEq) { t -> eqLevel(t, idx, plan) }
            }
            if (plan.filter != "none") {
                plotCurve(palette.curveFilter) { t -> filterLevel(t, idx, plan) }
            }

            val sx = mixL + mixW * plan.swap.toFloat()
            drawLine(palette.curveEq.copy(alpha = 0.65f), Offset(sx, 0f), Offset(sx, h), 1.2.dp.toPx())
            val hw = 12.dp.toPx()
            val hh = 5.dp.toPx()
            drawRoundRect(
                palette.curveEq,
                topLeft = Offset(sx - hw / 2f, if (idx == 0) 2.dp.toPx() else h - hh - 2.dp.toPx()),
                size = Size(hw, hh),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
            )

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
                val minC = minCue(idx, curPlan)
                val maxC = maxCueFor(idx)
                val clamped = rawSec.coerceIn(minC, maxC)
                val hit = if (doSnap && snapOn) {
                    snapCueToOtherTrack(idx, clamped, minC, maxC, curPlan, waveA, waveB, 0.65, 0.35)
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
                BeatAlignmentSeam(waveA, waveB, plan, palette, skin, playheadSec, previewMixStart)
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
                    .background(skin.surface.copy(alpha = 0.94f))
                    .border(0.8.dp, skin.line, btnShape)
                    .clickable(enabled = window != null, onClick = onToggleAudition)
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
                    .background(skin.surface.copy(alpha = 0.94f))
                    .border(0.8.dp, skin.line, barShape)
                    .clickable {
                        val nextBars = when (plan.bars) {
                            2 -> 4
                            4 -> 8
                            else -> 2
                        }
                        onUpdate(plan.copy(bars = nextBars))
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

        // 5. Concise 1-line legend & active snap feedback.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Label("波形：蓝低频 · 橙中频 · 白高频  |  曲线：青音量 · 黄低音 · 紫低通  |  绿条双轨强拍对齐", small = true)
            activeSnapMsg?.let { msg ->
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
internal fun NativeEditor(plugin: MainPlugin, onBack: (() -> Unit)? = null) {
    val skin = HostSkin.read(currentComposer)
    val scope = rememberCoroutineScope()
    val savedPlans by plugin.plans.collectAsState()
    CompositionLocalProvider(LocalSkin provides skin) {
        var library by remember { mutableStateOf(emptyList<MediaItem>()) }
        var queue by remember { mutableStateOf(QueueView()) }
        val queueEntries = queue.entries
        val queueTracks = remember(queueEntries) { queueEntries.map { it.item } }
        var playlists by remember { mutableStateOf(emptyList<SpwPlaylist>()) }
        var selectedPlaylistId by remember { mutableStateOf<String?>(null) }
        var playlistNote by remember { mutableStateOf("") }
        var visibleCount by remember { mutableStateOf(30) }
        val playlistScroll = rememberScrollState()
        var a by remember { mutableStateOf<MediaItem?>(null) }
        var b by remember { mutableStateOf<MediaItem?>(null) }
        var playlistTab by remember { mutableStateOf("queue") }
        var message by remember { mutableStateOf("正在读取队列与曲库…") }
        var queueNote by remember { mutableStateOf("") }
        var refreshing by remember { mutableStateOf(true) }
        var movingQueue by remember { mutableStateOf(false) }

        fun selectPair(pair: Pair<MediaItem, MediaItem>) {
            a = pair.first
            b = pair.second
        }

        suspend fun loadSpwPlaylists(tracks: List<MediaItem>) {
            if (tracks.isEmpty()) return
            val byId = tracks.associateBy { it.id }
            val byPath = tracks.associateBy { normPathStr(it.path) }
            try {
                val res = plugin.worker.loadPlaylists()
                val err = res["error"]?.jsonPrimitive?.contentOrNull
                val parsed = (res["playlists"] as? JsonArray)?.mapNotNull { el ->
                    val obj = el.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val sortOrdinal = obj["trackSort"]?.jsonPrimitive?.intOrNull ?: 0
                    val desc = obj["descending"]?.jsonPrimitive?.booleanOrNull ?: false
                    val mapped = (obj["tracks"] as? JsonArray)?.mapNotNull { tEl ->
                        val tObj = tEl.jsonObject
                        val tid = tObj["id"]?.jsonPrimitive?.contentOrNull
                        val tpath = tObj["path"]?.jsonPrimitive?.contentOrNull
                        val syncId = tObj["syncTrackId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val item = (tid?.let { byId[it] }) ?: (tpath?.let { byPath[normPathStr(it)] })
                        item?.let { SpwPlaylistTrack(it, syncId) }
                    }.orEmpty()
                    SpwPlaylist(id, title, mapped, sortOrdinal, desc)
                }.orEmpty()
                playlists = parsed
                if (selectedPlaylistId == null || parsed.none { it.id == selectedPlaylistId }) {
                    selectedPlaylistId = (parsed.firstOrNull { it.tracks.size >= 2 } ?: parsed.firstOrNull())?.id
                }
                playlistNote = err.orEmpty()
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
                val bridge = plugin.bridge
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

        LaunchedEffect(plugin) { refreshLists(selectInitialPair = true) }

        LaunchedEffect(plugin, library) {
            val bridge = plugin.bridge ?: return@LaunchedEffect
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

        fun handleMoveQueue(fromIdx: Int, toIdx: Int) {
            if (movingQueue || fromIdx !in queueEntries.indices || toIdx !in queueEntries.indices || fromIdx == toIdx) return
            val bridge = plugin.bridge ?: return
            val from = queueEntries[fromIdx]
            val to = queueEntries[toIdx]
            val order = queue.order
            movingQueue = true
            queueNote = "正在同步队列顺序…"
            scope.launch {
                try {
                    val ok = bridge.moveQueueItem(order, from, to)
                    queue = bridge.queueView(bridge.queueStates().value, library)
                    if (ok) bridge.rescheduleAutomix()
                    queueNote = if (ok) "已同步 SPW 播放队列顺序" else "队列顺序已更新，请确认后重试"
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    queueNote = "播放队列调整失败 · ${e.cause?.message ?: e.message ?: e.javaClass.simpleName}"
                } finally {
                    movingQueue = false
                }
            }
        }

        fun handleMovePlaylist(playlistId: String, fromIdx: Int, toIdx: Int) {
            val pl = playlists.firstOrNull { it.id == playlistId } ?: return
            if (fromIdx !in pl.tracks.indices || toIdx !in pl.tracks.indices || fromIdx == toIdx) return
            val nextTracks = pl.tracks.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
            val switchedSort = pl.trackSort != 0 || pl.descending
            playlists = playlists.map {
                if (it.id == playlistId) it.copy(tracks = nextTracks, trackSort = 0, descending = false) else it
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
        }

        @Composable
        fun MixTrackList(
            tracks: List<MediaItem>,
            rowKeys: List<String>,
            rowNumbers: List<Int> = emptyList(),
            currentTrackIndex: Int = -1,
            canReorder: Boolean,
            moveEnabled: Boolean = true,
            isAdjacent: (Int) -> Boolean = { true },
            maxVisible: Int,
            onMove: (fromIdx: Int, toIdx: Int) -> Unit,
            onShowMore: () -> Unit
        ) {
            val palette = remember(skin) { stagePalette(skin) }
            val animScope = rememberCoroutineScope()
            val stridePx = with(LocalDensity.current) { 78.dp.toPx() }
            val shown = tracks.take(maxVisible)
            val shownKeys = rowKeys.take(maxVisible)
            var draggingId by remember { mutableStateOf<String?>(null) }
            var dragOriginIdx by remember { mutableStateOf(-1) }
            var dragOffsetY by remember { mutableStateOf(0f) }
            var pendingSettleId by remember { mutableStateOf<String?>(null) }
            var pendingSettleY by remember { mutableStateOf(0f) }

            val dragTargetIdx = if (draggingId != null && dragOriginIdx in shown.indices) {
                (dragOriginIdx + (dragOffsetY / stridePx).roundToInt()).coerceIn(0, shown.lastIndex)
            } else -1

            val curMove by rememberUpdatedState(onMove)

            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                shown.forEachIndexed { idx, track ->
                    val rowKey = shownKeys[idx]
                    key(rowKey) {
                        val isA = a?.id == track.id
                        val isB = b?.id == track.id
                        val isDragging = draggingId == rowKey
                        val animY = remember { Animatable(0f) }
                        var prevIdx by remember { mutableStateOf(idx) }
                        val curIsDragging by rememberUpdatedState(isDragging)

                        val dragShiftTarget = when {
                            draggingId == null || isDragging || dragOriginIdx < 0 || dragTargetIdx < 0 -> 0f
                            dragOriginIdx < dragTargetIdx && idx in (dragOriginIdx + 1)..dragTargetIdx -> -stridePx
                            dragOriginIdx > dragTargetIdx && idx in dragTargetIdx until dragOriginIdx -> stridePx
                            else -> 0f
                        }

                        LaunchedEffect(idx, dragShiftTarget) {
                            if (prevIdx != idx) {
                                val oldIdx = prevIdx
                                prevIdx = idx
                                if (pendingSettleId == rowKey) {
                                    val startY = pendingSettleY
                                    pendingSettleId = null
                                    pendingSettleY = 0f
                                    animY.snapTo(startY)
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

                        val dragScale by animateFloatAsState(
                            targetValue = if (isDragging) 1.025f else 1.0f,
                            animationSpec = spring(dampingRatio = 0.80f, stiffness = Spring.StiffnessMediumLow)
                        )
                        val dragShadow by animateFloatAsState(
                            targetValue = if (isDragging) 12f else 0f,
                            animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)
                        )

                        val rowShape = RoundedCornerShape(8.dp)
                        val rowBg = when {
                            isDragging -> skin.accent.copy(alpha = if (skin.isDark) 0.22f else 0.12f)
                            isA -> palette.curveVol.copy(alpha = if (skin.isDark) 0.14f else 0.08f)
                            isB -> palette.curveFilter.copy(alpha = if (skin.isDark) 0.14f else 0.08f)
                            else -> skin.background
                        }
                        val rowBorder = when {
                            isDragging -> skin.accent
                            isA -> palette.curveVol.copy(alpha = 0.45f)
                            isB -> palette.curveFilter.copy(alpha = 0.45f)
                            else -> skin.line
                        }
                        val hasNext = idx + 1 < tracks.size && isAdjacent(idx) && track.id != tracks[idx + 1].id
                        val hasPrevious = idx > 0 && isAdjacent(idx - 1) && track.id != tracks[idx - 1].id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .zIndex(if (isDragging) 10f else 0f)
                                .graphicsLayer {
                                    translationY = if (isDragging) dragOffsetY else animY.value
                                    scaleX = dragScale
                                    scaleY = dragScale
                                    shadowElevation = dragShadow
                                    shape = rowShape
                                    clip = false
                                }
                                .clip(rowShape)
                                .background(rowBg)
                                .border(if (isDragging) 1.5.dp else 0.5.dp, rowBorder, rowShape)
                                .clickable(enabled = (hasNext || hasPrevious) && draggingId == null) {
                                    if (hasNext) selectPair(track to tracks[idx + 1])
                                    else if (hasPrevious) selectPair(tracks[idx - 1] to track)
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
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
                                            .width(16.dp)
                                            .height(24.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .semantics { contentDescription = "拖动调整歌曲位置" }
                                            .pointerInput(rowKey, shownKeys, moveEnabled) {
                                                if (!moveEnabled) return@pointerInput
                                                detectDragGestures(
                                                    onDragStart = {
                                                        draggingId = rowKey
                                                        dragOriginIdx = idx
                                                        dragOffsetY = 0f
                                                        animScope.launch { animY.snapTo(0f) }
                                                    },
                                                    onDragEnd = {
                                                        val from = dragOriginIdx
                                                        val target = (from + (dragOffsetY / stridePx).roundToInt())
                                                            .coerceIn(0, shownKeys.lastIndex)
                                                        val leftover = dragOffsetY - (target - from) * stridePx
                                                        draggingId = null
                                                        dragOriginIdx = -1
                                                        dragOffsetY = 0f
                                                        if (from >= 0 && target >= 0 && from != target) {
                                                            pendingSettleId = rowKey
                                                            pendingSettleY = leftover
                                                            curMove(from, target)
                                                        } else {
                                                            animScope.launch {
                                                                animY.snapTo(leftover)
                                                                animY.animateTo(
                                                                    0f,
                                                                    spring(dampingRatio = 0.80f, stiffness = 520f)
                                                                )
                                                            }
                                                        }
                                                    },
                                                    onDragCancel = {
                                                        val leftover = dragOffsetY
                                                        draggingId = null
                                                        dragOriginIdx = -1
                                                        dragOffsetY = 0f
                                                        animScope.launch {
                                                            animY.snapTo(leftover)
                                                            animY.animateTo(
                                                                0f,
                                                                spring(dampingRatio = 0.80f, stiffness = 520f)
                                                            )
                                                        }
                                                    }
                                                ) { change, delta ->
                                                    change.consume()
                                                    if (draggingId != rowKey || dragOriginIdx < 0) return@detectDragGestures
                                                    val minY = -dragOriginIdx * stridePx
                                                    val maxY = (shownKeys.lastIndex - dragOriginIdx) * stridePx
                                                    dragOffsetY = (dragOffsetY + delta.y).coerceIn(minY, maxY)
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Canvas(Modifier.size(8.dp, 13.dp)) {
                                            val dotCol = if (isDragging) skin.accent else skin.muted.copy(alpha = 0.65f)
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
                                                color = if (isA || isB || isDragging) skin.text else skin.muted,
                                                fontSize = 11.sp,
                                                fontWeight = if (isA || isB || isDragging) FontWeight.SemiBold else FontWeight.Normal
                                            )
                                        )
                                    }
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    BasicText(
                                        shortTitle(track.title, 16),
                                        style = skin.sub.copy(
                                            color = if (idx == currentTrackIndex) skin.accent else skin.text,
                                            fontWeight = FontWeight.Medium
                                        )
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
                                            "${shortTitle(track.artist, 10)} · ${fmtClock(track.duration / 1000.0)}",
                                            style = skin.sub.copy(color = skin.muted, fontSize = 11.sp)
                                        )
                                    }
                                }
                            }
                            if (canReorder) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    val upEnabled = moveEnabled && idx > 0
                                    val downEnabled = moveEnabled && idx + 1 < shown.size
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

                        if (idx + 1 < shown.size && hasNext) {
                            val t1 = shown[idx]
                            val t2 = shown[idx + 1]
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
                                targetValue = if (draggingId != null) 0.18f else 1f
                            )
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(28.dp)
                                    .graphicsLayer { alpha = connAlpha }
                                    .padding(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (canReorder) {
                                    Box(Modifier.width(16.dp).height(28.dp))
                                }
                                Box(Modifier.width(26.dp).height(28.dp), contentAlignment = Alignment.Center) {
                                    Box(
                                        Modifier
                                            .width(1.5.dp)
                                            .height(28.dp)
                                            .background(if (isEditing || savedPlan != null) skin.accent else skin.line)
                                    )
                                }
                                Box(
                                    Modifier
                                        .clip(pillShape)
                                        .background(pillBg)
                                        .border(0.5.dp, pillBorder, pillShape)
                                        .clickable { a = t1; b = t2 }
                                        .padding(horizontal = 9.dp, vertical = 3.dp)
                                ) {
                                    BasicText(
                                        pillText,
                                        style = skin.sub.copy(
                                            color = pillTextCol,
                                            fontSize = 11.sp,
                                            fontWeight = if (isEditing || savedPlan != null) FontWeight.Medium else FontWeight.Normal
                                        )
                                    )
                                }
                            }
                        } else if (idx + 1 < shown.size) {
                            Spacer(Modifier.height(28.dp))
                        }
                    }
                }
                if (tracks.size > shown.size) {
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                        Chip("展开更多 (${shown.size}/${tracks.size})", compact = true, click = onShowMore)
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
                BasicText(
                    shortTitle(itemA.title, 26),
                    style = skin.sub.copy(color = if (selected) skin.accent else skin.text, fontWeight = FontWeight.Medium)
                )
                BasicText(
                    "→ ${shortTitle(itemB.title, 24)}",
                    style = skin.sub.copy(color = skin.muted)
                )
            }
        }

        @Composable
        fun PlaylistPanel(modifier: Modifier = Modifier) {
            val activePlaylist = playlists.firstOrNull { it.id == selectedPlaylistId }
            val activePlaylistItems = remember(activePlaylist) {
                activePlaylist?.tracks?.map { it.item }.orEmpty()
            }
            val activeListTracks = when (playlistTab) {
                "queue" -> queueTracks
                "playlists" -> activePlaylistItems
                else -> emptyList()
            }
            val listSavedCount = activeListTracks.zipWithNext().withIndex().count { (idx, pair) ->
                val (x, y) = pair
                (playlistTab != "queue" || queueEntries[idx].rawIndex + 1 == queueEntries[idx + 1].rawIndex) &&
                    pairKey(x.id, y.id) in savedPlans
            }
            val cardTitle = if (playlistTab == "playlists" && activePlaylist != null) {
                "歌单 · ${shortTitle(activePlaylist.title, 16)}"
            } else {
                "播放列表"
            }
            val curQueueIdx = queueEntries.indexOfFirst { it.rawIndex == queue.currentIndex }
            val cardSummary = when (playlistTab) {
                "queue" -> {
                    val posText = if (queue.currentIndex in queue.order.indices) {
                        "当前 ${queue.currentIndex + 1}/${queue.order.size}"
                    } else "${queue.order.size} 首队列"
                    "$posText · $listSavedCount 处已自定义 · 共 ${savedPairs.size} 组已保存"
                }
                "playlists" -> if (activePlaylist != null) "${activePlaylist.tracks.size} 首歌曲 · $listSavedCount 处已自定义" else "共 ${playlists.size} 张歌单"
                "saved" -> "${savedPairs.size} 组已保存过渡"
                else -> "从曲库共 ${library.size} 首中自选双轨"
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
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(cardTitle, style = skin.main.copy(color = skin.text, fontWeight = FontWeight.SemiBold))
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Chip("当前转场", compact = true, enabled = queue.nextPair != null) {
                                queue.nextPair?.let(::selectPair)
                            }
                            Chip("刷新", compact = true, enabled = !refreshing && !movingQueue) {
                                refreshing = true
                                queueNote = ""
                                scope.launch { refreshLists(selectInitialPair = false) }
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Chip("队列", selected = playlistTab == "queue", compact = true) {
                            playlistTab = "queue"
                            visibleCount = 30
                        }
                        Chip("歌单 (${playlists.size})", selected = playlistTab == "playlists", compact = true) {
                            playlistTab = "playlists"
                            visibleCount = 30
                        }
                        Chip("已保存 (${savedPairs.size})", selected = playlistTab == "saved", compact = true) {
                            playlistTab = "saved"
                        }
                        Chip("自选", selected = playlistTab == "custom", compact = true) {
                            playlistTab = "custom"
                        }
                    }
                    Label(cardSummary, small = true)
                }

                SaltDivider()

                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(playlistScroll)
                        .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 84.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (playlistTab) {
                        "queue" -> {
                            Label(queue.nextPairNote, small = true)
                            if (queueNote.isNotBlank()) Label(queueNote, small = true)
                            if (queueTracks.isNotEmpty()) {
                                key("queue") {
                                    MixTrackList(
                                        tracks = queueTracks,
                                        rowKeys = queueEntries.map { it.queueId.toString() },
                                        rowNumbers = queueEntries.map { it.rawIndex + 1 },
                                        currentTrackIndex = curQueueIdx,
                                        canReorder = true,
                                        moveEnabled = !movingQueue && !refreshing,
                                        isAdjacent = { i -> queueEntries[i].rawIndex + 1 == queueEntries[i + 1].rawIndex },
                                        maxVisible = visibleCount,
                                        onMove = { fromIdx, toIdx -> handleMoveQueue(fromIdx, toIdx) },
                                        onShowMore = { visibleCount += 30 }
                                    )
                                }
                            } else if (queue.order.isNotEmpty()) {
                                Label("队列里的歌曲未匹配到本地曲库", small = true)
                            }
                        }
                        "playlists" -> {
                            if (playlists.isEmpty()) {
                                Label(playlistNote.ifBlank { "未读取到 SPW 歌单，可点击右上角「刷新」" }, small = true)
                            } else {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    for (pl in playlists) {
                                        Chip("${pl.title} (${pl.tracks.size})", selected = pl.id == selectedPlaylistId, compact = true) {
                                            selectedPlaylistId = pl.id
                                            visibleCount = 30
                                        }
                                    }
                                }
                                SaltDivider()
                                val pl = activePlaylist
                                if (pl == null || pl.tracks.size < 2) {
                                    Label("该歌单不足两首本地曲库歌曲", small = true)
                                } else {
                                    key("pl:${pl.id}") {
                                        MixTrackList(
                                            tracks = activePlaylistItems,
                                        rowKeys = pl.tracks.map { it.syncTrackId },
                                            currentTrackIndex = -1,
                                            canReorder = true,
                                            maxVisible = visibleCount,
                                            onMove = { fromIdx, toIdx -> handleMovePlaylist(pl.id, fromIdx, toIdx) },
                                            onShowMore = { visibleCount += 30 }
                                        )
                                    }
                                    if (playlistNote.isNotBlank()) Label(playlistNote, small = true)
                                }
                            }
                        }
                        "saved" -> {
                            if (savedPairs.isEmpty()) {
                                Label("暂无已保存的曲对过渡", small = true)
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    savedPairs.forEachIndexed { idx, (sA, sB) ->
                                        val isSel = a?.id == sA.id && b?.id == sB.id
                                        PlaylistRowItem("#${idx + 1}", sA, sB, isSel, savedPlans[pairKey(sA.id, sB.id)]) {
                                            selectPair(sA to sB)
                                        }
                                    }
                                }
                            }
                        }
                        "custom" -> if (library.isNotEmpty()) {
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

        val aa = a
        val bb = b
        if (aa != null && bb != null) {
            key(aa.id, bb.id) {
                PairWorkspace(
                    plugin = plugin,
                    a = aa,
                    b = bb,
                    savedPlan = savedPlans[pairKey(aa.id, bb.id)],
                    onBack = onBack,
                    playlistPanel = { mod -> PlaylistPanel(mod) }
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
                                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 84.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                    SaltCard { Label(message.ifBlank { "选择当前转场，或从右侧选择一组歌曲" }, small = true) }
                        }
                    }
                    Box(Modifier.width(0.5.dp).fillMaxHeight().background(skin.line))
                    PlaylistPanel(Modifier.width(310.dp).fillMaxHeight())
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
    savedPlan: Plan?,
    onBack: (() -> Unit)?,
    playlistPanel: @Composable (Modifier) -> Unit
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
    var openParam by remember { mutableStateOf<String?>(null) }
    var showReasons by remember { mutableStateOf(false) }
    var task by remember { mutableStateOf<Job?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var playheadSec by remember { mutableStateOf<Double?>(null) }
    var writingPlan by remember { mutableStateOf(false) }
    val pendingPosition = remember { AtomicReference<Double?>(null) }
    val planKey = pairKey(a.id, b.id)
    val draft = plugin.drafts[planKey]
    val p = draft ?: savedPlan ?: autoPlan
    val window = p?.window(a.duration, b.duration)
    val dirty = draft != null && draft != savedPlan
    val idleStatus by rememberUpdatedState(when {
        dirty -> "有未保存的修改"
        savedPlan != null -> "已加载保存设置"
        else -> "已自动规划"
    })

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
            val data = plugin.worker.job(a.path, b.path)
            fun parseWave(suffix: String): TrackWaves {
                fun ints(k: String) = data.getValue(k).jsonArray.map { it.jsonPrimitive.int }.toIntArray()
                fun dbls(k: String) = (data[k]?.jsonArray ?: JsonArray(emptyList())).map { it.jsonPrimitive.double }.toDoubleArray()
                return TrackWaves(
                    peaks = ints("wave$suffix"),
                    low = ints("low$suffix"),
                    mid = ints("mid$suffix"),
                    high = ints("high$suffix"),
                    beats = dbls("beats$suffix"),
                    downbeats = dbls("downbeats$suffix"),
                    duration = data.getValue("duration$suffix").jsonPrimitive.double,
                    lufs = data["lufs$suffix"]?.jsonPrimitive?.double ?: -14.0
                )
            }
            waveA = parseWave("A")
            waveB = parseWave("B")
            val recommended = Plan.read(data)
            autoPlan = recommended
            reasons = (data["reasons"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
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
                plugin.worker.job(a.path, b.path, plan, mix, pendingPosition::get)
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
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(scroll),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    Modifier
                        .widthIn(max = 1100.dp)
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 84.dp),
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
                        DeckStage(
                            titleA = a.title,
                            artistA = a.artist,
                            titleB = b.title,
                            artistB = b.artist,
                            waveA = wA,
                            waveB = wB,
                            plan = p,
                            window = window,
                            snapEnabled = p.snap,
                            playheadSec = playheadSec,
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

                    // 2. Spotify Bottom Dock: Equal-Width Preset Row + 2x3 Symmetrical Parameter Tiles.
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
                        val isCustom = matchedPreset == null && !isAutoMatch

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            EqualPresetTile("自定义", selected = isCustom, modifier = Modifier.weight(1f)) {
                                if (p.useHost) adjust(p.copy(useHost = false))
                                openParam = openParam ?: "volume"
                            }
                            if (autoPlan != null) {
                                EqualPresetTile("自动", selected = isAutoMatch, modifier = Modifier.weight(1f)) {
                                    autoPlan?.let { adjust(it, "已应用自动推荐") }
                                }
                            }
                            for (preset in Plan.presets) {
                                EqualPresetTile(
                                    preset.label,
                                    selected = preset.matches(p) && !isAutoMatch,
                                    modifier = Modifier.weight(1f)
                                ) {
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
                                ParamTile(
                                    barColor = palette.curveVol,
                                    title = "音量",
                                    value = Plan.volumes[p.volume] ?: p.volume,
                                    expanded = openParam == "volume",
                                    modifier = Modifier.weight(1f)
                                ) { openParam = if (openParam == "volume") null else "volume" }

                                ParamTile(
                                    barColor = palette.curveEq,
                                    title = "均衡器",
                                    value = Plan.eqs[p.eq] ?: p.eq,
                                    expanded = openParam == "eq",
                                    modifier = Modifier.weight(1f)
                                ) { openParam = if (openParam == "eq") null else "eq" }

                                ParamTile(
                                    barColor = palette.curveFilter,
                                    title = "筛选",
                                    value = Plan.filters[p.filter] ?: p.filter,
                                    expanded = openParam == "filter",
                                    modifier = Modifier.weight(1f)
                                ) { openParam = if (openParam == "filter") null else "filter" }
                            }

                            if (openParam != null) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(skin.background)
                                        .border(0.5.dp, skin.line, RoundedCornerShape(8.dp))
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    when (openParam) {
                                        "volume" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            for ((k, v) in Plan.volumes) Chip(v, p.volume == k, compact = true) {
                                                adjust(p.copy(volume = k))
                                                openParam = null
                                            }
                                        }
                                        "eq" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            for ((k, v) in Plan.eqs) Chip(v, p.eq == k, compact = true) {
                                                adjust(p.copy(eq = k, bass = k != "none"))
                                                openParam = null
                                            }
                                        }
                                        "filter" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            for ((k, v) in Plan.filters) Chip(v, p.filter == k, compact = true) {
                                                adjust(p.copy(filter = k))
                                                openParam = null
                                            }
                                        }
                                        "bars" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            for (n in listOf(2, 4, 8)) Chip("$n 个小节", p.bars == n, compact = true) {
                                                adjust(p.copy(bars = n))
                                                openParam = null
                                            }
                                        }
                                    }
                                }
                            }

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ParamTile(
                                    barColor = skin.muted,
                                    title = "效果",
                                    value = if (p.echo) "半拍回声" else "无",
                                    showChevron = false,
                                    modifier = Modifier.weight(1f)
                                ) { adjust(p.copy(echo = !p.echo)) }

                                ParamTile(
                                    barColor = skin.accent,
                                    title = "过渡长度",
                                    value = "${p.bars} 个小节",
                                    expanded = openParam == "bars",
                                    modifier = Modifier.weight(1f)
                                ) { openParam = if (openParam == "bars") null else "bars" }

                                SwitchTile(
                                    barColor = palette.playhead,
                                    title = "节拍匹配",
                                    checked = p.snap,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    val next = !p.snap
                                    val canTempo = (p.bpmA / p.bpmB) in .895..1.105 && wA.beats.isNotEmpty() && wB.beats.isNotEmpty()
                                    val note = when {
                                        !next -> "已关闭节拍匹配与磁吸"
                                        canTempo -> "已开启节拍匹配与磁吸"
                                        else -> "已开启节拍磁吸，保留原速"
                                    }
                                    adjust(p.copy(snap = next, sync = next && canTempo), note)
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
            playlistPanel(Modifier.width(310.dp).fillMaxHeight())
        }
    }
}
