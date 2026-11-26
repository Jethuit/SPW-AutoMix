package automix

import kotlinx.serialization.json.*
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit

internal data class DspTrack(val profile: DspProfile, val waveform: DspWaveform)

internal class DspTrackCache(private val directory: Path, private val ffmpeg: () -> String) {
    private data class Source(val path: Path, val size: Long, val modifiedNanos: Long) {
        fun key(version: String): String {
            // Preserve Python's JSON escaping and separators when looking up an existing v4 cache.
            val quoted = JsonPrimitive(path.toString()).toString()
            val escaped = buildString {
                for (char in quoted) {
                    if (char < '\u007f') append(char)
                    else append("\\u").append(char.code.toString(16).padStart(4, '0'))
                }
            }
            val id = "[$escaped, $size, $modifiedNanos, \"$version\"]"
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)))
        }
    }

    private data class Paths(val profile: Path, val waveform: Path)

    private fun source(path: String): Source {
        val original = Path.of(path).toRealPath()
        val attrs = Files.readAttributes(original, BasicFileAttributes::class.java)
        require(attrs.isRegularFile) { "找不到可分析的音频文件" }
        return Source(original, attrs.size(), attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS))
    }

    private fun paths(source: Source, version: String = "analysis-wave-v5"): Paths {
        val key = source.key(version)
        return Paths(directory.resolve("$key.profile.json"), directory.resolve("$key.json"))
    }

    fun profile(path: String): DspProfile {
        val track = source(path)
        return cachedProfile(track) ?: analyze(track)
    }

    fun cachedProfile(path: String): DspProfile? {
        val track = try {
            source(path)
        } catch (_: IOException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        return cachedProfile(track)
    }

    private fun cachedProfile(track: Source): DspProfile? {
        val current = paths(track)
        val previous = paths(track, "analysis-wave-v4")
        for (entry in listOf(current, previous)) {
            readProfile(readJson(entry.profile, PROFILE_CACHE_BYTES))?.let { return it }
            readProfile(readJson(entry.waveform, WAVEFORM_CACHE_BYTES)?.get("analysis") as? JsonObject)?.let { return it }
        }
        return null
    }

    fun track(path: String): DspTrack {
        val track = source(path)
        val target = paths(track)
        val previous = paths(track, "analysis-wave-v4")
        for (entry in listOf(target, previous)) {
            val json = readJson(entry.waveform, WAVEFORM_CACHE_BYTES) ?: continue
            val profile = readProfile(json["analysis"] as? JsonObject) ?: continue
            val wave = readWaveform(json) ?: continue
            return DspTrack(profile, wave)
        }
        val profile = cachedProfile(track) ?: analyze(track)
        val waveform = DspAnalysis.waveform(DspPcm.read(ffmpeg(), track.path.toString(), 16_000, 1))
        require(waveform.peaks.isNotEmpty()) { "未解码出可分析的波形" }
        write(track, target.waveform, buildJsonObject {
            put("analysis", profile.json())
            put("peaks", waveform.peaks.json())
            put("low", waveform.low.json())
            put("mid", waveform.mid.json())
            put("high", waveform.high.json())
        })
        return DspTrack(profile, waveform)
    }

    private fun analyze(source: Source): DspProfile {
        val profile = DspAnalysis.profile(DspPcm.read(ffmpeg(), source.path.toString(), 22_050, 1))
        require(profile.duration > 0.0) { "未解码出可分析的音频" }
        write(source, paths(source).profile, profile.json())
        return profile
    }

    private fun write(track: Source, path: Path, value: JsonObject) {
        if (Thread.interrupted()) throw InterruptedException()
        require(source(track.path.toString()) == track) { "分析期间音频文件已改变" }
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, "analysis-", ".tmp")
        try {
            Files.writeString(temporary, value.toString())
            try {
                Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun readJson(path: Path, maxBytes: Int): JsonObject? {
        return try {
            checkDspInterrupted()
            if (Files.size(path) > maxBytes) return null
            val data = Files.newInputStream(path).use { it.readNBytes(maxBytes + 1) }
            if (data.size > maxBytes) return null
            checkDspInterrupted()
            Json.parseToJsonElement(data.toString(Charsets.UTF_8)) as? JsonObject
        } catch (_: IOException) {
            checkDspInterrupted()
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun readProfile(json: JsonObject?): DspProfile? {
        if (json == null) return null
        return try {
            fun number(key: String) = json.getValue(key).jsonPrimitive.double.also { require(it.isFinite()) }
            fun doubles(key: String, maxSize: Int): DoubleArray {
                val array = json.getValue(key).jsonArray
                require(array.size <= maxSize)
                return DoubleArray(array.size) { index ->
                    array[index].jsonPrimitive.double.also { require(it.isFinite()) }
                }
            }
            val duration = number("duration").takeIf { it > 0.0 && it <= MAX_CACHED_PROFILE_SECONDS } ?: return null
            val bpm = number("bpm").takeIf { it in 30.0..300.0 } ?: return null
            val key = json["key"] as? JsonObject
            DspProfile(
                duration, bpm, number("bpm_confidence"), doubles("beats", MAX_CACHED_PROFILE_SECONDS * 5 + 1),
                doubles("downbeats", MAX_CACHED_PROFILE_SECONDS * 5 / 4 + 1),
                key?.get("name")?.jsonPrimitive?.contentOrNull,
                key?.get("camelot")?.jsonPrimitive?.contentOrNull,
                number("lufs"), doubles("lufs_2s", MAX_CACHED_PROFILE_SECONDS / 2 + 1),
                doubles("low_ratio_1s", MAX_CACHED_PROFILE_SECONDS + 1),
                number("low_ratio_head"), number("low_ratio_tail")
            )
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: NoSuchElementException) {
            null
        }
    }

    private fun readWaveform(json: JsonObject): DspWaveform? {
        fun ints(key: String): IntArray? {
            val array = json[key] as? JsonArray ?: return null
            if (array.size > MAX_CACHED_WAVEFORM_BUCKETS) return null
            val values = IntArray(array.size)
            for ((i, item) in array.withIndex()) {
                values[i] = (item as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..255 } ?: return null
            }
            return values
        }
        val peaks = ints("peaks")?.takeIf { it.isNotEmpty() } ?: return null
        val low = ints("low") ?: return null
        val mid = ints("mid") ?: return null
        val high = ints("high") ?: return null
        if (low.size != peaks.size || mid.size != peaks.size || high.size != peaks.size) return null
        return DspWaveform(peaks, low, mid, high)
    }

    private fun IntArray.json() = JsonArray(map(::JsonPrimitive))
    private fun DoubleArray.json() = JsonArray(map(::JsonPrimitive))

    private fun DspProfile.json() = buildJsonObject {
        put("duration", duration)
        put("bpm", bpm)
        put("bpm_confidence", bpmConfidence)
        put("beats", beats.json())
        put("downbeats", downbeats.json())
        put("key", if (keyName == null || camelot == null) JsonNull else buildJsonObject {
            put("name", keyName)
            put("camelot", camelot)
        })
        put("lufs", lufs)
        put("lufs_2s", lufs2s.json())
        put("low_ratio_1s", lowRatio1s.json())
        put("low_ratio_head", lowRatioHead)
        put("low_ratio_tail", lowRatioTail)
    }

    private companion object {
        const val PROFILE_CACHE_BYTES = 512 * 1024
        const val WAVEFORM_CACHE_BYTES = 2 * 1024 * 1024
        // Retain valid larger legacy entries while bounding parsed arrays before reusing them.
        const val MAX_CACHED_PROFILE_SECONDS = 2 * 60 * 60
        const val MAX_CACHED_WAVEFORM_BUCKETS = 2 * DspPcm.TRACK_LIMIT_SECONDS * 50
    }
}
