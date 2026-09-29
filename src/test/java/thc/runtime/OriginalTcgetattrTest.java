// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;
import thc.*;
import java.io.*;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.OriginalStdioChecks.*;

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch",matches = "amd64|x86_64")
@Timeout(60)
@SuppressWarnings("unchecked")
class OriginalTcgetattrTest {
    @TempDir Path directory;
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final String prefix = "build/original-tcgetattr";
    private Map<String, Object> cbd(String path) throws Exception { return thc.CoreCbdFixtures.read(new File(root, path).toPath()); }
    private static String entryId(String name) { return "main:OriginalTcgetattrAudit." + name; }
    private Map<String,Object> json(String path) throws Exception { return (Map<String,Object>) Json.parse(Files.readString(new File(root,path).toPath())); }
    private Map<String,Object> module(String stage) throws Exception { var modules = new ArrayList<Map<String,Object>>(); for (var part : List.of("OriginalTcgetattrAudit","THC.InterfaceClosure")) modules.add(cbd(prefix + "/" + stage + "/core/" + part + ".cbd")); return CoreModules.merge(modules); }
    private Context context() { return NativeFileProvider.createContext(Set.of(),ContextProfile.SYNCHRONOUS_TEST); }
    private ManagedAddress address(String path) { return ManagedAddress.fromByteArray((path + "\0").getBytes(StandardCharsets.UTF_8)); }
    private int size() { return (int) TermiosImage.scalar(OriginalStdioOp.SIZEOF_TERMIOS,ManagedAddress.nullAddress(),0); }
    private byte[] image(int fill) { byte[] bytes = new byte[size() + 16]; for (int i = 0; i < bytes.length; i++) bytes[i] = i >= 8 && i < size() + 8 ? (byte) fill : 77; return bytes; }
    private ExecutableProgram load(Language language,String backend,Map<String,Object> source) { return backend.equals("ast") ? new Program(language,source) : new BytecodeProgram(language,source); }
    private OriginalStdioOp validate(List<Object> call) { var reps = new ArrayList<Object>(); for (var arg : (List<List<Object>>) call.get(2)) { var metadata = CoreRepresentations.metadata(arg); reps.add(metadata == null ? null : metadata.get("rep")); } return CoreOriginalStdio.validate(call.get(6),reps,(List<?>) call.get(3),((Map<?,?>) call.get(6)).get("rep")); }
    private byte[] bytes(List<Long> values) { byte[] bytes = new byte[values.size()]; for (int i = 0; i < bytes.length; i++) bytes[i] = values.get(i).byteValue(); return bytes; }
    private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
    private final class Oracle implements AutoCloseable {
        private final Process process;
        private final BufferedWriter input;
        private final BufferedReader output;
        final String path;
        Oracle() throws Exception {
            process = new ProcessBuilder(new File(root,prefix + "/native/oracle").getAbsolutePath(),"--serve").redirectError(ProcessBuilder.Redirect.INHERIT).start();
            input = process.outputWriter(StandardCharsets.UTF_8); output = process.inputReader(StandardCharsets.UTF_8);
            try { path = (String) Json.parse(line()); } catch (Throwable failure) { close(); throw propagate(failure); }
        }
        private String line() throws Exception {
            var line = CompletableFuture.supplyAsync(() -> { try { return output.readLine(); } catch (IOException failure) { throw propagate(failure); } }).get(5,TimeUnit.SECONDS);
            if (line == null) throw new IllegalStateException("Native PTY oracle exited before its response"); return line;
        }
        List<Long> observe(int fill) throws Exception { input.write(fill + "\n"); input.flush(); return (List<Long>) Json.parse(line()); }
        @Override public void close() throws Exception { try { input.close(); } finally { if (!process.waitFor(5,TimeUnit.SECONDS)) process.destroyForcibly().waitFor(5,TimeUnit.SECONDS); output.close(); } }
    }
    @Test void originalImagesMatchPrivatePtyAtInterpretedAndFirstInstalledEntries() throws Exception {
        var manifest = json(prefix + "/manifest.json"); assertEquals(true,manifest.get("supported")); assertEquals(12L,manifest.get("nativeRows")); hashes(root,manifest.get("inputHashes"),Set.of("t/fixtures/compiler/OriginalTcgetattrAudit.hs","t/fixtures/compiler/OriginalTcgetattrNative.hs","t/haskell-fixtures/OriginalTcgetattrFixtures.hs"));
        var artifacts = new HashSet<>(Set.of(prefix + "/oracle.json",prefix + "/native/oracle")); for (var stage : List.of("pre","post")) artifacts.add(prefix + "/" + stage + "/originalTcgetattr.audit.json"); hashes(root,manifest.get("artifactHashes"),artifacts,prefix + "/");
        var recorded = json(prefix + "/oracle.json"); assertEquals((long) size(),recorded.get("size")); var rows = (List<List<Object>>) recorded.get("rows"); assertEquals(12,rows.size()); var abi = StdioHostAbi.load();
        for (int index = 0; index < rows.size(); index++) { var row = rows.get(index); assertEquals((long) (index / 4),row.get(0)); assertEquals(List.of(0L,90L,165L,255L).get(index % 4),row.get(1)); var observation = (List<Long>) row.get(2); assertEquals(size() + 18,observation.size());
            for (int i = 0; i < 8; i++) { assertEquals(77L,observation.get(2 + i)); assertEquals(77L,observation.get(observation.size() - 8 + i)); }
            if (Objects.equals(row.get(0),0L)) assertEquals(List.of(0L,0L),observation.subList(0,2)); else { assertEquals(List.of(-1L,Objects.equals(row.get(0),1L) ? abi.notTerminal() : abi.error(4)),observation.subList(0,2)); assertArrayEquals(image(((Long) row.get(1)).intValue()),bytes(observation.subList(2,observation.size()))); }
        }
        try (var oracle = new Oracle()) {
            for (var stage : List.of("pre","post")) {
                var audit = json(prefix + "/" + stage + "/originalTcgetattr.audit.json"); assertEquals(true,audit.get("accepted")); assertEquals(List.of(),audit.get("issues")); assertEquals(List.of(),audit.get("missingGlobals")); var audited = new ArrayList<Object>(); for (var call : (List<Map<?,?>>) audit.get("foreignCalls")) audited.add(call.get("symbol")); assertEquals(List.of(OriginalStdioOp.TCGETATTR.getSymbol()),audited);
                var source = module(stage); assertEquals(OriginalStdioOp.TCGETATTR,validate(single(foreignCalls(source),ignored -> true)));
                for (var backend : List.of("ast","bytecode")) try (var context = context()) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var io = Language.currentState().getStdio(); long fd = io.open(address(oracle.path),2L | 0x100L,0); assertTrue(fd >= 3);
                        var plain = directory.resolve("plain-" + stage + "-" + backend); Files.writeString(plain,"data"); long regular = io.open(address(plain.toString()),0,0); assertTrue(regular >= 3);
                        var program = load(language,backend,with(CoreModules.reachable(source, entryId("originalTcgetattr"),true),"instrument",true)); var entry = program.entryTarget(entryId("originalTcgetattr"));
                        class Exercise { void run(boolean compiled) throws Exception {
                            for (int fill : new int[]{0,90,165,255}) for (long descriptor : new long[]{fd,regular,-1L}) {
                                var nativeImage = descriptor == fd ? oracle.observe(fill) : null; var bytes = image(fill); assertEquals(-1L,io.close(-1)); long sticky = io.errno(); long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                                var result = Calls.target(entry,new Object[]{0L,descriptor,ManagedAddress.fromByteArray(bytes).plus(8)}); if (compiled) assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before);
                                if (nativeImage != null) { assertEquals(0L,result); assertEquals(List.of(0L,0L),nativeImage.subList(0,2)); assertArrayEquals(bytes(nativeImage.subList(2,nativeImage.size())),bytes); assertEquals(sticky,io.errno()); }
                                else { assertEquals(-1L,result); assertEquals(descriptor == regular ? abi.notTerminal() : abi.error(4),io.errno()); assertArrayEquals(image(fill),bytes); }
                                assertEquals(0,language.getHandoffState().get().getArguments().getDepth()); assertEquals(0,language.getHandoffState().get().getResults().getDepth());
                            }
                        }}
                        var exercise = new Exercise(); exercise.run(false); var cls = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"); for (var target : targets(entry)) { cls.getMethod("compile",boolean.class).invoke(target,true); assertEquals(true,cls.getMethod("isValidLastTier").invoke(target)); }
                        var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode",cls).invoke(runtime,entry); exercise.run(true); assertEquals(0L,program.diagnostics().get("unsupportedTraps"));
                    } finally { context.leave(); }
                }
            }
        }
    }
    @Test void preflightStateAndExactForeignProofsGuardTheWholeImage() throws Exception {
        var source = module("pre"); var original = single(foreignCalls(source),ignored -> true);
        for (var backend : List.of("ast","bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var target = load(language,backend,rawModule(original,source)).entryTarget("entry"); var io = Language.currentState().getStdio(); var bytes = image(90); var valid = ManagedAddress.fromByteArray(bytes).plus(8);
                assertEquals(-1L,io.close(-1)); long sticky = io.errno(); assertThrows(RuntimeFault.class,() -> Calls.target(target,new Object[]{0L,-1L,valid,9L}));
                for (long bad : new long[]{Long.MIN_VALUE,2147483648L}) assertThrows(RuntimeFault.class,() -> Calls.target(target,new Object[]{0L,bad,valid,thc.runtime.Unit.INSTANCE}));
                var pointerCell = ManagedAddress.fromAllocation(PinnedMemory.allocate(size(),8)); pointerCell.writeAddressElementIndex(0,valid);
                for (var bad : List.of(ManagedAddress.nullAddress(),valid.plus(9),pointerCell,ManagedAddress.fromByteArray(new byte[size() - 1]),ManagedAddress.fromHex("00".repeat(size())))) assertThrows(RuntimeFault.class,() -> Calls.target(target,new Object[]{0L,-1L,bad,thc.runtime.Unit.INSTANCE}));
                assertArrayEquals(image(90),bytes); assertEquals(sticky,io.errno()); assertSame(valid,pointerCell.readAddressElementIndex(0));
                for (int i = 0; i <= 2; i++) { int index = i; assertThrows(RuntimeFault.class,() -> load(language,backend,rawModule(original,source,index))); }
                for (var edit : List.of(list("convention","ccall"),list("safety","safe"),list("arity",2L))) { var changed = (List<Object>) Json.parse(Json.stringify(original)); ((Map<String,Object>) ((Map<?,?>) changed.get(6)).get("foreignCall")).put((String) edit.get(0),edit.get(1)); assertThrows(RuntimeFault.class,() -> validate(changed)); }
            } finally { context.leave(); }
        }
    }
    @Test void descriptorAliasesReuseContextAndDisposalKeepTheirOriginalResource() throws Exception {
        try (var ordinary = Context.create("thc")) { ordinary.initialize("thc"); ordinary.enter(); try { var bytes = image(90); var io = Language.currentState().getStdio(); assertEquals(-1L,io.tcgetattr(0,ManagedAddress.fromByteArray(bytes).plus(8))); assertEquals(StdioHostAbi.load().error(7),io.errno()); assertArrayEquals(image(90),bytes,"Embedding streams grant no native terminal authority"); } finally { ordinary.leave(); } }
        try (var oracle = new Oracle()) {
            var first = context(); var second = context(); OpenedNativeFile resource;
            try {
                first.initialize("thc"); first.enter();
                try {
                    var io = Language.currentState().getStdio(); long fd = io.open(address(oracle.path),2L | 0x100L,0); assertTrue(fd >= 3); long alias = io.duplicate(fd); assertTrue(alias >= 3); assertEquals(0L,io.close(fd));
                    var file = directory.resolve("reused"); Files.writeString(file,"data"); assertEquals(fd,io.open(address(file.toString()),0,0)); var bytes = image(165); var destination = ManagedAddress.fromByteArray(bytes).plus(8);
                    assertEquals(-1L,io.tcgetattr(fd,destination)); assertEquals(StdioHostAbi.load().notTerminal(),io.errno()); assertArrayEquals(image(165),bytes); assertEquals(0L,io.tcgetattr(alias,destination)); var observation = oracle.observe(165); assertArrayEquals(bytes(observation.subList(2,observation.size())),bytes);
                    resource = NativeFileProvider.current().openRaw((oracle.path + "\0").getBytes(StandardCharsets.UTF_8),2 | 0x100,0);
                } finally { first.leave(); }
                var originalResource = resource; second.initialize("thc"); second.enter(); try { assertThrows(RuntimeFault.class,() -> originalResource.readTermios(new byte[size()])); } finally { second.leave(); }
                first.initialize("thc"); first.enter();
                try { originalResource.close(); assertThrows(ClosedChannelException.class,() -> originalResource.readTermios(new byte[size()])); resource = NativeFileProvider.current().openRaw((oracle.path + "\0").getBytes(StandardCharsets.UTF_8),2 | 0x100,0); } finally { first.leave(); }
                first.close(); assertFalse(resource.isOpen()); resource.close();
            } finally { first.close(); second.close(); }
        }
    }
}
