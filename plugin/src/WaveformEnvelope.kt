package automix

import androidx.compose.ui.graphics.Path
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The worker sends one peak per 20 ms. All display buckets start on the track's own timeline. */
internal class WaveformEnvelope(private val samples: IntArray, private val useRms: Boolean = false) {
    private var bucketSize = 0
    private var amplitudes = FloatArray(0)

    private fun updateResolution(framesPerPoint: Int) {
        if (bucketSize == framesPerPoint) return
        val levels = FloatArray((samples.lastIndex / framesPerPoint) + 1) { point ->
            val start = point * framesPerPoint
            val end = start + min(framesPerPoint, samples.size - start)
            val level = if (useRms) {
                var sumSquares = 0L
                for (frame in start until end) {
                    val sample = samples[frame].toLong()
                    sumSquares += sample * sample
                }
                sqrt(sumSquares.toDouble() / (end - start)).toFloat()
            } else {
                var peak = 0
                for (frame in start until end) peak = max(peak, samples[frame])
                peak.toFloat()
            }
            level / 255f
        }
        amplitudes = FloatArray(levels.size) { point ->
            val previous = levels[max(0, point - 1)]
            val next = levels[min(levels.lastIndex, point + 1)]
            val level = (0.18f * previous + 0.64f * levels[point] + 0.18f * next).coerceIn(0f, 1f)
            if (useRms) level else level.pow(0.88f)
        }
        bucketSize = framesPerPoint
    }

    /** Build only the visible section, with the neighboring time points left for the canvas to clip. */
    fun path(leftSeconds: Double, spanSeconds: Double, width: Float, centerY: Float, scaleY: Float): Path {
        val path = Path()
        val trackEnd = samples.size / FRAMES_PER_SECOND
        val rightSeconds = leftSeconds + spanSeconds
        if (samples.isEmpty() || width <= 0f || spanSeconds <= 0.0 || rightSeconds <= 0.0 || leftSeconds >= trackEnd) {
            return path
        }

        val pixelsPerSecond = width / spanSeconds
        val framesPerPoint = (2.0 * FRAMES_PER_SECOND / pixelsPerSecond).roundToInt().coerceIn(1, samples.size)
        updateResolution(framesPerPoint)
        val interval = framesPerPoint / FRAMES_PER_SECOND
        val first = floor(leftSeconds / interval).toInt().coerceIn(0, amplitudes.lastIndex)
        val last = ceil(rightSeconds / interval).toInt().coerceIn(first, amplitudes.lastIndex)
        fun x(seconds: Double) = ((seconds - leftSeconds) * pixelsPerSecond).toFloat()
        fun y(point: Int, direction: Float) = centerY + amplitudes[point] * scaleY * direction

        path.moveTo(x(first * interval), y(first, -1f))
        for (point in first + 1..last) path.lineTo(x(point * interval), y(point, -1f))
        if (last == amplitudes.lastIndex) {
            path.lineTo(x(trackEnd), y(last, -1f))
            path.lineTo(x(trackEnd), y(last, 1f))
        }
        for (point in last downTo first) path.lineTo(x(point * interval), y(point, 1f))
        path.close()
        return path
    }

    private companion object {
        const val FRAMES_PER_SECOND = 50.0
    }
}
