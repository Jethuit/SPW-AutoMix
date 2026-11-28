@file:OptIn(androidx.compose.runtime.InternalComposeApi::class, com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package automix

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.MethodHook
import com.xuncorp.spw.workshop.api.hook.MethodHookParam
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import org.pf4j.PluginClassLoader
import org.pf4j.PluginManager
import org.pf4j.PluginState
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier as JModifier
import java.util.IdentityHashMap
import javax.swing.UIManager

internal enum class AutoMixExitMotion { Return, Navigate }
internal enum class AutoMixNavigationAction { Navigate, Replace, Forward }

/** Host-composed standalone AutoMix page and navigation coordinator. */
internal object AutoMixNav {
    private class Binding(
        val navigator: Any,
        val currentRoute: () -> Any?,
        val navigate: (Any) -> Unit,
        val hasHostOverlay: () -> Boolean,
        val forwardHistory: () -> List<Any?>?
    )
    private class OpenRequest(val host: Binding, val route: Any, val marker: Any)
    internal class NavigationObservation(
        val binding: Any,
        val action: AutoMixNavigationAction,
        val route: Any?,
        val overlay: Boolean?,
        val forwardHistory: List<Any?>?,
        val opensAutoMix: Boolean,
        val sameRoute: Boolean,
        val statsMarkerCandidate: Boolean
    )

    var visible by mutableStateOf(false)
        private set
    var closing by mutableStateOf(false)
        private set
    var exitMotion by mutableStateOf(AutoMixExitMotion.Return)
        private set
    var nativeNavigation by mutableStateOf(true)
        private set
    var closeTicket by mutableStateOf(0L)
        private set
    var navigationTicket by mutableStateOf(0L)
        private set
    val navigating: Boolean get() = closing && exitMotion == AutoMixExitMotion.Navigate
    val active: Boolean get() = visible && !closing

    private var binding: Binding? = null
    private var pending: OpenRequest? = null
    private var presenter: Any? = null
    private val issuedMarkers = mutableListOf<WeakReference<Any>>()

    fun bindNavigator(
        navigator: Any,
        currentRoute: () -> Any?,
        navigate: (Any) -> Unit,
        hasHostOverlay: () -> Boolean,
        forwardHistory: () -> List<Any?>? = { null }
    ): AutoCloseable {
        val b = Binding(navigator, currentRoute, navigate, hasHostOverlay, forwardHistory)
        binding = b
        pending = null
        return AutoCloseable {
            if (binding === b) {
                binding = null
                pending = null
            }
        }
    }

    fun attachPresenter(): AutoCloseable {
        val token = Any()
        presenter = token
        return AutoCloseable {
            if (presenter === token) {
                presenter = null
                if (closing) finishClose(closeTicket)
            }
        }
    }

    fun close() {
        pending = null
        resetVisibility()
    }

    private fun resetVisibility() {
        closeTicket++
        visible = false
        closing = false
        nativeNavigation = true
        exitMotion = AutoMixExitMotion.Return
    }

    fun finishClose(ticket: Long) {
        if (closing && ticket == closeTicket) resetVisibility()
    }

    fun requestReturn() {
        pending = null
        if (visible && !closing) beginClose(AutoMixExitMotion.Return)
    }

    fun requestNavigation(native: Boolean) {
        pending = null
        if (!visible) return
        nativeNavigation = native
        navigationTicket++
        beginClose(AutoMixExitMotion.Navigate)
    }

    private fun beginClose(motion: AutoMixExitMotion) {
        if (presenter == null) {
            resetVisibility()
            return
        }
        closeTicket++
        exitMotion = motion
        closing = true
    }

    private fun open() {
        pending = null
        closeTicket++
        exitMotion = AutoMixExitMotion.Return
        nativeNavigation = true
        closing = false
        visible = true
    }

    fun requestOpen() {
        if (active) return
        val host = binding
        if (host == null) {
            WorkshopApi.ui.toast("AutoMix 页面导航尚未准备好，请稍后重试", WorkshopApi.Ui.ToastType.Warning)
            return
        }
        try {
            val route = host.currentRoute()
            if (route == null) {
                open()
                return
            }
            val marker = copyHostRoute(route)
            issuedMarkers.removeAll { it.get() == null }
            issuedMarkers += WeakReference(marker)
            pending = OpenRequest(host, route, marker)
            host.navigate(marker)
        } catch (_: Exception) {
            pending = null
            WorkshopApi.ui.toast("无法切换到 AutoMix", WorkshopApi.Ui.ToastType.Warning)
        }
    }

    private fun OpenRequest.matches(navigator: Any?, arg: Any?): Boolean =
        marker === arg && host === binding && host.navigator === navigator && host.currentRoute() === route

    private fun isIssuedMarker(destination: Any?): Boolean =
        destination != null && issuedMarkers.any { it.get() === destination }

    fun skipStaleMarker(navigator: Any?, destination: Any?, action: AutoMixNavigationAction): Boolean {
        if (action != AutoMixNavigationAction.Navigate || !isIssuedMarker(destination)) return false
        val request = pending
        if (request?.matches(navigator, destination) == true) return false
        // Another plugin can replay a canceled request after an asynchronous leave guard.
        if (request?.marker === destination) pending = null
        return true
    }

    fun observeNavigation(navigator: Any?, destination: Any?, action: AutoMixNavigationAction): NavigationObservation? {
        val host = binding?.takeIf { it.navigator === navigator } ?: return null
        val opensAutoMix = action == AutoMixNavigationAction.Navigate && isIssuedMarker(destination)
        if (!opensAutoMix) {
            pending = null
            if (!visible) return null
        }
        val route = host.currentRoute()
        val sameRoute = action == AutoMixNavigationAction.Navigate && route != null && destination == route
        val forward = action == AutoMixNavigationAction.Forward
        return NavigationObservation(host, action, route,
            if (forward) runCatching(host.hasHostOverlay).getOrNull() else null,
            if (forward) runCatching(host.forwardHistory).getOrNull() else null,
            opensAutoMix, sameRoute, !opensAutoMix && sameRoute && destination !== route)
    }

    fun completeNavigation(observation: NavigationObservation, navigator: Any?, destination: Any?) {
        val host = binding?.takeIf { it === observation.binding && it.navigator === navigator } ?: return
        if (observation.opensAutoMix) {
            if (pending?.matches(navigator, destination) == true) open()
            return
        }
        if (!visible) return
        if (observation.action == AutoMixNavigationAction.Forward) {
            val routeUnchanged = observation.route === host.currentRoute()
            val overlayUnchanged = observation.overlay == runCatching(host.hasHostOverlay).getOrNull()
            val currentHistory = runCatching(host.forwardHistory).getOrNull()
            val oldHistory = observation.forwardHistory
            val historyUnchanged = oldHistory == null || currentHistory == null ||
                (oldHistory.size == currentHistory.size && oldHistory.indices.all { oldHistory[it] === currentHistory[it] })
            if (routeUnchanged && overlayUnchanged && historyUnchanged) return
        }
        requestNavigation(native = !observation.sameRoute)
    }

    fun consumeBack(navigator: Any): Boolean {
        val host = binding?.takeIf { it.navigator === navigator } ?: return false
        pending = null
        if (!visible) return false
        if (host.hasHostOverlay()) return false
        requestReturn()
        return true
    }

    private fun copyHostRoute(route: Any): Any {
        val type = route.javaClass
        val copy = type.declaredMethods.singleOrNull { it.name == "copy" }
        val result = if (copy != null) {
            val values = copy.parameterTypes.indices.map {
                type.getDeclaredMethod("component${it + 1}").apply { trySetAccessible() }.invoke(route)
            }
            copy.apply { trySetAccessible() }.invoke(route, *values.toTypedArray())
        } else {
            type.getDeclaredConstructor().apply { trySetAccessible() }.newInstance()
        }
        check(result !== route && result == route && route == result) { "Route copy mismatch" }
        return result
    }
}

internal class AutoMixNavigationHook(
    private val action: AutoMixNavigationAction,
    private val isActive: () -> Boolean,
    private val statsVisible: () -> Boolean? = { null }
) : MethodHook(90) {
    private class Call(val navigation: AutoMixNav.NavigationObservation, val statsWasVisible: Boolean?)
    private val observations = IdentityHashMap<MethodHookParam, Call>()

    override fun before(call: MethodHookParam) {
        if (!isActive()) return
        val destination = call.args.firstOrNull()
        if (AutoMixNav.skipStaleMarker(call.thisObject, destination, action)) {
            // Let the other page hooks see the navigation, then suppress stale replays.
            call.setResult(null)
            return
        }
        val observed = AutoMixNav.observeNavigation(call.thisObject, destination, action) ?: return
        observations[call] = Call(observed, if (observed.statsMarkerCandidate) statsVisible() else null)
    }

    override fun after(call: MethodHookParam) {
        val observed = observations.remove(call) ?: return
        if (!isActive() || call.throwable != null) return
        // Listen Stats consumes its own route marker; confirm that it actually opened.
        val statsOpened = call.isOriginalSkipped && observed.navigation.statsMarkerCandidate &&
            observed.statsWasVisible != true && statsVisible() == true
        if (!call.isOriginalSkipped || statsOpened) {
            AutoMixNav.completeNavigation(observed.navigation, call.thisObject, call.args.firstOrNull())
        }
    }
}

/** Neutralizes underlying sidebar route highlight while AutoMix owns the viewport. */
private class SidebarStyleMasker(control: Method) {
    private val owner = control.declaringClass
    private val styleType = owner.declaredMethods.single {
        JModifier.isStatic(it.modifiers) &&
            it.parameterTypes.toList() == listOf(Composer::class.java) &&
            !it.returnType.isPrimitive
    }.returnType

    private val localGetter = owner.declaredMethods.single {
        JModifier.isStatic(it.modifiers) &&
            it.parameterCount == 0 &&
            it.returnType == ProvidableCompositionLocal::class.java
    }

    @Suppress("UNCHECKED_CAST")
    val local: ProvidableCompositionLocal<Any?> get() = localGetter.invoke(null) as ProvidableCompositionLocal<Any?>

    private val constructor = styleType.declaredConstructors.single {
        val p = it.parameterTypes
        p.size == 10 && p[0].isEnum && p[1] == control.parameterTypes[6] &&
            p.slice(2..5).all { t -> t == Long::class.javaPrimitiveType } &&
            p[6].name == "androidx.compose.ui.text.TextStyle" && p[7] == p[6] &&
            p[8].name == "androidx.compose.ui.graphics.Shape" && p[9] == Boolean::class.javaPrimitiveType
    }.also { it.trySetAccessible() }

    private fun getter(type: Class<*>) = styleType.declaredMethods.single {
        it.parameterCount == 0 && it.returnType == type && !JModifier.isStatic(it.modifiers)
    }

    private val mode = getter(constructor.parameterTypes[0])
    private val dimensions = getter(constructor.parameterTypes[1])
    private val shape = getter(constructor.parameterTypes[8])
    private val color = styleType.declaredMethods.single {
        it.parameterTypes.toList() == listOf(Boolean::class.javaPrimitiveType) && it.returnType == Long::class.javaPrimitiveType
    }
    private val text = styleType.declaredMethods.single {
        it.parameterTypes.toList() == listOf(Boolean::class.javaPrimitiveType) && it.returnType == constructor.parameterTypes[6]
    }
    private val background = styleType.declaredMethods.single {
        it.parameterTypes.toList() == listOf(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType) &&
            it.returnType == Long::class.javaPrimitiveType
    }

    fun unselected(original: Any): Any {
        check(styleType.isInstance(original))
        val normalColor = color.invoke(original, false)
        val normalText = text.invoke(original, false)
        val normalBg = background.invoke(original, false, false)
        val hoverBg = background.invoke(original, false, true)
        return constructor.newInstance(
            mode.invoke(original),
            dimensions.invoke(original),
            normalColor,
            normalColor,
            normalBg,
            hoverBg,
            normalText,
            normalText,
            shape.invoke(original),
            false
        )
    }
}

/** Keep the overlay and sidebar scoped to the discovered host call sites. */
internal class SidebarBridge(symbols: HostSymbols) {
    private val navigation = HostNavigationTargets(symbols)
    val backdrop = runCatching { HostBackdrop(symbols, navigation.contentLayout(symbols)) }.getOrNull()
    private val sidebar = runCatching { HostSidebarTargets(symbols) }
    private val styleMasker = runCatching { SidebarStyleMasker(sidebar.getOrThrow().control) }.getOrNull()
    private val theme = runCatching { HostSkin(symbols) }.getOrNull()
    var sidebarWarning = sidebar.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        private set

    val localNavigator get() = navigation.localNavigator

    fun skin(composer: Composer): Skin {
        val base = runCatching { theme?.read(composer) }.getOrNull() ?: fallbackSkin(
            UIManager.getColor("Panel.background")?.let { it.red + it.green + it.blue < 384 } ?: false
        )
        return materialSkin(base, backdrop?.activeBackdrop ?: WindowBackdrop.Solid)
    }

    fun currentRoute(nav: Any): Any? = (navigation.stack.get(nav) as? List<*>)?.lastOrNull()

    fun navigate(nav: Any, route: Any) {
        navigation.navigateMethod.invoke(nav, route)
    }

    fun hasHostOverlay(nav: Any): Boolean = navigation.hasOverlay(nav, currentRoute(nav))

    fun forwardHistory(nav: Any): List<Any?>? = navigation.forwardHistory(nav)

    private fun listenStatsVisible(): Boolean? = runCatching {
        val loader = SidebarBridge::class.java.classLoader as? PluginClassLoader ?: return null
        val managerField = PluginClassLoader::class.java.getDeclaredField("pluginManager").apply { trySetAccessible() }
        val manager = managerField.get(loader) as? PluginManager ?: return null
        val wrapper = manager.getPlugin("com.jethuit.spw.listenstats")
            ?.takeIf { it.pluginState == PluginState.STARTED } ?: return null
        val stateClass = Class.forName(
            "com.jethuit.spw.listenstats.ui.StatsHostState",
            false,
            wrapper.pluginClassLoader
        )
        val instance = stateClass.getField("INSTANCE").get(null)
        val visible = stateClass.getMethod("getVisible").invoke(instance) as? Boolean ?: return null
        val closing = runCatching { stateClass.getMethod("getClosing").invoke(instance) as? Boolean }.getOrNull() == true
        visible && !closing
    }.getOrNull()

    fun register(plugin: MainPlugin, isActive: () -> Boolean): List<AutoCloseable> {
        val added = mutableListOf<AutoCloseable>()
        try {
            val registrar = WorkshopApi.hookRegistrar
            for ((method, action) in listOf(
                navigation.navigateMethod to AutoMixNavigationAction.Navigate,
                navigation.replaceMethod to AutoMixNavigationAction.Replace,
                navigation.forwardMethod to AutoMixNavigationAction.Forward
            )) {
                added += registrar.hookMethod(
                    method.declaringClass.name,
                    method.name,
                    method.parameterTypes.map { it.name },
                    AutoMixNavigationHook(action, isActive, ::listenStatsVisible)
                )
            }

            val back = navigation.backMethod
            added += registrar.hookMethod(back.declaringClass.name, back.name, emptyList(), object : MethodHook(95) {
                override fun before(call: MethodHookParam) {
                    val nav = call.thisObject ?: return
                    if (isActive() && runCatching { AutoMixNav.consumeBack(nav) }.getOrDefault(false)) {
                        call.setResult(null)
                    }
                }
            })

            val page = navigation.page
            added += registrar.afterComposable(page.target.className, page.target.name, page.callerClass,
                emptyList(), 40, object : ComposableHook() {
                    @Composable override fun Content(call: UiHookCall) {
                        if (isActive()) AutoMixOverlay(plugin, this@SidebarBridge)
                    }
                })

            // Isolate the native viewport inside AppNavigation, leaving the host's outer Salt Layer intact.
            backdrop?.let { bridge ->
                val site = bridge.site
                runCatching {
                    added += registrar.replaceComposable(site.target.className, site.target.name, site.callerClass,
                        listOf("androidx.compose.ui.Modifier", "kotlin.jvm.functions.Function3"), 40,
                        object : ComposableHook() {
                            @Composable override fun Content(call: UiHookCall) {
                                bridge.Render(
                                    call.getArgument(0) as Modifier,
                                    requireNotNull(call.getArgument(1)),
                                    isActive
                                ) {
                                    AutoMixPage(plugin, this@SidebarBridge.skin(currentComposer))
                                }
                            }
                        })
                }
            }

            sidebar.getOrNull()?.let { targets ->
                val site = targets.entry
                val params = targets.routeAccessor.parameterTypes.dropLast(2).map { it.name }
                val sidebarHook = runCatching {
                    registrar.afterComposable(site.target.className, site.target.name, site.callerClass, params,
                        20, object : ComposableHook() {
                            @Composable override fun Content(call: UiHookCall) {
                                val libraryRoute = runCatching {
                                    targets.routeGetter.invoke(call.getArgument(0))?.javaClass?.name ==
                                        "com.xuncorp.voxzen.ui.navigation.ScreenRoute\$MusicLibrary"
                                }.getOrDefault(false)
                                if (isActive() && libraryRoute) AutoMixSidebarButton(targets.control)
                            }
                        })
                }
                sidebarHook.onSuccess { added += it }
                    .onFailure { sidebarWarning = it.message ?: it.javaClass.simpleName }

                val masker = styleMasker
                if (sidebarHook.isSuccess && masker != null) runCatching {
                    added += registrar.replaceComposable(site.target.className, site.target.name, site.callerClass,
                        params, 50, object : ComposableHook() {
                            @Composable override fun Content(call: UiHookCall) {
                                val item = call.getArgument(0)
                                val local = masker.local
                                val current = local.current
                                val mask = isActive() && AutoMixNav.active
                                val neutral = remember(current) {
                                    current?.let { runCatching { masker.unselected(it) }.getOrNull() }
                                }
                                CompositionLocalProvider(local provides if (mask) neutral ?: current else current) {
                                    targets.routeAccessor.invoke(null, item, currentComposer, 0)
                                }
                            }
                        })
                }
            }
            return added
        } catch (failure: Throwable) {
            added.asReversed().forEach { handle ->
                runCatching { handle.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        }
    }
}

@Composable
private fun AutoMixSidebarIcon() {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 2.0.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

        val outCurve = Path().apply {
            moveTo(w * 0.14f, h * 0.28f)
            cubicTo(w * 0.44f, h * 0.28f, w * 0.56f, h * 0.72f, w * 0.86f, h * 0.72f)
        }
        val inCurve = Path().apply {
            moveTo(w * 0.14f, h * 0.72f)
            cubicTo(w * 0.44f, h * 0.72f, w * 0.56f, h * 0.28f, w * 0.86f, h * 0.28f)
        }

        drawPath(
            outCurve,
            brush = Brush.linearGradient(listOf(Color(0xFF38BDF8), Color(0xFF2563EB))),
            style = stroke
        )
        drawPath(
            inCurve,
            brush = Brush.linearGradient(listOf(Color(0xFF6366F1), Color(0xFFA855F7))),
            style = stroke
        )
        drawCircle(
            color = Color(0xFF0EA5E9),
            radius = 2.2.dp.toPx(),
            center = Offset(w * 0.50f, h * 0.50f)
        )
    }
}

@Composable
private fun AutoMixSidebarButton(control: Method) {
    val active = AutoMixNav.active
    val modifier = Modifier
        .semantics { contentDescription = "AutoMix"; selected = active }
        .clickable { AutoMixNav.requestOpen() }
    val icon: @Composable BoxScope.() -> Unit = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AutoMixSidebarIcon()
        }
    }
    control.invoke(
        null, "AutoMix", active, null, false, modifier, null, null,
        icon, currentComposer, 0, 108
    )
}

@Composable
private fun AutoMixOverlay(plugin: MainPlugin, bridge: SidebarBridge) {
    val composer = currentComposer
    bridge.backdrop?.Observe()
    val navigator = runCatching { composer.consume(bridge.localNavigator) }.getOrNull()
    DisposableEffect(navigator, bridge) {
        val handle = navigator?.let { nav ->
            AutoMixNav.bindNavigator(
                navigator = nav,
                currentRoute = { bridge.currentRoute(nav) },
                navigate = { route -> bridge.navigate(nav, route) },
                hasHostOverlay = { bridge.hasHostOverlay(nav) },
                forwardHistory = { bridge.forwardHistory(nav) }
            )
        }
        onDispose { handle?.close() }
    }
    if (!AutoMixNav.visible || bridge.backdrop?.isMounted == true) return
    AutoMixPageMotion {
        AutoMixPage(plugin, bridge.skin(composer))
    }
}

@Composable
private fun AutoMixPage(plugin: MainPlugin, skin: Skin) {
    DisposableEffect(plugin) {
        onDispose { plugin.worker.stopPreview() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(skin.pageBackground)
            // SPW places the player bar over the page instead of reducing its measured height.
            .padding(bottom = 84.dp)
            .pointerInput(Unit) { detectTapGestures {} }
    ) {
        NativeEditor(plugin, skin, onBack = { AutoMixNav.requestReturn() })
    }
}
