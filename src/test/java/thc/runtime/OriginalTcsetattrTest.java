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
class OriginalTcsetattrTest {
    @TempDir Path directory;
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final String prefix = "build/original-tcsetattr";
    private Map<String, Object> cbd(String path) throws Exception { return thc.CoreCbdFixtures.read(new File(root, path).toPath()); }
    private static String entryId(String name) { return "main:OriginalTcsetattrAudit." + name; }
    private Map<String,Object> json(String path) throws Exception { return (Map<String,Object>) Json.parse(Files.readString(new File(root,path).toPath())); }
    private Map<String,Object> module(String stage) throws Exception { var modules = new ArrayList<Map<String,Object>>(); for (var part : List.of("OriginalTcsetattrAudit","THC.InterfaceClosure")) modules.add(cbd(prefix + "/" + stage + "/core/" + part + ".cbd")); return CoreModules.merge(modules); }
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
            try { path = (String) Json.parse(line()); } catch (Throwable failure) { try { close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); } throw propagate(failure); }
        }
        private String line() throws Exception {
            var line = CompletableFuture.supplyAsync(() -> { try { return output.readLine(); } catch (IOException failure) { throw propagate(failure); } }).get(5,TimeUnit.SECONDS);
            if (line == null) throw new IllegalStateException("Native PTY oracle exited before its response"); return line;
        }
        List<Long> observe(long action,long echo) throws Exception { input.write("[" + action + "," + echo + "]\n"); input.flush(); return (List<Long>) Json.parse(line()); }
        void reset() throws Exception { input.write("[]\n"); input.flush(); if (!Objects.equals(Json.parse(line()),List.of())) throw new IllegalStateException("Check failed."); }
        @Override public void close() throws Exception { try { input.close(); } finally { if (!process.waitFor(5,TimeUnit.SECONDS)) process.destroyForcibly().waitFor(5,TimeUnit.SECONDS); output.close(); } }
    }
    @BeforeEach void verifyOriginalInputsAndArtifacts() throws Exception {
        var manifest = json(prefix + "/manifest.json"); assertEquals(true,manifest.get("supported")); assertEquals(22L,manifest.get("nativeRows"));
        hashes(root,manifest.get("inputHashes"),Set.of("t/fixtures/compiler/OriginalTcsetattrAudit.hs","t/fixtures/compiler/OriginalTcsetattrNative.hs","t/haskell-fixtures/OriginalTcsetattrFixtures.hs"));
        var artifacts = new HashSet<>(Set.of(prefix + "/oracle.json",prefix + "/native/oracle")); for (var stage : List.of("pre","post")) artifacts.add(prefix + "/" + stage + "/originalTcsetattr.audit.json"); hashes(root,manifest.get("artifactHashes"),artifacts,prefix + "/");
    }
    @Test void originalPrivatePtyChangesMatchNativeBeforeAndAfterCompilation() throws Exception {
        var recorded = json(prefix + "/oracle.json"); assertEquals((long) size(),recorded.get("size")); var rows = (List<List<Object>>) recorded.get("rows"); assertEquals(22,rows.size()); var abi = StdioHostAbi.load();
        for (var row : rows) {
            var nativeImage = (List<Long>) row.get(3); assertEquals(2 * (size() + 16) + 2,nativeImage.size());
            for (int start = 2; start < nativeImage.size(); start += size() + 16) for (int i = 0; i < 8; i++) { assertEquals(77L,nativeImage.get(start + i)); assertEquals(77L,nativeImage.get(start + size() + 8 + i)); }
            long kind = (Long) row.get(0), action = (Long) row.get(1); assertEquals(kind == 0 && action >= 0 && action <= 2 ? 0L : -1L,nativeImage.get(0));
            assertEquals(kind == 2 ? abi.error(4) : kind == 1 ? abi.notTerminal() : action < 0 || action > 2 ? abi.error(5) : 0L,nativeImage.get(1));
        }
        try (var oracle = new Oracle()) {
            for (var stage : List.of("pre","post")) {
                var audit = json(prefix + "/" + stage + "/originalTcsetattr.audit.json"); assertEquals(true,audit.get("accepted")); assertEquals(List.of(),audit.get("issues")); assertEquals(List.of(),audit.get("missingGlobals")); var audited = new ArrayList<Object>(); for (var call : (List<Map<?,?>>) audit.get("foreignCalls")) audited.add(call.get("symbol")); assertEquals(List.of(OriginalStdioOp.TCSETATTR.getSymbol()),audited);
                var source = module(stage); assertEquals(OriginalStdioOp.TCSETATTR,validate(single(foreignCalls(source),ignored -> true)));
                for (var backend : List.of("ast","bytecode")) try (var context = context()) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var io = Language.currentState().getStdio(); long terminal = io.open(address(oracle.path),2L | 0x100L,0); assertTrue(terminal >= 3);
                        var plain = directory.resolve("plain-" + stage + "-" + backend); Files.writeString(plain,"data"); long regular = io.open(address(plain.toString()),0,0); assertTrue(regular >= 3);
                        var program = load(language,backend,with(CoreModules.reachable(source, entryId("originalTcsetattr"),true),"instrument",true)); var entry = program.entryTarget(entryId("originalTcsetattr"));
                        class Exercise { void run(boolean compiled) throws Exception {
                            for (var row : rows) {
                                long kind = (Long) row.get(0), action = (Long) row.get(1), echo = (Long) row.get(2); var expected = oracle.observe(kind == 0 ? action : -1L,echo); var supplied = bytes(expected.subList(2,2 + size() + 16)); var original = supplied.clone(); long descriptor = kind == 0 ? terminal : kind == 1 ? regular : -1L;
                                try {
                                    assertEquals(-1L,io.close(-1)); long sticky = io.errno(); long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); var result = Calls.target(entry,new Object[]{0L,descriptor,action,ManagedAddress.fromByteArray(supplied).plus(8)});
                                    var recordedStatus = (List<Long>) row.get(3); assertEquals(recordedStatus.get(0),result); assertEquals(Objects.equals(result,0L) ? sticky : recordedStatus.get(1),io.errno());
                                    if (compiled) assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before); assertArrayEquals(original,supplied,"Const input and canaries must remain unchanged");
                                    var observed = image(90); assertEquals(0L,io.tcgetattr(terminal,ManagedAddress.fromByteArray(observed).plus(8))); assertArrayEquals(bytes(expected.subList(expected.size() - size() - 16,expected.size())),observed);
                                    assertEquals(0,language.getHandoffState().get().getArguments().getDepth()); assertEquals(0,language.getHandoffState().get().getResults().getDepth());
                                } finally { oracle.reset(); }
                            }
                            // Const struct termios also accepts immutable byte storage.
                            var expected = oracle.observe(0,0); var input = expected.subList(2,2 + size() + 16); var text = new StringBuilder(); for (long value : input) { var hex = Long.toString(value,16); if (hex.length() < 2) text.append('0'); text.append(hex); }
                            var immutable = ManagedAddress.fromHex(text.toString()).plus(8); try { assertEquals(0L,Calls.target(entry,new Object[]{0L,terminal,0L,immutable})); } finally { oracle.reset(); }
                        }}
                        var exercise = new Exercise(); exercise.run(false); var cls = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"); for (var target : targets(entry)) { cls.getMethod("compile",boolean.class).invoke(target,true); assertEquals(true,cls.getMethod("isValidLastTier").invoke(target)); }
                        var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode",cls).invoke(runtime,entry); exercise.run(true); assertEquals(0L,program.diagnostics().get("unsupportedTraps"));
                    } finally { context.leave(); }
                }
            }
        }
    }
    @Test void stateCIntsAndCompleteConstImageAreValidatedBeforeEffects() throws Exception {
        var source = module("pre"); var original = single(foreignCalls(source),ignored -> true);
        for (var backend : List.of("ast","bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var target = load(language,backend,rawModule(original,source)).entryTarget("entry"); var io = Language.currentState().getStdio(); var bytes = image(90); var valid = ManagedAddress.fromByteArray(bytes).plus(8);
                class Call { Object invoke(long fd,long action,ManagedAddress address,Object state) { return Calls.target(target,new Object[]{0L,fd,action,address,state}); }}
                var call = new Call(); assertEquals(-1L,io.close(-1)); long sticky = io.errno(); assertThrows(RuntimeFault.class,() -> call.invoke(-1,0,valid,9L));
                for (long bad : new long[]{Long.MIN_VALUE,2147483648L}) { assertThrows(RuntimeFault.class,() -> call.invoke(bad,0,valid,thc.runtime.Unit.INSTANCE)); assertThrows(RuntimeFault.class,() -> call.invoke(-1,bad,valid,thc.runtime.Unit.INSTANCE)); }
                var pointerCell = ManagedAddress.fromAllocation(PinnedMemory.allocate(size(),8)); pointerCell.writeAddressElementIndex(0,valid);
                for (var bad : List.of(ManagedAddress.nullAddress(),valid.plus(9),pointerCell,ManagedAddress.fromByteArray(new byte[size() - 1]))) assertThrows(RuntimeFault.class,() -> call.invoke(-1,0,bad,thc.runtime.Unit.INSTANCE));
                assertArrayEquals(image(90),bytes); assertEquals(sticky,io.errno()); assertSame(valid,pointerCell.readAddressElementIndex(0));
                for (int i = 0; i <= 3; i++) { int index = i; assertThrows(RuntimeFault.class,() -> load(language,backend,rawModule(original,source,index))); }
                for (var edit : List.of(list("convention","ccall"),list("safety","safe"),list("arity",3L))) { var changed = (List<Object>) Json.parse(Json.stringify(original)); ((Map<String,Object>) ((Map<?,?>) changed.get(6)).get("foreignCall")).put((String) edit.get(0),edit.get(1)); assertThrows(RuntimeFault.class,() -> validate(changed)); }
            } finally { context.leave(); }
        }
    }
    @Test void writesFollowTheOriginalLeaseAcrossAliasesReuseAndContextDisposal() throws Exception {
        try (var oracle = new Oracle()) {
            var first = context(); var second = context(); OpenedNativeFile resource;
            try {
                first.initialize("thc"); first.enter();
                try {
                    var io = Language.currentState().getStdio(); long fd = io.open(address(oracle.path),2L | 0x100L,0); assertTrue(fd >= 3); long alias = io.duplicate(fd); assertTrue(alias >= 3); assertEquals(0L,io.close(fd));
                    var file = directory.resolve("reused"); Files.writeString(file,"data"); assertEquals(fd,io.open(address(file.toString()),0,0)); var expected = oracle.observe(0,0); var bytes = bytes(expected.subList(2,2 + size() + 16)); var original = bytes.clone(); var input = ManagedAddress.fromByteArray(bytes).plus(8);
                    try {
                        assertEquals(-1L,io.tcsetattr(fd,0,input)); assertEquals(StdioHostAbi.load().notTerminal(),io.errno()); assertEquals(0L,io.tcsetattr(alias,0,input)); var observed = image(90); assertEquals(0L,io.tcgetattr(alias,ManagedAddress.fromByteArray(observed).plus(8)));
                        assertArrayEquals(bytes(expected.subList(expected.size() - size() - 16,expected.size())),observed); assertArrayEquals(original,bytes);
                    } finally { oracle.reset(); }
                    resource = NativeFileProvider.current().openRaw((oracle.path + "\0").getBytes(StandardCharsets.UTF_8),2 | 0x100,0);
                } finally { first.leave(); }
                var originalResource = resource; second.initialize("thc"); second.enter(); try { assertThrows(RuntimeFault.class,() -> originalResource.writeTermios(0,new byte[size()])); } finally { second.leave(); }
                first.initialize("thc"); first.enter();
                try { originalResource.close(); assertThrows(ClosedChannelException.class,() -> originalResource.writeTermios(0,new byte[size()])); resource = NativeFileProvider.current().openRaw((oracle.path + "\0").getBytes(StandardCharsets.UTF_8),2 | 0x100,0); } finally { first.leave(); }
                first.close(); assertFalse(resource.isOpen()); resource.close();
            } finally { first.close(); second.close(); }
        }
    }
}
