package automix

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import kotlinx.coroutines.flow.StateFlow

private const val MORVANIUM = "com.xuncorp.spc.morvanium.automix.Morvanium"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"
private const val CONTINUATION = "Lkotlin/coroutines/Continuation;"
private const val OBJECT = "Ljava/lang/Object;"

private fun HostSymbols.dataField(type: HostType, label: String, descriptor: String) =
    field(type.dataField(label, descriptor))

private fun HostSymbols.enums(type: HostType, vararg required: String): Map<String, Any> {
    val values = checkNotNull(resolve(type).enumConstants) { "宿主类型不是枚举 · ${type.owner}" }
        .associateBy { (it as Enum<*>).name }
    check(required.all(values::containsKey)) { "宿主枚举缺少必要成员 · ${type.owner}" }
    return values
}

internal class HostMixTargets(symbols: HostSymbols) {
    private val analyzedType = symbols.named(MORVANIUM + "AnalyzedTrack")
    private val profileType = symbols.named(MORVANIUM + "TrackProfile")
    private val tempoType = symbols.named(MORVANIUM + "TempoProfile")
    private val arrangedType = symbols.named(MORVANIUM + "ArrangedTransitionPlan")
    private val planType = symbols.named(MORVANIUM + "TransitionPlan")
    private val modeType = symbols.named(MORVANIUM + "TransitionMode")
    private val sceneType = symbols.named(MORVANIUM + "TransitionScene")
    private val envelopeType = symbols.named(MORVANIUM + "ParameterEnvelope")
    private val eventType = symbols.named(MORVANIUM + "TransitionEvent")
    private val confidenceType = symbols.named(MORVANIUM + "Confidence")
    private val gainCurveType = symbols.named(MORVANIUM + "GainCurve")
    private val targetType = symbols.named(MORVANIUM + "ProcessingTarget")
    private val processingType = symbols.named(MORVANIUM + "ProcessingCapabilities")
    private val capabilitiesType = symbols.named(MORVANIUM + "HostCapabilities")
    private val relationshipType = symbols.named(MORVANIUM + "TrackRelationship")

    private val plannerType = symbols.named(MORVANIUM + "ArrangedTransitionPlanner")
    val planner = symbols.method(plannerType.method("过渡规划") {
        it.isPublic && !it.isStatic && it.symbol.arguments == listOf(analyzedType.descriptor, analyzedType.descriptor,
            capabilitiesType.descriptor, "J", processingType.descriptor, relationshipType.descriptor) &&
            it.symbol.returns == arrangedType.descriptor
    }.symbol.also { target ->
        check(symbols.methods.any { method ->
            method.symbol.owner.startsWith("com/xuncorp/pisces/") && target in method.calls &&
                method.calls.any { it.name == "onPeekNext" }
        }) { "未发现 Pisces 的过渡规划调用点" }
    })

    val profile = symbols.dataField(analyzedType, "trackProfile", profileType.descriptor)
    val trackId = symbols.dataField(profileType, "trackId", STRING)
    val length = symbols.dataField(profileType, "durationMs", "J")
    private val entranceRegion = symbols.dataField(profileType, "entrance", symbols.named(MORVANIUM + "MixRegion").descriptor)
    private val regionRange = symbols.dataField(symbols.named(MORVANIUM + "MixRegion"), "range", symbols.named(MORVANIUM + "TimeRange").descriptor)
    private val rangeStart = symbols.dataField(symbols.named(MORVANIUM + "TimeRange"), "startMs", "J")
    val tempo = symbols.dataField(profileType, "tempo", tempoType.descriptor)
    val bpm = symbols.dataField(tempoType, "bpm", "D")
    private val beats = tempoType.dataField("beatTimesMs", LIST)
    private val offset = tempoType.dataField("downbeatOffset", "I")
    val downbeats = symbols.method(tempoType.method("小节重拍") {
        it.isPublic && !it.isStatic && it.symbol.descriptor == "()$LIST" && it.reads.containsAll(listOf(beats, offset))
    }.symbol)
    val beatTimes = symbols.field(beats)
    private val consecutiveAlbum = symbols.dataField(relationshipType, "consecutiveAlbumTracks", "Z")
    private val gaplessPreferred = symbols.dataField(relationshipType, "gaplessPreferred", "Z")
    private val liveRecording = symbols.dataField(relationshipType, "liveOrContinuousRecording", "Z")

    fun preservesGapless(relationship: Any?): Boolean = relationship != null && (
        consecutiveAlbum.getBoolean(relationship) ||
            gaplessPreferred.getBoolean(relationship) ||
            liveRecording.getBoolean(relationship)
        )

    fun entranceStartMs(trackProfile: Any): Long? =
        runCatching { entranceRegion.get(trackProfile)?.let(regionRange::get)?.let(rangeStart::getLong) }.getOrNull()
    val mode = symbols.dataField(planType, "mode", modeType.descriptor)
    val confidence = symbols.dataField(planType, "confidence", confidenceType.descriptor)
    val cueA = symbols.dataField(planType, "outgoingCueMs", "J")
    val cueB = symbols.dataField(planType, "incomingCueMs", "J")
    val duration = symbols.dataField(planType, "durationMs", "J")
    val speedA = symbols.dataField(planType, "outgoingPlaybackRate", "D")
    val speedB = symbols.dataField(planType, "incomingPlaybackRate", "D")
    val active = symbols.method(planType.method("过渡启用状态") {
        it.isPublic && !it.isStatic && it.symbol.descriptor == "()Z"
    }.symbol)
    val spectral = symbols.method(processingType.method("处理能力判定") {
        it.isPublic && !it.isStatic && it.symbol.descriptor == "(${STRING})Z" &&
            it.calls.any { call -> call.owner == "java/util/Set" && call.name == "contains" }
    }.symbol)

    val transition = symbols.constructor(planType, modeType.descriptor, confidenceType.descriptor,
        "J", "J", "J", "D", "D", gainCurveType.descriptor, LIST)
    val gainCurve = symbols.constructor(gainCurveType, LIST)
    val gainPoint = symbols.constructor(symbols.named(MORVANIUM + "GainPoint"), "D", "D", "D")
    val paramPoint = symbols.constructor(symbols.named(MORVANIUM + "ParameterPoint"), "D", "D")
    val envelope = symbols.constructor(envelopeType, targetType.descriptor, STRING, LIST)

    private fun arrangedField(symbols: HostSymbols, role: String, descriptor: String, generic: HostType? = null) =
        symbols.field(arrangedType.field(role) { field, signature ->
            field.descriptor == descriptor && (generic == null || signature == "Ljava/util/List<${generic.descriptor}>;")
        })

    val arrangedPlanField = arrangedField(symbols, "内置过渡计划", planType.descriptor)
    val arrangedSceneField = arrangedField(symbols, "过渡场景", sceneType.descriptor)
    val arrangedStrategyField = arrangedField(symbols, "过渡标识", STRING)
    val arrangedCurvesField = arrangedField(symbols, "过渡包络", LIST, envelopeType)
    val arrangedEventsField = arrangedField(symbols, "过渡事件", LIST, eventType)
    val targets = symbols.enums(targetType, "OUTGOING", "INCOMING").let {
        listOf(it.getValue("OUTGOING"), it.getValue("INCOMING"))
    }
    val modes = symbols.enums(modeType, "GAPLESS", "ENERGY_CROSSFADE", "BEAT_ALIGNED", "BEAT_MATCHED")
    val scenes = symbols.enums(sceneType, "TRANSPARENT_BLEND", "SPECTRAL_HANDOFF", "ECHO_RELEASE")
}

internal class HostQueueTargets(symbols: HostSymbols) {
    private val controllerClass = symbols.resolve(symbols.named(PLAYBACK_CONTROLLER))
    private val instance = controllerClass.getField("INSTANCE")
    private val observe = controllerClass.getMethod("getPlaybackQueueState").also {
        check(StateFlow::class.java.isAssignableFrom(it.returnType)) { "播放队列不再是 StateFlow" }
    }
    private val move = controllerClass.getMethod("moveMediaItem", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    private val state = symbols.named("com.xuncorp.spc.core.queue.PlaybackQueueState")
    private val item = symbols.named("com.xuncorp.spc.core.queue.PlaybackQueueItem")
    private val media = symbols.named("com.xuncorp.pisces.PiscesMediaItem")
    private val mode = symbols.named("com.xuncorp.spc.core.queue.PlaybackQueue\$Mode")

    val queueMode = symbols.dataField(state, "mode", mode.descriptor)
    val normalQueue = symbols.dataField(state, "normalQueue", LIST)
    val normalIndex = symbols.dataField(state, "normalIndex", "I")
    val randomQueue = symbols.dataField(state, "randomQueue", LIST)
    val randomIndex = symbols.dataField(state, "randomIndex", "I")
    val queueItemId = symbols.dataField(item, "id", "I")
    val queueMedia = symbols.dataField(item, "data", OBJECT)
    private val mediaFields = media.constructorProperties("id", "title", "artist", "album", "albumArtist", "path")
    private fun mediaField(symbols: HostSymbols, name: String) = symbols.field(mediaFields.getValue(name)).also {
        check(it.type == String::class.java) { "播放项属性已变化 · $name" }
    }
    val mediaId = mediaField(symbols, "id")
    val mediaTitle = mediaField(symbols, "title")
    val mediaArtist = mediaField(symbols, "artist")
    val mediaPath = mediaField(symbols, "path")

    fun states(): StateFlow<*> = observe.invoke(instance.get(null)) as StateFlow<*>
    fun move(from: Int, to: Int) { move.invoke(instance.get(null), from, to) }
    fun order(state: Any): List<Int> = (normalQueue.get(state) as List<*>).map(queueItemId::getInt)

    fun playbackIndex(state: Any, entry: QueueTrackEntry): Int? {
        val items = normalQueue.get(state) as List<*>
        val index = items.indexOfFirst { it != null && queueItemId.getInt(it) == entry.queueId }
        val media = items.getOrNull(index)?.let(queueMedia::get) ?: return null
        return index.takeIf { mediaId.get(media) == entry.sourceId && mediaPath.get(media) == entry.sourcePath }
    }
}

internal class HostTrackPlayback(symbols: HostSymbols) {
    private val controller = symbols.resolve(symbols.named(PLAYBACK_CONTROLLER))
    private val instance = controller.getField("INSTANCE")
    private val playAt = controller.getMethod("playMusicAt", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
    private val setQueue = controller.getMethod("setPlaybackQueue", List::class.java, Int::class.javaPrimitiveType)
    private val prepare = controller.getMethod("prepare")
    private val play = controller.getMethod("play")
    private val mediaType = symbols.named("com.xuncorp.pisces.PiscesMediaItem")
    private val media = symbols.constructor(mediaType, STRING, STRING, STRING, STRING, STRING, STRING)

    init {
        mediaType.constructorProperties("id", "title", "artist", "album", "albumArtist", "path")
    }

    fun playQueueIndex(index: Int) {
        // The sidebar shows normal queue order even when playback is shuffled.
        playAt.invoke(instance.get(null), index, false)
    }

    fun playPlaylist(tracks: List<MediaItem>, index: Int) {
        require(index in tracks.indices)
        val items = tracks.map { media.newInstance(it.id, it.title, it.artist, it.album, it.albumArtist, it.path) }
        val player = instance.get(null)
        // Match the native playlist action: queue replacement, preparation, then playback.
        setQueue.invoke(player, items, index)
        prepare.invoke(player)
        play.invoke(player)
    }
}

internal class HostPlaylistTargets(symbols: HostSymbols) {
    private val repo = symbols.named("com.xuncorp.voxzen.data.repo.PlaylistRepo")
    private val sort = symbols.named("com.xuncorp.voxzen.ui.screen.track.TrackSort")
    private val update = symbols.named("com.xuncorp.voxzen.data.update.PlaylistTrackOrderUpdate")
    val orderUpdate = symbols.constructor(update, STRING, STRING, "I")
    val customSort = symbols.enums(sort, "Custom").getValue("Custom")
    // Some hosts narrow suspend methods to a generated continuation that external callers cannot supply.
    private val save = repo.method("可调用的歌单排序接口") {
        it.isPublic && it.symbol.descriptor == "($LIST$CONTINUATION)$OBJECT" &&
            it.signature?.startsWith("(Ljava/util/List<${update.descriptor}>;") == true
    }
    val saveOrders = symbols.method(save.symbol)
    val setSort = symbols.method(repo.method("设置歌单排序模式") {
        it.isPublic && it.isStatic == save.isStatic && it.symbol.descriptor == "($STRING${sort.descriptor}$CONTINUATION)$OBJECT"
    }.symbol)
    val setDescending = symbols.method(repo.method("设置歌单排序方向") {
        it.isPublic && it.isStatic == save.isStatic && it.symbol.descriptor == "(${STRING}Z$CONTINUATION)$OBJECT"
    }.symbol)

    private val repositoryGetter = if (save.isStatic) null else {
        val companion = symbols.named("com.xuncorp.voxzen.data.repo.PlaylistRepo\$Companion")
        val getter = companion.method("歌单仓库实例") {
            it.isPublic && it.symbol.descriptor == "()${repo.descriptor}"
        }
        val field = if (getter.isStatic) null else symbols.field(repo.field("歌单仓库 Companion") { key, _ ->
            key.descriptor == companion.descriptor
        })
        symbols.method(getter.symbol) to field
    }

    fun repository(): Any? = repositoryGetter?.let { (getter, field) -> getter.invoke(field?.get(null)) }
}

internal class HostRescheduleTargets(symbols: HostSymbols) {
    private val controller = symbols.named(PLAYBACK_CONTROLLER)
    private val player = symbols.named("com.xuncorp.pisces.PiscesPlayer")
    private val bassPlayer = symbols.named("com.xuncorp.pisces.BassPiscesPlayer")
    private val bassPlayerClass = symbols.resolve(bassPlayer)
    private val activeType = symbols.named("com.xuncorp.pisces.automix.ActiveAutomix")
    private val preparedType = symbols.named("com.xuncorp.pisces.automix.PreparedAutomix")
    private val mediaType = symbols.named("com.xuncorp.pisces.PiscesMediaItem")
    private val toggle = controller.method("切换智能过渡") {
        it.symbol.name == "changeAutoMixEnabled" && it.symbol.descriptor == "(Z)V"
    }
    private val toggleMethod = symbols.method(toggle.symbol)
    private val controllerInstance = symbols.resolve(controller).getField("INSTANCE")
    private val service = symbols.field(toggle.reads.filter { it.owner == controller.owner }.uniqueHost("播放服务"))
    private val getPlayer = symbols.method(toggle.calls.filter {
        it.name == "getPlayer" && it.descriptor == "()${player.descriptor}"
    }.uniqueHost("播放器引用"))
    private val enabled = symbols.method(player.method("智能过渡开关状态") { getter ->
        getter.isPublic && !getter.isStatic && getter.symbol.descriptor == "()Z" && getter.reads.singleOrNull()?.let { field ->
            field.owner == player.owner && field.descriptor == "Z" && player.methods.any {
                it.symbol.descriptor == "(Z)V" && it.writes == listOf(field) && it.calls.isEmpty()
            }
        } == true && "automixEnabled" in player.original
    }.symbol)
    private val currentMedia = symbols.method(player.method("当前播放项") {
        it.isPublic && !it.isStatic && it.symbol.descriptor == "()${mediaType.descriptor}" && it.calls.isEmpty() &&
            it.reads.singleOrNull()?.descriptor == mediaType.descriptor
    }.symbol)
    private val mediaId = symbols.field(mediaType.constructorProperties("id", "title", "artist", "album", "albumArtist", "path").getValue("id"))
    private val activeAutomix = symbols.field(bassPlayer.field("活动过渡状态") { field, _ -> field.descriptor == activeType.descriptor })
    private val preparedAutomix = symbols.field(bassPlayer.field("预备过渡状态") { field, _ -> field.descriptor == preparedType.descriptor })
    private val preparedOutgoing = symbols.field(preparedType.fields.keys.filter { it.descriptor == mediaType.descriptor }
        .also { check(it.size == 2) { "预备过渡曲目字段已变化" } }[0])
    private val preparedIncoming = symbols.field(preparedType.fields.keys.filter { it.descriptor == mediaType.descriptor }[1])
    private val preparedFlags = preparedType.fields.keys.filter {
        it.descriptor == "Ljava/util/concurrent/atomic/AtomicBoolean;"
    }.also { check(it.size >= 2) { "预备过渡提交状态字段已变化" } }.take(2).map(symbols::field)
    private val positionMethod = runCatching {
        val scheduleMethod = symbols.methods.filter { method ->
            method.symbol.owner.startsWith("com/xuncorp/pisces/") &&
                method.calls.any { it.name == "onPeekNext" }
        }.uniqueHost("过渡调度协程")
        val posCall = scheduleMethod.calls.filter {
            it.owner == player.owner && it.descriptor == "()J"
        }.distinct().uniqueHost("实时播放位置")
        symbols.method(posCall)
    }.getOrNull()

    fun currentPositionMs(): Pair<String, Long>? {
        val host = controllerInstance.get(null)
        val playbackService = service.get(host) ?: return null
        val actualPlayer = getPlayer.invoke(playbackService) ?: return null
        val media = currentMedia.invoke(actualPlayer) ?: return null
        val id = mediaId.get(media) as? String ?: return null
        val pos = (positionMethod?.invoke(actualPlayer) as? Long)?.takeIf { it >= 0L } ?: return null
        return id to pos
    }

    fun refresh(affectedKey: String? = null): Boolean {
        val host = controllerInstance.get(null)
        val playbackService = service.get(host) ?: return false
        val actualPlayer = getPlayer.invoke(playbackService) ?: return false
        if (enabled.invoke(actualPlayer) != true) return false
        if (bassPlayerClass.isInstance(actualPlayer)) {
            // Never tear down an already-audible overlap or a transition currently committing at its mixer sync.
            if (activeAutomix.get(actualPlayer) != null) return false
            val prepared = preparedAutomix.get(actualPlayer)
            if (prepared != null) {
                val started = preparedFlags.any { (it.get(prepared) as? java.util.concurrent.atomic.AtomicBoolean)?.get() == true }
                if (started) return false
                if (affectedKey != null) {
                    val outId = (preparedOutgoing.get(prepared)?.let(mediaId::get) as? String).orEmpty()
                    val inId = (preparedIncoming.get(prepared)?.let(mediaId::get) as? String).orEmpty()
                    if (outId.isNotEmpty() && inId.isNotEmpty() && pairKey(outId, inId) != affectedKey) {
                        return false
                    }
                }
            } else if (affectedKey != null) {
                val curId = (currentMedia.invoke(actualPlayer)?.let(mediaId::get) as? String).orEmpty()
                if (curId.isNotEmpty() && !affectedKey.startsWith("${curId.length}:$curId")) {
                    return false
                }
            }
        }
        toggleMethod.invoke(host, false)
        toggleMethod.invoke(host, true)
        return true
    }
}
