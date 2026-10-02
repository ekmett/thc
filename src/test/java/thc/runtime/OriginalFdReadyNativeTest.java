// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.OriginalStdioChecks.*;

/** Adapted scalar Haskell consumers retain actual installed IO.FD FCallIds.
 * This proves the foreign boundary, not whole unchanged Handle/FD execution. */
@SuppressWarnings("unchecked")
class OriginalFdReadyNativeTest {
    @TempDir Path directory;
    private final File root=new File(System.getProperty("thc.projectRoot"));
    private final File fixture=new File(root,"build/original-fd-ready");
    private final List<String> entries=List.of("originalReadySafe","originalReadyUnsafe");
    private Map<String,Object> document(String name) throws Exception { return (Map<String,Object>)Json.parse(Files.readString(new File(fixture,name).toPath())); }
    private Map<String,Object> module() throws Exception { return CoreCbdFixtures.read(new File(fixture,"OriginalFdReadyAudit.cbd").toPath()); }
    private static String entryId(String name) { return "main:OriginalFdReadyAudit."+name; }
    private Context context() { return context(new ByteArrayOutputStream()); }
    private Context context(ByteArrayOutputStream output) { return Context.newBuilder("thc").allowIO(IOAccess.ALL).out(output).err(output).allowExperimentalOptions(true).option("engine.BackgroundCompilation","false").option("engine.MultiTier","false").option("engine.CompilationFailureAction","Throw").option("engine.SingleTierCompilationThreshold","10000000").build(); }
    private ExecutableProgram program(Language language,Map<String,Object> module,String backend) { return backend.equals("ast")?new Program(language,module):new BytecodeProgram(language,module); }
    private void released(Language language) { var pools=language.getHandoffState().get(); assertEquals(0,pools.getArguments().getDepth()); assertEquals(0,pools.getResults().getDepth()); assertEquals(0,pools.getArguments().retainedReferences()); assertEquals(0,pools.getResults().retainedReferences()); }
    @Test void originalReadyMatchesNativeForRegularFilesEofClosedAndIgnoredDescriptors() throws Exception {
        var manifest=document("manifest.json"); assertEquals(1L,manifest.get("schema")); assertEquals("9.14.1",manifest.get("ghc")); assertEquals(entries,manifest.get("entries")); hashes(root,manifest.get("inputHashes"),Set.of("t/fixtures/compiler/OriginalFdReadyAudit.hs","t/fixtures/compiler/OriginalFdReadyAuditNative.hs","t/haskell-fixtures/OriginalFdReadyFixtures.hs","src/cbd/THC/Compact/Module.hs","src/cbd/THC/Compact/JSON.hs","bin/core_original_foreign.py")); hashes(root,manifest.get("artifactHashes"),Set.of("build/original-fd-ready/oracle.json","build/original-fd-ready/OriginalFDDeclarations.cbd","build/original-fd-ready/OriginalFdReadyAudit.cbd"),"build/original-fd-ready/");
        var oracle=(List<Map<String,Object>>)Json.parse(Files.readString(new File(fixture,"oracle.json").toPath())); var expectedRequests=new ArrayList<List<Object>>(); for(var entry:entries) for(var scenario:List.of("read","write","readwrite","eof","closed","negative")) for(long writing:new long[]{0,1}) for(long socket:new long[]{0,1}) for(long milliseconds:scenario.equals("negative")?new long[]{0}:new long[]{-1,0,1,2147483649L}) expectedRequests.add(list(entry,scenario,writing,milliseconds,socket)); var actualRequests=new ArrayList<List<Object>>(); for(var row:oracle) actualRequests.add(list(row.get("entry"),row.get("scenario"),row.get("writing"),row.get("milliseconds"),row.get("socket"))); assertEquals(expectedRequests,actualRequests); long badFd=StdioHostAbi.load().error(4); for(var row:oracle) { assertEquals(Objects.equals(row.get("scenario"),"negative")?0L:1L,row.get("result")); assertEquals(badFd,row.get("errnoBefore")); assertEquals(badFd,row.get("errnoAfter")); }
        var original=CoreCbdFixtures.read(new File(fixture,"OriginalFDDeclarations.cbd").toPath()); var originalCalls=new ArrayList<List<Object>>(); for(var call:foreignCalls(original)) if(Objects.equals(((Map<?,?>)((Map<?,?>)((Map<?,?>)call.get(6)).get("foreignCall")).get("target")).get("symbol"),"fdReady")) originalCalls.add(call);
        for(var name:entries) {
            var linked=CoreModules.reachable(module(),entryId(name));
            var calls=foreignCalls(linked); assertFalse(calls.isEmpty(),name);
            for(var call:calls) {
                var descriptor=(Map<?,?>)((Map<?,?>)call.get(6)).get("foreignCall");
                var matched=new ArrayList<List<Object>>();
                for(var item:originalCalls) if(Objects.equals(((Map<?,?>)((Map<?,?>)item.get(6)).get("foreignCall")).get("safety"),descriptor.get("safety"))) matched.add(item);
                assertFalse(matched.isEmpty(),name);
                var originalHead=(List<?>)matched.getFirst().get(1); var adaptedHead=(List<?>)call.get(1);
                assertEquals(originalHead.get(0),adaptedHead.get(0)); assertEquals(originalHead.get(2),adaptedHead.get(2));
                assertEquals(((Map<?,?>)matched.getFirst().get(6)).get("foreignCall"),descriptor);
            }
            var report=document(name+".audit.json");
            assertEquals(true,report.get("accepted")); assertEquals(List.of(entryId(name)),report.get("roots"));
            for(var field:List.of("issues","missingGlobals","runtimeExternals")) assertEquals(List.of(),report.get(field),field);
            var auditedCalls=(List<Map<String,Object>>)report.get("foreignCalls");
            assertTrue(auditedCalls.stream().anyMatch(call->Objects.equals("fdReady",call.get("symbol"))),name);
        }
        for(var backend:List.of("ast","bytecode")) try(var context=context()) {
            context.initialize("thc"); context.enter(); try {
                var language=TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var files=Language.currentState().getFiles(); var stdio=Language.currentState().getStdio();
                var privateFile=directory.resolve(backend); Files.writeString(privateFile,"keep");
                var raw=privateFile.toString().getBytes(StandardCharsets.UTF_8);
                var path=ManagedAddress.fromByteArray(Arrays.copyOf(raw,raw.length+1));
                for(var name:entries) {
                    var linked=with(CoreModules.reachable(module(),entryId(name)),"instrument",true);
                    var program=program(language,linked,backend);
                    var function=context.asValue(new EntryValue(program,entryId(name),4));
                    class Exercise {
                        void run(List<Map<String,Object>> rows,Long beforeInstallation) {
                            boolean firstInstalled=beforeInstallation!=null;
                            for(var row:rows) {
                                var scenario=(String)row.get("scenario"); long fd;
                                if(scenario.equals("negative")) fd=-1;
                                else {
                                    fd=files.open(path,switch(scenario) { case "write" -> 2L; case "readwrite" -> 3L; default -> 0L; },ForeignSafety.UNSAFE);
                                    assertTrue(fd>=3L);
                                }
                                if(scenario.equals("closed")) assertEquals(0L,files.close(fd));
                                if(scenario.equals("eof")) assertEquals(4L,files.seek(fd,0,2));
                                Long initialPosition=null;
                                if(!List.of("closed","negative").contains(scenario)) {
                                    initialPosition=files.seek(fd,0,1); assertTrue(initialPosition>=0L);
                                }
                                assertEquals(-1L,stdio.close(-1)); assertEquals(badFd,stdio.errno());
                                assertEquals(row.get("result"),function.execute(fd,row.get("writing"),row.get("milliseconds"),row.get("socket")).asLong(),backend+"/"+name+"/"+row);
                                if(firstInstalled) {
                                    assertTrue(((Number)program.diagnostics().get("compiledEntries")).longValue()>beforeInstallation,"first installed execution "+row);
                                    var diagnostics=(Map<String,Object>)Json.parse(function.getMember("diagnostics").asString());
                                    assertEquals(true,((Map<?,?>)diagnostics.get("explicitCompilation")).get("validLastTier"),
                                        "first installed call preserves the installed guest entry and host bridge");
                                    firstInstalled=false;
                                }
                                assertEquals(badFd,stdio.errno(),"successful readiness preserves errno");
                                if(!List.of("closed","negative").contains(scenario)) {
                                    assertEquals(initialPosition,files.seek(fd,0,1),"readiness must not move the file position");
                                    assertEquals(0L,files.close(fd));
                                }
                            }
                        }
                    }
                    var rows=new ArrayList<Map<String,Object>>();
                    for(var row:oracle) if(Objects.equals(row.get("entry"),name)) rows.add(row);
                    var exercise=new Exercise(); exercise.run(rows,null);
                    long beforeInstallation=((Number)program.diagnostics().get("compiledEntries")).longValue();
                    assertTrue(function.invokeMember("compile").asBoolean());
                    assertEquals(beforeInstallation,((Number)program.diagnostics().get("compiledEntries")).longValue(),
                        "installation executes no guest work");
                    var diagnostics=(Map<String,Object>)Json.parse(function.getMember("diagnostics").asString());
                    assertEquals(true,((Map<?,?>)diagnostics.get("explicitCompilation")).get("validLastTier"),
                        "guest entry and host bridge installed");
                    exercise.run(rows.reversed(),beforeInstallation);
                    assertEquals(0L,((Number)program.diagnostics().get("unsupportedTraps")).longValue());
                    assertEquals("keep",Files.readString(privateFile)); released(language);
                }
            } finally { context.leave(); }
        }
    }
    @Test void malformedOriginalDescriptorsRetainTheirStrictAuditRejection() throws Exception {
        var labels=List.of("wrong-unit","non-function","wrong-convention","interruptible","wrong-arity","wrong-supplied-arity","signed-cbool","machine-timeout","scalar-state","machine-result");
        assertEquals(labels,document("manifest.json").get("negativeControlLabels"));
        for(var label:labels) {
            for(var entry:entries) { var report=document("negative/"+label+"-"+entry+".audit.json"); assertEquals(false,report.get("accepted")); assertEquals(List.of(entryId(entry)),report.get("roots"));
                var issues=(List<Map<String,Object>>)report.get("issues"); assertTrue(issues.stream().anyMatch(issue->Objects.equals("foreign-call",issue.get("code"))),label+"/"+entry);
            }
        }
    }
    @Test void opaqueStreamsAndOutOfDomainWaitsFailExplicitlyWithoutExternalEffects() throws Exception {
        var output=new ByteArrayOutputStream(); try(var context=context(output)) { context.initialize("thc"); context.enter(); try { var stdio=Language.currentState().getStdio(); long unsupported=StdioHostAbi.load().error(7); for(long fd:new long[]{0,1,2}) for(long direction:new long[]{0,1}) { assertEquals(-1L,stdio.ready(fd,direction,0,0)); assertEquals(unsupported,stdio.errno()); } assertEquals(0L,stdio.ready(Integer.MIN_VALUE,0,0,0)); assertEquals(1L,stdio.ready(Integer.MAX_VALUE,1,-1,1)); assertEquals(unsupported,stdio.errno()); for(long fd:new long[]{(long)Integer.MAX_VALUE+1,(long)Integer.MIN_VALUE-1}) assertThrows(RuntimeFault.class,()->stdio.ready(fd,0,0,0)); for(long bad:new long[]{-1,2,255,256,Long.MAX_VALUE}) { assertThrows(RuntimeFault.class,()->stdio.ready(-1,bad,0,0)); assertThrows(RuntimeFault.class,()->stdio.ready(-1,0,0,bad)); } for(long wait:new long[]{-1,1,Long.MAX_VALUE}) assertThrows(RuntimeFault.class,()->stdio.ready(-1,0,wait,0)); assertEquals(unsupported,stdio.errno()); assertEquals(0,output.size()); } finally { context.leave(); } }
        try(var second=context()) { second.initialize("thc"); second.enter(); try { assertEquals(0L,Language.currentState().getStdio().errno()); } finally { second.leave(); } }
    }
    private Object replace(Object value,Object target,Object replacement) { if(value==target) return replacement; if(value instanceof Map<?,?> values) { var result=new LinkedHashMap<Object,Object>(); for(var entry:values.entrySet()) result.put(entry.getKey(),replace(entry.getValue(),target,replacement)); return result; } if(value instanceof List<?> values) { var result=new ArrayList<Object>(); for(var item:values) result.add(replace(item,target,replacement)); return result; } return value; }
    @Test void originalReadinessRequiresExactHeadSafetyWidthsStateAndStoredCarriers() throws Exception {
        for(var name:entries) { var source=CoreModules.reachable(module(),entryId(name)); var call=foreignCalls(source).getFirst(); var metadata=(Map<String,Object>)call.get(6); var descriptor=(Map<String,Object>)metadata.get("foreignCall"); var target=(Map<String,Object>)descriptor.get("target"); var arguments=(List<List<Object>>)call.get(2); var invalid=new ArrayList<List<Object>>(); java.util.function.Consumer<Map<String,Object>> badDescriptor=change->{ var changed=new ArrayList<>(call); changed.set(6,with(metadata,"foreignCall",change)); invalid.add(changed); };
            for(var edit:List.of(list("convention","capi"),list("safety","interruptible"),list("arity",4L),list("suppliedArity",6L),list("schema",true))) badDescriptor.accept(with(descriptor,edit.get(0),edit.get(1))); for(var edit:List.of(list("unit","main"),list("kind","dynamic"),list("isFunction",false))) badDescriptor.accept(with(descriptor,"target",with(target,edit.get(0),edit.get(1)))); var declared=(List<Map<String,Object>>)descriptor.get("argumentReps"); for(int index=0;index<declared.size();index++) { var changed=new ArrayList<>(declared); changed.set(index,with(declared.get(index),"primReps",list("IntRep"))); badDescriptor.accept(with(descriptor,"argumentReps",changed)); }
            var flags=new ArrayList<>(call); flags.set(3,list(true,false,false,false,false)); invalid.add(flags); var literal=new ArrayList<>(call); literal.set(1,list("lit","int","0",((List<?>)call.get(1)).get(2))); invalid.add(literal);
            // A literal cannot become a zero-width State# merely by relabeling its occurrence.
            var state=new ArrayList<>(call); var stateMetadata=arguments.getLast().getLast(); var args=new ArrayList<>(arguments.subList(0,arguments.size()-1)); args.add(list("lit","int","0",stateMetadata)); state.set(2,args); invalid.add(state);
            for(var backend:List.of("ast","bytecode")) { var output=new ByteArrayOutputStream(); try(var context=context(output)) { context.initialize("thc"); context.enter(); try {
                var language=TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var inert=new Object[]{0L,-1L,0L,0L,0L};
                assertEquals(0L,ScalarTestCalls.callScalarTestTarget(program(language,source,backend).entryTarget(entryId(name)),inert),"valid original inert call");
                for(var changed:invalid) {
                    var error=assertThrows(RuntimeFault.class,()-> { var entry=program(language,(Map<String,Object>)replace(source,call,changed),backend).entryTarget(entryId(name)); if(changed==invalid.get(5)) ScalarTestCalls.callScalarTestTarget(entry,inert); },name+"/"+backend+" mutation "+invalid.indexOf(changed));
                    if(changed==invalid.get(5)) { assertEquals(UnsupportedCore.class,error.getClass()); assertEquals("Unsupported foreign call: fdReady",error.getMessage()); }
                    assertEquals(0,output.size(),"malformed readiness must not produce external output"); assertEquals(0L,Language.currentState().getStdio().errno(),"malformed readiness must not change errno"); released(language);
                }
                var operation=name.endsWith("Unsafe")?OriginalStdioOp.READY_UNSAFE:OriginalStdioOp.READY_SAFE;
                for(int i=0;i<arguments.size();i++) { int index=i; var rep=CoreRepresentations.expression(arguments.get(i)); assertDoesNotThrow(()->CoreOriginalStdio.validateScalarOperand(operation,index,rep,rep)); var wrong=new CoreRepresentation(CoreKind.ADDRESS,rep.getEvaluated(),rep.getPresent(),List.of("AddrRep"),rep.getComponents(),rep.getVector(),rep.getAlternatives(),rep.getTagSlot(),rep.getAlternativeSlots()); assertThrows(RuntimeFault.class,()->CoreOriginalStdio.validateScalarOperand(operation,index,rep,wrong),name+"/"+backend+" stored operand "+index); } released(language);
            } finally { context.leave(); } } }
        }
    }
}
