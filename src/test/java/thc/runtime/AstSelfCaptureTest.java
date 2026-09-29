// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.Main.executionContext;

/** Deterministic cuts exercise real self-transfer, forcing and saved-root protocols. */
class AstSelfCaptureTest {
    private static final CoreRepresentation LONG = CoreRepresentations.parse(
        Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true));
    private static final CoreRepresentation CLOSURE = CoreRepresentations.parse(
        Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true));

    private static final class Operand extends Expr {
        Object value;
        final boolean cut;
        int executions;
        Operand(Object value, CoreRepresentation proof, boolean cut) {
            this.value = value; this.cut = cut; setRepresentation(proof);
        }
        @Override public Object execute(VirtualFrame frame) {
            executions++;
            if (cut) throw new AstCapture(Unit.INSTANCE, SynchronousMasking.current(this));
            return value;
        }
    }
    private static final class Body extends Expr {
        @Child private AstTailApplication tail;
        final int[] arguments;
        final boolean masked;
        MaterializedFrame frame;
        MaskingState completionMask;
        int completions;
        Body(AstTailApplication tail, int[] arguments, boolean masked) {
            this.tail = tail; this.arguments = arguments; this.masked = masked;
            setRepresentation(LONG);
        }
        @Override public Object execute(VirtualFrame frame) {
            this.frame = frame.materialize();
            if ((long) FrameAccess.read(frame, arguments[0]) == 0L) {
                completions++; completionMask = SynchronousMasking.current(this);
                return (long) FrameAccess.read(frame, arguments[1]) - (long) FrameAccess.read(frame, arguments[2]);
            }
            if (!masked) return tail.execute(frame);
            MaskingState prior = SynchronousMasking.current(this);
            SynchronousMasking.set(this, MaskingState.MASKED_UNINTERRUPTIBLE);
            try { return tail.execute(frame); }
            catch (AstCapture cut) { throw cut.enclose(steps -> new AstMaskScope(this, prior, steps)); }
            finally { SynchronousMasking.set(this, prior); }
        }
    }
    private static final class Fixture {
        final Metrics metrics = new Metrics(true);
        final int[] arguments, temporaries;
        final Operand function, next, first, second;
        final Body body;
        final FunctionRoot root;
        final Closure closure;
        Fixture(Language language, String stage, boolean masked) {
            FrameLayout frame = new FrameLayout();
            arguments = new int[]{frame.bind("remaining"), frame.bind("first"), frame.bind("second")};
            temporaries = new int[]{frame.bind("next remaining"), frame.bind("next first"), frame.bind("next second")};
            boolean strict = stage.startsWith("strict");
            function = new Operand(null, CLOSURE, stage.equals("function"));
            next = new Operand(0L, LONG, false);
            first = new Operand(17L, strict ? CoreRepresentation.UNKNOWN : LONG,
                stage.equals("operand0") || stage.equals("twice"));
            second = new Operand(5L, strict ? CoreRepresentation.UNKNOWN : LONG,
                stage.equals("operand1") || stage.equals("twice"));
            var self = new AstSelfLayout(null, new int[0], arguments,
                new CoreRepresentation[]{LONG, LONG, LONG}, new boolean[]{false, strict, strict});
            var tail = new AstTailApplication(function, new Expr[]{next, first, second}, self, temporaries, metrics);
            body = new Body(tail, arguments, masked);
            root = new FunctionRoot(language, frame.build(), "saved scalar self transfer", null,
                new int[0], arguments, new int[]{0, 1, 2}, body, metrics,
                new CoreRepresentation[]{LONG, LONG, LONG}, LONG, null, new boolean[]{false, strict, strict},
                null, null, new int[0], null, true, new int[0][], false, FunctionRootRole.FUNCTION, false);
            root.configureEagerAsyncPolls(false);
            closure = new Closure(null, 3, root.getCallTarget()); function.value = closure;
        }
        AstContinuation capture() {
            return assertDoesNotThrow(() -> (AstContinuation) Calls.target(root.getCallTarget(), new Object[]{0L, 1L, 13L, 7L}));
        }
        void assertUnchangedArguments() {
            assertEquals(1L, FrameAccess.read(body.frame, arguments[0]));
            assertEquals(13L, FrameAccess.read(body.frame, arguments[1]));
            assertEquals(7L, FrameAccess.read(body.frame, arguments[2]));
        }
        void assertFinished() {
            assertEquals(1, function.executions); assertEquals(1, next.executions);
            assertEquals(1, first.executions); assertEquals(1, second.executions);
            assertEquals(1, body.completions);
            assertEquals(1L, metrics.getSelfTailReentries()); assertEquals(0L, metrics.getTailBounces());
            for (int slot : temporaries) assertEquals(FrameSlotKind.Illegal.tag, body.frame.getTag(slot), "Completed self scratch must clear");
        }
    }
    @FunctionalInterface private interface Action { void run(Language language) throws Exception; }
    private void inContext(Action action) throws Exception {
        try (var context = executionContext()) {
            context.initialize("thc"); context.enter();
            try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); }
            finally { context.leave(); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"function", "operand0", "operand1"})
    void savedPreparationConsumesEachStageOnceBeforeTransfer(String stage) throws Exception {
        inContext(language -> {
            var fixture = new Fixture(language, stage, false);
            var saved = fixture.capture(); fixture.assertUnchangedArguments();
            Object value = stage.equals("function") ? fixture.closure : stage.equals("operand0") ? 17L : 5L;
            assertEquals(12L, saved.continueWith(value)); fixture.assertFinished();
            assertThrows(RuntimeFault.class, () -> saved.continueWith(value));
            assertSame(fixture.root, saved.getSourceRoot());
        });
    }
    @Test void secondOperandCutRetainsOnlyTheUnconsumedPreparation() throws Exception {
        inContext(language -> {
            var fixture = new Fixture(language, "twice", false);
            var first = fixture.capture(); fixture.assertUnchangedArguments();
            var second = assertInstanceOf(AstContinuation.class, first.continueWith(17L));
            fixture.assertUnchangedArguments();
            assertThrows(RuntimeFault.class, () -> first.continueWith(17L));
            assertEquals(12L, second.continueWith(5L)); fixture.assertFinished();
            assertThrows(RuntimeFault.class, () -> second.continueWith(5L));
        });
    }
    @Test void savedSelfTransferUnwindsLexicalMaskBeforeRestartingTheBody() throws Exception {
        inContext(language -> {
            var fixture = new Fixture(language, "operand0", true);
            var saved = fixture.capture(); fixture.assertUnchangedArguments();
            assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(fixture.root));
            SynchronousMasking.set(fixture.root, MaskingState.MASKED_INTERRUPTIBLE);
            try {
                assertEquals(12L, saved.continueWith(17L)); fixture.assertFinished();
                assertEquals(MaskingState.UNMASKED, fixture.body.completionMask);
                assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(fixture.root));
            } finally { SynchronousMasking.set(fixture.root, MaskingState.UNMASKED); }
        });
    }
    @ParameterizedTest @ValueSource(ints = {0, 1})
    void strictForceKeepsItsOwnedThunkAndPendingSelfTransfer(int index) throws Exception {
        inContext(language -> {
            var fixture = new Fixture(language, "strict" + index, false);
            int[] prefix = {0}, suffix = {0};
            var leafBody = new Expr() {
                { setRepresentation(LONG); }
                @Override public Object execute(VirtualFrame frame) {
                    prefix[0]++;
                    throw new AstCapture(Unit.INSTANCE, SynchronousMasking.current(this)).append((saved, input) -> {
                        assertSame(Unit.INSTANCE, input); suffix[0]++; return index == 0 ? 17L : 5L;
                    });
                }
            };
            var leaf = new FunctionRoot(language, new FrameLayout().build(), "strict operand leaf", null,
                new int[0], new int[0], new int[0], leafBody, new Metrics(false), new CoreRepresentation[0], LONG,
                null, new boolean[0], null, null, new int[0], null, true, new int[0][], false, FunctionRootRole.FUNCTION, false);
            leaf.configureEagerAsyncPolls(false);
            var thunk = new Thunk(leaf.getCallTarget(), null);
            (index == 0 ? fixture.first : fixture.second).value = thunk;
            var saved = fixture.capture(); fixture.assertUnchangedArguments();
            assertEquals(5, thunk.getState()); assertEquals(1, prefix[0]); assertEquals(0, suffix[0]);
            RootCallTarget resume = new RootNode(language) {
                @Child private Force force = new Force(new Metrics(false), true);
                @Override public Object execute(VirtualFrame frame) { return force.drainStack((SavedGuestContinuation) frame.getArguments()[0]); }
            }.getCallTarget();
            assertEquals(12L, Calls.target(resume, new Object[]{saved})); fixture.assertFinished();
            assertEquals(1, prefix[0]); assertEquals(1, suffix[0]); assertEquals(2, thunk.getState());
            assertEquals(index == 0 ? 17L : 5L, thunk.getValue());
            assertThrows(RuntimeFault.class, () -> saved.continueWith(Unit.INSTANCE));
        });
    }
}
