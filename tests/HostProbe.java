import java.lang.reflect.*;
import java.util.*;
import java.nio.file.Path;
import java.nio.file.Files;
import automix.*;
import automix.Plan;
import com.xuncorp.pisces.PiscesMediaItem;
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem;
import com.xuncorp.spw.workshop.api.hook.MethodHookParam;
import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonObject;

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
        var custom=new Plan(155.,3.,100.,102.,4,"fade-cut",true,.5,"low-out",true,"bass-swap-end",true,false);
        var legacy=(JsonObject)Json.Default.parseToJsonElement(custom.json().toString().replace("\"sync\":false","\"sync\":true"));
        var saved=Map.of("1:AB",Plan.Companion.read(legacy));
        if(bridge.apply(call,saved)==null)throw new AssertionError("Saved pair missed");
        var transition=transitionFrom(call.result);
        checkRate(transition);
        if(!targets.getCueA().get(transition).equals(155000L) ||
           !targets.getCueB().get(transition).equals(3000L) ||
           !targets.getDuration().get(transition).equals(9600L))throw new AssertionError("Incorrect cues or length");
        if(((List<?>)targets.getArrangedCurvesField().get(call.result)).size()!=6)throw new AssertionError("Missing automation");
        call.result=arranged(baseline);
        if(bridge.apply(call,Map.of())==null)throw new AssertionError("Automatic mix missing");
        call.result=arranged(baseline);call.args[3]=170000L;
        if(!bridge.apply(call,saved).startsWith("已过切点") ||
           transitionFrom(call.result)!=baseline)throw new AssertionError("Late mix accepted");
        call.result=arranged(baseline);call.args[3]=100000L;

        var delayed=new Plan(178.,-1.5,100.,100.,4,"smooth",false,.5,"none",false,"none",false,false);
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
            var cropped = new Plan(178.,-1.5,100.,100.,4,volume,false,.5,"none",false,"none",false,false);
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

        var nativePlan=new Plan(155.,3.,100.,100.,4,"smooth",false,.5,"none",false,"none",false,true);
        call.result=arranged(nativeMatched);
        bridge.apply(call,Map.of("1:AB",nativePlan));
        if(transitionFrom(call.result)!=nativeMatched)
            throw new AssertionError("Host-controlled pair replaced the host plan");

        var recommendedPlan = new Plan(147., 2., 100., 100., 4, "overlap", true, .5,
            "low-out", false, "bass-swap", true, false);
        var recommendation = new PreparedAutoMix("1:AB", recommendedPlan);
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

        call.result = arranged(nativeMatched);
        bridge.apply(call, Map.of("1:AB", nativePlan), recommendation);
        if (transitionFrom(call.result) != nativeMatched)
            throw new AssertionError("Automatic playback replaced an explicit host override");

        call.result = arranged(baseline);
        bridge.apply(call, Map.of(), new PreparedAutoMix("1:AC", recommendedPlan));
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
        if(shuffle.getOrder().size()!=4 || shuffle.getEntries().get(1).getRawIndex()!=2 ||
           !shuffle.getNextPair().getSecond().getId().equals("C"))
            throw new AssertionError("Lost raw indices or ignored shuffle order");
        var repeatA = queueItem(14, "A");
        var repeated = queue("Random", List.of(a, missing, b, repeatA, c), 0, List.of(repeatA, b, a, c, missing), 0);
        var queueTargets = new HostQueueTargets(symbols);
        var clicked = bridge.queueView(repeated, library).getEntries().get(2);
        var reordered = queue("Circle", List.of(a, repeatA, missing, b, c), 0, List.of(), -1);
        var replaced = queue("Circle", List.of(a, queueItem(14, "B"), missing, b, c), 0, List.of(), -1);
        if (!bridge.getCanPlayTracks() || clicked.getQueueId() != 14 ||
            !Objects.equals(queueTargets.playbackIndex(repeated, clicked), 3) ||
            !Objects.equals(queueTargets.playbackIndex(reordered, clicked), 1) ||
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
        System.out.println("PASS Workshop binding, original-speed saved/automatic plans, short fade, host mode and queue modes");

        var smoothPlan = new Plan(10., 0., 120., 120., 4, "smooth", false, .5, "none", false, "none", false, false);
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

        var previewPlan=new Plan(2.,-1.5,100.,102.,4,"smooth",false,.5,"none",false,"none",true,false);
        var previewWindow=previewPlan.window(4000L,12000L);
        System.out.println("PREVIEW_JOB={\"plan\":" + previewPlan.json() + ",\"window\":" + previewWindow.json(previewPlan) + "}");
    }
}
