package automix

import kotlinx.serialization.json.*
import kotlin.math.*

internal data class TransitionWindow(
    val startMs: Long,
    val entryMs: Long,
    val durationMs: Long,
    val leadMs: Long,
    val nominalMs: Long,
    val speed: Double
) {
    val phaseStart get() = leadMs.toDouble() / nominalMs
    val phaseEnd get() = (leadMs + durationMs).toDouble() / nominalMs
    val previewBeforeMs get() = min(3000L, startMs)
    val previewOrigin get() = (previewBeforeMs - leadMs) / 1000.0

    fun phase(u: Double) = phaseStart + u * (phaseEnd - phaseStart)

    fun gains(plan: Plan) = (0..48).map { plan.gains(phase(it / 48.0)) }

    fun json(plan: Plan) = buildJsonObject {
        put("startMs", startMs)
        put("entryMs", entryMs)
        put("durationMs", durationMs)
        put("beforeMs", previewBeforeMs)
        put("phaseStart", phaseStart)
        put("phaseEnd", phaseEnd)
        put("speed", speed)
        putJsonArray("gains") {
            for ((a, b) in gains(plan)) addJsonArray { add(a); add(b) }
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
    val sync: Boolean,
    val filter: String = "none",
    val echo: Boolean = false,
    val eq: String = if (bass) "bass-swap" else "none",
    val snap: Boolean = sync,
    val useHost: Boolean = false
) {
    val duration get() = bars * 240.0 / bpmA
    val speed get() = if (sync) bpmA / bpmB else 1.0
    val pivot get() = if (eq == "bass-swap-start" || eq == "bass-swap-end") 0.5 else swap
    val eqCenter get() = when (eq) {
        "bass-swap-start" -> 0.02
        "bass-swap-end" -> 0.98
        else -> swap
    }

    fun valid() = listOf(cueA, cueB, bpmA, bpmB, swap).all { it.isFinite() } &&
        cueA >= 0.0 && cueB > -duration * speed + 0.1 && bpmA in 30.0..300.0 && bpmB in 30.0..300.0 &&
        bars in listOf(2, 4, 8) && volume in volumes && eq in eqs && filter in filters &&
        swap in .05.. .95 && speed in .895..1.105

    fun window(lengthA: Long, lengthB: Long, effectiveSpeed: Double = speed): TransitionWindow? {
        if (useHost || !valid()) return null
        val nominal = (duration * 1000.0).roundToLong()
        val entry = (cueB * 1000.0).roundToLong()
        val lead = if (entry < 0) (-entry / effectiveSpeed).roundToLong() else 0L
        val start = (cueA * 1000.0).roundToLong() + lead
        val effectiveEntry = max(0L, entry)
        val available = minOf(nominal - lead, lengthA - start, ((lengthB - effectiveEntry) / effectiveSpeed).toLong())
        if (available < 200L) return null
        return TransitionWindow(start, effectiveEntry, available, lead, nominal, effectiveSpeed)
    }

    fun json() = buildJsonObject {
        put("cueA", cueA); put("cueB", cueB); put("bpmA", bpmA); put("bpmB", bpmB)
        put("bars", bars); put("volume", volume); put("bass", eq != "none"); put("eq", eq)
        put("swap", swap); put("sync", sync); put("filter", filter); put("echo", echo)
        put("snap", snap); put("useHost", useHost)
    }

    private fun smoothstep(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    fun gains(t: Double): Pair<Double, Double> {
        val pv = pivot.coerceIn(0.05, 0.95)
        return when (volume) {
            "linear" -> (1.0 - t) to t
            "full-overlap" -> 1.0 to 1.0
            "fade-cut" -> (if (t <= .96) 1.0 else .5 + .5 * cos(PI * (t - .96) / .04)) to min(1.0, t / pv)
            "cut-fade" -> min(1.0, (1.0 - t) / (1.0 - pv)) to sin(PI * .5 * min(1.0, t / .04))
            "overlap" -> min(1.0, (1.0 - t) / (1.0 - pv)) to min(1.0, t / pv)
            "mid-cut" -> {
                val s = smoothstep((t - pv) / 0.015 + 0.5)
                (1.0 - s) to s
            }
            else -> cos(t * PI / 2.0) to sin(t * PI / 2.0)
        }
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
        val filters = linkedMapOf(
            "none" to "无",
            "low-out" to "低通切出",
            "low-in" to "低通切入",
            "both-low" to "双轨低通"
        )
        val eqs = linkedMapOf(
            "none" to "无 EQ",
            "bass-swap-start" to "开头",
            "bass-swap" to "中间",
            "bass-swap-end" to "末尾",
            "fast-bass-cut" to "快切"
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
            val sync = o.getValue("sync").jsonPrimitive.boolean
            return Plan(
                cueA = n("cueA"),
                cueB = n("cueB"),
                bpmA = n("bpmA"),
                bpmB = n("bpmB"),
                bars = o.getValue("bars").jsonPrimitive.int,
                volume = volMode,
                bass = eqMode != "none",
                swap = n("swap"),
                sync = sync,
                filter = filtMode,
                echo = o["echo"]?.jsonPrimitive?.boolean ?: false,
                eq = eqMode,
                snap = o["snap"]?.jsonPrimitive?.boolean ?: (sync || n("bpmA") / n("bpmB") !in .895..1.105),
                useHost = o["useHost"]?.jsonPrimitive?.boolean ?: false
            )
        }
    }
}

internal fun pairKey(a: String, b: String) = "${a.length}:$a$b"
