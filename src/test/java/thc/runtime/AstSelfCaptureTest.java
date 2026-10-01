// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import jdk.incubator.vector.ShortVector;
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
    private static final CoreRepresentation VECTOR = CoreRepresentations.parse(Map.of("kind", "vector",
        "primReps", List.of("VecRep 8 Int16ElemRep"), "vector", Map.of("lanes", 8, "element", "Int16ElemRep"), "evaluated", true));

    private static final class Operand extends Expr {
        Object value;
        final boolean cut;
        final boolean delimited;
        int executions;
        Operand(Object value, CoreRepresentation proof, boolean cut) {
            this(value, proof, cut, false);
        }
        Operand(Object value, CoreRepresentation proof, boolean cut, boolean delimited) {
            this.value = value; this.cut = cut; this.delimited = delimited; setRepresentation(proof);
        }
        @Override public Object execute(VirtualFrame frame) {
            executions++;
            if (cut && delimited) throw new DelimitedCut(new PromptTag(Language.currentState()), null, null,
                SynchronousMasking.current(this), this);
            if (cut) throw new AstCapture(Unit.INSTANCE, SynchronousMasking.current(this));
            return value;
        }
    }
    private static final class TypedBody extends Expr {
        @Child private AstTypedApplication tail;
        final int[] arguments;
        final boolean masked;
        MaterializedFrame frame;
        MaskingState completionMask;
        int completions;
        TypedBody(AstTypedApplication tail, int[] arguments, boolean masked) {
            this.tail = tail; this.arguments = arguments; this.masked = masked; setRepresentation(VECTOR);
        }
        @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Typed destination required"); }
        @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
            this.frame = frame.materialize();
            if (frame.getLong(arguments[0]) == 0L) {
                completions++; completionMask = SynchronousMasking.current(this);
                getTypedVectorLayout().write(frame, slots, offset,
                    ((ShortVector) frame.getObject(arguments[2])).add((short) frame.getLong(arguments[1])));
                return null;
            }
            if (!masked) return tail.executeTuple(frame, slots, offset);
            MaskingState prior = SynchronousMasking.current(this);
            SynchronousMasking.set(this, MaskingState.MASKED_UNINTERRUPTIBLE);
            try { return tail.executeTuple(frame, slots, offset); }
            catch (AstCapture cut) { throw cut.enclose(steps -> new AstMaskScope(this, prior, steps)); }
            catch (DelimitedCut cut) { throw cut.append(frame, new DelimitedMaskStep(this, prior)); }
            finally { SynchronousMasking.set(this, prior); }
        }
    }
    private static final class TypedFixture {
        final Metrics metrics = new Metrics(true);
        final int[] arguments, temporaries;
        final Operand function, next, first, vector;
        final TypedBody body;
        final FunctionRoot root;
        final Closure closure;
        final TupleShape shape;
        TypedFixture(Language language, String stage, boolean masked) {
            var frame = new FrameLayout();
            arguments = new int[]{frame.bind("remaining"), frame.bind("scalar"), frame.bind("vector")};
            function = new Operand(null, CLOSURE, stage.equals("function"));
            next = new Operand(0L, LONG, stage.equals("operand0") || stage.equals("twice"));
            first = new Operand(17L, LONG, stage.equals("operand1") || stage.equals("twice") || stage.equals("delimited"), stage.equals("delimited"));
            vector = new Operand(ShortVector.broadcast(ShortVector.SPECIES_128, (short) 5), VECTOR, false);
            shape = new TupleShape(VECTOR, language);
            var tail = new AstTypedApplication(function, new Expr[]{next, first, vector}, frame, true, metrics, shape, true);
            body = new TypedBody(tail, arguments, masked);
            var input = ArgumentLayout.fromProofs(List.of(LONG, LONG, VECTOR));
            int result = frame.bind("typed result");
            root = new FunctionRoot(language, frame.build(), "saved typed self transfer", null, new int[0], arguments,
                new int[]{0, 1, 2}, body, metrics, new CoreRepresentation[]{LONG, LONG, VECTOR}, VECTOR, null,
                new boolean[]{false, false, false}, null, shape, new int[]{result}, input,
                true, new int[0][], stage.equals("delimited"), FunctionRootRole.FUNCTION, false);
            root.configureTypedInput(new TypedInputLayout(language, input, false));
            root.configureEagerAsyncPolls(false);
            closure = new Closure(null, 3, root.getCallTarget()); function.value = closure;
            temporaries = NodeUtil.findFirstNodeInstance(root, AstInputOperands.class).getSource().getSlots();
        }
        Object call() {
            var input = root.getTypedInput(); var packet = input.getPacket().create(); packet.setInputMode(3);
            input.getPacket().setLong(packet, 0, 0L); input.getPacket().setLong(packet, 1, 1L);
            input.getPacket().setLong(packet, 2, 13L);
            input.getPacket().setObject(packet, 3, ShortVector.broadcast(ShortVector.SPECIES_128, (short) 7));
            return TypedInputs.invokeTypedInput(input, packet, arguments -> Calls.target(root.getCallTarget(), arguments));
        }
        void assertUnchangedArguments() {
            assertEquals(1L, body.frame.getLong(arguments[0])); assertEquals(13L, body.frame.getLong(arguments[1]));
            assertArrayEquals(new short[]{7, 7, 7, 7, 7, 7, 7, 7}, ((ShortVector) body.frame.getObject(arguments[2])).toArray());
        }
        void assertResult(Object answer) {
            var result = TupleResults.ownedTupleResult(answer, shape);
            assertArrayEquals(new short[]{22, 22, 22, 22, 22, 22, 22, 22},
                ((ShortVector) shape.getLayout().getObject(result, 0)).toArray());
        }
        void assertFinished() {
            assertEquals(1, function.executions); assertEquals(1, next.executions);
            assertEquals(1, first.executions); assertEquals(1, vector.executions); assertEquals(1, body.completions);
            for (int slot : temporaries) assertEquals(FrameSlotKind.Illegal.tag, body.frame.getTag(slot), "Completed typed scratch must clear");
            assertEquals(1L, metrics.getSelfTailReentries()); assertEquals(0L, metrics.getTailBounces());
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
    @ParameterizedTest @ValueSource(strings = {"function", "operand0", "operand1", "twice"})
    void savedTypedPreparationMovesOnlyAfterEachStageCompletes(String stage) throws Exception {
        inContext(language -> {
            var fixture = new TypedFixture(language, stage, true);
            var saved = assertInstanceOf(AstContinuation.class, fixture.call()); fixture.assertUnchangedArguments();
            assertSame(fixture.root, saved.getSourceRoot());
            assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(fixture.root));
            SynchronousMasking.set(fixture.root, MaskingState.MASKED_INTERRUPTIBLE);
            try {
                Object input = stage.equals("function") ? fixture.closure : stage.equals("operand1") ? 17L : 0L;
                Object answer = saved.continueWith(input);
                assertThrows(RuntimeFault.class, () -> saved.continueWith(input));
                if (stage.equals("twice")) {
                    var second = assertInstanceOf(AstContinuation.class, answer); fixture.assertUnchangedArguments();
                    assertSame(fixture.root, second.getSourceRoot()); answer = second.continueWith(17L);
                    assertThrows(RuntimeFault.class, () -> second.continueWith(17L));
                }
                fixture.assertResult(answer); fixture.assertFinished();
                assertEquals(MaskingState.UNMASKED, fixture.body.completionMask);
                assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(fixture.root));
            } finally { SynchronousMasking.set(fixture.root, MaskingState.UNMASKED); }
        });
    }
    @Test void delimitedTypedPreparationRetainsItsDestinationAndContextOwner() throws Exception {
        inContext(language -> {
            var fixture = new TypedFixture(language, "delimited", true);
            var cut = assertThrows(DelimitedCut.class, fixture::call); fixture.assertUnchangedArguments();
            var original = fixture.body.frame;
            cut.append(original, (saved, input, ambient, outer) -> {
                Object answer = input.get();
                for (int slot : fixture.temporaries)
                    assertEquals(FrameSlotKind.Illegal.tag, saved.getTag(slot), "Delimited typed scratch must clear before the caller suffix");
                return answer;
            });
            var image = new DelimitedStack(cut, fixture.shape);
            class Owner extends GuestRoot {
                @Child private DelimitedActionSite site;
                Owner(Language ownerLanguage) {
                    super(ownerLanguage, new FrameLayout().build());
                    site = new DelimitedActionSite(ownerLanguage, new Metrics(false));
                }
                @Override public long bloom(VirtualFrame frame) { return 0L; }
                @Override public Object execute(VirtualFrame frame) { return 17L; }
                Object resume() { return image.resume(site, Truffle.getRuntime().createMaterializedFrame(new Object[]{0L}, getFrameDescriptor()),
                    new Closure(null, 1, getCallTarget())); }
            }
            var owner = new Owner(language); owner.getCallTarget();
            try (var other = executionContext()) {
                other.initialize("thc"); other.enter();
                try {
                    var foreign = new Owner(TruffleLanguage.LanguageReference.create(Language.class).get(null));
                    foreign.getCallTarget(); assertThrows(RuntimeFault.class, foreign::resume);
                }
                finally { other.leave(); }
            }
            SynchronousMasking.set(fixture.root, MaskingState.MASKED_INTERRUPTIBLE);
            try {
                fixture.assertResult(owner.resume()); fixture.assertResult(owner.resume());
                assertEquals(1, fixture.function.executions); assertEquals(1, fixture.next.executions);
                assertEquals(1, fixture.first.executions); assertEquals(2, fixture.vector.executions);
                assertEquals(2, fixture.body.completions);
                assertEquals(2L, fixture.metrics.getSelfTailReentries()); assertEquals(2L, fixture.metrics.getTailBounces());
                assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, fixture.body.completionMask);
                assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(fixture.root));
                assertEquals(1L, original.getLong(fixture.arguments[0]));
                assertEquals(13L, original.getLong(fixture.arguments[1]));
                assertArrayEquals(new short[]{7, 7, 7, 7, 7, 7, 7, 7}, ((ShortVector) original.getObject(fixture.arguments[2])).toArray());
            } finally { SynchronousMasking.set(fixture.root, MaskingState.UNMASKED); }
        });
    }
}
