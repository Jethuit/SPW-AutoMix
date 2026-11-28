package automix

import com.xuncorp.spw.workshop.api.config.ConfigHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.coroutineContext

internal data class AutoMixUpdateNotice(
    val enabled: Boolean = true,
    val ready: Boolean = false,
    val checking: Boolean = false,
    val checkedAt: Long = 0L,
    val result: AutoMixReleaseCheck? = null,
    val checkError: String? = null,
    val preferenceError: Boolean = false,
) {
    val available get() = result as? AutoMixReleaseCheck.Available
}

internal class AutoMixUpdates(
    private val version: String,
    private val scope: CoroutineScope,
    private val openSettings: () -> ConfigHelper,
    private val onUpdate: (AutoMixReleaseCheck.Available) -> Unit,
    private val fetch: (String) -> AutoMixReleaseCheck = AutoMixReleaseUpdate::fetch,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val state = MutableStateFlow(AutoMixUpdateNotice())
    val notice = state.asStateFlow()
    private val checkLock = Mutex()
    private val settings by lazy(openSettings)
    private var lastAttempt = 0L
    private var retryNotBefore = 0L
    private var lastRelease: String? = null
    private var lastSeenVersion = ""

    fun start() {
        scope.launch(Dispatchers.IO) {
            initialize()
            delay(30_000)
            while (isActive) {
                check(manual = false)
                delay(60_000)
            }
        }
    }

    fun checkManually() {
        scope.launch(Dispatchers.IO) { check(manual = true) }
    }

    fun setEnabled(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            initialize()
            val saved = synchronized(this@AutoMixUpdates) {
                ensureActive()
                val saved = persist(enabled)
                state.value = state.value.copy(enabled = if (saved) enabled else state.value.enabled, preferenceError = !saved)
                saved
            }
            if (saved && enabled) check(manual = false)
        }
    }

    @Synchronized private fun initialize() {
        if (state.value.ready) return
        val loaded = runCatching {
            lastAttempt = settings.get("lastAttempt", "0").toLongOrNull() ?: 0L
            retryNotBefore = settings.get("retryNotBefore", "0").toLongOrNull() ?: 0L
            lastRelease = settings.get("lastRelease", "").takeIf(String::isNotEmpty)
            lastSeenVersion = settings.get("lastSeenVersion", "")
            val checkedAt = settings.get("checkedAt", "0").toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            val result = lastRelease?.let { AutoMixReleaseUpdate.compareTag(version, it) }
                ?.takeUnless { it is AutoMixReleaseCheck.Failed }
                ?: AutoMixReleaseCheck.NoRelease.takeIf { checkedAt > 0L }
            AutoMixUpdateNotice(enabled = settings.get("enabled", true), ready = true, checkedAt = checkedAt, result = result)
        }.getOrElse { AutoMixUpdateNotice(enabled = false, ready = true, preferenceError = true) }
        state.value = loaded
    }

    internal suspend fun check(manual: Boolean) {
        initialize()
        if (!checkLock.tryLock()) return
        try {
            synchronized(this) {
                coroutineContext.ensureActive()
                val now = clock()
                if (now < retryNotBefore) {
                    if (manual) state.value = state.value.copy(checkError = "GitHub 暂时限制了请求，请稍后重试。")
                    return
                }
                if (!manual && (!state.value.enabled || within(now, state.value.checkedAt, IntervalMillis) ||
                        within(now, lastAttempt, RetryMillis))) return
                lastAttempt = now
                state.value = state.value.copy(checking = true)
                persist(state.value.enabled)
            }

            val result = try {
                runInterruptible(Dispatchers.IO) { fetch(version) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AutoMixReleaseCheck.Failed("暂时无法读取 GitHub 的发布信息，请稍后重试。")
            }
            val update = synchronized(this) {
                coroutineContext.ensureActive()
                val previous = state.value
                if (result is AutoMixReleaseCheck.Failed) {
                    retryNotBefore = result.retryAt
                    persist(previous.enabled)
                    if (manual) state.value = previous.copy(checkError = result.message)
                    return@synchronized null
                }
                if (!manual && !previous.enabled) return@synchronized null
                retryNotBefore = 0L
                lastRelease = (result as? AutoMixReleaseCheck.Published)?.tag
                state.value = previous.copy(result = result, checkedAt = clock(), checkError = null)
                val available = result as? AutoMixReleaseCheck.Available
                val newlySeen = available != null && available.version != lastSeenVersion
                if (available != null) lastSeenVersion = available.version
                persist(previous.enabled)
                available?.takeIf { !manual && newlySeen }
            }
            if (update != null && state.value.enabled) onUpdate(update)
        } finally {
            synchronized(this) { state.value = state.value.copy(checking = false) }
            checkLock.unlock()
        }
    }

    private fun persist(enabled: Boolean): Boolean = runCatching {
        settings.set("enabled", enabled)
        settings.set("lastAttempt", lastAttempt.toString())
        settings.set("retryNotBefore", retryNotBefore.toString())
        settings.set("checkedAt", state.value.checkedAt.toString())
        settings.set("lastRelease", lastRelease.orEmpty())
        settings.set("lastSeenVersion", lastSeenVersion)
        settings.save()
    }.getOrDefault(false)

    private companion object {
        const val IntervalMillis = 24 * 60 * 60 * 1_000L
        const val RetryMillis = 15 * 60 * 1_000L

        fun within(now: Long, previous: Long, interval: Long) = previous > 0L && now >= previous && now - previous < interval
    }
}
