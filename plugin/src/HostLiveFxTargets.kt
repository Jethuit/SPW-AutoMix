package automix

import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.lang.reflect.Method

internal class HostLiveFxTargets(symbols: HostSymbols) {
    val lowPassParameters: Method

    init {
        val engine = symbols.named("com.xuncorp.pisces.automix.AutomixExecutionEngine")
        val candidates = mutableListOf<HostSymbol>()
        val bytecode = checkNotNull(symbols.resolve(engine).getResourceAsStream("/${engine.owner}.class")) {
            "无法读取宿主滤波参数实现"
        }
        bytecode.use {
            ClassReader(it).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?
                ): MethodVisitor? {
                    val privateStatic = Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC
                    if (access and privateStatic != privateStatic || descriptor != "(D)Ljava/lang/Object;") return null
                    return object : MethodVisitor(Opcodes.ASM9) {
                        private val constants = mutableSetOf<Float>()

                        override fun visitLdcInsn(value: Any) {
                            if (value is Float) constants += value
                        }

                        override fun visitEnd() {
                            // This factory clamps the host's low-pass cutoff and sets its resonance.
                            if (400f in constants && 18_000f in constants && 0.82f in constants) {
                                candidates += HostSymbol(engine.owner, name, descriptor)
                            }
                        }
                    }
                }
            }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
        lowPassParameters = symbols.method(candidates.uniqueHost("宿主低通滤波参数"))
    }
}
