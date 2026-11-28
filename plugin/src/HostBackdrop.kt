@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class,
    androidx.compose.runtime.InternalComposeApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package automix

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

/** Keeps the native viewport composed during the transition to and from AutoMix. */
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

    val isMounted: Boolean get() = mounted
    val activeBackdrop: WindowBackdrop get() = if (mounted) backdrop else WindowBackdrop.Solid

    @Composable
    fun Observe() {
        if (!mounted) return
        LaunchedEffect(this) {
            while (true) {
                val next = withContext(Dispatchers.IO) {
                    runCatching {
                        val instance = configInstance.get(null)
                        val style = (windowStyle.invoke(instance) as? Enum<*>)?.name
                        val hasWallpaper = (wallpaperPath?.invoke(instance) as? String)?.isNotBlank() == true
                        hostBackdrop(style, hasWallpaper)
                    }.getOrDefault(WindowBackdrop.Solid)
                }
                if (next != backdrop) backdrop = next
                delay(1_000L)
            }
        }
    }

    @Composable
    fun Render(modifier: Modifier, original: Any, isActive: () -> Boolean, editor: @Composable () -> Unit) {
        val content: @Composable SharedTransitionScope.() -> Unit = {
            val scope = this
            DisposableEffect(this@HostBackdrop) {
                mounted = true
                onDispose {
                    mounted = false
                    backdrop = WindowBackdrop.Solid
                }
            }
            AutoMixMotionLayers(
                isActive = isActive(),
                host = { invokeContent.invoke(original, scope, currentComposer, 0) },
                editor = editor,
            )
        }
        layout.invoke(null, modifier, content, currentComposer, 0, 0)
    }
}

internal fun materialSkin(base: Skin, backdrop: WindowBackdrop): Skin {
    if (backdrop == WindowBackdrop.Solid) return base
    val dark = base.isDark
    if (dark) {
        val controls = Color.White.copy(alpha = 0.06f)
        val surface = Color.White.copy(alpha = 0.055f)
        val page = if (backdrop == WindowBackdrop.Wallpaper) Color.Black.copy(alpha = 0.26f) else Color.Transparent
        return base.copy(
            background = controls,
            surface = surface,
            pageBackground = page,
            line = Color(0xFF9B9BA1).copy(alpha = 0.30f),
            hover = Color.White.copy(alpha = 0.09f),
            selectedControl = Color.White.copy(alpha = 0.12f)
        )
    }
    if (backdrop == WindowBackdrop.Acrylic) {
        return base.copy(
            background = Color.Black.copy(alpha = 0.045f),
            surface = Color.White.copy(alpha = 0.18f),
            pageBackground = Color.Transparent,
            line = Color(0xFF808080).copy(alpha = 0.28f),
            hover = Color.Black.copy(alpha = 0.075f),
            selectedControl = Color.White.copy(alpha = 0.65f)
        )
    }
    if (backdrop == WindowBackdrop.Mica) {
        return base.copy(
            background = base.background.copy(alpha = 0.72f),
            surface = base.surface.copy(alpha = 0.76f),
            pageBackground = Color.Transparent,
            line = Color.Black.copy(alpha = 0.10f),
            hover = Color.Black.copy(alpha = 0.06f),
            selectedControl = Color.White.copy(alpha = 0.85f)
        )
    }
    return base.copy(
        background = Color.Black.copy(alpha = 0.05f),
        surface = Color.White.copy(alpha = 0.24f),
        pageBackground = Color.White.copy(alpha = 0.17f),
        line = Color.Black.copy(alpha = 0.14f),
        hover = Color.Black.copy(alpha = 0.08f),
        selectedControl = Color.White.copy(alpha = 0.65f)
    )
}
