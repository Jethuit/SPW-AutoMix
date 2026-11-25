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
    val queueId: Int
)

internal data class QueueView(
    val entries: List<QueueTrackEntry> = emptyList(),
    val order: List<Int> = emptyList(),
    val currentIndex: Int = -1,
    val nextPair: Pair<MediaItem, MediaItem>? = null,
    val nextPairNote: String = "当前没有可编辑的下一首"
)

/** Semantic mapping for the pinned Steam 1.19.3 artifact. No native handle is retained. */
internal class Host1193(loader: ClassLoader) {
    fun type(n: Int) = Class.forName("androidx.compose.ui." + n.toChar(), false, loaderRef)
    private val loaderRef = loader
    private fun get(n: Int, method: Int) = type(n).getMethod(method.toChar().toString())
    private fun ctor(n: Int) = type(n).constructors.single()
    private fun enumValue(n: Int, name: String) = type(n).enumConstants.single { (it as Enum<*>).name == name }

    private val profile = get(0x507, 0x37f)
    private val trackId = get(0x910, 0x37f)
    private val length = get(0x910, 0x528)
    private val tempo = get(0x910, 0x52b)
    private val bpm = get(0x90d, 0x37f)
    private val original = get(0x574, 0x37f)
    private val mode = get(0x44c, 0x37f)
    private val confidence = get(0x44c, 0x528)
    private val cueA = get(0x44c, 0x529)
    private val cueB = get(0x44c, 0x52a)
    private val active = get(0x44c, 0x560)
    private val spectral = type(0x4ed).getMethod("Ϳ", String::class.java)
    private val stretch = get(0x163, 0x52a)
    private val gainPoint = ctor(0x6c8)
    private val gainCurve = ctor(0x99f)
    private val paramPoint = ctor(0x533)
    private val envelope = ctor(0x779)
    private val transition = ctor(0x44c)
    private val arrangedPlanField = type(0x574).getDeclaredField("\u037f").apply { isAccessible = true }
    private val arrangedSceneField = type(0x574).getDeclaredField("\u0528").apply { isAccessible = true }
    private val arrangedStrategyField = type(0x574).getDeclaredField("\u0529").apply { isAccessible = true }
    private val arrangedCurvesField = type(0x574).getDeclaredField("\u052a").apply { isAccessible = true }
    private val arrangedEventsField = type(0x574).getDeclaredField("\u052b").apply { isAccessible = true }
    val planner = type(0x13f).declaredMethods.single { it.parameterCount == 6 && it.returnType == type(0x574) }
    private val targets = listOf("OUTGOING", "INCOMING").map { enumValue(0x987, it) }

    private val controllerClass = Class.forName("com.xuncorp.voxzen.service.PlaybackController", false, loaderRef)
    private val controller by lazy { controllerClass.getField("INSTANCE").get(null) }
    private val queueClass = Class.forName("com.xuncorp.spc.core.queue.PlaybackQueueState", false, loaderRef)
    private val queueItemClass = Class.forName("com.xuncorp.spc.core.queue.\u037f", false, loaderRef)
    private val queueMode = queueClass.getMethod("\u037f")
    private val normalQueue = queueClass.getMethod("\u0528")
    private val normalIndex = queueClass.getMethod("\u0529")
    private val randomQueue = queueClass.getMethod("\u052a")
    private val randomIndex = queueClass.getMethod("\u052b")
    private val queueItemId = queueItemClass.getMethod("\u037f")
    private val queueMedia = queueItemClass.getMethod("\u0528")

    private fun normPath(p: String): String = p.trim().replace('/', '\\').lowercase()

    private fun fmtCue(ms: Long): String {
        val sec = max(0L, ms / 1000L)
        return "%d:%02d".format(java.util.Locale.ROOT, sec / 60L, sec % 60L)
    }

    fun queueStates(): StateFlow<*> = controllerClass.getMethod("getPlaybackQueueState").invoke(controller) as StateFlow<*>

    private fun queueOrder(state: Any): List<Int> =
        (normalQueue.invoke(state) as List<*>).map { queueItemId.invoke(it) as Int }

    fun queueView(state: Any?, library: List<MediaItem>): QueueView {
        if (state == null) return QueueView(nextPairNote = "播放队列为空")
        return runCatching {
            val raw = normalQueue.invoke(state) as List<*>
            val order = raw.map { queueItemId.invoke(it) as Int }
            val normalCurrent = normalIndex.invoke(state) as Int
            val modeName = (queueMode.invoke(state) as Enum<*>).name

            val byId = library.associateBy { it.id }
            val byPath = library.associateBy { normPath(it.path) }
            val byTitleArtist = library.groupBy { it.title.trim().lowercase() to it.artist.trim().lowercase() }
            fun resolve(qItem: Any): MediaItem? {
                val media = queueMedia.invoke(qItem) ?: return null
                val mCls = media.javaClass
                val id = mCls.getMethod("Ϳ").invoke(media) as? String
                val title = mCls.getMethod("Ԩ").invoke(media) as? String
                val artist = mCls.getMethod("ԩ").invoke(media) as? String
                val path = mCls.getMethod("Ԭ").invoke(media) as? String
                return (id?.let { byId[it] })
                    ?: (path?.let { byPath[normPath(it)] })
                    ?: if (!title.isNullOrBlank()) {
                        byTitleArtist[title.trim().lowercase() to (artist ?: "").trim().lowercase()]?.singleOrNull()
                    } else null
            }

            val entries = raw.mapIndexedNotNull { idx, qItem ->
                qItem?.let(::resolve)?.let { QueueTrackEntry(it, idx, order[idx]) }
            }
            val isRandom = modeName == "Random"
            val activeQueue = if (isRandom) randomQueue.invoke(state) as List<*> else raw
            val activeIndex = if (isRandom) randomIndex.invoke(state) as Int else normalCurrent
            val currentIndex = if (isRandom) {
                activeQueue.getOrNull(activeIndex)?.let { order.indexOf(queueItemId.invoke(it) as Int) } ?: -1
            } else normalCurrent
            val nextIndex = when {
                activeIndex !in activeQueue.indices || modeName == "RepeatOne" -> -1
                activeIndex + 1 < activeQueue.size -> activeIndex + 1
                modeName == "Circle" && activeQueue.size > 1 -> 0
                else -> -1
            }
            val byQueueId = entries.associateBy { it.queueId }
            fun itemAt(index: Int) = activeQueue.getOrNull(index)
                ?.let { byQueueId[queueItemId.invoke(it) as Int]?.item }
            val a = itemAt(activeIndex)
            val b = itemAt(nextIndex)
            val pair = if (a != null && b != null && a.id != b.id) a to b else null
            val note = when {
                raw.isEmpty() -> "播放队列为空"
                modeName == "RepeatOne" -> "正在单曲循环"
                nextIndex < 0 -> if (isRandom) "随机队列暂无可确定的下一首" else "当前没有下一首"
                a == null || b == null -> "当前歌曲或下一首未匹配到本地文件"
                pair == null -> "当前歌曲和下一首是同一首"
                else -> "当前转场 · ${a.title} → ${b.title}"
            }
            QueueView(entries, order, currentIndex, pair, note)
        }.getOrElse { e ->
            QueueView(nextPairNote = "队列读取失败 · ${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}")
        }
    }

    suspend fun moveQueueItem(order: List<Int>, from: QueueTrackEntry, to: QueueTrackEntry): Boolean {
        val states = queueStates()
        val current = states.value ?: return false
        if (queueOrder(current) != order || order.getOrNull(from.rawIndex) != from.queueId ||
            order.getOrNull(to.rawIndex) != to.queueId) return false
        val expected = order.toMutableList().apply { add(to.rawIndex, removeAt(from.rawIndex)) }
        controllerClass.getMethod("moveMediaItem", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(controller, from.rawIndex, to.rawIndex)
        return withTimeoutOrNull(2000L) {
            val updated = states.first { it != null && queueOrder(it) != order }
            updated != null && queueOrder(updated) == expected
        } == true
    }

    /** Persist custom playlist track order back to SPW's native PlaylistRepository and switch sort to Custom ascending. */
    suspend fun reorderPlaylist(playlistId: String, orderedSyncTrackIds: List<String>): Result<Unit> {
        return try {
            val repoCls = type(0x0b9a)
            val comp = repoCls.getField("\u037f").get(null)
            val repo = comp.javaClass.getMethod("\u037f").invoke(comp)

            val dtoCtor = type(0x0e81).getConstructor(
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType
            )
            val updates = orderedSyncTrackIds.mapIndexed { idx, syncId ->
                dtoCtor.newInstance(playlistId, syncId, idx)
            }
            val saveOrders = repoCls.getMethod("\u0528", List::class.java, Continuation::class.java)
            suspendCoroutineUninterceptedOrReturn<Any?> { cont ->
                saveOrders.invoke(repo, updates, cont)
            }

            val sortCls = type(0x01c1)
            val customSort = enumValue(0x01c1, "Custom")
            val setSort = repoCls.getMethod("\u037f", String::class.java, sortCls, Continuation::class.java)
            suspendCoroutineUninterceptedOrReturn<Any?> { cont ->
                setSort.invoke(repo, playlistId, customSort, cont)
            }

            val setDesc = repoCls.getMethod(
                "\u037f",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                Continuation::class.java
            )
            suspendCoroutineUninterceptedOrReturn<Any?> { cont ->
                setDesc.invoke(repo, playlistId, false, cont)
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e.cause ?: e)
        }
    }

    /** Force SPW's Pisces engine to clear its cached preparedAutomix and re-plan if AutoMix is enabled. */
    fun rescheduleAutomix(): Boolean = runCatching {
        val ctrlCls = Class.forName("com.xuncorp.voxzen.service.PlaybackController", false, loaderRef)
        val ctrl = ctrlCls.getField("INSTANCE").get(null)
        val svcField = ctrlCls.getDeclaredField("service").apply { isAccessible = true }
        val svc = svcField.get(ctrl) ?: return false
        val player = svc.javaClass.getMethod("getPlayer").invoke(svc) ?: return false
        val enabled = player.javaClass.getMethod("\u0560").invoke(player) == true
        if (!enabled) return false
        val toggle = ctrlCls.getMethod("changeAutoMixEnabled", Boolean::class.javaPrimitiveType)
        toggle.invoke(ctrl, false)
        toggle.invoke(ctrl, true)
        true
    }.getOrDefault(false)

    fun apply(call: MethodHookParam, saved: Map<String, Plan>): String? {
        val resultObj = call.result ?: return null
        val base = original.invoke(resultObj)
        val a = profile.invoke(call.args[0])
        val b = profile.invoke(call.args[1])
        val idA = trackId.invoke(a) as String
        val idB = trackId.invoke(b) as String
        val savedPlan = saved[pairKey(idA, idB)]
        if (savedPlan?.useHost == true) return "沿用 SPW · 当前曲对"
        val modeName = (mode.invoke(base) as Enum<*>).name
        val spectralOK = spectral.invoke(call.args[4], "spectral_automation") == true
        val p = savedPlan ?: run {
            if (active.invoke(base) != true) return null
            if (modeName !in setOf("BEAT_MATCHED", "BEAT_ALIGNED")) return null
            val ba = bpm.invoke(tempo.invoke(a) ?: return null) as Double
            val bb = bpm.invoke(tempo.invoke(b) ?: return null) as Double
            if (ba !in 30.0..300.0 || bb !in 30.0..300.0 || abs(ba / bb - 1) > .045) return null
            val start = (cueA.invoke(base) as Long) / 1000.0
            val entry = (cueB.invoke(base) as Long) / 1000.0
            val bars = listOf(8, 4, 2).firstOrNull {
                start + it * 240 / ba <= (length.invoke(a) as Long) / 1000.0 &&
                    entry + it * 240 / bb <= (length.invoke(b) as Long) / 1000.0
            } ?: return null
            Plan(start, entry, ba, bb, bars, "smooth", spectralOK, .5, modeName == "BEAT_MATCHED")
        }
        if (!p.valid()) {
            return if (savedPlan != null) "曲对参数无效，已保留宿主过渡" else null
        }
        fun supports(code: String) = spectral.invoke(call.args[4], code) == true
        val applyEq = p.eq != "none" && spectralOK
        val applyFilter = p.filter != "none" && supports("filter_automation")
        val applyEcho = p.echo && supports("beat_synchronized_delay")
        val effSpeed = if (p.speed != 1.0 && stretch.invoke(call.args[2]) == true) p.speed else 1.0

        val lenA = length.invoke(a) as Long
        val lenB = length.invoke(b) as Long
        val posA = call.args[3] as Long
        val window = p.window(lenA, lenB, effSpeed)
            ?: return if (savedPlan != null) "过渡时长超出曲尾，已保留宿主过渡" else null
        val effStart = window.startMs
        val effEntry = window.entryMs
        val effDur = window.durationMs
        if (effStart <= posA + 250L) {
            return if (savedPlan != null) "已过切点 (${fmtCue(effStart)})，回拖进度条即可生效" else null
        }
        val points = window.gains(p).mapIndexed { i, (ga, gb) ->
            gainPoint.newInstance(i / 48.0, ga, gb)
        }

        val curves = mutableListOf<Any>()
        fun add(i: Int, code: String, knots: List<Pair<Double, Double>>) {
            curves += envelope.newInstance(targets[i], code, knots.map { paramPoint.newInstance(it.first, it.second) })
        }
        if (applyEq) {
            val width = (if (p.eq == "fast-bass-cut") 0.125 else 0.25) / (4 * p.bars)
            val c = p.eqCenter
            for (i in targets.indices) {
                val knots = (0..24).map { step ->
                    val u = step / 24.0
                    val ox = window.phase(u)
                    var t = ((ox - c) / max(1e-4, width) + 0.5).coerceIn(0.0, 1.0)
                    t = t * t * (3.0 - 2.0 * t)
                    val db = -24.0 * if (i == 0) t else (1.0 - t)
                    u to db
                }
                add(i, "low_shelf_gain_db", knots)
            }
        }
        val pv = p.pivot.coerceIn(0.05, 0.95)
        if (applyFilter) {
            for (i in targets.indices) {
                if (p.filter == "both-low" || p.filter == (if (i == 0) "low-out" else "low-in")) {
                    add(i, "low_pass_cutoff_hz", (0..16).map {
                        val u = it / 16.0
                        val x = window.phase(u)
                        val open = if (i == 0) {
                            if (x <= pv) 1.0 else ((1.0 - x) / (1.0 - pv)).coerceIn(0.0, 1.0)
                        } else {
                            if (x >= pv) 1.0 else (x / pv).coerceIn(0.0, 1.0)
                        }
                        u to 160.0 * (18000.0 / 160.0).pow(open)
                    })
                }
            }
        }
        if (applyEcho) {
            val delay = 30000 / p.bpmA
            add(0, "echo_delay_ms", listOf(0.0 to delay, 1.0 to delay))
            add(0, "echo_feedback", listOf(0.0 to .42, 1.0 to .42))
            add(0, "echo_wet", (0..12).map { step ->
                val u = step / 12.0
                val ox = window.phase(u)
                val wet = ((ox - 0.65) / 0.25).coerceIn(0.0, 1.0) * 0.42
                u to wet
            })
        }
        val name = when {
            effSpeed != 1.0 -> "BEAT_MATCHED"
            abs(p.bpmA / p.bpmB - 1) < .005 -> "BEAT_ALIGNED"
            else -> "ENERGY_CROSSFADE"
        }
        val plan = transition.newInstance(
            enumValue(0x28e, name), confidence.invoke(base), effStart, effEntry,
            effDur, 1.0, effSpeed, gainCurve.newInstance(points), emptyList<Any>()
        )
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
        // Mutate SPW's returned MorvaniumArrangedTransitionPlan in-place because SPW's WorkshopMethodAdvice.exit pops MethodHookParam.getResult() without assigning @Advice.Return.
        arrangedPlanField.set(resultObj, plan)
        arrangedSceneField.set(resultObj, enumValue(0x6c6, scene))
        arrangedCurvesField.set(resultObj, curves)
        arrangedEventsField.set(resultObj, emptyList<Any>())
        arrangedStrategyField.set(resultObj, strategyName)
        call.setResult(resultObj)

        return if (savedPlan == null) {
            "已接管 · 自动过渡 · ${p.bars} 小节"
        } else {
            val presetLabel = Plan.presets.firstOrNull { it.matches(p) }?.label
                ?: Plan.volumes[p.volume]
                ?: "自定义"
            "已接管 · $presetLabel · ${p.bars} 小节 (${fmtCue(effStart)}→${fmtCue(effEntry)})"
        }
    }
}
