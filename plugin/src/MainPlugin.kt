@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package automix

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.xuncorp.spw.workshop.api.*
import com.xuncorp.spw.workshop.api.hook.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

class MainPlugin(context: PluginContext) : SpwPlugin(context) {
    private val handles = mutableListOf<AutoCloseable>()
    private val config by lazy { WorkshopApi.manager.createConfigManager().getConfig("pairs.json") }
    private val statusState = mutableStateOf("等待宿主接入")
    private val savedPlans = MutableStateFlow(emptyMap<String, Plan>())
    internal val plans = savedPlans.asStateFlow()
    internal val drafts = mutableStateMapOf<String, Plan>()
    @Volatile internal var running = false
    @Volatile internal var bridge: Host1193? = null
    internal lateinit var worker: Worker
        private set

    internal val status: String get() = statusState.value

    private fun updateStatus(msg: String) {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            statusState.value = msg
        } else {
            javax.swing.SwingUtilities.invokeLater { statusState.value = msg }
        }
    }

    @Synchronized internal fun save(a: String, b: String, p: Plan) {
        check(running && p.valid())
        val next = plans.value + (pairKey(a, b) to p)
        val encoded = JsonObject(next.mapValues { it.value.json() }).toString()
        config.set("plans", encoded)
        check(config.save()) { "曲对设置写入失败" }
        savedPlans.value = next
        bridge?.rescheduleAutomix()
    }

    @Synchronized internal fun remove(a: String, b: String) {
        check(running)
        val next = plans.value - pairKey(a, b)
        val encoded = JsonObject(next.mapValues { it.value.json() }).toString()
        config.set("plans", encoded)
        check(config.save()) { "曲对设置删除失败" }
        savedPlans.value = next
        bridge?.rescheduleAutomix()
    }

    override fun start() {
        worker = Worker(config.getConfigPath().parent.resolve("analysis")) {
            runCatching { WorkshopApi.playback.pause() }
        }
        current = this
        running = true
        try {
            check(pluginContext.spwVersion == "1.19.3") { "此版本适配 SPW 1.19.3" }
            check(WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM)) { "请授权类变换并重新启用" }
            val loader = WorkshopApi.instance.javaClass.classLoader
            val source = Path.of(Class.forName("com.xuncorp.voxzen.service.PlaybackController",false,loader)
                .protectionDomain.codeSource.location.toURI())
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(source).use { input ->
                val block = ByteArray(131072)
                while(true) { val n=input.read(block); if(n<0) break; digest.update(block,0,n) }
            }
            check(HexFormat.of().formatHex(digest.digest()) ==
                "75eb9f7185acbbb2302fbcf3356d70f08958a410d2eaf32443abedddafa0e9a0") { "宿主构建与已适配版本不同" }
            config.reload()
            savedPlans.value = Json.parseToJsonElement(config.get("plans","{}")).jsonObject.mapNotNull { (k,v) ->
                runCatching { Plan.read(v.jsonObject) }.getOrNull()?.takeIf { it.valid() }?.let { k to it }
            }.toMap()
            val h = Host1193(loader)
            bridge = h
            val target = h.planner
            handles += WorkshopApi.hookRegistrar.hookMethod(target.declaringClass.name,target.name,
                target.parameterTypes.map { it.name },object : MethodHook(50) {
                    override fun after(call: MethodHookParam) {
                        if (!running || call.throwable != null) return
                        try { h.apply(call, plans.value)?.let { updateStatus(it) } }
                        catch(e: Exception) { updateStatus("已保留宿主过渡 · " + (e.cause?.message ?: e.javaClass.simpleName)) }
                    }
                })
            val navMsg = runCatching {
                handles += SidebarBridge(loader).register(this)
                "已就绪 · 切歌时自动接管"
            }.getOrElse { e ->
                "侧栏接入失败 · ${e.cause?.message ?: e.message ?: e.javaClass.simpleName}"
            }
            updateStatus(navMsg)
            if (plans.value.isNotEmpty()) {
                h.rescheduleAutomix()
            }
        } catch(e: Exception) {
            releaseHooks()
            updateStatus("接入未启用 · " + (e.message ?: e.javaClass.simpleName))
        }
    }
    private fun releaseHooks() {
        for (handle in handles.toList().asReversed()) { handle.close(); handles.remove(handle) }
    }
    override fun stop() {
        running = false
        bridge = null
        javax.swing.SwingUtilities.invokeLater { AutoMixNav.close() }
        try { releaseHooks() } finally { worker.stop(); if (current === this) current = null }
    }
    override fun delete() = stop()
    companion object {
        @Volatile internal var current: MainPlugin? = null
        @JvmStatic fun openPage() { javax.swing.SwingUtilities.invokeLater { AutoMixNav.requestOpen() } }
        @JvmStatic fun showStatus() { WorkshopApi.ui.toast(current?.status ?: "AutoMix 未启用", WorkshopApi.Ui.ToastType.Success) }
    }
}

class PlaybackEvents : PlaybackExtensionPoint {
    override fun onIsPlayingChanged(isPlaying: Boolean) { MainPlugin.current?.worker?.playingChanged(isPlaying) }
    override fun onStateChanged(state: PlaybackExtensionPoint.State) {
        if(state==PlaybackExtensionPoint.State.Idle || state==PlaybackExtensionPoint.State.Ended)
            MainPlugin.current?.worker?.playingChanged(false)
    }
}
