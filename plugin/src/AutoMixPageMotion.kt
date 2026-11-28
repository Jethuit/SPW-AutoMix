package automix

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.zIndex
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val PageOffsetPx = 150f
private const val PageSpringStiffness = 500f

internal val LocalAutoMixPageClosing = staticCompositionLocalOf { false }
internal val LocalAutoMixPageEntering = staticCompositionLocalOf { false }

// Animatable resets velocity when interrupted, including when reopening during a return.
private class PageVelocity {
    var offset = 0f
    var opacity = 0f
    var hostOpacity = 0f
}

@Composable
internal fun AutoMixPageMotion(
    hostFade: Animatable<Float, AnimationVector1D>? = null,
    content: @Composable () -> Unit,
) {
    if (!AutoMixNav.visible) return
    val closing = AutoMixNav.closing
    val navigating = closing && AutoMixNav.exitMotion == AutoMixExitMotion.Navigate
    val closeTicket = AutoMixNav.closeTicket
    val offset = remember { Animatable(PageOffsetPx) }
    val opacity = remember { Animatable(1f) }
    val velocity = remember { PageVelocity() }
    var entering by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        val presenter = AutoMixNav.attachPresenter()
        onDispose { presenter.close() }
    }

    LaunchedEffect(closing, navigating, closeTicket, hostFade) {
        if (closing) entering = true
        coroutineScope {
            launch {
                if (navigating) {
                    offset.snapTo(offset.value)
                } else {
                    offset.animateTo(
                        if (closing) PageOffsetPx else 0f,
                        spring(dampingRatio = 1f, stiffness = PageSpringStiffness, visibilityThreshold = 0.5f),
                        initialVelocity = velocity.offset,
                    ) { velocity.offset = this.velocity }
                }
                velocity.offset = 0f
            }
            launch {
                opacity.animateTo(
                    if (closing) 0f else 1f,
                    spring(dampingRatio = 1f, stiffness = PageSpringStiffness),
                    initialVelocity = velocity.opacity,
                ) { velocity.opacity = this.velocity }
                velocity.opacity = 0f
            }
            launch {
                hostFade?.animateTo(
                    if (closing) 1f else 0f,
                    spring(dampingRatio = 1f, stiffness = PageSpringStiffness),
                    initialVelocity = velocity.hostOpacity,
                ) { velocity.hostOpacity = this.velocity }
                velocity.hostOpacity = 0f
            }
        }
        if (closing) AutoMixNav.finishClose(closeTicket) else entering = false
    }

    Box(
        Modifier.fillMaxSize().clipToBounds().blockPageInput(closing).graphicsLayer {
            alpha = opacity.value.coerceIn(0f, 1f)
            translationY = offset.value
        },
    ) {
        CompositionLocalProvider(
            LocalAutoMixPageClosing provides closing,
            LocalAutoMixPageEntering provides entering,
            content = content,
        )
    }
}

@Composable
internal fun AutoMixMotionLayers(
    isActive: Boolean,
    host: @Composable () -> Unit,
    editor: @Composable () -> Unit,
) {
    val shown = isActive && AutoMixNav.visible
    val navigating = shown && AutoMixNav.closing && AutoMixNav.exitMotion == AutoMixExitMotion.Navigate
    val hostFade = if (shown) remember { Animatable(1f) } else null
    val hideHost by remember(hostFade, navigating) { derivedStateOf { !navigating && hostFade?.value == 0f } }
    val hostOffset = remember(AutoMixNav.navigationTicket) {
        Animatable(if (navigating && !AutoMixNav.nativeNavigation) PageOffsetPx else 0f)
    }
    val focus = LocalFocusManager.current
    LaunchedEffect(shown) { if (shown) focus.clearFocus(force = true) }
    LaunchedEffect(hostOffset) {
        if (hostOffset.value != 0f) {
            hostOffset.animateTo(0f, spring(dampingRatio = 1f, stiffness = PageSpringStiffness, visibilityThreshold = 0.5f))
        }
    }

    Box(Modifier.fillMaxSize().clipToBounds(), propagateMinConstraints = true) {
        Box(
            Modifier.fillMaxSize().zIndex(if (navigating) 1f else 0f)
                .then(NativePagePlacement(hideHost)).blockPageInput(shown).graphicsLayer {
                    alpha = hostFade?.value?.coerceIn(0f, 1f) ?: 1f
                    translationY = hostOffset.value
                },
            propagateMinConstraints = true,
        ) { host() }
        if (shown) AutoMixPageMotion(hostFade, editor)
    }
}

private fun Modifier.blockPageInput(blocked: Boolean): Modifier {
    if (!blocked) return this
    return onPreviewKeyEvent { true }.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
    }
}

private data class NativePagePlacement(val hidden: Boolean) : LayoutModifier {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints) =
        measurable.measure(constraints).let { child ->
            layout(child.width, child.height) { if (!hidden) child.placeRelative(0, 0) }
        }
}
