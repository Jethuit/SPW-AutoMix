package automix

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

internal data class TrackAnalysisMetadata(val bpm: Double?, val keyName: String?, val camelot: String?) {
    val keyLabel: String? get() = if (keyName != null && camelot != null) "$keyName · $camelot" else null

    val bpmLabel: String? get() = bpm?.let { "${formatBpm(it)} BPM" }

    fun tempoPair(other: TrackAnalysisMetadata): String? {
        val from = bpm ?: return null
        val to = other.bpm ?: return null
        return "${formatBpm(from)} → ${formatBpm(to)} BPM"
    }

    companion object {
        private fun formatBpm(value: Double): String {
            val tenths = (value * 10).roundToInt()
            return if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
        }
    }
}

internal data class PlaylistScanResult(val analyzed: Int, val firstTrackError: String?)

internal data class PlaylistSequenceResult(
    val matched: List<Int>,
    val all: List<Int>,
    val analyzed: Int,
    val weakLinks: Int
)

internal data class PairAnalysis(val a: DspTrack, val b: DspTrack, val recommendation: DspRecommendation)

internal class Worker(
    private val cache: Path,
    private val autoPauseHost: () -> Unit = {}
) {
    private val requests = ReentrantLock()
    private var activeThread: Thread? = null
    private var activePreview = false
    private var clip: Clip? = null
    private var closed = false
    private var hostPlaying: Boolean? = null
    private var audition = 0L
    private val ffmpeg by lazy {
        if (Worker::class.java.getResource("/automix-system-runtime") != null) {
            System.getenv("AUTOMIX_FFMPEG")?.takeIf { it.isNotBlank() } ?: "ffmpeg"
        } else {
            try {
                PortableRuntime.prepare(cache.resolveSibling("runtime")).resolve("ffmpeg.exe").toString()
            } catch (e: IOException) {
                checkDspInterrupted()
                throw AudioRuntimeException("无法准备 FFmpeg · ${e.message}", e)
            }
        }
    }
    private val tracks = DspTrackCache(cache) { ffmpeg }

    private data class RecommendationKey(
        val a: DspProfile, val b: DspProfile, val algorithm: MixAlgorithm,
        val featuresA: DspMixFeatures?, val featuresB: DspMixFeatures?
    )

    // Accessed under requests. Profile identities change when a source file changes.
    private val recommendations = object : LinkedHashMap<RecommendationKey, DspRecommendation>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RecommendationKey, DspRecommendation>) = size > 12
    }

    private data class PreviewKey(val a: String, val b: String, val plan: Plan, val window: TransitionWindow)

    private var cachedKey: PreviewKey? = null
    private var cachedWav: ByteArray? = null

    private fun <T> process(preview: Boolean = false, action: () -> T): T {
        requests.lockInterruptibly()
        try {
            synchronized(this) {
                check(!closed) { "插件已停用" }
                activeThread = Thread.currentThread()
                activePreview = preview
            }
            checkDspInterrupted()
            return action()
        } finally {
            synchronized(this) {
                activeThread = null
                activePreview = false
            }
            requests.unlock()
        }
    }

    @Synchronized fun playingChanged(playing: Boolean) {
        hostPlaying = playing
        if (playing) stopPreview()
    }

    @Synchronized fun stopPreview() {
        audition++
        if (activePreview) activeThread?.interrupt()
        val old = clip
        clip = null
        old?.stop()
        old?.close()
    }

    @Synchronized fun isPlaying(): Boolean = clip?.isOpen == true

    @Synchronized fun positionSeconds(): Double? {
        val c = clip ?: return null
        return if (c.isOpen) c.microsecondPosition / 1_000_000.0 else null
    }

    @Synchronized fun seekSeconds(sec: Double): Boolean {
        val c = clip?.takeIf { it.isOpen } ?: return false
        c.seekTo(sec)
        if (!c.isRunning) c.start()
        return true
    }

    private fun Clip.seekTo(sec: Double) {
        microsecondPosition = (sec * 1_000_000.0).toLong().coerceIn(0L, (microsecondLength - 1).coerceAtLeast(0L))
    }

    @Synchronized private fun listen(wavBytes: ByteArray, key: PreviewKey, ticket: Long, position: () -> Double?) {
        check(!closed && audition == ticket) { "试听已取消" }
        stopPreview()
        val next = AudioSystem.getClip()
        try {
            AudioSystem.getAudioInputStream(ByteArrayInputStream(wavBytes)).use { next.open(it) }
            next.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP) synchronized(this) {
                    if (clip === next && next.microsecondPosition >= next.microsecondLength - 50_000L) {
                        stopPreview()
                    }
                }
            }
            position()?.let { next.seekTo(it) }
            // A plugin enabled during playback may not have received an initial playback event yet.
            if (hostPlaying != false) autoPauseHost()
            clip = next
            next.start()
            cachedKey = key
            cachedWav = wavBytes
        } catch (e: Exception) {
            if (clip === next) clip = null
            next.close()
            throw e
        }
    }

    suspend fun planPair(a: String, b: String, algorithm: MixAlgorithm): Plan = runInterruptible(Dispatchers.IO) {
        process { recommend(a, b, algorithm).plan }
    }

    suspend fun analyzePair(a: String, b: String, algorithm: MixAlgorithm): PairAnalysis = runInterruptible(Dispatchers.IO) {
        process {
            val recommendation = recommend(a, b, algorithm)
            PairAnalysis(tracks.track(a), tracks.track(b), recommendation)
        }
    }

    private fun recommend(a: String, b: String, algorithm: MixAlgorithm): DspRecommendation {
        val (first, featuresA) = profile(a, algorithm)
        val (second, featuresB) = profile(b, algorithm)
        val key = RecommendationKey(first, second, algorithm, featuresA, featuresB)
        return recommendations.getOrPut(key) {
            val result = if (algorithm == MixAlgorithm.Spotify) {
                SpotifyMixPlanning.transition(first, second, featuresA, featuresB)
            } else {
                DspPlanning.transition(first, second)
            }
            check(result.plan.valid()) { "自动过渡参数无效" }
            result
        }
    }

    private fun profile(path: String, algorithm: MixAlgorithm): Pair<DspProfile, DspMixFeatures?> {
        if (algorithm == MixAlgorithm.Original) return tracks.profile(path) to null
        return try {
            tracks.mix(path)
        } catch (e: IOException) {
            checkDspInterrupted()
            if (e is AudioRuntimeException) throw e
            tracks.cachedMix(path) ?: throw e
        } catch (e: IllegalArgumentException) {
            // An older, longer track can still supply tempo and cues when a new decode exceeds the limit.
            tracks.cachedMix(path) ?: throw e
        }
    }

    suspend fun preAnalyze(path: String, algorithm: MixAlgorithm) = runInterruptible(Dispatchers.IO) {
        process { profile(path, algorithm) }
        Unit
    }

    suspend fun scanPlaylist(
        paths: List<String?>, algorithm: MixAlgorithm, onProgress: (Int) -> Unit
    ): PlaylistScanResult {
        var firstError: String? = null
        var analyzed = 0
        for ((index, path) in paths.withIndex()) {
            coroutineContext.ensureActive()
            if (path != null) {
                try {
                    runInterruptible(Dispatchers.IO) { process { profile(path, algorithm) } }
                    analyzed++
                } catch (e: IOException) {
                    coroutineContext.ensureActive()
                    if (e is AudioRuntimeException) throw e
                    if (firstError == null) firstError = e.message ?: "音频解码失败"
                } catch (e: IllegalArgumentException) {
                    if (firstError == null) firstError = e.message ?: "音频分析失败"
                }
            }
            onProgress(index + 1)
        }
        return PlaylistScanResult(analyzed, firstError)
    }

    suspend fun sequenceTracks(paths: List<String?>, algorithm: MixAlgorithm, anchorFirst: Boolean): PlaylistSequenceResult {
        val result = runInterruptible(Dispatchers.IO) {
            process {
                if (algorithm == MixAlgorithm.Spotify) {
                    val input = paths.mapIndexed { index, path ->
                        path?.let(tracks::cachedMix)?.let { (profile, features) ->
                            SpotifyMixPlanning.sequenceTrack(index, profile, features)
                        }
                    }
                    SpotifyMixPlanning.sequence(input, anchorFirst)
                } else {
                    DspPlanning.sequence(paths.map { it?.let(tracks::cachedProfile) }, anchorFirst)
                }
            }
        }
        check(result.all.size == paths.size && result.all.toSet() == paths.indices.toSet() &&
            result.matched.distinct().size == result.matched.size &&
            result.matched.all { it in paths.indices }) { "歌单排序结果无效" }
        return result
    }

    suspend fun cachedTrackMetadata(paths: List<String>): Map<String, TrackAnalysisMetadata> = runInterruptible(Dispatchers.IO) {
        paths.mapNotNull { path ->
            if (Thread.interrupted()) throw InterruptedException()
            tracks.cachedProfile(path)?.let { path to TrackAnalysisMetadata(it.bpm, it.keyName, it.camelot) }
        }.toMap()
    }

    suspend fun preview(
        a: String,
        b: String,
        plan: Plan,
        window: TransitionWindow,
        position: () -> Double? = { null }
    ) = runInterruptible(Dispatchers.IO) {
        val key = PreviewKey(a, b, plan, window)
        val (ticket, hitBytes) = synchronized(this) {
            check(!closed) { "插件已停用" }
            audition to cachedWav?.takeIf { cachedKey == key }
        }

        val wav = hitBytes ?: process(preview = true) {
            synchronized(this) { check(audition == ticket) { "试听已取消" } }
            val first = tracks.profile(a)
            val second = tracks.profile(b)
            DspPreview.render(a, b, second.duration, plan, window, ffmpeg, lufsA = first.lufs, lufsB = second.lufs)
        }
        if (Thread.interrupted()) throw InterruptedException()
        listen(wav, key, ticket, position)
    }

    @Synchronized fun stop() {
        closed = true
        activeThread?.interrupt()
        cachedWav = null
        cachedKey = null
        stopPreview()
    }
}
