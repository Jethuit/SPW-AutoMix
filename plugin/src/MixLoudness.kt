package automix

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** An absolute loudness timeline shared by the cue search and fade selection. */
internal class MixLoudness(private val profile: DspProfile, features: DspMixFeatures?) {
    private val detail = features?.loudness100ms?.takeIf { it.isNotEmpty() }
    val detailed get() = detail != null
    private val step = if (detailed) 0.1 else 2.0
    private val levels = detail?.let { fine -> DoubleArray(fine.size) { fine[it].toDouble() } } ?: profile.lufs2s

    // Keep quiet music such as sparse intros and reverb tails above the silence threshold.
    private val silence = minOf(-63.0, profile.lufs - 27).coerceAtLeast(-82.0)
    private val firstActive = detail?.indexOfFirst { it >= silence } ?: -1
    private val lastActive = detail?.indexOfLast { it >= silence } ?: -1
    val audibleStart = if (firstActive < 0) 0.0 else (firstActive * step - 0.08).coerceAtLeast(0.0)
    val audibleEnd = if (lastActive < 0) profile.duration else
        ((lastActive + 1) * step + 0.08).coerceIn(audibleStart, profile.duration)

    private val program = DoubleArray(levels.size + 1).apply {
        for (i in levels.indices) {
            // Quiet but audible notes still matter. Stronger passages carry more weight.
            val weight = ((levels[i] - silence) / 22.0).coerceIn(0.0, 1.0)
            this[i + 1] = this[i] + step * weight
        }
    }

    fun level(time: Double): Double {
        if (levels.isEmpty()) return profile.lufs
        val position = (time / step - 0.5).coerceIn(0.0, levels.lastIndex.toDouble())
        val index = floor(position).toInt()
        val next = minOf(index + 1, levels.lastIndex)
        val fraction = position - index
        return levels[index] * (1.0 - fraction) + levels[next] * fraction
    }

    fun contextPower(center: Double): Double =
        (-2..2).sumOf { offset -> power(level(center + offset * 0.13)) } / 5

    fun programBetween(from: Double, to: Double): Double =
        (programBefore(to) - programBefore(from)).coerceAtLeast(0.0)

    private fun programBefore(time: Double): Double {
        if (levels.isEmpty()) return time.coerceIn(0.0, profile.duration)
        val point = (time / step).coerceIn(0.0, levels.size.toDouble())
        val i = point.toInt()
        return if (i == levels.size) program[i] else program[i] + (program[i + 1] - program[i]) * (point - i)
    }

    companion object {
        fun power(level: Double) = 10.0.pow(level.coerceIn(-120.0, 24.0) / 10.0)

        fun decibels(power: Double) = 10.0 * log10(power.coerceAtLeast(1e-12))
    }
}
