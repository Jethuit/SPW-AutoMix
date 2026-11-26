@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package automix

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.xuncorp.spw.workshop.api.WorkshopApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

internal class PlaylistCoverStore(
    private val fetch: (String) -> ByteArray? = { trackId ->
        WorkshopApi.library.getCoverById(trackId).toCompletableFuture().get(5, TimeUnit.SECONDS)
    }
) {
    private data class CoverKey(val trackId: String, val pixels: Int)
    private val slots = Semaphore(4)
    private val cache = object : LinkedHashMap<CoverKey, ImageBitmap?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CoverKey, ImageBitmap?>) = size > 144
    }

    suspend fun load(trackId: String, pixels: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = CoverKey(trackId, pixels.coerceIn(16, 128))
        slots.acquire()
        try {
            synchronized(cache) {
                if (cache.containsKey(key)) return@withContext cache[key]
            }
            val bytes = runInterruptible { fetch(key.trackId) }
            val bitmap = bytes?.takeIf { it.isNotEmpty() }?.let { makeThumbnail(it, key.pixels) }
            coroutineContext.ensureActive()
            synchronized(cache) { cache[key] = bitmap }
            bitmap
        } catch (_: Exception) {
            coroutineContext.ensureActive()
            null
        } catch (_: LinkageError) {
            null
        } finally {
            slots.release()
        }
    }

    private fun makeThumbnail(bytes: ByteArray, pixels: Int): ImageBitmap =
        SkiaImage.makeFromEncoded(bytes).use { image ->
            val width = image.width.toFloat()
            val height = image.height.toFloat()
            val side = minOf(width, height)
            val source = Rect.makeXYWH((width - side) / 2f, (height - side) / 2f, side, side)
            val target = Rect.makeWH(pixels.toFloat(), pixels.toFloat())
            // Drawing on a raster surface forces decoding on the IO thread at thumbnail size.
            Surface.makeRasterN32Premul(pixels, pixels).use { raster ->
                raster.canvas.drawImageRect(image, source, target, SamplingMode.MITCHELL, null, true)
                val snapshot = raster.makeImageSnapshot()
                try {
                    snapshot.toComposeImageBitmap()
                } catch (failure: Throwable) {
                    snapshot.close()
                    throw failure
                }
            }
        }
}

@Composable
internal fun PlaylistCover(trackId: String, store: PlaylistCoverStore, size: Dp) {
    val skin = LocalSkin.current
    val pixels = with(LocalDensity.current) { size.roundToPx() }
    var image by remember(trackId, store, pixels) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(trackId, store, pixels) { image = store.load(trackId, pixels) }
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.size(size).clip(shape).background(skin.surface).border(0.5.dp, skin.line, shape),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = image
        if (bitmap != null) {
            Canvas(Modifier.fillMaxSize()) {
                drawImage(bitmap, dstSize = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()),
                    filterQuality = FilterQuality.Medium)
            }
        } else {
            Canvas(Modifier.size(size * 0.48f)) {
                drawCircle(skin.muted.copy(alpha = 0.6f), style = Stroke(1.dp.toPx()))
                drawCircle(skin.muted.copy(alpha = 0.65f), radius = this.size.minDimension * 0.12f)
            }
        }
    }
}
