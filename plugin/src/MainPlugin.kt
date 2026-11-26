@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package automix

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.xuncorp.spw.workshop.api.*
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
    private class Activation(val worker: Worker) {
        val handles = mutableListOf<AutoCloseable>() // EDT only
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var discovery: Thread? = null
        @Volatile var upcoming: UpcomingTransition? = null
        @Volatile var position: Pair<String, Long>? = null
        @Volatile var automatic: PreparedAutoMix? = null
    }

    @Volatile private var activation: Activation? = null
    private val config by lazy { WorkshopApi.manager.createConfigManager().getConfig("pairs.json") }
    private val statusState = mutableStateOf("等待宿主接入")
    private val savedPlans = MutableStateFlow(emptyMap<String, Plan>())
    internal val plans = savedPlans.asStateFlow()
    internal val drafts = mutableStateMapOf<String, Plan>()
    @Volatile internal var running = false
    private val availableHost = MutableStateFlow<HostPlayback?>(null)
    internal val hosts = availableHost.asStateFlow()
    internal val bridge get() = availableHost.value
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

    @Synchronized override fun start() {
        if (activation != null) return
        worker = Worker(config.getConfigPath().parent.resolve("analysis")) {
            WorkshopApi.playback.pause()
        }
        val state = Activation(worker)
        activation = state
        current = this
        running = true
        try {
            checkPermission()
            config.reload()
            savedPlans.value = Json.parseToJsonElement(config.get("plans","{}")).jsonObject.mapNotNull { (k,v) ->
                runCatching { Plan.read(v.jsonObject) }.getOrNull()?.takeIf { it.valid() }?.let { k to it }
            }.toMap()
            updateStatus(state, "正在识别 SPW ${pluginContext.spwVersion} 的宿主接口…")
            val loader = WorkshopApi.instance.javaClass.classLoader
            state.discovery = Thread({
                try {
                    val symbols = HostSymbols.read(HostSymbols.source(loader), loader)
                    val playback = HostPlayback(symbols)
                    val sidebar = runCatching { SidebarBridge(symbols) }
                    EventQueue.invokeLater { install(state, playback, sidebar) }
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
            combine(queue, plans) { value, saved ->
                host.upcomingTransition(value)?.takeUnless { it.key in saved }
            }.distinctUntilChanged().collectLatest { pair ->
                state.upcoming = pair
                state.automatic = null
                if (pair == null) return@collectLatest
                val plan = try {
                    state.worker.planPair(pair.from.path, pair.to.path)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    if (activation === state && host.upcomingTransition(queue.value) == pair && pair.key !in plans.value) {
                        updateStatus(state, "自动分析失败 · ${failure.detail().take(120)}")
                    }
                    return@collectLatest
                }
                if (activation !== state || host.upcomingTransition(queue.value) != pair || pair.key in plans.value) {
                    return@collectLatest
                }
                val prepared = PreparedAutoMix(pair.key, plan)
                state.automatic = prepared
                EventQueue.invokeLater {
                    if (activation !== state || state.automatic !== prepared ||
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

    private fun install(state: Activation, host: HostPlayback, sidebar: Result<SidebarBridge>) {
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
                            host.apply(call, plans.value, state.automatic)?.let { updateStatus(state, it) }
                        } catch (failure: Exception) {
                            updateStatus(state, "已保留宿主过渡 · ${failure.detail()}")
                        }
                    }
                })
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
            transition.fold({ "切歌时自动接管" }, { "过渡未接入 · ${it.detail()}" }),
            navigation.fold({ warning -> warning?.let { "侧栏不可用，可从模组配置打开 · $it" } },
                { "编辑器未接入 · ${it.detail()}" }),
            host.warnings.filterNot { it.startsWith("过渡 · ") }.takeIf(List<String>::isNotEmpty)?.joinToString(" · ")
        )
        updateStatus(state, messages.joinToString(" · "))
        if (transition.isSuccess && activation === state) {
            prepareUpcoming(state, host)
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
