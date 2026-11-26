package automix

import net.bytebuddy.jar.asm.*
import java.lang.invoke.MethodType
import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

internal const val PLAYBACK_CONTROLLER = "com.xuncorp.voxzen.service.PlaybackController"

internal fun <T> List<T>.uniqueHost(role: String): T {
    check(size == 1) { "$role · 需要唯一匹配，找到 ${size} 项" }
    return single()
}

internal data class HostSymbol(val owner: String, val name: String, val descriptor: String) {
    val className: String get() = owner.replace('/', '.')
    val arguments: List<String> get() = Type.getArgumentTypes(descriptor).map { it.descriptor }
    val returns: String get() = Type.getReturnType(descriptor).descriptor
}

internal class HostMethod(
    val symbol: HostSymbol,
    val access: Int,
    val signature: String?
) {
    val isStatic: Boolean get() = access and Opcodes.ACC_STATIC != 0
    val isPublic: Boolean get() = access and Opcodes.ACC_PUBLIC != 0
    val calls = mutableListOf<HostSymbol>()
    val reads = mutableListOf<HostSymbol>()
    val writes = mutableListOf<HostSymbol>()
    val strings = mutableSetOf<String>()
}

internal class HostType(val owner: String) {
    val descriptor: String get() = "L$owner;"
    val original = mutableListOf<String>()
    val methods = mutableListOf<HostMethod>()
    val fields = mutableMapOf<HostSymbol, String?>()

    fun method(role: String, matches: (HostMethod) -> Boolean): HostMethod =
        methods.filter(matches).uniqueHost("$role ($owner)")

    fun field(role: String, matches: (HostSymbol, String?) -> Boolean): HostSymbol =
        fields.filter { (key, signature) -> matches(key, signature) }.keys.toList().uniqueHost("$role ($owner)")

    /** The Kotlin data-class string recipe identifies otherwise identical field types. */
    fun dataField(label: String, descriptor: String): HostSymbol {
        val text = method("toString") { it.symbol.name == "toString" && it.symbol.descriptor == "()Ljava/lang/String;" }
        val recipe = text.strings.filter { it.contains("=$CONCAT_ARGUMENT") }.uniqueHost("数据字段描述 ($owner)")
        val names = FIELD_LABEL.findAll(recipe).map { it.groupValues[1] }.toList()
        val reads = text.reads.filter { it.owner == owner }
        check(names.size == reads.size && names.distinct().size == names.size) { "数据字段顺序变更 ($owner)" }
        return reads.getOrNull(names.indexOf(label))?.takeIf { it.descriptor == descriptor }
            ?: error("宿主字段缺失 · $label ($owner)")
    }

    /** Used for validated class layouts that assign the constructor properties in metadata order. */
    fun constructorProperties(vararg properties: String): Map<String, HostSymbol> {
        val metadataNames = original.drop(1).takeWhile { it != "<init>" }.filter { PROPERTY.matches(it) }
        check(metadataNames == properties.toList()) { "宿主构造器属性已变化 ($owner)" }
        val constructor = method("宿主构造器") {
            it.symbol.name == "<init>" && it.writes.count { field -> field.owner == owner } == properties.size
        }
        val fields = constructor.writes.filter { it.owner == owner }
        check(fields.distinct().size == fields.size) { "宿主构造器字段重复 ($owner)" }
        return properties.zip(fields).toMap()
    }

    companion object {
        private const val CONCAT_ARGUMENT = '\u0001'
        private val FIELD_LABEL = Regex("(?:\\(|, )([A-Za-z][A-Za-z0-9_]*)=$CONCAT_ARGUMENT")
        private val PROPERTY = Regex("[A-Za-z][A-Za-z0-9_]*")
    }
}

/** Read only the host class files and resolve members without initializing host services. */
internal class HostSymbols private constructor(
    private val loader: ClassLoader,
    val types: Map<String, HostType>
) {
    private val byOriginal = types.values.filter { it.original.firstOrNull()?.startsWith("L") == true }
        .groupBy { it.original.first() }
    val methods = types.values.flatMap { it.methods }

    fun named(name: String): HostType = byOriginal["L${name.replace('.', '/')};"].orEmpty().uniqueHost(name)
    fun actual(owner: String): HostType = checkNotNull(types[owner]) { "宿主字节码缺失 · $owner" }
    fun resolve(type: HostType): Class<*> = Class.forName(type.owner.replace('/', '.'), false, loader)
    fun body(symbol: HostSymbol): HostMethod? = types[symbol.owner]?.methods?.firstOrNull { it.symbol == symbol }

    fun method(symbol: HostSymbol) = MethodType.fromMethodDescriptorString(symbol.descriptor, loader).let { signature ->
        Class.forName(symbol.className, false, loader).getDeclaredMethod(symbol.name, *signature.parameterArray()).also {
            check(it.returnType == signature.returnType() && it.trySetAccessible()) { "宿主方法不可用 · $symbol" }
        }
    }

    fun field(symbol: HostSymbol) = Class.forName(symbol.className, false, loader).getDeclaredField(symbol.name).also {
        check(it.type.descriptorString() == symbol.descriptor && it.trySetAccessible()) { "宿主字段不可用 · $symbol" }
    }

    fun constructor(type: HostType, vararg arguments: String) = resolve(type).declaredConstructors.filter {
        it.parameterTypes.map(Class<*>::descriptorString) == arguments.toList()
    }.uniqueHost("宿主构造器 (${type.owner})").also { check(it.trySetAccessible()) }

    companion object {
        fun source(loader: ClassLoader): Path {
            val host = Class.forName(PLAYBACK_CONTROLLER, false, loader)
            val location = host.protectionDomain?.codeSource?.location
            if (location?.protocol == "file") return Path.of(location.toURI())
            val name = host.name.replace('.', '/') + ".class"
            val resource = host.getResource("/$name") ?: error("找不到宿主字节码")
            if (resource.protocol == "jar") {
                val connection = resource.openConnection() as JarURLConnection
                connection.useCaches = false
                if (connection.jarFileURL.protocol == "file") return Path.of(connection.jarFileURL.toURI())
            }
            if (resource.protocol == "file") {
                var root = Path.of(resource.toURI())
                repeat(name.count { it == '/' } + 1) { root = root.parent }
                return root
            }
            error("无法读取宿主字节码 · ${resource.protocol}")
        }

        fun read(source: Path, loader: ClassLoader): HostSymbols {
            val types = mutableMapOf<String, HostType>()
            fun relevant(name: String) = name.endsWith(".class") &&
                (name.startsWith("androidx/compose/ui/") || name.startsWith("com/xuncorp/") ||
                    name.startsWith("com/moriafly/salt/ui/"))
            fun parse(bytes: ByteArray) {
                check(!Thread.currentThread().isInterrupted) { "宿主适配已取消" }
                val pool = bytes.toString(Charsets.ISO_8859_1)
                if (MARKERS.none { it in pool }) return
                ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                    private lateinit var type: HostType
                    override fun visit(version: Int, access: Int, name: String, signature: String?,
                                       superName: String?, interfaces: Array<out String>?) {
                        type = HostType(name)
                        types[name] = type
                    }

                    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                        if (descriptor != "Lkotlin/Metadata;") return null
                        return object : AnnotationVisitor(Opcodes.ASM9) {
                            override fun visitArray(name: String): AnnotationVisitor? {
                                if (name != "d2") return null
                                return object : AnnotationVisitor(Opcodes.ASM9) {
                                    override fun visit(name: String?, value: Any) {
                                        type.original += value as String
                                    }
                                }
                            }
                        }
                    }

                    override fun visitField(access: Int, name: String, descriptor: String,
                                            signature: String?, value: Any?): FieldVisitor? {
                        type.fields[HostSymbol(type.owner, name, descriptor)] = signature
                        return null
                    }

                    override fun visitMethod(access: Int, name: String, descriptor: String,
                                             signature: String?, exceptions: Array<out String>?): MethodVisitor {
                        val method = HostMethod(HostSymbol(type.owner, name, descriptor), access, signature)
                        type.methods += method
                        return object : MethodVisitor(Opcodes.ASM9) {
                            override fun visitMethodInsn(opcode: Int, owner: String, name: String,
                                                         descriptor: String, isInterface: Boolean) {
                                method.calls += HostSymbol(owner, name, descriptor)
                            }
                            override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                                val field = HostSymbol(owner, name, descriptor)
                                if (opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC) method.writes += field
                                else method.reads += field
                            }
                            override fun visitLdcInsn(value: Any) {
                                if (value is String) method.strings += value
                            }
                            override fun visitInvokeDynamicInsn(name: String, descriptor: String, handle: Handle,
                                                                vararg arguments: Any) {
                                if (handle.owner == "java/lang/invoke/StringConcatFactory") {
                                    method.strings += arguments.filterIsInstance<String>()
                                }
                            }
                        }
                    }
                }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            }
            if (Files.isDirectory(source)) {
                Files.walk(source).use { paths ->
                    paths.filter { relevant(source.relativize(it).toString().replace('\\', '/')) }
                        .forEach { parse(Files.readAllBytes(it)) }
                }
            } else JarFile(source.toFile()).use { archive ->
                for (entry in archive.entries()) {
                    check(!Thread.currentThread().isInterrupted) { "宿主适配已取消" }
                    if (relevant(entry.name)) archive.getInputStream(entry).use { parse(it.readBytes()) }
                }
            }
            return HostSymbols(loader, types)
        }

        private val MARKERS = listOf(
            "androidx/compose/runtime/", "androidx/navigation3/runtime/", "com/xuncorp/spc/morvanium/automix/",
            "com/xuncorp/spc/core/queue/", "PiscesPlayer;", "PiscesMediaItem;", "PlaylistRepo;",
            "PlaylistRepo\$Companion;", "PlaylistTrackOrderUpdate;", "TrackSort;", "com/moriafly/salt/ui/Salt",
            "com/xuncorp/voxzen/data/entity/Playlist;", "com/xuncorp/voxzen/data/AppDatabase;"
        )
    }
}
