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
import java.lang.reflect.Method
import java.lang.reflect.Modifier as JModifier

/** Host-composed standalone AutoMix page and navigation coordinator. */
internal object AutoMixNav {
    private class Binding(
        val navigator: Any,
        val currentRoute: () -> Any?,
        val navigate: (Any) -> Unit,
        val hasHostOverlay: () -> Boolean
    )

    var visible by mutableStateOf(false)
        private set

    private var binding: Binding? = null
    @Volatile private var pendingMarker: Any? = null

    val boundNavigator: Any? get() = binding?.navigator

    fun bindNavigator(
        navigator: Any,
        currentRoute: () -> Any?,
        navigate: (Any) -> Unit,
        hasHostOverlay: () -> Boolean
    ): AutoCloseable {
        val b = Binding(navigator, currentRoute, navigate, hasHostOverlay)
        binding = b
        return AutoCloseable {
            if (binding === b) {
                binding = null
                pendingMarker = null
            }
        }
    }

    fun open() {
        visible = true
    }

    fun close() {
        pendingMarker = null
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
            if (route != null) {
                val marker = copyHostRoute(route)
                pendingMarker = marker
                host.navigate(marker)
            } else {
                open()
            }
        } catch (_: Throwable) {
            pendingMarker = null
            open()
        }
    }

    fun consumeIfPending(arg: Any?): Boolean {
        val marker = pendingMarker ?: return false
        if (arg !== marker) return false
        pendingMarker = null
        open()
        return true
    }

    fun onExternalNavigation(arg: Any?) {
        val marker = pendingMarker
        if (marker != null && arg === marker) return
        close()
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
        check(result !== route && result == route) { "Route copy mismatch" }
        return result
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

    @Suppress("UNCHECKED_CAST")
    val local: ProvidableCompositionLocal<Any?> = owner.declaredMethods.single {
        JModifier.isStatic(it.modifiers) &&
            it.parameterCount == 0 &&
            it.returnType == ProvidableCompositionLocal::class.java
    }.invoke(null) as ProvidableCompositionLocal<Any?>

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

/** Verified SPW 1.19.3 sidebar and navigation hooks (uses afterComposable to coexist with other plugins). */
internal class SidebarBridge(loader: ClassLoader) {
    private val sidebarItemClass = "androidx.compose.ui.غ\$Ԫ"
    private val routeGetter = Class.forName(sidebarItemClass, false, loader).getMethod("Ԩ")
    val sidebarControl = Class.forName("androidx.compose.ui.ӌ", false, loader).declaredMethods.single {
        it.name == "Ϳ" && it.parameterCount == 11
    }
    private val routeAccessor = Class.forName("androidx.compose.ui.ଚ", false, loader).declaredMethods.single {
        JModifier.isStatic(it.modifiers) && it.name == "Ϳ" &&
            it.parameterCount == 3 && it.parameterTypes[0].name == sidebarItemClass
    }.also { it.trySetAccessible() }
    private val styleMasker = runCatching { SidebarStyleMasker(sidebarControl) }.getOrNull()

    private val navigatorCls = Class.forName("com.xuncorp.voxzen.ui.navigation.Navigator", false, loader)
    private val screenRouteCls = Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute", false, loader)
    private val navStackField = navigatorCls.getDeclaredField("navBackStack").apply { isAccessible = true }
    private val navNavigate = navigatorCls.getMethod("navigate", screenRouteCls)
    private val playerSheetField = navigatorCls.getDeclaredField("playerSheetState").apply { isAccessible = true }
    private val playerExpanded = Class.forName("com.xuncorp.voxzen.ui.component.PlayerSheetState", false, loader).getMethod("Ԩ")
    private val isDialogRoute = Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRouteKt", false, loader)
        .getMethod("isDialogRoute", Class.forName("androidx.navigation3.runtime.NavKey", false, loader))

    @Suppress("UNCHECKED_CAST")
    val localNavigator: CompositionLocal<Any?> = Class.forName("com.xuncorp.voxzen.ui.navigation.AppNavigationKt", false, loader)
        .getMethod("getLocalNavigator").invoke(null) as CompositionLocal<Any?>

    fun currentRoute(nav: Any): Any? = (navStackField.get(nav) as? List<*>)?.lastOrNull()

    fun navigate(nav: Any, route: Any) {
        navNavigate.invoke(nav, route)
    }

    fun hasHostOverlay(nav: Any): Boolean {
        val route = currentRoute(nav)
        val inDialog = route != null && isDialogRoute.invoke(null, route) == true
        val inPlayer = playerExpanded.invoke(playerSheetField.get(nav)) == true
        return inDialog || inPlayer
    }

    fun isMusicLibrary(item: Any?): Boolean {
        if (item == null) return false
        return runCatching {
            routeGetter.invoke(item)?.javaClass?.name == "com.xuncorp.voxzen.ui.navigation.ScreenRoute\$MusicLibrary"
        }.getOrDefault(false)
    }

    fun register(plugin: MainPlugin): List<AutoCloseable> {
        val added = mutableListOf<AutoCloseable>()
        try {
            val registrar = WorkshopApi.hookRegistrar
            val navClsName = navigatorCls.name
            val routeParam = listOf(screenRouteCls.name)

            // Close AutoMix overlay whenever the user switches to another destination.
            for ((method, params) in listOf(
                "navigate" to routeParam,
                "replace" to routeParam,
                "forward" to emptyList()
            )) {
                added += registrar.hookMethod(navClsName, method, params, object : MethodHook(90) {
                    override fun before(call: MethodHookParam) {
                        if (call.thisObject === AutoMixNav.boundNavigator) {
                            AutoMixNav.onExternalNavigation(call.args.firstOrNull())
                        }
                    }
                })
            }

            // Intercept our self-triggered marker navigation so it never pushes onto the back stack.
            added += registrar.hookMethod(navClsName, "navigate", routeParam, object : MethodHook(45) {
                override fun before(call: MethodHookParam) {
                    if (AutoMixNav.consumeIfPending(call.args.firstOrNull())) {
                        call.setResult(null)
                    }
                }
            })

            // Handle back navigation while AutoMix is open above AppNavigation.
            added += registrar.hookMethod(navClsName, "back", emptyList(), object : MethodHook(95) {
                override fun before(call: MethodHookParam) {
                    val nav = call.thisObject ?: return
                    if (AutoMixNav.consumeBack(nav)) {
                        call.setResult(null)
                    }
                }
            })

            // Render AutoMix standalone overlay inside Salt Layer via AppNavigation afterComposable (no Layer conflict).
            added += registrar.afterComposable(
                "com.xuncorp.voxzen.ui.navigation.AppNavigationKt",
                "AppNavigation",
                "androidx.compose.ui.Ɏ",
                emptyList(),
                40,
                object : ComposableHook() {
                    @Composable override fun Content(call: UiHookCall) {
                        if (plugin.running) AutoMixOverlay(plugin, this@SidebarBridge)
                    }
                }
            )

            // Inject AutoMix row right after MusicLibrary in the left sidebar.
            added += registrar.afterComposable(
                "androidx.compose.ui.ଚ",
                "Ϳ",
                "androidx.compose.ui.ଚ\$Ԯ",
                listOf(sidebarItemClass),
                20,
                object : ComposableHook() {
                    @Composable override fun Content(call: UiHookCall) {
                        if (plugin.running && isMusicLibrary(call.getArgument(0))) {
                            AutoMixSidebarButton(this@SidebarBridge)
                        }
                    }
                }
            )

            // Mask underlying host route's sidebar blue bar & highlight when AutoMix is open.
            val masker = styleMasker
            if (masker != null) {
                runCatching {
                    added += registrar.replaceComposable(
                        "androidx.compose.ui.ଚ",
                        "Ϳ",
                        "androidx.compose.ui.ଚ\$Ԯ",
                        listOf(sidebarItemClass),
                        50,
                        object : ComposableHook() {
                            @Composable override fun Content(call: UiHookCall) {
                                val item = call.getArgument(0)
                                val current = masker.local.current
                                val mask = plugin.running && AutoMixNav.visible
                                val neutral = remember(current) {
                                    if (current != null) runCatching { masker.unselected(current) }.getOrNull() else null
                                }
                                val provided = if (mask && neutral != null) {
                                    masker.local provides neutral
                                } else {
                                    masker.local provides current
                                }
                                CompositionLocalProvider(provided) {
                                    routeAccessor.invoke(null, item, currentComposer, 0)
                                }
                            }
                        }
                    )
                }
            }
            return added
        } catch (t: Throwable) {
            added.asReversed().forEach { runCatching { it.close() } }
            throw t
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
private fun AutoMixSidebarButton(bridge: SidebarBridge) {
    val active = AutoMixNav.visible
    val modifier = Modifier
        .semantics { contentDescription = "AutoMix"; selected = active }
        .clickable { AutoMixNav.requestOpen() }
    val icon: @Composable BoxScope.() -> Unit = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AutoMixSidebarIcon()
        }
    }
    bridge.sidebarControl.invoke(
        null, "AutoMix", active, null, false, modifier, null, null,
        icon, currentComposer, 0, 108
    )
}

@Composable
private fun AutoMixOverlay(plugin: MainPlugin, bridge: SidebarBridge) {
    val composer = currentComposer
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
        AutoMixPage(plugin)
    }
}

@Composable
private fun AutoMixPage(plugin: MainPlugin) {
    val skin = HostSkin.read(currentComposer)

    DisposableEffect(plugin) {
        onDispose { plugin.worker.stopPreview() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(skin.background)
            .pointerInput(Unit) { detectTapGestures {} }
    ) {
        NativeEditor(plugin, onBack = { AutoMixNav.close() })
    }
}
