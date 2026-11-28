@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package automix

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.xuncorp.spw.workshop.api.*
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import com.xuncorp.spw.workshop.api.hook.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.awt.EventQueue

class MainPlugin(context: PluginContext) : SpwPlugin(context) {
    private class Activation(val worker: Worker, version: String, openUpdateConfig: () -> ConfigHelper,
                             onUpdate: (AutoMixReleaseCheck.Available) -> Unit) {
        val handles = mutableListOf<AutoCloseable>() // EDT only
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val updates = AutoMixUpdates(version, scope, openSettings = openUpdateConfig, onUpdate = onUpdate)
        var discovery: Thread? = null
        @Volatile var upcoming: UpcomingTransition? = null
        @Volatile var position: Pair<String, Long>? = null
        @Volatile var automatic: PreparedAutoMix? = null
        val uiCache = AutoMixUiCache()
    }

    @Volatile private var activation: Activation? = null
    private val configManager by lazy { WorkshopApi.manager.createConfigManager() }
    private val config by lazy { configManager.getConfig("pairs.json") }
    private val updateConfig by lazy { configManager.getConfig("updates.json") }
    private val statusState = mutableStateOf("等待宿主接入")
    private val savedPlans = MutableStateFlow(emptyMap<String, Plan>())
    internal val plans = savedPlans.asStateFlow()
    private val selectedAlgorithm = MutableStateFlow(MixAlgorithm.Original)
    internal val algorithm = selectedAlgorithm.asStateFlow()
    internal val drafts = mutableStateMapOf<String, Plan>()
    @Volatile internal var running = false
    private val availableHost = MutableStateFlow<HostPlayback?>(null)
    internal val hosts = availableHost.asStateFlow()
    internal val bridge get() = availableHost.value
    internal val updates get() = activation?.updates
    internal val uiCache: AutoMixUiCache get() = activation?.uiCache ?: fallbackUiCache
    private val fallbackUiCache = AutoMixUiCache()
    internal lateinit var worker: Worker
        private set

    internal val status: String get() = statusState.value

    private fun updateStatus(state: Activation, message: String) {
        if (EventQueue.isDispatchThread()) {
            if (activation === state) statusState.value = message
        } else EventQueue.invokeLater { updateStatus(state, message) }
    }

    @Synchronized internal fun save(a: String, b: String, p: Plan) {
        check(running && p.valid())
        val key = pairKey(a, b)
        val next = plans.value + (key to p)
        val encoded = JsonObject(next.mapValues { it.value.json() }).toString()
        config.set("plans", encoded)
        check(config.save()) { "曲对设置写入失败" }
        savedPlans.value = next
        bridge?.rescheduleAutomix(key)
    }

    @Synchronized internal fun remove(a: String, b: String) {
        check(running)
        val key = pairKey(a, b)
        val next = plans.value - key
        val encoded = JsonObject(next.mapValues { it.value.json() }).toString()
        config.set("plans", encoded)
        check(config.save()) { "曲对设置删除失败" }
        savedPlans.value = next
        bridge?.rescheduleAutomix(key)
    }

    @Synchronized internal fun selectAlgorithm(next: MixAlgorithm) {
        check(running)
        val previous = algorithm.value
        if (next == previous) return
        config.set("algorithm", next.name)
        if (!config.save()) {
            config.set("algorithm", previous.name)
            error("算法设置写入失败")
        }
        selectedAlgorithm.value = next
        bridge?.rescheduleAutomix()
    }

    @Synchronized override fun start() {
        if (activation != null) return
        worker = Worker(config.getConfigPath().parent.resolve("analysis")) {
            WorkshopApi.playback.pause()
        }
        val state = Activation(worker, pluginContext.pluginVersion, { updateConfig }) { release ->
            EventQueue.invokeLater {
                val notice = updates?.notice?.value
                if (notice?.enabled == true && notice.available == release) {
                    runCatching { WorkshopApi.ui.toast("AutoMix 有新版本 v${release.version}，可在「关于」中查看。", WorkshopApi.Ui.ToastType.Success) }
                }
            }
        }
        activation = state
        current = this
        running = true
        try {
            checkPermission()
            config.reload()
            val storedAlgorithm = config.get("algorithm", MixAlgorithm.Original.name)
            selectedAlgorithm.value = MixAlgorithm.entries.firstOrNull { it.name == storedAlgorithm } ?: MixAlgorithm.Original
            savedPlans.value = Json.parseToJsonElement(config.get("plans","{}")).jsonObject.mapNotNull { (k,v) ->
                runCatching { Plan.read(v.jsonObject) }.getOrNull()?.takeIf { it.valid() }?.let { k to it }
            }.toMap()
            state.updates.start()
            updateStatus(state, "正在识别 SPW ${pluginContext.spwVersion} 的宿主接口…")
            val loader = WorkshopApi.instance.javaClass.classLoader
            state.discovery = Thread({
                try {
                    val symbols = HostSymbols.read(HostSymbols.source(loader), loader)
                    val playback = HostPlayback(symbols)
                    val sidebar = runCatching { SidebarBridge(symbols) }
                    val liveEffects = runCatching { HostLiveFxTargets(symbols) }
                    EventQueue.invokeLater { install(state, playback, sidebar, liveEffects) }
                } catch (failure: Exception) {
                    updateStatus(state, "接入未启用 · ${failure.detail()}")
                } catch (failure: LinkageError) {
                    updateStatus(state, "宿主 API 不兼容 · ${failure.detail()}")
                }
            }, "AutoMix host discovery").apply { isDaemon = true; start() }
        } catch (failure: Exception) {
            updateStatus(state, "接入未启用 · ${failure.detail()}")
        }
    }

    private fun checkPermission() {
        check(WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM)) { "请授权类变换并重新启用" }
    }

    internal fun recordPosition(positionMs: Long) {
        val state = activation ?: return
        val live = bridge?.currentPositionMs()
        if (live != null) {
            state.position = live
            return
        }
        val current = runCatching { WorkshopApi.playback.getCurrentMediaItem() }.getOrNull() ?: return
        state.position = current.id to positionMs
    }

    private fun prepareUpcoming(state: Activation, host: HostPlayback) {
        val queue = runCatching { host.queueStates() }.getOrNull() ?: return
        state.scope.launch {
            combine(queue, plans, algorithm) { value, saved, mode ->
                host.upcomingTransition(value)?.takeUnless { it.key in saved } to mode
            }.distinctUntilChanged().collectLatest { (pair, mode) ->
                state.upcoming = pair
                state.automatic = null
                if (pair == null) return@collectLatest
                val plan = try {
                    state.worker.planPair(pair.from.path, pair.to.path, mode)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    if (activation === state && algorithm.value == mode &&
                        host.upcomingTransition(queue.value) == pair && pair.key !in plans.value) {
                        updateStatus(state, "自动分析失败 · ${failure.detail().take(120)}")
                    }
                    return@collectLatest
                }
                if (activation !== state || algorithm.value != mode ||
                    host.upcomingTransition(queue.value) != pair || pair.key in plans.value) {
                    return@collectLatest
                }
                val prepared = PreparedAutoMix(pair.key, plan, mode)
                state.automatic = prepared
                EventQueue.invokeLater {
                    if (activation !== state || algorithm.value != mode || state.automatic !== prepared ||
                        host.upcomingTransition(queue.value) != pair || pair.key in plans.value) return@invokeLater
                    val position = host.currentPositionMs()?.takeIf { it.first == pair.from.id }?.second
                        ?: state.position?.takeIf { it.first == pair.from.id }?.second
                    val start = (plan.cueA + (-plan.cueB).coerceAtLeast(0.0)) * 1000.0
                    // With no position yet, the planner still checks Pisces' actual position before applying the cue.
                    if (position == null || position + HostPlayback.MIN_LEAD_MS < start) host.rescheduleAutomix(pair.key)
                }
            }
        }
    }

    private fun preAnalyzeFollowing(state: Activation, host: HostPlayback) {
        state.scope.launch {
            combine(host.queueStates(), algorithm) { queue, mode ->
                if (mode == MixAlgorithm.Spotify) host.followingTracks(queue, 3).drop(1) else emptyList()
            }.distinctUntilChanged().collectLatest { tracks ->
                // Yield the first I/O slot to the imminent pair and avoid decoding an entire queue.
                if (tracks.isEmpty()) return@collectLatest
                kotlinx.coroutines.delay(1500)
                for (track in tracks) {
                    try {
                        state.worker.preAnalyze(track.path, MixAlgorithm.Spotify)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Upcoming playback can still retry the analysis or use its host fallback.
                    }
                }
            }
        }
    }

    private fun install(
        state: Activation,
        host: HostPlayback,
        sidebar: Result<SidebarBridge>,
        liveEffects: Result<HostLiveFxTargets>
    ) {
        if (activation !== state) return
        try {
            checkPermission()
        } catch (failure: Exception) {
            updateStatus(state, "接入未启用 · ${failure.detail()}")
            return
        }

        val transition = runCatching {
            val target = host.planner
            state.handles += WorkshopApi.hookRegistrar.hookMethod(target.declaringClass.name, target.name,
                target.parameterTypes.map { it.name }, object : MethodHook(50) {
                    override fun after(call: MethodHookParam) {
                        if (activation !== state || call.throwable != null) return
                        try {
                            val prepared = state.automatic?.takeIf { it.algorithm == algorithm.value }
                            host.apply(call, plans.value, prepared)?.let { updateStatus(state, it) }
                        } catch (failure: Exception) {
                            updateStatus(state, "已保留宿主过渡 · ${failure.detail()}")
                        }
                    }
                })
        }
        if (transition.isSuccess) liveEffects.onSuccess { effects ->
            val target = effects.lowPassParameters
            runCatching {
                state.handles += WorkshopApi.hookRegistrar.hookMethod(target.declaringClass.name, target.name,
                    target.parameterTypes.map { it.name }, object : MethodHook(50) {
                        override fun after(call: MethodHookParam) {
                            if (activation !== state || call.throwable != null || !host.liveHighPass.value) return
                            val cutoff = (call.args.getOrNull(0) as? Double)?.let(HostHighPass::decode) ?: return
                            // Workshop advice leaves the returned reference in place; if rewriting fails, the host's
                            // clamped 18 kHz low-pass buffer remains valid as a safe fallback.
                            val updated = runCatching { HostBassLiveFilter.writeHighPass(call.result, cutoff) }
                                .getOrDefault(false)
                            if (!updated && host.liveHighPass.compareAndSet(true, false)) {
                                updateStatus(state, "高通仅试听 · 宿主滤波接入失效")
                            }
                        }
                    })
                host.liveHighPass.value = true
            }.onFailure { System.err.println("AutoMix 实播高通接入失败 · ${it.detail()}") }
        }
        val navigation = sidebar.mapCatching { entry ->
            state.handles += entry.register(this) { activation === state }
            entry.sidebarWarning
        }
        synchronized(this) {
            if (activation !== state) return
            availableHost.value = host
        }
        val messages = listOfNotNull(
            transition.fold({ if (host.liveHighPass.value) "切歌时自动接管 · 支持高通" else "切歌时自动接管" },
                { "过渡未接入 · ${it.detail()}" }),
            navigation.fold({ warning -> warning?.let { "侧栏不可用，可从模组配置打开 · $it" } },
                { "编辑器未接入 · ${it.detail()}" }),
            host.warnings.filterNot { it.startsWith("过渡 · ") }.takeIf(List<String>::isNotEmpty)?.joinToString(" · ")
        )
        updateStatus(state, messages.joinToString(" · "))
        if (transition.isSuccess && activation === state) {
            prepareUpcoming(state, host)
            preAnalyzeFollowing(state, host)
            if (plans.value.isNotEmpty()) host.rescheduleAutomix()
        }
    }

    override fun stop() {
        val state = synchronized(this) {
            val enabled = activation ?: return
            activation = null
            running = false
            availableHost.value = null
            if (current === this) current = null
            enabled
        }
        state.discovery?.interrupt()
        state.scope.cancel()
        EventQueue.invokeLater {
            AutoMixNav.close()
            state.handles.asReversed().forEach { handle ->
                runCatching { handle.close() }.onFailure { System.err.println("AutoMix Hook cleanup: ${it.detail()}") }
            }
            state.handles.clear()
        }
        state.worker.stop()
    }
    override fun delete() = stop()
    companion object {
        @Volatile internal var current: MainPlugin? = null
        @JvmStatic fun openPage() { javax.swing.SwingUtilities.invokeLater { AutoMixNav.requestOpen() } }
        @JvmStatic fun showStatus() { WorkshopApi.ui.toast(current?.status ?: "AutoMix 未启用", WorkshopApi.Ui.ToastType.Success) }
    }
}

private fun Throwable.detail() = cause?.message ?: message ?: javaClass.simpleName

class PlaybackEvents : PlaybackExtensionPoint {
    override fun onIsPlayingChanged(isPlaying: Boolean) { MainPlugin.current?.worker?.playingChanged(isPlaying) }
    override fun onPositionUpdated(position: Long) { MainPlugin.current?.recordPosition(position) }
    override fun onSeekTo(position: Long) { MainPlugin.current?.recordPosition(position) }
    override fun onStateChanged(state: PlaybackExtensionPoint.State) {
        if(state==PlaybackExtensionPoint.State.Idle || state==PlaybackExtensionPoint.State.Ended)
            MainPlugin.current?.worker?.playingChanged(false)
    }
}
