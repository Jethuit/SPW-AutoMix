package automix

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

internal class AudioRuntimeException(message: String, cause: IOException) : IOException(message, cause)

internal object DspPcm {
    const val TRACK_LIMIT_SECONDS = 15 * 60
    private const val MAX_PCM_BYTES = TRACK_LIMIT_SECONDS * 22050 * Float.SIZE_BYTES
    private const val BLOCK_BYTES = 64 * 1024

    private data class Decoded(val samples: FloatArray, val completeClip: Boolean = false)

    fun read(
        ffmpeg: String,
        path: String,
        rate: Int,
        channels: Int,
        startFrame: Long = 0,
        frames: Int? = null
    ): FloatArray {
        require(rate > 0 && channels > 0 && startFrame >= 0 && (frames == null || frames >= 0))
        if (frames == 0) return FloatArray(0)
        val frameBytes = Math.multiplyExact(channels, Float.SIZE_BYTES)
        val memoryLimit = MAX_PCM_BYTES / frameBytes
        require(memoryLimit > 0 && (frames == null || frames <= memoryLimit)) { "PCM 解码范围超出内存上限" }
        val trackLimit = minOf(rate.toLong() * TRACK_LIMIT_SECONDS, memoryLimit.toLong()).toInt()
        checkDspInterrupted()
        fun timestamp(frame: Long) = String.format(Locale.ROOT, "%.9f", frame.toDouble() / rate)
        val command = buildList {
            addAll(listOf(ffmpeg, "-nostdin", "-v", "error"))
            if (startFrame > 0) addAll(listOf("-ss", timestamp(startFrame)))
            addAll(listOf("-i", path, "-map", "0:a:0"))
            if (frames != null) addAll(listOf("-t", timestamp(frames.toLong())))
            addAll(listOf("-ac", channels.toString(), "-ar", rate.toString(), "-f", "f32le", "pipe:1"))
        }
        val process = try {
            ProcessBuilder(command).start()
        } catch (e: IOException) {
            checkDspInterrupted()
            throw AudioRuntimeException("无法启动 FFmpeg · ${e.message}", e)
        }
        val pcm = FutureTask {
            val stream = process.inputStream
            var completeClip = false
            try {
                if (frames == null) {
                    Decoded(stream.readTrack(trackLimit, frameBytes, rate))
                } else {
                    val values = FloatArray(Math.multiplyExact(frames, channels))
                    val count = stream.readSamples(values, frames, frameBytes)
                    completeClip = count == frames
                    Decoded(if (completeClip) values else values.copyOf(count * channels), completeClip)
                }
            } finally {
                // Keep a complete clip's pipe open until FFmpeg exits or we stop it. A premature close can hide a real failure.
                if (!completeClip) stream.close()
            }
        }
        val stderr = FutureTask {
            process.errorStream.use {
                val first = it.readNBytes(4096)
                it.transferTo(OutputStream.nullOutputStream())
                first.toString(Charsets.UTF_8).trim().take(240)
            }
        }
        try {
            checkDspInterrupted()
            Thread.startVirtualThread(pcm)
            Thread.startVirtualThread(stderr)
            val decoded = pcm.get()
            val stoppedAfterClip = decoded.completeClip && !process.waitFor(200, TimeUnit.MILLISECONDS)
            if (stoppedAfterClip) {
                // The requested PCM is complete. An encoder that keeps writing must not delay the audition.
                stopProcess(process)
            }
            val exitCode = process.waitFor()
            val diagnostic = stderr.get()
            val failed = if (stoppedAfterClip) diagnostic.isNotEmpty() else exitCode != 0
            if (failed) {
                throw IOException(diagnostic.ifEmpty { "FFmpeg 无法解码音频" })
            }
            return decoded.samples
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            if (cause is InterruptedException) throw cause
            if (cause is Error) throw cause
            throw IOException(cause.message ?: "读取 PCM 数据失败", cause)
        } finally {
            val pcmDone = pcm.isDone
            pcm.cancel(true)
            stderr.cancel(true)
            if (process.isAlive) stopProcess(process)
            if (pcmDone) runCatching { process.inputStream.close() }
        }
    }

    private fun stopProcess(process: Process) {
        // A system-runtime launcher can inherit the decoder's stdout/stderr in a child process.
        runCatching { process.descendants().use { children -> children.forEach { it.destroyForcibly() } } }
        process.destroyForcibly()
    }

    private fun InputStream.readTrack(limit: Int, frameBytes: Int, rate: Int): FloatArray {
        // The file is owned by the decode task. Keep one PCM allocation on SPW's heap, even for a long track.
        val temp = Files.createTempFile("automix-pcm-", ".raw")
        try {
            val frames = Files.newOutputStream(temp).use { output ->
                readFrameBlocks(limit, frameBytes) { bytes, count -> output.write(bytes, 0, count) }
            }
            if (frames == limit) {
                val overflow = readNBytes(frameBytes).size
                if (overflow in 1 until frameBytes) throw IOException("FFmpeg 返回了不完整的 PCM 帧")
                if (overflow == frameBytes) {
                    val duration = if (limit.toLong() == rate.toLong() * TRACK_LIMIT_SECONDS)
                        "${TRACK_LIMIT_SECONDS / 60} 分钟" else "${limit / rate} 秒"
                    throw IOException("音频超过完整分析上限（$duration）")
                }
            }
            checkDspInterrupted()
            val samples = FloatArray(frames * (frameBytes / Float.SIZE_BYTES))
            Files.newInputStream(temp).use {
                if (it.readSamples(samples, frames, frameBytes) != frames) throw IOException("PCM 临时文件不完整")
            }
            return samples
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun InputStream.readSamples(samples: FloatArray, limit: Int, frameBytes: Int): Int {
        var offset = 0
        return readFrameBlocks(limit, frameBytes) { bytes, count ->
            val length = count / Float.SIZE_BYTES
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples, offset, length)
            offset += length
        }
    }

    private inline fun InputStream.readFrameBlocks(
        limit: Int,
        frameBytes: Int,
        consume: (ByteArray, Int) -> Unit
    ): Int {
        val blockFrames = maxOf(1, BLOCK_BYTES / frameBytes)
        val bytes = ByteArray(blockFrames * frameBytes)
        var frames = 0
        while (frames < limit) {
            checkDspInterrupted()
            val count = readNBytes(bytes, 0, minOf(blockFrames, limit - frames) * frameBytes)
            if (count == 0) break
            if (count % frameBytes != 0) throw IOException("FFmpeg 返回了不完整的 PCM 帧")
            consume(bytes, count)
            frames += count / frameBytes
        }
        return frames
    }
}
