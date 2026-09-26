// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import thc.runtime.BytecodeProgram;
import thc.runtime.ExecutableProgram;
import thc.runtime.Program;
import java.nio.file.*;
import java.util.*;

/** Fixed-input correctness/graph harness for actual exported Core and THC runtimes. No timing. */
public final class SimdRuntimeGraphProbe {
    static boolean valid(RootCallTarget t) throws Exception { return (boolean)t.getClass().getMethod("isValidLastTier").invoke(t); }
    static void compile(RootCallTarget t) throws Exception {
        t.getClass().getMethod("compile",boolean.class).invoke(t,true);
        if(!valid(t)) throw new AssertionError("No installed code: "+t);
    }
    static void check(ExecutableProgram program,String entry,int arity,List<String[]> rows) {
        RootCallTarget host=program.hostEntryTarget(arity);
        Object closure=program.entryValue(entry);
        for(String[] row:rows) {
            Object[] input=new Object[arity]; for(int i=0;i<arity;i++) input[i]=Long.valueOf(row[i+1]);
            long expected=Long.parseLong(row[arity+1]);
            Object actual=host.call(closure,input);
            if(!(actual instanceof Long) || ((Long)actual)!=expected)
                throw new AssertionError(entry+" "+Arrays.toString(input)+": oracle="+expected+" THC="+actual);
        }
    }
    @SuppressWarnings("unchecked") public static void main(String[] args) throws Exception {
        if(args.length!=6) throw new IllegalArgumentException("module.json oracle.tsv entry ast|bytecode inline|residual native|model");
        String entry=args[2],backend=args[3]; boolean inline=args[4].equals("inline");
        Map<String,Object> module=(Map<String,Object>)Json.INSTANCE.parse(Files.readString(Path.of(args[0])));
        Map<String,Object> linked=new LinkedHashMap<>(CoreModules.INSTANCE.reachable(module,entry,false));
        linked.put("instrument",false); linked.put("diagnosticUnsupported",false);
        List<Map<String,Object>> bindings=(List<Map<String,Object>>)linked.get("bindings");
        Map<String,Object> selected=bindings.stream().filter(b->entry.equals(b.get("name"))||entry.equals(b.get("id"))).findFirst().orElseThrow();
        int arity=((Number)selected.get("arity")).intValue();
        List<String[]> rows=Files.readAllLines(Path.of(args[1])).stream().map(s->s.split("\t"))
            .filter(r->r[0].equals(entry)).toList();
        if(rows.isEmpty()) throw new AssertionError("No oracle rows");
        for(String[] row:rows) if(row.length!=arity+2) throw new AssertionError("Oracle row arity mismatch");
        Context.Builder builder=Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation","false").option("engine.MultiTier","false")
            .option("engine.CompilationFailureAction","Throw").option("engine.SingleTierCompilationThreshold","10000000");
        if(!inline) builder.option("compiler.Inlining","false");
        try(Context context=builder.build()) {
            context.initialize("thc"); context.enter();
            try {
                Language language=TruffleLanguage.LanguageReference.create(Language.class).get(null);
                ExecutableProgram program=backend.equals("ast")?new Program(language,linked,false):new BytecodeProgram(language,linked);
                for(int i=0;i<40;i++) check(program,entry,arity,rows);
                if(!inline) for(Map<String,Object>b:bindings)
                    if(!b.get("id").equals(selected.get("id")) && ((List<?>)b.get("expr")).get(0).equals("lam")) compile(program.entryTarget((String)b.get("id")));
                RootCallTarget target=program.entryTarget((String)selected.get("id"));
                compile(target);
                check(program,entry,arity,rows);
                if(!valid(target)) throw new AssertionError("Entry invalidated after compiled execution: "+entry);
                check(program,entry,arity,rows);
                if(!valid(target)) throw new AssertionError("Entry invalidated after repeated compiled execution: "+entry);
                System.out.println("PASS entry="+entry+" backend="+backend+" mode="+args[4]+" oracleOrigin="+args[5]+" oracleRows="+rows.size()+" arity="+arity+" validAfterExecution=true");
                System.out.println("diagnostics="+Json.INSTANCE.stringify(program.diagnostics()));
            } finally { context.leave(); }
        }
    }
}
