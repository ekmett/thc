// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import thc.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.OriginalStdioChecks.*;

/** The FCall is GHC's installed c_isatty declaration, not a synthetic alias. */
@SuppressWarnings("unchecked")
class OriginalHandleReadinessNativeTest {
    private final File root=new File(System.getProperty("thc.projectRoot"));
    private final File directory=new File(root,"build/original-handle-readiness");
    private Map<String,Object> json(String path) throws Exception { return (Map<String,Object>)Json.parse(Files.readString(new File(root,path).toPath())); }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true,target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private int loweredEntryRoots(Map<String,Object> module,String name) { var evidence=new ArrayCoreEvidence(module,name); var outer=(List<Object>)evidence.getRoot().get("expr"); assertEquals(1,evidence.getBindings().size()); assertTrue(evidence.globalReferences(outer).isEmpty()); var state=evidence.stateLambda(outer); assertEquals(list(outer,state),evidence.guestLambdas(outer),name+": preserve the two exported lambdas"); var lowered=evidence.loweredStateLambdas(outer); assertEquals(list(outer),lowered,name+": the immediate State# redex executes in-frame"); return lowered.size(); }
    private List<Map<String,Number>> checkedRows() throws Exception {
        var manifest=json("build/original-handle-readiness/manifest.json"); assertEquals(1L,manifest.get("schema")); assertEquals(3L,manifest.get("nativeRows")); hashes(root,manifest.get("inputHashes"),Set.of("test/fixtures/compiler/OriginalHandleReadinessAudit.hs","test/fixtures/compiler/OriginalHandleReadinessNative.hs","test/haskell-fixtures/OriginalHandleReadinessFixtures.hs","bin/core_original_foreign.py")); hashes(root,manifest.get("artifactHashes"),Set.of("build/original-handle-readiness/oracle.json","build/original-handle-readiness/pre/core/OriginalHandleReadinessAudit.json","build/original-handle-readiness/post/core/OriginalHandleReadinessAudit.json"),"build/original-handle-readiness/"); var rows=(List<Map<String,Number>>)Json.parse(Files.readString(new File(directory,"oracle.json").toPath())); var fds=new ArrayList<Integer>(); for(var row:rows) fds.add(row.get("fd").intValue()); assertEquals(List.of(-1,1,2),fds); var abi=StdioHostAbi.load(); for(var row:rows) { assertEquals(0,row.get("result").intValue()); assertEquals(row.get("fd").intValue()<0?abi.error(4):abi.notTerminal(),row.get("errno").longValue()); } return rows;
    }
    private static <T,E extends Throwable> T propagate(Throwable error) throws E { throw (E)error; }
    @Test @EnabledOnOs(OS.LINUX)
    @EnabledIfSystemProperty(named="os.arch",matches="amd64|x86_64")
    void privatePtyUsesOriginalIsattyOnItsOwnedNativeDescriptorAfterCompilation() throws Exception {
        checkedRows(); var oracle=new ProcessBuilder(new File(directory,"native/oracle").getAbsolutePath(),"--hold-pty").start(); boolean completed=false;
        try {
            var line=CompletableFuture.supplyAsync(()->{ try { return new BufferedReader(new InputStreamReader(oracle.getInputStream(),StandardCharsets.UTF_8)).readLine(); } catch(IOException error) { return OriginalHandleReadinessNativeTest.<String,RuntimeException>propagate(error); } }).get(15,TimeUnit.SECONDS); if(line==null) fail("PTY oracle ended before announcing its path"); var announced=line.split("\t",-1); assertEquals(2,announced.length); assertTrue(announced[0].startsWith("/dev/pts/"),"Expected a private Linux PTY"); assertEquals("1",announced[1],"Original GHC c_isatty must see the live slave"); var pathBytes=announced[0].getBytes(StandardCharsets.UTF_8); var path=ManagedAddress.fromByteArray(Arrays.copyOf(pathBytes,pathBytes.length+1));
            for(var stage:List.of("pre","post")) { var prefix="build/original-handle-readiness/"+stage; var modules=new ArrayList<Map<String,Object>>(); for(var name:List.of("OriginalHandleReadinessAudit","THC.InterfaceClosure")) modules.add(json(prefix+"/core/"+name+".json")); var module=CoreModules.merge(modules); int executedRoots=loweredEntryRoots(module,"originalIsTerminal");
                for(var backend:List.of("ast","bytecode")) try(var context=NativeFileProvider.createContext(Set.of(),ContextProfile.SYNCHRONOUS_TEST)) { context.enter(); try { var state=Language.currentState(); long fd=state.getFiles().openOriginal(path,0x100,0,OriginalStdioOp.OPEN); // Linux O_NOCTTY | O_RDONLY.
                    assertTrue(fd>=3,stage+"/"+backend+": open the private PTY"); try { var linked=with(CoreModules.reachable(module,"originalIsTerminal"),"instrument",true); var language=TruffleLanguage.LanguageReference.create(Language.class).get(null); ExecutableProgram program=backend.equals("ast")?new Program(language,linked):new BytecodeProgram(language,linked); var target=program.entryTarget("originalIsTerminal"); for(int i=0;i<3;i++) assertEquals(1L,Calls.target(target,new Object[]{0L,fd})); var active=targets(target); assertEquals(executedRoots,active.size(),"Exactly the lowered entry roots"); for(var item:active) { item.getClass().getMethod("compile",boolean.class).invoke(item,true); valid(item); } long before=((Number)program.diagnostics().get("compiledEntries")).longValue(); assertEquals(1L,Calls.target(target,new Object[]{0L,fd})); assertEquals(before+executedRoots,((Number)program.diagnostics().get("compiledEntries")).longValue()); assertEquals(active,targets(target)); for(var item:active) valid(item); long alias=state.getFiles().duplicate(fd); assertTrue(alias>=3); assertEquals(0L,state.getFiles().close(fd)); assertEquals(1L,state.getStdio().isTerminal(alias),"The last owned alias retains the PTY"); assertEquals(0L,state.getFiles().close(alias)); assertEquals(0L,state.getStdio().isTerminal(alias)); assertEquals(StdioHostAbi.load().error(4),state.getStdio().errno()); } finally { state.getFiles().close(fd); }
                } finally { context.leave(); } }
            }
            completed=true;
        } finally {
            if(!completed) { oracle.destroyForcibly(); try { oracle.waitFor(5,TimeUnit.SECONDS); } catch(Throwable ignored) {} } else {
                // The child may have already failed; still reap it and report
                // its exit status instead of masking the outcome with a broken pipe.
                try { oracle.getOutputStream().write('\n'); oracle.getOutputStream().flush(); } catch(Throwable ignored) {} if(!oracle.waitFor(5,TimeUnit.SECONDS)) { oracle.destroyForcibly(); assertTrue(oracle.waitFor(5,TimeUnit.SECONDS),"PTY oracle did not terminate"); } assertEquals(0,oracle.exitValue(),new String(oracle.getErrorStream().readAllBytes(),StandardCharsets.UTF_8));
            }
        }
    }
    @Test void originalIsattyAndErrnoMatchNativeBeforeAndAfterExplicitCompilation() throws Exception {
        var rows=checkedRows();
        for(var stage:List.of("pre","post")) { var prefix="build/original-handle-readiness/"+stage; var modules=new ArrayList<Map<String,Object>>(); for(var name:List.of("OriginalHandleReadinessAudit","THC.InterfaceClosure")) modules.add(json(prefix+"/core/"+name+".json")); var module=CoreModules.merge(modules); for(var name:List.of("originalIsTerminal","originalIsTerminalErrno")) { var audit=json(prefix+"/"+name+".audit.json"); assertEquals(true,audit.get("accepted")); assertEquals(List.of(),audit.get("issues")); assertEquals(List.of(),audit.get("missingGlobals")); } var executedRoots=new LinkedHashMap<String,Integer>(); for(var name:List.of("originalIsTerminal","originalIsTerminalErrno")) executedRoots.put(name,loweredEntryRoots(module,name));
            for(var backend:List.of("ast","bytecode")) { var output=new ByteArrayOutputStream(); var errors=new ByteArrayOutputStream(); try(var context=Context.newBuilder("thc").allowIO(IOAccess.NONE).out(output).err(errors).allowExperimentalOptions(true).option("engine.BackgroundCompilation","false").option("engine.MultiTier","false").option("engine.CompilationFailureAction","Throw").build()) { context.initialize("thc"); context.enter(); try {
                var language=TruffleLanguage.LanguageReference.create(Language.class).get(null); var programs=new LinkedHashMap<String,ExecutableProgram>(); for(var name:List.of("originalIsTerminal","originalIsTerminalErrno")) { var linked=with(CoreModules.reachable(module,name),"instrument",true); programs.put(name,backend.equals("ast")?new Program(language,linked):new BytecodeProgram(language,linked)); } var entries=new LinkedHashMap<String,RootCallTarget>(); for(var entry:programs.entrySet()) entries.put(entry.getKey(),entry.getValue().entryTarget(entry.getKey())); var active=new LinkedHashMap<String,List<RootCallTarget>>();
                class Exercise { void run(boolean compiled) throws Exception { for(var row:rows) for(var name:entries.keySet()) { var program=programs.get(name); var target=entries.get(name); long before=((Number)program.diagnostics().get("compiledEntries")).longValue(); var actual=Calls.target(target,new Object[]{0L,row.get("fd").longValue()}); var expected=name.equals("originalIsTerminal")?row.get("result"):row.get("errno"); assertEquals(expected.longValue(),actual,stage+"/"+backend+"/"+name+"/"+row); if(compiled) { assertEquals(before+executedRoots.get(name),((Number)program.diagnostics().get("compiledEntries")).longValue(),stage+"/"+backend+"/"+name+"/"+row+": every lowered entry root"); assertEquals(active.get(name),targets(target),"First-installed target identities must remain unchanged"); for(var item:active.get(name)) valid(item); } } assertEquals(0,output.size()); assertEquals(0,errors.size()); var handoff=language.getHandoffState().get(); assertEquals(0,handoff.getArguments().getDepth()); assertEquals(0,handoff.getResults().getDepth()); assertEquals(0,handoff.getResults().retainedReferences()); } }
                var exercise=new Exercise(); exercise.run(false); for(var entry:entries.entrySet()) { var list=targets(entry.getValue()); assertEquals(executedRoots.get(entry.getKey()),list.size(),stage+"/"+backend+"/"+entry.getKey()+": exactly the lowered entry roots"); active.put(entry.getKey(),list); } for(var list:active.values()) for(var target:list) { target.getClass().getMethod("compile",boolean.class).invoke(target,true); valid(target); } exercise.run(true);
            } finally { context.leave(); } } }
        }
    }
}
