import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleStackTrace;
import com.oracle.truffle.api.TruffleStackTraceElement;
import thc.runtime.GuestRoot;
import org.graalvm.polyglot.Context;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import thc.runtime.BytecodeProgram;
import thc.runtime.ExecutableProgram;
import thc.runtime.Program;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Real exported Set diagnostic execution. Passing covered rows does not establish strict support. */
public final class SetDiagnosticProbe {
    static Map<RootCallTarget,String> targetNames=new LinkedHashMap<>();
    @SuppressWarnings("unchecked") static void indexTargets(ExecutableProgram p,Map<String,Object> linked) {
        targetNames.clear();
        for(Map<String,Object>b:(List<Map<String,Object>>)linked.get("bindings"))
            if(((List<?>)b.get("expr")).get(0).equals("lam"))targetNames.put(p.entryTarget((String)b.get("id")),(String)b.get("id"));
    }
    static void failureTargets(RuntimeException failure,long input) {
        List<String> frames=new ArrayList<>();
        TruffleStackTrace.fillIn(failure);
        for(TruffleStackTraceElement f:TruffleStackTrace.getStackTrace(failure)) {
            List<String>matches=new ArrayList<>();
            for(var e:targetNames.entrySet())if(e.getKey()==f.getTarget() ||
                e.getKey().getRootNode() instanceof GuestRoot root && root.isSelf(f.getTarget()))matches.add(e.getValue());
            frames.add(matches.isEmpty()?f.getTarget().getRootNode().getName():String.join("|",matches));
        }
        System.out.println("FAILED_INPUT_GUEST_TARGETS input="+input+" targets="+Json.INSTANCE.stringify(frames));
    }
    static String sha(Path p) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))); }
    static boolean valid(RootCallTarget t) throws Exception { return (boolean)t.getClass().getMethod("isValidLastTier").invoke(t); }
    static void compile(RootCallTarget t) throws Exception {
        t.getClass().getMethod("compile",boolean.class).invoke(t,true);
        if(!valid(t)) throw new AssertionError("No installed code: "+t);
    }
    static long count(ExecutableProgram p,String name) { return ((Number)p.diagnostics().get(name)).longValue(); }
    static void policy(ExecutableProgram p) {
        Map<String,Object>d=p.diagnostics();
        if(!"diagnostic-traps".equals(d.get("unsupportedPolicy"))) throw new AssertionError("Not diagnostic mode");
        if(((List<?>)d.get("deferredUnsupported")).isEmpty()) throw new AssertionError("Expected unresolved cold frontier");
        if(count(p,"unsupportedTraps")!=0 || count(p,"blackholes")!=0) throw new AssertionError(d.toString());
    }
    static void check(ExecutableProgram p,List<List<Number>> rows,String phase) {
        RootCallTarget host=p.hostEntryTarget(1); Object closure=p.entryValue("setAggregate");
        for(List<Number>row:rows) {
            long input=row.get(0).longValue(),expected=row.get(1).longValue();
            long before=count(p,"compiledEntries");
            Object actual;
            try { actual=host.call(closure,new Object[]{input}); }
            catch(RuntimeException failure) { failureTargets(failure,input);throw failure; }
            if((phase.equals("compiled-warm") || phase.equals("post-cold-compiled")) && count(p,"compiledEntries")<=before)
                throw new AssertionError("No installed guest entry for "+phase+" input="+input);
            if(!(actual instanceof Long) || ((Long)actual)!=expected) throw new AssertionError(phase+" input="+input+" native="+expected+" THC="+actual);
            policy(p);
            System.out.println("VERIFIED_DIAGNOSTIC_SET\t"+phase+"\t"+input+"\t"+actual);
        }
    }
    static ExecutableProgram program(Language l,Map<String,Object> m,String backend) { return backend.equals("ast")?new Program(l,m):new BytecodeProgram(l,m); }
    static Context context(boolean compiled) {
        Context.Builder b=Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation","false").option("engine.MultiTier","false")
            .option("engine.CompilationFailureAction","Throw").option("engine.SingleTierCompilationThreshold","10000000");
        if(!compiled)b.option("engine.Compilation","false");
        return b.build();
    }
    @SuppressWarnings("unchecked") public static void main(String[] args) throws Exception {
        if(args.length<2 || args.length>3) throw new IllegalArgumentException("CASES_JSON ast|bytecode [--graph-min-view]");
        Path casesPath=Path.of(args[0]); String backend=args[1];
        if(!backend.equals("ast") && !backend.equals("bytecode"))throw new IllegalArgumentException("Unknown backend: "+backend);
        if(args.length==3 && !args[2].equals("--graph-min-view"))throw new IllegalArgumentException("Unknown mode: "+args[2]);
        Map<String,Object>cases=(Map<String,Object>)Json.INSTANCE.parse(Files.readString(casesPath));
        for(var e:((Map<String,String>)cases.get("artifactHashes")).entrySet())
            if(!sha(Path.of(e.getKey())).equals(e.getValue()))throw new AssertionError("Changed exported/native artifact: "+e.getKey());
        List<Map<String,String>>validationToolDrift=new ArrayList<>();
        for(var e:((Map<String,String>)cases.get("inputHashes")).entrySet()) if(!sha(Path.of(e.getKey())).equals(e.getValue())) {
            if(!e.getKey().endsWith("/scripts/audit-core.py") && !e.getKey().endsWith("/scripts/core-capabilities.json") &&
               !e.getKey().endsWith("/scripts/prepare-library-tests.py"))
                throw new AssertionError("Changed exporter/library input: "+e.getKey());
            validationToolDrift.add(Map.of("path",e.getKey(),"manifestSha256",e.getValue(),"currentSha256",sha(Path.of(e.getKey()))));
        }
        System.out.println("REUSED_EXPORTS_CURRENT_VALIDATION_TOOL_DRIFT="+Json.INSTANCE.stringify(validationToolDrift));
        Map<String,Object>group=((List<Map<String,Object>>)cases.get("groups")).stream().filter(g->g.get("id").equals("set")).findFirst().orElseThrow();
        Map<String,Object>entry=((List<Map<String,Object>>)group.get("entries")).get(0);
        List<List<Number>>warm=(List<List<Number>>)entry.get("warm"),cold=(List<List<Number>>)entry.get("cold");
        List<List<Number>>all=new ArrayList<>(warm);all.addAll(cold);if(all.size()!=22)throw new AssertionError("Expected 22 native rows");
        List<String>nativeRows=Files.readAllLines(casesPath.resolveSibling("oracle.tsv")).stream().filter(s->s.startsWith("setAggregate\t")).toList();
        List<String>manifestRows=all.stream().map(r->"setAggregate\t"+r.get(0).longValue()+"\t"+r.get(1).longValue()).toList();
        if(!nativeRows.equals(manifestRows))throw new AssertionError("Set manifest disagrees with native oracle");
        List<Map<String,Object>>modules=new ArrayList<>();
        for(String path:(List<String>)group.get("modules"))modules.add((Map<String,Object>)Json.INSTANCE.parse(Files.readString(Path.of(path))));
        Map<String,Object>linked=new LinkedHashMap<>(CoreModules.INSTANCE.reachable(CoreModules.INSTANCE.merge(modules),"setAggregate"));
        linked.put("instrument",true); linked.put("diagnosticUnsupported",true);
        if(args.length==3) {
            try(Context c=context(true)) {
                c.initialize("thc");c.enter();
                try {
                    Language l=TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    ExecutableProgram p=program(l,linked,backend);indexTargets(p,linked);
                    for(int i=0;i<3;i++)check(p,all,"graph-train");
                    RootCallTarget target=p.entryTarget("main:Data.Set.Internal.$wgo1");
                    compile(target);long before=count(p,"compiledEntries");check(p,all,"graph-replay");
                    if(count(p,"compiledEntries")<=before)throw new AssertionError("Compiled minViewSure helper was not entered");
                    if(!valid(target))throw new AssertionError("minViewSure helper invalidated on replay");
                    System.out.println("GRAPH_COMPILED_ENTRIES="+(count(p,"compiledEntries")-before));
                    System.out.println("PASS_DIAGNOSTIC_SET_JOIN_GRAPH backend="+backend+" nativeRows=22 helper=main:Data.Set.Internal.$wgo1 valid=true unsupportedTraps=0 strictSupported=false");
                }finally{c.leave();}
            }
            return;
        }
        try(Context c=context(false)) {
            c.initialize("thc");c.enter();
            try {
                Language l=TruffleLanguage.LanguageReference.create(Language.class).get(null);
                Map<String,Object>strict=new LinkedHashMap<>(linked);strict.put("diagnosticUnsupported",false);
                try { program(l,strict,backend);throw new AssertionError("Strict Set unexpectedly accepted"); }
                catch(RuntimeException expected) {
                    String message=String.valueOf(expected.getMessage()).toLowerCase(Locale.ROOT);
                    if(!message.contains("unsupported") && !message.contains("unresolved"))throw expected;
                    System.out.println("EXPECTED_STRICT_REJECTION\t"+backend+"\t"+expected.getMessage());
                }
                ExecutableProgram p=program(l,linked,backend);indexTargets(p,linked);
                System.out.println("LOADED_DIAGNOSTIC_FRONTIER="+Json.INSTANCE.stringify(p.diagnostics().get("deferredUnsupported")));
                check(p,all,"interpreted");
                System.out.println("INTERPRETED_DIAGNOSTICS="+Json.INSTANCE.stringify(p.diagnostics()));
                List<Map<String,Object>>bindings=(List<Map<String,Object>>)linked.get("bindings");
                List<Map<String,Object>>calls=new ArrayList<>();
                for(Map<String,Object>b:bindings) if(((List<?>)b.get("expr")).get(0).equals("lam")) {
                    RootCallTarget t=p.entryTarget((String)b.get("id"));
                    int n=(int)t.getClass().getMethod("getCallCount").invoke(t);
                    if(n>0)calls.add(Map.of("id",b.get("id"),"calls",n));
                }
                System.out.println("INTERPRETED_CALLED_EXPORTED_TARGETS="+Json.INSTANCE.stringify(calls));
                for(String required:List.of("main:Data.Set.Internal.$wgo","main:Data.Set.Internal.$wgo1","main:Data.Set.Internal.glue"))
                    if(calls.stream().noneMatch(call->required.equals(call.get("id"))))throw new AssertionError("Native rows did not execute "+required);
            }finally{c.leave();}
        }
        try(Context c=context(true)) {
            c.initialize("thc");c.enter();
            try {
                Language l=TruffleLanguage.LanguageReference.create(Language.class).get(null);
                ExecutableProgram p=program(l,linked,backend);indexTargets(p,linked);
                for(int i=0;i<40;i++) {
                    List<Number>r=warm.get(i%warm.size());
                    Object got=p.hostEntryTarget(1).call(p.entryValue("setAggregate"),new Object[]{r.get(0).longValue()});
                    if(!Long.valueOf(r.get(1).longValue()).equals(got))throw new AssertionError("warm mismatch");
                }
                policy(p);RootCallTarget target=p.entryTarget("setAggregate");compile(target);
                check(p,warm,"compiled-warm");if(!valid(target))throw new AssertionError("Warm entry invalidated");
                check(p,cold,"after-compilation-cold");
                for(int i=0;i<2;i++)check(p,all,"train-all");
                compile(target);check(p,all,"post-cold-compiled");
                if(!valid(target))throw new AssertionError("Final replay invalidated exact entry");
                if(count(p,"localJoinTransfers")==0)throw new AssertionError("No real exported local join executed");
                System.out.println("PASS_DIAGNOSTIC_SET backend="+backend+" nativeRows=22 warmValid=true postColdValid=true unsupportedTraps=0 strictSupported=false");
                System.out.println("FINAL_DIAGNOSTICS="+Json.INSTANCE.stringify(p.diagnostics()));
            }finally{c.leave();}
        }
    }
}
