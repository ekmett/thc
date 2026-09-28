// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SuppressWarnings("unchecked")
public class RtsDiagnosticsTest {
    private Context context(OutputStream output) { return context(output, false, new ByteArrayOutputStream()); }
    private Context context(OutputStream output, boolean nativeAccess) { return context(output, nativeAccess, new ByteArrayOutputStream()); }
    private Context context(OutputStream output, boolean nativeAccess, OutputStream stdout) { return Context.newBuilder("thc").err(output).out(stdout)
        .allowNativeAccess(nativeAccess).allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build(); }
    @FunctionalInterface private interface Action { void run(Language language) throws Exception; }
    private void entered(Context context, Action action) throws Exception {
        context.initialize("thc"); context.enter();
        try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
    }
    private Map<String, Object> scalar(String rep) { return scalar(rep, true); }
    private Map<String, Object> scalar(String rep, boolean evaluated) {
        return Map.of("kind", switch (rep) { case null -> "void"; case "AddrRep" -> "address"; case "BoxedRep (Just Unlifted)" -> "object"; default -> "closure"; },
            "primReps", rep == null ? List.of() : List.of(rep), "evaluated", evaluated);
    }
    private Map<String, Object> tuple(boolean evaluated) { return Map.of("kind", "unknown", "primReps", List.of(), "aggregate", "unboxed-tuple", "components", List.of(scalar(null)), "evaluated", evaluated); }
    /** ABI controls are synthetic; the separately compiled native oracle calls the original symbols. */
    private List<Object> call(String operation) {
        List<String> reps = switch (operation) {
            case "reportStackOverflow" -> Arrays.asList("BoxedRep (Just Unlifted)", null);
            case "reportHeapOverflow" -> Arrays.asList((String) null); default -> Arrays.asList("AddrRep", "AddrRep", null);
        };
        var proofs = new ArrayList<Map<String, Object>>(); var arguments = new ArrayList<List<Object>>(); var flags = new ArrayList<Boolean>();
        for (int i = 0; i < reps.size(); i++) {
            proofs.add(scalar(reps.get(i), false)); arguments.add(List.of("var", "p" + i, Map.of("rep", scalar(reps.get(i))))); flags.add(false);
        }
        var descriptor = Map.of("schema", 1L, "target", Map.of("kind", "static", "symbol", operation, "unit", "ghc-internal", "isFunction", true),
            "convention", "ccall", "safety", "unsafe", "arity", (long) reps.size(), "suppliedArity", (long) reps.size(), "argumentReps", proofs, "resultRep", tuple(false));
        return List.of("app", List.of("var", "foreign", Map.of("rep", scalar("BoxedRep (Just Lifted)"))), arguments, flags, false, false, Map.of("rep", tuple(true), "foreignCall", descriptor));
    }
    private ExecutableProgram program(Language language, String backend, List<Object> call) {
        return program(language, backend, OriginalStdioChecks.rawModule(call, Map.of("sourceFiles", List.of(), "sourceSpans", List.of()), null));
    }
    private ExecutableProgram program(Language language, String backend, Map<String, Object> module) { return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module); }
    private ManagedAddress format(String operation) { return cstring((operation.equals("debugBelch2") ? "%s\n" : "%s").getBytes(StandardCharsets.UTF_8)); }
    private byte[] bytes(List<Long> values) { var result = new byte[values.size()]; for (int i = 0; i < result.length; i++) result[i] = values.get(i).byteValue(); return result; }
    private ManagedAddress cstring(byte[] bytes) { return ManagedAddress.fromByteArray(Arrays.copyOf(bytes, bytes.length + 1)); }
    private Map<String, Object> fixture() throws Exception {
        var root = new File(System.getProperty("thc.projectRoot")); var prefix = "build/rts-diagnostics";
        var manifest = (Map<String, Object>) Json.parse(Files.readString(new File(root, prefix + "/manifest.json").toPath()));
        OriginalStdioChecks.hashes(root, manifest.get("inputHashes"), Set.of("compiler/test-fixtures/RtsDiagnosticsNative.hs", "test/haskell-fixtures/RtsDiagnosticFixtures.hs"), null);
        var cases = List.of("ascii", "empty", "bytes", "nul", "newline"); var nativeCases = new ArrayList<>(cases);
        for (var name : cases) nativeCases.add("debug-" + name); nativeCases.addAll(List.of("trace-nul", "stack", "heap"));
        var required = new LinkedHashSet<String>(); required.add(prefix + "/oracle.json");
        for (var name : nativeCases) { required.add(prefix + "/logs/" + name + ".stderr"); required.add(prefix + "/logs/" + name + ".stdout"); }
        OriginalStdioChecks.hashes(root, manifest.get("artifactHashes"), required, prefix + "/");
        var result = (Map<String, Object>) Json.parse(Files.readString(new File(root, prefix + "/oracle.json").toPath()));
        assertEquals(true, result.get("nativeCallsReturned")); assertEquals(true, result.get("nativeStdoutOnlyHarnessMarkers")); assertEquals(true, result.get("overflowSizesAreBackendSpecific")); return result;
    }
    @Test public void nativeCStringBytesMatchBothBackendsAndFirstInstalledCallsReturn() throws Exception {
        var fixture = fixture(); var rows = (List<Map<String, Object>>) fixture.get("cases");
        var nativeOutput = new LinkedHashMap<Object, Map<String, Object>>();
        for (var row : (List<Map<String, Object>>) fixture.get("nativeOutput")) nativeOutput.put(row.get("name"), row);
        var names = new ArrayList<Object>(); for (var row : rows) names.add(row.get("name")); assertEquals(List.of("ascii", "empty", "bytes", "nul", "newline"), names);
        for (var backend : List.of("ast", "bytecode")) {
            var output = new ByteArrayOutputStream(); var stdout = new ByteArrayOutputStream();
            try (var context = context(output, false, stdout)) { entered(context, language -> {
                var threads = Language.currentState().getThreads(); threads.enterCurrent();
                try {
                    for (var operation : List.of("errorBelch2", "debugBelch2", "reportStackOverflow", "reportHeapOverflow")) {
                        var program = program(language, backend, call(operation)); var target = program.entryTarget("entry");
                        class Runner { void exercise(boolean compiled) throws Exception {
                            List<Map<String, Object>> selected = operation.endsWith("Belch2") ? rows : List.of(Map.of());
                            for (var row : selected) {
                                output.reset();
                                Object[] arguments = switch (operation) {
                                    case "errorBelch2", "debugBelch2" -> new Object[]{0L, format(operation), cstring(bytes((List<Long>) row.get("bytes"))).plus((Long) row.get("offset")), thc.runtime.Unit.INSTANCE};
                                    case "reportStackOverflow" -> new Object[]{0L, threads.currentIdentity(), thc.runtime.Unit.INSTANCE};
                                    default -> new Object[]{0L, thc.runtime.Unit.INSTANCE};
                                };
                                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); assertEquals(0L, Calls.target(target, arguments));
                                byte[] expected;
                                switch (operation) {
                                    case "errorBelch2" -> { var message = bytes((List<Long>) row.get("message")); expected = Arrays.copyOf(message, message.length + 1); expected[message.length] = 10; }
                                    case "debugBelch2" -> expected = bytes((List<Long>) nativeOutput.get("debug-" + row.get("name")).get("stderr"));
                                    case "reportStackOverflow" -> expected = ("Stack space overflow (THC guest Java thread " + Thread.currentThread().threadId() + "; JVM stack limit unavailable).\n").getBytes(StandardCharsets.UTF_8);
                                    default -> expected = ("Heap exhausted; JVM maximum heap size is " + Runtime.getRuntime().maxMemory() + " bytes.\n").getBytes(StandardCharsets.UTF_8);
                                }
                                assertArrayEquals(expected, output.toByteArray(), backend + "/" + operation + "/" + row.get("name")); assertEquals(0, stdout.size(), "Native diagnostics write no stdout bytes");
                                if (compiled) { assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue()); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
                                var handoff = language.getHandoffState().get(); assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
                                assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
                            }
                        } }
                        var runner = new Runner(); runner.exercise(false);
                        target.getClass().getMethod("compile", boolean.class).invoke(target, true); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); runner.exercise(true);
                        if (operation.equals("debugBelch2")) {
                            // Native traceIO filters NULs in Haskell, then makes
                            // these two leaf calls. The leaf itself only truncates.
                            output.reset(); long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            for (var message : List.of("leftright", "WARNING: previous trace message had null bytes")) assertEquals(0L, Calls.target(target, new Object[]{0L, format(operation), cstring(message.getBytes(StandardCharsets.UTF_8)), thc.runtime.Unit.INSTANCE}));
                            assertArrayEquals(bytes((List<Long>) nativeOutput.get("trace-nul").get("stderr")), output.toByteArray());
                            assertEquals(before + 2, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                            assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); assertEquals(0, stdout.size());
                        }
                    }
                } finally { threads.leaveCurrent(); }
            }); }
        }
    }
    private record Mutation(String key, Object value) {}
    @Test public void malformedDescriptorsAndInvalidCarriersRejectBeforeOutput() throws Exception {
        for (var backend : List.of("ast", "bytecode")) {
            var output = new ByteArrayOutputStream(); var stdout = new ByteArrayOutputStream();
            try (var context = context(output, false, stdout)) { entered(context, language -> {
                for (var operation : List.of("errorBelch2", "debugBelch2")) {
                    var target = program(language, backend, call(operation)).entryTarget("entry"); var text = cstring("ok".getBytes(StandardCharsets.UTF_8));
                    var validFormat = format(operation); var otherFormat = format(operation.equals("debugBelch2") ? "errorBelch2" : "debugBelch2");
                    for (var args : List.of(new Object[]{0L, validFormat, text, 1L}, new Object[]{0L, 1L, text, thc.runtime.Unit.INSTANCE},
                            new Object[]{0L, validFormat, 1L, thc.runtime.Unit.INSTANCE}, new Object[]{0L, otherFormat, text, thc.runtime.Unit.INSTANCE},
                            new Object[]{0L, cstring("%d".getBytes(StandardCharsets.UTF_8)), text, thc.runtime.Unit.INSTANCE},
                            new Object[]{0L, ManagedAddress.fromByteArray(new byte[]{37, 115}), text, thc.runtime.Unit.INSTANCE},
                            new Object[]{0L, validFormat, ManagedAddress.fromByteArray(new byte[]{1, 2}), thc.runtime.Unit.INSTANCE},
                            new Object[]{0L, ManagedAddress.nullAddress(), text, thc.runtime.Unit.INSTANCE}, new Object[]{0L, validFormat, ManagedAddress.nullAddress(), thc.runtime.Unit.INSTANCE})) {
                        assertThrows(RuntimeFault.class, () -> Calls.target(target, args)); assertEquals(0, output.size()); assertEquals(0, stdout.size());
                    }
                    for (var mutation : List.of(new Mutation("safety", "safe"), new Mutation("arity", 4L), new Mutation("suppliedArity", 2L), new Mutation("convention", "capi"), new Mutation("schema", 2L))) {
                        var broken = (List<Object>) Json.parse(Json.stringify(call(operation)));
                        ((Map<String, Object>) ((Map<String, Object>) broken.get(6)).get("foreignCall")).put(mutation.key(), mutation.value());
                        assertThrows(RuntimeFault.class, () -> program(language, backend, broken));
                    }
                    for (var unit : List.of("main", "ghc-internal-9.1401.0-inplace")) {
                        var broken = (List<Object>) Json.parse(Json.stringify(call(operation))); var descriptor = (Map<?, ?>) ((Map<?, ?>) broken.get(6)).get("foreignCall");
                        ((Map<String, Object>) descriptor.get("target")).put("unit", unit); assertThrows(RuntimeFault.class, () -> program(language, backend, broken));
                    }
                    var source = Map.<String, Object>of("sourceFiles", List.of(), "sourceSpans", List.of());
                    assertThrows(RuntimeFault.class, () -> program(language, backend, OriginalStdioChecks.rawModule(call(operation), source, 2)));
                    for (var mutation : List.of("state-producer", "bound-head", "singleton-state-result")) {
                        var module = (Map<String, Object>) Json.parse(Json.stringify(OriginalStdioChecks.rawModule(call(operation), source, null)));
                        var bindings = (List<Map<String, Object>>) module.get("bindings"); if (bindings.size() != 1) throw new IllegalArgumentException("Expected one binding");
                        var body = (List<?>) ((List<?>) bindings.getFirst().get("expr")).get(2); var call = (List<Object>) body.get(1);
                        switch (mutation) {
                            case "state-producer" -> ((List<Object>) call.get(2)).set(2, List.of("lit", "int", "0", Map.of("rep", scalar(null))));
                            case "bound-head" -> ((List<Object>) call.get(1)).set(1, "p0");
                            default -> ((Map<String, Object>) ((Map<?, ?>) call.get(6)).get("foreignCall")).put("resultRep", scalar(null, false));
                        }
                        assertThrows(RuntimeFault.class, () -> program(language, backend, module));
                    }
                }
                var stack = program(language, backend, call("reportStackOverflow")).entryTarget("entry");
                assertThrows(RuntimeFault.class, () -> Calls.target(stack, new Object[]{0L, new Object(), thc.runtime.Unit.INSTANCE})); assertEquals(0, output.size()); assertEquals(0, stdout.size());
            }); }
        }
    }
    @Test public void foreignThreadIdentityRejectsAndStreamErrorsReturn() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var first = context(output)) { entered(first, _ -> {
            var state = Language.currentState(); state.getThreads().enterCurrent();
            try {
                var thread = state.getThreads().currentIdentity();
                try (var second = context(output)) { entered(second, _ -> { assertThrows(RuntimeFault.class, () -> RtsDiagnostics.report(null, RtsDiagnosticOp.STACK, thread, null)); assertEquals(0, output.size()); }); }
            } finally { state.getThreads().leaveCurrent(); }
        }); }
        var broken = new OutputStream() { @Override public void write(int value) throws IOException { throw new IOException("closed"); } };
        try (var context = context(broken)) { entered(context, _ -> {
            assertDoesNotThrow(() -> RtsDiagnostics.report(null, RtsDiagnosticOp.HEAP, null, null));
            assertDoesNotThrow(() -> RtsDiagnostics.report(null, RtsDiagnosticOp.DEBUG, format("debugBelch2"), cstring("message".getBytes(StandardCharsets.UTF_8))));
        }); }
    }
    @Test public void ownedNativeCStringAliasesRejectCrossContextAndExpiredStorage() throws Exception {
        assumeTrue(System.getProperty("os.name").equals("Linux") && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")), "Owned native malloc currently has the verified Linux x86_64 ABI");
        var output = new ByteArrayOutputStream();
        try (var first = context(output, true)) { entered(first, _ -> {
            var state = Language.currentState(); var address = state.getNativeAllocations().malloc(4); var alias = address.plus(1);
            try {
                alias.writeWord8(0, 120); alias.writeWord8(1, 0);
                for (var operation : List.of(RtsDiagnosticOp.ERROR, RtsDiagnosticOp.DEBUG)) {
                    RtsDiagnostics.report(null, operation, format(operation.getSymbol()), alias);
                    assertArrayEquals(new byte[]{120, 10}, output.toByteArray()); output.reset();
                    try (var second = context(output)) { entered(second, _ -> { assertThrows(RuntimeFault.class, () -> RtsDiagnostics.report(null, operation, format(operation.getSymbol()), alias)); assertEquals(0, output.size()); }); }
                }
            } finally { state.getNativeAllocations().free(address); }
            for (var operation : List.of(RtsDiagnosticOp.ERROR, RtsDiagnosticOp.DEBUG)) assertThrows(RuntimeFault.class, () -> RtsDiagnostics.report(null, operation, format(operation.getSymbol()), alias));
            assertEquals(0, output.size());
        }); }
    }
}
