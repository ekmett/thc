// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.OriginalStdioChecks.*;

/** Real GHC c_read/c_safe_read wrappers, native bytes, and compiled typed paths. */
@SuppressWarnings("unchecked")
class OriginalStdioReadTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/original-stdio-read");
    private final List<String> names = List.of("originalRead", "originalSafeRead", "originalReadErrno", "originalSafeReadErrno");
    private final byte[] payload = {0,1,127,-128,-1,65,-61,-87};
    private final List<List<Long>> cases = List.of(List.of(-1L,0L,0L,0L), List.of(-1L,3L,4L,0L), List.of(1L,3L,4L,0L), List.of(2L,3L,0L,0L),
        List.of(0L,16L,0L,0L), List.of(0L,2L,4L,0L), List.of(0L,1L,12L,0L), List.of(0L,0L,5L,4L), List.of(0L,5L,4L,8L), List.of(0L,3L,6L,7L));
    private Map<String, Object> cbd(File path) throws Exception { return thc.CoreCbdFixtures.read(path.toPath()); }
    private static String entryId(String name) { return "main:OriginalStdioReadAudit." + name; }
    private Object json(File path) throws Exception { return Json.parse(Files.readString(path.toPath())); }
    private record Expected(long result, byte[] buffer) {}
    private Expected expected(String name, List<Long> args) throws IOException {
        long fd = args.get(0), offset = args.get(1), count = args.get(2), start = args.get(3);
        byte[] buffer = new byte[16]; Arrays.fill(buffer, (byte) 0xa5);
        int copied = fd == 0 ? (int) Math.min(count, payload.length - start) : 0;
        if (copied > 0) System.arraycopy(payload, (int) start, buffer, (int) offset, copied);
        long result = fd != 0 ? -1 : copied;
        return new Expected(name.endsWith("Errno") ? (result < 0 ? StdioHostAbi.load().error(4) : -result - 2) : result, buffer);
    }
    private List<Map<String,Object>> rows() throws Exception {
        var raw = (List<Map<String,Object>>) json(new File(directory, "oracle.json")); assertEquals(names.size() * cases.size(), raw.size());
        int index = 0;
        for (var name : names) for (var args : cases) {
            var row = raw.get(index); assertEquals(name, row.get("entry")); assertEquals(args, row.get("arguments")); var expected = expected(name, args);
            assertEquals(expected.result(), row.get("result"), name + "/" + args); assertEquals(hex(expected.buffer()), row.get("bufferHex"), name + "/" + args + " buffer");
            assertEquals(expected.result() + "\n" + hex(expected.buffer()) + "\n", Files.readString(new File(directory, "results/" + index + ".txt").toPath())); index++;
        }
        return raw;
    }
    private void valid(RootCallTarget target, String label) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label); }
    @Test void originalReadMatchesNativeWithInlining() throws Exception { nativeChecks(true); }
    @Test void originalReadMatchesNativeAcrossResidualCalls() throws Exception { nativeChecks(false); }
    private void nativeChecks(boolean inlining) throws Exception {
        var manifest = (Map<String,Object>) json(new File(directory, "manifest.json"));
        assertEquals(1L, manifest.get("schema")); assertEquals("9.14.1", manifest.get("ghc")); assertEquals(names, manifest.get("entries")); assertEquals(40L, manifest.get("nativeRows"));
        assertEquals(hex(payload), manifest.get("payloadHex"));
        hashes(root, manifest.get("inputHashes"), Set.of("t/fixtures/compiler/OriginalStdioReadAudit.hs", "t/fixtures/compiler/OriginalStdioReadNative.hs", "t/haskell-fixtures/OriginalStdioFixtures.hs", "t/haskell-fixtures/Main.hs", "bin/core-capabilities.json", "thc.cabal"));
        hashes(root, manifest.get("artifactHashes"), Set.of("build/original-stdio-read/input.bin", "build/original-stdio-read/oracle.json"), "build/original-stdio-read/");
        assertArrayEquals(payload, Files.readAllBytes(new File(directory, "input.bin").toPath()));
        var oracle = new LinkedHashMap<String,List<Map<String,Object>>>(); for (var row : rows()) oracle.computeIfAbsent((String) row.get("entry"), ignored -> new ArrayList<>()).add(row);
        assertEquals(new HashSet<>(names), oracle.keySet());
        var stages = (Map<String,List<String>>) manifest.get("stages"); var audits = (Map<String,String>) manifest.get("audits"); assertEquals(Set.of("pre", "post"), stages.keySet());
        for (var stageEntry : stages.entrySet()) {
            var stage = stageEntry.getKey(); var paths = stageEntry.getValue(); var expectedPaths = new ArrayList<String>();
            for (var part : List.of("OriginalStdioReadAudit", "THC.InterfaceClosure")) expectedPaths.add("build/original-stdio-read/" + stage + "/core/" + part + ".cbd"); assertEquals(expectedPaths, paths);
            var modules = new ArrayList<Map<String,Object>>(); for (var path : paths) modules.add((Map<String,Object>) cbd(new File(root, path)));
            var module = CoreModules.merge(modules);
            var report = (Map<String,Object>) json(new File(root, audits.get(stage)));
            assertEquals(true, report.get("accepted")); assertEquals(List.of(), report.get("issues"));
            assertEquals(List.of(), report.get("missingGlobals"));
            for (var name : names) for (var backend : List.of("ast", "bytecode")) {
                var input = new ByteArrayInputStream(payload);
                try (var context = Context.newBuilder("thc").allowIO(IOAccess.NONE).in(input).allowExperimentalOptions(true)
                    .option("compiler.Inlining", Boolean.toString(inlining)).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
                    context.initialize("thc"); context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var linked = with(CoreModules.reachable(module, entryId(name)), "instrument", true);
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked); var entry = program.entryTarget(entryId(name));
                        var label = stage + "/" + backend + "/" + name + "/inlining=" + inlining;
                        class Check { void row(Map<String,Object> row) {
                            var args = (List<Long>) row.get("arguments"); long fd = args.get(0), offset = args.get(1), count = args.get(2), start = args.get(3);
                            input.reset(); assertEquals(start, input.skip(start)); byte[] buffer = new byte[16]; Arrays.fill(buffer, (byte) 0xa5); var address = ManagedAddress.fromByteArray(buffer);
                            assertEquals(row.get("result"), callScalarTestTarget(entry, new Object[]{0L,fd,address,offset,count}), label + "/" + row);
                            assertEquals(row.get("bufferHex"), hex(address.cbitsBacking()), label + " buffer/" + row);
                            assertEquals(0, language.getHandoffState().get().getArguments().getDepth()); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                        }}
                        var check = new Check(); var rows = oracle.get(name); for (var row : rows) check.row(row); var active = targets(entry);
                        for (var target : active) { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target, label + " installed"); }
                        for (var row : rows) {
                            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); check.row(row); long after = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            assertTrue(after > before, label + " entered compiled code"); for (var target : active) valid(target, label + " target remains valid");
                        }
                        assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                    } finally { context.leave(); }
                }
            }
        }
    }
    /** A provider that returns zero for a nonempty request must not impersonate EOF. */
    @Test void zeroProgressInputBecomesEioWithoutChangingDestination() throws Exception {
        var zero = new InputStream() { @Override public int read() { return 0; } @Override public int read(byte[] bytes, int offset, int length) { return 0; } };
        try (var context = Context.newBuilder("thc").allowIO(IOAccess.NONE).in(zero).build()) {
            context.initialize("thc"); context.enter();
            try {
                var stdio = Language.currentState().getStdio(); byte[] bytes = new byte[16]; Arrays.fill(bytes, (byte) 0xa5); var address = ManagedAddress.fromByteArray(bytes);
                assertEquals(-1L, stdio.read(0L,address,4L)); assertEquals(StdioHostAbi.load().error(6), stdio.errno());
                byte[] expected = new byte[16]; Arrays.fill(expected, (byte) 0xa5); assertArrayEquals(expected, address.cbitsBacking());
            } finally { context.leave(); }
        }
    }
}
