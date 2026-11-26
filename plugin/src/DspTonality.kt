package automix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

internal object DspTonality {
    private const val FRAME_SIZE = 8192
    private const val FFT_SIZE = 16384
    private const val NOTES = 12
    private val window = DoubleArray(FRAME_SIZE) { 0.5 - 0.5 * cos(2.0 * PI * it / (FRAME_SIZE - 1)) }

    // Krumhansl–Kessler profiles, ordered chromatically from the tonic.
    private val weights = arrayOf(
        doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88),
        doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
    ).map { profile ->
        val average = profile.average()
        val centered = DoubleArray(NOTES) { profile[it] - average }
        val norm = sqrt(centered.sumOf { it * it })
        DoubleArray(NOTES) { centered[it] / norm }
    }
    private val names = arrayOf(
        arrayOf("C", "D♭", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B"),
        arrayOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "G♯", "A", "B♭", "B")
    )
    private val camelot = arrayOf(
        intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1),
        intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)
    )

    data class Key(val name: String, val camelot: String)
    private data class Peak(val frame: Int, val pitch: Double, val weight: Double)
    private data class SpectralPeaks(val levels: DoubleArray, val peaks: List<Peak>)

    fun estimate(samples: FloatArray, sampleRate: Int): Key? {
        if (samples.size < 2 * sampleRate) return null
        val (levels, peaks) = samplePeaks(samples, sampleRate)
        val threshold = maxOf(1e-6, samplePercentile(levels, 0.8) * 0.035)

        var tuningReal = 0.0
        var tuningImaginary = 0.0
        var totalWeight = 0.0
        for (peak in peaks) {
            if (levels[peak.frame] < threshold) continue
            val angle = 2.0 * PI * peak.pitch
            tuningReal += cos(angle) * peak.weight
            tuningImaginary += sin(angle) * peak.weight
            totalWeight += peak.weight
        }
        if (totalWeight <= 0.0) return null
        val radius = sqrt(tuningReal * tuningReal + tuningImaginary * tuningImaginary)
        if (radius / totalWeight < 0.2) return null

        val tuning = atan2(tuningImaginary, tuningReal) / (2.0 * PI)
        val chroma = Array(levels.size) { DoubleArray(NOTES) }
        val bass = Array(levels.size) { DoubleArray(NOTES) }
        for (peak in peaks) {
            if (levels[peak.frame] < threshold) continue
            val tuned = peak.pitch - tuning
            val pitch = round(tuned).toInt()
            if (abs(tuned - pitch) > 0.38) continue
            val note = Math.floorMod(pitch, NOTES)
            chroma[peak.frame][note] += peak.weight
            if (pitch in 40..62) bass[peak.frame][note] += peak.weight
        }
        if (normalizeFrames(chroma) < 8) return null
        normalizeFrames(bass)
        val tonal = sumFrames(chroma, 0, levels.size)
        val strongest = tonal.max()
        if (tonal.count { it >= 0.15 * strongest } < 3) return null

        val correlations = correlate(tonal)
        val score = addBassPrior(correlations, sumFrames(bass, 0, levels.size))
        val bestKey = score.indices.maxBy { score[it] }
        val runnerUp = score.indices.filter { it != bestKey }.maxOf { score[it] }
        if (correlations[bestKey] < 0.62 || score[bestKey] - runnerUp < 0.055) return null

        var start = 0
        var active = 0
        var agreeing = 0
        val sections = minOf(6, maxOf(1, levels.size / 36))
        for (section in 0 until sections) {
            val count = levels.size / sections + if (section < levels.size % sections) 1 else 0
            val end = start + count
            val sectionChroma = sumFrames(chroma, start, end)
            val sectionBass = sumFrames(bass, start, end)
            start = end
            if (sectionChroma.sum() <= 0.0) continue
            active++
            val local = addBassPrior(correlate(sectionChroma), sectionBass)
            if (local[bestKey] >= local.max() - 0.14) agreeing++
        }
        if (active == 0 || agreeing.toDouble() / active < 0.6) return null

        val mode = bestKey / NOTES
        val root = bestKey % NOTES
        val suffix = if (mode == 1) "m" else ""
        val letter = if (mode == 1) "A" else "B"
        return Key(names[mode][root] + suffix, camelot[mode][root].toString() + letter)
    }

    private fun samplePeaks(samples: FloatArray, sampleRate: Int): SpectralPeaks {
        val last = samples.size - FRAME_SIZE
        val count = minOf(800, last / 4096 + 1)
        val increment = last.toDouble() / maxOf(1, count - 1)
        val low = ceil(52.0 * FFT_SIZE / sampleRate).toInt()
        val high = (3000.0 * FFT_SIZE / sampleRate).toInt()
        val levels = DoubleArray(count)
        val peaks = ArrayList<Peak>()
        val fft = DspFft(FFT_SIZE)
        val magnitudes = DoubleArray(high + 2)
        val medianBuffer = DoubleArray(high - low + 1)
        for (frame in 0 until count) {
            checkDspInterrupted()
            val time = if (frame == count - 1 && count > 1) last else (frame * increment).toInt()
            fft.windowed(samples, time, window)
            for (bin in low - 1..high + 1) {
                val real = fft.real[bin]
                val imag = fft.imaginary[bin]
                magnitudes[bin] = sqrt(real * real + imag * imag)
            }
            var level = 0.0
            for (bin in low..high) {
                val amplitude = magnitudes[bin]
                level = maxOf(level, amplitude)
                medianBuffer[bin - low] = amplitude
            }
            levels[frame] = level
            medianBuffer.sort()
            val medianIndex = medianBuffer.size / 2
            val median = (medianBuffer[(medianBuffer.size - 1) / 2] + medianBuffer[medianIndex]) / 2.0
            val floor = maxOf(level * 0.035, median * 5.0)
            for (bin in low..high) {
                val amplitude = magnitudes[bin]
                if (amplitude <= floor || amplitude <= magnitudes[bin - 1] || amplitude < magnitudes[bin + 1]) continue
                val left = ln(maxOf(magnitudes[bin - 1], 1e-12))
                val middle = ln(maxOf(amplitude, 1e-12))
                val right = ln(maxOf(magnitudes[bin + 1], 1e-12))
                val shift = 0.5 * (left - right) / minOf(left - 2.0 * middle + right, -1e-9)
                val frequency = (bin + shift) * sampleRate / FFT_SIZE
                val pitch = 69.0 + 12.0 * log2(frequency / 440.0)
                val weight = (amplitude / level).pow(0.75) * if (frequency < 230.0) 1.2 else 1.0
                peaks.add(Peak(frame, pitch, weight))
            }
        }
        return SpectralPeaks(levels, peaks)
    }

    private fun normalizeFrames(frames: Array<DoubleArray>): Int {
        var active = 0
        for (frame in frames) {
            val sum = frame.sum()
            if (sum <= 0.0) continue
            active++
            val divisor = maxOf(1e-12, sum)
            for (note in 0 until NOTES) frame[note] /= divisor
        }
        return active
    }

    private fun sumFrames(frames: Array<DoubleArray>, start: Int, end: Int): DoubleArray {
        val total = DoubleArray(NOTES)
        for (i in start until end) {
            val frame = frames[i]
            for (note in 0 until NOTES) total[note] += frame[note]
        }
        return total
    }

    private fun correlate(chroma: DoubleArray): DoubleArray {
        val mean = chroma.average()
        val centered = DoubleArray(NOTES) { chroma[it] - mean }
        val norm = maxOf(1e-12, sqrt(centered.sumOf { it * it }))
        return DoubleArray(2 * NOTES) { key ->
            val profile = weights[key / NOTES]
            val root = key % NOTES
            var score = 0.0
            for (note in 0 until NOTES) score += (centered[note] / norm) * profile[(note - root + NOTES) % NOTES]
            score
        }
    }

    private fun addBassPrior(scores: DoubleArray, bass: DoubleArray): DoubleArray {
        val scale = maxOf(1e-12, bass.max())
        return DoubleArray(scores.size) { scores[it] + 0.22 * bass[it % NOTES] / scale }
    }
}
