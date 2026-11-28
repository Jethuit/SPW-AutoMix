package automix

import com.sun.jna.Memory
import com.sun.jna.Pointer

internal object HostBassLiveFilter {
    private const val HIGH_PASS = 1
    private const val ALL_CHANNELS = -1
    private const val BUTTERWORTH_Q = 0.70710677f
    private const val BQF_SIZE = 28L
    private const val FILTER_OFFSET = 0L
    private const val CENTER_OFFSET = 4L
    private const val GAIN_OFFSET = 8L
    private const val BANDWIDTH_OFFSET = 12L
    private const val Q_OFFSET = 16L
    private const val SLOPE_OFFSET = 20L
    private const val CHANNEL_OFFSET = 24L

    // BASS_BFX_BQF has seven 32-bit fields. Mutate the host-allocated buffer in place because SPW's
    // WorkshopMethodAdvice leaves the returned reference unchanged.
    fun writeHighPass(result: Any?, cutoffHz: Double): Boolean {
        val pointer = result as? Pointer ?: return false
        if (pointer is Memory && pointer.size() < BQF_SIZE) return false
        pointer.setInt(FILTER_OFFSET, HIGH_PASS)
        pointer.setFloat(CENTER_OFFSET, cutoffHz.toFloat())
        pointer.setFloat(GAIN_OFFSET, 0f)
        pointer.setFloat(BANDWIDTH_OFFSET, 0f)
        pointer.setFloat(Q_OFFSET, BUTTERWORTH_Q)
        pointer.setFloat(SLOPE_OFFSET, 0f)
        pointer.setInt(CHANNEL_OFFSET, ALL_CHANNELS)
        return true
    }
}
