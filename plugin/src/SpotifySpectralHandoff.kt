package automix

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.round
import kotlin.math.sqrt

/** Time-based cue search for track pairs that cannot maintain an audible beat match. */
internal object SpotifySpectralHandoff {
    private const val SAMPLES = 10

    private class Timeline(val profile: DspProfile, private val features: DspMixFeatures?) {
        val loudness = MixLoudness(profile, features)
        val start get() = loudness.audibleStart
        val end get() = loudness.audibleEnd

        fun sample(time: Double): DoubleArray {
            if (features != null) {
                val found = features.times.binarySearch(time)
                val index = (if (found >= 0) found else -found - 2).coerceIn(features.times.indices)
                return DoubleArray(DspMixFeatures.WIDTH) { features.value(index, it) }
            }
            return DoubleArray(DspMixFeatures.WIDTH).apply {
                val bass = profile.lowRatio1s.getOrNull(time.toInt()) ?: 0.0
                this[DspMixFeatures.BASS] = bass.coerceIn(0.0, 1.0)
            }
        }
    }

    private data class Window(
        val cue: Double,
        val samples: Array<DoubleArray>,
        val power: DoubleArray,
        val context: Double,
        val skippedProgram: Double,
        val normalization: Double
    )

    private data class Choice(
        val a: Window, val b: Window, val bars: Int, val cost: Double,
        val bass: Double, val pivot: Double, val focused: Boolean
    )

    fun plan(a: DspProfile, b: DspProfile, x: DspMixFeatures?, y: DspMixFeatures?, reason: String): DspRecommendation {
        val out = Timeline(a, x)
        val into = Timeline(b, y)
        val fullSpectrum = x != null && y != null
        var best: Choice? = null
        var compared = 0
        if (minOf(a.duration, b.duration) * 1000 >= Plan.MIN_OVERLAP_MS) {
            for (bars in 1..2) {
                val seconds = minOf(bars * 240.0 / a.bpm, a.duration, b.duration)
                val outgoing = windows(out, seconds, outgoing = true)
                val incoming = windows(into, seconds, outgoing = false)
                for (from in outgoing) {
                    checkDspInterrupted()
                    for (to in incoming) {
                        val choice = evaluate(from, to, bars, fullSpectrum)
                        if (choice.cost < (best?.cost ?: Double.POSITIVE_INFINITY)) best = choice
                        compared++
                    }
                }
            }
        }
        val choice = best ?: return DspRecommendation(
            Plan(a.duration, 0.0, a.bpm, b.bpm, 1, "off", false, 0.5),
            listOf("Spotify 算法 · 可用音频不足以重叠，直接衔接")
        )
        val eq = if (choice.bass > 0.17) "bass-fade-out" else "none"
        val plan = Plan(
            milliseconds(choice.a.cue), milliseconds(choice.b.cue), a.bpm, b.bpm, choice.bars,
            if (choice.focused) "mix-handoff" else "mix-power",
            eq != "none", choice.pivot, eq = eq
        )
        val duration = minOf(plan.duration, a.duration - plan.cueA, b.duration - plan.cueB)
        val sources = if (fullSpectrum) "响度、音色和频段重叠" else "可用的响度和低频特征"
        return DspRecommendation(plan, listOf(
            "Spotify 算法 · $reason，使用不锁拍淡化",
            "比较 $compared 组时间窗口，按${sources}与混音响度连续性选点",
            String.format(Locale.ROOT, "A %.3fs → B %.3fs，重叠 %.2fs · %s",
                plan.cueA, plan.cueB, duration, Plan.volumes.getValue(plan.volume))
        ))
    }

    private fun windows(track: Timeline, seconds: Double, outgoing: Boolean): List<Window> {
        val latest = (track.profile.duration - seconds).coerceAtLeast(0.0)
        val preferred = if (outgoing) track.end - seconds else track.start
        val anchor = preferred.coerceIn(0.0, latest)
        val first = if (outgoing) (anchor - 12.0).coerceAtLeast(0.0) else anchor
        // Only search beyond deck B's physical start when its entrance dynamics were measured.
        val entranceRange = minOf(24.0, track.profile.duration * 0.20)
        val last = if (outgoing || !track.loudness.detailed) anchor else (anchor + entranceRange).coerceAtMost(latest)
        val steps = ceil((last - first) / 0.40).toInt().coerceAtLeast(1)
        val normalization = if (track.profile.lufs > -60.0)
            MixLoudness.power((DspPreview.AUDITION_TARGET_LUFS - track.profile.lufs).coerceIn(-12.0, 6.0)) else 1.0
        return (0..steps).map { first + (last - first) * it / steps }.distinct().map { cue ->
            val samples = Array(SAMPLES) { i -> track.sample(cue + seconds * (i + 0.5) / SAMPLES) }
            val power = DoubleArray(SAMPLES) { i ->
                MixLoudness.power(track.loudness.level(cue + seconds * (i + 0.5) / SAMPLES))
            }
            val contextAt = if (outgoing) cue - 0.22 else cue + seconds + 0.22
            val context = track.loudness.contextPower(contextAt)
            val skipped = if (outgoing) track.loudness.programBetween(cue + seconds, track.profile.duration)
                else track.loudness.programBetween(0.0, cue)
            Window(cue, samples, power, context, skipped, normalization)
        }
    }

    private fun evaluate(a: Window, b: Window, bars: Int, fullSpectrum: Boolean): Choice {
        var timbre = 0.0
        var chroma = 0.0
        var bass = 0.0
        var vocal = 0.0
        for (i in 0 until SAMPLES) {
            val x = a.samples[i]
            val y = b.samples[i]
            bass += x[DspMixFeatures.BASS] * y[DspMixFeatures.BASS]
            vocal += x[DspMixFeatures.VOICE] * y[DspMixFeatures.VOICE]
            if (!fullSpectrum) continue
            for (band in 0 until DspMixFeatures.BANDS) {
                val t = x[band] - y[band]
                val c = x[band + DspMixFeatures.CHROMA] - y[band + DspMixFeatures.CHROMA]
                timbre += t * t
                chroma += c * c
            }
        }
        bass /= SAMPLES
        vocal /= SAMPLES
        // Prefer a similar-sounding cue closer to the edge when two windows mix comparably.
        val trim = 0.045 * (a.skippedProgram / 5.0).coerceAtMost(2.0) +
            0.048 * (b.skippedProgram / 6.0).coerceAtMost(2.0)
        val fixedCost = trim + 0.08 * sqrt(timbre / SAMPLES) + 0.08 * sqrt(chroma / SAMPLES) + 0.07 * (bars - 1)
        var bestCost = Double.POSITIVE_INFINITY
        var bestPivot = 0.5
        var focus = false
        for (focused in listOf(false, true)) {
            // Reserve concentrated handoffs for actual evidence of crowded voices or bass.
            if (focused && (!fullSpectrum || vocal < 0.10 && bass < 0.20)) continue
            for (pivot in listOf(0.5, 0.36, 0.64)) {
                var cost = 0.0
                for (i in 0 until SAMPLES) {
                    val u = (i + 0.5) / SAMPLES
                    val incoming = incomingMixPower(u, pivot, focused)
                    val mixA = (1.0 - incoming) * a.power[i]
                    val mixB = incoming * b.power[i]
                    val targetA = (1.0 - u) * a.context
                    val targetB = u * b.context
                    val raw = loudnessDeviation(mixA + mixB, targetA + targetB)
                    val normalized = loudnessDeviation(mixA * a.normalization + mixB * b.normalization,
                        targetA * a.normalization + targetB * b.normalization)
                    val collision = 2 * sqrt(incoming * (1.0 - incoming))
                    val coexistence = 2 * sqrt(a.power[i] * b.power[i]) / (a.power[i] + b.power[i] + 1e-12)
                    cost += 0.04 * (raw + normalized) + collision * coexistence * (0.28 * vocal + 0.16 * bass)
                }
                cost = fixedCost + cost / SAMPLES + 0.08 * abs(pivot - 0.5) + if (focused) 0.015 else 0.0
                if (cost < bestCost) {
                    bestCost = cost
                    bestPivot = pivot
                    focus = focused
                }
            }
        }
        return Choice(a, b, bars, bestCost, bass, bestPivot, focus)
    }

    private fun loudnessDeviation(power: Double, reference: Double): Double {
        val delta = MixLoudness.decibels(power) - MixLoudness.decibels(reference)
        return (abs(delta) - if (delta < 0) 1.5 else 2.5).coerceIn(0.0, 16.0)
    }

    private fun milliseconds(value: Double) = round(value * 1000) / 1000
}
