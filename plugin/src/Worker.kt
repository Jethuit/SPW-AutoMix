package automix

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent

internal class Worker(
    private val cache: Path,
    private val autoPauseHost: () -> Unit = {}
) {
    private val active = mutableSetOf<Process>()
    private var clip: Clip? = null
    private var closed = false
    private var hostPlaying: Boolean? = null
    private var audition = 0L

    private data class PreviewKey(val a: String, val b: String, val plan: Plan, val window: TransitionWindow)

    private var cachedKey: PreviewKey? = null
    private var cachedWav: ByteArray? = null

    @Synchronized fun playingChanged(playing: Boolean) {
        hostPlaying = playing
        if (playing) stopPreview()
    }

    @Synchronized fun stopPreview() {
        audition++
        val old = clip
        clip = null
        old?.stop()
        old?.close()
    }

    @Synchronized fun isPlaying(): Boolean = clip?.isOpen == true

    @Synchronized fun positionSeconds(): Double? {
        val c = clip ?: return null
        return if (c.isOpen) c.microsecondPosition / 1_000_000.0 else null
    }

    @Synchronized fun seekSeconds(sec: Double): Boolean {
        val c = clip?.takeIf { it.isOpen } ?: return false
        c.seekTo(sec)
        if (!c.isRunning) c.start()
        return true
    }

    private fun Clip.seekTo(sec: Double) {
        microsecondPosition = (sec * 1_000_000.0).toLong().coerceIn(0L, (microsecondLength - 1).coerceAtLeast(0L))
    }

    @Synchronized private fun listen(wavBytes: ByteArray, key: PreviewKey, ticket: Long, position: () -> Double?) {
        check(!closed && audition == ticket) { "试听已取消" }
        stopPreview()
        val next = AudioSystem.getClip()
        try {
            AudioSystem.getAudioInputStream(ByteArrayInputStream(wavBytes)).use { next.open(it) }
            next.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP) synchronized(this) {
                    if (clip === next && next.microsecondPosition >= next.microsecondLength - 50_000L) {
                        stopPreview()
                    }
                }
            }
            position()?.let { next.seekTo(it) }
            if (hostPlaying == true) autoPauseHost()
            clip = next
            next.start()
            cachedKey = key
            cachedWav = wavBytes
        } catch (e: Exception) {
            if (clip === next) clip = null
            next.close()
            throw e
        }
    }

    suspend fun loadPlaylists(): JsonObject = runInterruptible(Dispatchers.IO) {
        synchronized(this) { check(!closed) { "插件已停用" } }
        val dir = Files.createTempDirectory("spw-automix-pl-")
        val script = dir.resolve("worker.pyz")
        val out = dir.resolve("result.json")
        val err = dir.resolve("worker.log")
        try {
            Worker::class.java.getResourceAsStream("/automix-worker.pyz")!!.use { Files.copy(it, script) }
            val process = synchronized(this) {
                check(!closed) { "插件已停用" }
                ProcessBuilder("py", "-3", "-X", "utf8", script.toString())
                    .redirectOutput(out.toFile()).redirectError(err.toFile()).start().also { active.add(it) }
            }
            try {
                process.outputStream.bufferedWriter(Charsets.UTF_8).use {
                    it.write(buildJsonObject { put("op", "playlists") }.toString())
                }
                check(process.waitFor() == 0) {
                    Files.readAllLines(err).lastOrNull()?.take(200) ?: "读取歌单失败"
                }
                Json.parseToJsonElement(Files.readString(out)).jsonObject
            } finally {
                terminate(process)
                synchronized(this) { active.remove(process) }
            }
        } finally {
            listOf(out, err, script, dir).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    suspend fun job(
        a: String,
        b: String,
        plan: Plan? = null,
        window: TransitionWindow? = null,
        position: () -> Double? = { null }
    ): JsonObject = runInterruptible(Dispatchers.IO) {
        val key = plan?.let { PreviewKey(a, b, it, requireNotNull(window)) }
        val (ticket, hitBytes) = synchronized(this) {
            check(!closed) { "插件已停用" }
            audition to cachedWav?.takeIf { key != null && cachedKey == key }
        }

        if (key != null && hitBytes != null) {
            listen(hitBytes, key, ticket, position)
            return@runInterruptible buildJsonObject { put("cached", true) }
        }

        val dir = Files.createTempDirectory("spw-automix-")
        val script = dir.resolve("worker.pyz")
        val out = dir.resolve("result.json")
        val err = dir.resolve("worker.log")
        val wav = dir.resolve("preview.wav")
        try {
            Worker::class.java.getResourceAsStream("/automix-worker.pyz")!!.use { Files.copy(it, script) }
            val process = synchronized(this) {
                check(!closed) { "插件已停用" }
                ProcessBuilder("py", "-3", "-X", "utf8", script.toString())
                    .redirectOutput(out.toFile()).redirectError(err.toFile()).start().also { active.add(it) }
            }
            try {
                process.outputStream.bufferedWriter(Charsets.UTF_8).use {
                    it.write(buildJsonObject {
                        put("a", a); put("b", b); put("op", if (key == null) "analyze" else "preview")
                        put("cache", cache.toString())
                        if (key != null) {
                            put("plan", key.plan.json())
                            put("window", key.window.json(key.plan))
                            put("output", wav.toString())
                        }
                    }.toString())
                }
                check(process.waitFor() == 0) {
                    Files.readAllLines(err).lastOrNull()?.take(200) ?: "音频处理失败"
                }
                val resJson = Json.parseToJsonElement(Files.readString(out)).jsonObject
                if (key != null) {
                    if (Thread.interrupted()) throw InterruptedException()
                    listen(Files.readAllBytes(wav), key, ticket, position)
                }
                resJson
            } finally {
                terminate(process)
                synchronized(this) { active.remove(process) }
            }
        } finally {
            listOf(wav, out, err, script, dir).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun terminate(process: Process) {
        if (process.isAlive) {
            process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
            process.destroyForcibly()
        }
    }

    @Synchronized fun stop() {
        closed = true
        cachedWav = null
        cachedKey = null
        stopPreview()
        active.forEach(::terminate)
    }
}
