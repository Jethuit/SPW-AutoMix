package automix

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

fun main() {
    val tests = listOf(
        ::introUsesSustainedSectionAfterEarlyLift,
        ::fullIntensityAtStartKeepsOpeningEligible,
        ::shortOverlapCanAlignWhenFourBarsWouldDrift,
        ::noncentralDownbeatTakesPriorityOverAnOrdinaryBeat,
        ::ordinaryBeatsCanAlignWithoutDownbeats,
        ::delayedIncomingCueKeepsAnAudibleWindowWhenEdited
    )
    for (test in tests) {
        test()
        println("PASS ${test.name}")
    }
}

private fun track(loudness: DoubleArray, bpm: Double = 60.0): DspProfile {
    val duration = loudness.size * 2
    val bar = 240.0 / bpm
    val downbeats = DoubleArray((duration / bar).toInt()) { 1.0 + it * bar }
    return DspProfile(
        duration = duration.toDouble(),
        bpm = bpm,
        bpmConfidence = 0.95,
        beats = downbeats,
        downbeats = downbeats,
        keyName = null,
        camelot = null,
        lufs = -5.0,
        lufs2s = loudness,
        lowRatio1s = DoubleArray(duration) { 0.2 },
        lowRatioHead = 0.2,
        lowRatioTail = 0.2
    )
}

private fun introUsesSustainedSectionAfterEarlyLift() {
    val outgoing = track(DoubleArray(90) { -6.0 })
    val incoming = track(
        DoubleArray(4) { -16.0 } + DoubleArray(4) { -9.0 } + doubleArrayOf(-5.0, -12.0) +
            DoubleArray(5) { -9.0 } + DoubleArray(35) { -5.0 }
    )
    // The brief lift is at 16s. The sustained rise begins at 30s, near the 29s downbeat.
    val plan = DspPlanning.transition(outgoing, incoming).plan
    check(plan.snap && plan.cueB > 12.0) { "The cue followed the early lift: $plan" }
    check(plan.json()["sync"]?.jsonPrimitive?.boolean == false) { "The transition changed playback speed: $plan" }
    val matches = sharedOffsets(plan, outgoing.downbeats, incoming.downbeats)
    check(matches.any { abs(plan.cueB + it - 29.0) <= 4.0 }) { "No shared downbeat near the sustained rise: $plan" }
}

private fun fullIntensityAtStartKeepsOpeningEligible() {
    val incoming = track(DoubleArray(6) { -5.0 } + DoubleArray(6) { -16.0 } + DoubleArray(45) { -5.0 })
    val plan = DspPlanning.transition(track(DoubleArray(80) { -5.0 }), incoming).plan
    check(plan.cueB == 1.0) { "The full-intensity opening was skipped: $plan" }
}

private fun shortOverlapCanAlignWhenFourBarsWouldDrift() {
    val outgoing = track(DoubleArray(80) { -5.0 }, bpm = 126.0)
    val incoming = track(DoubleArray(80) { -6.0 }, bpm = 130.0)
    val plan = DspPlanning.transition(outgoing, incoming).plan
    check(plan.bars == 2 && plan.snap) { "Expected a two-bar aligned overlap: $plan" }
    check(plan.json()["sync"]?.jsonPrimitive?.boolean == false) { "The transition changed playback speed: $plan" }
    val tempoGap = abs(outgoing.bpm - incoming.bpm)
    val matches = sharedOffsets(plan, outgoing.downbeats, incoming.downbeats)
    check(matches.any { maxOf(it, plan.duration - it) * tempoGap / 60.0 <= 0.251 }) {
        "No shared downbeat meets the drift limit: $plan"
    }
}

private fun noncentralDownbeatTakesPriorityOverAnOrdinaryBeat() {
    val outgoing = track(DoubleArray(12) { -5.0 }, bpm = 120.0)
        .copy(beats = doubleArrayOf(15.0, 20.5), downbeats = doubleArrayOf(20.5))
    val incoming = track(DoubleArray(12) { -5.0 }, bpm = 120.0)
        .copy(beats = doubleArrayOf(0.0, 1.2), downbeats = doubleArrayOf(1.2))
    val plan = DspPlanning.transition(outgoing, incoming).plan
    val matches = sharedOffsets(plan, outgoing.downbeats, incoming.downbeats)
    check(plan.snap && matches.isNotEmpty()) { "Expected the downbeats to align: $plan" }
    check(matches.all { abs(it - plan.duration / 2.0) > 0.4 }) {
        "The alignment did not use the off-center downbeat: $matches, $plan"
    }
}

private fun ordinaryBeatsCanAlignWithoutDownbeats() {
    val profile = track(DoubleArray(12) { -5.0 }).copy(downbeats = doubleArrayOf())
    val plan = DspPlanning.transition(profile, profile).plan
    check(plan.snap && sharedOffsets(plan, profile.beats, profile.beats).isNotEmpty()) {
        "Expected ordinary beats to align when downbeats are unavailable: $plan"
    }
}

private fun delayedIncomingCueKeepsAnAudibleWindowWhenEdited() {
    val delayed = Plan(170.0, -6.0, 120.0, 120.0, 8, "smooth", false, 0.5)
    val short = delayed.withBars(2, 180_000, 180_000)
    check(short.cueA == delayed.cueA && short.cueB == -3.8 && short.window(180_000, 180_000) != null) {
        "Changing length lost the available overlap: $short"
    }
    val latestCue = delayed.cueBounds(0, 180_000, 180_000).endInclusive
    val nearEnd = delayed.copy(cueA = latestCue)
    check(latestCue == 173.8 && nearEnd.window(180_000, 180_000)?.durationMs == Plan.MIN_OVERLAP_MS) {
        "The outgoing drag bound did not leave room for the incoming lead: $nearEnd"
    }
}

private fun sharedOffsets(plan: Plan, gridA: DoubleArray, gridB: DoubleArray): List<Double> {
    val offsetsB = gridB.map { it - plan.cueB }
    return gridA.map { it - plan.cueA }.filter { offset ->
        offset in -0.001..(plan.duration + 0.001) && offsetsB.any { abs(it - offset) < 0.002 }
    }
}
