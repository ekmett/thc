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
import static thc.runtime.NarrowIntegerCarrierTestKt.callScalarTestTarget;
import static thc.runtime.OriginalStdioChecks.*;

/** Genuine installed GHC and Unix declarations, with native-compiled consumers. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch",matches = "amd64|x86_64")
@SuppressWarnings("unchecked")
class OriginalPathModeTest {
    @TempDir Path directory;
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final String prefix = "build/original-path-mode";
    private final Map<String,OriginalStdioOp> operations = new LinkedHashMap<>();
    OriginalPathModeTest() { operations.put("pathMkdir",OriginalStdioOp.MKDIR); operations.put("pathChmod",OriginalStdioOp.CHMOD); }
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
    private List<Object> original(String name) throws Exception { return original(name,"post"); }
    private List<Object> original(String name,String stage) throws Exception { return single(foreignCalls(CoreModules.reachable(source(stage),name)),ignored -> true); }
    private Map<String,Object> raw(List<Object> call) throws Exception { return rawModule(call,source("post")); }
    private ManagedAddress path(Path scratch,byte[] bytes) {
        byte[] prefix = bytes.length == 0 ? new byte[0] : (Path.of(Language.currentState().getEnv().getCurrentWorkingDirectory().getPath()).relativize(scratch) + "/").getBytes(StandardCharsets.UTF_8);
        byte[] result = Arrays.copyOf(prefix,prefix.length + bytes.length + 1); System.arraycopy(bytes,0,result,prefix.length,bytes.length); return ManagedAddress.fromByteArray(result);
    }
    private Path rawPath(Path scratch,byte[] bytes) { var text = new StringBuilder(scratch.toUri().toASCIIString()); for (byte value : bytes) text.append(String.format(Locale.ROOT,"%%%02X",value & 255)); return Path.of(URI.create(text.toString())); }
    private long unixMode(Path path) throws Exception { return Files.exists(path) ? ((Number) Files.getAttribute(path,"unix:mode")).longValue() & 511L : -1L; }
    private Path setup() throws Exception {
        var scratch = Files.createTempDirectory(directory,"case-"); Files.writeString(scratch.resolve("target"),"unchanged"); Files.setPosixFilePermissions(scratch.resolve("target"),PosixFilePermissions.fromString("rw-r--r--"));
        Files.createDirectory(scratch.resolve("sub")); Files.setPosixFilePermissions(scratch.resolve("sub"),PosixFilePermissions.fromString("rwx------")); Files.createSymbolicLink(scratch.resolve("link"),Path.of("target")); Files.createSymbolicLink(scratch.resolve("dangling"),Path.of("absent-target")); return scratch;
    }
    @Test void originalNativeModesMatchBothBackendsAndFirstInstalledCalls() throws Exception {
        var manifest = json(prefix + "/manifest.json"); assertEquals(new ArrayList<>(operations.keySet()),manifest.get("entries")); assertTrue(CoreOriginalStdio.isOriginalUnixUnit(manifest.get("unixUnit")));
        hashes(root,manifest.get("inputHashes"),Set.of("compiler/test-fixtures/OriginalPathModeAudit.hs","test/haskell-fixtures/OriginalPathModeFixtures.hs","scripts/core_original_foreign.py","scripts/core-capabilities.json"));
        var artifacts = new HashSet<>(Set.of(prefix + "/oracle.json")); for (var stage : List.of("pre","post")) { artifacts.add(prefix + "/" + stage + ".json"); for (var name : operations.keySet()) artifacts.add(prefix + "/" + stage + "-" + name + ".audit.json"); } hashes(root,manifest.get("artifactHashes"),artifacts,prefix + "/");
        var oracle = json(prefix + "/oracle.json"); var rows = (List<Map<String,Object>>) oracle.get("rows"); var mkdir = new ArrayList<Object>(); var chmod = new ArrayList<Object>(); for (var row : rows) { if (Objects.equals(row.get("entry"),"pathMkdir")) mkdir.add(row.get("name")); if (Objects.equals(row.get("entry"),"pathChmod")) chmod.add(row.get("name")); }
        assertEquals(List.of("create","zero","wide","existing-file","existing-directory","missing-parent","not-directory","empty","relative","raw"),mkdir);
        assertEquals(List.of("file","zero","wide","directory","link","dangling","missing","not-directory","empty","relative","raw"),chmod);
        // Observe inherited creation permissions without changing this process's umask.
        var probe = Files.createDirectory(directory.resolve("mask-probe"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx"))); long creationMask = unixMode(probe), nativeMask = (Long) oracle.get("creationMask");
        for (var stage : List.of("pre","post")) for (var operationEntry : operations.entrySet()) {
            var name = operationEntry.getKey(); var operation = operationEntry.getValue(); var audit = json(prefix + "/" + stage + "-" + name + ".audit.json"); assertEquals(true,audit.get("accepted")); assertEquals(List.of(),audit.get("issues")); assertEquals(List.of(),audit.get("missingGlobals"));
            var linked = with(CoreModules.reachable(source(stage),name),"instrument",true); var evidence = new ArrayCoreEvidence(linked,name); assertEquals(1,evidence.getBindings().size()); assertEquals(1,evidence.guestLambdas(evidence.getRoot().get("expr")).size()); assertEquals(1,evidence.loweredGuestLambdas(evidence.getRoot().get("expr")).size()); assertEquals(operation,validate(original(name,stage)));
            if (operation == OriginalStdioOp.MKDIR) assertEquals(manifest.get("unixUnit"),((Map<?,?>) ((Map<?,?>) ((Map<?,?>) original(name,stage).get(6)).get("foreignCall")).get("target")).get("unit"));
            for (var backend : List.of("ast","bytecode")) try (var context = context()) { entered(context,() -> {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var executable = program(language,backend,linked); var entry = executable.entryTarget(name); var stdio = Language.currentState().getStdio();
                class Exercise { void run(boolean compiled) throws Exception {
                    for (var row : rows) if (Objects.equals(row.get("entry"),name)) {
                        var scratch = setup(); var raw = (List<Long>) row.get("path"); byte[] bytes = new byte[raw.size()]; for (int i = 0; i < bytes.length; i++) bytes[i] = raw.get(i).byteValue();
                        if (name.equals("pathChmod")) Files.createSymbolicLink(rawPath(scratch,new byte[]{-1,'m'}),Path.of("target")); long mode = (Long) row.get("mode");
                        assertEquals(-1L,stdio.close(-1)); long before = ((Number) executable.diagnostics().get("compiledEntries")).longValue(); if (compiled) valid(entry);
                        assertEquals(row.get("status"),callScalarTestTarget(entry,new Object[]{0L,path(scratch,bytes),(int)mode}),stage + "/" + backend + "/" + name + "/" + row.get("name")); assertEquals(row.get("errno"),stdio.errno());
                        if (compiled) { assertEquals(before + 1,((Number) executable.diagnostics().get("compiledEntries")).longValue()); valid(entry); }
                        long observed = bytes.length == 0 ? -1L : unixMode(rawPath(scratch,bytes));
                        if (name.equals("pathMkdir") && Objects.equals(row.get("status"),0L)) { assertEquals(mode & nativeMask & 511L,row.get("observedMode")); assertEquals(mode & creationMask & 511L,observed); assertTrue(Files.isDirectory(rawPath(scratch,bytes))); } else assertEquals(row.get("observedMode"),observed);
                        assertEquals(row.get("targetMode"),unixMode(scratch.resolve("target")));
                        if (Objects.equals(row.get("status"),0L)) Files.setPosixFilePermissions(rawPath(scratch,bytes),PosixFilePermissions.fromString("rwx------")); Files.setPosixFilePermissions(scratch.resolve("target"),PosixFilePermissions.fromString("rw-r--r--"));
                        assertEquals("unchanged",Files.readString(scratch.resolve("target"))); assertTrue(Files.isSymbolicLink(scratch.resolve("link"))); released(language);
                    }
                }}
                var exercise = new Exercise(); exercise.run(false); entry.getClass().getMethod("compile",boolean.class).invoke(entry,true); valid(entry); exercise.run(true); assertEquals(0L,((Number) executable.diagnostics().get("unsupportedTraps")).longValue()); return null;
            }); }
        }
    }
    @Test void exactDescriptorOwnerStateAndStoredOperandProofsRemainRequired() throws Exception {
        for (var operationEntry : operations.entrySet()) {
            var name = operationEntry.getKey(); var operation = operationEntry.getValue(); var call = original(name); assertEquals(operation,validate(call));
            for (var edit : List.of(list("safety","safe"),list("convention","capi"),list("arity",2L),list("suppliedArity",2L))) { var bad = (List<Object>) copy(call); ((Map<String,Object>) ((Map<?,?>) bad.get(6)).get("foreignCall")).put((String) edit.get(0),edit.get(1)); assertThrows(RuntimeFault.class,() -> validate(bad)); }
            for (var unit : List.of("main","unix-2.8.7.0-inplace","unix-2.8.8.0-ABCD","unix-2.8.8.0-nothex",operation == OriginalStdioOp.MKDIR ? "ghc-internal" : "unix-2.8.8.0-inplace")) { var bad = (List<Object>) copy(call); ((Map<String,Object>) ((Map<?,?>) ((Map<?,?>) bad.get(6)).get("foreignCall")).get("target")).put("unit",unit); assertThrows(RuntimeFault.class,() -> validate(bad)); }
            for (var backend : List.of("ast","bytecode")) try (var context = context()) { entered(context,() -> {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var unit : List.of("unix-2.8.8.0-inplace","unix-2.8.8.0-460b")) { var installed = (List<Object>) copy(call); ((Map<String,Object>) ((Map<?,?>) ((Map<?,?>) installed.get(6)).get("foreignCall")).get("target")).put("unit",unit);
                    if (operation == OriginalStdioOp.MKDIR) { assertEquals(operation,validate(installed)); program(language,backend,raw(installed)); } else assertThrows(RuntimeFault.class,() -> program(language,backend,raw(installed))); }
                for (int i = 0; i <= 2; i++) { int index = i; assertThrows(RuntimeFault.class,() -> program(language,backend,rawModule(call,source("post"),index))); }
                var shadowed = raw(call); var body = single((List<Map<String,Object>>) shadowed.get("bindings"),ignored -> true).get("expr"); var copied = single(foreignCalls(body),ignored -> true); copied.set(1,list("var","p0",((List<?>) copied.get(1)).get(2))); assertThrows(RuntimeFault.class,() -> program(language,backend,shadowed)); return null;
            }); }
        }
    }
    @Test void stateModesAndPathOwnershipAreCheckedBeforeMutation() throws Exception {
        for (var name : operations.keySet()) for (var backend : List.of("ast","bytecode")) try (var context = context()) { entered(context,() -> {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var scratch = setup(); var entry = program(language,backend,raw(original(name))).entryTarget("entry"); var stdio = Language.currentState().getStdio(); var selected = name.equals("pathMkdir") ? "new" : "target";
            class Call { Object invoke(ManagedAddress address,Object mode,Object state) { return callScalarTestTarget(entry,new Object[]{0L,address,mode,state}); }}
            var call = new Call(); assertThrows(RuntimeFault.class,() -> Calls.target(entry,new Object[]{0L,ManagedAddress.nullAddress(),0,9L})); var destination = path(scratch,selected.getBytes(StandardCharsets.UTF_8)); assertEquals(-1L,stdio.close(-1)); long prior = stdio.errno(); assertThrows(RuntimeFault.class,() -> call.invoke(destination,448,9L));
            for (long mode : new long[]{-1L,0x1_0000_0000L}) assertThrows(RuntimeFault.class,() -> call.invoke(destination,mode,kotlin.Unit.INSTANCE)); assertThrows(RuntimeFault.class,() -> call.invoke(ManagedAddress.nullAddress(),448,kotlin.Unit.INSTANCE));
            var allocation = ManagedAllocation.mutable(16,8); allocation.writeAddressByteOffset(0,ManagedAddress.nullAddress()); assertThrows(RuntimeFault.class,() -> call.invoke(ManagedAddress.fromAllocation(allocation),448,kotlin.Unit.INSTANCE)); assertEquals(prior,stdio.errno()); assertFalse(Files.exists(scratch.resolve("new"))); assertEquals(420L,unixMode(scratch.resolve("target"))); released(language); return null;
        }); }
        for (var backend : List.of("ast","bytecode")) try (var context = Context.newBuilder("thc").build()) { entered(context,() -> {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var destination = directory.resolve("denied"); var entry = program(language,backend,raw(original("pathMkdir"))).entryTarget("entry");
            assertEquals(-1,callScalarTestTarget(entry,new Object[]{0L,cstring(destination.toString()),448,kotlin.Unit.INSTANCE})); assertEquals(StdioHostAbi.load().error(7),Language.currentState().getStdio().errno()); assertFalse(Files.exists(destination)); return null;
        }); }
    }
    @Test void nativePathAliasesRetainContextAndLifetimeChecks() throws Exception {
        for (var backend : List.of("ast","bytecode")) try (var first = context()) { entered(first,() -> {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var destination = Files.createTempDirectory(directory,"owned-").resolve("new"); var bytes = (destination + "\0").getBytes(StandardCharsets.UTF_8); var state = Language.currentState(); var base = state.getNativeAllocations().malloc(bytes.length + 8L); var alias = base.plus(8); var entry = program(language,backend,raw(original("pathMkdir"))).entryTarget("entry");
            try {
                ManagedAddress.fromByteArray(bytes).copyNonOverlappingTo(alias,bytes.length); try (var second = context()) { entered(second,() -> { var other = TruffleLanguage.LanguageReference.create(Language.class).get(null); var foreign = program(other,backend,raw(original("pathMkdir"))).entryTarget("entry"); assertThrows(RuntimeFault.class,() -> callScalarTestTarget(foreign,new Object[]{0L,alias,448,kotlin.Unit.INSTANCE})); assertFalse(Files.exists(destination)); released(other); return null; }); }
                assertEquals(0,callScalarTestTarget(entry,new Object[]{0L,alias,448,kotlin.Unit.INSTANCE})); assertTrue(Files.isDirectory(destination));
            } finally { state.getNativeAllocations().free(base); }
            assertThrows(RuntimeFault.class,() -> callScalarTestTarget(entry,new Object[]{0L,alias,448,kotlin.Unit.INSTANCE})); released(language); return null;
        }); }
    }
}
