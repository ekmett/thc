// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.List;
import java.util.Objects;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Adversarial lifetime controls for the shared typed input protocol. */
class TypedInputOwnershipTest {
    @FunctionalInterface private interface Action { void run(Language language) throws ReflectiveOperationException; }
    private void withLanguage(Action action) throws ReflectiveOperationException {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); }
            finally { context.leave(); }
        }
    }
    private TypedInputLayout input(Language language) {
        var fields = List.of(new CoreRepresentation(CoreKind.LONG, true, true, List.of("IntRep"), null, null, null, null, null),
            new CoreRepresentation(CoreKind.OBJECT, false, true, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null));
        var tuple = new CoreRepresentation(CoreKind.UNKNOWN, true, true, List.of("IntRep", "BoxedRep (Just Lifted)"), fields, null, null, null, null);
        return new TypedInputLayout(language, Objects.requireNonNull(ArgumentLayout.fromProofs(List.of(tuple))), false);
    }
    private void clear(Language language) {
        var state = language.getHandoffState().get(); assertNull(state.getPending());
        assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences());
        assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
    }
    private boolean valid(RootCallTarget target) throws ReflectiveOperationException { return Boolean.TRUE.equals(target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private void compile(RootCallTarget target) throws ReflectiveOperationException {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true); assertTrue(valid(target));
    }
    private static final class Barrier {
        static volatile int action;
        static volatile long observations;
        static long materializedFresh;
        static long clearedFresh;
        @CompilerDirectives.TruffleBoundary static int observe() { observations++; return action; }
    }
    private static final class Receiver extends GuestRoot {
        private final TypedInputLayout input;
        Receiver(Language language, TypedInputLayout input) {
            super(language, new FrameLayout().build()); this.input = input;
            configureEntry(new boolean[]{false}, false); configureInput(input.getLogical()); configureTypedInput(input);
        }
        @Override public long bloom(VirtualFrame frame) { return 0L; }
        @Override public Object execute(VirtualFrame frame) {
            var carrier = input.take(frame.getArguments());
            try {
                long number = input.getPacket().getLong(carrier, input.getHeader());
                // This side effect prevents replay of entry before fresh-carrier construction and consumption.
                int action = Barrier.observe();
                if (action != 0) CompilerDirectives.transferToInterpreterAndInvalidate();
                if (CompilerDirectives.inInterpreter() && carrier.getInputMode() == 2) {
                    if (carrier.getLive()) throw new IllegalStateException("Check failed.");
                    Barrier.materializedFresh++;
                }
                var pointer = input.getPacket().getObject(carrier, input.getHeader() + 1);
                if (number != 4097L) throw new IllegalStateException("Check failed.");
                if (action == 2) throw new GuestException("after partial typed restore", this);
                return pointer;
            } finally {
                boolean fresh = carrier.getInputMode() == 2;
                input.releaseChecked(carrier);
                if (CompilerDirectives.inInterpreter() && fresh) {
                    if (carrier.getInputMode() != 0 || carrier.getLive()) throw new IllegalStateException("Check failed.");
                    if (input.getPacket().getObject(carrier, input.getHeader() + 1) != null) throw new IllegalStateException("Check failed.");
                    Barrier.clearedFresh++;
                }
            }
        }
    }
    private static final class Slots {
        final FrameLayout layout = new FrameLayout();
        @CompilationFinal(dimensions = 1) final int[] fields = {layout.bind("number"), layout.bind("lazy pointer")};
    }
    private static final class Caller extends RootNode {
        private final Slots slots;
        private final Closure function;
        @Child private InputDispatch dispatch;
        Caller(Language language, TypedInputLayout input, RootCallTarget target) { this(language, input, target, new Slots()); }
        private Caller(Language language, TypedInputLayout input, RootCallTarget target, Slots slots) {
            super(language, slots.layout.build()); this.slots = slots; function = new Closure(null, 1, target);
            dispatch = new InputDispatch(new AstInputSource(input.getLogical(), slots.fields), 1, false, new Metrics(false));
        }
        @Override public Object execute(VirtualFrame frame) {
            FrameAccess.writeLong(frame, slots.fields[0], (Long) frame.getArguments()[0]);
            TypedInputs.writeInputReference(frame, slots.fields[1], frame.getArguments()[1]);
            return dispatch.execute(frame, function);
        }
    }
    @Test void actualDeoptimizationAfterPartialRestoreClearsFreshReferenceWithoutPoolLoan() throws ReflectiveOperationException {
        withLanguage(language -> {
            for (int action : new int[]{1, 2}) {
                var layout = input(language);
                var target = new Caller(language, layout, new Receiver(language, layout).getCallTarget()).getCallTarget();
                var bottom = new RootNode(language) { @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Lifted input was forced"); } };
                var pointer = new Thunk(bottom.getCallTarget(), null); Barrier.action = 0;
                for (int i = 0; i < 3; i++) { assertSame(pointer, target.call(4097L, pointer)); clear(language); }
                compile(target); long beforeNormal = Barrier.materializedFresh;
                assertSame(pointer, target.call(4097L, pointer)); assertTrue(valid(target)); clear(language);
                assertEquals(beforeNormal, Barrier.materializedFresh, "Normal compiled restore must not use the interpreter path");
                long before = Barrier.materializedFresh, cleared = Barrier.clearedFresh, observations = Barrier.observations;
                Barrier.action = action;
                try {
                    if (action == 1) assertSame(pointer, target.call(4097L, pointer));
                    else assertThrows(GuestException.class, () -> target.call(4097L, pointer));
                } finally { Barrier.action = 0; }
                assertEquals(observations + 1, Barrier.observations, "Observer must not replay after deoptimization");
                assertEquals(before + 1, Barrier.materializedFresh, "Actual fresh carrier must reach interpreter restore");
                assertEquals(cleared + 1, Barrier.clearedFresh);
                clear(language); // Failure cleanup is checked before recovery can conceal retained fields.
                assertSame(pointer, target.call(4097L, pointer)); clear(language);
            }
        });
    }
    @Test void staleCallerCleanupCannotReleaseReusedInputOrIndependentOutputLoan() throws ReflectiveOperationException {
        withLanguage(language -> {
            var layout = input(language); var state = language.getHandoffState().get();
            var old = state.getArguments().acquire(layout.getPacket()); old.setInputMode(1);
            long oldGeneration = old.getGeneration(); layout.getPacket().setObject(old, layout.getHeader() + 1, new Object()); layout.release(old);
            var next = state.getArguments().acquire(layout.getPacket()); next.setInputMode(1);
            var pointer = new Object(); layout.getPacket().setObject(next, layout.getHeader() + 1, pointer);
            var output = state.getResults().acquire(layout.getPacket()); var outputPointer = new Object();
            layout.getPacket().setObject(output, layout.getHeader() + 1, outputPointer);
            assertNotSame(next, output); assertSame(old, next); assertNotEquals(oldGeneration, next.getGeneration());
            layout.releaseIfOwned(old, oldGeneration);
            assertEquals(1, state.getArguments().getDepth()); assertSame(pointer, layout.getPacket().getObject(next, layout.getHeader() + 1));
            layout.release(next); assertEquals(0, state.getArguments().getDepth()); assertEquals(1, state.getResults().getDepth());
            assertSame(outputPointer, layout.getPacket().getObject(output, layout.getHeader() + 1));
            state.getResults().release(output, layout.getPacket()); clear(language);
        });
    }
}
