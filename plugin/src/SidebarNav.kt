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
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier as JModifier
import javax.swing.UIManager

/** Host-composed standalone AutoMix page and navigation coordinator. */
internal object AutoMixNav {
    private class Binding(
        val navigator: Any,
        val currentRoute: () -> Any?,
        val navigate: (Any) -> Unit,
        val hasHostOverlay: () -> Boolean
    )
    private class OpenRequest(val host: Binding, val route: Any, val marker: Any)

    var visible by mutableStateOf(false)
        private set

    private var binding: Binding? = null
    private var pending: OpenRequest? = null
    private val issuedMarkers = mutableListOf<WeakReference<Any>>()

    fun bindNavigator(
        navigator: Any,
        currentRoute: () -> Any?,
        navigate: (Any) -> Unit,
        hasHostOverlay: () -> Boolean
    ): AutoCloseable {
        val b = Binding(navigator, currentRoute, navigate, hasHostOverlay)
        binding = b
        pending = null
        return AutoCloseable {
            if (binding === b) {
                binding = null
                pending = null
            }
        }
    }

    fun close() {
        pending = null
        visible = false
    }

    fun requestOpen() {
        if (visible) return
        val host = binding
        if (host == null) {
            WorkshopApi.ui.toast("AutoMix 页面导航尚未准备好，请稍后重试", WorkshopApi.Ui.ToastType.Warning)
            return
        }
        try {
            val route = host.currentRoute()
            if (route == null) {
                visible = true
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

    fun beforeNavigation(navigator: Any?, destination: Any?, isNavigate: Boolean): Boolean {
        if (isNavigate && destination != null && issuedMarkers.any { it.get() === destination }) {
            val request = pending
            if (request?.matches(navigator, destination) == true) return false
            // Another plugin can replay a canceled request after an asynchronous leave guard.
            if (request?.marker === destination) pending = null
            return true
        }
        if (navigator != null && navigator === binding?.navigator) close()
        return false
    }

    fun afterNavigate(navigator: Any?, destination: Any?) {
        val request = pending ?: return
        if (!request.matches(navigator, destination)) return
        pending = null
        visible = true
    }

    fun consumeBack(navigator: Any): Boolean {
        if (!visible) return false
        val host = binding
        if (host != null && (host.navigator !== navigator || host.hasHostOverlay())) return false
        close()
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

internal class AutoMixNavigationHook(private val isNavigate: Boolean, private val isActive: () -> Boolean) : MethodHook(90) {
    override fun before(call: MethodHookParam) {
        if (isActive() && AutoMixNav.beforeNavigation(call.thisObject, call.args.firstOrNull(), isNavigate)) {
            call.setResult(null)
        }
    }

    override fun after(call: MethodHookParam) {
        // The host returns early for this equal route. Other plugins still see an accepted call.
        if (isNavigate && isActive() && !call.isOriginalSkipped && call.throwable == null) {
            AutoMixNav.afterNavigate(call.thisObject, call.args.firstOrNull())
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

    fun register(plugin: MainPlugin, isActive: () -> Boolean): List<AutoCloseable> {
        val added = mutableListOf<AutoCloseable>()
        try {
            val registrar = WorkshopApi.hookRegistrar
            for (method in listOf(navigation.navigateMethod, navigation.replaceMethod, navigation.forwardMethod)) {
                added += registrar.hookMethod(method.declaringClass.name, method.name,
                    method.parameterTypes.map { it.name }, AutoMixNavigationHook(method == navigation.navigateMethod, isActive))
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
                                bridge.Render(call.getArgument(0) as Modifier, requireNotNull(call.getArgument(1)), isActive)
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
                                val mask = isActive() && AutoMixNav.visible
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
    val active = AutoMixNav.visible
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
                hasHostOverlay = { bridge.hasHostOverlay(nav) }
            )
        }
        onDispose { handle?.close() }
    }
    if (!AutoMixNav.visible) return
    Box(Modifier.fillMaxSize()) {
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
        NativeEditor(plugin, skin, onBack = { AutoMixNav.close() })
    }
}
