import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.jetbrains.org.objectweb.asm.*;
import org.jetbrains.org.objectweb.asm.commons.*;

/**
 * Post-compile bytecode relocator for SaltUI and its transitive libraries.
 *
 * SPW's host fat jar ships obfuscated classes under com.moriafly.salt.*,
 * androidx.graphics.shapes.*, androidx.collection.*, and an older Haze 2.0.x.
 * Because PF4J PluginClassLoader delegates parent-first, bundling unmodified
 * jars causes NoSuchFieldError/NoSuchMethodError at runtime. Relocating the
 * bundled libraries into automix.shaded.* after kotlinc compiles against the
 * original Maven jars isolates them cleanly without altering string literals.
 */
public final class ShadeSaltUi {
    private static final String[][] LIB_RULES = {
        {"com/moriafly/salt/", "automix/shaded/salt/"},
        {"androidx/graphics/shapes/", "automix/shaded/shapes/"},
        {"androidx/collection/", "automix/shaded/collection/"},
        {"dev/chrisbanes/haze/", "automix/shaded/haze/"},
    };

    // Plugin classes do not relocate androidx/collection/ so Compose code never
    // crosses an androidx.collection type boundary with the host runtime.
    private static final String[][] PLUGIN_RULES = {
        {"com/moriafly/salt/", "automix/shaded/salt/"},
        {"androidx/graphics/shapes/", "automix/shaded/shapes/"},
        {"dev/chrisbanes/haze/", "automix/shaded/haze/"},
    };

    private static Remapper makeRemapper(String[][] rules) {
        return new Remapper() {
            @Override
            public String map(String internalName) {
                for (String[] rule : rules) {
                    if (internalName.startsWith(rule[0])) {
                        return rule[1] + internalName.substring(rule[0].length());
                    }
                }
                return internalName;
            }
        };
    }

    private static byte[] remapClass(byte[] input, Remapper remapper, String originalInternalName) {
        ClassReader reader = new ClassReader(input);
        boolean isHazeNode = "dev/chrisbanes/haze/HazeEffectNode".equals(originalInternalName);
        boolean isTrimMem = "dev/chrisbanes/haze/TrimMemoryCallbackKt".equals(originalInternalName);
        ClassWriter writer = new ClassWriter((isHazeNode || isTrimMem) ? ClassWriter.COMPUTE_MAXS : 0);
        ClassVisitor visitor = new ClassRemapper(writer, remapper);
        if (isHazeNode) {
            visitor = patchHazeEffectNode(visitor);
        } else if (isTrimMem) {
            visitor = patchTrimMemoryCallback(visitor);
        }
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    /**
     * SPW's host jar obfuscates androidx.lifecycle.*; HazeEffectNode only reads
     * LocalLifecycleOwner to register an optional ON_STOP trim-memory callback.
     * Neutralize those two LocalLifecycleOwner reads so shaded Haze works without
     * crashing on NoClassDefFoundError when Blur/Glass modifiers attach.
     */
    private static ClassVisitor patchHazeEffectNode(ClassVisitor downstream) {
        return new ClassVisitor(Opcodes.ASM9, downstream) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, exceptions);
                if ("updateLifecycle$haze_root_haze".equals(name)) {
                    mv.visitCode();
                    mv.visitInsn(Opcodes.RETURN);
                    mv.visitMaxs(0, 2);
                    mv.visitEnd();
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    private boolean skipCurrentValueOf = false;

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean isIface) {
                        if ("androidx/lifecycle/compose/LocalLifecycleOwnerKt".equals(owner)
                                && "getLocalLifecycleOwner".equals(mName)) {
                            mv.visitInsn(Opcodes.ACONST_NULL);
                            skipCurrentValueOf = true;
                            return;
                        }
                        if (skipCurrentValueOf
                                && "androidx/compose/ui/node/CompositionLocalConsumerModifierNodeKt".equals(owner)
                                && "currentValueOf".equals(mName)) {
                            mv.visitInsn(Opcodes.POP2);
                            mv.visitInsn(Opcodes.ACONST_NULL);
                            skipCurrentValueOf = false;
                            return;
                        }
                        if ("androidx/lifecycle/LifecycleOwner".equals(owner)
                                && "getLifecycle".equals(mName)) {
                            mv.visitInsn(Opcodes.POP);
                            mv.visitInsn(Opcodes.ACONST_NULL);
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, mName, mDesc, isIface);
                    }
                };
            }
        };
    }

    /** Replace Lifecycle observer registration with a no-op DisposableHandle. */
    private static ClassVisitor patchTrimMemoryCallback(ClassVisitor downstream) {
        return new ClassVisitor(Opcodes.ASM9, downstream) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                if (name.startsWith("registerLifecycleTrimMemoryCallback$lambda$")) {
                    return null;
                }
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, exceptions);
                if ("registerLifecycleTrimMemoryCallback".equals(name)) {
                    mv.visitCode();
                    mv.visitFieldInsn(Opcodes.GETSTATIC, "kotlinx/coroutines/NonDisposableHandle",
                        "INSTANCE", "Lkotlinx/coroutines/NonDisposableHandle;");
                    mv.visitInsn(Opcodes.ARETURN);
                    mv.visitMaxs(1, 2);
                    mv.visitEnd();
                    return null;
                }
                return mv;
            }
        };
    }

    private static void shadeJar(Path inputJar, Path outputJar) throws IOException {
        Remapper remapper = makeRemapper(LIB_RULES);
        Path outputDir = outputJar.toAbsolutePath().getParent();
        Files.createDirectories(outputDir);
        Path tmp = Files.createTempFile(outputDir, outputJar.getFileName() + ".", ".part");
        int remapped = 0;
        try {
            try (JarFile in = new JarFile(inputJar.toFile());
                 JarOutputStream out = new JarOutputStream(Files.newOutputStream(tmp))) {
                Set<String> written = new HashSet<>();
                for (JarEntry entry : Collections.list(in.entries())) {
                    String name = entry.getName();
                    if (entry.isDirectory()) continue;
                    // Skip compiler-only Kotlin module descriptors and signatures.
                    if (name.startsWith("META-INF/") && (name.endsWith(".kotlin_module")
                            || name.endsWith(".SF") || name.endsWith(".DSA") || name.endsWith(".RSA"))) {
                        continue;
                    }
                    byte[] data = in.getInputStream(entry).readAllBytes();
                    String outName = name;
                    if (name.endsWith(".class") && !name.equals("module-info.class") && !name.startsWith("META-INF/")) {
                        String internal = name.substring(0, name.length() - 6);
                        outName = remapper.map(internal) + ".class";
                        data = remapClass(data, remapper, internal);
                        remapped++;
                    }
                    if (!written.add(outName)) continue;
                    JarEntry outEntry = new JarEntry(outName);
                    out.putNextEntry(outEntry);
                    out.write(data);
                    out.closeEntry();
                }
            }
            Files.move(tmp, outputJar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
        System.out.println("Shaded jar: " + outputJar.getFileName() + " (" + remapped + " classes)");
    }

    private static void shadeClassesDir(Path classesDir) throws IOException {
        Remapper remapper = makeRemapper(PLUGIN_RULES);
        int[] count = {0};
        try (var stream = Files.walk(classesDir)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                if (!file.getFileName().toString().endsWith(".class")) continue;
                byte[] original = Files.readAllBytes(file);
                byte[] updated = remapClass(original, remapper, null);
                Files.write(file, updated);
                count[0]++;
            }
        }
        System.out.println("Shaded plugin classes: " + count[0]);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("--jar")) {
            shadeJar(Path.of(args[1]), Path.of(args[2]));
        } else if (args.length >= 3 && args[0].equals("--jars")) {
            Path outDir = Path.of(args[1]);
            for (int i = 2; i < args.length; i++) {
                Path inJar = Path.of(args[i]);
                shadeJar(inJar, outDir.resolve(inJar.getFileName()));
            }
        } else if (args.length == 2 && args[0].equals("--dir")) {
            shadeClassesDir(Path.of(args[1]));
        } else {
            throw new IllegalArgumentException(
                "Usage: ShadeSaltUi --jar <in.jar> <out.jar> | --jars <out_dir> <in.jar...> | --dir <classes_dir>");
        }
    }
}
