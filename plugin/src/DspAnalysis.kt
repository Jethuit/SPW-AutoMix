package automix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln1p
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class DspProfile(
    val duration: Double,
    val bpm: Double,
    val bpmConfidence: Double,
    val beats: DoubleArray,
    val downbeats: DoubleArray,
    val keyName: String?,
    val camelot: String?,
    val lufs: Double,
    val lufs2s: DoubleArray,
    val lowRatio1s: DoubleArray,
    val lowRatioHead: Double,
    val lowRatioTail: Double
)

internal data class DspWaveform(val peaks: IntArray, val low: IntArray, val mid: IntArray, val high: IntArray)

internal object DspAnalysis {
    private const val PROFILE_RATE = 22050
    private const val STFT_SIZE = 1024
    private const val STFT_HOP = 128
    private const val WAVEFORM_BUCKET = 320 // 20 ms at 16 kHz.
    private val stftWindow = DoubleArray(STFT_SIZE) { 0.5 - 0.5 * cos(2.0 * PI * it / STFT_SIZE) }

    private data class Section(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    private class Filter(private val sections: Array<Section>) {
        private val state1 = DoubleArray(sections.size)
        private val state2 = DoubleArray(sections.size)

        fun next(sample: Double): Double {
            var value = sample
            for (i in sections.indices) {
                val section = sections[i]
                val result = section.b0 * value + state1[i]
                state1[i] = section.b1 * value - section.a1 * result + state2[i]
                state2[i] = section.b2 * value - section.a2 * result
                value = result
            }
            return value
        }
    }

    // Same 22,050 Hz K-weighting and 16,000 Hz Butterworth sections as the reference analysis.
    private val kWeight = arrayOf(
        Section(1.4684290427937805, -2.064047116194825, 0.801125598720123, -1.2644297434834626, 0.4699372688025411),
        Section(0.9923454805508523, -1.9846909611017045, 0.9923454805508523, -1.9846323685754361, 0.9847495536279729)
    )
    private val waveLow = arrayOf(
        Section(0.0017575756077033268, 0.0035151512154066537, 0.0017575756077033268, -1.8779595410500867, 0.8849898434808999)
    )
    private val waveMid = arrayOf(
        Section(0.12074553442913315, 0.2414910688582663, 0.12074553442913315, -0.7988723979864111, 0.32508886970021156),
        Section(1.0, -2.0, 1.0, -1.8802111560582568, 0.8883303816357775)
    )
    private val waveHigh = arrayOf(
        Section(0.4898083010035343, -0.9796166020070686, 0.4898083010035343, -0.6997380282733656, 0.25949517574077186)
    )

    fun profile(samples: FloatArray): DspProfile {
        checkDspInterrupted()
        val duration = samples.size.toDouble() / PROFILE_RATE
        if (samples.size < PROFILE_RATE) {
            return DspProfile(roundToScale(duration, 1000.0), 120.0, 0.0, DoubleArray(0), DoubleArray(0),
                null, null, -60.0, DoubleArray(0), DoubleArray(0), 0.0, 0.0)
        }

        val loudness = measureLoudness(samples)
        val features = spectralFeatures(samples)
        val tempo = DspTempo.estimate(features.kick, features.snare, duration)
        val key = DspTonality.estimate(samples, PROFILE_RATE)
        val ratio = features.lowRatio
        val framesPerSecond = (PROFILE_RATE.toDouble() / STFT_HOP).roundToInt()
        val ratioBlocks = DoubleArray(maxOf(1, ratio.size / framesPerSecond)) { block ->
            val start = block * framesPerSecond
            val end = minOf(start + framesPerSecond, ratio.size)
            roundToScale(mean(ratio, start, end), 1000.0)
        }
        return DspProfile(
            roundToScale(duration, 1000.0), tempo.bpm, tempo.confidence, tempo.beats, tempo.downbeats,
            key?.name, key?.camelot, loudness.integrated, loudness.twoSecond, ratioBlocks,
            roundToScale(mean(ratioBlocks, 0, minOf(20, ratioBlocks.size)), 1000.0),
            roundToScale(mean(ratioBlocks, maxOf(0, ratioBlocks.size - 25), ratioBlocks.size), 1000.0)
        )
    }

    fun waveform(samples: FloatArray): DspWaveform {
        checkDspInterrupted()
        val count = (samples.size.toLong() + WAVEFORM_BUCKET - 1L).div(WAVEFORM_BUCKET).toInt()
        val wave = DspWaveform(IntArray(count), IntArray(count), IntArray(count), IntArray(count))
        val low = Filter(waveLow)
        val mid = Filter(waveMid)
        val high = Filter(waveHigh)
        for (bucket in 0 until count) {
            if ((bucket and 255) == 0) checkDspInterrupted()
            val start = bucket * WAVEFORM_BUCKET
            var peak = 0.0
            var lowPeak = 0.0
            var midPeak = 0.0
            var highPeak = 0.0
            for (i in start until start + minOf(WAVEFORM_BUCKET, samples.size - start)) {
                val sample = samples[i].toDouble()
                peak = maxOf(peak, abs(sample))
                lowPeak = maxOf(lowPeak, abs(low.next(sample)))
                midPeak = maxOf(midPeak, abs(mid.next(sample)))
                highPeak = maxOf(highPeak, abs(high.next(sample)))
            }
            wave.peaks[bucket] = amplitudeByte(peak)
            wave.low[bucket] = amplitudeByte(lowPeak)
            wave.mid[bucket] = amplitudeByte(midPeak)
            wave.high[bucket] = amplitudeByte(highPeak)
        }
        return wave
    }

    private fun amplitudeByte(value: Double) = minOf(255, (sqrt(value) * 255.0).toInt())

    private data class Loudness(val integrated: Double, val twoSecond: DoubleArray)

    private fun measureLoudness(samples: FloatArray): Loudness {
        val hop = PROFILE_RATE / 10
        val sectionSize = PROFILE_RATE * 2
        // Retain 100 ms energy buckets instead of a full-track filtered PCM buffer.
        val hopPowers = DoubleArray(samples.size / hop)
        val sections = DoubleArray(maxOf(1, samples.size / sectionSize))
        val filter = Filter(kWeight)
        var hopSum = 0.0
        var sectionSum = 0.0
        var nextHop = 0
        var nextSection = 0
        var inHop = 0
        for (i in samples.indices) {
            if ((i and 65535) == 0) checkDspInterrupted()
            val value = filter.next(samples[i].toDouble())
            val energy = 2.0 * (value * value)
            hopSum += energy
            sectionSum += energy
            if (++inHop == hop) {
                hopPowers[nextHop++] = hopSum / hop
                inHop = 0
                hopSum = 0.0
                if (nextHop % 20 == 0) {
                    sections[nextSection++] = asLufs(sectionSum / sectionSize)
                    sectionSum = 0.0
                }
            }
        }
        if (nextSection == 0) sections[0] = asLufs(sectionSum / samples.size)

        var absoluteSum = 0.0
        var absoluteCount = 0
        val powers = DoubleArray(hopPowers.size - 3) { index ->
            (hopPowers[index] + hopPowers[index + 1] + hopPowers[index + 2] + hopPowers[index + 3]) / 4.0
        }
        val absoluteGate = 10.0.pow((-70.0 + 0.691) / 10.0) - 1e-12
        for (power in powers) if (power > absoluteGate) {
            absoluteSum += power
            absoluteCount++
        }
        if (absoluteCount == 0) return Loudness(-120.0, sections)
        val absoluteMean = absoluteSum / absoluteCount
        val gate = maxOf(absoluteGate, (absoluteMean + 1e-12) * 0.1 - 1e-12)
        var relativeSum = 0.0
        var relativeCount = 0
        for (power in powers) if (power > gate) {
            relativeSum += power
            relativeCount++
        }
        return Loudness(asLufs(if (relativeCount == 0) absoluteMean else relativeSum / relativeCount), sections)
    }

    // 100 ms K-weighted blocks keep quiet intros and reverb tails visible to the planner.
    fun transitionLoudness(samples: FloatArray): FloatArray {
        val block = PROFILE_RATE / 10
        val filtered = Filter(kWeight)
        return FloatArray((samples.size + block - 1) / block) { index ->
            checkDspInterrupted()
            val start = index * block
            val end = minOf(start + block, samples.size)
            var power = 0.0
            for (i in start until end) {
                val value = filtered.next(samples[i].toDouble())
                power += 2.0 * value * value
            }
            asLufs(power / (end - start)).coerceIn(-125.0, 48.0).toFloat()
        }
    }

    private fun asLufs(energy: Double) = roundToScale(-0.691 + 10.0 * log10(energy + 1e-12), 100.0)

    private data class SpectralFeatures(val kick: DoubleArray, val snare: DoubleArray, val lowRatio: DoubleArray)

    private fun spectralFeatures(samples: FloatArray): SpectralFeatures {
        val frames = (samples.size - STFT_SIZE + STFT_HOP - 1) / STFT_HOP + 1
        val kick = DoubleArray(frames)
        val snare = DoubleArray(frames)
        val lowRatio = DoubleArray(frames)
        val fft = DspFft(STFT_SIZE)
        val previous = DoubleArray(STFT_SIZE / 2 + 1)
        val firstBin = 2 // 35 Hz
        val lastKickBin = 6 // 140 Hz
        val lastLowBin = 9 // 200 Hz
        val snareStart = 42 // 900 Hz
        val snareEnd = 208 // 4500 Hz
        val lastBin = 371 // 8000 Hz
        for (frame in 0 until frames) {
            if ((frame and 127) == 0) checkDspInterrupted()
            fft.windowed(samples, frame * STFT_HOP, stftWindow)
            var totalPower = 0.0
            var lowPower = 0.0
            var kickFlux = 0.0
            var snareFlux = 0.0
            for (bin in firstBin..lastBin) {
                val real = fft.real[bin]
                val imaginary = fft.imaginary[bin]
                val energy = (real * real + imaginary * imaginary) / (512.0 * 512.0)
                totalPower += energy
                if (bin <= lastLowBin) lowPower += energy
                if (bin <= lastKickBin || bin in snareStart..snareEnd) {
                    val amplitude = ln1p(15.0 * sqrt(energy))
                    val flux = if (frame == 0) 0.0 else maxOf(0.0, amplitude - previous[bin])
                    if (bin <= lastKickBin) kickFlux += flux else snareFlux += flux
                    previous[bin] = amplitude
                }
            }
            kick[frame] = kickFlux / (lastKickBin - firstBin + 1)
            snare[frame] = snareFlux / (snareEnd - snareStart + 1)
            lowRatio[frame] = (lowPower / (totalPower + 1e-12)).coerceIn(0.0, 1.0)
        }
        return SpectralFeatures(normalizeFlux(kick), normalizeFlux(snare), lowRatio)
    }

    private fun normalizeFlux(flux: DoubleArray): DoubleArray {
        var local = 0.0
        for (i in 0..16) local += flux[i]
        val adjusted = DoubleArray(flux.size) { i ->
            val value = maxOf(0.0, flux[i] - (local / 33.0) * 0.72)
            if (i >= 16) local -= flux[i - 16]
            if (i + 17 < flux.size) local += flux[i + 17]
            value
        }
        val scale = samplePercentile(adjusted, 0.99).takeIf { it > 0.0 }
            ?: adjusted.max().takeIf { it > 0.0 } ?: 1.0
        for (i in adjusted.indices) adjusted[i] = (adjusted[i] / scale).coerceIn(0.0, 2.5)
        return adjusted
    }

    private fun mean(samples: DoubleArray, start: Int, end: Int): Double {
        var total = 0.0
        for (i in start until end) total += samples[i]
        return total / (end - start)
    }
}

internal fun checkDspInterrupted() {
    if (Thread.interrupted()) throw InterruptedException("Audio analysis interrupted")
}

internal fun roundToScale(value: Double, scale: Double): Double = round(value * scale) / scale

internal fun samplePercentile(values: DoubleArray, fraction: Double): Double {
    val sorted = values.sortedArray()
    val at = (sorted.size - 1) * fraction
    val lower = at.toInt()
    val upper = minOf(lower + 1, sorted.lastIndex)
    return sorted[lower] + (sorted[upper] - sorted[lower]) * (at - lower)
}
