// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import thc.CoreModules;
import thc.EntryValue;
import thc.Json;
import thc.Language;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(120)
@SuppressWarnings("unchecked")
class GhcBCOTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final List<String> entries = List.of("bcoConstant", "bcoApply", "bcoApplyTwo", "bcoFunction", "bcoArithmetic", "bcoBranch", "bcoLargeOperand", "bcoSharing");
    private long expected(String entry, long n) { return switch (entry) { case "bcoConstant", "bcoFunction", "bcoLargeOperand" -> n; case "bcoApply" -> n + 7; case "bcoApplyTwo" -> n * 10 + 3; case "bcoArithmetic" -> 100 - n; case "bcoBranch" -> n < 0 ? -1 : 1; case "bcoSharing" -> n * 2 + 1; default -> throw new IllegalStateException(entry); }; }
    private Context context() { return Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.WarnInterpreterOnly", "false").option("compiler.Inlining", "false").option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.SingleTierCompilationThreshold", "10000000").option("engine.CompilationFailureAction", "Throw").build(); }
    @Test void nativeInstructionsExecuteThroughBothCoreBackends() throws Exception {
        var manifest = (Map<?, ?>) Json.parse(Files.readString(new File(root, "build/ghc-bco/manifest.json").toPath())); assertEquals(entries, manifest.get("entries"));
        for (String group : List.of("inputHashes", "artifactHashes")) for (var row : ((Map<?, ?>) manifest.get(group)).entrySet()) assertEquals(row.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, (String) row.getKey()).toPath()))), (String) row.getKey());
        var nativeResults = new ArrayList<Long>(); for (var value : (List<?>) manifest.get("native")) nativeResults.add(((Number) value).longValue()); var expectedResults = new ArrayList<Long>(); for (long n : new long[]{-2, 0, 7}) for (String entry : entries) expectedResults.add(expected(entry, n)); assertEquals(expectedResults, nativeResults);
        for (String stage : List.of("pre", "post")) for (String backend : List.of("ast", "bytecode")) for (String entry : entries) try (var context = context()) {
            context.initialize("thc"); context.enter(); try {
                var module = (Map<String, Object>) Json.parse(Files.readString(new File(root, "build/ghc-bco/" + stage + "/core/GhcBCO.json").toPath())); var evidence = new ArrayCoreEvidence(module, entry);
                assertEquals(1, evidence.getPrimitiveCounts().get("newBCO#"), "Actual primitive, not a synthetic BCO substitute"); assertEquals(List.of("bcoFunction", "bcoArithmetic").contains(entry) ? null : Integer.valueOf(1), evidence.getPrimitiveCounts().get("mkApUpd0#"));
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var linked = CoreModules.reachable(module, entry); ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked); var function = context.asValue(new EntryValue(program, entry, 1));
                long[] inputs = {-2, 0, 7}; for (int index = 0; index < inputs.length; index++) { long n = inputs[index]; assertEquals(nativeResults.get(index * entries.size() + entries.indexOf(entry)), function.execute(n).asLong(), stage + "/" + backend + "/" + entry + "/" + n); ThreadInventoryCoreEvidence.released(language); }
                // Compile precisely the genuine public Core root. BCOs are
                // dynamically created interpreter roots, not invented Core.
                var target = program.entryTarget(entry); var interpreted = ThreadInventoryCoreEvidence.interpretedCalls(List.of(target)); long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); ThreadInventoryCoreEvidence.install(List.of(target)); var pools = language.getHandoffState().get(); var allocations = List.of(pools.getArguments().getAllocations(), pools.getResults().getAllocations());
                assertEquals(expected(entry, 11), function.execute(11L).asLong(), stage + "/" + backend + "/" + entry + " first installed call"); assertEquals(1L, ((Number) program.diagnostics().get("compiledEntries")).longValue() - before); assertEquals(interpreted, ThreadInventoryCoreEvidence.interpretedCalls(List.of(target)), "No settling guest call"); ThreadInventoryCoreEvidence.released(language); assertEquals(allocations, List.of(pools.getArguments().getAllocations(), pools.getResults().getAllocations()));
            } finally { context.leave(); }
        }
    }
    private byte[] code(int... values) { byte[] bytes = new byte[values.length * 2]; for (int index = 0; index < values.length; index++) ManagedByteArray.writeInt16(bytes, index, values[index]); return bytes; }
    private byte[] words(long... values) { byte[] bytes = new byte[values.length * 8]; for (int index = 0; index < values.length; index++) ManagedByteArray.writeInt(bytes, index, values[index]); return bytes; }
    private Closure create(Language language, byte[] code) { return create(language, code, 0, words(0), words(), new Object[0]); }
    private Closure create(Language language, byte[] code, long arity, byte[] bitmap) { return create(language, code, arity, bitmap, words(), new Object[0]); }
    private Closure create(Language language, byte[] code, long arity, byte[] bitmap, byte[] literals) { return create(language, code, arity, bitmap, literals, new Object[0]); }
    private Closure create(Language language, byte[] code, long arity, byte[] bitmap, byte[] literals, Object[] refs) { return GhcBCO.create(new Node() {}, language, new Metrics(false), code, literals, refs, arity, bitmap, kotlin.Unit.INSTANCE); }
    @Test void malformedBytecodeFailsBeforeExecutionOrPointerBitFabrication() {
        try (var context = context()) { context.initialize("thc"); context.enter(); try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
            for (var bad : List.of(code(), new byte[]{11}, code(11), code(0x800b, 0, 0), code(0x100b, 0), code(56), code(45, 0, 0), code(55, 1), code(55, 99), code(11, 0, 58), code(25, 0, 1, 61))) assertThrows(RuntimeFault.class, () -> create(language, bad));
            assertThrows(RuntimeFault.class, () -> create(language, code(60), -1, words(0))); assertThrows(RuntimeFault.class, () -> create(language, code(60), 1, words(1)));
            var underflow = create(language, code(2, 0, 60)); assertThrows(RuntimeFault.class, () -> underflow.target.call(0L)); var branchIntoOperand = code(25, 0, 1, 46, 0, 1, 61); assertThrows(RuntimeFault.class, () -> create(language, branchIntoOperand, 0, words(0), words(0)));
            var pointer = new Object(); var pointerAsWord = create(language, code(11, 0, 61), 0, words(0), words(), new Object[]{pointer}); assertThrows(RuntimeFault.class, () -> pointerAsWord.target.call(0L)); var function = create(language, code(58), 1, words(1, 0)); assertThrows(RuntimeFault.class, () -> GhcBCO.updating(new Node() {}, function));
        } finally { context.leave(); } }
    }
    @Test void updatingWrapperIsLazySharedAndReleasesItsCodeAfterUpdate() {
        try (var context = context()) { context.initialize("thc"); context.enter(); try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); int[] effects = {0}; var answer = new Object();
            var worker = new GuestRoot(language, FrameDescriptor.newBuilder().build()) { @Override public long bloom(VirtualFrame frame) { return 0L; } @Override public Object execute(VirtualFrame frame) { effects[0]++; return answer; } };
            var bco = create(language, code(11, 1, 31, 11, 0, 58), 0, words(0), words(), new Object[]{new Closure(null, 1, worker.getCallTarget()), new Object()}); var thunk = GhcBCO.updating(new Node() {}, bco); assertEquals(0, effects[0]);
            var probe = new GuestRoot(language, FrameDescriptor.newBuilder().build()) { @Child private Force force = new Force(new Metrics(false)); @Override public long bloom(VirtualFrame frame) { return 0L; } @Override public Object execute(VirtualFrame frame) { return force.execute(frame, frame.getArguments()[0]); } }.getCallTarget();
            assertSame(answer, probe.call(thunk)); assertSame(answer, probe.call(thunk)); assertEquals(1, effects[0]); assertEquals(2, thunk.getState()); assertNull(thunk.getTarget()); assertNull(thunk.getEnvironment()); var second = GhcBCO.updating(new Node() {}, bco); assertSame(answer, probe.call(second)); assertEquals(2, effects[0]); ThreadInventoryCoreEvidence.released(language);
        } finally { context.leave(); } }
    }
    @Test void boxedApplicationsUseRealPapsAndGuestFailureUpdates() {
        try (var context = context()) { context.initialize("thc"); context.enter(); try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var first = new Object(); var second = new Object(); var selector = create(language, code(2, 0, 38, 1, 2, 58), 2, words(2, 0)); var pap = selector.pap(new Object[]{first});
            var call = new GuestRoot(language, FrameDescriptor.newBuilder().build()) { @Child private Dispatch dispatch = DispatchNodeGen.create(1, false, new Metrics(false)); @Override public long bloom(VirtualFrame frame) { return 0L; } @Override public Object execute(VirtualFrame frame) { return dispatch.execute(frame, pap, new Object[]{second}); } }.getCallTarget();
            assertSame(first, call.call(0L)); var payload = new Object(); int[] effects = {0};
            var throwing = new GuestRoot(language, FrameDescriptor.newBuilder().build()) { @Override public long bloom(VirtualFrame frame) { return 0L; } @Override public Object execute(VirtualFrame frame) { effects[0]++; throw new GuestException(payload, this); } };
            var bco = create(language, code(11, 1, 31, 11, 0, 58), 0, words(0), words(), new Object[]{new Closure(null, 1, throwing.getCallTarget()), first}); var thunk = GhcBCO.updating(new Node() {}, bco);
            var force = new GuestRoot(language, FrameDescriptor.newBuilder().build()) { @Child private Force force = new Force(new Metrics(false)); @Override public long bloom(VirtualFrame frame) { return 0L; } @Override public Object execute(VirtualFrame frame) { return force.execute(frame, thunk); } }.getCallTarget();
            for (int i = 0; i < 2; i++) assertSame(payload, assertThrows(GuestException.class, () -> force.call(0L)).getPayload()); assertEquals(1, effects[0]); assertEquals(3, thunk.getState()); ThreadInventoryCoreEvidence.released(language);
        } finally { context.leave(); } }
    }
    @Test void unsupportedSuspensionsCannotDiscardPendingBcoStackWork() {
        try (var context = context()) { context.initialize("thc"); context.enter(); try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var state = new CoreRepresentation(CoreKind.VOID, false, false, List.of(), null, null, null, null, null); var boxed = new CoreRepresentation(CoreKind.OBJECT, false, false, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null); var shape = new TupleShape(new CoreRepresentation(CoreKind.UNKNOWN, false, false, boxed.getPrimReps(), List.of(state, boxed), null, null, null, null), language); int[] resumed = {0};
            for (boolean capture : new boolean[]{false, true}) {
                var worker = new GuestRoot(language, FrameDescriptor.newBuilder().build()) {
                    @Override public long bloom(VirtualFrame frame) { return 0L; }
                    @Override public Object execute(VirtualFrame frame) {
                        if (capture) throw new DelimitedCut(new PromptTag(Language.currentState(null)), null, shape, MaskingState.UNMASKED, this);
                        return new SavedGuestContinuation() {
                            private final Object savedIdentity = new Object(), savedYield = new Object(), savedRoot = new Object();
                            @Override public Object getIdentity() { return savedIdentity; } @Override public Object getYielded() { return savedYield; } @Override public Object getSourceRoot() { return savedRoot; }
                            @Override public Object continueWith(Object input) { resumed[0]++; return new Object(); }
                        };
                    }
                };
                var bco = create(language, code(11, 1, 31, 11, 0, 58), 0, words(0), words(), new Object[]{new Closure(null, 1, worker.getCallTarget()), new Object()}); var failure = assertThrows(RuntimeFault.class, () -> bco.target.call(0L)); assertTrue(Objects.requireNonNull(failure.getMessage()).contains(capture ? "Delimited capture" : "asynchronous continuation"));
            }
            assertEquals(0, resumed[0]); ThreadInventoryCoreEvidence.released(language);
        } finally { context.leave(); } }
    }
    @Test void scalarEntryRejectsTupleOnlyBcoOperationsBeforeEvaluatingOperands() {
        try (var context = context()) { context.initialize("thc"); context.enter(); try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var bco = new CoreRepresentation(CoreKind.OBJECT, false, false, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null); var proof = new CoreRepresentation(CoreKind.UNKNOWN, false, false, bco.getPrimReps(), List.of(bco), null, null, null, null); var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], FrameDescriptor.newBuilder().build());
            for (String name : List.of("newBCO#", "mkApUpd0#")) {
                Expr[] operands = new Expr[name.equals("newBCO#") ? 6 : 1]; for (int i = 0; i < operands.length; i++) operands[i] = new Expr() { @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("scalar rejection evaluated an operand"); } };
                var node = new GhcBCOExpression(name, operands, language, new Metrics(false), proof); assertEquals(name + " requires a tuple destination", assertThrows(RuntimeFault.class, () -> node.execute(frame)).getMessage());
            }
        } finally { context.leave(); } }
    }
    private CoreRepresentation tuple(CoreRepresentation... fields) { var reps = new ArrayList<String>(); for (var field : fields) reps.addAll(Objects.requireNonNull(field.getPrimReps())); return new CoreRepresentation(CoreKind.UNKNOWN, false, false, reps, Arrays.asList(fields), null, null, null, null); }
    @Test void loweringRejectsWrongPhysicalCarriersAndTupleOrder() {
        var state = new CoreRepresentation(CoreKind.VOID, false, false, List.of(), null, null, null, null, null); var pointer = new CoreRepresentation(CoreKind.OBJECT, false, false, List.of("BoxedRep (Just Unlifted)"), null, null, null, null, null); var bco = new CoreRepresentation(CoreKind.OBJECT, false, false, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null); var number = new CoreRepresentation(CoreKind.LONG, false, false, List.of("IntRep"), null, null, null, null, null); var args = List.of(pointer, pointer, pointer, number, pointer, state);
        GhcBCO.validate("newBCO#", args, Collections.nCopies(6, false), tuple(state, bco)); GhcBCO.validate("mkApUpd0#", List.of(bco), List.of(true), tuple(bco));
        for (int index = 0; index < args.size(); index++) { var bad = new ArrayList<>(args); bad.set(index, index == 3 ? pointer : number); assertThrows(RuntimeFault.class, () -> GhcBCO.validate("newBCO#", bad, Collections.nCopies(6, false), tuple(state, bco))); }
        assertThrows(RuntimeFault.class, () -> GhcBCO.validate("newBCO#", args, Collections.nCopies(6, false), tuple(bco, state))); assertThrows(RuntimeFault.class, () -> GhcBCO.validate("mkApUpd0#", List.of(bco), List.of(true), tuple(number))); assertThrows(RuntimeFault.class, () -> GhcBCO.validate("mkApUpd0#", List.of(bco), List.of(false), tuple(bco)));
    }
    @Test void scalarStackOperationsPreserveOrderBitsAndContextOwnership() {
        Closure foreign;
        try (var context = context()) { context.initialize("thc"); context.enter(); try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var arithmetic = create(language, code(25, 0, 1, 91, 61), 1, words(1, 1), words(100));
            for (long n : new long[]{Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE}) assertEquals(100L - n, arithmetic.target.call(0L, n)); var reverse = create(language, code(3, 0, 1, 38, 2, 2, 91, 61), 2, words(2, 3)); assertEquals(9L, reverse.target.call(0L, 3L, 12L));
            long nan = 0x7ff8000000000042L; var floating = create(language, code(25, 0, 1, 63), 0, words(0), words(nan)); assertEquals(nan, Double.doubleToRawLongBits((Double) floating.target.call(0L))); var shift = create(language, code(99, 61), 2, words(2, 3)); assertEquals(1L, shift.target.call(0L, -1L, 63L)); assertThrows(RuntimeFault.class, () -> shift.target.call(0L, 1L, 64L)); foreign = arithmetic;
        } finally { context.leave(); } }
        try (var context = context()) { context.initialize("thc"); context.enter(); try { assertThrows(RuntimeFault.class, () -> foreign.target.call(0L, 7L)); assertThrows(RuntimeFault.class, () -> GhcBCO.updating(new Node() {}, foreign)); } finally { context.leave(); } }
    }
}
