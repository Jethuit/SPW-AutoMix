package automix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class DspMixFeatures(
    val times: DoubleArray,
    val descriptors: FloatArray,
    val boundaries: FloatArray,
    val drops: FloatArray,
    val audibleStart: Double,
    val audibleEnd: Double,
    val loudness100ms: FloatArray? = null
) {
    fun value(beat: Int, feature: Int) = descriptors[beat * WIDTH + feature].toDouble()

    fun timbreMean(): DoubleArray {
        val active = times.indices.filter { times[it] in audibleStart..audibleEnd && value(it, LEVEL) > 0.15 }
        return DoubleArray(BANDS) { band ->
            if (active.isEmpty()) 0.0 else active.sumOf { value(it, band) } / active.size
        }
    }

    companion object {
        const val BANDS = 12
        const val CHROMA = 12
        const val LEVEL = 24
        const val VOICE = 25
        const val BASS = 26
        const val WIDTH = 27
    }
}

internal object DspMixAnalysis {
    private const val RATE = 22050
    private const val FRAME = 4096
    private const val HOP = 3072
    private val window = DoubleArray(FRAME) { 0.5 - 0.5 * cos(2.0 * PI * it / FRAME) }
    private val bandEnds = intArrayOf(60, 95, 150, 240, 380, 600, 950, 1500, 2400, 3800, 6000, 9500)
        .map { it * FRAME / RATE }

    fun extract(samples: FloatArray, profile: DspProfile): DspMixFeatures {
        val times = profile.beats.takeIf { it.size >= 2 }
            ?: DoubleArray((profile.duration * 2).toInt().coerceAtLeast(1)) { it * 0.5 }
        val frames = FloatArray(times.size * DspMixFeatures.WIDTH)
        val counts = IntArray(times.size)
        val bandPower = DoubleArray(DspMixFeatures.BANDS)
        val notePower = DoubleArray(DspMixFeatures.BANDS)
        val fft = DspFft(FRAME)
        val power = DoubleArray(bandEnds.last() + 2)
        var beat = 0
        for (offset in samples.indices step HOP) {
            checkDspInterrupted()
            val center = (offset + FRAME / 2.0) / RATE
            while (beat + 1 < times.size && times[beat + 1] <= center) beat++
            fft.windowed(samples, offset, window)
            for (bin in 5..power.lastIndex) {
                val real = fft.real[bin]
                val imag = fft.imaginary[bin]
                power[bin] = (real * real + imag * imag) / (FRAME.toDouble() * FRAME)
            }
            bandPower.fill(0.0)
            notePower.fill(0.0)
            var band = 0
            var midPeaks = 0.0
            for (bin in 6..bandEnds.last()) {
                if (bin > bandEnds[band]) band++
                val peak = power[bin]
                bandPower[band] += peak
                if (bin !in 12..420 || peak <= 1e-12 || peak <= power[bin - 1] || peak < power[bin + 1]) continue
                val left = ln(power[bin - 1].coerceAtLeast(1e-16))
                val right = ln(power[bin + 1].coerceAtLeast(1e-16))
                val displacement = (0.5 * (left - right) / (left - 2 * ln(peak) + right))
                    .coerceIn(-0.5, 0.5)
                val frequency = (bin + displacement) * RATE / FRAME
                val note = 69 + 12 * log2(frequency / 440)
                val nearest = note.roundToInt()
                if (abs(note - nearest) < 0.38) notePower[Math.floorMod(nearest, 12)] += sqrt(peak)
                if (frequency in 280.0..2400.0) midPeaks += peak
            }
            val level = bandPower.sum()
            val chromaTotal = notePower.sum().coerceAtLeast(1e-12)
            val base = beat * DspMixFeatures.WIDTH
            for (i in bandPower.indices) {
                frames[base + i] += sqrt(bandPower[i] / (level + 1e-12)).toFloat()
                frames[base + DspMixFeatures.CHROMA + i] += (notePower[i] / chromaTotal).toFloat()
            }
            frames[base + DspMixFeatures.LEVEL] += level.toFloat()
            // Harmonic midrange occupancy is a cheap proxy for vocal masking and also responds to instruments.
            frames[base + DspMixFeatures.VOICE] += (midPeaks / (level + 1e-12)).coerceIn(0.0, 1.0).toFloat()
            frames[base + DspMixFeatures.BASS] += ((bandPower[0] + bandPower[1] + bandPower[2]) / (level + 1e-12)).toFloat()
            counts[beat]++
        }
        for (i in times.indices) {
            if (counts[i] == 0) continue
            for (feature in 0 until DspMixFeatures.WIDTH) frames[i * DspMixFeatures.WIDTH + feature] /= counts[i].toFloat()
        }
        fun index(beatIndex: Int, feature: Int) = beatIndex * DspMixFeatures.WIDTH + feature
        val energies = DoubleArray(times.size) { frames[index(it, DspMixFeatures.LEVEL)].toDouble() }
        val reference = samplePercentile(energies, 0.8).coerceAtLeast(1e-9)
        for (i in times.indices) {
            val relativeDb = 10 * log10(energies[i].coerceAtLeast(1e-14) / reference)
            val level = ((relativeDb + 24) / 24).coerceIn(0.0, 1.0).toFloat()
            frames[index(i, DspMixFeatures.LEVEL)] = level
            frames[index(i, DspMixFeatures.VOICE)] *= level
        }

        val boundaries = FloatArray(times.size)
        val drops = FloatArray(times.size)
        // A local self-similarity checkerboard detects changes across adjacent bars in O(beats).
        // Averaging each side before measuring the difference suppresses within-bar syncopation.
        val half = 4
        val left = DoubleArray(DspMixFeatures.VOICE)
        val right = DoubleArray(left.size)
        for (i in half..times.size - half) {
            for (f in left.indices) {
                left[f] = 0.0
                right[f] = 0.0
                for (j in 0 until half) {
                    left[f] += frames[index(i - half + j, f)] / half
                    right[f] += frames[index(i + j, f)] / half
                }
            }
            val departure = abs(right[DspMixFeatures.LEVEL] - left[DspMixFeatures.LEVEL])
            val spectral = (0 until DspMixFeatures.LEVEL).sumOf { f -> (right[f] - left[f]) * (right[f] - left[f]) }
            boundaries[i] = (0.6 * sqrt(spectral) + 0.65 * departure).coerceIn(0.0, 1.0).toFloat()
            drops[i] = ((right[DspMixFeatures.LEVEL] - left[DspMixFeatures.LEVEL] - 0.22) * 2)
                .coerceIn(0.0, 1.0).toFloat() * boundaries[i]
        }
        val activeFirst = energies.indexOfFirst { it > reference * 0.04 }
        val activeLast = energies.indexOfLast { it > reference * 0.04 }
        return DspMixFeatures(
            times, frames, boundaries, drops,
            times.getOrNull(activeFirst) ?: 0.0,
            times.getOrNull(activeLast + 1)?.takeIf { activeLast >= 0 } ?: profile.duration,
            DspAnalysis.transitionLoudness(samples)
        )
    }
}
