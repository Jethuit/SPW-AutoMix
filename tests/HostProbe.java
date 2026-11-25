import java.lang.reflect.*;
import java.util.*;
import automix.Host1193;
import automix.Plan;
import com.xuncorp.pisces.PiscesMediaItem;
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem;
import com.xuncorp.spw.workshop.api.hook.MethodHookParam;

/** Check pinned playback ABI and the native Workshop call site without starting the player. */
class HostProbe {
    static Class<?> host(int code) throws Exception { return Class.forName("androidx.compose.ui."+(char)code); }
    static Object create(int code,Object... args) throws Exception {
        return Arrays.stream(host(code).getConstructors()).filter(c->c.getParameterCount()==args.length)
            .findFirst().orElseThrow().newInstance(args);
    }
    static Object named(int code,String name) throws Exception {
        return Arrays.stream(host(code).getEnumConstants()).filter(e->((Enum<?>)e).name().equals(name)).findFirst().orElseThrow();
    }
    static Object track(String id) throws Exception {
        var range=create(0xf5e,0L,180000L);
        var region=create(0x6cf,range,-16.0,.4,.8);
        var tempo=create(0x90d,100.0,List.of(0L,600L,1200L,1800L),0,.9,.9,.9);
        var profile=create(0x910,id,180000L,range,-15.0,-2.0,tempo,region,region);
        var spectral=create(0x935,range,-20.0,-18.0,-25.0,.4);
        return create(0x507,profile,create(0x576,id,spectral,spectral));
    }
    static Object arranged(Object transition) throws Exception {
        return create(0x574, transition, named(0x6c6, "TRANSPARENT_BLEND"), List.of(), List.of(), "transparent_blend");
    }
    static Object queueItem(int index, String id) throws Exception {
        return Class.forName("com.xuncorp.spc.core.queue.\u037f").getConstructors()[0].newInstance(
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
        var bridge=new Host1193(HostProbe.class.getClassLoader());
        var planner=bridge.getPlanner();
        if(planner.getParameterCount()!=6 || planner.getReturnType()!=host(0x574))throw new AssertionError(planner);
        Class<?> resolver=Class.forName("com.xuncorp.voxzen.workshop.hooks.\u052a");
        Object singleton=Arrays.stream(resolver.getDeclaredFields()).filter(f->Modifier.isStatic(f.getModifiers())&&f.getType()==resolver).findFirst().orElseThrow().get(null);
        Method resolve=Arrays.stream(resolver.getDeclaredMethods()).filter(m->Modifier.isPublic(m.getModifiers())&&Arrays.equals(m.getParameterTypes(),new Class<?>[]{String.class,String.class,String.class,List.class})).findFirst().orElseThrow();
        var site=resolve.invoke(singleton,host(0x2bd).getName(),"\u037f",host(0xb8f).getName(),List.of("java.lang.String","androidx.compose.ui.Modifier"));
        if(site==null)throw new AssertionError("Missing config page hook");
        var gain=create(0x99f,List.of(create(0x6c8,0.,1.,0.),create(0x6c8,1.,0.,1.)));
        var baseline=create(0x44c,named(0x28e,"BEAT_ALIGNED"),create(0x264,.9,named(0x778,"HIGH")),
            150000L,0L,6000L,1.,1.,gain,List.of());
        var initial=arranged(baseline);
        var caps=create(0x4ed,Set.of("spectral_automation","filter_automation","beat_synchronized_delay"),(byte)0);
        var call=new Call(planner,new Object[]{track("A"),track("B"),create(0x163),100000L,caps,null},initial);
        var custom=new Plan(155.,3.,100.,100.,4,"fade-cut",true,.5,false,"low-out",true,"bass-swap-end",false,false);
        var saved=Map.of("1:AB",custom);
        if(bridge.apply(call,saved)==null)throw new AssertionError("Saved pair missed");
        var transition=host(0x574).getMethod("\u037f").invoke(call.result);
        if(!host(0x44c).getMethod("\u0529").invoke(transition).equals(155000L) ||
           !host(0x44c).getMethod("\u052a").invoke(transition).equals(3000L) ||
           !host(0x44c).getMethod("\u052b").invoke(transition).equals(9600L))throw new AssertionError("Incorrect cues or length");
        if(((List<?>)host(0x574).getMethod("\u0529").invoke(call.result)).size()!=6)throw new AssertionError("Missing automation");
        call.result=arranged(baseline);
        if(bridge.apply(call,Map.of())==null)throw new AssertionError("Automatic mix missing");
        call.result=arranged(baseline);call.args[3]=170000L;
        if(!bridge.apply(call,saved).startsWith("已过切点") ||
           host(0x574).getMethod("\u037f").invoke(call.result)!=baseline)throw new AssertionError("Late mix accepted");
        call.result=arranged(baseline);call.args[3]=100000L;

        var delayed=new Plan(178.,-1.5,100.,100.,4,"smooth",false,.5,false,"none",false,"none",false,false);
        var window=delayed.window(180000L,180000L,1.0);
        if(window.getStartMs()!=179500L || window.getEntryMs()!=0L || window.getDurationMs()!=500L)
            throw new AssertionError("Delayed and cropped window differs");
        bridge.apply(call,Map.of("1:AB",delayed));
        transition=host(0x574).getMethod("\u037f").invoke(call.result);
        if(!host(0x44c).getMethod("\u0529").invoke(transition).equals(window.getStartMs()) ||
           !host(0x44c).getMethod("\u052a").invoke(transition).equals(window.getEntryMs()) ||
           !host(0x44c).getMethod("\u052b").invoke(transition).equals(window.getDurationMs()))
            throw new AssertionError("Host did not use the shared window");

        var nativePlan=new Plan(155.,3.,100.,100.,4,"smooth",false,.5,false,"none",false,"none",false,true);
        call.result=arranged(baseline);
        bridge.apply(call,Map.of("1:AB",nativePlan));
        if(host(0x574).getMethod("\u037f").invoke(call.result)!=baseline)
            throw new AssertionError("Host-controlled pair replaced the host plan");

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
        if(bridge.queueView(queue("CircleEnd",normal,0,List.of(),-1),library).getNextPair()!=null ||
           bridge.queueView(queue("CircleEnd",normal,3,List.of(),-1),library).getNextPair()!=null ||
           bridge.queueView(queue("RepeatOne",normal,2,List.of(),-1),library).getNextPair()!=null ||
           !bridge.queueView(queue("Circle",normal,3,List.of(),-1),library).getNextPair().getSecond().getId().equals("A"))
            throw new AssertionError("Queue adjacency or repeat handling differs");
        System.out.println("PASS Workshop binding, saved/automatic/native plans, shared window, late fallback and queue modes");

        var previewPlan=new Plan(2.,-1.5,100.,100.,4,"smooth",false,.5,false,"none",false,"none",false,false);
        var previewWindow=previewPlan.window(4000L,12000L,1.0);
        System.out.println("PREVIEW_JOB={\"plan\":" + previewPlan.json() + ",\"window\":" + previewWindow.json(previewPlan) + "}");
    }
}
