import java.lang.reflect.*;
import java.util.*;
import java.nio.file.Path;
import java.nio.file.Files;
import automix.*;
import automix.Plan;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.xuncorp.pisces.PiscesMediaItem;
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem;
import com.xuncorp.spw.workshop.api.hook.MethodHookParam;
import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonObject;
import net.bytebuddy.jar.asm.*;

/** Check discovered playback ABI and native Workshop call sites without starting the player. */
class HostProbe {
    private static final String MIX = "com.xuncorp.spc.morvanium.automix.Morvanium";
    private static HostSymbols symbols;
    private static HostMixTargets targets;

    static Class<?> host(String role) {
        return symbols.resolve(symbols.named(MIX + role));
    }
    static Object create(String role, Object... args) throws Exception {
        return Arrays.stream(host(role).getConstructors()).filter(c -> c.getParameterCount() == args.length)
            .findFirst().orElseThrow().newInstance(args);
    }
    static Object named(String role, String name) {
        return Arrays.stream(host(role).getEnumConstants()).filter(e -> ((Enum<?>) e).name().equals(name))
            .findFirst().orElseThrow();
    }
    static Object track(String id) throws Exception {
        return track(id,100.);
    }
    static Object track(String id,double bpm) throws Exception {
        return track(id, bpm, 0L);
    }
    static Object track(String id,double bpm,long exitStartMs) throws Exception {
        var range=create("TimeRange",0L,180000L);
        var region=create("MixRegion",range,-16.0,.4,.8);
        var exit=create("MixRegion",create("TimeRange",exitStartMs,180000L),-16.0,.4,.8);
        var grid=new ArrayList<Long>();
        for(int beat=0;beat*60000/bpm<180000;beat++)grid.add(Math.round(beat*60000/bpm));
        var tempo=create("TempoProfile",bpm,grid,0,.9,.9,.9);
        var profile=create("TrackProfile",id,180000L,range,-15.0,-2.0,tempo,region,exit);
        var spectral=create("SpectralRegionProfile",range,-20.0,-18.0,-25.0,.4);
        return create("AnalyzedTrack",profile,create("ArrangementProfile",id,spectral,spectral));
    }
    static Object arranged(Object transition) throws Exception {
        return create("ArrangedTransitionPlan", transition, named("TransitionScene", "TRANSPARENT_BLEND"), List.of(), List.of(), "transparent_blend");
    }
    static Object queueItem(int index, String id) throws Exception {
        return symbols.resolve(symbols.named("com.xuncorp.spc.core.queue.PlaybackQueueItem"))
            .getConstructors()[0].newInstance(
            index, new PiscesMediaItem(id, id, "artist", "", "", id + ".wav")
        );
    }
    static Object queue(String mode, List<?> normal, int current, List<?> random, int randomCurrent) throws Exception {
        var modeClass = Class.forName("com.xuncorp.spc.core.queue.PlaybackQueue$Mode");
        var modeValue = Arrays.stream(modeClass.getEnumConstants())
            .filter(value -> ((Enum<?>) value).name().equals(mode)).findFirst().orElseThrow();
        return Class.forName("com.xuncorp.spc.core.queue.PlaybackQueueState").getConstructors()[0]
            .newInstance(modeValue, normal, current, random, randomCurrent, false);
    }
    static MediaItem libraryItem(String id) {
        return new MediaItem(id, "artist", "", "", id + ".wav", id, 0, "", 0, 180000L, false, true);
    }
    static void checkRate(Object transition) throws Exception {
        if(!targets.getSpeedA().get(transition).equals(1.0) ||
           !targets.getSpeedB().get(transition).equals(1.0))
            throw new AssertionError("A track would change speed");
    }
    static Object transitionFrom(Object arrangement) throws Exception {
        return targets.getArrangedPlanField().get(arrangement);
    }

    static void checkPresetRouting(HostPlayback bridge, Call call, Object baseline) throws Exception {
        var previewOnly = new Plan(155., 3., 100., 100., 4, "smooth", true, .5,
            "high-out", TransitionEffect.ReverbEndFade, "three-band", true, false);
        call.result = arranged(baseline);
        if (bridge.apply(call, Map.of("1:AB", previewOnly)) == null ||
            !((List<?>) targets.getArrangedCurvesField().get(call.result)).isEmpty() ||
            !targets.getArrangedSceneField().get(call.result).equals(named("TransitionScene", "TRANSPARENT_BLEND")))
            throw new AssertionError("A preview-only effect leaked into native playback");

        var envelopeClass = host("ParameterEnvelope");
        var targetType = host("ProcessingTarget");
        var envelopeTarget = Arrays.stream(envelopeClass.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()) && field.getType() == targetType)
            .findFirst().orElseThrow();
        var envelopeCode = Arrays.stream(envelopeClass.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()) && field.getType() == String.class)
            .findFirst().orElseThrow();
        envelopeTarget.setAccessible(true);
        envelopeCode.setAccessible(true);
        for (var filter : Map.of("high-out-low-in", "INCOMING", "low-out-high-in", "OUTGOING").entrySet()) {
            var mix = new Plan(155., 3., 100., 100., 4, "smooth", false, .5,
                filter.getKey(), TransitionEffect.None, "none", true, false);
            call.result = arranged(baseline);
            bridge.apply(call, Map.of("1:AB", mix));
            var transition = transitionFrom(call.result);
            var curves = (List<?>) targets.getArrangedCurvesField().get(call.result);
            if (transition == baseline ||
                !targets.getMode().get(transition).equals(named("TransitionMode", "BEAT_ALIGNED")) ||
                curves.size() != 1 || !envelopeCode.get(curves.getFirst()).equals("low_pass_cutoff_hz") ||
                !envelopeTarget.get(curves.getFirst()).equals(named("ProcessingTarget", filter.getValue())))
                throw new AssertionError("Mixed filter did not preserve the supported low-pass deck: " + filter.getKey());
            checkRate(transition);
        }

        var noMix = new Plan(175., 3., 100., 100., 4, "off", false, .5,
            "none", TransitionEffect.None, "none", true, false);
        var position = call.args[3];
        call.args[3] = 179900L;
        call.result = arranged(baseline);
        var applied = bridge.apply(call, Map.of("1:AB", Plan.Companion.read(noMix.json())));
        var gapless = transitionFrom(call.result);
        call.args[3] = position;
        if (applied == null ||
            !targets.getMode().get(gapless).equals(named("TransitionMode", "GAPLESS")) ||
            targets.getCueA().getLong(gapless) != 180000L || targets.getCueB().getLong(gapless) != 0L ||
            targets.getDuration().getLong(gapless) != 0L ||
            !((List<?>) targets.getArrangedCurvesField().get(call.result)).isEmpty())
            throw new AssertionError("Saved no-transition preset did not replace a late overlapping host mix");
        checkRate(gapless);
    }

    static void checkLiveHighPass(HostPlayback bridge, Call call, Object baseline, ClassLoader loader) throws Exception {
        var fx = new HostLiveFxTargets(symbols);
        var target = fx.getLowPassParameters();
        if (!Modifier.isPrivate(target.getModifiers()) || !Modifier.isStatic(target.getModifiers()) ||
            target.getReturnType() != Object.class || !Arrays.equals(target.getParameterTypes(), new Class<?>[]{double.class})) {
            throw new AssertionError("Unexpected host filter factory signature: " + target);
        }

        // Verify WorkshopHookTargets resolves the private static filter factory as a supported method hook.
        Class<?> resolverClass = Class.forName("com.xuncorp.voxzen.workshop.hooks.\u052a");
        Object resolver = Arrays.stream(resolverClass.getDeclaredFields())
            .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == resolverClass)
            .findFirst().orElseThrow().get(null);
        Method resolveMethod = Arrays.stream(resolverClass.getDeclaredMethods())
            .filter(m -> Modifier.isPublic(m.getModifiers()) && Arrays.equals(m.getParameterTypes(),
                new Class<?>[]{String.class, String.class, List.class}))
            .findFirst().orElseThrow();
        Object methodInfo = resolveMethod.invoke(resolver, target.getDeclaringClass().getName(),
            target.getName(), List.of("double"));
        if (methodInfo == null || !methodInfo.toString().contains("supported=true")) {
            throw new AssertionError("Workshop did not resolve filter factory as supported: " + methodInfo);
        }

        // Run SPW's WorkshopMethodTransformer on AutomixExecutionEngine and inspect + execute the transformed method.
        String engineClassName = target.getDeclaringClass().getName();
        Class<?> transformerClass = Class.forName("com.xuncorp.voxzen.workshop.hooks.\u052c");
        Object transformer = transformerClass.getConstructor(Method.class).newInstance(target);
        Method transform = Arrays.stream(transformerClass.getDeclaredMethods())
            .filter(m -> m.getParameterCount() == 2 && m.getParameterTypes()[0] == String.class)
            .findFirst().orElseThrow();
        byte[] originalBytes;
        try (var is = target.getDeclaringClass().getResourceAsStream("/" + engineClassName.replace('.', '/') + ".class")) {
            originalBytes = is.readAllBytes();
        }
        byte[] transformedBytes = (byte[]) transform.invoke(transformer, engineClassName, originalBytes);
        boolean[] woven = new boolean[3]; // [enter, exit, popsGetResult]
        new ClassReader(transformedBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if (!name.equals(target.getName()) || !desc.equals("(D)Ljava/lang/Object;")) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    boolean sawGetResult = false;
                    @Override public void visitMethodInsn(int op, String owner, String mName, String mDesc, boolean itf) {
                        if (owner.equals("com/xuncorp/voxzen/workshop/hooks/WorkshopHookBridge") && mName.equals("enter")) woven[0] = true;
                        if (owner.equals("com/xuncorp/voxzen/workshop/hooks/WorkshopHookBridge") && mName.equals("exit")) woven[1] = true;
                        sawGetResult = owner.equals("com/xuncorp/spw/workshop/api/hook/MethodHookParam") && mName.equals("getResult");
                    }
                    @Override public void visitInsn(int op) {
                        if (sawGetResult && op == Opcodes.POP) woven[2] = true;
                        sawGetResult = false;
                    }
                };
            }
        }, 0);
        if (!woven[0] || !woven[1]) {
            throw new AssertionError("WorkshopMethodTransformer did not weave enter/exit into filter factory");
        }
        System.out.println("LIVE_FX_HOOK advicePopsResult=" + woven[2] + " target=" + target);

        ClassLoader isolated = new ClassLoader(loader) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(engineClassName) || name.startsWith(engineClassName + "$")) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        byte[] bytes;
                        if (name.equals(engineClassName)) {
                            bytes = transformedBytes;
                        } else {
                            try (var is = loader.getResourceAsStream(name.replace('.', '/') + ".class")) {
                                if (is == null) throw new ClassNotFoundException(name);
                                bytes = is.readAllBytes();
                            } catch (Exception e) {
                                throw new ClassNotFoundException(name, e);
                            }
                        }
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    }
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
                return super.loadClass(name, resolve);
            }
        };
        Method transformedFactory = isolated.loadClass(engineClassName).getDeclaredMethod(target.getName(), double.class);
        transformedFactory.setAccessible(true);

        // Helper matching MainPlugin's after-hook behavior on the host-returned Pointer.
        java.util.function.DoubleFunction<Pointer> invokeWithAfterHook = value -> {
            try {
                Object result = transformedFactory.invoke(null, value);
                Double decoded = HostHighPass.INSTANCE.decode(value);
                if (decoded != null) {
                    if (!HostBassLiveFilter.INSTANCE.writeHighPass(result, decoded)) {
                        throw new AssertionError("In-place high-pass write failed for " + result);
                    }
                }
                return (Pointer) result;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        // Sanity checks on HostHighPass decoding and HostBassLiveFilter safety guards.
        if (HostHighPass.INSTANCE.decode(1200.0) != null || HostHighPass.INSTANCE.decode(18000.0) != null ||
            !Double.valueOf(25.0).equals(HostHighPass.INSTANCE.decode(100_025.0 - 1e-9)) ||
            !Double.valueOf(1800.0).equals(HostHighPass.INSTANCE.decode(101_800.0 + 1e-9)) ||
            HostBassLiveFilter.INSTANCE.writeHighPass(null, 420.0) ||
            HostBassLiveFilter.INSTANCE.writeHighPass(new Memory(16L), 420.0)) {
            throw new AssertionError("HostHighPass boundary or HostBassLiveFilter guard failed");
        }

        // Direct invocation checks on transformed private static factory.
        Pointer lowPassPtr = invokeWithAfterHook.apply(1200.0);
        if (lowPassPtr.getInt(0) != 0 || Math.abs(lowPassPtr.getFloat(4) - 1200f) > 0.01f ||
            Math.abs(lowPassPtr.getFloat(16) - 0.82f) > 1e-4f || lowPassPtr.getInt(24) != -1) {
            throw new AssertionError("Low-pass BQF parameters changed unexpectedly");
        }
        Pointer highPassPtr = invokeWithAfterHook.apply(HostHighPass.INSTANCE.encode(420.0));
        if (highPassPtr.getInt(0) != 1 || Math.abs(highPassPtr.getFloat(4) - 420f) > 0.01f ||
            highPassPtr.getFloat(8) != 0f || highPassPtr.getFloat(12) != 0f ||
            Math.abs(highPassPtr.getFloat(16) - 0.70710677f) > 1e-5f ||
            highPassPtr.getFloat(20) != 0f || highPassPtr.getInt(24) != -1) {
            throw new AssertionError("High-pass in-place BQF mutation failed");
        }

        // Verify dual-track routing (one deck high-pass, one deck low-pass) when liveHighPass is enabled.
        var envelopeClass = host("ParameterEnvelope");
        var targetType = host("ProcessingTarget");
        var envelopeTarget = Arrays.stream(envelopeClass.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()) && field.getType() == targetType)
            .findFirst().orElseThrow();
        var envelopeCode = Arrays.stream(envelopeClass.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()) && field.getType() == String.class)
            .findFirst().orElseThrow();
        var valueAt = Arrays.stream(envelopeClass.getDeclaredMethods())
            .filter(m -> m.getReturnType() == double.class && Arrays.equals(m.getParameterTypes(), new Class<?>[]{double.class}))
            .findFirst().orElseThrow();
        envelopeTarget.setAccessible(true);
        envelopeCode.setAccessible(true);
        valueAt.setAccessible(true);

        bridge.getLiveHighPass().setValue(true);
        try {
            for (var spec : Map.of(
                "high-out-low-in", Map.of("OUTGOING", 1, "INCOMING", 0),
                "low-out-high-in", Map.of("OUTGOING", 0, "INCOMING", 1),
                "both-high", Map.of("OUTGOING", 1, "INCOMING", 1)
            ).entrySet()) {
                var mix = new Plan(155., 3., 100., 100., 4, "smooth", false, .5,
                    spec.getKey(), TransitionEffect.None, "none", true, false);
                call.result = arranged(baseline);
                bridge.apply(call, Map.of("1:AB", mix));
                var curves = (List<?>) targets.getArrangedCurvesField().get(call.result);
                if (curves.size() != 2) {
                    throw new AssertionError("Expected 2 filter curves for " + spec.getKey() + ", got " + curves.size());
                }
                var seenTargets = new HashSet<String>();
                for (var curve : curves) {
                    if (!envelopeCode.get(curve).equals("low_pass_cutoff_hz")) {
                        throw new AssertionError("Unexpected curve code: " + envelopeCode.get(curve));
                    }
                    String deck = ((Enum<?>) envelopeTarget.get(curve)).name();
                    seenTargets.add(deck);
                    int expectedFilter = spec.getValue().get(deck);
                    for (double progress : new double[]{0.0, 0.25, 0.5, 0.75, 1.0}) {
                        double evaluated = (Double) valueAt.invoke(curve, progress);
                        Pointer ptr = invokeWithAfterHook.apply(evaluated);
                        if (ptr.getInt(0) != expectedFilter) {
                            throw new AssertionError(spec.getKey() + " " + deck + " at u=" + progress +
                                " produced lFilter=" + ptr.getInt(0) + ", expected " + expectedFilter);
                        }
                        float center = ptr.getFloat(4);
                        if (expectedFilter == 1 && (center < 24.9f || center > 1800.1f)) {
                            throw new AssertionError("High-pass cutoff out of range: " + center);
                        }
                        if (expectedFilter == 0 && (center < 399.9f || center > 18000.1f)) {
                            throw new AssertionError("Low-pass cutoff out of range: " + center);
                        }
                    }
                }
                if (!seenTargets.equals(Set.of("OUTGOING", "INCOMING"))) {
                    throw new AssertionError("Missing deck in dual-track filter curves: " + seenTargets);
                }
            }

            // Three-band EQ and noise-out must still remain preview-only even when liveHighPass is enabled.
            var stillPreviewOnly = new Plan(155., 3., 100., 100., 4, "smooth", true, .5,
                "noise-out", TransitionEffect.None, "three-band", true, false);
            call.result = arranged(baseline);
            bridge.apply(call, Map.of("1:AB", stillPreviewOnly));
            if (!((List<?>) targets.getArrangedCurvesField().get(call.result)).isEmpty()) {
                throw new AssertionError("Preview-only three-band EQ or noise-out leaked into live playback");
            }
        } finally {
            bridge.getLiveHighPass().setValue(false);
        }
        System.out.println("PASS Workshop filter factory hook, in-place BQF high-pass mutation, and dual-track HPF+LPF routing");
    }

    static float alpha(long color) throws Exception {
        return (Float) Class.forName("androidx.compose.ui.graphics.Color")
            .getMethod("getAlpha-impl", long.class).invoke(null, color);
    }

    static void checkBackdrop() throws Exception {
        var background = Skin.class.getDeclaredField("pageBackground");
        background.setAccessible(true);
        var popup = Arrays.stream(Skin.class.getMethods())
            .filter(m -> m.getName().equals("popupBackground") || m.getName().startsWith("popupBackground-"))
            .findFirst().orElseThrow();
        for (boolean dark : new boolean[]{false, true}) {
            var base = EditorKt.fallbackSkin(dark);
            if (HostBackdropKt.materialSkin(base, WindowBackdrop.Solid) != base)
                throw new AssertionError("Classic theme palette changed");
            for (var type : List.of(WindowBackdrop.Acrylic, WindowBackdrop.Mica, WindowBackdrop.Wallpaper)) {
                var skin = HostBackdropKt.materialSkin(base, type);
                if (alpha(background.getLong(skin)) >= 1f || alpha((Long) popup.invoke(skin)) != 1f)
                    throw new AssertionError("Backdrop or popup opacity is wrong: " + type);
            }
        }
        if (HostBackdropKt.hostBackdrop("WindowsMicaAlt", false) != WindowBackdrop.Mica ||
            HostBackdropKt.hostBackdrop("None", true) != WindowBackdrop.Wallpaper)
            throw new AssertionError("Host backdrop mode was not recognized");
    }

    static class Call extends MethodHookParam {
        final Method target; final Object[] args; Object result;
        Call(Method target,Object[] args,Object result){this.target=target;this.args=args;this.result=result;}
        public Method getMethod(){return target;} public Object[] getArgs(){return args;}
        public Object getThisObject(){return null;} public Throwable getThrowable(){return null;}
        public Object getResult(){return result;} public boolean isOriginalSkipped(){return false;}
        public void setResult(Object value){result=value;}
        public void setThrowable(Throwable t){throw new AssertionError(t);}
        public Object invokeOriginal(){throw new AssertionError("duplicate planner invocation");}
    }

    public record TestRoute(String title) {
        public TestRoute copy(String title) { return new TestRoute(title); }
        public String component1() { return title; }
    }

    static class NavigationCall extends Call {
        private final Object navigator;
        private boolean skipped;

        NavigationCall(Method target, Object navigator, Object... args) {
            super(target, args, null);
            this.navigator = navigator;
        }

        @Override public Object getThisObject() { return navigator; }
        @Override public boolean isOriginalSkipped() { return skipped; }
        @Override public void setResult(Object value) { super.setResult(value); skipped = true; }
    }

    static void checkNavigationHandoff(HostNavigationTargets targets) throws Exception {
        var state = AutoMixNav.INSTANCE;
        var navigator = new Object();
        var hostRoute = new TestRoute[]{new TestRoute("library")};
        var hostOverlay = new boolean[]{false};
        var statsVisible = new boolean[]{false};
        var forwardHistory = new ArrayList<Object>();
        var navigate = new AutoMixNavigationHook(AutoMixNavigationAction.Navigate, () -> true, () -> statsVisible[0]);
        var forward = new AutoMixNavigationHook(AutoMixNavigationAction.Forward, () -> true, () -> statsVisible[0]);
        state.close();
        try (var binding = state.bindNavigator(navigator, () -> hostRoute[0], route -> {
            var call = new NavigationCall(targets.getNavigateMethod(), navigator, route);
            if (route == hostRoute[0] || !route.equals(hostRoute[0]))
                throw new AssertionError("An open request would create a new host history entry");
            navigate.before(call);
            navigate.after(call);
            return kotlin.Unit.INSTANCE;
        }, () -> hostOverlay[0], () -> List.copyOf(forwardHistory));
             var presenter = state.attachPresenter()) {
            state.requestOpen();
            if (!state.getVisible() || state.getClosing()) throw new AssertionError("AutoMix did not open");

            var deferred = new NavigationCall(targets.getNavigateMethod(), navigator, new TestRoute("other"));
            navigate.before(deferred);
            deferred.setResult(null);
            navigate.after(deferred);
            if (!state.getVisible() || state.getClosing())
                throw new AssertionError("A deferred or canceled navigation dismissed AutoMix");

            var unchanged = new NavigationCall(targets.getForwardMethod(), navigator);
            forward.before(unchanged);
            forward.after(unchanged);
            if (state.getClosing()) throw new AssertionError("No-op forward dismissed AutoMix");

            hostOverlay[0] = true;
            if (state.consumeBack(navigator) || state.getClosing())
                throw new AssertionError("AutoMix intercepted Back from a host overlay");
            hostOverlay[0] = false;
            if (!state.consumeBack(navigator) || !state.getClosing())
                throw new AssertionError("Back did not start AutoMix return");
            var staleTicket = state.getCloseTicket();
            state.requestOpen();
            state.finishClose(staleTicket);
            if (!state.getVisible() || state.getClosing()) throw new AssertionError("Stale exit closed the reopened page");

            var marker = new NavigationCall(targets.getNavigateMethod(), navigator, new TestRoute(hostRoute[0].title()));
            navigate.before(marker);
            statsVisible[0] = true;
            marker.setResult(null);
            navigate.after(marker);
            if (!state.getClosing()) throw new AssertionError("AutoMix blocked the accepted statistics handoff");
            state.finishClose(state.getCloseTicket());
            if (state.getVisible()) throw new AssertionError("AutoMix survived the handoff");

            statsVisible[0] = false;
            state.requestOpen();
            var destination = new TestRoute("other");
            var accepted = new NavigationCall(targets.getNavigateMethod(), navigator, destination);
            navigate.before(accepted);
            hostRoute[0] = destination;
            navigate.after(accepted);
            if (!state.getClosing() || !state.getNativeNavigation())
                throw new AssertionError("Accepted native navigation did not begin a handoff");
        } finally {
            state.close();
        }
        System.out.println("PASS accepted navigation, guarded back, and statistics handoff");
    }

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--worker-plan")) {
                var payload = (JsonObject) Json.Default.parseToJsonElement(Files.readString(Path.of(args[++i])));
                var playback = Plan.Companion.read((JsonObject) payload.get("playback"));
                var editor = Plan.Companion.read((JsonObject) payload.get("editor"));
                if (!playback.valid() || !playback.equals(editor))
                    throw new AssertionError("Worker playback plan differs from the editor: " + playback);
                System.out.println("PASS packaged worker plan through Kotlin decoder: " + playback.getBars() + " bars");
            }
        }
        ClassLoader loader = HostProbe.class.getClassLoader();
        Path artifact = HostSymbols.Companion.source(loader);
        symbols = HostSymbols.Companion.read(artifact, loader);
        targets = new HostMixTargets(symbols);
        var bridge = new HostPlayback(symbols);
        boolean readOnlyPlaylist = Arrays.asList(args).contains("--legacy-playlist-read-only");
        if (readOnlyPlaylist ? bridge.getCanReorderPlaylists() || bridge.getWarnings().size() != 1
            : !bridge.getCanReorderPlaylists() || !bridge.getWarnings().isEmpty())
            throw new AssertionError(bridge.getWarnings());
        var planner = bridge.getPlanner();
        System.out.println("HOST " + artifact.getFileName() + " planner=" + planner);
        if (planner.getParameterCount() != 6 || planner.getReturnType() != host("ArrangedTransitionPlan"))
            throw new AssertionError(planner);
        new HostSkin(symbols);

        var navigation = new HostNavigationTargets(symbols);
        checkNavigationHandoff(navigation);
        var sidebar = new HostSidebarTargets(symbols);
        var backdrop = new HostBackdrop(symbols, navigation.contentLayout(symbols));
        checkBackdrop();
        Class<?> resolver = Class.forName("com.xuncorp.voxzen.workshop.hooks.\u052a");
        Object singleton = Arrays.stream(resolver.getDeclaredFields())
            .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == resolver)
            .findFirst().orElseThrow().get(null);
        Method resolve = Arrays.stream(resolver.getDeclaredMethods())
            .filter(m -> Modifier.isPublic(m.getModifiers()) && Arrays.equals(m.getParameterTypes(),
                new Class<?>[]{String.class, String.class, String.class, List.class}))
            .findFirst().orElseThrow();
        for (var site : List.of(navigation.getPage(), sidebar.getEntry(), backdrop.getSite())) {
            var parameters = site == navigation.getPage() ? List.of()
                : site == backdrop.getSite() ? List.of("androidx.compose.ui.Modifier", "kotlin.jvm.functions.Function3")
                : List.of(sidebar.getRouteAccessor().getParameterTypes()[0].getName());
            var key = site.getTarget();
            if (resolve.invoke(singleton, key.getClassName(), key.getName(), site.getCallerClass(), parameters) == null)
                throw new AssertionError("Workshop did not resolve " + site);
        }
        var gain=create("GainCurve",List.of(create("GainPoint",0.,1.,0.),create("GainPoint",1.,0.,1.)));
        var baseline=create("TransitionPlan",named("TransitionMode","BEAT_ALIGNED"),create("Confidence",.9,named("ConfidenceTier","HIGH")),
            150000L,0L,6000L,1.,1.,gain,List.of());
        var initial=arranged(baseline);
        var caps=create("ProcessingCapabilities",Set.of("spectral_automation","filter_automation","beat_synchronized_delay"),(byte)0);
        var call=new Call(planner,new Object[]{track("A"),track("B"),create("HostCapabilities"),100000L,caps,null},initial);
        var custom=new Plan(155.,3.,100.,102.,4,"fade-cut",true,.5,"low-out",TransitionEffect.LegacyEcho,"bass-swap-end",true,false);
        var legacy=(JsonObject)Json.Default.parseToJsonElement(custom.json().toString().replace("\"sync\":false","\"sync\":true"));
        var saved=Map.of("1:AB",Plan.Companion.read(legacy));
        if(bridge.apply(call,saved)==null)throw new AssertionError("Saved pair missed");
        var transition=transitionFrom(call.result);
        checkRate(transition);
        if(!targets.getCueA().get(transition).equals(155000L) ||
           !targets.getCueB().get(transition).equals(3000L) ||
           !targets.getDuration().get(transition).equals(9600L))throw new AssertionError("Incorrect cues or length");
        if(((List<?>)targets.getArrangedCurvesField().get(call.result)).size()!=6)throw new AssertionError("Missing automation");
        checkPresetRouting(bridge, call, baseline);
        checkLiveHighPass(bridge, call, baseline, loader);
        call.result=arranged(baseline);
        if(bridge.apply(call,Map.of())==null)throw new AssertionError("Automatic mix missing");
        call.result=arranged(baseline);call.args[3]=170000L;
        if(!bridge.apply(call,saved).startsWith("已过切点") ||
           transitionFrom(call.result)!=baseline)throw new AssertionError("Late mix accepted");
        call.result=arranged(baseline);call.args[3]=100000L;

        var delayed=new Plan(178.,-1.5,100.,100.,4,"smooth",false,.5,"none",TransitionEffect.None,"none",false,false);
        var window=delayed.window(180000L,180000L);
        if(window.getStartMs()!=179500L || window.getEntryMs()!=0L || window.getDurationMs()!=500L)
            throw new AssertionError("Delayed and cropped window differs");
        bridge.apply(call,Map.of("1:AB",delayed));
        transition=transitionFrom(call.result);
        if(!targets.getCueA().get(transition).equals(window.getStartMs()) ||
           !targets.getCueB().get(transition).equals(window.getEntryMs()) ||
           !targets.getDuration().get(transition).equals(window.getDurationMs()))
            throw new AssertionError("Host did not use the shared window");

        for (var volume : List.of("smooth", "linear", "overlap", "mid-cut", "fade-cut", "cut-fade", "full-overlap")) {
            var cropped = new Plan(178.,-1.5,100.,100.,4,volume,false,.5,"none",TransitionEffect.None,"none",false,false);
            var knots = cropped.window(180000L, 180000L).gains(cropped);
            var first = knots.getFirst();
            var last = knots.getLast();
            if (first.getSecond() != 1.0 || first.getThird() != 0.0 ||
                last.getSecond() != 0.0 || last.getThird() != 1.0 ||
                knots.stream().noneMatch(k -> k.getFirst() > 0.0 && k.getFirst() < 1.0 &&
                    k.getSecond() > 0.0 && k.getSecond() < 1.0 && k.getThird() > 0.0))
                throw new AssertionError("Clipped curve has a gain discontinuity: " + volume);
        }

        var gapless = create("TransitionPlan",named("TransitionMode","GAPLESS"),create("Confidence",.9,named("ConfidenceTier","HIGH")),
            180000L,0L,0L,1.,1.,gain,List.of());
        call.args[0] = track("A", 100., 150000L);
        call.result = arranged(gapless);
        bridge.apply(call, Map.of());
        transition = transitionFrom(call.result);
        if (transition == gapless || targets.getCueA().getLong(transition) + targets.getDuration().getLong(transition) != 180000L)
            throw new AssertionError("Unprepared fallback did not reach the end of the outgoing track");
        call.args[0] = track("A");

        var nativeMatched=create("TransitionPlan",named("TransitionMode","BEAT_MATCHED"),create("Confidence",.9,named("ConfidenceTier","HIGH")),
            150000L,0L,12000L,1.0,100.0/102.0,gain,List.of());
        call.args[1]=track("B",102.);
        call.result=arranged(nativeMatched);
        bridge.apply(call,Map.of());
        transition=transitionFrom(call.result);
        checkRate(transition);
        var activeOverlap=(Long)targets.getDuration().get(transition);
        var sourceStart=(Long)targets.getCueA().get(transition);
        var sourceEntry=(Long)targets.getCueB().get(transition);
        var downbeatAligned = false;
        for (long beatA = ((sourceStart + 2399L) / 2400L) * 2400L; beatA <= sourceStart + activeOverlap; beatA += 2400L) {
            long timeB = sourceEntry + beatA - sourceStart;
            long beatB = Math.round(Math.round(timeB * 102.0 / 240000.0) * 240000.0 / 102.0);
            if (Math.abs(beatB - timeB) <= 1L) downbeatAligned = true;
        }
        if(!targets.getMode().get(transition).equals(named("TransitionMode","BEAT_ALIGNED")) ||
           activeOverlap!=9600L || !downbeatAligned)
            throw new AssertionError("Close BPMs did not receive a four-bar original-speed alignment");

        call.args[1]=track("B",120.);
        call.result=arranged(nativeMatched);
        bridge.apply(call,Map.of());
        transition=transitionFrom(call.result);
        checkRate(transition);
        if(!targets.getMode().get(transition).equals(named("TransitionMode","ENERGY_CROSSFADE")) ||
           !targets.getCueA().get(transition).equals(157200L) ||
           !targets.getDuration().get(transition).equals(4800L))
            throw new AssertionError("Distant BPMs did not receive a shorter fade ending at the same point");

        call.result=arranged(nativeMatched);call.args[3]=156000L;
        if(!bridge.apply(call,saved).startsWith("已过切点"))throw new AssertionError("Late save fallback missing");
        checkRate(transitionFrom(call.result));
        call.args[3]=100000L;

        var nativePlan=new Plan(155.,3.,100.,100.,4,"smooth",false,.5,"none",TransitionEffect.None,"none",false,true);
        call.result=arranged(nativeMatched);
        bridge.apply(call,Map.of("1:AB",nativePlan));
        if(transitionFrom(call.result)!=nativeMatched)
            throw new AssertionError("Host-controlled pair replaced the host plan");

        var recommendedPlan = new Plan(147., 2., 100., 100., 4, "overlap", true, .5,
            "low-out", TransitionEffect.None, "bass-swap", true, false);
        var recommendation = new PreparedAutoMix("1:AB", recommendedPlan, MixAlgorithm.Spotify);
        call.args[1] = track("B");
        call.result = arranged(baseline);
        var applied = bridge.apply(call, Map.of(), recommendation);
        transition = transitionFrom(call.result);
        if (applied == null || !applied.contains(" · 自动 · ") ||
            !targets.getCueA().get(transition).equals(147000L) ||
            !targets.getCueB().get(transition).equals(2000L) ||
            ((List<?>)targets.getArrangedCurvesField().get(call.result)).size() != 3)
            throw new AssertionError("The upcoming recommendation only works after saving it");
        checkRate(transition);

        call.result = arranged(baseline);
        bridge.apply(call, saved, recommendation);
        if (!targets.getCueA().get(transitionFrom(call.result)).equals(155000L))
            throw new AssertionError("Automatic playback replaced a saved edit");

        var relationship = host("TrackRelationship").getConstructor().newInstance();
        var relationshipType = symbols.named(MIX + "TrackRelationship");
        symbols.field(relationshipType.dataField("gaplessPreferred", "Z")).setBoolean(relationship, true);
        call.args[5] = relationship;
        call.result = arranged(gapless);
        bridge.apply(call, Map.of(), recommendation);
        if (transitionFrom(call.result) != gapless)
            throw new AssertionError("Automatic Spotify transition replaced a continuous album boundary");
        bridge.apply(call, saved, recommendation);
        if (transitionFrom(call.result) == gapless)
            throw new AssertionError("Continuous album protection replaced an explicitly saved transition");
        call.args[5] = null;

        call.result = arranged(nativeMatched);
        bridge.apply(call, Map.of("1:AB", nativePlan), recommendation);
        if (transitionFrom(call.result) != nativeMatched)
            throw new AssertionError("Automatic playback replaced an explicit host override");

        call.result = arranged(baseline);
        bridge.apply(call, Map.of(), new PreparedAutoMix("1:AC", recommendedPlan, MixAlgorithm.Spotify));
        if (targets.getCueA().get(transitionFrom(call.result)).equals(147000L))
            throw new AssertionError("A recommendation was applied to a different pair");
        call.result = arranged(nativeMatched);
        call.args[3] = 148000L;
        bridge.apply(call, Map.of(), recommendation);
        transition = transitionFrom(call.result);
        if (targets.getCueA().get(transition).equals(147000L))
            throw new AssertionError("A late recommendation was applied to playback");
        checkRate(transition);
        call.args[3] = 100000L;

        var a=queueItem(10,"A");
        var missing=queueItem(11,"missing");
        var b=queueItem(12,"B");
        var c=queueItem(13,"C");
        var library=List.of(libraryItem("A"),libraryItem("B"),libraryItem("C"));
        var normal=List.of(a,missing,b,c);
        var shuffle=bridge.queueView(queue("Random",normal,0,List.of(b,a,c,missing),1),library);
        if (!shuffle.getShuffled() || !shuffle.getOrder().equals(List.of(12, 10, 13, 11)) ||
            !shuffle.getEntries().stream().map(QueueTrackEntry::getQueueId).toList().equals(List.of(12, 10, 13)) ||
            shuffle.getCurrentIndex() != 1 || shuffle.getEntries().get(1).getDisplayIndex() != 1 ||
            !shuffle.getNextPair().getSecond().getId().equals("C"))
            throw new AssertionError("Displayed queue, current index or transition did not follow shuffle order");
        var repeatA = queueItem(14, "A");
        var repeated = queue("Random", List.of(a, missing, b, repeatA, c), 0, List.of(repeatA, b, a, c, missing), 0);
        var queueTargets = new HostQueueTargets(symbols);
        var clicked = bridge.queueView(repeated, library).getEntries().getFirst();
        var reordered = queue("Circle", List.of(a, repeatA, missing, b, c), 0, List.of(), -1);
        var replaced = queue("Circle", List.of(a, queueItem(14, "B"), missing, b, c), 0, List.of(), -1);
        if (!bridge.getCanPlayTracks() || clicked.getQueueId() != 14 ||
            !Objects.equals(queueTargets.playbackIndex(repeated, clicked), new kotlin.Pair<>(0, true)) ||
            !Objects.equals(queueTargets.playbackIndex(reordered, clicked), new kotlin.Pair<>(1, false)) ||
            queueTargets.playbackIndex(replaced, clicked) != null)
            throw new AssertionError("Direct playback lost the selected queue entry or used a stale index");
        if(bridge.queueView(queue("CircleEnd",normal,0,List.of(),-1),library).getNextPair()!=null ||
           bridge.queueView(queue("CircleEnd",normal,3,List.of(),-1),library).getNextPair()!=null ||
           bridge.queueView(queue("RepeatOne",normal,2,List.of(),-1),library).getNextPair()!=null ||
           !bridge.queueView(queue("Circle",normal,3,List.of(),-1),library).getNextPair().getSecond().getId().equals("A"))
            throw new AssertionError("Queue adjacency or repeat handling differs");
        var upcoming = bridge.upcomingTransition(queue("Random", normal, 0, List.of(b,a,c,missing),1));
        if (upcoming == null || !upcoming.getKey().equals("1:AC") ||
            !upcoming.getFrom().getPath().equals("A.wav") || !upcoming.getTo().getPath().equals("C.wav") ||
            bridge.upcomingTransition(queue("RepeatOne", normal, 2, List.of(), -1)) != null ||
            bridge.upcomingTransition(queue("CircleEnd", normal, 3, List.of(), -1)) != null)
            throw new AssertionError("Background preparation did not follow playback order");
        var lookahead = bridge.followingTracks(queue("Random", normal, 0, List.of(b, a, c, missing), 0), 3);
        if (!lookahead.stream().map(PlaybackTrack::getId).toList().equals(List.of("A", "C", "missing")) ||
            !bridge.followingTracks(queue("Circle", normal, 3, List.of(), -1), 2)
                .stream().map(PlaybackTrack::getId).toList().equals(List.of("A", "missing")))
            throw new AssertionError("Pre-analysis did not follow host playback order");
        var directQueue = List.of(a, b, c);
        var beforeAdvance = bridge.queueView(queue("CircleEnd", directQueue, 0, List.of(), -1), library);
        var afterAdvance = bridge.queueView(queue("CircleEnd", directQueue, 1, List.of(), -1), library);
        var terminal = bridge.queueView(queue("CircleEnd", directQueue, 2, List.of(), -1), library);
        var current = EditorPair.Companion.displayed(beforeAdvance, null);
        var advanced = EditorPair.Companion.displayed(afterAdvance, null);
        var exhausted = EditorPair.Companion.displayed(terminal, null);
        var randomPair = EditorPair.Companion.displayed(shuffle, null);
        if (!current.getOutgoing().getId().equals("A") || !current.getIncoming().getId().equals("B") ||
            !advanced.getOutgoing().getId().equals("B") || !advanced.getIncoming().getId().equals("C") ||
            exhausted.getOutgoing() != null || exhausted.getIncoming() != null ||
            !randomPair.getOutgoing().getId().equals("A") || !randomPair.getIncoming().getId().equals("C"))
            throw new AssertionError("The following editor did not update or clear its active pair with the host queue");
        var pinned = new EditorPair(library.get(0), library.get(1), new kotlin.Pair<>("10", "12"));
        var incomplete = new EditorPair(library.get(1), null, null);
        if (EditorPair.Companion.displayed(afterAdvance, pinned) != pinned ||
            EditorPair.Companion.displayed(terminal, incomplete) != incomplete)
            throw new AssertionError("A host update replaced a manually selected editor pair");

        System.out.println("PASS Workshop binding, original-speed saved/automatic plans, short fade, host mode and queue modes");
        System.out.println("PASS editor follows queue advances, clears at queue end, and preserves manual selections");

        var smoothPlan = new Plan(10., 0., 120., 120., 4, "smooth", false, .5, "none", TransitionEffect.None, "none", false, false);
        var smoothWindow = smoothPlan.window(180000L, 180000L);
        var smoothKnots = smoothWindow.gains(smoothPlan);
        var earlyGain = smoothKnots.get(6);
        var middleGain = smoothKnots.get(24);
        var lateGain = smoothKnots.get(42);
        if (earlyGain.getThird() >= 0.06 || lateGain.getSecond() >= 0.06 ||
            Math.abs(earlyGain.getThird() - lateGain.getSecond()) > 1e-9 ||
            Math.abs(middleGain.getSecond() - Math.pow(0.5, 0.625)) > 1e-9 ||
            Math.abs(middleGain.getSecond() - middleGain.getThird()) > 1e-9)
            throw new AssertionError("Smooth fade does not ease both ends symmetrically");
        System.out.println("PASS smooth S-curve ends and center");

        var previewPlan=new Plan(2.,-1.5,100.,102.,4,"smooth",false,.5,"none",TransitionEffect.None,"none",true,false);
        var previewWindow=previewPlan.window(4000L,12000L);
        System.out.println("PREVIEW_JOB={\"plan\":" + previewPlan.json() + ",\"window\":" + previewWindow.json(previewPlan) + "}");
    }
}
