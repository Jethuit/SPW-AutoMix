package automix

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val AUTO_MIX_PROJECT = "https://github.com/Jethuit/SPW-AutoMix"
internal const val AUTO_MIX_RELEASES = "$AUTO_MIX_PROJECT/releases"

internal sealed interface AutoMixReleaseCheck {
    sealed interface Published : AutoMixReleaseCheck { val tag: String }
    data class Available(override val tag: String) : Published {
        val version get() = tag.removePrefix("v")
        val releasePage get() = URI("$AUTO_MIX_RELEASES/tag/$tag")
    }
    data class Current(override val tag: String) : Published
    data object NoRelease : AutoMixReleaseCheck
    data class Failed(val message: String, val retryAt: Long = 0L) : AutoMixReleaseCheck
}

internal object AutoMixReleaseUpdate {
    private const val REQUEST_TIMEOUT_MS = 5_000
    private const val MAX_RESPONSE_BYTES = 1_048_576
    private const val MAX_REDIRECTS = 3
    private val endpoint = URI("https://api.github.com/repos/Jethuit/SPW-AutoMix/releases/latest")

    private val versionPattern = Regex(
        "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
            "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$"
    )

    fun decode(currentVersion: String, status: Int, body: String): AutoMixReleaseCheck {
        when (status) {
            404 -> return AutoMixReleaseCheck.NoRelease
            403, 429 -> return AutoMixReleaseCheck.Failed("GitHub 暂时限制了请求，请稍后重试。")
            200 -> Unit
            else -> return AutoMixReleaseCheck.Failed("GitHub 请求失败（HTTP $status）。")
        }

        val invalid = AutoMixReleaseCheck.Failed("GitHub 返回的正式版本信息无法比较。")
        val tag = try {
            val response = Json.parseToJsonElement(body).jsonObject
            val draft = response["draft"]?.jsonPrimitive?.booleanOrNull ?: return invalid
            val prerelease = response["prerelease"]?.jsonPrimitive?.booleanOrNull ?: return invalid
            if (draft || prerelease) return AutoMixReleaseCheck.NoRelease
            response["tag_name"]?.jsonPrimitive?.contentOrNull ?: return invalid
        } catch (_: IllegalArgumentException) {
            return invalid
        }
        return compareTag(currentVersion, tag)
    }

    fun compareTag(currentVersion: String, tag: String): AutoMixReleaseCheck {
        val remote = parseVersion(tag)
            ?: return AutoMixReleaseCheck.Failed("GitHub 返回的正式版本信息无法比较。")
        if (remote.groupValues[4].isNotEmpty()) return AutoMixReleaseCheck.NoRelease
        val current = parseVersion(currentVersion.trim().removeSuffix(".lite"))
            ?: return AutoMixReleaseCheck.Failed("当前安装包的版本号无法比较。")
        // The server candidate is a stable release. Equal core numbers sort above only a local prerelease.
        val order = compareValuesBy(remote, current,
            { it.groupValues[1].toBigInteger() }, { it.groupValues[2].toBigInteger() }, { it.groupValues[3].toBigInteger() })
        return if (order > 0 || (order == 0 && current.groupValues[4].isNotEmpty())) {
            AutoMixReleaseCheck.Available(tag)
        } else AutoMixReleaseCheck.Current(tag)
    }

    private fun parseVersion(version: String): MatchResult? {
        if (version.length > 256) return null
        val match = versionPattern.matchEntire(version.removePrefix("v")) ?: return null
        if (match.groupValues[4].split('.').any { it.length > 1 && it.startsWith('0') && it.all(Char::isDigit) }) return null
        return match
    }

    internal fun redirectTarget(from: URI, location: String?): URI? {
        val target = runCatching { from.resolve(location ?: return null) }.getOrNull() ?: return null
        return target.takeIf {
            it.scheme.equals("https", ignoreCase = true) && it.host.equals(endpoint.host, ignoreCase = true) &&
                it.userInfo == null && (it.port == -1 || it.port == 443) && it.fragment == null && it != from
        }
    }

    fun fetch(currentVersion: String): AutoMixReleaseCheck = try {
        requestLatest(currentVersion)
    } catch (_: SocketTimeoutException) {
        AutoMixReleaseCheck.Failed("检查更新超时，请稍后重试。")
    } catch (_: IOException) {
        AutoMixReleaseCheck.Failed("暂时无法读取 GitHub 的发布信息，请稍后重试。")
    }

    private fun requestLatest(currentVersion: String): AutoMixReleaseCheck {
        val deadline = System.nanoTime() + 10_000_000_000L
        fun timeout(): Int {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) throw SocketTimeoutException()
            return minOf(remainingMs.toInt(), REQUEST_TIMEOUT_MS)
        }
        var address = endpoint
        repeat(MAX_REDIRECTS + 1) { hop ->
            val connection = address.toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = timeout()
                connection.readTimeout = connection.connectTimeout
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "SPW-AutoMix-UpdateCheck")
                val status = connection.responseCode
                if (status in 301..303 || status == 307 || status == 308) {
                    if (hop == MAX_REDIRECTS) return AutoMixReleaseCheck.Failed("GitHub 更新地址跳转次数过多。")
                    address = redirectTarget(address, connection.getHeaderField("Location"))
                        ?: return AutoMixReleaseCheck.Failed("GitHub 返回了无效的更新地址。")
                    return@repeat
                }
                if (status == 403 || status == 429) {
                    val retrySeconds = connection.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(0, 86_400)
                    val resetSeconds = connection.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()
                    val resetAt = if (connection.getHeaderField("X-RateLimit-Remaining") == "0" &&
                        resetSeconds != null && resetSeconds in 1..Long.MAX_VALUE / 1_000) resetSeconds * 1_000 else 0L
                    val retryAt = retrySeconds?.let { System.currentTimeMillis() + it * 1_000 } ?: 0L
                    return AutoMixReleaseCheck.Failed("GitHub 暂时限制了请求，请稍后重试。", maxOf(retryAt, resetAt))
                }
                if (status != 200) return decode(currentVersion, status, "")

                val output = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        connection.readTimeout = timeout()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_RESPONSE_BYTES) throw IOException("Release response too large")
                        output.write(buffer, 0, count)
                    }
                }
                return decode(currentVersion, status, output.toString(Charsets.UTF_8))
            } finally {
                connection.disconnect()
            }
        }
        error("Unreachable redirect state")
    }
}
