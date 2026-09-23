import com.oracle.truffle.api.*;
import com.oracle.truffle.api.nodes.*;
import org.graalvm.polyglot.*;
import thc.*;
import thc.runtime.*;
import java.nio.file.*;
import java.util.*;
public class CycleAllocationProbe {
 static Set<RootCallTarget> active(RootCallTarget host,RootCallTarget original) {
  Set<RootCallTarget> result=new LinkedHashSet<>();
  for(DirectCallNode call:NodeUtil.findAllNodeInstances(host.getRootNode(),DirectCallNode.class))
   if(call.getCallTarget()==original) result.add((RootCallTarget)call.getCurrentCallTarget());
  return result;
 }
 static boolean valid(RootCallTarget t)throws Exception{return (boolean)t.getClass().getMethod("isValidLastTier").invoke(t);}
 @SuppressWarnings("unchecked") public static void main(String[] args)throws Exception {
  Map<String,Object> module=(Map<String,Object>)Json.INSTANCE.parse(Files.readString(Path.of(args[0])));
  try(Context ctx=Context.newBuilder("thc").allowExperimentalOptions(true)
   .option("engine.BackgroundCompilation","false").option("engine.MultiTier","false")
   .option("engine.CompilationFailureAction","Throw").option("engine.TraceCompilation","true")
   .option("engine.TraceTransferToInterpreter","true").build()) {
   ctx.initialize("thc");ctx.enter();try{
    thc.Language language=TruffleLanguage.LanguageReference.create(thc.Language.class).get(null);
    ExecutableProgram p=args[1].equals("ast")?new Program(language,module):new BytecodeProgram(language,module);
    var fn=ctx.asValue(new EntryValue(p,"cycle",2,null));
    long[][] rows={{Long.MIN_VALUE,0},{Long.MAX_VALUE,1},{-4097,2},{0,3},{4097,17},{3000000000L,64}};
    for(long[] row:rows) {
      long expected=row[0]+7+row[1]*(row[1]+1)/2;
      if(fn.execute(row[0],row[1]).asLong()!=expected)throw new AssertionError("Interpreted mismatch");
    }
    fn.invokeMember("compile");
    var host=p.hostEntryTarget(2);var guest=p.entryTarget("cycle");
    var observed=active(host,guest);if(observed.isEmpty())throw new AssertionError("Missing active guest target");
    if(!valid(host)||!valid(guest))throw new AssertionError("Initial compilation failed");
    for(int i=rows.length-1;i>=0;--i) {
      long[] row=rows[i];long expected=row[0]+7+row[1]*(row[1]+1)/2;
      long r=fn.execute(row[0],row[1]).asLong();
      if(!observed.equals(active(host,guest)))throw new AssertionError("Active target changed");
      for(var target:observed)if(!valid(target))throw new AssertionError("Active target invalid");
      if(r!=expected||!valid(host)||!valid(guest))throw new AssertionError("First-pass compiled gate "+Arrays.toString(row));
    }
    System.out.println("PASS "+args[1]+" "+args[0]+" rows="+rows.length+" hostValid=true guestValid=true; dynamic depth and payload addition");
   }finally{ctx.leave();}
  }
 }
}
