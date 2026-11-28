package automix

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.SplittableRandom
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/** Offline audition on the same 48 kHz stereo clock as the player. */
internal object DspPreview {
    private const val RATE = 48000
    private const val CHANNELS = 2
    private const val FRAMES_PER_MS = RATE / 1000
    private const val FILTER_BLOCK = 128
    private const val FILTER_WARMUP = 100 * FRAMES_PER_MS
    private const val EFFECT_RAMP = 25 * FRAMES_PER_MS
    private const val FIR_HALF = 40
    private const val OVERSAMPLING = 4
    const val AUDITION_TARGET_LUFS = -14
    private val sourcePeakCeiling = 10.0.pow(-2.5 / 20.0)
    private val truePeakCeiling = 10.0.pow(-1.0 / 20.0)
    private val releaseFactor = exp(-0.001 / 0.120)
    private val crossoverLow = butterworth(200.0, lowPass = true)
    private val crossoverHigh = butterworth(200.0, lowPass = false)
    private val crossoverMidLow = butterworth(2500.0, lowPass = true)
    private val crossoverMidHigh = butterworth(2500.0, lowPass = false)
    private val echoLow = butterworth(4200.0, lowPass = true)
    private val noiseHighPass = butterworth(450.0, lowPass = false)
    private val noiseLowPass = butterworth(4800.0, lowPass = true)
    private val truePeakPhases = oversamplingPhases()

    fun canMatchLoudness(lufsA: Double, lufsB: Double): Boolean =
        lufsA.isFinite() && lufsB.isFinite() && lufsA > -60.0 && lufsB > -60.0

    fun render(
        pathA: String,
        pathB: String,
        durationB: Double,
        plan: Plan,
        window: TransitionWindow,
        ffmpeg: String,
        lufsA: Double = Double.NaN,
        lufsB: Double = Double.NaN
    ): ByteArray {
        val before = (window.previewBeforeMs * FRAMES_PER_MS).toInt()
        val overlap = (window.durationMs * FRAMES_PER_MS).toInt()
        require(overlap >= 64) { "过渡太短，无法试听" }
        val after = if (durationB.isFinite()) {
            val seconds = (durationB - (window.entryMs + window.durationMs) / 1000.0).coerceIn(0.0, 10.0)
            round(seconds * RATE).toInt()
        } else 0

        val startA = (window.startMs - window.previewBeforeMs) * FRAMES_PER_MS
        val startB = window.entryMs * FRAMES_PER_MS
        val prefixA = startA.coerceAtMost(FILTER_WARMUP.toLong()).toInt()
        val prefixB = startB.coerceAtMost(FILTER_WARMUP.toLong()).toInt()
        val mixA = prefixA + before
        val mixB = prefixB
        val framesA = mixA + overlap
        val framesB = mixB + overlap + after
        val samplesA = DspPcm.read(ffmpeg, pathA, RATE, CHANNELS, startA - prefixA, framesA)
        val samplesB = DspPcm.read(ffmpeg, pathB, RATE, CHANNELS, startB - prefixB, framesB)
        val (trackGainA, trackGainB) = matchingGains(samplesA, samplesB, prefixA, prefixB, lufsA, lufsB)
        val outgoing = splitAndShape(samplesA, framesA, mixA, overlap, window, plan, trackGainA,
            outgoing = true, adjoiningContext = before > 0)
        val incoming = splitAndShape(samplesB, framesB, mixB, overlap, window, plan, trackGainB,
            outgoing = false, adjoiningContext = after > 0)
        plan.filterSweep(0)?.let { sweep ->
            sweepFilter(outgoing, mixA, overlap, window, plan.pivot, sweep, outgoing = true, adjoiningContext = before > 0)
        }
        plan.filterSweep(1)?.let { sweep ->
            sweepFilter(incoming, mixB, overlap, window, plan.pivot, sweep, outgoing = false, adjoiningContext = after > 0)
        }

        val mixed = DoubleArray((before + overlap + after) * CHANNELS)
        outgoing.copyInto(mixed, startIndex = prefixA * CHANNELS, endIndex = mixA * CHANNELS)
        incoming.copyInto(mixed, (before + overlap) * CHANNELS, (mixB + overlap) * CHANNELS)
        val effectSend = if (plan.effect != TransitionEffect.None) DoubleArray(overlap * CHANNELS) else null
        val knots = window.gains(plan)
        var knot = 0
        for (frame in 0 until overlap) {
            checkInterrupted(frame)
            val u = frame.toDouble() / (overlap - 1)
            while (knot < knots.lastIndex - 1 && u > knots[knot + 1].first) knot++
            val (start, startA, startB) = knots[knot]
            val (end, endA, endB) = knots[knot + 1]
            val t = (u - start) / (end - start)
            val gainA = startA + t * (endA - startA)
            val gainB = startB + t * (endB - startB)
            val indexA = (mixA + frame) * CHANNELS
            val indexB = (mixB + frame) * CHANNELS
            val output = (before + frame) * CHANNELS
            val send = frame * CHANNELS
            for (channel in 0 until CHANNELS) {
                val fadedA = outgoing[indexA + channel] * gainA
                mixed[output + channel] = fadedA + incoming[indexB + channel] * gainB
                if (effectSend != null) effectSend[send + channel] = fadedA
            }
        }
        if (effectSend != null) {
            if (plan.echo) addEcho(mixed, effectSend, before, overlap, window, plan.bpmA)
            else addReverb(mixed, effectSend, before, overlap, after, window, plan.effect)
        }
        if (plan.noiseTail) addNoiseTail(mixed, before, overlap, window)
        limitTruePeak(mixed)
        return wav(mixed)
    }

    private fun matchingGains(
        a: FloatArray, b: FloatArray, prefixA: Int, prefixB: Int, lufsA: Double, lufsB: Double
    ): Pair<Double, Double> {
        if (!canMatchLoudness(lufsA, lufsB)) return 1.0 to 1.0
        fun gain(lufs: Double) = 10.0.pow((AUDITION_TARGET_LUFS - lufs).coerceIn(-12.0, 6.0) / 20.0)
        fun peak(audio: FloatArray, prefix: Int): Double {
            var maximum = 0.0
            for (index in prefix * CHANNELS until audio.size) {
                checkInterrupted(index)
                maximum = maxOf(maximum, abs(audio[index].toDouble()))
            }
            return maximum
        }
        val gainA = gain(lufsA)
        val gainB = gain(lufsB)
        val peakAfterGain = maxOf(peak(a, prefixA) * gainA, peak(b, prefixB) * gainB)
        // Reduce both gains together to preserve the loudness match and keep pre-mix peak headroom.
        val protection = if (peakAfterGain > sourcePeakCeiling) sourcePeakCeiling / peakAfterGain else 1.0
        return gainA * protection to gainB * protection
    }

    private data class Coefficients(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    private class Biquad(var coefficients: Coefficients) {
        private val z1 = DoubleArray(CHANNELS)
        private val z2 = DoubleArray(CHANNELS)

        fun sample(input: Double, channel: Int): Double {
            val c = coefficients
            val output = c.b0 * input + z1[channel]
            z1[channel] = c.b1 * input - c.a1 * output + z2[channel]
            z2[channel] = c.b2 * input - c.a2 * output
            return output
        }
    }

    private fun butterworth(cutoff: Double, lowPass: Boolean): Coefficients {
        val angle = 2.0 * PI * cutoff / RATE
        val cosine = cos(angle)
        val alpha = sin(angle) / sqrt(2.0)
        val a0 = 1.0 + alpha
        val b0 = (if (lowPass) 1.0 - cosine else 1.0 + cosine) / (2.0 * a0)
        val b1 = if (lowPass) 2.0 * b0 else -2.0 * b0
        return Coefficients(b0, b1, b0, -2.0 * cosine / a0, (1.0 - alpha) / a0)
    }

    /** Linkwitz–Riley fourth-order branch. Two equal Butterworth sections preserve phase at the crossover. */
    private class CrossoverBranch(coefficients: Coefficients) {
        private val first = Biquad(coefficients)
        private val second = Biquad(coefficients)

        fun sample(input: Double, channel: Int): Double = second.sample(first.sample(input, channel), channel)
    }

    private class BandShape(val envelope: EqEnvelope, cutDb: Double, val softenEdge: Boolean) {
        private val reduction = 1.0 - 10.0.pow(cutDb / 20.0)

        fun gain(phase: Double, edgeBlend: Double): Double =
            1.0 - reduction * envelope.attenuationAt(phase) * (if (softenEdge) edgeBlend else 1.0)
    }

    private fun splitAndShape(
        input: FloatArray,
        frames: Int,
        mixStart: Int,
        overlap: Int,
        window: TransitionWindow,
        plan: Plan,
        gain: Double,
        outgoing: Boolean,
        adjoiningContext: Boolean
    ): DoubleArray {
        val lowMidBranch = CrossoverBranch(crossoverMidLow)
        val highBranch = CrossoverBranch(crossoverMidHigh)
        val lowBranch = CrossoverBranch(crossoverLow)
        val midBranch = CrossoverBranch(crossoverHigh)
        // The high band is phase-matched to the low/mid split by the same 200 Hz all-pass sum.
        val highMatchLow = CrossoverBranch(crossoverLow)
        val highMatchHigh = CrossoverBranch(crossoverHigh)
        val mixEnd = mixStart + overlap
        val deck = if (outgoing) 0 else 1
        val edgePhase = if (outgoing) window.phaseStart else window.phaseEnd
        fun bandShape(band: EqBand): BandShape? = plan.eqEnvelope(deck, band)?.let { envelope ->
            BandShape(envelope, band.cutDb, adjoiningContext && envelope.attenuationAt(edgePhase) > 0.0)
        }
        val lowEq = bandShape(EqBand.Low)
        val midEq = bandShape(EqBand.Mid)
        val highEq = bandShape(EqBand.High)
        val hasEq = lowEq != null || midEq != null || highEq != null
        val softenEdge = lowEq?.softenEdge == true || midEq?.softenEdge == true || highEq?.softenEdge == true
        val fade = minOf(overlap / 4, EFFECT_RAMP)
        val result = DoubleArray(frames * CHANNELS)
        for (frame in 0 until frames) {
            checkInterrupted(frame)
            var lowGain = 1.0
            var midGain = 1.0
            var highGain = 1.0
            if (hasEq && frame in mixStart until mixEnd) {
                val position = frame - mixStart
                val phase = window.phase(position.toDouble() / (overlap - 1))
                val edge = if (outgoing) position else overlap - 1 - position
                val blend = if (softenEdge && edge < fade) smoothEdge(edge.toDouble() / (fade - 1)) else 1.0
                lowGain = lowEq?.gain(phase, blend) ?: 1.0
                midGain = midEq?.gain(phase, blend) ?: 1.0
                highGain = highEq?.gain(phase, blend) ?: 1.0
            }
            val index = frame * CHANNELS
            for (channel in 0 until CHANNELS) {
                val sample = input.getOrElse(index + channel) { 0f }.toDouble()
                val lowMid = lowMidBranch.sample(sample, channel)
                val high = highBranch.sample(sample, channel)
                val low = lowBranch.sample(lowMid, channel)
                val mid = midBranch.sample(lowMid, channel)
                val highMatched = highMatchLow.sample(high, channel) + highMatchHigh.sample(high, channel)
                result[index + channel] = gain * (low * lowGain + mid * midGain + highMatched * highGain)
            }
        }
        return result
    }

    private fun sweepFilter(
        audio: DoubleArray,
        mixStart: Int,
        frames: Int,
        window: TransitionWindow,
        pivot: Double,
        sweep: FilterSweep,
        outgoing: Boolean,
        adjoiningContext: Boolean
    ) {
        if (outgoing && window.phaseEnd < pivot || !outgoing && window.phaseStart > pivot) return
        var left = 0
        var right = frames
        while (left < right) {
            val middle = (left + right) / 2
            if (window.phase(middle.toDouble() / (frames - 1)) < pivot) left = middle + 1 else right = middle
        }
        val fade = minOf(frames / 4, EFFECT_RAMP)
        val start = if (outgoing) (left - fade).coerceAtLeast(0) else 0
        val end = if (outgoing) frames else (left + fade).coerceAtMost(frames)
        if (start >= end) return
        val clippedAtEdge = if (outgoing) window.phaseStart > pivot else window.phaseEnd < pivot
        val ramp = fade > 1 && end - start >= fade && (!clippedAtEdge || adjoiningContext)
        fun coefficientsAt(frame: Int): Coefficients {
            val phase = window.phase(frame.toDouble() / (frames - 1))
            val cutoff = sweep.cutoff(phase, pivot, outgoing).coerceIn(15.0, RATE * 0.45)
            return butterworth(cutoff, lowPass = sweep.lowPass)
        }
        val first = mixStart + start
        val filter = Biquad(coefficientsAt(start))
        // Feed actual earlier samples through the filter while the audible deck remains dry.
        for (frame in (first - FILTER_WARMUP).coerceAtLeast(0) until first) {
            checkInterrupted(frame)
            val index = frame * CHANNELS
            for (channel in 0 until CHANNELS) filter.sample(audio[index + channel], channel)
        }
        for (frame in start until end) {
            checkInterrupted(frame)
            if ((frame - start) % FILTER_BLOCK == 0) {
                filter.coefficients = coefficientsAt(frame)
            }
            val wet = when {
                ramp && outgoing && frame < start + fade -> (frame - start).toDouble() / (fade - 1)
                ramp && !outgoing && frame >= end - fade -> (end - 1 - frame).toDouble() / (fade - 1)
                else -> 1.0
            }
            val index = (mixStart + frame) * CHANNELS
            val mix = if (clippedAtEdge) smoothEdge(wet) else wet
            for (channel in 0 until CHANNELS) {
                val input = audio[index + channel]
                val filtered = filter.sample(input, channel)
                audio[index + channel] = input * (1.0 - mix) + filtered * mix
            }
        }
    }

    private fun addNoiseTail(output: DoubleArray, before: Int, overlap: Int, window: TransitionWindow) {
        val fade = minOf(overlap / 4, EFFECT_RAMP)
        val softenStart = Plan.noiseGain(window.phaseStart) > 0.0
        val softenEnd = Plan.noiseGain(window.phaseEnd) > 0.0
        // Keep the preview repeatable and roll off the hiss at both ends of the spectrum.
        val random = SplittableRandom(0x4E4F495345L)
        val highPass = Biquad(noiseHighPass)
        val lowPass = Biquad(noiseLowPass)
        for (frame in 0 until overlap) {
            checkInterrupted(frame)
            val phase = window.phase(frame.toDouble() / (overlap - 1))
            var gain = Plan.noiseGain(phase)
            if (gain <= 0.0) continue
            if (softenStart && frame < fade) gain *= smoothEdge(frame.toDouble() / (fade - 1))
            val remaining = overlap - 1 - frame
            if (softenEnd && remaining < fade) gain *= smoothEdge(remaining.toDouble() / (fade - 1))
            val index = (before + frame) * CHANNELS
            for (channel in 0 until CHANNELS) {
                val white = random.nextDouble(-1.0, 1.0)
                val colored = lowPass.sample(highPass.sample(white, channel), channel)
                output[index + channel] += gain * colored
            }
        }
    }

    private fun smoothEdge(progress: Double): Double {
        val t = progress.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private class ReverbDelay(frames: Int) {
        private val buffer = DoubleArray(frames)
        private var position = 0
        private var damped = 0.0

        private fun push(sample: Double) {
            buffer[position] = sample
            position++
            if (position == buffer.size) position = 0
        }

        fun comb(input: Double): Double {
            val delayed = buffer[position]
            damped += 0.65 * (delayed - damped)
            push(input + 0.74 * damped)
            return delayed
        }

        fun diffuse(input: Double): Double {
            val delayed = buffer[position]
            val output = delayed - 0.55 * input
            push(input + 0.55 * output)
            return output
        }
    }

    private fun addReverb(
        output: DoubleArray,
        send: DoubleArray,
        before: Int,
        overlap: Int,
        after: Int,
        window: TransitionWindow,
        effect: TransitionEffect
    ) {
        val tailFrames = if (effect == TransitionEffect.ReverbEndCut) 0 else 750 * FRAMES_PER_MS
        val renderFrames = overlap + minOf(tailFrames, after)
        val endWet = window.reverbWet(effect, 1.0)
        for (channel in 0 until CHANNELS) {
            val stereoOffset = channel * 37
            val short = ReverbDelay(1427 + stereoOffset)
            val medium = ReverbDelay(1601 + stereoOffset)
            val long = ReverbDelay(1777 + stereoOffset)
            val longest = ReverbDelay(1973 + stereoOffset)
            val firstDiffuser = ReverbDelay(239 + stereoOffset)
            val secondDiffuser = ReverbDelay(83 + stereoOffset)
            for (frame in 0 until renderFrames) {
                checkInterrupted(frame)
                val input = if (frame < overlap) send[frame * CHANNELS + channel] else 0.0
                val sum = short.comb(input) + medium.comb(input) + long.comb(input) + longest.comb(input)
                val reflections = secondDiffuser.diffuse(firstDiffuser.diffuse(sum * 0.25))
                val wet = if (frame < overlap) window.reverbWet(effect, frame.toDouble() / (overlap - 1))
                    else endWet * (1.0 - smoothEdge((frame - overlap + 1.0) / tailFrames))
                output[(before + frame) * CHANNELS + channel] += 0.45 * wet * reflections
            }
        }
    }

    private fun addEcho(
        output: DoubleArray,
        tap: DoubleArray,
        before: Int,
        overlap: Int,
        window: TransitionWindow,
        bpm: Double
    ) {
        val delay = round(RATE * (30.0 / bpm).coerceIn(0.04, 1.5)).toInt().coerceAtLeast(1)
        val wet = DoubleArray(overlap) { frame ->
            val phase = window.phase(frame.toDouble() / (overlap - 1))
            val rise = ((phase - 0.55) / 0.20).coerceIn(0.0, 1.0)
            val fade = ((1.0 - phase) / 0.08).coerceIn(0.0, 1.0)
            rise * fade * Plan.HOST_ECHO_MAX_WET
        }
        for (repeat in 1..8) {
            val start = delay * repeat
            if (start >= overlap) break
            val filter = Biquad(echoLow)
            for (frame in 0 until overlap - start) {
                checkInterrupted(frame)
                val input = frame * CHANNELS
                val target = (before + start + frame) * CHANNELS
                for (channel in 0 until CHANNELS) {
                    val delayed = filter.sample(tap[input + channel], channel) * Plan.HOST_ECHO_FEEDBACK
                    tap[input + channel] = delayed
                    output[target + channel] += delayed * wet[start + frame]
                }
            }
        }
    }

    private data class FirPhase(val firstOffset: Int, val weights: DoubleArray)

    /** Kaiser-windowed sinc matching resample_poly's centered 4× antialiasing kernel. */
    private fun oversamplingPhases(): List<FirPhase> {
        fun besselI0(value: Double): Double {
            val square = (value / 2.0).pow(2)
            var term = 1.0
            var sum = 1.0
            for (order in 1..24) {
                term *= square / (order * order)
                sum += term
                if (term < sum * 1e-16) break
            }
            return sum
        }

        val denominator = besselI0(5.0)
        val kernel = DoubleArray(2 * FIR_HALF + 1) { index ->
            val offset = index - FIR_HALF
            val x = PI * offset / OVERSAMPLING
            val sinc = if (offset == 0) 1.0 else sin(x) / x
            val windowArg = offset.toDouble() / FIR_HALF
            val window = besselI0(5.0 * sqrt(1.0 - windowArg * windowArg)) / denominator
            sinc * window
        }
        val scale = OVERSAMPLING / kernel.sum()
        return (0 until OVERSAMPLING).map { phase ->
            val first = Math.ceilDiv(phase - FIR_HALF, OVERSAMPLING)
            val last = Math.floorDiv(phase + FIR_HALF, OVERSAMPLING)
            FirPhase(first, DoubleArray(last - first + 1) { index ->
                kernel[FIR_HALF + phase - (first + index) * OVERSAMPLING] * scale
            })
        }
    }

    private fun truePeak(audio: DoubleArray, perFrame: DoubleArray? = null): Double {
        val frames = audio.size / CHANNELS
        var peak = 0.0
        val centerGain = truePeakPhases[0].weights[-truePeakPhases[0].firstOffset]
        for (frame in 0 until frames) {
            checkInterrupted(frame)
            // Only the center coefficient is nonzero at integer sample positions.
            var maximum = maxOf(abs(audio[frame * CHANNELS]), abs(audio[frame * CHANNELS + 1])) * centerGain
            for (phase in 1 until OVERSAMPLING) {
                val (offset, weights) = truePeakPhases[phase]
                val start = (-(frame + offset)).coerceAtLeast(0)
                val end = (frames - frame - offset).coerceAtMost(weights.size)
                var index = (frame + offset + start) * CHANNELS
                var left = 0.0
                var right = 0.0
                for (tap in start until end) {
                    val weight = weights[tap]
                    left += audio[index] * weight
                    right += audio[index + 1] * weight
                    index += CHANNELS
                }
                maximum = maxOf(maximum, abs(left), abs(right))
            }
            if (perFrame != null) perFrame[frame] = maximum
            peak = maxOf(peak, maximum)
        }
        return peak
    }

    private fun limitTruePeak(audio: DoubleArray) {
        val peaks = DoubleArray(audio.size / CHANNELS)
        if (truePeak(audio, peaks) <= truePeakCeiling) return
        val blockGain = DoubleArray(Math.ceilDiv(peaks.size, FRAMES_PER_MS)) { 1.0 }
        for (frame in peaks.indices) {
            checkInterrupted(frame)
            if (peaks[frame] > truePeakCeiling) {
                val block = frame / FRAMES_PER_MS
                blockGain[block] = minOf(blockGain[block], truePeakCeiling / peaks[frame])
            }
        }
        val release = DoubleArray(blockGain.size)
        for (block in blockGain.indices) {
            var held = 1.0
            for (n in (block - 5).coerceAtLeast(0)..(block + 5).coerceAtMost(blockGain.lastIndex)) {
                held = minOf(held, blockGain[n])
            }
            release[block] = if (block == 0) held else minOf(held, 1.0 - releaseFactor * (1.0 - release[block - 1]))
        }
        fun dilated(block: Int): Double {
            if (block !in release.indices) return 1.0
            return minOf(release[block], release[(block - 1).coerceAtLeast(0)], release[(block + 1).coerceAtMost(release.lastIndex)])
        }
        val smooth = DoubleArray(blockGain.size) { block ->
            0.25 * dilated(block - 1) + 0.5 * dilated(block) + 0.25 * dilated(block + 1)
        }
        for (frame in peaks.indices) {
            checkInterrupted(frame)
            val block = frame / FRAMES_PER_MS
            val blend = (frame % FRAMES_PER_MS).toDouble() / FRAMES_PER_MS
            val gain = smooth[block] + blend * (smooth[(block + 1).coerceAtMost(smooth.lastIndex)] - smooth[block])
            audio[frame * CHANNELS] *= gain
            audio[frame * CHANNELS + 1] *= gain
        }
        val finalPeak = truePeak(audio)
        if (finalPeak > truePeakCeiling) {
            val scale = truePeakCeiling / finalPeak
            for (index in audio.indices) {
                checkInterrupted(index)
                audio[index] *= scale
            }
        }
    }

    private fun wav(audio: DoubleArray): ByteArray {
        val dataBytes = audio.size * Short.SIZE_BYTES
        val out = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataBytes)
        out.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        out.putShort(1).putShort(CHANNELS.toShort()).putInt(RATE)
        out.putInt(RATE * CHANNELS * Short.SIZE_BYTES).putShort((CHANNELS * Short.SIZE_BYTES).toShort()).putShort(16)
        out.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes)
        val random = SplittableRandom(0)
        for (index in audio.indices) {
            checkInterrupted(index)
            check(audio[index].isFinite()) { "试听中出现无效音频采样" }
            val dither = (random.nextDouble() + random.nextDouble() - 1.0) / 32768.0
            val value = round((audio[index] + dither) * Short.MAX_VALUE).toInt()
            out.putShort(value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
        return out.array()
    }

    private fun checkInterrupted(index: Int) {
        if (index and 8191 == 0 && Thread.interrupted()) throw InterruptedException()
    }
}
