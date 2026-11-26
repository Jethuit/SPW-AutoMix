package automix

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sqrt

internal object DspTempo {
    private const val HOP = 128.0 / 22050.0
    private const val FIRST_FRAME_TIME = 512.0 / 22050.0

    data class Grid(val bpm: Double, val confidence: Double, val beats: DoubleArray, val downbeats: DoubleArray)

    fun estimate(kick: DoubleArray, snare: DoubleArray, duration: Double): Grid {
        val onset = DoubleArray(kick.size) { 0.65 * kick[it] + 0.35 * snare[it] }
        val comb = DoubleArray(kick.size) { kick[it] + 0.55 * snare[it] }
        val (first, end) = strongestWindow(onset)
        val centered = onset.copyOfRange(first, end)
        val mean = centered.average()
        var energy = 0.0
        for (i in centered.indices) {
            centered[i] -= mean
            energy += centered[i] * centered[i]
        }
        if (centered.size < 200 || sqrt(energy / centered.size) < 1e-6) return noGrid(120.0)

        val autocorrelation = autocorrelate(centered)
        val coarse = DoubleArray(389) { i ->
            val bpm = 68.0 + 0.25 * i
            val period = 60.0 / bpm / HOP
            var score = correlationAt(autocorrelation, period)
            score += 0.65 * correlationAt(autocorrelation, 2.0 * period)
            score += 0.45 * correlationAt(autocorrelation, 4.0 * period)
            score += 0.30 * correlationAt(autocorrelation, 0.5 * period)
            if (bpm in 85.0..135.0) score * 1.04 else score
        }
        val strongest = coarse.indices.maxBy { coarse[it] }
        val coarseBpm = 68.0 + 0.25 * strongest
        val bestCoarseScore = coarse[strongest]
        val confidence = roundToScale(
            ((bestCoarseScore - samplePercentile(coarse, 0.5)) / maxOf(bestCoarseScore, 1e-6)).coerceIn(0.0, 1.0),
            1000.0
        )
        val startTime = frameTime(first)
        val endTime = frameTime(end - 1)
        var bestFineScore = -1.0
        var bpm = coarseBpm
        for (index in 0..60) {
            checkDspInterrupted()
            val candidate = coarseBpm - 1.2 + index * 0.04
            val (score) = bestPhase(comb, startTime, endTime, 60.0 / candidate, 0.004, 36)
            if (score > bestFineScore) {
                bestFineScore = score
                bpm = candidate
            }
        }
        bpm = roundToScale(bpm, 100.0)
        if (abs(bpm - round(bpm)) <= 0.08) bpm = round(bpm)

        val period = 60.0 / bpm
        val (_, anchor) = bestPhase(comb, startTime, endTime, period, 0.002, 120)
        val samples = gridCount(anchor, endTime, period)
        if (samples < 4) return noGrid(bpm)
        val kickByPhase = DoubleArray(4)
        val snareByPhase = DoubleArray(4)
        val phaseCount = IntArray(4)
        for (i in 0 until samples) {
            val time = anchor + i * period
            val phase = i % 4
            phaseCount[phase]++
            kickByPhase[phase] += interpolate(kick, time)
            snareByPhase[phase] += interpolate(snare, time)
        }
        for (phase in 0..3) {
            kickByPhase[phase] /= phaseCount[phase].toDouble()
            snareByPhase[phase] /= phaseCount[phase].toDouble()
        }
        val downbeatPhase = (0..3).maxBy { phase ->
            kickByPhase[phase] * 1.4 + snareByPhase[(phase + 1) % 4] + snareByPhase[(phase + 3) % 4] -
                0.5 * snareByPhase[phase]
        }
        val downbeatAnchor = anchor + downbeatPhase * period
        return projectGrid(bpm, confidence, period, downbeatAnchor, duration, comb)
    }

    private fun noGrid(bpm: Double) = Grid(bpm, 0.0, DoubleArray(0), DoubleArray(0))

    private fun strongestWindow(onset: DoubleArray): Pair<Int, Int> {
        if (frameTime(onset.lastIndex) <= 38.0) return 0 to onset.size
        val window = round(36.0 / HOP).toInt()
        val step = round(2.0 / HOP).toInt()
        var best = 0
        var bestScore = -1.0
        for (start in 0..(onset.size - window) step step) {
            checkDspInterrupted()
            var sum = 0.0
            var squareSum = 0.0
            for (i in start until start + window) {
                val value = onset[i]
                sum += value
                squareSum += value * value
            }
            val mean = sum / window
            val score = sqrt(maxOf(0.0, squareSum / window - mean * mean)) * mean
            if (score > bestScore) {
                best = start
                bestScore = score
            }
        }
        return best to minOf(onset.size, best + window)
    }

    private fun frameTime(index: Int) = (512.0 + index * 128.0) / 22050.0

    private fun autocorrelate(centered: DoubleArray): DoubleArray {
        val size = Integer.highestOneBit(2 * centered.size - 1) shl 1
        val fft = DspFft(size)
        centered.copyInto(fft.real)
        fft.transform()
        for (i in 0 until size) {
            val real = fft.real[i]
            val imaginary = fft.imaginary[i]
            fft.real[i] = real * real + imaginary * imaginary
            fft.imaginary[i] = 0.0
        }
        fft.transform(inverse = true)
        val normalizer = maxOf(1e-12, fft.real[0])
        return DoubleArray(centered.size) { fft.real[it] / normalizer }
    }

    private fun correlationAt(values: DoubleArray, index: Double): Double =
        if (index < values.lastIndex) interpolateIndex(values, index) else 0.0

    private fun interpolate(values: DoubleArray, time: Double): Double =
        interpolateIndex(values, (time - FIRST_FRAME_TIME) / HOP)

    private fun interpolateIndex(values: DoubleArray, index: Double): Double {
        if (index <= 0.0) return values[0]
        if (index >= values.lastIndex) return values.last()
        val left = index.toInt()
        val first = values[left]
        return first + (values[left + 1] - first) * (index - left)
    }

    private fun gridCount(start: Double, end: Double, period: Double) =
        maxOf(0, ceil((end - 0.05 - start) / period).toInt())

    private fun bestPhase(
        envelope: DoubleArray,
        start: Double,
        end: Double,
        period: Double,
        step: Double,
        minimumPhases: Int
    ): Pair<Double, Double> {
        val phases = maxOf(minimumPhases, round(period / step).toInt())
        var bestScore = -1.0
        var bestAnchor = start
        for (phase in 0 until phases) {
            val anchor = start + phase * (period / phases)
            val beats = gridCount(anchor, end, period)
            if (beats < 4) continue
            var sum = 0.0
            for (i in 0 until beats) sum += interpolate(envelope, anchor + i * period)
            val score = sum / beats
            if (score > bestScore) {
                bestScore = score
                bestAnchor = anchor
            }
        }
        return bestScore to bestAnchor
    }

    private fun projectGrid(
        bpm: Double,
        confidence: Double,
        period: Double,
        anchor: Double,
        duration: Double,
        envelope: DoubleArray
    ): Grid {
        val headEnd = minOf(duration, 38.0)
        val tailStart = maxOf(headEnd, duration - 50.0)
        val headDelta = if (duration > 75.0) localDelta(envelope, 0.0, headEnd, period, anchor) else 0.0
        val tailDelta = if (duration - tailStart > 16.0) localDelta(envelope, tailStart, duration, period, anchor) else 0.0
        val blend = 4.0 * period

        val lastTime = maxOf(0.0, duration - 0.03)
        val startBeat = ceil((-0.5 * period - anchor) / period).toInt()
        val endBeat = floor((lastTime + 0.5 * period - anchor) / period).toInt()
        val beats = ArrayList<Double>(maxOf(0, endBeat - startBeat + 1))
        val downbeats = ArrayList<Double>()
        for (i in startBeat..endBeat) {
            val base = anchor + i * period
            val wHead = ((headEnd + blend - base) / (2.0 * blend)).coerceIn(0.0, 1.0)
            val wTail = ((base - (tailStart - blend)) / (2.0 * blend)).coerceIn(0.0, 1.0)
            val time = base + wHead * headDelta + wTail * tailDelta
            if (time >= 0.0 && time < lastTime) {
                val value = roundToScale(time, 1000.0)
                beats.add(value)
                if (i % 4 == 0) downbeats.add(value)
            }
        }
        return Grid(bpm, confidence, beats.toDoubleArray(), downbeats.toDoubleArray())
    }

    private fun localDelta(envelope: DoubleArray, start: Double, end: Double, period: Double, anchor: Double): Double {
        if (end - start < 8.0 * period) return 0.0
        val (_, local) = bestPhase(envelope, start, end, period, 0.003, 90)
        val barsToAlign = round((local - anchor) / period).toLong()
        val shift = (4 - Math.floorMod(barsToAlign, 4)) % 4
        val difference = local + shift * period - anchor
        val delta = difference - round(difference / period) * period
        return if (abs(delta) > 1e-6) delta else 0.0
    }
}
