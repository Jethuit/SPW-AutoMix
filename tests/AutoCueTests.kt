@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package automix

import com.xuncorp.spw.workshop.api.config.ConfigHelper
import java.net.URI
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        ::delayedIncomingCueKeepsAnAudibleWindowWhenEdited,
        ::transitionPresetsRoundTripAndKeepAuditionOnlyEffectsExplicit,
        ::reverbOptionsShareCurvesAndKeepSavedEcho,
        ::fastOutKeepsTheOutgoingTrackUpLonger,
        ::swapUsagePivotDecouplingAndPresetMatching,
        ::transitionHistoryRestoresSelectionsAndGroupsDrag,
        ::manualPresetEditAndDeletionCanBeUndone,
        ::updateRedirectsStayOnGitHub,
        ::automaticUpdateFailuresRetryBeforeTomorrow
    )
    for (test in tests) {
        test()
        println("PASS ${test.name}")
    }
}

private fun transitionHistoryRestoresSelectionsAndGroupsDrag() {
    val plan = Plan(20.0, 2.0, 120.0, 120.0, 4, "smooth", false, 0.5)
    val initial = TransitionEdit(plan, TransitionSelection())
    val custom = initial.copy(selection = TransitionSelection(custom = true))
    val firstDrag = custom.copy(plan = plan.copy(cueB = 2.2))
    val finalDrag = custom.copy(plan = plan.copy(cueB = 3.0))
    val history = TransitionEditHistory()

    history.record(initial, custom)
    history.beginGesture(custom)
    history.record(custom, firstDrag)
    history.record(firstDrag, finalDrag)
    check(!history.canUndo && !history.canRedo)
    history.finishGesture(finalDrag)
    check(history.undo(finalDrag) == custom)
    check(history.undo(custom) == initial && history.selection == initial.selection && !history.canUndo)
    check(history.redo(initial) == custom && history.selection == custom.selection)

    history.beginGesture(custom)
    history.finishGesture(custom)
    check(history.canRedo) { "An unchanged drag should preserve redo" }
    check(history.redo(custom) == finalDrag && !history.canRedo)
    check(history.undo(finalDrag) == custom)
    val otherEdit = custom.copy(plan = plan.copy(snap = true), selection = TransitionSelection(presetId = "fade"))
    history.record(custom, otherEdit)
    check(history.selection == otherEdit.selection && !history.canRedo)
    check(!TransitionEditHistory().canUndo)
}

private fun manualPresetEditAndDeletionCanBeUndone() {
    val plan = Plan(20.0, 2.0, 120.0, 120.0, 4, "smooth", false, 0.5)
    val preset = TransitionSelection(presetId = "fade")
    check(preset.afterManualChange(plan, plan) == preset)
    val edited = plan.copy(volume = "full-overlap")
    val selection = preset.afterManualChange(plan, edited)
    check(selection.custom && selection.presetId == null)

    val saved = TransitionEdit(plan, preset)
    val removed = TransitionEdit(null, TransitionSelection())
    val history = TransitionEditHistory()
    history.record(saved, removed)
    check(history.undo(removed) == saved)
    check(history.redo(saved) == removed)
}

private fun updateRedirectsStayOnGitHub() {
    val endpoint = URI("https://api.github.com/repos/a/b/releases/latest")
    check(AutoMixReleaseUpdate.redirectTarget(endpoint, "/repositories/123/releases/latest")?.host == endpoint.host)
    for (target in listOf("http://api.github.com/r", "https://api.github.com.example/r", "//elsewhere.test/r",
        "https://api.github.com:8443/r", "https://name@api.github.com/r", endpoint.toString())) {
        check(AutoMixReleaseUpdate.redirectTarget(endpoint, target) == null)
    }
}

private fun automaticUpdateFailuresRetryBeforeTomorrow() = runBlocking {
    val settings = object : ConfigHelper {
        private val values = mutableMapOf<String, Any>()
        @Suppress("UNCHECKED_CAST")
        override fun <T> get(key: String, defaultValue: T): T = (values[key] ?: defaultValue) as T
        override fun set(key: String, value: Any) { values[key] = value }
        override fun save() = true
        override fun reload() = true
        override fun getConfigPath(): Path = Path.of("updates.json")
    }
    var now = 10_000L
    var calls = 0
    val retry = 15 * 60 * 1_000L
    val updates = AutoMixUpdates("0.3.0", this, { settings }, {}, fetch = {
        when (++calls) {
            1 -> AutoMixReleaseCheck.Failed("offline")
            2 -> AutoMixReleaseCheck.Failed("rate limited", now + 2 * retry)
            else -> AutoMixReleaseCheck.Current("v0.3.0")
        }
    }, clock = { now })
    updates.check(manual = false)
    now += retry - 1
    updates.check(manual = false)
    check(calls == 1 && updates.notice.value.checkedAt == 0L && updates.notice.value.checkError == null)
    now++
    updates.check(manual = false)
    check(calls == 2)
    now += retry
    updates.check(manual = true)
    check(calls == 2)
    now += retry
    updates.check(manual = false)
    check(calls == 3 && updates.notice.value.checkedAt == now)
    now += retry
    updates.check(manual = false)
    check(calls == 3)
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

private fun transitionPresetsRoundTripAndKeepAuditionOnlyEffectsExplicit() {
    val base = Plan(160.0, 2.0, 120.0, 120.0, 4, "smooth", false, 0.5)
    val presets = Plan.presets.map { preset ->
        val plan = preset.apply(base)
        check(plan.valid() && preset.matches(plan) && Plan.read(plan.json()) == plan) {
            "Preset cannot be saved and restored: ${preset.id}"
        }
        plan
    }
    check(presets.toSet().size == presets.size) { "Two preset buttons use identical settings" }
    val noMix = presets.single { it.noMix }
    check(!noMix.useHost && noMix.window(180_000, 180_000) == null)

    val preview = base.copy(eq = "three-band", filter = "high-out-low-in", bass = true)
    check(preview.hasPreviewOnlyEffects() && preview.previewOnlyEq)
    check(preview.filterSweep(0)?.lowPass == false && preview.filterSweep(1)?.lowPass == true)
    check(Plan.read(preview.json()) == preview)
}

private fun fastOutKeepsTheOutgoingTrackUpLonger() {
    val plan = Plan(160.0, 2.0, 120.0, 120.0, 4, "fade-fast-out", false, 0.5)
    val window = checkNotNull(plan.window(180_000, 180_000))
    val knots = window.gains(plan)
    val duringHold = knots.single { it.first == 0.625 }
    check(duringHold.second == 1.0 && duringHold.third == 1.0) { "The outgoing track faded too soon" }
    check(knots.first() == Triple(0.0, 1.0, 0.0) && knots.last() == Triple(1.0, 0.0, 1.0))
}

private fun reverbOptionsShareCurvesAndKeepSavedEcho() {
    val plan = Plan(160.0, 2.0, 120.0, 120.0, 4, "smooth", false, 0.5)
    val window = checkNotNull(plan.window(180_000, 180_000))
    val mid = TransitionEffect.ReverbMidFade
    val cut = TransitionEffect.ReverbEndCut
    val fade = TransitionEffect.ReverbEndFade
    check(TransitionEffect.options.keys.toList() == listOf(mid, cut, fade, TransitionEffect.None))
    for (effect in listOf(mid, cut, fade)) {
        val edited = plan.copy(effect = effect)
        check(Plan.read(edited.json()) == edited && edited.hasPreviewOnlyEffects() && !edited.echo)
    }
    check(window.reverbWet(mid, 0.25) == 0.5 && window.reverbWet(mid, 0.75) == 1.0)
    check(window.reverbWet(fade, 0.25) == 0.0 && window.reverbWet(fade, 0.75) == 0.5)
    check(window.effectKnots(cut).last().second == 0.0 && window.effectKnots(fade).last().second == 1.0)
    val legacy = JsonObject((plan.json() - "effect") + ("echo" to JsonPrimitive(true)))
    check(Plan.read(legacy).effect == TransitionEffect.LegacyEcho)
}

private fun swapUsagePivotDecouplingAndPresetMatching() {
    val plain = Plan(160.0, 2.0, 120.0, 120.0, 4, "smooth", false, 0.5, filter = "none", eq = "none")
    check(!plain.usesSwap)
    check(!plain.copy(eq = "bass-swap-start").usesSwap)
    check(!plain.copy(eq = "bass-swap-end").usesSwap)
    check(!plain.copy(eq = "bass-fade-out", filter = "noise-out").usesSwap)
    check(!plain.copy(eq = "bass-swap", volume = "overlap", filter = "low-out", useHost = true).usesSwap)
    check(!plain.copy(eq = "bass-swap", volume = "off", filter = "low-out").usesSwap)

    for (eq in listOf("bass-swap", "fast-bass-cut", "bass-cut-fast", "bass-cut-long", "three-band")) {
        check(plain.copy(eq = eq).usesSwap) { "Expected usesSwap for eq=$eq" }
    }
    for (volume in listOf("overlap", "mid-cut", "fade-cut", "cut-fade")) {
        check(plain.copy(volume = volume).usesSwap) { "Expected usesSwap for volume=$volume" }
    }
    for (filter in listOf("low-out", "low-in", "high-out-half")) {
        check(plain.copy(filter = filter).usesSwap) { "Expected usesSwap for filter=$filter" }
    }

    val startSwap = plain.copy(eq = "bass-swap-start", volume = "cut-fade", swap = 0.72)
    val endSwap = plain.copy(eq = "bass-swap-end", volume = "fade-cut", swap = 0.28)
    check(startSwap.pivot == 0.72 && startSwap.eqCenter == 0.02)
    check(endSwap.pivot == 0.28 && endSwap.eqCenter == 0.98)

    val earlyCut = checkNotNull(plain.copy(eq = "bass-cut-long", swap = 0.05).eqEnvelope(0))
    val lateCut = checkNotNull(plain.copy(eq = "bass-cut-long", swap = 0.95).eqEnvelope(0))
    check(earlyCut.start in 0.0..1.0 && earlyCut.end in 0.0..1.0 && earlyCut.start < earlyCut.end)
    check(lateCut.start in 0.0..1.0 && lateCut.end in 0.0..1.0 && lateCut.start < lateCut.end)

    val offPreset = Plan.presets.single { it.id == "off" }
    check(offPreset.matches(offPreset.apply(plain).copy(swap = 0.70)))
    val noSwapPreset = Plan.Preset("flat", "Flat", "smooth", "bass-fade-out", "none")
    check(noSwapPreset.matches(noSwapPreset.apply(plain).copy(swap = 0.70)))

    val fadePreset = Plan.presets.single { it.id == "fade" }
    check(fadePreset.matches(fadePreset.apply(plain)))
    check(!fadePreset.matches(fadePreset.apply(plain).copy(swap = 0.70)))

    val risePreset = Plan.presets.single { it.id == "rise" }
    check(risePreset.apply(plain).usesSwap)
    check(!risePreset.matches(risePreset.apply(plain).copy(swap = 0.70)))
}
