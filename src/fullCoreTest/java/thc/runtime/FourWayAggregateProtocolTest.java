// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Positive layouts come from genuine GHC exports; only negative controls corrupt them. */
@SuppressWarnings("unchecked")
public class FourWayAggregateProtocolTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private Map<String, Object> json(File file) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8)); }
    private List<Map<String, Object>> modules(String stage) throws Exception {
        FourWayEvidence.verify(root); var files = new File(root, "build/fourway-aggregate/" + stage + "/core").listFiles(); assertNotNull(files);
        var result = new ArrayList<Map<String, Object>>(); for (File file : files) if (file.getName().endsWith(".json")) result.add(json(file)); return result;
    }
    private CoreRepresentation originalProof() throws Exception {
        FourWayEvidence.verify(root); var module = json(new File(root, "build/fourway-aggregate/pre/core/FourWayAggregateFields.json")); Map<String, Object> original = null;
        for (var constructor : (List<Map<String, Object>>) module.get("constructors")) if ("VirtualRegWithFormat".equals(constructor.get("name"))) { assertNull(original); original = constructor; }
        assertNotNull(original); return new CoreFields(original).getLogicalProofs()[0];
    }
    @FunctionalInterface private interface Action { void run(Context context, Language language) throws Exception; }
    private void entered(Action action) throws Exception {
        try (Context context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try { action.run(context, TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    private void released(Language language) {
        var handoff = language.getHandoffState().get(); assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getArguments().getDepth());
        assertEquals(0, handoff.getResults().retainedReferences()); assertEquals(0, handoff.getArguments().retainedReferences());
    }
    private Object call(RootCallTarget target, DataLayout layout, DataValue format, long tag) { return Calls.target(target, new Object[] {0L, layout.create(new Object[] {tag, Long.MIN_VALUE, format})}); }
    @Test public void malformedPhysicalTagsCannotSelectTheOriginalDefaultArm() throws Exception { entered((context, language) -> {
        for (String stage : List.of("pre", "post")) for (String backend : List.of("ast", "bytecode")) {
            var linked = CoreModules.reachable(CoreModules.merge(modules(stage)), "main:FourWayAggregateFields.defaultArm", true);
            ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
            var layout = program.constructorLayout("ghc-9.14.1-inplace:GHC.CmmToAsm.Format.VirtualRegWithFormat");
            var format = program.constructorLayout("ghc-9.14.1-inplace:GHC.CmmToAsm.Format.II64").allocate();
            String name = "main:FourWayAggregateFields.consumeDefault"; var target = program.entryTarget(name);
            for (long tag = 1; tag <= 4; tag++) assertEquals(tag == 1L ? Long.MIN_VALUE : -1L, call(target, layout, format, tag));
            assertTrue(context.asValue(new EntryValue(program, name, 1)).invokeMember("compile").asBoolean());
            long before = (Long) program.diagnostics().get("compiledEntries"); assertEquals(-1L, call(target, layout, format, 4)); assertTrue((Long) program.diagnostics().get("compiledEntries") > before);
            for (long tag : new long[] {Long.MIN_VALUE, -1L, 0L, 5L, 0x100000001L, Long.MAX_VALUE}) {
                var failure = assertThrows(RuntimeFault.class, () -> call(target, layout, format, tag), stage + "/" + backend + "/tag=" + tag);
                assertTrue(Objects.toString(failure.getMessage(), "").contains("Invalid unboxed sum tag"), failure.getMessage()); released(language);
            }
        }
    }); }
    @Test public void genuineFourWayFamilyKeepsExactArityProjectionAndPayloadBoundaries() throws Exception {
        var proof = originalProof(); var alternatives = Objects.requireNonNull(proof.getAlternatives());
        for (int arity : new int[] {2, 3, 5}) for (int tag : new int[] {1, 2, 3, 4}) assertThrows(RuntimeFault.class,
            () -> SumShape.constructor(proof, Map.of("kind", "unboxed-sum", "arity", 1, "sumArity", arity, "tag", tag), 1));
        for (long tag : new long[] {Long.MIN_VALUE, 0L, 5L, 0x100000001L, Long.MAX_VALUE}) assertThrows(RuntimeFault.class,
            () -> SumShape.constructor(proof, Map.of("kind", "unboxed-sum", "arity", 1, "sumArity", 4, "tag", tag), 1));
        assertThrows(RuntimeFault.class, () -> SumShape.validate(proof.copy(proof.getKind(), proof.getEvaluated(), proof.getPresent(), proof.getPrimReps(), proof.getComponents(), proof.getVector(), alternatives, proof.getTagSlot(), List.of(List.of(0), List.of(1), List.of(1), List.of(1)))));
        assertThrows(RuntimeFault.class, () -> SumShape.validate(proof.copy(proof.getKind(), proof.getEvaluated(), proof.getPresent(), List.of("WordRep", "WordRep"), proof.getComponents(), proof.getVector(), alternatives, proof.getTagSlot(), proof.getAlternativeSlots())));
        assertThrows(RuntimeFault.class, () -> SumShape.payload(alternatives.getFirst(), new CoreRepresentation(CoreKind.FLOAT, true, true, List.of("FloatRep"), null, null, null, null, null)));
        assertThrows(RuntimeFault.class, () -> SumShape.payload(alternatives.getFirst(), alternatives.getFirst(), true));
        // A nested payload cannot reuse the original family's physical proof unchanged.
        var nested = new ArrayList<>(alternatives); nested.set(0, proof);
        var forgedNested = assertThrows(RuntimeFault.class, () -> SumShape.validate(proof.copy(proof.getKind(), proof.getEvaluated(), proof.getPresent(), proof.getPrimReps(), proof.getComponents(), proof.getVector(), nested, proof.getTagSlot(), proof.getAlternativeSlots())));
        assertTrue(Objects.toString(forgedNested.getMessage(), "").contains("Sum physical representation or tag slot mismatch"), forgedNested.getMessage());
    }
    private static final class ResumeProbe extends GuestRoot {
        int scrutineeVisits, branchVisits;
        @Child private Expr body;
        private static FrameLayout layout() { var layout = new FrameLayout(); layout.bind("tag"); layout.bind("payload"); return layout; }
        ResumeProbe(Language language, CoreRepresentation proof, int[] mapping) { this(language, proof, mapping, layout()); }
        private ResumeProbe(Language language, CoreRepresentation proof, int[] mapping, FrameLayout layout) {
            super(language, layout.build()); int[] slots = {layout.slot("tag"), layout.slot("payload")};
            Expr scrutinee = new Expr() {
                @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Typed scrutinee expected"); }
                @Override public Object executeTuple(VirtualFrame frame, int[] destination, int offset) {
                    scrutineeVisits++; long tag = (Long) frame.getArguments()[0];
                    throw new AstCapture(thc.runtime.Unit.INSTANCE, SynchronousMasking.current(this)).append((saved, input) -> {
                        assertSame(thc.runtime.Unit.INSTANCE, input); FrameAccess.writeLong(saved, destination[offset], tag); FrameAccess.writeLong(saved, destination[offset + 1], Long.MIN_VALUE); return null;
                    });
                }
            };
            Expr[] branches = new Expr[2];
            for (int index = 0; index < 2; index++) {
                long answer = index == 0 ? 41L : 77L;
                branches[index] = new Expr() {
                    @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
                    @Override public long executeLong(VirtualFrame frame) {
                        branchVisits++;
                        if (Boolean.TRUE.equals(frame.getArguments()[1])) throw new AstCapture(thc.runtime.Unit.INSTANCE, SynchronousMasking.current(this)).append((saved, input) -> { assertSame(thc.runtime.Unit.INSTANCE, input); return answer; });
                        return answer;
                    }
                };
            }
            body = new SumCase(scrutinee, slots, branches, mapping, Objects.requireNonNull(proof.getAlternatives()).getFirst());
        }
        @Override public Object execute(VirtualFrame frame) {
            try { return body.executeLong(frame); } catch (AstCapture cut) { return cut.freeze(this, frame.materialize()); }
            catch (com.oracle.truffle.api.nodes.UnexpectedResultException failure) { throw new AssertionError(failure); }
        }
        @Override public long bloom(VirtualFrame frame) { return 0L; }
    }
    @Test public void resumedThirdAndFourthTagsRetainDefaultRouteAndDoNotReplayAcrossASecondCut() throws Exception { entered((context, language) -> {
        var proof = originalProof();
        for (long tag : new long[] {3L, 4L}) for (boolean branchCut : new boolean[] {false, true}) {
            var probe = new ResumeProbe(language, proof, new int[] {0, 1, 1, 1}); var cut = (AstContinuation) probe.getCallTarget().call(tag, branchCut);
            var result = cut.continueWith(thc.runtime.Unit.INSTANCE); if (branchCut) assertEquals(77L, ((AstContinuation) result).continueWith(thc.runtime.Unit.INSTANCE)); else assertEquals(77L, result);
            assertEquals(1, probe.scrutineeVisits); assertEquals(1, probe.branchVisits); assertThrows(RuntimeFault.class, () -> cut.continueWith(thc.runtime.Unit.INSTANCE)); released(language);
        }
        for (long tag : new long[] {0L, 5L, 0x100000001L, Long.MIN_VALUE}) {
            var probe = new ResumeProbe(language, proof, new int[] {0, 1, 1, 1}); var cut = (AstContinuation) probe.getCallTarget().call(tag, false);
            assertThrows(RuntimeFault.class, () -> cut.continueWith(thc.runtime.Unit.INSTANCE)); assertEquals(1, probe.scrutineeVisits); assertEquals(0, probe.branchVisits);
        }
        for (long tag : new long[] {3L, 4L}) {
            var probe = new ResumeProbe(language, proof, new int[] {0, -1, -1, -1}); var cut = (AstContinuation) probe.getCallTarget().call(tag, false);
            var failure = assertThrows(RuntimeFault.class, () -> cut.continueWith(thc.runtime.Unit.INSTANCE));
            assertTrue(Objects.toString(failure.getMessage(), "").contains("Non-exhaustive"), failure.getMessage()); assertEquals(1, probe.scrutineeVisits); assertEquals(0, probe.branchVisits);
        }
    }); }
}
