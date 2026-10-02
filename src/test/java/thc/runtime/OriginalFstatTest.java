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
import thc.NativeIO.StandardEndpoint;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;
import static thc.runtime.OriginalStdioChecks.*;

/** Genuine installed __hscore_fstat FCallIds; context descriptors, never host fds. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch",matches = "amd64|x86_64")
@SuppressWarnings("unchecked")
class OriginalFstatTest {
    @TempDir Path directory;
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final String prefix = "build/original-posix-stat";
    private final List<String> entries = List.of("originalFstat","originalFstatErrno");
    private Map<String,Object> json(String path) throws Exception { return (Map<String,Object>) Json.parse(Files.readString(new File(root,path).toPath())); }
    private Map<String,Object> module(String stage) throws Exception { var modules = new ArrayList<Map<String,Object>>(); for (var part : List.of("OriginalPosixStatAudit","THC.InterfaceClosure")) modules.add(thc.CoreCbdFixtures.read(new File(root, prefix + "/" + stage + "/core/" + part + ".cbd").toPath())); return CoreModules.merge(modules); }
    private Object copy(Object value) { return Json.parse(Json.stringify(value)); }
    private ManagedAddress path(Path value) { return ManagedAddress.fromByteArray((value + "\0").getBytes(StandardCharsets.UTF_8)); }
    private long field(ManagedAddress address,OriginalStdioOp operation) { return PosixStat.execute(operation,address,0); }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true,target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private static <T> T entered(Context context,Callable<T> action) throws Exception { context.initialize("thc"); context.enter(); try { return action.call(); } finally { context.leave(); } }
    private ExecutableProgram load(Language language,String backend,Map<String,Object> module) { return backend.equals("ast") ? new Program(language,module) : new BytecodeProgram(language,module); }
    private static List<Long> longs(Object values) { var result = new ArrayList<Long>(); for (var value : (List<Number>) values) result.add(value.longValue()); return result; }
    @Test void nativeOriginalObservationsMatchBothBackendsAndEveryFirstInstalledCall() throws Exception {
        var manifest = json(prefix + "/manifest.json"); assertEquals(true,manifest.get("supported")); hashes(root,manifest.get("inputHashes"),Set.of("t/fixtures/compiler/OriginalPosixStatAudit.hs","t/fixtures/compiler/OriginalPosixStatNative.hs","t/haskell-fixtures/OriginalPosixStatFixtures.hs","bin/core_original_foreign.py","bin/core-capabilities.json"));
        var artifacts = new HashSet<>(Set.of(prefix + "/oracle.json")); for (var stage : List.of("pre","post")) { for (var name : entries) artifacts.add(prefix + "/" + stage + "/" + name + ".audit.json"); for (var part : List.of("OriginalPosixStatAudit","THC.InterfaceClosure")) artifacts.add(prefix + "/" + stage + "/core/" + part + ".cbd"); } hashes(root,manifest.get("artifactHashes"),artifacts,prefix + "/");
        var oracle = json(prefix + "/oracle.json"); int size = ((Number) oracle.get("size")).intValue(); var rows = (List<List<Object>>) oracle.get("fstats"); var names = new ArrayList<Object>(); for (var row : rows) names.add(row.get(0)); assertEquals(List.of("initial","resized","chmod","renamed","unlinked","invalid","closed"),names);
        for (var row : rows) { boolean success = !List.of("invalid","closed").contains(row.get(0)); assertEquals(success ? 0L : -1L,((Number) row.get(1)).longValue()); assertEquals(StdioHostAbi.load().error(4),((Number) row.get(2)).longValue());
            assertEquals(success ? List.of(Objects.equals(row.get(0),"initial") ? 256L : 17L,1L,1L,1L,List.of("initial","resized").contains(row.get(0)) ? 384L : 256L) : List.of(),longs(row.get(3))); assertEquals(true,row.get(4)); }
        for (var stage : List.of("pre","post")) for (var name : entries) {
            var audit = json(prefix + "/" + stage + "/" + name + ".audit.json"); assertEquals(true,audit.get("accepted")); assertEquals(List.of(),audit.get("issues")); assertEquals(List.of(),audit.get("missingGlobals"));
            var linked = with(CoreModules.reachable(module(stage),"main:OriginalPosixStatAudit." + name),"instrument",true);
            var fstats = new ArrayList<List<Object>>(); for (var call : foreignCalls(linked)) if (Objects.equals(((Map<?,?>) ((Map<?,?>) ((Map<?,?>) call.get(6)).get("foreignCall")).get("target")).get("symbol"),"__hscore_fstat")) fstats.add(call); assertFalse(fstats.isEmpty()); for (var call : fstats) assertEquals(OriginalStdioOp.FSTAT,validate(call));
            for (var backend : List.of("ast","bytecode")) try (var context = NativeFileProvider.createContext(Set.of(),ContextProfile.SYNCHRONOUS_TEST)) { entered(context,() -> {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var state = Language.currentState(); var files = state.getFiles(); var stdio = state.getStdio(); var program = load(language,backend,linked); var entry = program.entryTarget("main:OriginalPosixStatAudit." + name);
                class Exercise {
                    void run(boolean compiled) throws Exception {
                        var original = directory.resolve("file"); var renamed = directory.resolve("renamed"); byte[] input = new byte[256]; for (int i = 0; i < input.length; i++) input[i] = (byte) i; Files.write(original,input); Files.setPosixFilePermissions(original,PosixFilePermissions.fromString("rw-------"));
                        long fd = files.open(path(original),3,ForeignSafety.UNSAFE); assertTrue(fd >= 3); long alias = files.duplicate(fd); assertTrue(alias >= 3); var image = ManagedAddress.fromByteArray(files.statImage(fd)); long device = field(image,OriginalStdioOp.ST_DEV), inode = field(image,OriginalStdioOp.ST_INO);
                        for (var row : rows) {
                            switch ((String) row.get(0)) { case "resized" -> assertEquals(0L,files.setSize(fd,17)); case "chmod" -> Files.setPosixFilePermissions(original,PosixFilePermissions.fromString("r--------"));
                                case "renamed" -> { Files.move(original,renamed); Files.write(original,new byte[]{42}); } case "unlinked" -> Files.delete(renamed); case "closed" -> { assertEquals(0L,files.close(alias)); assertEquals(0L,files.close(fd)); } }
                            byte[] bytes = new byte[size + 16]; Arrays.fill(bytes,(byte) 90); var address = ManagedAddress.fromByteArray(bytes).plus(8); long source = Objects.equals(row.get(0),"invalid") ? -1L : Objects.equals(row.get(0),"renamed") ? alias : fd;
                            assertEquals(-1L,stdio.close(-1)); long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            long expected = ((Number) row.get(name.equals("originalFstat") ? 1 : 2)).longValue(); assertEquals(expected,callScalarTestTarget(entry,new Object[]{0L,source,address}),stage + "/" + backend + "/" + name + "/" + row.get(0)); assertEquals(((Number) row.get(2)).longValue(),stdio.errno());
                            if (compiled) assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before);
                            assertEquals(true,row.get(4),"Native destination/canary observation");
                            if (((Number) row.get(1)).longValue() == 0) { long mode = field(address,OriginalStdioOp.ST_MODE);
                                var observed = List.of(field(address,OriginalStdioOp.ST_SIZE),PosixStat.execute(OriginalStdioOp.IS_REG,ManagedAddress.nullAddress(),mode),field(address,OriginalStdioOp.ST_DEV) == device ? 1L : 0L,field(address,OriginalStdioOp.ST_INO) == inode ? 1L : 0L,mode & 511L); assertEquals(longs(row.get(3)),observed);
                                for (int i = 0; i < 8; i++) assertEquals((byte) 90,bytes[i]); for (int i = size + 8; i < bytes.length; i++) assertEquals((byte) 90,bytes[i]);
                            } else for (byte value : bytes) assertEquals((byte) 90,value);
                            assertEquals(0,language.getHandoffState().get().getArguments().getDepth()); assertEquals(0,language.getHandoffState().get().getResults().getDepth()); assertEquals(0,language.getHandoffState().get().getResults().retainedReferences());
                        }
                        Files.delete(original);
                    }
                }
                var exercise = new Exercise(); exercise.run(false);
                for (var target : targets(entry)) { target.getClass().getMethod("compile",boolean.class).invoke(target,true); valid(target); } exercise.run(true); return null;
            }); }
        }
    }
    private List<Object> original() throws Exception { for (var call : foreignCalls(module("pre"))) if (Objects.equals(((Map<?,?>) ((Map<?,?>) ((Map<?,?>) call.get(6)).get("foreignCall")).get("target")).get("symbol"),"__hscore_fstat")) return call; throw new NoSuchElementException(); }
    private OriginalStdioOp validate(List<Object> call) { var metadata = (Map<?,?>) call.get(6); var reps = new ArrayList<Object>(); for (var arg : (List<List<Object>>) call.get(2)) { var rep = CoreRepresentations.metadata(arg); reps.add(rep == null ? null : rep.get("rep")); } return CoreOriginalStdio.validate(metadata,reps,(List<?>) call.get(3),metadata.get("rep")); }
    @Test void stateAndFullWritableByteRegionPrecedeAnyObservationOrMutation() throws Exception {
        int size = (int) PosixStat.execute(OriginalStdioOp.SIZEOF_STAT,ManagedAddress.nullAddress(),0);
        for (var backend : List.of("ast","bytecode")) try (var context = NativeFileProvider.createContext(EnumSet.allOf(StandardEndpoint.class),ContextProfile.SYNCHRONOUS_TEST)) { entered(context,() -> {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var state = Language.currentState(); byte[] bytes = new byte[size + 2]; Arrays.fill(bytes,(byte) 90);
            var program = load(language,backend,rawModule(original(),module("pre"))); var entry = program.entryTarget("entry"); var address = ManagedAddress.fromByteArray(bytes).plus(1);
            class Call { Object invoke(Object fd,ManagedAddress output,Object token) { return callScalarTestTarget(entry,new Object[]{0L,fd,output,token}); }}
            var call = new Call(); assertThrows(RuntimeFault.class,() -> Calls.target(entry,new Object[]{0L,1,address,9L})); assertEquals(-1L,state.getStdio().close(-1)); long errno = state.getStdio().errno(); assertThrows(RuntimeFault.class,() -> call.invoke(1,address,9L)); assertThrows(RuntimeFault.class,() -> call.invoke(1L << 32,address,thc.runtime.Unit.INSTANCE));
            for (var bad : List.of(ManagedAddress.nullAddress(),ManagedAddress.fromByteArray(new byte[size - 1]),ManagedAddress.fromHex("00".repeat(size)),address.plus(2))) assertThrows(RuntimeFault.class,() -> call.invoke(1,bad,thc.runtime.Unit.INSTANCE));
            var allocation = ManagedAllocation.mutable(size + 16L,8); var pointer = ManagedAddress.fromAllocation(allocation); allocation.writeAddressByteOffset(8,address); assertThrows(RuntimeFault.class,() -> call.invoke(1,pointer,thc.runtime.Unit.INSTANCE)); assertSame(address,allocation.readAddressByteOffset(8));
            assertEquals(errno,state.getStdio().errno()); for (byte value : bytes) assertEquals((byte) 90,value); assertEquals(-1,call.invoke(-1,address,thc.runtime.Unit.INSTANCE)); for (byte value : bytes) assertEquals((byte) 90,value); assertEquals(StdioHostAbi.load().error(4),state.getStdio().errno());
            for (int fd = 0; fd <= 2; fd++) { assertEquals(0,call.invoke(fd,address,thc.runtime.Unit.INSTANCE)); assertEquals(90,(int) bytes[0]); assertEquals(90,(int) bytes[bytes.length - 1]); }
            for (int i = 0; i <= 2; i++) { int index = i; assertThrows(RuntimeFault.class,() -> load(language,backend,rawModule(original(),module("pre"),index))); } return null;
        }); }
        try (var context = Context.newBuilder("thc").build()) { entered(context,() -> { byte[] bytes = new byte[size]; Arrays.fill(bytes,(byte) 90); var stdio = Language.currentState().getStdio(); assertEquals(-1L,stdio.fstat(1,ManagedAddress.fromByteArray(bytes))); assertEquals(StdioHostAbi.load().error(7),stdio.errno()); for (byte value : bytes) assertEquals((byte) 90,value); return null; }); }
    }
    @Test void genuineDescriptorRejectsEveryChangedAbiAndDefinedOrMalformedHead() throws Exception {
        var original = original(); assertEquals(OriginalStdioOp.FSTAT,validate(original));
        var reject = new Object() { void call(BiConsumer<List<Object>,Map<String,Object>> action) { var call = (List<Object>) copy(original); var descriptor = (Map<String,Object>) ((Map<?,?>) call.get(6)).get("foreignCall"); action.accept(call,descriptor); assertThrows(RuntimeFault.class,() -> validate(call)); }};
        for (var key : List.of("schema","arity","suppliedArity")) for (var value : list(null,true,3.0,"3",0L,1L << 32)) reject.call((c,d) -> d.put(key,value));
        for (var edit : List.of(list("convention","capi"),list("safety","safe"),list("extra",0L))) reject.call((c,d) -> d.put((String) edit.get(0),edit.get(1)));
        for (var edit : List.of(list("unit","base"),list("kind","dynamic"),list("isFunction",false),list("extra",true))) reject.call((c,d) -> ((Map<String,Object>) d.get("target")).put((String) edit.get(0),edit.get(1)));
        for (int i = 0; i <= 2; i++) { int index = i; reject.call((c,d) -> ((List<Object>) c.get(3)).set(index,true)); reject.call((c,d) -> ((List<Map<String,Object>>) d.get("argumentReps")).get(index).put("primReps",list("WordRep"))); reject.call((c,d) -> ((List<Map<String,Object>>) d.get("argumentReps")).get(index).put("aggregate","unboxed-tuple")); }
        for (var key : List.of("resultRep","rep")) reject.call((c,d) -> ((Map<String,Object>) (key.equals("rep") ? (Map<?,?>) c.get(6) : d).get(key)).put("primReps",list("WordRep")));
        for (var backend : List.of("ast","bytecode")) try (var context = Context.newBuilder("thc").build()) { entered(context,() -> {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
            for (var head : list(list("var",17L),list("var","entry",map("rep",OriginalStdioFixtures.closure())))) { var bad = (Map<String,Object>) copy(rawModule(original,module("pre"))); single(foreignCalls(bad),ignored -> true).set(1,head); assertThrows(RuntimeFault.class,() -> load(language,backend,bad)); } return null;
        }); }
    }
}
