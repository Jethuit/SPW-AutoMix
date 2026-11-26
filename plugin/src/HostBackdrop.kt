@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class,
    androidx.compose.runtime.InternalComposeApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package automix

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal enum class WindowBackdrop { Solid, Acrylic, Mica, Wallpaper }

internal fun hostBackdrop(windowStyle: String?, hasWallpaper: Boolean): WindowBackdrop = when {
    hasWallpaper -> WindowBackdrop.Wallpaper
    windowStyle == "WindowsAcrylic" -> WindowBackdrop.Acrylic
    windowStyle == "WindowsMica" || windowStyle == "WindowsMicaAlt" -> WindowBackdrop.Mica
    else -> WindowBackdrop.Solid
}

/** The native viewport remains composed while its pixels, hit targets and focus are hidden. */
internal class HostBackdrop(symbols: HostSymbols, val site: HostUiSite) {
    private val layout = symbols.method(site.target)
    private val invokeContent = Class.forName("kotlin.jvm.functions.Function3")
        .getMethod("invoke", Any::class.java, Any::class.java, Any::class.java)
    private val config = symbols.resolve(symbols.named("com.xuncorp.voxzen.util.AppConfig"))
    private val configInstance = config.getField("INSTANCE")
    private val windowStyle = config.getMethod("getSpwWindowStyle").also { check(it.returnType.isEnum) }
    private val wallpaperPath = runCatching { config.getMethod("getWallpaperFilePath") }.getOrNull()
    private var mounted by mutableStateOf(false)
    private var backdrop by mutableStateOf(WindowBackdrop.Solid)

    val activeBackdrop: WindowBackdrop get() = if (mounted) backdrop else WindowBackdrop.Solid

    @Composable
    fun Observe() {
        if (!mounted) return
        LaunchedEffect(this) {
            while (true) {
                backdrop = withContext(Dispatchers.IO) {
                    runCatching {
                        val instance = configInstance.get(null)
                        val style = (windowStyle.invoke(instance) as? Enum<*>)?.name
                        val hasWallpaper = (wallpaperPath?.invoke(instance) as? String)?.isNotBlank() == true
                        hostBackdrop(style, hasWallpaper)
                    }.getOrDefault(WindowBackdrop.Solid)
                }
                delay(1_000L)
            }
        }
    }

    @Composable
    fun Render(modifier: Modifier, original: Any, isActive: () -> Boolean) {
        val content: @Composable SharedTransitionScope.() -> Unit = {
            val scope = this
            DisposableEffect(this@HostBackdrop) {
                mounted = true
                onDispose { mounted = false; backdrop = WindowBackdrop.Solid }
            }
            val hidden = isActive() && AutoMixNav.visible && activeBackdrop != WindowBackdrop.Solid
            val focus = LocalFocusManager.current
            LaunchedEffect(hidden) { if (hidden) focus.clearFocus(force = true) }
            Box(Modifier.fillMaxSize().then(NativePagePlacement(hidden)).onPreviewKeyEvent { hidden },
                propagateMinConstraints = true) {
                invokeContent.invoke(original, scope, currentComposer, 0)
            }
        }
        layout.invoke(null, modifier, content, currentComposer, 0, 0)
    }
}

private data class NativePagePlacement(val hidden: Boolean) : LayoutModifier {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints) =
        measurable.measure(constraints).let { child ->
            layout(child.width, child.height) { if (!hidden) child.placeRelative(0, 0) }
        }
}

internal fun materialSkin(base: Skin, backdrop: WindowBackdrop): Skin {
    if (backdrop == WindowBackdrop.Solid) return base
    val dark = base.isDark
    val controls = if (dark) Color.White.copy(alpha = 0.075f) else Color.Black.copy(alpha = 0.05f)
    val surface = when {
        dark -> Color.White.copy(alpha = 0.065f)
        backdrop == WindowBackdrop.Mica -> Color.White.copy(alpha = 0.35f)
        else -> Color.White.copy(alpha = 0.20f)
    }
    val page = when {
        backdrop != WindowBackdrop.Wallpaper -> Color.Transparent
        dark -> Color.Black.copy(alpha = 0.26f)
        else -> Color.White.copy(alpha = 0.17f)
    }
    val line = if (dark) Color.White.copy(alpha = 0.20f) else Color.Black.copy(alpha = 0.17f)
    return base.copy(background = controls, surface = surface, pageBackground = page, line = line)
}
