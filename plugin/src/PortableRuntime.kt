package automix

import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipInputStream

internal object PortableRuntime {
    private const val executable = "ffmpeg.exe"
    private val bundleId by lazy {
        val id = resource("automix-runtime.sha256").bufferedReader().use { it.readText().trim() }
        check(id.matches(Regex("[0-9a-f]{64}"))) { "内置音频运行环境缺少有效的校验值" }
        id
    }

    @Synchronized
    fun prepare(directory: Path): Path {
        val root = directory.toAbsolutePath().normalize()
        val runtime = root.resolve(bundleId)
        if (ready(runtime)) return runtime

        Files.createDirectories(root)
        FileChannel.open(root.resolve("install.lock"), CREATE, WRITE).use { channel ->
            channel.lock().use {
                if (ready(runtime)) return runtime
                val staging = Files.createTempDirectory(root, "install-")
                try {
                    unpack(staging)
                    check(Files.isRegularFile(staging.resolve(executable))) { "内置 FFmpeg 文件不完整" }
                    Files.createFile(staging.resolve(".ready"))
                    deleteDirectory(runtime)
                    try {
                        Files.move(staging, runtime, ATOMIC_MOVE)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(staging, runtime)
                    }
                } finally {
                    deleteDirectory(staging)
                }
            }
        }
        return runtime
    }

    private fun ready(directory: Path): Boolean =
        Files.isDirectory(directory, NOFOLLOW_LINKS) && Files.isRegularFile(directory.resolve(".ready")) &&
            Files.isRegularFile(directory.resolve(executable))

    private fun resource(name: String) = checkNotNull(PortableRuntime::class.java.getResourceAsStream("/$name")) {
        "安装包缺少内置运行环境资源 · $name"
    }

    private fun unpack(directory: Path) {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(resource("automix-runtime.zip").buffered(), digest).use { input ->
            ZipInputStream(input).use { archive ->
                while (true) {
                    val entry = archive.nextEntry ?: break
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val target = directory.resolve(entry.name).normalize()
                    check(target != directory && target.startsWith(directory)) { "内置运行环境包含无效的文件路径" }
                    if (entry.isDirectory) {
                        Files.createDirectories(target)
                    } else {
                        Files.createDirectories(target.parent)
                        Files.copy(archive, target)
                    }
                    archive.closeEntry()
                }
                input.transferTo(OutputStream.nullOutputStream())
            }
        }
        check(HexFormat.of().formatHex(digest.digest()) == bundleId) { "内置音频运行环境校验失败，请重新导入安装包" }
    }

    private fun deleteDirectory(directory: Path) {
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
