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
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.OriginalStdioChecks.*;

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch",matches = "amd64|x86_64")
@SuppressWarnings("unchecked")
class OriginalDirectoryPathsTest {
    @TempDir Path directory;
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final String prefix = "build/original-directory-paths";
    private final Map<String,OriginalStdioOp> operations = new LinkedHashMap<>();
    OriginalDirectoryPathsTest() { operations.put("pathRemoveDirectory",OriginalStdioOp.RMDIR); operations.put("executableReadlink",OriginalStdioOp.READLINK); }
    private Map<String,Object> json(String path) throws Exception { return (Map<String,Object>) Json.parse(Files.readString(new File(root,path).toPath())); }
    private Map<String,Object> source(String stage) throws Exception { return json(prefix + "/" + stage + ".json"); }
    private Object copy(Object value) { return Json.parse(Json.stringify(value)); }
    private ManagedAddress address(byte[] bytes) { return ManagedAddress.fromByteArray(Arrays.copyOf(bytes,bytes.length + 1)); }
    private ManagedAddress address(String value) { return address(value.getBytes(StandardCharsets.UTF_8)); }
    private ManagedAddress path(Path path) { return ManagedAddress.fromByteArray(NativeDirectoryOwner.pathBytes(path)); }
    private byte[] joined(Path base,byte[] name) { var prefix = NativeDirectoryOwner.pathBytes(base); var bytes = Arrays.copyOf(prefix,prefix.length + name.length); bytes[prefix.length - 1] = 47; System.arraycopy(name,0,bytes,prefix.length,name.length); return bytes; }
    private Path raw(Path base,byte[] name) { return NativeDirectoryOwner.bytesPath(joined(base,name)); }
    private byte[] bytes(Map<String,Object> row,String key) { var values = (List<Long>) row.get(key); byte[] result = new byte[values.size()]; for (int i = 0; i < result.length; i++) result[i] = values.get(i).byteValue(); return result; }
    private Context context() { return NativeFileProvider.createContext(Set.of(),ContextProfile.SYNCHRONOUS_TEST); }
    private ExecutableProgram program(Language language,String backend,Map<String,Object> module) { return backend.equals("ast") ? new Program(language,module) : new BytecodeProgram(language,module); }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true,target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private List<Object> original(String name) throws Exception { return original(name,"post"); }
    private List<Object> original(String name,String stage) throws Exception { return single(foreignCalls(CoreModules.reachable(source(stage),name)),ignored -> true); }
    private OriginalStdioOp validate(List<Object> call) {
        var reps = new ArrayList<Object>(); for (var arg : (List<List<Object>>) call.get(2)) { var metadata = CoreRepresentations.metadata(arg); reps.add(metadata == null ? null : metadata.get("rep")); }
        return CoreOriginalStdio.validate(call.get(6),reps,(List<?>) call.get(3),((Map<?,?>) call.get(6)).get("rep"));
    }
    private Path setup() throws Exception {
        var base = Files.createTempDirectory(directory,"case-"); Files.setPosixFilePermissions(base,PosixFilePermissions.fromString("rwx------")); Files.writeString(base.resolve("file"),"unchanged");
        for (var name : List.of("empty","nonempty","denied")) Files.createDirectory(base.resolve(name)); Files.createDirectory(raw(base,new byte[]{-1,110}));
        Files.writeString(base.resolve("nonempty/file"),"unchanged"); Files.createDirectory(base.resolve("denied/child")); Files.createSymbolicLink(base.resolve("link"),Path.of("empty")); Files.createSymbolicLink(base.resolve("dangling"),Path.of("missing"));
        Files.createSymbolicLink(base.resolve("raw-link"),Path.of("/").relativize(NativeDirectoryOwner.bytesPath(new byte[]{47,-1,110}))); Files.createSymbolicLink(base.resolve("absolute-link"),Path.of("/thc-native-readlink")); Files.setPosixFilePermissions(base.resolve("denied"),Set.of()); return base;
    }
    private void restorePermissions(Path base) throws Exception { Files.setPosixFilePermissions(base.resolve("denied"),PosixFilePermissions.fromString("rwx------")); }
    private List<List<Long>> remaining(Path base) {
        var names = new ArrayList<byte[]>(); for (var name : List.of("empty","nonempty","file","link","dangling","raw-link","absolute-link","denied","denied/child")) names.add(name.getBytes(StandardCharsets.UTF_8)); names.add(new byte[]{-1,110});
        var result = new ArrayList<List<Long>>(); for (var name : names) if (Files.exists(raw(base,name),LinkOption.NOFOLLOW_LINKS)) { var bytes = new ArrayList<Long>(); for (byte value : name) bytes.add((long) value & 255); result.add(bytes); } return result;
    }
    @Test void genuineInstalledOwnersMatchNativeAndEveryFirstInstalledEntry() throws Exception {
        var manifest = json(prefix + "/manifest.json"); assertEquals(new ArrayList<>(operations.keySet()),manifest.get("entries")); assertTrue(CoreOriginalStdio.isOriginalUnixUnit(manifest.get("unixUnit"))); assertEquals("ghc-internal",manifest.get("readlinkUnit"));
        hashes(root,manifest.get("inputHashes"),Set.of("test/fixtures/compiler/OriginalDirectoryPathsAudit.hs","test/haskell-fixtures/OriginalDirectoryPathsFixtures.hs","bin/core_original_foreign.py","bin/core-capabilities.json"));
        var artifacts = new HashSet<>(Set.of(prefix + "/oracle.json")); for (var stage : List.of("pre","post")) { artifacts.add(prefix + "/" + stage + ".json"); for (var name : operations.keySet()) artifacts.add(prefix + "/" + stage + "-" + name + ".audit.json"); } hashes(root,manifest.get("artifactHashes"),artifacts,prefix + "/");
        var oracle = json(prefix + "/oracle.json"); var removeRows = (List<Map<String,Object>>) oracle.get("removeRows"); var readRows = (List<Map<String,Object>>) oracle.get("readRows"); assertEquals(15,removeRows.size()); assertEquals(12,readRows.size());
        var removeNames = new HashSet<Object>(); for (var row : removeRows) removeNames.add(row.get("name")); var readNames = new HashSet<Object>(); for (var row : readRows) readNames.add(row.get("name")); assertEquals(15,removeNames.size()); assertEquals(12,readNames.size()); assertEquals(true,oracle.get("processCwdUnchanged"));
        var processDirectory = Files.readSymbolicLink(Path.of("/proc/self/cwd"));
        for (var stage : List.of("pre","post")) for (var entry : operations.entrySet()) {
            var name = entry.getKey(); var operation = entry.getValue(); var audit = json(prefix + "/" + stage + "-" + name + ".audit.json"); assertEquals(true,audit.get("accepted")); assertEquals(List.of(),audit.get("issues")); assertEquals(List.of(),audit.get("missingGlobals"));
            var linked = with(CoreModules.reachable(source(stage),name),"instrument",true); var evidence = new ArrayCoreEvidence(linked,name); assertEquals(1,evidence.getBindings().size()); assertEquals(1,evidence.loweredGuestLambdas(evidence.getRoot().get("expr")).size()); assertEquals(operation,validate(original(name,stage)));
            assertEquals(operation == OriginalStdioOp.RMDIR ? manifest.get("unixUnit") : "ghc-internal",((Map<?,?>) ((Map<?,?>) ((Map<?,?>) original(name,stage).get(6)).get("foreignCall")).get("target")).get("unit"));
            for (var backend : List.of("ast","bytecode")) try (var context = context()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var executable = program(language,backend,linked); var target = executable.entryTarget(name); var stdio = Language.currentState().getStdio();
                    class Exercise { void run(boolean compiled) throws Exception {
                        for (var row : operation == OriginalStdioOp.RMDIR ? removeRows : readRows) {
                            var base = setup();
                            try {
                                assertEquals(0L,stdio.changeDirectory(path(base))); var suffix = bytes(row,"path"); var argument = Objects.equals(row.get("absolute"),true) ? address(joined(base,suffix)) : address(suffix);
                                int capacity = row.get("capacity") instanceof Long count ? count.intValue() : 64; byte[] image = new byte[capacity + 16]; Arrays.fill(image,(byte) 90); stdio.setErrno(9); if (compiled) valid(target);
                                long before = ((Number) executable.diagnostics().get("compiledEntries")).longValue(); Object[] args = operation == OriginalStdioOp.RMDIR ? new Object[]{0L,argument} : new Object[]{0L,argument,ManagedAddress.fromByteArray(image).plus(8),(long) capacity};
                                var result = Calls.target(target,args); assertEquals(row.get("status"),result,stage + "/" + backend + "/" + name + "/" + row.get("name")); assertEquals(row.get("errno"),stdio.errno());
                                if (compiled) { assertEquals(before + 1,((Number) executable.diagnostics().get("compiledEntries")).longValue()); valid(target); }
                                restorePermissions(base); if (operation == OriginalStdioOp.RMDIR) assertEquals(row.get("remaining"),remaining(base)); else assertArrayEquals(bytes(row,"image"),image);
                                assertEquals("unchanged",Files.readString(base.resolve("file"))); assertEquals("unchanged",Files.readString(base.resolve("nonempty/file"))); var handoff = language.getHandoffState().get();
                                assertEquals(0,handoff.getArguments().getDepth()); assertEquals(0,handoff.getResults().getDepth()); assertEquals(0,handoff.getArguments().retainedReferences()); assertEquals(0,handoff.getResults().retainedReferences());
                            } finally { restorePermissions(base); }
                        }
                    }}
                    var exercise = new Exercise(); exercise.run(false); target.getClass().getMethod("compile",boolean.class).invoke(target,true); valid(target); exercise.run(true); assertEquals(0L,((Number) executable.diagnostics().get("unsupportedTraps")).longValue());
                } finally { context.leave(); }
            }
        }
        assertEquals(processDirectory,Files.readSymbolicLink(Path.of("/proc/self/cwd")));
    }
    @Test void originalPackageSafetyResultAndOperandGuardsRemainRequired() throws Exception {
        for (var entry : operations.entrySet()) {
            var name = entry.getKey(); var operation = entry.getValue(); var call = original(name); assertEquals(operation,validate(call));
            var units = new ArrayList<>(List.of("main","unix-2.8.7.0-inplace","unix-2.8.8.0-ABCD","unix-2.8.8.0-460b:forged","ghc-internal:forged")); if (operation == OriginalStdioOp.RMDIR) units.add("ghc-internal");
            for (var unit : units) { var bad = (List<Object>) copy(call); ((Map<String,Object>) ((Map<?,?>) ((Map<?,?>) bad.get(6)).get("foreignCall")).get("target")).put("unit",unit); assertThrows(RuntimeFault.class,() -> validate(bad)); }
            for (var edit : List.of(list("safety","safe"),list("convention","capi"),list("arity",99L),list("suppliedArity",99L))) { var bad = (List<Object>) copy(call); ((Map<String,Object>) ((Map<?,?>) bad.get(6)).get("foreignCall")).put((String) edit.get(0),edit.get(1)); assertThrows(RuntimeFault.class,() -> validate(bad)); }
            var wider = (List<Object>) copy(call); var meta = (Map<?,?>) wider.get(6); for (var rep : list(meta.get("rep"),((Map<?,?>) meta.get("foreignCall")).get("resultRep"))) {
                var proof = (Map<String,Object>) rep; proof.put("primReps",list("Int64Rep")); ((List<Map<String,Object>>) proof.get("components")).get(1).put("primReps",list("Int64Rep"));
            }
            assertThrows(RuntimeFault.class,() -> validate(wider));
            for (var backend : List.of("ast","bytecode")) try (var context = context()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (int i = 0; i < ((List<?>) call.get(2)).size(); i++) { int index = i; assertThrows(RuntimeFault.class,() -> program(language,backend,rawModule(call,source("post"),index))); }
                    var module = rawModule(call,source("post")); var body = single((List<Map<String,Object>>) module.get("bindings"),ignored -> true).get("expr"); var changed = single(foreignCalls(body),ignored -> true); changed.set(1,list("var","p0",((List<?>) changed.get(1)).get(2)));
                    assertThrows(RuntimeFault.class,() -> program(language,backend,module));
                } finally { context.leave(); }
            }
        }
    }
    @Test void invalidStateAndRenamedCwdPreserveNamespaceAuthority() throws Exception {
        for (var backend : List.of("ast","bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var base = setup(); var moved = base.resolveSibling(base.getFileName() + "-moved"); var stdio = Language.currentState().getStdio();
                try {
                    assertEquals(0L,stdio.changeDirectory(path(base))); var remove = program(language,backend,rawModule(original("pathRemoveDirectory"),source("post"))).entryTarget("entry"); var read = program(language,backend,rawModule(original("executableReadlink"),source("post"))).entryTarget("entry");
                    byte[] image = new byte[32]; Arrays.fill(image,(byte) 90); var output = ManagedAddress.fromByteArray(image).plus(8); stdio.setErrno(9);
                    assertThrows(RuntimeFault.class,() -> Calls.target(remove,new Object[]{0L,address("empty"),7L})); assertThrows(RuntimeFault.class,() -> Calls.target(read,new Object[]{0L,address("link"),output,16L,7L}));
                    assertTrue(Files.isDirectory(base.resolve("empty"))); for (byte value : image) assertEquals((byte) 90,value); assertEquals(9L,stdio.errno());
                    Files.move(base,moved); Files.createDirectory(base); Files.createDirectory(base.resolve("empty")); assertEquals(0,Calls.target(remove,new Object[]{0L,address("empty"),thc.runtime.Unit.INSTANCE}));
                    assertFalse(Files.exists(moved.resolve("empty"))); assertTrue(Files.isDirectory(base.resolve("empty"))); assertEquals(5,Calls.target(read,new Object[]{0L,address("link"),output,16L,thc.runtime.Unit.INSTANCE}));
                    assertArrayEquals("empty".getBytes(StandardCharsets.UTF_8),Arrays.copyOfRange(image,8,13)); assertEquals((byte) 90,image[13]);
                } finally { restorePermissions(Files.exists(moved) ? moved : base); }
            } finally { context.leave(); }
        }
    }
}
