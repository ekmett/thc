// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import java.io.*;
import java.lang.foreign.*;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BiConsumer;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.OriginalStdioChecks.*;

@EnabledOnOs({OS.LINUX,OS.MAC})
@SuppressWarnings("unchecked")
class OriginalProcessIdentityTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final String prefix = "build/original-process-identity";
    private final Map<String,Object> entries = map("originalGetPid","getpid","originalGetEuid","geteuid");
    private Map<String, Object> cbd(String path) throws Exception { return thc.CoreCbdFixtures.read(new File(root, path).toPath()); }
    private static String entryId(String name) { return "main:OriginalProcessIdentityAudit." + name; }
    private Object json(String path) throws Exception { return Json.parse(Files.readString(new File(root,path).toPath())); }
    private Context context() { return context(true); }
    private Context context(boolean nativeAccess) { return Context.newBuilder("thc").allowIO(IOAccess.NONE).allowNativeAccess(nativeAccess).out(new ByteArrayOutputStream()).err(new ByteArrayOutputStream())
        .allowExperimentalOptions(true).option("engine.BackgroundCompilation","false").option("engine.MultiTier","false").option("engine.CompilationFailureAction","Throw").build(); }
    private Map<String,Object> source(String stage) throws Exception {
        var modules = new ArrayList<Map<String,Object>>(); for (var part : List.of("OriginalProcessIdentityAudit","THC.InterfaceClosure")) modules.add((Map<String,Object>) cbd(prefix + "/" + stage + "/core/" + part + ".cbd")); return CoreModules.merge(modules);
    }
    private ExecutableProgram program(Language language,String backend,Map<String,Object> module) throws Exception { var linked = nativeModules(module); return backend.equals("ast") ? new Program(language,linked) : new BytecodeProgram(language,linked); }
    private List<Object> original(Map<String,Object> module,String symbol) { return single(foreignCalls(module),call -> Objects.equals(((Map<?,?>) ((Map<?,?>) ((Map<?,?>) call.get(6)).get("foreignCall")).get("target")).get("symbol"),symbol)); }
    private void released(Language language) { var state = language.getHandoffState().get(); assertEquals(0,state.getArguments().getDepth()); assertEquals(0,state.getArguments().retainedReferences()); assertEquals(0,state.getResults().getDepth()); assertEquals(0,state.getResults().retainedReferences()); assertNull(state.getPending()); }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true,target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private void fixture() throws Exception {
        var manifest = (Map<String,Object>) json(prefix + "/manifest.json"); assertEquals(1L,manifest.get("schema")); assertEquals("9.14.1",manifest.get("ghc")); assertEquals(true,manifest.get("strictAccepted")); assertEquals(true,manifest.get("installedArtifactsHashed")); assertEquals(new ArrayList<>(entries.keySet()),manifest.get("entries"));
        assertTrue(CoreOriginalStdio.isOriginalUnixUnit(manifest.get("unixUnit")));
        for (var stage : List.of("pre","post")) assertEquals(manifest.get("unixUnit"),((Map<?,?>) ((Map<?,?>) ((Map<?,?>) original(source(stage),"geteuid").get(6)).get("foreignCall")).get("target")).get("unit"));
        hashes(root,manifest.get("inputHashes"),Set.of("t/fixtures/compiler/OriginalProcessIdentityAudit.hs","t/fixtures/compiler/OriginalProcessIdentityNative.hs","t/haskell-fixtures/OriginalStdioFixtures.hs","bin/core_original_foreign.py","src/main/java/thc/runtime/ProcessIdentity.java"));
        var artifacts = new HashSet<>(Set.of(prefix + "/oracle.json")); for (var stage : List.of("pre","post")) { artifacts.add(prefix + "/" + stage + "/core/OriginalProcessIdentityAudit.cbd"); artifacts.add(prefix + "/" + stage + "/core/THC.InterfaceClosure.cbd"); for (var name : entries.keySet()) artifacts.add(prefix + "/" + stage + "/" + name + ".audit.json"); }
        hashes(root,manifest.get("artifactHashes"),artifacts,prefix + "/"); var oracle = (Map<String,Long>) json(prefix + "/oracle.json"); assertEquals(Set.of("pid","parentPid","euid"),oracle.keySet());
        assertTrue(oracle.get("pid") >= 1 && oracle.get("pid") <= Integer.MAX_VALUE); assertTrue(oracle.get("parentPid") >= 0 && oracle.get("parentPid") <= Integer.MAX_VALUE); assertNotEquals(oracle.get("pid"),oracle.get("parentPid")); assertTrue(oracle.get("euid") >= 0 && oracle.get("euid") <= 0xffff_ffffL);
        // The native fixture's separate-process PID is not this JVM's expected identity.
    }
    @Test void originalLiveQueriesMatchThisProcessOnFirstCompiledEntries() throws Throwable {
        fixture(); var linker = Linker.nativeLinker(); var effectiveUid = linker.downcallHandle(linker.defaultLookup().find("geteuid").orElseThrow(),FunctionDescriptor.of(ValueLayout.JAVA_INT));
        for (var stage : List.of("pre","post")) {
            var module = source(stage);
            for (var entry : entries.entrySet()) {
                var name = entry.getKey(); var symbol = (String) entry.getValue(); audit((Map<String,Object>) json(prefix + "/" + stage + "/" + name + ".audit.json"),"main:OriginalProcessIdentityAudit." + name,List.of(symbol));
                for (var backend : List.of("ast","bytecode")) try (var context = context()) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var raw = program(language,backend,rawModule(original(module,symbol),module)); var consumer = program(language,backend,with(CoreModules.reachable(module, entryId(name)),"instrument",true));
                        var rawTarget = raw.entryTarget("entry"); var consumerTarget = consumer.entryTarget(entryId(name)); var stdio = Language.currentState().getStdio();
                        record Invocation(ExecutableProgram program,RootCallTarget target,Object[] arguments) {}
                        class Exercise { void run(boolean compiled) throws Throwable {
                            for (long sentinel : new long[]{-1,0,17}) {
                                stdio.setErrno(sentinel);
                                for (var invocation : List.of(new Invocation(raw,rawTarget,new Object[]{0L,thc.runtime.Unit.INSTANCE}),new Invocation(consumer,consumerTarget,new Object[]{0L,0L}))) {
                                    long identity = symbol.equals("getpid") ? ProcessHandle.current().pid() : Integer.toUnsignedLong((int) effectiveUid.invokeExact());
                                    long before = ((Number) invocation.program().diagnostics().get("compiledEntries")).longValue(); Object expected = invocation.target() == rawTarget ? (Object) (int) identity : identity; assertEquals(expected,Calls.target(invocation.target(),invocation.arguments()),stage + "/" + backend + "/" + name); assertEquals(sentinel,stdio.errno()); released(language);
                                    if (compiled) { assertTrue(((Number) invocation.program().diagnostics().get("compiledEntries")).longValue() > before); valid(invocation.target()); }
                                }
                            }
                        }}
                        var exercise = new Exercise(); exercise.run(false); for (var target : List.of(rawTarget,consumerTarget)) { target.getClass().getMethod("compile",boolean.class).invoke(target,true); valid(target); } exercise.run(true);
                    } finally { context.leave(); }
                }
            }
        }
    }
    @Test void stateAndNativePermissionAreCheckedBeforeQueryWithoutErrnoMutation() throws Exception {
        var module = source("pre");
        for (var backend : List.of("ast","bytecode")) for (boolean nativeAccess : new boolean[]{false,true}) try (var context = context(nativeAccess)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var stdio = Language.currentState().getStdio();
                for (var symbol : entries.values()) {
                    if (!nativeAccess) {
                        stdio.setErrno(-23L);
                        var failure = assertThrows(RuntimeFault.class, () -> program(language,backend,rawModule(original(module,(String) symbol),module)));
                        assertTrue(Objects.toString(failure.getMessage(), "").contains("requires native access"), failure.getMessage());
                        assertEquals(-23L,stdio.errno()); released(language); continue;
                    }
                    var target = program(language,backend,rawModule(original(module,(String) symbol),module)).entryTarget("entry");
                    for (var invalid : list(null,0L,true,ManagedAddress.nullAddress())) { stdio.setErrno(-23L); var failure = assertThrows(RuntimeFault.class,() -> Calls.target(target,new Object[]{0L,invalid}));
                        assertTrue(Objects.toString(failure.getMessage(),"").contains("zero-width scalar carrier") || invalid == null && Objects.equals(failure.getMessage(),"Uninitialized local binding"),failure.getMessage()); assertEquals(-23L,stdio.errno()); released(language); }
                }
            } finally { context.leave(); }
        }
    }
    @Test void exactOwnersRepresentationsAndStoredStateCannotBeForged() throws Exception {
        var module = source("pre");
        for (var backend : List.of("ast","bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var entry : entries.entrySet()) {
                    var name = entry.getKey(); var symbol = (String) entry.getValue(); var call = original(module,symbol);
                    class Control { void rejects(BiConsumer<List<Object>,Map<String,Object>> change) {
                        var changed = (Map<String,Object>) Json.parse(Json.stringify(rawModule(call,module))); var app = single(foreignCalls(changed),ignored -> true); var descriptor = (Map<String,Object>) ((Map<?,?>) app.get(6)).get("foreignCall"); change.accept(app,descriptor); assertThrows(RuntimeFault.class,() -> Calls.target(program(language,backend,changed).entryTarget("entry"),new Object[]{0L,thc.runtime.Unit.INSTANCE}));
                    }}
                    var control = new Control();
                    for (var unit : List.of("main","ghc-internal-9.1401.0-inplace","unix-2.8.7.0-inplace","unix-2.8.8.0-ABCD","unix-2.8.8.0-nothex",symbol.equals("getpid") ? "unix-2.8.8.0-inplace" : "ghc-internal")) control.rejects((a,d) -> ((Map<String,Object>) d.get("target")).put("unit",unit));
                    // Only the exact acquired installed owner can supply this native component.
                    for (var unit : List.of("unix-2.8.8.0-inplace","unix-2.8.8.0-460b"))
                        control.rejects((a,d) -> ((Map<String,Object>) d.get("target")).put("unit",unit));
                    for (var edit : List.of(list("schema",true),list("convention","capi"),list("safety","safe"),list("arity",2L),list("suppliedArity",0L))) control.rejects((a,d) -> d.put((String) edit.get(0),edit.get(1)));
                    for (var rep : List.of("IntRep","WordRep",symbol.equals("getpid") ? "Word32Rep" : "Int32Rep")) control.rejects((app,descriptor) -> {
                        var metadata = (Map<?,?>) app.get(6); for (var result : list(metadata.get("rep"),descriptor.get("resultRep"))) { var proof = (Map<String,Object>) result; proof.put("primReps",list(rep)); ((List<Object>) proof.get("components")).set(1,OriginalStdioFixtures.scalar(rep)); }
                    });
                    control.rejects((app,d) -> app.set(1,list("var","p0",map("rep",OriginalStdioFixtures.closure()))));
                    control.rejects((app,d) -> ((List<Object>) app.get(2)).set(0,list("lit","int","1",map("rep",OriginalStdioFixtures.scalar(null)))));
                    assertThrows(RuntimeFault.class,() -> Calls.target(program(language,backend,rawModule(call,module,0)).entryTarget("entry"),new Object[]{0L,thc.runtime.Unit.INSTANCE}),name);
                }
            } finally { context.leave(); }
        }
    }
}
