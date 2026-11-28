package automix

import kotlinx.serialization.json.*
import kotlin.math.*

internal enum class EqBand(val cutDb: Double) {
    Low(Plan.HOST_BASS_CUT_DB), Mid(-10.0), High(-8.0)
}

internal enum class TransitionEffect(val id: String, val label: String) {
    ReverbMidFade("reverb-mid-fade", "混响中段淡出"),
    ReverbEndCut("reverb-end-cut", "混响末尾切出"),
    ReverbEndFade("reverb-end-fade", "混响末尾淡出"),
    None("none", "无效果"),
    LegacyEcho("echo", "半拍回声（旧）");

    val reverb get() = this != None && this != LegacyEcho

    fun reverbWet(phase: Double): Double = when (this) {
        ReverbMidFade -> (2.0 * phase).coerceIn(0.0, 1.0)
        ReverbEndCut, ReverbEndFade -> (2.0 * phase - 1.0).coerceIn(0.0, 1.0)
        else -> 0.0
    }

    companion object {
        val options = entries.filter { it != LegacyEcho }.associateWith { it.label }
    }
}

internal data class EqEnvelope(
    val start: Double,
    val end: Double,
    private val from: Double,
    private val to: Double
) {
    fun attenuationAt(phase: Double): Double =
        from + (to - from) * smoothstep((phase - start) / (end - start))
}

internal enum class FilterSweep(val lowPass: Boolean, private val strength: Double = 1.0) {
    LowPass(true), HighPass(false), HighPassHalf(false, 0.5);

    fun cutoff(phase: Double, pivot: Double, outgoing: Boolean): Double {
        val center = pivot.coerceIn(0.05, 0.95)
        val open = (if (outgoing) (1.0 - phase) / (1.0 - center) else phase / center).coerceIn(0.0, 1.0)
        if (lowPass) {
            return Plan.HOST_LPF_MIN_HZ * (Plan.HOST_LPF_MAX_HZ / Plan.HOST_LPF_MIN_HZ).pow(open)
        }
        return Plan.AUDITION_HPF_MIN_HZ * (Plan.AUDITION_HPF_MAX_HZ / Plan.AUDITION_HPF_MIN_HZ).pow((1.0 - open) * strength)
    }

    fun strengthAt(cutoffHz: Double): Double {
        val relative = if (lowPass) ln(Plan.HOST_LPF_MAX_HZ / cutoffHz) / ln(Plan.HOST_LPF_MAX_HZ / Plan.HOST_LPF_MIN_HZ)
            else ln(cutoffHz / Plan.AUDITION_HPF_MIN_HZ) / ln(Plan.AUDITION_HPF_MAX_HZ / Plan.AUDITION_HPF_MIN_HZ)
        return relative.coerceIn(0.0, 1.0)
    }
}

private fun smoothstep(u: Double): Double {
    val t = u.coerceIn(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
}

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

    fun eqKnots(plan: Plan, deck: Int, band: EqBand = EqBand.Low): List<Pair<Double, Double>> {
        val envelope = plan.eqEnvelope(deck, band) ?: return emptyList()
        val span = phaseEnd - phaseStart
        val positions = (0..24).mapTo(sortedSetOf()) { it / 24.0 }
        // A swap can be narrower than one of the regular sampling intervals.
        for (step in 0..12) {
            val phase = envelope.start + (envelope.end - envelope.start) * step / 12.0
            val u = (phase - phaseStart) / span
            if (u in 0.0..1.0) positions += u
        }
        return buildList {
            add(0.0 to (band.cutDb * envelope.attenuationAt(phaseStart)))
            for (u in positions) {
                if (u - last().first >= 1e-5 && u <= 1.0 - 1e-5) {
                    add(u to (band.cutDb * envelope.attenuationAt(phase(u))))
                }
            }
            add(1.0 to (band.cutDb * envelope.attenuationAt(phaseEnd)))
        }
    }

    fun filterKnots(plan: Plan, deck: Int): List<Pair<Double, Double>> {
        val sweep = plan.filterSweep(deck) ?: return emptyList()
        val positions = (0..16).mapTo(sortedSetOf<Double>()) { it / 16.0 }
        val pivot = (plan.pivot - phaseStart) / (phaseEnd - phaseStart)
        if (pivot > 0.0 && pivot < 1.0) positions.add(pivot)
        return positions.map { u -> u to sweep.cutoff(phase(u), plan.pivot, outgoing = deck == 0) }
    }

    fun echoWetKnots(): List<Pair<Double, Double>> = (0..16).map { step ->
        val u = step / 16.0
        val progress = phase(u)
        val rise = ((progress - 0.55) / 0.20).coerceIn(0.0, 1.0)
        // Pisces unloads the outgoing echo at the end of the transition.
        val fade = ((1.0 - progress) / 0.08).coerceIn(0.0, 1.0)
        u to (rise * fade * Plan.HOST_ECHO_MAX_WET)
    }

    val reverbCutStart get() = 1.0 - min(8.0 / durationMs, 0.04)

    fun effectKnots(effect: TransitionEffect): List<Pair<Double, Double>> {
        if (effect == TransitionEffect.LegacyEcho) return echoWetKnots()
        if (!effect.reverb) return listOf(0.0 to 0.0, 1.0 to 0.0)
        val positions = sortedSetOf(0.0, 1.0)
        val middle = (0.5 - phaseStart) / (phaseEnd - phaseStart)
        if (middle in 0.0..1.0) positions += middle
        if (effect == TransitionEffect.ReverbEndCut) positions += reverbCutStart
        return positions.map { u -> u to reverbWet(effect, u) }
    }

    fun reverbWet(effect: TransitionEffect, u: Double): Double {
        val wet = effect.reverbWet(phase(u))
        if (effect != TransitionEffect.ReverbEndCut || u <= reverbCutStart) return wet
        return wet * ((1.0 - u) / (1.0 - reverbCutStart)).coerceIn(0.0, 1.0)
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
        val quickReleaseStart = 0.75
        val earlyFadeEnd = 0.5
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
            "fade-fast-out" -> {
                densify(0.0, earlyFadeEnd)
                densify(quickReleaseStart, 1.0)
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
                "fade-fast-out" -> (1.0 - cosineEase((u - quickReleaseStart) / (1.0 - quickReleaseStart))) to
                    cosineEase(u / earlyFadeEnd)
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
    val effect: TransitionEffect = TransitionEffect.None,
    val eq: String = if (bass) "bass-swap" else "none",
    val snap: Boolean = false,
    val useHost: Boolean = false
) {
    val duration get() = bars * 240.0 / bpmA
    val pivot get() = swap
    val noMix get() = volume == "off"
    val usesSwap: Boolean
        get() = !useHost && !noMix && (
            eq in listOf("bass-swap", "fast-bass-cut", "bass-cut-fast", "bass-cut-long", "three-band") ||
                volume in listOf("overlap", "mid-cut", "fade-cut", "cut-fade") ||
                filterSweep(0) != null ||
                filterSweep(1) != null
            )
    val previewOnlyEq get() = eq == "three-band"
    val noiseTail get() = filter == "noise-out"
    val echo get() = effect == TransitionEffect.LegacyEcho
    fun hasPreviewOnlyEffects(liveHighPass: Boolean = false): Boolean = effect.reverb || previewOnlyEq || noiseTail ||
        (!liveHighPass && (filterSweep(0)?.lowPass == false || filterSweep(1)?.lowPass == false))
    val eqCenter get() = when (eq) {
        "bass-swap-start" -> 0.02
        "bass-swap-end" -> 0.98
        else -> swap
    }

    fun eqEnvelope(deck: Int, band: EqBand = EqBand.Low): EqEnvelope? {
        val outgoing = deck == 0
        if (previewOnlyEq) {
            val (start, end) = when (band) {
                EqBand.Low -> eqCenter - 0.125 / (4 * bars) to eqCenter + 0.125 / (4 * bars)
                EqBand.Mid -> 0.0 to 1.0
                EqBand.High -> 0.08 to 0.92
            }
            return if (outgoing) EqEnvelope(start, end, 0.0, 1.0) else EqEnvelope(start, end, 1.0, 0.0)
        }
        if (band != EqBand.Low) return null
        return when (eq) {
            "bass-swap-start", "bass-swap", "bass-swap-end", "fast-bass-cut" -> {
                val widthInBeats = if (eq == "fast-bass-cut") 0.125 else 0.25
                val halfWidth = (widthInBeats / (4 * bars)).coerceAtLeast(1e-4) / 2.0
                val start = eqCenter - halfWidth
                val end = eqCenter + halfWidth
                if (outgoing) EqEnvelope(start, end, 0.0, 1.0) else EqEnvelope(start, end, 1.0, 0.0)
            }
            "bass-cut-fast" -> if (outgoing) {
                val halfWidth = 0.125 / (4 * bars) / 2.0
                EqEnvelope(eqCenter - halfWidth, eqCenter + halfWidth, 0.0, 1.0)
            } else null
            "bass-cut-long" -> if (outgoing) {
                val center = swap.coerceIn(0.05, 0.95)
                EqEnvelope((center - 0.2).coerceAtLeast(0.0), (center + 0.2).coerceAtMost(1.0), 0.0, 1.0)
            } else null
            "bass-fade-start" -> if (outgoing) EqEnvelope(0.0, 0.50, 0.0, 1.0) else null
            "bass-fade-out" -> if (outgoing) EqEnvelope(0.0, 1.0, 0.0, 1.0) else null
            else -> null
        }
    }

    fun filterSweep(deck: Int): FilterSweep? = when (filter) {
        "both-low" -> FilterSweep.LowPass
        "both-high" -> FilterSweep.HighPass
        "low-out" -> if (deck == 0) FilterSweep.LowPass else null
        "low-in" -> if (deck == 1) FilterSweep.LowPass else null
        "high-out" -> if (deck == 0) FilterSweep.HighPass else null
        "high-in" -> if (deck == 1) FilterSweep.HighPass else null
        "high-out-low-in" -> if (deck == 0) FilterSweep.HighPass else FilterSweep.LowPass
        "low-out-high-in" -> if (deck == 0) FilterSweep.LowPass else FilterSweep.HighPass
        "high-out-half" -> if (deck == 0) FilterSweep.HighPassHalf else null
        else -> null
    }

    fun valid() = listOf(cueA, cueB, bpmA, bpmB, swap).all { it.isFinite() } &&
        cueA >= 0.0 && bpmA in 30.0..300.0 && bpmB in 30.0..300.0 &&
        bars in listOf(2, 4, 8) && (volume in volumes || noMix) && eq in eqs && filter in filters &&
        swap in .05.. .95

    fun window(lengthA: Long, lengthB: Long): TransitionWindow? {
        if (useHost || noMix || !valid()) return null
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
        put("swap", swap); put("sync", false); put("filter", filter); put("echo", echo); put("effect", effect.id)
        put("snap", snap); put("useHost", useHost)
    }

    data class Preset(
        val id: String,
        val label: String,
        val volume: String,
        val eq: String,
        val filter: String,
        val effect: TransitionEffect = TransitionEffect.None,
        val swap: Double = 0.5
    ) {
        fun matches(p: Plan): Boolean =
            !p.useHost && p.volume == volume && p.eq == eq && p.filter == filter && p.effect == effect &&
                (!p.usesSwap || abs(p.swap - swap) < 0.01)

        fun apply(p: Plan): Plan = p.copy(
            volume = volume,
            eq = eq,
            bass = eq != "none",
            filter = filter,
            effect = effect,
            swap = swap,
            useHost = false
        )
    }

    companion object {
        const val MIN_OVERLAP_MS = 200L
        const val HOST_BASS_CUT_DB = -15.0
        const val HOST_LPF_MIN_HZ = 400.0
        const val HOST_LPF_MAX_HZ = 18000.0
        const val AUDITION_HPF_MIN_HZ = 25.0
        const val AUDITION_HPF_MAX_HZ = 1800.0
        const val HOST_ECHO_FEEDBACK = 0.42
        const val HOST_ECHO_MAX_WET = 0.38

        // With the midpoint aligned, allow at most a quarter beat of drift towards either edge.
        fun canAlign(bpmA: Double, bpmB: Double, bars: Int): Boolean =
            bars * 2.0 * abs(bpmB - bpmA) / bpmA <= 0.25

        fun noiseGain(phase: Double): Double {
            if (phase <= 0.55 || phase >= 1.0) return 0.0
            return 0.024 * sin(PI * (phase - 0.55) / 0.45).pow(2)
        }

        val filters = linkedMapOf(
            "none" to "无",
            "low-out" to "低通切出",
            "low-in" to "低通切入",
            "both-low" to "双轨低通",
            "high-out" to "高通切出（仅试听）",
            "high-in" to "高通切入（仅试听）",
            "both-high" to "双轨高通（仅试听）",
            "high-out-low-in" to "低通切入、高通切出（高通仅试听）",
            "low-out-high-in" to "高通切入、低通切出（高通仅试听）",
            "high-out-half" to "半幅高通淡出（仅试听）",
            "noise-out" to "噪音末尾淡出（仅试听）"
        )
        private val nativeHighPassFilters = filters.mapValues { (id, label) ->
            if (id == "noise-out") label else label.substringBefore("（")
        }

        fun filterOptions(liveHighPass: Boolean): Map<String, String> = if (liveHighPass) nativeHighPassFilters else filters

        val eqs = linkedMapOf(
            "none" to "无 EQ",
            "bass-swap-start" to "开头换低音",
            "bass-swap" to "中间换低音",
            "bass-swap-end" to "末尾换低音",
            "fast-bass-cut" to "快速低音交接",
            "bass-cut-fast" to "快速低音切出",
            "bass-cut-long" to "长低音切出",
            "bass-fade-start" to "开始淡出",
            "bass-fade-out" to "低音淡出",
            "three-band" to "三频段淡入淡出（仅试听）"
        )
        val volumes = linkedMapOf(
            "smooth" to "流畅",
            "full-overlap" to "重叠",
            "overlap" to "淡入淡出",
            "fade-cut" to "淡入切出",
            "cut-fade" to "切入淡出",
            "mid-cut" to "瞬切",
            "linear" to "线性",
            "fade-fast-out" to "淡入快出"
        )
        val presets = listOf(
            Preset("fade", "淡化", "smooth", "bass-swap", "none"),
            Preset("rise", "上升", "cut-fade", "bass-swap-start", "low-in"),
            Preset("mix", "混音", "full-overlap", "bass-swap", "none"),
            Preset("swell", "起伏", "full-overlap", "bass-swap", "both-low"),
            Preset("blend", "融入", "overlap", "bass-swap", "low-out"),
            Preset("cut", "瞬切", "mid-cut", "none", "none"),
            Preset("melt", "渐融", "smooth", "bass-fade-out", "both-low"),
            Preset("surge", "涌升", "fade-fast-out", "bass-cut-fast", "low-out", swap = 0.70),
            Preset("linger", "余韵", "smooth", "bass-swap", "none", TransitionEffect.LegacyEcho),
            Preset("off", "无过渡效果", "off", "none", "none")
        )

        fun read(o: JsonObject): Plan {
            fun n(k: String) = o.getValue(k).jsonPrimitive.double
            val bassFlag = o["bass"]?.jsonPrimitive?.boolean ?: true
            val eqMode = o["eq"]?.jsonPrimitive?.content?.takeIf { it in eqs }
                ?: if (bassFlag) "bass-swap" else "none"
            val volMode = o.getValue("volume").jsonPrimitive.content.takeIf { it in volumes || it == "off" } ?: "smooth"
            val filtMode = o["filter"]?.jsonPrimitive?.content?.takeIf { it in filters } ?: "none"
            val effectId = o["effect"]?.jsonPrimitive?.content
            val effect = TransitionEffect.entries.firstOrNull { it.id == effectId }
                ?: if (o["echo"]?.jsonPrimitive?.boolean == true) TransitionEffect.LegacyEcho else TransitionEffect.None
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
                effect = effect,
                eq = eqMode,
                snap = o["snap"]?.jsonPrimitive?.boolean ?: (o["sync"]?.jsonPrimitive?.boolean ?: false),
                useHost = o["useHost"]?.jsonPrimitive?.boolean ?: false
            )
        }
    }
}

internal fun pairKey(a: String, b: String) = "${a.length}:$a$b"
