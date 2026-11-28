package automix

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

fun main(args: Array<String>) {
    val (outgoing, outgoingFeatures) = fixture(120.0, "9B")
    val (incoming, incomingFeatures) = fixture(125.0, "10B")
    val comparison = SpotifyMixPlanning.transition(outgoing, incoming, outgoingFeatures, incomingFeatures)
    val plan = comparison.plan
    check(plan.valid() && plan.window(120_000, 120_000)?.durationMs == 8000L) { comparison }
    check(plan.cueA + plan.duration in 90.0..119.7 && plan.cueB in 0.0..24.0)
    check(comparison.reasons.first().contains("强拍窗口"))
    val lowConfidence = SpotifyMixPlanning.transition(outgoing, incoming.copy(bpmConfidence = 0.1),
        outgoingFeatures, incomingFeatures)
    check(!lowConfidence.plan.snap && lowConfidence.plan.window(120_000, 120_000) != null)
    check("不锁拍" in lowConfidence.reasons.first())

    val (denseA, featureA) = fixture(120.0, "9B", activity = 0.7f)
    val (denseB, featureB) = fixture(120.0, "9B", activity = 0.7f)
    val dense = SpotifyMixPlanning.transition(denseA, denseB, featureA, featureB).plan
    check(dense.volume == "mix-handoff" && dense.filter == "low-out")
    println("PASS 120/125 BPM four-bar plan, playback window, and adaptive envelope")
    checkBeatSearch(outgoing, outgoingFeatures)
    checkQuietProgramAndGainCurves()

    val profiles = listOf(
        fixture(125.0, "10B").first, fixture(156.0, "3A").first, fixture(120.0, "9B").first,
        null, fixture(122.0, "9B").first, fixture(122.0, "9B").first
    )
    val input = profiles.mapIndexed { i, profile -> profile?.let { SpotifyMixPlanning.sequenceTrack(i, it, null) } }
    val ordered = SpotifyMixPlanning.sequence(input)
    check(ordered.all.sorted() == input.indices.toList() && ordered.all.last() == 3)
    check(ordered.matched.toSet() == setOf(0, 2, 4, 5) && ordered.weakLinks == 2) { ordered }
    check(SpotifyMixPlanning.sequence(emptyList()).weakLinks == 0)
    val anchored = SpotifyMixPlanning.sequence(input, anchorFirst = true)
    check(anchored.all.first() == 0 && anchored.all.sorted() == input.indices.toList())
    checkOriginalQueueAnchor()
    checkQueueReordering()
    println("PASS harmonic sequencing, duplicate tracks, and unavailable entries")

    if (args.isNotEmpty()) {
        check(args.size == 2) { "Expected FFmpeg and a scratch directory" }
        val root = Files.createTempDirectory(Path.of(args[1]), "spotify-mix-")
        val a = synthesize(root.resolve("outgoing.wav"), 120.0, doubleArrayOf(196.0, 247.0, 294.0))
        val b = synthesize(root.resolve("incoming.wav"), 125.0, doubleArrayOf(294.0, 370.0, 440.0))
        validateCache(args[0], root.resolve("cache"), a.toString(), b.toString())
    }
}

private fun checkBeatSearch(outgoing: DspProfile, features: DspMixFeatures) {
    fun plan(bpm: Double): DspRecommendation {
        val (incoming, incomingFeatures) = fixture(bpm, "9B")
        return SpotifyMixPlanning.transition(outgoing, incoming, features, incomingFeatures)
    }
    val short = plan(135.0)
    check(short.plan.snap && short.plan.bars == 1 && Plan.read(short.plan.json()) == short.plan) { short }
    val half = plan(60.0)
    check(half.plan.snap && "倍半速" in half.reasons.first()) { half }
    val wide = plan(93.0)
    check(!wide.plan.snap && "不锁拍" in wide.reasons.first() && wide.plan.window(120_000, 120_000) != null) { wide }
    val ordinary = SpotifyMixPlanning.transition(
        outgoing.copy(downbeats = doubleArrayOf()), outgoing, features, features
    )
    check(ordinary.plan.snap && "普通拍点" in ordinary.reasons.first()) { ordinary }
    val coarse = SpotifyMixPlanning.transition(outgoing, outgoing, null, features)
    check(!coarse.plan.snap && "不锁拍" in coarse.reasons.first() && coarse.plan.window(120_000, 120_000) != null)
    println("PASS one-bar beatmatch, half/double tempo, regular-beat search, and unaligned spectral handoff")
}

private fun checkQuietProgramAndGainCurves() {
    val (outgoing, outFeatures) = fixture(120.0, "9B")
    val (incoming, inFeatures) = fixture(90.0, "8B")
    val tail = outFeatures.copy(loudness100ms = FloatArray(1200) { i ->
        when {
            i < 1140 -> -14f
            i < 1180 -> -47f
            else -> -110f
        }
    })
    val head = inFeatures.copy(audibleStart = 8.0, loudness100ms = FloatArray(1200) { i ->
        when {
            i < 32 -> -110f
            i < 80 -> -44f
            else -> -14f
        }
    })
    val measuredTail = MixLoudness(outgoing, tail)
    val measuredHead = MixLoudness(incoming, head)
    check(measuredTail.audibleEnd in 117.9..118.2 && measuredHead.audibleStart in 3.0..3.2)
    val p = SpotifyMixPlanning.transition(outgoing, incoming, tail, head).plan
    check(!p.snap && p.cueB >= 3.0 && p.window(120_000, 120_000) != null) { p }
    val coarse = SpotifyMixPlanning.transition(outgoing, incoming, null, null).plan
    check(coarse.cueB == 0.0 && !coarse.snap && coarse.volume == "mix-power") { coarse }

    fun overlap(curve: String): Int {
        val plan = p.copy(volume = curve, swap = 0.43)
        val window = checkNotNull(plan.window(120_000, 120_000))
        val knots = window.gains(plan)
        check(knots.first().second == 1.0 && knots.first().third == 0.0)
        check(knots.last().second == 0.0 && knots.last().third == 1.0)
        check(knots.all { (_, a, b) -> abs(a * a + b * b - 1.0) < 1e-9 })
        check(knots.any { (phase, a, b) -> abs(phase - 0.43) < 1e-6 && abs(a - b) < 1e-6 })
        check(Plan.read(plan.json()) == plan)
        return knots.count { (_, a, b) -> a > 0.4 && b > 0.4 }
    }
    check(overlap("mix-handoff") < overlap("mix-power"))
    println("PASS short-term analysis recognizes quiet audio, and automatic envelopes retain mix power")
}

private fun checkOriginalQueueAnchor() {
    val current = fixture(129.0, "9B").first.copy(lufs2s = DoubleArray(60) { if (it >= 52) -2.0 else -14.0 })
    val upcoming = listOf(120.0, 123.0, 126.0).map { fixture(it, "9B").first }
    val profiles = listOf(current) + upcoming

    val unanchored = DspPlanning.sequence(profiles)
    check(unanchored.all == listOf(1, 2, 3, 0)) { unanchored }
    val anchored = DspPlanning.sequence(profiles, anchorFirst = true)
    check(anchored.all == listOf(0, 3, 2, 1) && anchored.weakLinks == 0) { anchored }

    for (unavailable in listOf(null, current.copy(bpmConfidence = 0.1))) {
        val result = DspPlanning.sequence(listOf(unavailable) + upcoming, anchorFirst = true)
        check(result.all.first() == 0 && result.all.sorted() == profiles.indices.toList()) { result }
    }
    println("PASS original queue sequencing optimizes the first transition from the playing track")
}

private fun checkQueueReordering() = runBlocking {
    val initial = QueueOrder(listOf(43, 3, 51, 52, 19, 24), currentIndex = 1, shuffled = false)
    val target = listOf(43, 3, 24, 51, 19, 52)
    var state = initial
    val outcome = applyUpcomingQueueOrder(initial, target, { state }) { before, from, to ->
        check(from >= initial.fixedPrefix && to >= initial.fixedPrefix)
        state = before.copy(ids = before.ids.toMutableList().apply { add(to, removeAt(from)) })
        state
    }
    check(outcome.complete && outcome.moves == 2 && state.ids == target && state.currentIndex == initial.currentIndex)

    val stale = applyUpcomingQueueOrder(initial, target, { state }) { _, _, _ -> error("Stale reorder must not move") }
    check(!stale.complete && stale.moves == 0)
    state = initial
    val duringPlaybackChange = applyUpcomingQueueOrder(initial, target, { state }) { before, from, to ->
        state = before.copy(
            ids = before.ids.toMutableList().apply { add(to, removeAt(from)) }, currentIndex = 2
        )
        state
    }
    check(!duringPlaybackChange.complete && state.currentIndex == 2)
    val shuffled = initial.copy(shuffled = true)
    val skipped = applyUpcomingQueueOrder(shuffled, target, { shuffled }) { _, _, _ -> error("Shuffle must not move") }
    check(!skipped.complete && skipped.moves == 0)
    println("PASS current queue keeps its playback anchor and stops on stale order or playback changes")
}

private fun fixture(bpm: Double, key: String, activity: Float = 0.08f): Pair<DspProfile, DspMixFeatures> {
    val duration = 120.0
    val times = DoubleArray((duration * bpm / 60).toInt()) { it * 60 / bpm }
    val downbeats = times.filterIndexed { index, _ -> index % 4 == 0 }.toDoubleArray()
    val profile = DspProfile(duration, bpm, 0.9, times, downbeats, key, key, -14.0,
        DoubleArray(60) { -14.0 }, DoubleArray(120) { 0.4 }, 0.4, 0.4)
    val width = DspMixFeatures.WIDTH
    val features = DspMixFeatures(times, FloatArray(times.size * width) { i ->
        when (i % width) {
            0, DspMixFeatures.CHROMA -> 1f
            DspMixFeatures.LEVEL -> 0.9f
            DspMixFeatures.VOICE -> activity
            DspMixFeatures.BASS -> 0.4f
            else -> 0f
        }
    }, FloatArray(times.size), FloatArray(times.size), 1.0, times.last())
    return profile to features
}

private fun synthesize(path: Path, bpm: Double, pitches: DoubleArray): Path {
    val rate = 22050
    val count = 72 * rate
    val samples = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
    val random = Random(11)
    val period = 60.0 / bpm
    for (i in 0 until count) {
        val time = i.toDouble() / rate
        val beat = (time / period).toInt()
        val bar = beat / 4
        val phase = time - beat * period
        val accent = if (beat % 4 == 0) 0.50 else 0.32
        val kick = accent * sin(2 * PI * (55 * phase + 0.9 * (1 - exp(-30 * phase)))) * exp(-40 * phase)
        val snare = (random.nextDouble() * 2 - 1) * exp(-65 * phase) * if (beat % 2 == 0) 0.025 else 0.18
        val root = pitches[bar / 4 % pitches.size]
        val harmonyGain = if (bar in 5..29) 0.033 else 0.012
        val chord = harmonyGain * (sin(2 * PI * root * time) + sin(2 * PI * pitches[1] * time) + sin(2 * PI * pitches[2] * time))
        val tail = ((71.75 - time) / 1.5).coerceIn(0.0, 1.0)
        samples.putShort(((kick + snare + chord) * tail * 30_000).toInt().toShort())
    }
    val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
    AudioInputStream(ByteArrayInputStream(samples.array()), format, count.toLong()).use {
        AudioSystem.write(it, AudioFileFormat.Type.WAVE, path.toFile())
    }
    return path
}

private fun validateCache(ffmpeg: String, root: Path, a: String, b: String) {
    var decodes = 0
    val cache = DspTrackCache(root) { decodes++; ffmpeg }
    val legacy = cache.profile(a)
    val start = System.nanoTime()
    val first = cache.mix(a)
    val second = cache.mix(b)
    val coldMs = (System.nanoTime() - start) / 1_000_000
    check(decodes == 3 && first.first === legacy)
    check(abs(first.first.bpm - 120.0) < 1.0 && abs(second.first.bpm - 125.0) < 1.0) {
        "BPM estimates: ${first.first.bpm}, ${second.first.bpm}"
    }
    val warmStart = System.nanoTime()
    val warm = DspTrackCache(root) { error("A warm cache must not launch FFmpeg") }
    val (firstWarm, featuresWarm) = warm.mix(a)
    warm.mix(b)
    val warmMs = (System.nanoTime() - warmStart) / 1_000_000
    check(first.first.beats.contentEquals(firstWarm.beats))
    check(first.second.descriptors.contentEquals(featuresWarm.descriptors))
    check(checkNotNull(first.second.loudness100ms).contentEquals(checkNotNull(featuresWarm.loudness100ms)))

    val originalPlan = DspPlanning.transition(first.first, second.first).plan
    val updated = SpotifyMixPlanning.transition(first.first, second.first, first.second, second.second)
    for (plan in listOf(originalPlan, updated.plan)) {
        val window = checkNotNull(plan.window((first.first.duration * 1000).toLong(), (second.first.duration * 1000).toLong()))
        val wav = DspPreview.render(a, b, second.first.duration, plan, window, ffmpeg, first.first.lufs, second.first.lufs)
        AudioSystem.getAudioInputStream(ByteArrayInputStream(wav)).use {
            check(it.format.channels == 2)
            check(it.frameLength == (window.previewBeforeMs + window.durationMs + 10_000L) * 48)
            check(it.readNBytes(48_000).any { sample -> sample != 0.toByte() })
        }
    }
    check(updated.reasons.first().contains("强拍窗口")) { updated }
    println("PCM original plan: ${originalPlan.cueA}/${originalPlan.cueB}, ${originalPlan.bars} bars")
    println("PCM Spotify plan: ${updated.plan.cueA}/${updated.plan.cueB}, ${updated.plan.bars} bars")
    println("PCM cache: preparation ${coldMs} ms, disk hits ${warmMs} ms")

    val changed = Path.of(a)
    Files.setLastModifiedTime(changed, FileTime.fromMillis(Files.getLastModifiedTime(changed).toMillis() + 1500))
    check(cache.cachedMix(a) == null)
    cache.mix(a)
    check(decodes == 4)
    println("PASS PCM analysis and rendering, cached profile reuse, durable feature hits, and source invalidation")
}
