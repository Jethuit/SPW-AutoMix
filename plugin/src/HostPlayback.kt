package automix

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.hook.MethodHookParam
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.math.*

internal data class QueueTrackEntry(
    val item: MediaItem,
    val rawIndex: Int,
    val queueId: Int,
    val sourceId: String?,
    val sourcePath: String?
)

internal data class QueueView(
    val entries: List<QueueTrackEntry> = emptyList(),
    val order: List<Int> = emptyList(),
    val currentIndex: Int = -1,
    val nextPair: Pair<MediaItem, MediaItem>? = null,
    val nextPairNote: String = "当前没有可编辑的下一首"
)

internal data class PlaybackTrack(val id: String, val path: String)

internal data class UpcomingTransition(val from: PlaybackTrack, val to: PlaybackTrack) {
    val key = pairKey(from.id, to.id)
}

internal data class PreparedAutoMix(val key: String, val plan: Plan)

internal class HostPlayback(symbols: HostSymbols) {
    private val mix = runCatching { HostMixTargets(symbols) }
    private val queue = runCatching { HostQueueTargets(symbols) }
    private val trackPlayback = runCatching { HostTrackPlayback(symbols) }
    private val playlist = runCatching { HostPlaylistTargets(symbols) }
    private val playlistReader = runCatching { HostPlaylistReader(symbols) }
    private val playlistCreation = runCatching { HostPlaylistCreation(symbols) }
    private val reschedule = runCatching { HostRescheduleTargets(symbols) }
    val planner get() = mix.getOrThrow().planner
    val canPlayTracks get() = trackPlayback.isSuccess
    val canReorderPlaylists get() = playlist.isSuccess
    val canCreatePlaylists get() = playlistCreation.isSuccess
    val warnings = listOf("过渡" to mix, "队列" to queue, "歌单读取" to playlistReader,
        "歌单排序" to playlist, "立即重排过渡" to reschedule)
        .mapNotNull { (role, result) -> result.exceptionOrNull()?.let { "$role · ${it.message ?: it.javaClass.simpleName}" } }

    private fun normPath(p: String): String = p.trim().replace('/', '\\').lowercase()

    private fun fmtCue(ms: Long): String {
        val sec = max(0L, ms / 1000L)
        return "%d:%02d.%03d".format(java.util.Locale.ROOT, sec / 60L, sec % 60L, max(0L, ms) % 1000L)
    }

    fun queueStates(): StateFlow<*> = queue.getOrNull()?.states() ?: unavailableQueue

    private val unavailableQueue: StateFlow<*> = kotlinx.coroutines.flow.MutableStateFlow<Any?>(null)

    private data class QueueSelection(val items: List<*>, val current: Int, val next: Int, val mode: String)

    private fun HostQueueTargets.selection(state: Any, normal: List<*>): QueueSelection {
        val mode = (queueMode.get(state) as Enum<*>).name
        val random = mode == "Random"
        val items = if (random) randomQueue.get(state) as List<*> else normal
        val current = if (random) randomIndex.getInt(state) else normalIndex.getInt(state)
        val next = when {
            current !in items.indices || mode == "RepeatOne" -> -1
            current + 1 < items.size -> current + 1
            mode == "Circle" && items.size > 1 -> 0
            else -> -1
        }
        return QueueSelection(items, current, next, mode)
    }

    fun upcomingTransition(state: Any?): UpcomingTransition? {
        val targets = queue.getOrNull() ?: return null
        if (state == null) return null
        return runCatching { with(targets) {
            val selection = selection(state, normalQueue.get(state) as List<*>)
            fun trackAt(index: Int): PlaybackTrack? {
                val item = selection.items.getOrNull(index) ?: return null
                val media = queueMedia.get(item) ?: return null
                val id = mediaId.get(media) as? String ?: return null
                val path = mediaPath.get(media) as? String ?: return null
                return PlaybackTrack(id, path).takeIf { id.isNotBlank() && path.isNotBlank() }
            }
            val a = trackAt(selection.current) ?: return@runCatching null
            val b = trackAt(selection.next) ?: return@runCatching null
            UpcomingTransition(a, b).takeIf { a.id != b.id }
        } }.getOrNull()
    }

    fun queueView(state: Any?, library: List<MediaItem>): QueueView {
        val targets = queue.getOrNull() ?: return QueueView(nextPairNote = "当前宿主的播放队列不可用")
        if (state == null) return QueueView(nextPairNote = "播放队列为空")
        return runCatching { with(targets) {
            val raw = normalQueue.get(state) as List<*>
            val order = raw.map { queueItemId.getInt(it) }
            val selection = selection(state, raw)

            val byId = library.associateBy { it.id }
            val byPath = library.associateBy { normPath(it.path) }
            val byTitleArtist = library.groupBy { it.title.trim().lowercase() to it.artist.trim().lowercase() }
            fun resolve(media: Any, id: String?, path: String?): MediaItem? {
                val title = mediaTitle.get(media) as? String
                val artist = mediaArtist.get(media) as? String
                return (id?.let { byId[it] })
                    ?: (path?.let { byPath[normPath(it)] })
                    ?: if (!title.isNullOrBlank()) {
                        byTitleArtist[title.trim().lowercase() to (artist ?: "").trim().lowercase()]?.singleOrNull()
                    } else null
            }

            val entries = raw.mapIndexedNotNull { idx, qItem ->
                val media = qItem?.let(queueMedia::get) ?: return@mapIndexedNotNull null
                val id = mediaId.get(media) as? String
                val path = mediaPath.get(media) as? String
                resolve(media, id, path)?.let { QueueTrackEntry(it, idx, order[idx], id, path) }
            }
            val isRandom = selection.mode == "Random"
            val currentIndex = if (isRandom) {
                selection.items.getOrNull(selection.current)?.let { order.indexOf(queueItemId.getInt(it)) } ?: -1
            } else selection.current
            val byQueueId = entries.associateBy { it.queueId }
            fun itemAt(index: Int) = selection.items.getOrNull(index)
                ?.let { byQueueId[queueItemId.getInt(it)]?.item }
            val a = itemAt(selection.current)
            val b = itemAt(selection.next)
            val pair = if (a != null && b != null && a.id != b.id) a to b else null
            val note = when {
                raw.isEmpty() -> "播放队列为空"
                selection.mode == "RepeatOne" -> "正在单曲循环"
                selection.next < 0 -> if (isRandom) "随机队列暂无可确定的下一首" else "当前没有下一首"
                a == null || b == null -> "当前歌曲或下一首未匹配到本地文件"
                pair == null -> "当前歌曲和下一首是同一首"
                else -> "当前转场 · ${a.title} → ${b.title}"
            }
            QueueView(entries, order, currentIndex, pair, note)
        } }.getOrElse { e ->
            QueueView(nextPairNote = "队列读取失败 · ${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}")
        }
    }

    fun playQueueTrack(entry: QueueTrackEntry) {
        val targets = queue.getOrThrow()
        val index = targets.states().value?.let { targets.playbackIndex(it, entry) }
            ?: error("该歌曲已不在当前队列，请刷新后重试")
        trackPlayback.getOrThrow().playQueueIndex(index)
    }

    fun playPlaylistTrack(tracks: List<MediaItem>, index: Int) =
        trackPlayback.getOrThrow().playPlaylist(tracks, index)

    suspend fun moveQueueItem(order: List<Int>, from: QueueTrackEntry, to: QueueTrackEntry): Boolean {
        val targets = queue.getOrNull() ?: return false
        val states = targets.states()
        val current = states.value ?: return false
        if (targets.order(current) != order || order.getOrNull(from.rawIndex) != from.queueId ||
            order.getOrNull(to.rawIndex) != to.queueId) return false
        val expected = order.toMutableList().apply { add(to.rawIndex, removeAt(from.rawIndex)) }
        targets.move(from.rawIndex, to.rawIndex)
        return withTimeoutOrNull(2000L) {
            val updated = states.first { it != null && targets.order(it) != order }
            updated != null && targets.order(updated) == expected
        } == true
    }

    /** Persist custom playlist track order back to SPW's native PlaylistRepository and switch sort to Custom ascending. */
    suspend fun reorderPlaylist(playlistId: String, orderedSyncTrackIds: List<String>): Result<Unit> {
        return try {
            val targets = playlist.getOrThrow()
            val repo = targets.repository()
            val updates = orderedSyncTrackIds.mapIndexed { idx, syncId ->
                targets.orderUpdate.newInstance(playlistId, syncId, idx)
            }
            suspendCoroutineUninterceptedOrReturn<Any?> { cont ->
                targets.saveOrders.invoke(repo, updates, cont)
            }
            suspendCoroutineUninterceptedOrReturn<Any?> { cont ->
                targets.setSort.invoke(repo, playlistId, targets.customSort, cont)
            }
            suspendCoroutineUninterceptedOrReturn<Any?> { cont ->
                targets.setDescending.invoke(repo, playlistId, false, cont)
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e.cause ?: e)
        }
    }

    suspend fun createPlaylist(sourceId: String, title: String, orderedSyncTrackIds: List<String>): String =
        playlistCreation.getOrThrow().create(sourceId, title, orderedSyncTrackIds)

    suspend fun loadPlaylists(): List<HostPlaylist> = playlistReader.getOrThrow().read()

    /** Force SPW's Pisces engine to clear its cached preparedAutomix and re-plan if AutoMix is enabled. */
    @JvmOverloads
    fun rescheduleAutomix(affectedKey: String? = null): Boolean =
        runCatching { reschedule.getOrNull()?.refresh(affectedKey) == true }.getOrDefault(false)

    fun currentPositionMs(): Pair<String, Long>? =
        runCatching { reschedule.getOrNull()?.currentPositionMs() }.getOrNull()

    companion object {
        // Leave enough margin for Pisces to decode and attach the incoming FileTempoStream before cueA.
        const val MIN_LEAD_MS = 650L
    }

    @JvmOverloads
    fun apply(call: MethodHookParam, saved: Map<String, Plan>, automatic: PreparedAutoMix? = null): String? =
        mix.getOrThrow().applyPlan(call, saved, automatic)

    private fun HostMixTargets.applyPlan(call: MethodHookParam, saved: Map<String, Plan>, automatic: PreparedAutoMix?): String? {
        val resultObj = call.result ?: return null
        val base = arrangedPlanField.get(resultObj)
        val a = profile.get(call.args[0])
        val b = profile.get(call.args[1])
        val idA = trackId.get(a) as String
        val idB = trackId.get(b) as String
        val key = pairKey(idA, idB)
        val savedPlan = saved[key]
        if (savedPlan?.useHost == true) return "沿用 SPW · 当前曲对"
        val modeName = (mode.get(base) as Enum<*>).name
        val nativeActive = active.invoke(base) == true
        val nativeChangesSpeed = nativeActive &&
            (modeName == "BEAT_MATCHED" || speedA.getDouble(base) != 1.0 || speedB.getDouble(base) != 1.0)
        val nativeStart = cueA.getLong(base)
        val nativeEntry = cueB.getLong(base)
        val nativeLength = duration.getLong(base)
        val lenA = length.getLong(a)
        val lenB = length.getLong(b)
        val livePos = currentPositionMs()?.takeIf { it.first == idA }?.second
        val posA = max(call.args[3] as Long, livePos ?: 0L)
        val leadMargin = if (savedPlan != null) 450L else MIN_LEAD_MS
        val recommended = automatic?.plan?.takeIf {
            savedPlan == null && automatic.key == key &&
                it.window(lenA, lenB)?.let { window -> window.startMs > posA + leadMargin } == true
        }
        val timingA = tempo.get(a)
        val timingB = tempo.get(b)
        val bpmA = timingA?.let { bpm.getDouble(it) }?.takeIf { it in 30.0..300.0 }
        val bpmB = timingB?.let { bpm.getDouble(it) }?.takeIf { it in 30.0..300.0 }
        val shortLimit = bpmA?.let { (480000.0 / it).roundToLong() } ?: 3000L
        val spectralOK = spectral.invoke(call.args[4], "spectral_automation") == true
        val keepGapless = preservesGapless(call.args.getOrNull(5))

        fun install(
            transitionMode: String, start: Long, entry: Long, lengthMs: Long, gains: List<Any>,
            curves: List<Any> = emptyList(), scene: String = "TRANSPARENT_BLEND",
            strategy: String = "transparent_blend"
        ) {
            val plan = transition.newInstance(
                modes.getValue(transitionMode), confidence.get(base), start, entry,
                lengthMs, 1.0, 1.0, gainCurve.newInstance(gains), emptyList<Any>()
            )
            // Workshop advice leaves the returned object in place, so update its fields.
            arrangedPlanField.set(resultObj, plan)
            arrangedSceneField.set(resultObj, scenes.getValue(scene))
            arrangedCurvesField.set(resultObj, curves)
            arrangedEventsField.set(resultObj, emptyList<Any>())
            arrangedStrategyField.set(resultObj, strategy)
            call.setResult(resultObj)
        }

        val fallbackEndA = if (nativeActive) minOf(lenA, nativeStart + nativeLength) else lenA
        val shortStart = maxOf(0L, fallbackEndA - if (nativeActive) min(nativeLength, shortLimit) else shortLimit)
        fun fallback(message: String? = null): String? {
            if (!nativeChangesSpeed) return message
            val start = max(shortStart, posA + 450L)
            val entry = max(0L, nativeEntry)
            val lengthMs = minOf(shortLimit, nativeLength, lenA - start, lenB - entry)
            if (lengthMs < 200L) {
                install("GAPLESS", lenA, 0L, 0L, listOf(
                    gainPoint.newInstance(0.0, 1.0, 0.0), gainPoint.newInstance(1.0, 0.0, 1.0)
                ))
            } else {
                val points = (0..48).map {
                    val t = it / 48.0
                    gainPoint.newInstance(t, cos(t * PI / 2.0), sin(t * PI / 2.0))
                }
                install("ENERGY_CROSSFADE", start, entry, lengthMs, points)
            }
            return message ?: "已接管 · 原速交接"
        }

        val p = savedPlan ?: recommended ?: run {
            if (!nativeActive && keepGapless) return null
            val ba = bpmA ?: return fallback()
            val bb = bpmB ?: return fallback()
            val canAlignShortMix = Plan.canAlign(ba, bb, 2)

            fun alignedPlan(targetA: Long, targetB: Long, lengths: List<Int>, endAtTrackEnd: Boolean = false): Plan? {
                if (!canAlignShortMix) return null
                for (strong in listOf(true, false)) {
                    val beatsA = (if (strong) downbeats.invoke(timingA) else beatTimes.get(timingA)) as List<*>
                    val beatsB = (if (strong) downbeats.invoke(timingB) else beatTimes.get(timingB)) as List<*>
                    for (bars in lengths) {
                        if (!Plan.canAlign(ba, bb, bars)) continue
                        val overlap = (bars * 240000.0 / ba).roundToLong()
                        val startA = if (endAtTrackEnd) lenA - overlap else targetA
                        val earliestA = max(posA + leadMargin + 1L, if (endAtTrackEnd) startA else 0L)
                        val cues = alignedCues(beatsA, beatsB, startA, targetB, earliestA,
                            lenA, lenB, overlap, ba, bb) ?: continue
                        return Plan(cues.first / 1000.0, cues.second / 1000.0,
                            ba, bb, bars, "smooth", spectralOK, .5, snap = true)
                    }
                }
                return null
            }

            if (!nativeActive) {
                // Host returned GAPLESS on an ordinary pair before background analysis finished: build a safe original-speed transition.
                val anchorEntryB = entranceStartMs(b)?.takeIf { it in 0 until lenB } ?: 0L
                alignedPlan(shortStart, anchorEntryB, listOf(4, 2), endAtTrackEnd = true)?.let { return@run it }
                val available = minOf(shortLimit, lenB - anchorEntryB)
                val endFadeStart = lenA - available
                if (available >= Plan.MIN_OVERLAP_MS && endFadeStart > posA + leadMargin) {
                    return@run Plan(endFadeStart / 1000.0, anchorEntryB / 1000.0, ba, bb, 2, "smooth", false, .5)
                }
                return null
            }
            val beatTransition = modeName == "BEAT_MATCHED" || modeName == "BEAT_ALIGNED"
            val longEnergyFade = modeName == "ENERGY_CROSSFADE" && !Plan.canAlign(ba, bb, 4) && nativeLength > shortLimit
            if (!beatTransition && !longEnergyFade) return fallback()

            if (beatTransition) {
                alignedPlan(nativeStart, nativeEntry, listOf(8, 4, 2))?.let { return@run it }
            }
            // Keep an existing short host fade when both tracks are already at original speed.
            if (nativeLength <= shortLimit) return fallback()
            Plan(shortStart / 1000.0, max(0L, nativeEntry) / 1000.0, ba, bb, 2, "smooth", false, .5)
        }
        if (!p.valid()) {
            return fallback("曲对参数无效，使用原速回退")
        }
        fun supports(code: String) = spectral.invoke(call.args[4], code) == true
        val applyEq = p.eq != "none" && spectralOK
        val applyFilter = p.filter != "none" && supports("filter_automation")
        val applyEcho = p.echo && supports("beat_synchronized_delay")
        val window = p.window(lenA, lenB)
            ?: return fallback(if (savedPlan != null) "过渡时长超出曲尾，使用原速回退" else null)
        val effStart = window.startMs
        val effEntry = window.entryMs
        val effDur = window.durationMs
        if (effStart <= posA + leadMargin) {
            return fallback(if (savedPlan != null) "已过切点 (${fmtCue(effStart)})，回拖进度条即可生效" else null)
        }
        val curveSamples = window.gains(p)
        val points = curveSamples.map { (u, ga, gb) ->
            gainPoint.newInstance(u, ga, gb)
        }

        val curves = mutableListOf<Any>()
        fun add(i: Int, code: String, knots: List<Pair<Double, Double>>) {
            curves += envelope.newInstance(targets[i], code, knots.map { paramPoint.newInstance(it.first, it.second) })
        }
        if (applyEq) {
            for (i in targets.indices) {
                add(i, "low_shelf_gain_db", window.eqKnots(p, i))
            }
        }
        if (applyFilter) {
            for (i in targets.indices) {
                val knots = window.filterKnots(p, i)
                if (knots.isNotEmpty()) add(i, "low_pass_cutoff_hz", knots)
            }
        }
        if (applyEcho) {
            val delay = (30000.0 / p.bpmA).coerceIn(40.0, 1500.0)
            add(0, "echo_delay_ms", listOf(0.0 to delay, 1.0 to delay))
            add(0, "echo_feedback", listOf(0.0 to Plan.HOST_ECHO_FEEDBACK, 1.0 to Plan.HOST_ECHO_FEEDBACK))
            add(0, "echo_wet", window.echoWetKnots())
        }
        val name = if (p.snap && Plan.canAlign(p.bpmA, p.bpmB, p.bars)) "BEAT_ALIGNED" else "ENERGY_CROSSFADE"
        val scene = when {
            applyEcho && !applyEq -> "ECHO_RELEASE"
            applyEq -> "SPECTRAL_HANDOFF"
            else -> "TRANSPARENT_BLEND"
        }
        val strategyName = when {
            applyEq -> "spectral_handoff"
            applyEcho -> "echo_release"
            else -> "transparent_blend"
        }
        install(name, effStart, effEntry, effDur, points, curves, scene, strategyName)

        return if (recommended != null) {
            "已接管 · 自动 · ${p.bars} 小节 (${fmtCue(effStart)}→${fmtCue(effEntry)})"
        } else if (savedPlan == null) {
            "已接管 · 原速自动过渡 · ${p.bars} 小节"
        } else {
            val presetLabel = Plan.presets.firstOrNull { it.matches(p) }?.label
                ?: Plan.volumes[p.volume]
                ?: "自定义"
            "已接管 · $presetLabel · ${p.bars} 小节 (${fmtCue(effStart)}→${fmtCue(effEntry)})"
        }
    }

    private fun alignedCues(
        beatsA: List<*>, beatsB: List<*>, targetA: Long, targetB: Long, earliestA: Long,
        lengthA: Long, lengthB: Long, overlap: Long, bpmA: Double, bpmB: Double
    ): Pair<Long, Long>? {
        val gap = abs(bpmB - bpmA)
        val driftLimit = if (gap == 0.0) overlap else (15000.0 / gap).roundToLong()
        val minOffset = max(0L, overlap - driftLimit)
        val maxOffset = min(overlap, driftLimit)
        if (minOffset > maxOffset) return null
        val half = ((overlap + 1) / 2).coerceIn(minOffset, maxOffset)
        val offsets = mutableSetOf(half, minOffset, maxOffset)
        val toleranceA = (120000.0 / bpmA).roundToLong()
        val toleranceB = (120000.0 / bpmB).roundToLong()
        val limitsA = max(earliestA, targetA - toleranceA)..min(lengthA - overlap, targetA + toleranceA)
        val limitsB = max(-250L, targetB - toleranceB)..min(lengthB - overlap, targetB + toleranceB)
        if (limitsA.isEmpty() || limitsB.isEmpty()) return null

        fun collectAnchors(beats: List<*>, target: Long, limits: LongRange): List<Long> {
            return beats.filterIsInstance<Long>().filter { beat ->
                if (beat !in limits.first + minOffset..limits.last + maxOffset) return@filter false
                for (cue in listOf(target, limits.first, limits.last)) {
                    if (beat - cue in minOffset..maxOffset) offsets += beat - cue
                }
                true
            }
        }
        val nearA = collectAnchors(beatsA, targetA, limitsA)
        val nearB = collectAnchors(beatsB, targetB, limitsB)

        fun nearestCue(beats: List<Long>, offset: Long, target: Long, limits: LongRange): Long? =
            beats.asSequence().map { it - offset }.filter { it in limits }
                .minByOrNull { abs(it - target) }

        var best: Pair<Long, Long>? = null
        var bestDistance = Long.MAX_VALUE
        for (offset in offsets.sortedBy { abs(it - half) }) {
            val a = nearestCue(nearA, offset, targetA, limitsA) ?: continue
            val b = nearestCue(nearB, offset, targetB, limitsB) ?: continue
            val distance = abs(a - targetA) + abs(b - targetB)
            if (distance < bestDistance) {
                best = a to b
                bestDistance = distance
            }
        }
        return best
    }
}
