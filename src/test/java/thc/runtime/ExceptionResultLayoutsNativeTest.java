// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedTruffleRuntime;
import com.oracle.truffle.runtime.OptimizedTruffleRuntimeListener;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import thc.CoreModules;
import thc.EntryValue;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class ExceptionResultLayoutsNativeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/exception-result-layouts");
    private static final List<String> entries = List.of("int8Result", "word8Result", "int16Result", "word16Result", "int32Result", "word32Result",
        "int64Result", "word64Result", "floatResult", "doubleResult", "emptyResult", "nestedResult", "sumResult", "vectorResult", "unliftedResult", "unliftedPayloadResult");
    private static final List<String> stages = Set.of("aarch64", "arm64").contains(System.getProperty("os.arch")) ? List.of("pre") : List.of("pre", "post");
    public static Stream<Arguments> cases() {
        var result = Stream.<Arguments>builder();
        for (var stage : stages) for (var backend : List.of("ast", "bytecode")) for (var entry : entries) result.add(Arguments.of(stage, backend, entry));
        return result.build();
    }
    private Map<String, Object> module(String stage) throws Exception {
        return (Map<String, Object>) thc.CoreCbdFixtures.read(new File(directory, stage + "/core/ExceptionResultLayoutsAudit.cbd").toPath());
    }
    private record Row(long mode, long input, long expected) {}
    private Map<String, List<Row>> oracle() throws Exception {
        var manifest = (Map<String, Object>) Json.parse(Files.readString(new File(directory, "manifest.json").toPath()));
        assertEquals("9.14.1", manifest.get("ghc")); assertEquals(entries, manifest.get("entries"));
        assertEquals(stages, manifest.get("stages"), "Only stages actually emitted by the host GHC pipeline");
        assertEquals("native-boxed-effects-independent-layout-model", manifest.get("oracleKind"));
        for (var kind : List.of("inputHashes", "artifactHashes")) for (var item : ((Map<String, String>) manifest.get(kind)).entrySet()) {
            var file = new File(root, item.getKey()); assertTrue(file.getCanonicalFile().toPath().startsWith(root.getCanonicalFile().toPath()));
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath())));
            assertEquals(item.getValue(), hash, kind + "/" + item.getKey());
        }
        var lines = Files.readAllLines(new File(root, (String) manifest.get("oracle")).toPath()); assertEquals(141, lines.size());
        var grouped = new LinkedHashMap<String, List<Row>>();
        for (var line : lines) {
            var columns = line.split("\t", -1); grouped.computeIfAbsent(columns[0], _ -> new ArrayList<>())
                .add(new Row(Long.parseLong(columns[1]), Long.parseLong(columns[2]), Long.parseLong(columns[3])));
        }
        for (var item : grouped.entrySet()) {
            var name = item.getKey(); var values = item.getValue(); assertEquals(name.equals("unliftedPayloadResult") ? 6 : 9, values.size());
            for (var row : values) {
                long value = row.input() + (row.mode() == 0L ? 7 : 34);
                long transformed = switch (name) {
                    case "int8Result" -> (byte) value; case "word8Result" -> value & 255;
                    case "int16Result" -> (short) value; case "word16Result" -> value & 65535;
                    case "int32Result" -> (int) value; case "word32Result" -> value & 0xffffffffL;
                    case "emptyResult" -> 0; case "nestedResult" -> 2 * value + 1; case "vectorResult" -> 4 * value; default -> value;
                };
                assertEquals(transformed + 101, row.expected(), name + "/" + row);
            }
        }
        return grouped;
    }
    private List<List<Object>> calls(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof Map<?, ?> map) for (var child : map.values()) result.addAll(calls(child));
        else if (value instanceof List<?> list) {
            if (!list.isEmpty() && "app".equals(list.getFirst()) && list.get(1) instanceof List<?> head && !head.isEmpty() && "prim".equals(head.getFirst())) result.add((List<Object>) list);
            for (var child : list) result.addAll(calls(child));
        }
        return result;
    }
    private CoreRepresentation components(CoreRepresentation proof, List<CoreRepresentation> fields) {
        return proof.copy(proof.getKind(), proof.getEvaluated(), proof.getPresent(), proof.getPrimReps(), fields, proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots());
    }
    @Test public void genuineProofsKeepResultShapeAndIndependentPayloadLevity() throws Exception {
        oracle();
        for (var stage : stages) for (var entry : entries) {
            var linked = CoreModules.reachable(module(stage), "main:ExceptionResultLayoutsAudit." + entry, true); var primitives = new ArrayList<List<Object>>();
            for (var call : calls(linked)) if (Set.of("catch#", "raiseIO#", "maskAsyncExceptions#", "maskUninterruptible#", "unmaskAsyncExceptions#").contains(((List<?>) call.get(1)).get(1))) primitives.add(call);
            assertFalse(primitives.isEmpty(), stage + "/" + entry);
            for (var call : primitives) {
                var name = (String) ((List<?>) call.get(1)).get(1); var arguments = new ArrayList<CoreRepresentation>();
                for (var argument : (List<List<Object>>) call.get(2)) arguments.add(CoreRepresentations.expression(argument));
                var flags = (List<?>) call.get(3); var result = CoreRepresentations.expression(call);
                assertEquals(2, result.getComponents().size(), stage + "/" + entry + "/" + name + " logical arity");
                var value = result.getComponents().get(1);
                switch (entry) {
                    case "emptyResult" -> assertTrue(value.isEmptyTuple());
                    case "nestedResult" -> assertTrue(value.isTuple() && value.getComponents().get(1).isTuple());
                    case "sumResult" -> assertTrue(value.isSum()); case "vectorResult" -> assertTrue(value.isVector());
                    case "unliftedResult" -> assertEquals(List.of("BoxedRep (Just Unlifted)"), value.getPrimReps()); default -> {}
                }
                CoreSynchronousExceptions.validate(name, arguments, flags, result);
                if (entry.equals("unliftedPayloadResult") && name.equals("raiseIO#")) {
                    assertEquals(List.of("BoxedRep (Just Unlifted)"), arguments.get(0).getPrimReps()); assertEquals(List.of(false, false), flags);
                    assertThrows(RuntimeFault.class, () -> CoreSynchronousExceptions.validate(name, arguments, List.of(true, false), result));
                }
                assertThrows(RuntimeFault.class, () -> {
                    var fields = new ArrayList<>(result.getComponents()); fields.add(result.getComponents().getLast());
                    CoreSynchronousExceptions.validate(name, arguments, flags, components(result, fields));
                });
            }
            var audit = (Map<?, ?>) Json.parse(Files.readString(new File(directory, stage + "/audit.json").toPath()));
            assertEquals(true, audit.get("accepted"), stage + "/" + entry); assertEquals(List.of(), audit.get("issues"));
        }
    }
    private CoreRepresentation tuple(CoreRepresentation state, CoreRepresentation value) { return new CoreRepresentation(CoreKind.UNKNOWN, true, true, value.getPrimReps(), List.of(state, value)); }
    @Test public void unknownIsNotAZeroWidthResultButUnknownBoxedResultHasPointerStorage() {
        var state = new CoreRepresentation(CoreKind.VOID, true, true, List.of());
        var closure = new CoreRepresentation(CoreKind.CLOSURE, true, true, List.of("BoxedRep (Just Lifted)"));
        var empty = new CoreRepresentation(CoreKind.UNKNOWN, true, true, List.of(), List.of());
        CoreSynchronousExceptions.validate("catch#", List.of(closure, closure, state), List.of(true, true, false), tuple(state, empty));
        CoreSynchronousExceptions.validate("catch#", List.of(closure, closure, state), List.of(true, true, false),
            tuple(state, new CoreRepresentation(CoreKind.OBJECT, false, true, List.of("BoxedRep Nothing"))));
        for (var value : List.of(CoreRepresentation.UNKNOWN, components(empty, null)))
            assertThrows(RuntimeFault.class, () -> CoreSynchronousExceptions.validate("catch#", List.of(closure, closure, state), List.of(true, true, false), tuple(state, value)));
        for (var payload : List.of(CoreRepresentation.UNKNOWN, new CoreRepresentation(CoreKind.LONG, true, true, List.of("IntRep")), new CoreRepresentation(CoreKind.OBJECT, false, true, List.of("BoxedRep Nothing"))))
            assertThrows(RuntimeFault.class, () -> CoreSynchronousExceptions.validate("raiseIO#", List.of(payload, state), List.of(false, false), tuple(state, empty)));
    }
    @Test public void boxedUnliftedRaiseKeepsTheExactPayloadAndConsumesStateOnce() {
        var payload = new Object(); var consumed = new int[]{0};
        var state = new CoreRepresentation(CoreKind.VOID, true, true, List.of());
        var value = new CoreRepresentation(CoreKind.OBJECT, true, true, List.of("BoxedRep (Just Unlifted)"));
        var result = new CoreRepresentation(CoreKind.UNKNOWN, true, true, value.getPrimReps(), List.of(state, value));
        CoreSynchronousExceptions.validate("raiseIO#", List.of(value, state), List.of(false, false), result);
        var raise = new RaiseIOException(new Expr() { @Override public Object execute(VirtualFrame frame) { return payload; } },
            new Expr() { @Override public Object execute(VirtualFrame frame) { consumed[0]++; return thc.runtime.Unit.INSTANCE; } }, result);
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], FrameDescriptor.newBuilder().build());
        var exception = assertThrows(GuestException.class, () -> raise.executeTuple(frame, new int[0], 0));
        assertSame(payload, exception.getPayload()); assertEquals(1, consumed[0]);
    }
    @ParameterizedTest(name = "{0}/{1}/{2}") @MethodSource("cases")
    public void nativeModelEffectsAndLayoutsSurviveTheFirstInstalledCall(String stage, String backend, String entry) throws Exception {
        var selected = oracle().get(entry); var failures = new CopyOnWriteArrayList<String>();
        var runtime = (OptimizedTruffleRuntime) Truffle.getRuntime();
        var listener = new OptimizedTruffleRuntimeListener() {
            @Override public void onCompilationFailed(OptimizedCallTarget target, String reason, boolean bailout, boolean permanentBailout, int tier, Supplier<String> lazyStackTrace) {
                failures.add(target + ": " + reason);
            }
        };
        runtime.addListener(listener);
        try {
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).allowCreateThread(true)
                    .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                    .option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw")
                    .option("engine.SingleTierCompilationThreshold", "10000000").build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var linked = new LinkedHashMap<>(CoreModules.reachable(module(stage), "main:ExceptionResultLayoutsAudit." + entry, true)); linked.put("instrument", true);
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, linked, true) : new BytecodeProgram(language, linked, true);
                    var target = program.entryTarget("main:ExceptionResultLayoutsAudit." + entry); var function = context.asValue(new EntryValue(program, "main:ExceptionResultLayoutsAudit." + entry, 2)); var label = stage + "/" + backend + "/" + entry;
                    class Runner { void check(Row row) {
                        assertEquals(row.expected(), function.execute(row.mode(), row.input()).asLong(), label + "/" + row);
                        assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(target.getRootNode()), label);
                        assertEquals(0, language.getHandoffState().get().getArguments().getDepth(), label);
                        assertEquals(0, language.getHandoffState().get().getResults().getDepth(), label);
                    } }
                    var runner = new Runner(); for (var row : selected) runner.check(row);
                    assertTrue(function.invokeMember("compile").asBoolean(), label);
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label + " installed");
                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    runner.check(selected.getLast()); // Immediate first invocation; no settling/recovery call.
                    assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before, label);
                    assertSame(target, program.entryTarget("main:ExceptionResultLayoutsAudit." + entry), label);
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label + " retained after first installed call");
                    for (var row : selected) runner.check(row);
                    assertEquals(0L, program.diagnostics().get("unsupportedTraps"), label); assertEquals(0L, program.diagnostics().get("blackholes"), label);
                } finally { context.leave(); }
            }
            assertEquals(List.of(), new ArrayList<>(failures), "No concealed diagnostic compilation retry");
        } finally { runtime.removeListener(listener); }
    }
    @Test public void floatingAndNestedCaptureRootsInstallWithoutDiagnosticRetry() throws Exception {
        nativeModelEffectsAndLayoutsSurviveTheFirstInstalledCall("pre", "ast", "floatResult");
        nativeModelEffectsAndLayoutsSurviveTheFirstInstalledCall("pre", "bytecode", "nestedResult");
    }
    @Test public void asyncVectorOperandReadsItsRawCarrierBeforeAndAfterInstallation() throws Exception {
        nativeModelEffectsAndLayoutsSurviveTheFirstInstalledCall("pre", "ast", "vectorResult");
    }
}
