import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import java.nio.file.*;
import java.util.*;
import org.graalvm.polyglot.*;
import thc.*;
import thc.runtime.*;

/** Isolated genuine-Core probe. Deliberately avoids serializing the merged request. */
public final class SequenceEmptyProbe {
  static long count(ExecutableProgram p) { return ((Number)p.diagnostics().get("compiledEntries")).longValue(); }
  static boolean valid(RootCallTarget t) throws Exception { return (Boolean)t.getClass().getMethod("isValidLastTier").invoke(t); }
  static Set<RootCallTarget> active(RootCallTarget host, RootCallTarget original) {
    Set<RootCallTarget> result = new HashSet<>();
    for (DirectCallNode call : NodeUtil.findAllNodeInstances(host.getRootNode(), DirectCallNode.class))
      if (call.getCallTarget() == original) result.add((RootCallTarget)call.getCurrentCallTarget());
    if (result.isEmpty()) result.add(original);
    return result;
  }
  static Context context(boolean compile) {
    Context.Builder builder = Context.newBuilder("thc").allowExperimentalOptions(true);
    if (!compile) return builder.option("engine.Compilation", "false").build();
    return builder.option("engine.BackgroundCompilation", "false")
      .option("engine.MultiTier", "false").option("engine.SingleTierCompilationThreshold", "10000")
      .option("engine.CompilationFailureAction", "Throw").option("compiler.CompilationTimeout", "30")
      .option("compiler.MaximumGraalGraphSize", "100000").build();
  }
  static void check(Value fn, ExecutableProgram p, List<Number> row, String label, boolean compiled,
      RootCallTarget host, RootCallTarget original, Set<RootCallTarget> active) throws Exception {
    long before = count(p), input = row.get(0).longValue(), expected = row.get(1).longValue();
    long actual = fn.execute(input).asLong();
    if (actual != expected) throw new AssertionError(label + " result " + actual + " != " + expected);
    if (compiled) {
      boolean hostValid = valid(host), originalValid = valid(original);
      boolean activeValid = true;
      for (RootCallTarget t : active) activeValid &= valid(t);
      boolean identity = active.equals(active(host, original));
      System.out.println("COMPILED_STATE\t"+label+"\t"+input+"\tbefore="+before+"\tafter="+count(p)
        +"\thost="+hostValid+"\toriginal="+originalValid+"\tactive="+activeValid+"\tidentity="+identity);
      if (count(p) <= before || !hostValid || !originalValid || !activeValid || !identity)
        throw new AssertionError(label + " installed-entry validity at " + input);
    }
    if (((Number)p.diagnostics().get("unsupportedTraps")).longValue() != 0 ||
        ((Number)p.diagnostics().get("blackholes")).longValue() != 0)
      throw new AssertionError(label + " trap/blackhole");
    System.out.println("VERIFIED_SEQUENCE\t"+label+"\t"+input+"\t"+actual);
  }
  @SuppressWarnings("unchecked") public static void main(String[] args) throws Exception {
    if (args.length != 3) throw new IllegalArgumentException("backend modules-list selected-native-cases");
    String backend = args[0];
    List<Map<String,Object>> modules = new ArrayList<>();
    for (String path : Files.readAllLines(Path.of(args[1])))
      modules.add((Map<String,Object>)Json.INSTANCE.parse(Files.readString(Path.of(path))));
    Map<String,Object> merged = CoreModules.INSTANCE.merge(modules);
    Map<String,List<List<Number>>> cases = (Map)Json.INSTANCE.parse(Files.readString(Path.of(args[2])));
    int failures = 0;
    for (var entry : cases.entrySet()) for (boolean compiled : new boolean[]{false,true}) {
      String name = entry.getKey(), label = backend+"/"+name+"/"+(compiled?"compiled":"interpreted");
      try (Context context = context(compiled)) {
        context.initialize("thc"); context.enter();
        try {
          thc.Language language = TruffleLanguage.LanguageReference.create(thc.Language.class).get(null);
          Map<String,Object> linked = new LinkedHashMap<>(CoreModules.INSTANCE.reachable(merged,name));
          linked.put("instrument",true);
          ExecutableProgram program = backend.equals("ast") ? new Program(language,linked) : new BytecodeProgram(language,linked);
          Value fn = context.asValue(new EntryValue(program,name,1,null));
          List<List<Number>> all = entry.getValue(), warm = all.subList(0,3), cold = all.subList(3,all.size());
          if (!compiled) {
            for (var row : all) check(fn,program,row,label,false,null,null,null);
          } else {
            for (int i=0;i<40;i++) check(fn,program,warm.get(i%3),label+"/warmup",false,null,null,null);
            if (!fn.invokeMember("compile").asBoolean()) throw new AssertionError("installation");
            RootCallTarget host = program.hostEntryTarget(1), original = program.entryTarget(name);
            Set<RootCallTarget> active = active(host,original);
            for (var row : warm) check(fn,program,row,label+"/warm",true,host,original,active);
            for (var row : cold) check(fn,program,row,label+"/cold",false,null,null,null);
            for (int i=0;i<Math.max(40,all.size());i++) check(fn,program,all.get(i%all.size()),label+"/all-training",false,null,null,null);
            if (!fn.invokeMember("compile").asBoolean()) throw new AssertionError("post-cold installation");
            active = active(host,original);
            for (int i=all.size()-1;i>=0;i--) check(fn,program,all.get(i),label+"/replay",true,host,original,active);
          }
          System.out.println("SEQUENCE_PASS\t"+label+"\trows="+all.size());
        } finally { context.leave(); }
      } catch (Throwable failure) {
        failures++;
        System.out.println("SEQUENCE_FAILURE\t"+label+"\t"+failure);
        failure.printStackTrace(System.out);
      }
    }
    System.out.println("SEQUENCE_SUMMARY\t"+backend+"\tfailures="+failures);
    if (failures != 0) System.exit(1);
  }
}
