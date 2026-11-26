package automix

import kotlinx.serialization.json.*
import kotlin.math.*

internal data class TransitionWindow(
    val startMs: Long,
    val entryMs: Long,
    val durationMs: Long,
    val leadMs: Long,
    val nominalMs: Long
) {
    val phaseStart get() = leadMs.toDouble() / nominalMs
    val phaseEnd get() = (leadMs + durationMs).toDouble() / nominalMs
    val previewBeforeMs get() = min(startMs, leadMs + 3000L)
    val previewOrigin get() = (previewBeforeMs - leadMs) / 1000.0

    fun phase(u: Double) = phaseStart + u * (phaseEnd - phaseStart)

    fun eqKnots(plan: Plan, deck: Int): List<Pair<Double, Double>> {
        val width = (if (plan.eq == "fast-bass-cut") 0.125 else 0.25) / (4 * plan.bars)
        return (0..24).map { step ->
            val u = step / 24.0
            val t = ((phase(u) - plan.eqCenter) / max(1e-4, width) + 0.5).coerceIn(0.0, 1.0)
            val cut = t * t * (3.0 - 2.0 * t)
            u to (Plan.HOST_BASS_CUT_DB * if (deck == 0) cut else 1.0 - cut)
        }
    }

    fun filterKnots(plan: Plan, deck: Int): List<Pair<Double, Double>> {
        val mode = if (deck == 0) "low-out" else "low-in"
        if (plan.filter != mode && plan.filter != "both-low") return emptyList()
        val pivot = plan.pivot.coerceIn(0.05, 0.95)
        val ratio = Plan.HOST_LPF_MAX_HZ / Plan.HOST_LPF_MIN_HZ
        return (0..16).map { step ->
            val u = step / 16.0
            val progress = phase(u)
            val open = if (deck == 0) (1.0 - progress) / (1.0 - pivot) else progress / pivot
            u to (Plan.HOST_LPF_MIN_HZ * ratio.pow(open.coerceIn(0.0, 1.0)))
        }
    }

    fun echoWetKnots(): List<Pair<Double, Double>> = (0..16).map { step ->
        val u = step / 16.0
        val progress = phase(u)
        val rise = ((progress - 0.55) / 0.20).coerceIn(0.0, 1.0)
        // Pisces unloads the outgoing echo at the end of the transition.
        val fade = ((1.0 - progress) / 0.08).coerceIn(0.0, 1.0)
        u to (rise * fade * Plan.HOST_ECHO_MAX_WET)
    }

    /** Sample gain knots densely around sharp cuts/edges while keeping strictly increasing progress in [0, 1]. */
    fun gains(plan: Plan): List<Triple<Double, Double, Double>> {
        val span = phaseEnd - phaseStart
        // Place the volume handoff at the requested phase when possible, inside the audible overlap.
        val pivot = ((plan.pivot - phaseStart) / span).coerceIn(0.05, 0.95)
        val cutWidth = (20.0 / durationMs).coerceIn(1e-4, 0.10)
        val startEdge = if (plan.volume == "full-overlap") (15.0 / durationMs).coerceIn(1e-4, 0.08)
            else minOf(0.04 / span, 0.25)
        val endEdge = if (plan.volume == "full-overlap") (25.0 / durationMs).coerceIn(1e-4, 0.08) else startEdge
        val endEdgeStart = 1.0 - endEdge
        val knots = (0..48).mapTo(sortedSetOf<Double>()) { it / 48.0 }
        fun densify(first: Double, last: Double) {
            for (i in 0..8) knots += first + (last - first) * i / 8.0
        }
        when (plan.volume) {
            "mid-cut" -> densify(pivot - cutWidth / 2.0, pivot + cutWidth / 2.0)
            "overlap" -> knots += pivot
            "full-overlap" -> {
                densify(0.0, startEdge)
                densify(endEdgeStart, 1.0)
            }
            "fade-cut" -> {
                knots += pivot
                densify(endEdgeStart, 1.0)
            }
            "cut-fade" -> {
                knots += pivot
                densify(0.0, startEdge)
            }
        }
        val result = mutableListOf(Triple(0.0, 1.0, 0.0))
        for (u in knots) {
            if (u - result.last().first < 1e-5 || u > 1.0 - 1e-5) continue
            val (a, b) = when (plan.volume) {
                "linear" -> (1.0 - u) to u
                "full-overlap" -> (1.0 - cosineEase((u - endEdgeStart) / endEdge)) to cosineEase(u / startEdge)
                "fade-cut" -> (1.0 - cosineEase((u - endEdgeStart) / endEdge)) to min(1.0, u / pivot)
                "cut-fade" -> min(1.0, (1.0 - u) / (1.0 - pivot)) to sin(PI / 2.0 * min(1.0, u / startEdge))
                "overlap" -> min(1.0, (1.0 - u) / (1.0 - pivot)) to min(1.0, u / pivot)
                "mid-cut" -> {
                    val b = smoothstep((u - pivot) / cutWidth + 0.5)
                    (1.0 - b) to b
                }
                else -> {
                    val angle = smoothstep(u) * PI / 2.0
                    cos(angle).pow(1.25) to sin(angle).pow(1.25)
                }
            }
            result += Triple(u, a, b)
        }
        result += Triple(1.0, 0.0, 1.0)
        return result
    }

    private fun cosineEase(u: Double) = 0.5 - 0.5 * cos(PI * u.coerceIn(0.0, 1.0))

    private fun smoothstep(u: Double): Double {
        val t = u.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    fun json(plan: Plan) = buildJsonObject {
        put("startMs", startMs)
        put("entryMs", entryMs)
        put("durationMs", durationMs)
        put("beforeMs", previewBeforeMs)
        put("phaseStart", phaseStart)
        put("phaseEnd", phaseEnd)
        putJsonArray("gains") {
            for ((u, a, b) in gains(plan)) addJsonArray { add(u); add(a); add(b) }
        }
    }
}

internal data class Plan(
    val cueA: Double,
    val cueB: Double,
    val bpmA: Double,
    val bpmB: Double,
    val bars: Int,
    val volume: String,
    val bass: Boolean,
    val swap: Double,
    val filter: String = "none",
    val echo: Boolean = false,
    val eq: String = if (bass) "bass-swap" else "none",
    val snap: Boolean = false,
    val useHost: Boolean = false
) {
    val duration get() = bars * 240.0 / bpmA
    val pivot get() = if (eq == "bass-swap-start" || eq == "bass-swap-end") 0.5 else swap
    val eqCenter get() = when (eq) {
        "bass-swap-start" -> 0.02
        "bass-swap-end" -> 0.98
        else -> swap
    }

    fun valid() = listOf(cueA, cueB, bpmA, bpmB, swap).all { it.isFinite() } &&
        cueA >= 0.0 && bpmA in 30.0..300.0 && bpmB in 30.0..300.0 &&
        bars in listOf(2, 4, 8) && volume in volumes && eq in eqs && filter in filters &&
        swap in .05.. .95

    fun window(lengthA: Long, lengthB: Long): TransitionWindow? {
        if (useHost || !valid()) return null
        val nominal = (duration * 1000.0).roundToLong()
        val entry = (cueB * 1000.0).roundToLong()
        if (entry <= -nominal) return null
        val lead = max(0L, -entry)
        val start = (cueA * 1000.0).roundToLong() + lead
        if (start !in 0 until lengthA) return null
        val effectiveEntry = max(0L, entry)
        val available = minOf(nominal - lead, lengthA - start, lengthB - effectiveEntry)
        if (available < MIN_OVERLAP_MS) return null
        return TransitionWindow(start, effectiveEntry, available, lead, nominal)
    }

    fun cueBounds(deck: Int, lengthA: Long, lengthB: Long): ClosedFloatingPointRange<Double> {
        if (deck == 0) {
            val lead = (-(cueB * 1000.0).roundToLong()).coerceAtLeast(0L)
            return 0.0..((lengthA - lead - MIN_OVERLAP_MS).coerceAtLeast(0L) / 1000.0)
        }
        val nominal = (duration * 1000.0).roundToLong()
        val remainingA = lengthA - (cueA * 1000.0).roundToLong()
        val lead = (minOf(nominal, remainingA) - MIN_OVERLAP_MS).coerceAtLeast(0L)
        return (-lead / 1000.0)..((lengthB - MIN_OVERLAP_MS).coerceAtLeast(0L) / 1000.0)
    }

    fun withBars(newBars: Int, lengthA: Long, lengthB: Long): Plan {
        val latestA = (lengthA - MIN_OVERLAP_MS).coerceAtLeast(0L) / 1000.0
        val adjusted = copy(bars = newBars, cueA = cueA.coerceIn(0.0, latestA))
        return adjusted.copy(cueB = cueB.coerceIn(adjusted.cueBounds(1, lengthA, lengthB)))
    }

    fun json() = buildJsonObject {
        put("cueA", cueA); put("cueB", cueB); put("bpmA", bpmA); put("bpmB", bpmB)
        put("bars", bars); put("volume", volume); put("bass", eq != "none"); put("eq", eq)
        put("swap", swap); put("sync", false); put("filter", filter); put("echo", echo)
        put("snap", snap); put("useHost", useHost)
    }

    data class Preset(
        val id: String,
        val label: String,
        val volume: String,
        val eq: String,
        val filter: String,
        val echo: Boolean,
        val swap: Double = 0.5,
        val useHost: Boolean = false
    ) {
        fun matches(p: Plan): Boolean =
            p.useHost == useHost && (useHost || (
                p.volume == volume && p.eq == eq && p.filter == filter && p.echo == echo &&
                    abs(p.swap - swap) < 0.01
                ))

        fun apply(p: Plan): Plan = if (useHost) p.copy(useHost = true) else p.copy(
            volume = volume,
            eq = eq,
            bass = eq != "none",
            filter = filter,
            echo = echo,
            swap = swap,
            useHost = false
        )
    }

    companion object {
        const val MIN_OVERLAP_MS = 200L
        const val HOST_BASS_CUT_DB = -15.0
        const val HOST_LPF_MIN_HZ = 400.0
        const val HOST_LPF_MAX_HZ = 18000.0
        const val HOST_ECHO_FEEDBACK = 0.42
        const val HOST_ECHO_MAX_WET = 0.38

        // With the midpoint aligned, allow at most a quarter beat of drift towards either edge.
        fun canAlign(bpmA: Double, bpmB: Double, bars: Int): Boolean =
            bars * 2.0 * abs(bpmB - bpmA) / bpmA <= 0.25

        val filters = linkedMapOf(
            "none" to "无",
            "low-out" to "低通切出",
            "low-in" to "低通切入",
            "both-low" to "双轨低通"
        )
        val eqs = linkedMapOf(
            "none" to "无 EQ",
            "bass-swap-start" to "开头换低音",
            "bass-swap" to "中间换低音",
            "bass-swap-end" to "末尾换低音",
            "fast-bass-cut" to "快速低音交接"
        )
        val volumes = linkedMapOf(
            "smooth" to "流畅",
            "full-overlap" to "重叠",
            "overlap" to "淡入淡出",
            "fade-cut" to "淡入切出",
            "cut-fade" to "切入淡出",
            "mid-cut" to "瞬切",
            "linear" to "线性"
        )
        val presets = listOf(
            Preset("fade", "淡化", "smooth", "bass-swap", "none", false),
            Preset("swell", "起伏", "full-overlap", "bass-swap", "both-low", false),
            Preset("blend", "融入", "overlap", "bass-swap", "low-out", false),
            Preset("softcut", "柔和切出", "fade-cut", "bass-swap-end", "low-out", false),
            Preset("cut", "瞬切", "mid-cut", "none", "none", false),
            Preset("linger", "余韵", "smooth", "bass-swap", "none", true),
            Preset("host", "沿用 SPW", "smooth", "none", "none", false, useHost = true)
        )

        fun read(o: JsonObject): Plan {
            fun n(k: String) = o.getValue(k).jsonPrimitive.double
            val bassFlag = o["bass"]?.jsonPrimitive?.boolean ?: true
            val eqMode = o["eq"]?.jsonPrimitive?.content?.takeIf { it in eqs }
                ?: if (bassFlag) "bass-swap" else "none"
            val volMode = o.getValue("volume").jsonPrimitive.content.let { if (it in volumes) it else "smooth" }
            val filtMode = o["filter"]?.jsonPrimitive?.content?.takeIf { it in filters } ?: "none"
            return Plan(
                cueA = n("cueA"),
                cueB = n("cueB"),
                bpmA = n("bpmA"),
                bpmB = n("bpmB"),
                bars = o.getValue("bars").jsonPrimitive.int,
                volume = volMode,
                bass = eqMode != "none",
                swap = n("swap"),
                filter = filtMode,
                echo = o["echo"]?.jsonPrimitive?.boolean ?: false,
                eq = eqMode,
                snap = o["snap"]?.jsonPrimitive?.boolean ?: (o["sync"]?.jsonPrimitive?.boolean ?: false),
                useHost = o["useHost"]?.jsonPrimitive?.boolean ?: false
            )
        }
    }
}

internal fun pairKey(a: String, b: String) = "${a.length}:$a$b"
