// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedTruffleRuntime;
import com.oracle.truffle.runtime.OptimizedTruffleRuntimeListener;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import thc.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarValueTestSupport.*;

class ScalarExceptionResultsNativeTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final Path directory = root.resolve("build/scalar-exception-results");
    private static final List<String> ENTRIES = entries();
    private static List<String> entries() {
        var entries = new ArrayList<String>();
        for (var prefix : list("normal", "throw", "interrupt")) for (var suffix : list("Int", "Word", "Addr")) entries.add(prefix + suffix);
        return entries;
    }
    static Stream<Arguments> cases() {
        var cases = new ArrayList<Arguments>();
        for (var stage : list("pre", "post")) for (var backend : list("ast", "bytecode")) for (var entry : ENTRIES)
            for (boolean async : entry.startsWith("interrupt") ? list(true) : list(false, true)) cases.add(Arguments.of(stage, backend, entry, async));
        return cases.stream();
    }
    private Map<String, Object> module(String stage) throws Exception {
        return object(Json.parse(Files.readString(directory.resolve(stage + "/core/ScalarExceptionResultsAudit.json"))));
    }
    private record Row(long input, long output) {}
    private Map<String, List<Row>> oracle() throws Exception {
        var manifest = object(Json.parse(Files.readString(directory.resolve("manifest.json"))));
        assertEquals("9.14.1", manifest.get("ghc")); assertEquals(ENTRIES, manifest.get("entries"));
        for (var kind : list("inputHashes", "artifactHashes")) for (var entry : object(manifest.get(kind)).entrySet()) {
            var file = root.resolve(entry.getKey()); assertTrue(file.toFile().getCanonicalFile().toPath().startsWith(root.toFile().getCanonicalFile().toPath()));
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
            assertEquals(entry.getValue(), hash, kind + "/" + entry.getKey());
        }
        var lines = Files.readAllLines(root.resolve((String) manifest.get("oracle"))); assertEquals(36, lines.size());
        var groups = new LinkedHashMap<String, List<Row>>();
        for (var line : lines) {
            var fields = line.split("\t", -1); groups.computeIfAbsent(fields[0], ignored -> new ArrayList<>()).add(new Row(Long.parseLong(fields[1]), Long.parseLong(fields[2])));
        }
        for (var group : groups.entrySet()) {
            var name = group.getKey(); long offset = name.equals("normalInt") ? 59L : name.startsWith("normal") ? 57L : name.startsWith("throw") ? 34L : 135L;
            assertEquals(list(-129L, -17L, 0L, 23L), group.getValue().stream().map(Row::input).toList());
            for (var row : group.getValue()) assertEquals(row.input() + offset, row.output(), name);
        }
        return groups;
    }
    private List<List<Object>> calls(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof Map<?, ?> map) for (var item : map.values()) result.addAll(calls(item));
        else if (value instanceof List<?> values) {
            if (values.size() > 1 && "app".equals(values.getFirst()) && values.get(1) instanceof List<?> head && !head.isEmpty() && "prim".equals(head.getFirst())) result.add(expression(values));
            for (var item : values) result.addAll(calls(item));
        }
        return result;
    }
    private CoreRepresentation copy(CoreRepresentation source, List<String> reps, List<CoreRepresentation> components) {
        return source.copy(source.getKind(), source.getEvaluated(), source.getPresent(), reps, components, source.getVector(), source.getAlternatives(), source.getTagSlot(), source.getAlternativeSlots());
    }
    @Test void genuineScalarProofsAndMalformedBoundaries() throws Exception {
        oracle();
        for (var stage : list("pre", "post")) for (var entry : ENTRIES) {
            var linked = CoreModules.reachable(module(stage), entry, true);
            var primitives = calls(linked).stream().filter(call -> Set.of("catch#", "raiseIO#", "maskAsyncExceptions#", "maskUninterruptible#", "unmaskAsyncExceptions#").contains(expression(call.get(1)).get(1))).toList();
            assertFalse(primitives.isEmpty(), stage + "/" + entry + " retains original exception primops");
            var expected = entry.endsWith("Word") ? "WordRep" : entry.endsWith("Addr") ? "AddrRep" : "IntRep";
            for (var call : primitives) {
                var name = (String) expression(call.get(1)).get(1); var arguments = new ArrayList<CoreRepresentation>();
                for (var argument : expression(call.get(2))) arguments.add(CoreRepresentations.expression(expression(argument)));
                var flags = expression(call.get(3)); var result = CoreRepresentations.expression(call);
                assertEquals(list(expected), result.getPrimReps(), stage + "/" + entry + "/" + name); CoreSynchronousExceptions.validate(name, arguments, flags, result);
                var components = Objects.requireNonNull(result.getComponents()); var extra = new ArrayList<>(components); extra.add(components.getLast());
                for (var broken : list(copy(result, result.getPrimReps(), components.reversed()), copy(result, result.getPrimReps(), extra), copy(result, list("DoubleRep"), components)))
                    assertThrows(RuntimeFault.class, () -> CoreSynchronousExceptions.validate(name, arguments, flags, broken), stage + "/" + entry + "/" + name + " malformed result");
                // Scalar carrier/repr disagreements are checked by the generic
                // proof parser, before the primop consumes the lowered layout.
                assertThrows(RuntimeFault.class, () -> CoreRepresentations.parse(map("kind", "float", "evaluated", true, "primReps", components.get(1).getPrimReps())));
                CoreSynchronousExceptions.validate(name, arguments, flags, copy(result, list("DoubleRep"), list(components.get(0),
                    new CoreRepresentation(CoreKind.DOUBLE, true, true, list("DoubleRep"), null, null, null, null, null))));
                var badFlags = new ArrayList<>(flags.subList(0, flags.size() - 1)); badFlags.add(true);
                assertThrows(RuntimeFault.class, () -> CoreSynchronousExceptions.validate(name, arguments, badFlags, result));
            }
            var audit = object(Json.parse(Files.readString(directory.resolve(stage + "/" + entry + "-audit.json"))));
            assertEquals(true, audit.get("accepted"), stage + "/" + entry); assertEquals(list(), audit.get("issues"));
        }
    }
    private void valid(RootCallTarget target, String label) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label); }
    @ParameterizedTest(name = "{0}/{1}/{2}/async={3}") @MethodSource("cases")
    void nativeValuesAndMaskRestorationSurviveTheFirstInstalledCall(String stage, String backend, String entry, boolean async) throws Exception {
        var selected = Objects.requireNonNull(oracle().get(entry));
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).allowCreateThread(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.Splitting", "false")
            .option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var linked = with(CoreModules.reachable(module(stage), entry, true), "instrument", true);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, linked, async) : new BytecodeProgram(language, linked, async);
                var target = program.entryTarget(entry); var function = context.asValue(new EntryValue(program, entry, 1)); var label = stage + "/" + backend + "/" + entry + "/async=" + async;
                CheckedConsumer<Row> check = row -> {
                    assertEquals(row.output(), function.execute(row.input()).asLong(), label); assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(target.getRootNode()), label);
                    assertEquals(0, language.getHandoffState().get().getArguments().getDepth(), label); assertEquals(0, language.getHandoffState().get().getResults().getDepth(), label);
                };
                for (var row : selected) check.accept(row); assertTrue(function.invokeMember("compile").asBoolean(), label); valid(target, label + " installed");
                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); check.accept(selected.getLast()); // No settling invocation after installation.
                assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before, label + " first installed call entered guest code");
                assertSame(target, program.entryTarget(entry), label + " same guest identity"); valid(target, label + " retained after first installed call");
                assertEquals(0L, program.diagnostics().get("unsupportedTraps"), label); assertEquals(0L, program.diagnostics().get("blackholes"), label);
            } finally { context.leave(); }
        }
    }
    @Test void bytecodeAsyncNestedMasksCompileWithoutDiagnosticRetry() throws Exception {
        var failures = new CopyOnWriteArrayList<String>(); var runtime = (OptimizedTruffleRuntime) Truffle.getRuntime();
        var listener = new OptimizedTruffleRuntimeListener() {
            @Override public void onCompilationFailed(OptimizedCallTarget target, String reason, boolean bailout, boolean permanentBailout, int tier, Supplier<String> lazyStackTrace) {
                failures.add(target + ": " + reason);
            }
        };
        runtime.addListener(listener);
        try {
            nativeValuesAndMaskRestorationSurviveTheFirstInstalledCall("pre", "bytecode", "normalInt", true);
            assertEquals(list(), new ArrayList<>(failures), "Successful installation must not conceal a failed compilation followed by a diagnostic retry");
        } finally { runtime.removeListener(listener); }
    }
}
