// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;
import thc.*;
import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.Callable;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;
import static thc.runtime.OriginalStdioChecks.*;

/** Native GHC observes real-ID access results; no assumptions about root's permissions. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch",matches = "amd64|x86_64")
@SuppressWarnings("unchecked")
class OriginalPathAccessTest {
    @TempDir Path directory;
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final String prefix = "build/original-path-access";
    private Map<String,Object> json(String path) throws Exception { return (Map<String,Object>) Json.parse(Files.readString(new File(root,path).toPath())); }
    private Map<String,Object> source(String stage) throws Exception { return json(prefix + "/" + stage + ".json"); }
    private Object copy(Object value) { return Json.parse(Json.stringify(value)); }
    private ManagedAddress cstring(String value) { return ManagedAddress.fromByteArray((value + "\0").getBytes(StandardCharsets.UTF_8)); }
    private ExecutableProgram program(Language language,String backend,Map<String,Object> module) { return backend.equals("ast") ? new Program(language,module) : new BytecodeProgram(language,module); }
    private Context context() { return NativeFileProvider.createContext(Set.of(),ContextProfile.SYNCHRONOUS_TEST); }
    private static <T> T entered(Context context,Callable<T> action) throws Exception { context.initialize("thc"); context.enter(); try { return action.call(); } finally { context.leave(); } }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true,target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private void released(Language language) { var handoff = language.getHandoffState().get(); assertEquals(0,handoff.getArguments().getDepth()); assertEquals(0,handoff.getResults().getDepth()); assertEquals(0,handoff.getArguments().retainedReferences()); assertEquals(0,handoff.getResults().retainedReferences()); }
    private OriginalStdioOp validate(List<Object> call) { var reps = new ArrayList<Object>(); for (var arg : (List<List<Object>>) call.get(2)) { var metadata = CoreRepresentations.metadata(arg); reps.add(metadata == null ? null : metadata.get("rep")); } return CoreOriginalStdio.validate(call.get(6),reps,(List<?>) call.get(3),((Map<?,?>) call.get(6)).get("rep")); }
    private List<Object> original() throws Exception { return original("post"); }
    private List<Object> original(String stage) throws Exception { return single(foreignCalls(CoreModules.reachable(source(stage),"pathAccess")),ignored -> true); }
    private Map<String,Object> raw(List<Object> call) throws Exception { return rawModule(call,source("post")); }
    private ManagedAddress path(Path scratch,byte[] bytes) {
        byte[] prefix = bytes.length == 0 ? new byte[0] : (Path.of(Language.currentState().getEnv().getCurrentWorkingDirectory().getPath()).relativize(scratch) + "/").getBytes(StandardCharsets.UTF_8);
        byte[] result = Arrays.copyOf(prefix,prefix.length + bytes.length + 1); System.arraycopy(bytes,0,result,prefix.length,bytes.length); return ManagedAddress.fromByteArray(result);
    }
    private Path rawPath(Path scratch,byte[] bytes) { var text = new StringBuilder(scratch.toUri().toASCIIString()); for (byte value : bytes) text.append(String.format(Locale.ROOT,"%%%02X",value & 255)); return Path.of(URI.create(text.toString())); }
    private void permissions(Path path,String mode) throws Exception { Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(mode)); }
    private Path setup() throws Exception {
        var scratch = Files.createTempDirectory(directory,"case-"); for (var pair : List.of(List.of("target","rw-r-----"),List.of("executable","rwxr-x--x"),List.of("zero","---------"))) { Files.writeString(scratch.resolve(pair.get(0)),"unchanged"); permissions(scratch.resolve(pair.get(0)),pair.get(1)); }
        Files.createDirectory(scratch.resolve("sub")); permissions(scratch.resolve("sub"),"rwxr-x---"); Files.createDirectory(scratch.resolve("locked")); Files.writeString(scratch.resolve("locked/target"),"unchanged"); permissions(scratch.resolve("locked/target"),"rw-r-----"); permissions(scratch.resolve("locked"),"---------");
        Files.createSymbolicLink(scratch.resolve("link"),Path.of("target")); Files.createSymbolicLink(scratch.resolve("dangling"),Path.of("absent-target")); var raw = rawPath(scratch,new byte[]{-1,'n'}); Files.writeString(raw,"unchanged"); permissions(raw,"rw-r-----"); return scratch;
    }
    @Test void originalNativeAccessMatchesBothBackendsAndFirstInstalledCalls() throws Exception {
        var manifest = json(prefix + "/manifest.json"); assertEquals(List.of("pathAccess"),manifest.get("entries"));
        hashes(root,manifest.get("inputHashes"),Set.of("test/fixtures/compiler/OriginalPathAccessAudit.hs","test/haskell-fixtures/OriginalPathAccessFixtures.hs","bin/core_original_foreign.py","bin/core-capabilities.json"));
        var artifacts = new HashSet<>(Set.of(prefix + "/oracle.json")); for (var stage : List.of("pre","post")) { artifacts.add(prefix + "/" + stage + ".json"); artifacts.add(prefix + "/" + stage + "-pathAccess.audit.json"); } hashes(root,manifest.get("artifactHashes"),artifacts,prefix + "/");
        var rows = (List<Map<String,Object>>) json(prefix + "/oracle.json").get("rows"); assertEquals(68,rows.size()); var names = new ArrayList<Object>(); for (int i = 0; i < 60; i += 5) names.add(rows.get(i).get("name"));
        assertEquals(List.of("file","executable","zero","directory","locked-child","link","dangling","missing","empty","not-directory","raw","relative"),names);
        var modes = new ArrayList<Object>(); for (var row : rows) modes.add(row.get("mode")); assertTrue(modes.containsAll(List.of(0L,1L,2L,3L,4L,5L,6L,7L,8L,-1L,(long) Integer.MIN_VALUE,(long) Integer.MAX_VALUE,0x1_0000_0000L)));
        for (var stage : List.of("pre","post")) {
            var audit = json(prefix + "/" + stage + "-pathAccess.audit.json"); assertEquals(true,audit.get("accepted")); assertEquals(List.of(),audit.get("issues")); assertEquals(List.of(),audit.get("missingGlobals")); var linked = with(CoreModules.reachable(source(stage),"pathAccess"),"instrument",true);
            var evidence = new ArrayCoreEvidence(linked,"pathAccess"); assertEquals(1,evidence.getBindings().size()); assertEquals(1,evidence.guestLambdas(evidence.getRoot().get("expr")).size()); assertEquals(1,evidence.loweredGuestLambdas(evidence.getRoot().get("expr")).size()); assertEquals(OriginalStdioOp.ACCESS,validate(original(stage)));
            assertEquals("ghc-internal",((Map<?,?>) ((Map<?,?>) ((Map<?,?>) original(stage).get(6)).get("foreignCall")).get("target")).get("unit"));
            for (var backend : List.of("ast","bytecode")) try (var context = context()) { entered(context,() -> {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var executable = program(language,backend,linked); var entry = executable.entryTarget("pathAccess"); var stdio = Language.currentState().getStdio();
                class Exercise { void run(boolean compiled) throws Exception {
                    var scratch = setup();
                    try {
                        for (var row : rows) { var raw = (List<Long>) row.get("path"); byte[] bytes = new byte[raw.size()]; for (int i = 0; i < bytes.length; i++) bytes[i] = raw.get(i).byteValue();
                            assertEquals(-1L,stdio.close(-1)); long before = ((Number) executable.diagnostics().get("compiledEntries")).longValue(); if (compiled) valid(entry);
                            assertEquals(row.get("status"),Calls.target(entry,new Object[]{0L,path(scratch,bytes),row.get("mode")}),stage + "/" + backend + "/" + row.get("name") + "/" + row.get("mode")); assertEquals(row.get("errno"),stdio.errno());
                            if (compiled) { assertEquals(before + 1,((Number) executable.diagnostics().get("compiledEntries")).longValue()); valid(entry); } released(language);
                        }
                        assertEquals("unchanged",Files.readString(scratch.resolve("target"))); assertTrue(Files.isSymbolicLink(scratch.resolve("link")));
                    } finally { permissions(scratch.resolve("locked"),"rwx------"); }
                }}
                var exercise = new Exercise(); exercise.run(false); entry.getClass().getMethod("compile",boolean.class).invoke(entry,true); valid(entry); exercise.run(true); assertEquals(0L,((Number) executable.diagnostics().get("unsupportedTraps")).longValue()); return null;
            }); }
        }
    }
    @Test void exactOwnerAbiStateAndStoredOperandProofsRemainRequired() throws Exception {
        var call = original(); assertEquals(OriginalStdioOp.ACCESS,validate(call));
        for (var edit : List.of(list("safety","safe"),list("convention","capi"),list("arity",2L),list("suppliedArity",2L))) { var bad = (List<Object>) copy(call); ((Map<String,Object>) ((Map<?,?>) bad.get(6)).get("foreignCall")).put((String) edit.get(0),edit.get(1)); assertThrows(RuntimeFault.class,() -> validate(bad)); }
        for (var unit : List.of("main","unix-2.8.8.0-inplace","unix-2.8.8.0-460b","ghc-internal-forged")) { var bad = (List<Object>) copy(call); ((Map<String,Object>) ((Map<?,?>) ((Map<?,?>) bad.get(6)).get("foreignCall")).get("target")).put("unit",unit); assertThrows(RuntimeFault.class,() -> validate(bad)); }
        for (var backend : List.of("ast","bytecode")) try (var context = context()) { entered(context,() -> {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
            for (int i = 0; i <= 2; i++) { int index = i; assertThrows(RuntimeFault.class,() -> program(language,backend,rawModule(call,source("post"),index))); }
            var shadowed = raw(call); var body = single((List<Map<String,Object>>) shadowed.get("bindings"),ignored -> true).get("expr"); var copied = single(foreignCalls(body),ignored -> true); copied.set(1,list("var","p0",((List<?>) copied.get(1)).get(2))); assertThrows(RuntimeFault.class,() -> program(language,backend,shadowed));
            for (var rep : List.of("IntRep","Word32Rep","Word64Rep")) { var bad = (List<Object>) copy(call); var descriptor = (Map<String,Object>) ((Map<?,?>) bad.get(6)).get("foreignCall"); ((List<Map<String,Object>>) descriptor.get("argumentReps")).get(1).put("primReps",list(rep)); assertThrows(RuntimeFault.class,() -> program(language,backend,raw(bad))); } return null;
        }); }
    }
    @Test void stateCanonicalModeAndPathOwnershipPrecedeObservation() throws Exception {
        for (var backend : List.of("ast","bytecode")) try (var context = context()) { entered(context,() -> {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var entry = program(language,backend,raw(original())).entryTarget("entry"); var stdio = Language.currentState().getStdio(); var missing = cstring(directory.resolve("missing").toString());
            class Call { Object invoke(ManagedAddress address,Object mode,Object state) { return callScalarTestTarget(entry,new Object[]{0L,address,mode,state}); }}
            var call = new Call(); assertThrows(RuntimeFault.class,() -> Calls.target(entry,new Object[]{0L,ManagedAddress.nullAddress(),0,9L})); assertEquals(-1L,stdio.close(-1)); long prior = stdio.errno(); assertThrows(RuntimeFault.class,() -> call.invoke(missing,0,9L));
            for (long mode : new long[]{(long) Integer.MIN_VALUE - 1,(long) Integer.MAX_VALUE + 1,0x1_0000_0000L}) assertThrows(RuntimeFault.class,() -> call.invoke(missing,mode,thc.runtime.Unit.INSTANCE));
            assertThrows(RuntimeFault.class,() -> call.invoke(ManagedAddress.nullAddress(),0,thc.runtime.Unit.INSTANCE)); assertThrows(RuntimeFault.class,() -> call.invoke(ManagedAddress.fromByteArray(new byte[]{65}),0,thc.runtime.Unit.INSTANCE));
            var allocation = ManagedAllocation.mutable(16,8); allocation.writeAddressByteOffset(0,ManagedAddress.nullAddress()); assertThrows(RuntimeFault.class,() -> call.invoke(ManagedAddress.fromAllocation(allocation),0,thc.runtime.Unit.INSTANCE)); assertEquals(prior,stdio.errno()); released(language); return null;
        }); }
        for (var backend : List.of("ast","bytecode")) try (var context = Context.newBuilder("thc").build()) { entered(context,() -> {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var entry = program(language,backend,raw(original())).entryTarget("entry");
            assertEquals(-1,callScalarTestTarget(entry,new Object[]{0L,cstring(directory.toString()),0,thc.runtime.Unit.INSTANCE})); assertEquals(StdioHostAbi.load().error(7),Language.currentState().getStdio().errno()); return null;
        }); }
    }
    @Test void nativePathAliasesRetainContextAndLifetimeChecks() throws Exception {
        for (var backend : List.of("ast","bytecode")) try (var first = context()) { entered(first,() -> {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var existing = Files.createTempFile(directory,"owned-",".txt"); var bytes = (existing + "\0").getBytes(StandardCharsets.UTF_8); var state = Language.currentState();
            var base = state.getNativeAllocations().malloc(bytes.length + 8L); var alias = base.plus(8); var entry = program(language,backend,raw(original())).entryTarget("entry");
            try {
                ManagedAddress.fromByteArray(bytes).copyNonOverlappingTo(alias,bytes.length);
                try (var second = context()) { entered(second,() -> { var other = TruffleLanguage.LanguageReference.create(Language.class).get(null); var foreign = program(other,backend,raw(original())).entryTarget("entry"); assertThrows(RuntimeFault.class,() -> callScalarTestTarget(foreign,new Object[]{0L,alias,0,thc.runtime.Unit.INSTANCE})); released(other); return null; }); }
                assertEquals(0,callScalarTestTarget(entry,new Object[]{0L,alias,0,thc.runtime.Unit.INSTANCE})); assertEquals(-1L,state.getStdio().close(-1)); long prior = state.getStdio().errno();
                assertEquals(0,callScalarTestTarget(entry,new Object[]{0L,alias,0,thc.runtime.Unit.INSTANCE})); assertEquals(prior,state.getStdio().errno());
            } finally { state.getNativeAllocations().free(base); }
            assertThrows(RuntimeFault.class,() -> callScalarTestTarget(entry,new Object[]{0L,alias,0,thc.runtime.Unit.INSTANCE})); released(language); return null;
        }); }
    }
}
