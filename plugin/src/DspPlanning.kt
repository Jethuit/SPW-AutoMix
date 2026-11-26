package automix

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

internal data class DspRecommendation(val plan: Plan, val reasons: List<String>)

internal object DspPlanning {
    private data class Entry(val cue: Double, val mainSection: Double?)
    private data class Outro(val bodyEnd: Double, val fadeEnd: Double)
    private data class TransitionStyle(val volume: String, val eq: String, val filter: String)
    private data class Alignment(val cueA: Double, val cueB: Double, val anchor: Double, val score: Score)
    private data class Score(
        val cost: Double,
        val count: Int,
        val levelGap: Double = 0.0,
        val bassOverlap: Double = 0.0
    ) {
        fun describe() = if (count == 0) "响度剖面不足，采用出入点附近的拍点" else format(
            "比较 %d 组拍点组合，所选方案的重叠响度差 %.1f dB，低频重叠指标 %.0f%%",
            count, levelGap, bassOverlap * 100.0
        )
    }
    private data class Handoff(
        val time: Double,
        val distance: Double,
        val boundary: Double,
        val level: DoubleArray,
        val bass: DoubleArray
    )
    private data class SequenceTrack(
        val bpm: Double,
        val reliable: Boolean,
        val camelot: String?,
        val introDb: Double,
        val outroDb: Double,
        val introBass: Double,
        val outroBass: Double
    )
    private data class Edge(val cost: Double, val from: Int, val to: Int)
    private data class Chain(val indices: List<Int>, val cost: Double)

    fun transition(a: DspProfile, b: DspProfile): DspRecommendation {
        val intro = entry(b)
        val tail = outro(a)
        val barA = 240.0 / a.bpm
        val barB = 240.0 / b.bpm
        val tailSpan = (tail.fadeEnd - tail.bodyEnd).coerceAtLeast(0.0)
        val longIntro = intro.mainSection?.let { it - intro.cue >= 4.0 * barB } == true
        val longMix = longIntro && (tailSpan >= 3.5 * barA || a.duration >= 150.0 && b.duration >= 200.0)

        fun targetA(overlap: Double): Double {
            val cut = if (tailSpan >= 2.0 * barA) tail.bodyEnd - overlap * 0.5 else tail.fadeEnd - overlap
            return minOf(a.duration - overlap - 0.5, cut).coerceAtLeast(0.0)
        }

        val reliable = a.bpmConfidence >= 0.35 && b.bpmConfidence >= 0.35 &&
            (a.beats.isNotEmpty() || a.downbeats.isNotEmpty()) && (b.beats.isNotEmpty() || b.downbeats.isNotEmpty())
        val shortCanAlign = Plan.canAlign(a.bpm, b.bpm, 2)
        var bars = 2
        var alignment: Alignment? = null
        var anchorLabel = ""
        if (reliable && shortCanAlign) {
            val driftLimit = 15.0 / abs(b.bpm - a.bpm)
            val candidates = if (longMix) intArrayOf(8, 4, 2) else intArrayOf(4, 2)
            // Consider every viable downbeat length before falling back to ordinary beats.
            candidateGrid@ for (useDownbeats in listOf(true, false)) {
                val gridA = if (useDownbeats) a.downbeats else a.beats
                val gridB = if (useDownbeats) b.downbeats else b.beats
                if (gridA.isEmpty() || gridB.isEmpty()) continue
                for (length in candidates) {
                    if (!Plan.canAlign(a.bpm, b.bpm, length)) continue
                    alignment = chooseAlignment(a, b, gridA, gridB, targetA(length * barA), intro, length, barA, barB, driftLimit)
                    if (alignment != null) {
                        bars = length
                        anchorLabel = if (useDownbeats) "强拍" else "拍点"
                        break@candidateGrid
                    }
                }
            }
        }

        val overlap = bars * barA
        val cueA = alignment?.cueA ?: targetA(overlap)
        val cueB = alignment?.cueB ?: maxOf(intro.cue, intro.mainSection?.minus(overlap * 0.5) ?: intro.cue)
        val lowA = windowLow(a.lowRatio1s, cueA, overlap)
        val lowB = windowLow(b.lowRatio1s, cueB, overlap)
        // Use supported SPW processing for both the editor and the background recommendation.
        val style = when {
            alignment == null -> TransitionStyle("smooth", "none", "none")
            bars == 4 && lowA >= 0.30 && lowB >= 0.30 -> TransitionStyle("overlap", "bass-swap", "low-out")
            bars == 8 && (lowA >= 0.32 && lowB >= 0.20 || lowB >= 0.25) -> TransitionStyle("fade-cut", "bass-swap", "low-out")
            bars == 8 -> TransitionStyle("overlap", "bass-swap", "none")
            else -> TransitionStyle("smooth", "bass-swap", "none")
        }
        val plan = Plan(
            cueA = milliseconds(cueA), cueB = milliseconds(cueB), bpmA = a.bpm, bpmB = b.bpm,
            bars = bars, volume = style.volume, bass = style.eq != "none", swap = 0.5,
            filter = style.filter, eq = style.eq, snap = alignment != null
        )
        val tempoReason = when {
            !reliable -> "至少一轨的拍网置信度不足，采用原速短淡化"
            alignment != null -> "按两轨 BPM 评估漂移，在原速下对齐交接$anchorLabel"
            !shortCanAlign -> "原速下拍点漂移较大，采用 2 小节短淡化"
            else -> "可用区间无法对齐拍点，采用原速短淡化"
        }
        val reasons = buildList {
            add(format(
                "节奏与拍网：A %.2f BPM（置信度 %.2f）→ B %.2f BPM（相对 A %+.1f%%），%s",
                a.bpm, a.bpmConfidence, b.bpm, (b.bpm - a.bpm) / a.bpm * 100.0, tempoReason
            ))
            val main = intro.mainSection?.let { format("B 约 %.1fs 进入持续主段，", it) }.orEmpty()
            val handoff = if (alignment != null) format("双轨%s在过渡开始后 %.2fs 对齐，", anchorLabel, alignment.anchor)
                else "按原速做短淡化，"
            add(format(
                "切点：A 尾段收束 %.1fs / 淡出 %.1fs，%s%s选取 A=%.3fs → B=%.3fs（%d 小节，%.1fs 重叠）",
                tail.bodyEnd, tail.fadeEnd, main, handoff, cueA, cueB, bars, overlap
            ))
            alignment?.let { add("候选评分：" + it.score.describe()) }
            val filterDescription = if (style.filter == "none") "（无扫频滤波）" else "（仅在交接半窗内扫频）"
            add(format(
                "频段与半窗滤波：A 退出低频占比 %.0f%% / B 进入低频占比 %.0f%%，采用 %s + %s + %s%s",
                lowA * 100.0, lowB * 100.0, style.volume, style.eq, style.filter, filterDescription
            ))
            val auditionLoudness = if (DspPreview.canMatchLoudness(a.lufs, b.lufs)) {
                "试听以 ${DspPreview.AUDITION_TARGET_LUFS} LUFS 为目标并预留峰值余量"
            } else {
                "曲目响度过低，试听使用原音量"
            }
            add(format(
                "全曲门控响度：A %.1f LUFS / B %.1f LUFS（差值 %+.1f dB）。%s",
                a.lufs, b.lufs, a.lufs - b.lufs, auditionLoudness
            ))
        }
        return DspRecommendation(plan, reasons)
    }

    fun sequence(profiles: List<DspProfile?>): PlaylistSequenceResult {
        val tracks = profiles.map { it?.let(::sequenceTrack) }
        val tempos = tracks.indices.filter { tracks[it]?.reliable == true }
            .sortedWith(compareBy({ requireNotNull(tracks[it]).bpm }, { it }))
        val edges = buildList {
            for ((position, outgoing) in tempos.withIndex()) {
                checkInterrupted()
                val a = requireNotNull(tracks[outgoing])
                // Limit candidates to the same tempo neighborhood used in the existing analysis.
                for (j in position + 1 until minOf(position + 25, tempos.size)) {
                    val incoming = tempos[j]
                    val b = requireNotNull(tracks[incoming])
                    if (Plan.canAlign(a.bpm, b.bpm, 4)) add(Edge(sequenceCost(a, b), outgoing, incoming))
                    if (Plan.canAlign(b.bpm, a.bpm, 4)) add(Edge(sequenceCost(b, a), incoming, outgoing))
                }
            }
        }

        val parent = IntArray(tracks.size) { it }
        val next = IntArray(tracks.size) { -1 }
        val hasPrevious = BooleanArray(tracks.size)
        val costs = DoubleArray(tracks.size)

        fun root(index: Int): Int {
            var i = index
            while (parent[i] != i) {
                parent[i] = parent[parent[i]]
                i = parent[i]
            }
            return i
        }

        for (edge in edges.sortedWith(compareBy(Edge::cost, Edge::from, Edge::to))) {
            if (next[edge.from] >= 0 || hasPrevious[edge.to]) continue
            val first = root(edge.from)
            val second = root(edge.to)
            if (first == second) continue
            parent[second] = first
            next[edge.from] = edge.to
            hasPrevious[edge.to] = true
            costs[edge.from] = edge.cost
        }

        val chains = tempos.filterNot { hasPrevious[it] }.map { start ->
            val chain = ArrayList<Int>()
            var cost = 0.0
            var index = start
            while (index >= 0) {
                chain.add(index)
                cost += costs[index]
                index = next[index]
            }
            Chain(chain, cost)
        }
        val matched = chains.minWithOrNull(compareBy({ -it.indices.size }, Chain::cost, { it.indices.first() }))
            ?.indices?.takeIf { it.size >= 2 }.orEmpty()
        val ordered = chains.flatMap(Chain::indices) + tracks.indices.filter { tracks[it]?.reliable != true }
        val weakLinks = ordered.zipWithNext().count { (from, to) ->
            val a = tracks[from]
            val b = tracks[to]
            a == null || b == null || !a.reliable || !b.reliable || !Plan.canAlign(a.bpm, b.bpm, 4)
        }
        return PlaylistSequenceResult(matched, ordered, tracks.count { it != null }, weakLinks)
    }

    private fun chooseAlignment(
        a: DspProfile, b: DspProfile, gridA: DoubleArray, gridB: DoubleArray, cueA: Double,
        intro: Entry, bars: Int, barA: Double, barB: Double, driftLimit: Double
    ): Alignment? {
        val overlap = bars * barA
        val half = overlap * 0.5
        val firstOffset = maxOf(0.0, overlap - driftLimit)
        val lastOffset = minOf(overlap, driftLimit)
        val preferredB = maxOf(intro.cue, intro.mainSection?.minus(half) ?: intro.cue)
        val offsets = mutableSetOf(firstOffset, half, lastOffset)

        fun addOffsets(grid: DoubleArray, vararg cues: Double) {
            for (cue in cues) for (beat in grid) {
                val phase = beat - cue
                if (phase in firstOffset..lastOffset) offsets.add(phase)
            }
        }
        addOffsets(gridA, cueA, 0.0, a.duration - overlap - 0.3)
        addOffsets(gridB, preferredB, -0.25, b.duration - overlap)

        val count = bars * 4
        val phase = DoubleArray(count) { (it + 0.5) / count }
        val weights = DoubleArray(count) { sin(PI * phase[it]) }
        val weightSum = weights.sum()
        for (i in weights.indices) weights[i] /= weightSum

        var best: Alignment? = null
        for (anchor in offsets.sortedWith(compareBy({ abs(it - half) }, { it }))) {
            checkInterrupted()
            val remaining = overlap - anchor
            val targetA = cueA + anchor
            val targetB = maxOf(intro.cue + anchor, intro.mainSection ?: 0.0)
            val startA = anchor - 1e-9
            val endA = a.duration - remaining - 0.3 + 1e-9
            val startB = anchor - 0.25 - 1e-9
            val endB = b.duration - remaining + 1e-9
            var eligibleB = gridB.asSequence().filter { it in startB..endB }
            val section = intro.mainSection
            if (section != null && section >= intro.cue + anchor) {
                val nearby = eligibleB.filter { abs(it - section) <= barB + 0.01 }.toList()
                if (nearby.isNotEmpty()) eligibleB = nearby.asSequence()
            }
            val timesA = gridA.asSequence().filter { it in startA..endA && abs(it - targetA) <= 3.0 * barA }
                .sortedWith(compareBy({ abs(it - targetA) }, { it })).take(7).toList()
            val timesB = eligibleB.filter { it - targetB in -2.0 * barB..6.0 * barB }
                .sortedWith(compareBy({ abs(it - targetB) }, { it })).take(9).toList()
            if (timesA.isEmpty() || timesB.isEmpty()) continue
            if (a.lufs2s.isEmpty() || b.lufs2s.isEmpty()) {
                val distance = abs(timesA.first() - targetA) / barA + abs(timesB.first() - targetB) / barB
                if (distance < (best?.score?.cost ?: Double.POSITIVE_INFINITY)) {
                    best = Alignment(maxOf(0.0, timesA.first() - anchor), timesB.first() - anchor, anchor, Score(distance, 0))
                }
                continue
            }
            val positions = DoubleArray(count) { phase[it] * bars * barA - anchor }
            val profilesA = timesA.map { handoff(a, it, targetA, barA, positions, incoming = false) }
            val profilesB = timesB.map { handoff(b, it, targetB, barB, positions, incoming = true) }
            for (out in profilesA) for (entry in profilesB) {
                val score = score(out, entry, weights, profilesA.size * profilesB.size)
                if (score.cost < (best?.score?.cost ?: Double.POSITIVE_INFINITY)) {
                    best = Alignment(maxOf(0.0, out.time - anchor), entry.time - anchor, anchor, score)
                }
            }
        }
        return best
    }

    private fun handoff(
        track: DspProfile, time: Double, target: Double, bar: Double, positions: DoubleArray, incoming: Boolean
    ): Handoff {
        fun levelAt(at: Double) = if (at >= 0.0) sample(track.lufs2s, at, 2.0) - track.lufs else -60.0
        fun bassAt(at: Double) = if (at >= 0.0) sample(track.lowRatio1s, at, 1.0) else 0.0
        val levels = DoubleArray(positions.size) { levelAt(time + positions[it]) }
        val bass = DoubleArray(positions.size) { bassAt(time + positions[it]) * 10.0.pow(minOf(levels[it], 0.0) / 20.0) }
        val direction = if (incoming) 1.0 else -1.0
        fun boundaryChange(metric: (Double) -> Double): Double {
            val before = (metric(time - 0.75 * bar) + metric(time - 0.25 * bar)) * 0.5
            val after = (metric(time + 0.25 * bar) + metric(time + 0.75 * bar)) * 0.5
            return direction * (after - before)
        }
        val boundary = maxOf(boundaryChange(::levelAt) / 6.0, boundaryChange(::bassAt) / 0.24).coerceIn(0.0, 1.0)
        val distance = minOf(1.0, abs(time - target) / ((if (incoming) 6.0 else 3.0) * bar))
        return Handoff(time, distance, boundary, levels, bass)
    }

    private fun score(a: Handoff, b: Handoff, weights: DoubleArray, count: Int): Score {
        var levelGap = 0.0
        var quietDepth = 0.0
        var bassOverlap = 0.0
        for (i in weights.indices) {
            val weight = weights[i]
            levelGap += weight * abs(a.level[i] - b.level[i])
            quietDepth += weight * maxOf(0.0, -10.0 - maxOf(a.level[i], b.level[i]))
            bassOverlap += weight * minOf(a.bass[i], b.bass[i])
        }
        val anchorDistance = (a.distance + b.distance) * 0.5
        val boundaryPenalty = 1.0 - (a.boundary + b.boundary) * 0.5
        val cost = 0.30 * anchorDistance + 0.31 * minOf(1.0, levelGap / 12.0) +
            0.15 * minOf(1.0, quietDepth / 12.0) + 0.14 * minOf(1.0, bassOverlap / 0.35) + 0.10 * boundaryPenalty
        return Score(cost, count, levelGap, bassOverlap)
    }

    private fun entry(track: DspProfile): Entry {
        val grid = track.downbeats.takeIf { it.isNotEmpty() } ?: track.beats
        val levels = track.lufs2s
        val bar = 240.0 / track.bpm
        val introEnd = minOf(12.0 * bar, 45.0, track.duration)
        val audible = maxOf(-42.0, track.lufs - 26.0)
        val cue = milliseconds(grid.asSequence().takeWhile { it <= introEnd }.firstOrNull {
            levels.isEmpty() || levels[(it / 2.0).toInt().coerceIn(levels.indices)] >= audible
        } ?: grid.firstOrNull() ?: 0.0)
        if (levels.size < 4 || cue + 2.0 * bar > introEnd) return Entry(cue, null)

        fun fourSamples(values: DoubleArray, start: Double, step: Double) =
            DoubleArray(4) { sample(values, start + (it + 0.5) * (bar / 2.0), step) }
        fun sustained(values: DoubleArray) = values.count { it >= track.lufs - 3.0 } >= 3

        if (sustained(fourSamples(levels, cue, 2.0))) return Entry(cue, null)
        for (beat in grid) {
            if (beat <= cue + 0.5 * bar) continue
            if (beat > introEnd || beat + 2.0 * bar > track.duration) break
            val after = fourSamples(levels, beat, 2.0)
            if (!sustained(after)) continue
            val before = maxOf(cue, beat - 2.0 * bar)
            if (medianOfFour(after) - medianOfFour(fourSamples(levels, before, 2.0)) >= 2.5) {
                return Entry(cue, milliseconds(beat))
            }
            if (track.lowRatio1s.isNotEmpty()) {
                val bass = medianOfFour(fourSamples(track.lowRatio1s, beat, 1.0))
                if (bass >= 0.28 && bass - medianOfFour(fourSamples(track.lowRatio1s, before, 1.0)) >= 0.14) {
                    return Entry(cue, milliseconds(beat))
                }
            }
        }
        return Entry(cue, null)
    }

    private fun outro(track: DspProfile): Outro {
        if (track.lufs2s.isEmpty()) return Outro(maxOf(0.0, track.duration - 12.0), maxOf(0.0, track.duration - 6.0))
        val fadeThreshold = maxOf(-30.0, track.lufs - 16.0)
        val bodyThreshold = maxOf(-24.0, track.lufs - 4.5)
        val fade = track.lufs2s.indexOfLast { it >= fadeThreshold }.takeIf { it >= 0 }
            ?.let { minOf(track.duration, it * 2.0 + 2.0) } ?: maxOf(0.0, track.duration - 6.0)
        val body = track.lufs2s.indexOfLast { it >= bodyThreshold }.takeIf { it >= 0 }
            ?.let { minOf(fade, it * 2.0 + 2.0) } ?: fade
        return Outro(body, fade)
    }

    private fun sequenceTrack(track: DspProfile): SequenceTrack {
        val entry = entry(track)
        val outro = outro(track)
        val introStart = maxOf(entry.cue, (entry.mainSection ?: entry.cue) - 4.0)
        val outroStart = minOf(outro.bodyEnd, outro.fadeEnd - 8.0).coerceAtLeast(0.0)
        fun level(start: Double): Double {
            val index = (start / 2.0).toInt()
            return if (index in track.lufs2s.indices) mean(track.lufs2s, index, index + 4) - track.lufs else 0.0
        }
        return SequenceTrack(
            track.bpm, track.bpm in 30.0..300.0 && track.bpmConfidence >= 0.35 && track.downbeats.isNotEmpty(),
            track.camelot, level(introStart), level(outroStart),
            mean(track.lowRatio1s, introStart.toInt(), introStart.toInt() + 8),
            mean(track.lowRatio1s, outroStart.toInt(), outroStart.toInt() + 8)
        )
    }

    private fun sequenceCost(a: SequenceTrack, b: SequenceTrack): Double {
        fun keyCost(first: String?, second: String?): Double {
            if (first.isNullOrEmpty() || second.isNullOrEmpty()) return 0.45
            if (first == second) return 0.0
            val from = first.dropLast(1).toIntOrNull() ?: return 0.45
            val to = second.dropLast(1).toIntOrNull() ?: return 0.45
            val delta = abs(from - to)
            return if (delta == 0 || first.last() == second.last() && (delta == 1 || delta == 11)) 0.15 else 1.0
        }

        val tempo = minOf(1.0, abs(ln(b.bpm / a.bpm)) / 0.12)
        val level = minOf(1.0, abs(a.outroDb - b.introDb) / 12.0)
        val bass = minOf(1.0, minOf(a.outroBass, b.introBass) / 0.45)
        return 0.58 * tempo + 0.24 * keyCost(a.camelot, b.camelot) + 0.12 * level + 0.06 * bass
    }

    private fun sample(values: DoubleArray, seconds: Double, step: Double): Double {
        if (values.isEmpty()) return 0.0
        val position = seconds / step - 0.5
        if (position <= 0.0) return values.first()
        if (position >= values.lastIndex) return values.last()
        val i = position.toInt()
        return values[i] + (position - i) * (values[i + 1] - values[i])
    }

    private fun windowLow(values: DoubleArray, start: Double, span: Double): Double {
        if (values.isEmpty()) return 0.0
        val i = floor(start).toInt().coerceIn(values.indices)
        val end = ceil(start + span).toInt().coerceIn(i + 1, values.size)
        return mean(values, i, end)
    }

    private fun mean(values: DoubleArray, start: Int, end: Int): Double {
        val stop = minOf(values.size, end)
        if (start >= stop) return 0.0
        var sum = 0.0
        for (i in start until stop) sum += values[i]
        return sum / (stop - start)
    }

    private fun medianOfFour(samples: DoubleArray): Double {
        samples.sort()
        return (samples[1] + samples[2]) * 0.5
    }

    private fun milliseconds(seconds: Double): Double = BigDecimal(seconds).setScale(3, RoundingMode.HALF_EVEN).toDouble()

    private fun format(template: String, vararg values: Any) = String.format(Locale.ROOT, template, *values)

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
    }
}

