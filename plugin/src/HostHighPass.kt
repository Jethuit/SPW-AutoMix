package automix

/** The host keeps the BQF slot and schedules and removes it like any other crossover filter. */
internal object HostHighPass {
    private const val cutoffOffset = 100_000.0
    private const val boundaryMargin = 1.0

    fun encode(cutoffHz: Double): Double = cutoffOffset + cutoffHz

    fun decode(value: Double): Double? {
        val raw = value - cutoffOffset
        if (raw !in (Plan.AUDITION_HPF_MIN_HZ - boundaryMargin)..(Plan.AUDITION_HPF_MAX_HZ + boundaryMargin)) {
            return null
        }
        return raw.coerceIn(Plan.AUDITION_HPF_MIN_HZ, Plan.AUDITION_HPF_MAX_HZ)
    }
}
