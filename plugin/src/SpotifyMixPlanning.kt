package automix

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class MixAlgorithm(val label: String) {
    Original("原有算法"),
    Spotify("Spotify 算法")
}

internal object SpotifyMixPlanning {
    private enum class Grid(val label: String) {
        Downbeat("强拍"), Beat("普通拍点")
    }

    private data class BeatRatio(val strideA: Int, val strideB: Int) {
        val straight get() = strideA == strideB
    }

    private data class Span(val start: Int, val end: Int, val time: Double)

    private class Deck(val profile: DspProfile, val features: DspMixFeatures) {
        val levels = MixLoudness(profile, features)
    }

    private data class Candidate(
        val a: Span, val cueB: Double, val drift: Double, val driftInBeats: Double,
        val bars: Int, val costs: DoubleArray, val bass: Double, val vocalOverlap: Double, val pivot: Double,
        val renderCost: Double
    )

    private data class Match(val candidate: Candidate, val compared: Int, val grid: Grid, val ratio: BeatRatio)

    fun transition(a: DspProfile, b: DspProfile, x: DspMixFeatures?, y: DspMixFeatures?): DspRecommendation {
        if (x == null || y == null) {
            return SpotifySpectralHandoff.plan(a, b, x, y, "逐拍特征不完整")
        }
        if (minOf(a.bpmConfidence, b.bpmConfidence) < 0.35 || a.beats.size < 5 || b.beats.size < 5) {
            return SpotifySpectralHandoff.plan(a, b, x, y, "拍网可靠度不足")
        }

        val ratio = b.bpm / a.bpm
        val beatRatio = when {
            ratio in 1.75..2.25 -> BeatRatio(1, 2)
            ratio in 0.44..0.57 -> BeatRatio(2, 1)
            else -> BeatRatio(1, 1)
        }
        val out = Deck(a, x)
        val into = Deck(b, y)
        // Prefer musical phrases, then shorter overlaps and regular beats. Native playback keeps each deck's tempo.
        val match = search(out, into, Grid.Downbeat, beatRatio, intArrayOf(4, 2, 8))
            ?: search(out, into, Grid.Downbeat, beatRatio, intArrayOf(1))
            ?: search(out, into, Grid.Beat, beatRatio, intArrayOf(4, 2))
            ?: search(out, into, Grid.Beat, beatRatio, intArrayOf(1))
            ?: return SpotifySpectralHandoff.plan(a, b, x, y, "原速拍点漂移过大或可用拍点窗口不足")

        val chosen = match.candidate
        val mask = chosen.vocalOverlap
        val chroma = chosen.costs[1]
        val bassSwap = chosen.bass > 0.09
        val volume = if (mask > 0.12 || chroma > 0.52) "mix-handoff" else "mix-power"
        val filter = if (mask > 0.22) "low-out" else "none"
        val plan = Plan(
            cueA = milliseconds(chosen.a.time), cueB = milliseconds(chosen.cueB), bpmA = a.bpm, bpmB = b.bpm,
            bars = chosen.bars, volume = volume, bass = bassSwap, swap = chosen.pivot,
            filter = filter, eq = if (bassSwap) "bass-swap" else "none", snap = true
        )
        val rhythm = if (match.ratio.straight) match.grid.label else "倍半速${match.grid.label}"
        return DspRecommendation(plan, listOf(
            "Spotify 算法 · 比较 ${match.compared} 组${rhythm}窗口，按音色、色度、响度、人声倾向、段落、能量突变和漂移选点",
            String.format(Locale.ROOT, "A %.3fs → B %.3fs，%d 小节，原速偏差最大 %.0f ms（%.2f 拍）",
                plan.cueA, plan.cueB, plan.bars, chosen.drift * 1000, chosen.driftInBeats),
            String.format(Locale.ROOT, "人声重叠代理 %.0f%% / 低频重叠 %.0f%% · %s + %s + %s",
                mask * 100, chosen.bass * 100, Plan.volumes.getValue(volume), plan.eq, filter)
        ))
    }

    private fun search(
        out: Deck, into: Deck, grid: Grid, ratio: BeatRatio, lengths: IntArray
    ): Match? {
        var compared = 0
        var best: Candidate? = null
        var bestCost = Double.POSITIVE_INFINITY
        val step = 60.0 * ratio.strideA / out.profile.bpm
        val incomingStep = 60.0 * ratio.strideB / into.profile.bpm
        if (abs(step - incomingStep) > step * 0.22) return null
        for (bars in lengths) {
            val count = 4 * bars / ratio.strideA
            val outgoing = spans(out, count * ratio.strideA, grid, outgoing = true)
            val incoming = spans(into, count * ratio.strideB, grid, outgoing = false)
            val candidates = ArrayList<Candidate>()
            for (from in outgoing) {
                checkDspInterrupted()
                for (to in incoming) {
                    evaluate(out, into, from, to, bars, ratio)?.let(candidates::add)
                }
            }
            if (candidates.isEmpty()) continue

            // ISMIR 2017 Algorithm 1 scales each feature matrix independently.
            val weights = doubleArrayOf(0.17, 0.17, 0.10, 0.14, 0.07, 0.16, 0.11, 0.08)
            val min = DoubleArray(weights.size) { column -> candidates.minOf { it.costs[column] } }
            val max = DoubleArray(weights.size) { column -> candidates.maxOf { it.costs[column] } }
            for (candidate in candidates) {
                val relative = weights.indices.sumOf { i ->
                    val range = max[i] - min[i]
                    if (range > 1e-6) weights[i] * (candidate.costs[i] - min[i]) / range else 0.0
                }
                val absolute = weights.indices.sumOf { i -> weights[i] * candidate.costs[i] }
                // Absolute cost keeps the rankings comparable between overlap lengths.
                val lengthCost = when (bars) {
                    2 -> 0.07
                    8 -> 0.05 + candidate.vocalOverlap * 0.15
                    else -> 0.0
                }
                val score = 0.55 * relative + 0.45 * absolute + lengthCost + candidate.renderCost
                if (score < bestCost) {
                    best = candidate
                    bestCost = score
                }
            }
            compared += candidates.size
        }
        return best?.let { Match(it, compared, grid, ratio) }
    }

    private fun spans(
        deck: Deck, beats: Int, grid: Grid, outgoing: Boolean
    ): List<Span> {
        val times = deck.features.times
        // Apply the paper's head and tail regions to the audible span, including quiet program.
        val first = if (deck.levels.detailed) deck.levels.audibleStart else deck.features.audibleStart
        val last = if (deck.levels.detailed) deck.levels.audibleEnd else deck.features.audibleEnd
        val audibleLength = last - first
        val searchSeconds = if (grid == Grid.Downbeat) 45.0 else 18.0
        val earliestEnd = last - (audibleLength * 0.25).coerceAtMost(searchSeconds)
        val latestStart = first + (audibleLength * 0.20).coerceAtMost(searchSeconds)
        val points = if (grid == Grid.Downbeat) deck.profile.downbeats else deck.profile.beats
        return points.asIterable().mapNotNull { time ->
            val start = times.binarySearch(time)
            val end = start + beats
            if (start < 0 || end !in times.indices) return@mapNotNull null
            val audible = if (outgoing) times[end] in earliestEnd..(last + 0.15)
                else time in (first - 0.25)..latestStart
            if (audible && time >= first - 0.25) Span(start, end, time) else null
        }
    }

    private fun evaluate(
        out: Deck, into: Deck, from: Span, to: Span, bars: Int, ratio: BeatRatio
    ): Candidate? {
        val a = out.profile
        val b = into.profile
        val x = out.features
        val y = into.features
        val count = (from.end - from.start) / ratio.strideA
        val duration = bars * 240.0 / a.bpm
        val deviations = DoubleArray(count + 1) { offset ->
            (y.times[to.start + offset * ratio.strideB] - to.time) -
                (x.times[from.start + offset * ratio.strideA] - from.time)
        }
        val low = deviations.min()
        val high = deviations.max()
        // Use the shorter beat for drift, including the intervening beats of a 2:1 pairing.
        val beat = 60.0 / maxOf(a.bpm, b.bpm)
        val driftLimit = 0.35 * beat
        val latestA = if (out.levels.detailed) out.levels.audibleEnd else x.audibleEnd
        val minShift = maxOf(-to.time, high - driftLimit)
        val maxShift = minOf(b.duration - duration - to.time, low + driftLimit)
        if (minShift > maxShift || from.time + duration > minOf(a.duration, latestA + 0.2)) return null
        var shift = (high + low) / 2
        if (out.levels.detailed && into.levels.detailed) {
            var weighted = 0.0
            var evidence = 0.0
            for (i in 1 until count) {
                val aLevel = out.levels.level(x.times[from.start + i * ratio.strideA])
                val bLevel = into.levels.level(y.times[to.start + i * ratio.strideB])
                val weight = sin(PI * i / count) * MixLoudness.power((aLevel + bLevel) / 2)
                weighted += weight * deviations[i]
                evidence += weight
            }
            if (evidence > 1e-7) shift = weighted / evidence
        }
        shift = shift.coerceIn(minShift, maxShift)
        val cueB = to.time + shift
        val drift = maxOf(abs(low - shift), abs(high - shift))

        fun value(features: DspMixFeatures, start: Int, stride: Int, feature: Int): Double {
            val first = features.value(start, feature)
            return if (stride == 1) first else (first + features.value(start + 1, feature)) * 0.5
        }

        var timbre = 0.0
        var chroma = 0.0
        var quiet = 0.0
        var vocals = 0.0
        var vocalOverlap = 0.0
        var bass = 0.0
        var power = 0.0
        for (k in 0 until count) {
            val i = from.start + k * ratio.strideA
            val j = to.start + k * ratio.strideB
            val weight = sin(PI * (k + 0.5) / count)
            for (f in 0 until DspMixFeatures.BANDS) {
                val tone = value(x, i, ratio.strideA, f) - value(y, j, ratio.strideB, f)
                val pitchBand = f + DspMixFeatures.CHROMA
                val pitch = value(x, i, ratio.strideA, pitchBand) - value(y, j, ratio.strideB, pitchBand)
                timbre += weight * tone * tone
                chroma += weight * pitch * pitch
            }
            quiet += 1.0 - (value(x, i, ratio.strideA, DspMixFeatures.LEVEL) +
                value(y, j, ratio.strideB, DspMixFeatures.LEVEL)) * 0.5
            val voiceA = value(x, i, ratio.strideA, DspMixFeatures.VOICE)
            val voiceB = value(y, j, ratio.strideB, DspMixFeatures.VOICE)
            vocals += (voiceA + voiceB) * 0.5
            vocalOverlap += weight * voiceA * voiceB
            bass += weight * value(x, i, ratio.strideA, DspMixFeatures.BASS) * value(y, j, ratio.strideB, DspMixFeatures.BASS)
            power += weight
        }
        if (quiet / count > 0.85) return null

        var pivot = 0.5
        var pivotCost = Double.POSITIVE_INFINITY
        val bar = 4 / ratio.strideA
        val handoffs = (bar until count step bar).filter { it in count / 4..3 * count / 4 }.ifEmpty { listOf(count / 2) }
        for (offset in handoffs) {
            val i = from.start + offset * ratio.strideA
            val j = to.start + offset * ratio.strideB
            val voice = x.value(i, DspMixFeatures.VOICE) + y.value(j, DspMixFeatures.VOICE)
            val novelty = x.boundaries[i] + y.boundaries[j]
            val phase = (x.times[i] - from.time) / duration
            val levelGap = if (out.levels.detailed && into.levels.detailed)
                abs(out.levels.level(x.times[i]) - into.levels.level(y.times[j])).coerceAtMost(18.0) / 18 else 0.0
            val cost = voice - novelty * 0.7 + abs(phase - 0.5) + 0.25 * levelGap
            if (cost < pivotCost) {
                pivot = phase.coerceIn(0.25, 0.75)
                pivotCost = cost
            }
        }
        val tail = ((x.audibleEnd - x.times[from.end]) / 60).coerceIn(0.0, 1.0)
        val head = ((to.time - y.audibleStart) / 60).coerceIn(0.0, 1.0)
        val costs = doubleArrayOf(
            sqrt(timbre / (2 * power)), sqrt(chroma / (2 * power)), quiet / count,
            0.65 * vocals / count + 0.35 * vocalOverlap / power,
            1.0 - (x.drops[from.end] + y.drops[to.end]) / 2.0,
            1.0 - (x.boundaries[from.end] + y.boundaries[to.end]) / 2.0,
            drift / driftLimit, (tail + head) / 2
        )
        // The paper's matrix remains the primary objective. Check loudness under the intended fade
        // as well, since this implementation plays at native speed and does not stretch the decks.
        val renderCost = if (out.levels.detailed && into.levels.detailed) {
            val edgeA = out.levels.contextPower(from.time - 0.25)
            val edgeB = into.levels.contextPower(cueB + duration + 0.25)
            val focused = vocalOverlap / power > 0.12 || sqrt(chroma / (2 * power)) > 0.52
            val irregularity = (0..7).sumOf { i ->
                val u = (i + 0.5) / 8
                val weight = incomingMixPower(u, pivot, focused)
                val currentA = MixLoudness.power(out.levels.level(from.time + duration * u))
                val currentB = MixLoudness.power(into.levels.level(cueB + duration * u))
                val mixed = (1 - weight) * currentA + weight * currentB
                val target = (1 - u) * edgeA + u * edgeB
                (abs(MixLoudness.decibels(mixed) - MixLoudness.decibels(target)) - 2).coerceIn(0.0, 14.0)
            } / 8
            val outgoingLoss = out.levels.programBetween(from.time + duration, a.duration)
            val incomingLoss = into.levels.programBetween(0.0, cueB)
            0.043 * irregularity + 0.023 * (outgoingLoss / 7).coerceAtMost(2.0) +
                0.025 * (incomingLoss / 7).coerceAtMost(2.0)
        } else 0.0
        return Candidate(from, cueB, drift, drift / beat, bars, costs, bass / power, vocalOverlap / power, pivot, renderCost)
    }

    data class Track(val index: Int, val bpm: Double, val reliable: Boolean, val key: String?, val timbre: DoubleArray?)

    fun sequenceTrack(index: Int, profile: DspProfile, features: DspMixFeatures?) =
        Track(index, profile.bpm, profile.bpmConfidence >= 0.35, profile.camelot, features?.timbreMean())

    fun sequence(input: List<Track?>, anchorFirst: Boolean = false): PlaylistSequenceResult {
        val tracks = input.filterNotNull()
        val path = ArrayDeque<Track>()
        val waiting = tracks.toMutableSet()
        val anchor = input.firstOrNull()?.takeIf { anchorFirst }
        if (tracks.isNotEmpty()) {
            val seed = anchor ?: tracks.firstOrNull(Track::reliable) ?: tracks.first()
            path.add(seed)
            waiting.remove(seed)
        }
        // HAM-2 grows either end of the playlist using a symmetric acoustic edge cost.
        // Retain each unused vertex's edge from the unchanged end.
        val headCosts = DoubleArray(input.size)
        val tailCosts = DoubleArray(input.size)
        var updateHead = true
        var updateTail = true
        while (waiting.isNotEmpty()) {
            checkDspInterrupted()
            var next: Track? = null
            var prepend = false
            var best = Double.POSITIVE_INFINITY
            val head = path.first()
            val tail = path.last()
            for (track in waiting) {
                val i = track.index
                if (updateHead) headCosts[i] = edgeCost(track, head)
                if (updateTail) tailCosts[i] = if (head === tail) headCosts[i] else edgeCost(track, tail)
                val atHead = anchor == null && headCosts[i] < tailCosts[i]
                val cost = if (atHead) headCosts[i] else tailCosts[i]
                if (cost < best) {
                    next = track
                    prepend = atHead
                    best = cost
                }
            }
            val selected = checkNotNull(next)
            waiting.remove(selected)
            if (prepend) path.addFirst(selected) else path.addLast(selected)
            updateHead = prepend
            updateTail = !prepend
        }

        val ordered = path.toList()
        val longest = ArrayList<Track>()
        var start = 0
        var weak = 0
        for (i in ordered.indices) {
            val end = i == ordered.lastIndex || !strong(ordered[i], ordered[i + 1])
            if (!end) continue
            if (i < ordered.lastIndex) weak++
            if (i - start + 1 > longest.size) {
                longest.clear()
                longest.addAll(ordered.subList(start, i + 1))
            }
            start = i + 1
        }
        val missing = input.indices.filter { input[it] == null }
        if (ordered.isNotEmpty() || missing.isNotEmpty()) {
            weak += if (ordered.isEmpty()) missing.size - 1 else missing.size
        }
        return PlaylistSequenceResult(
            longest.takeIf { it.size >= 2 }.orEmpty().map(Track::index),
            ordered.map(Track::index) + missing, ordered.size, weak
        )
    }

    private fun strong(a: Track, b: Track): Boolean =
        a.reliable && b.reliable && Plan.canAlign(a.bpm, b.bpm, 2) && keyDistance(a.key, b.key) <= 0.6

    private fun edgeCost(a: Track, b: Track): Double {
        val octaves = abs(log2(a.bpm / b.bpm))
        val tempo = if (a.reliable && b.reliable) {
            minOf(octaves, abs(octaves - octaves.roundToInt()) + 0.16) * 2.4
        } else 0.75
        val key = keyDistance(a.key, b.key)
        val x = a.timbre
        val y = b.timbre
        val timbre = if (x != null && y != null) {
            x.indices.sumOf { (x[it] - y[it]) * (x[it] - y[it]) } * 0.5
        } else 0.12
        return sqrt(0.55 * tempo * tempo + 0.35 * key * key + 0.10 * timbre)
    }

    private fun keyDistance(a: String?, b: String?): Double {
        val n = a?.dropLast(1)?.toIntOrNull() ?: return 0.45
        val m = b?.dropLast(1)?.toIntOrNull() ?: return 0.45
        val steps = abs(n - m).coerceAtMost(12)
        val circle = sin(PI * minOf(steps, 12 - steps) / 12)
        val mode = if (a.last() == b.last()) 0.0 else sin(PI / 12)
        return sqrt(circle * circle + mode * mode)
    }

    private fun milliseconds(value: Double) = kotlin.math.round(value * 1000.0) / 1000.0
}
