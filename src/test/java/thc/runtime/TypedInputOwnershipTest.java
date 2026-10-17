// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import java.util.List;
import java.util.Objects;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Adversarial lifetime controls for the shared typed input protocol. */
class TypedInputOwnershipTest {
    @FunctionalInterface private interface Action { void run(Language language) throws ReflectiveOperationException; }
    private void withLanguage(Action action) throws ReflectiveOperationException { withLanguage(true, action); }
    private void withLanguage(boolean inlining, Action action) throws ReflectiveOperationException {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
                .option("compiler.Inlining", Boolean.toString(inlining)).build()) {
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
        @CompilationFinal(dimensions = 1) final int[] fields;
        Slots() { this(false); }
        Slots(boolean strict) {
            fields = strict ? new int[]{layout.bind("number"), layout.bind("lazy pointer"), layout.bind("strict pointer")}
                : new int[]{layout.bind("number"), layout.bind("lazy pointer")};
        }
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
    private static final class TailCaller extends GuestRoot {
        private final Slots slots;
        private final Closure function;
        @Child private InputDispatch dispatch;
        @Child private GenericInputCall generic;
        @Child private TailCallLoop trampoline;
        TailCaller(Language language, TypedInputLayout input, RootCallTarget target, Metrics metrics) {
            this(language, input, target, metrics, new Slots(input.getLeaves().length == 3));
        }
        TailCaller(Language language, TypedInputLayout input, RootCallTarget target, Metrics metrics, boolean dynamic) {
            this(language, input, target, metrics);
            if (dynamic) generic = new GenericInputCall(new AstInputSource(input.getLogical(), slots.fields), input.getLogical().getLogicalArity(), true, metrics, null, 0);
        }
        private TailCaller(Language language, TypedInputLayout input, RootCallTarget target, Metrics metrics, Slots slots) {
            super(language, slots.layout.build()); this.slots = slots;
            int arity = input.getLogical().getLogicalArity();
            function = new Closure(null, arity, target);
            dispatch = new InputDispatch(new AstInputSource(input.getLogical(), slots.fields), arity, true, metrics);
            trampoline = new TailCallLoop(metrics);
        }
        @Override public long bloom(VirtualFrame frame) { return (Long) frame.getArguments()[0]; }
        @Override public Object execute(VirtualFrame frame) {
            FrameAccess.writeLong(frame, slots.fields[0], 4097L);
            TypedInputs.writeInputReference(frame, slots.fields[1], frame.getArguments()[1]);
            if (slots.fields.length == 3) TypedInputs.writeInputReference(frame, slots.fields[2], frame.getArguments()[1]);
            try { return generic == null ? dispatch.execute(frame, function)
                : generic.execute(frame, (Closure) frame.getArguments()[2], null); }
            catch (TailCall tail) { return trampoline.execute(tail); }
        }
    }
    @Test void strictRawAncestryAndUnknownTypedTargetsRetainTailBounces() throws ReflectiveOperationException {
        withLanguage(false, language -> {
            for (var kind : List.of("strict", "raw", "ancestry", "unknown", "dynamic")) {
                var input = input(language);
                boolean strict = kind.equals("strict");
                if (strict) input = new TypedInputLayout(language, Objects.requireNonNull(ArgumentLayout.fromProofs(List.of(
                    input.getLogical().proof(0), input.getLeaves()[1]))), false);
                var slots = new Slots(strict); var metrics = new Metrics(true);
                GuestRoot root;
                if (kind.equals("unknown")) root = new Receiver(language, input);
                else if (kind.equals("raw")) {
                    root = FunctionRoot.application(language, "raw typed tail", new LocalRead(FrameLayout.TAIL_ARGUMENTS, false), metrics, false, false);
                    root.configureInput(input.getLogical()); root.configureTypedInput(input);
                } else {
                    Expr body = new LocalRead(kind.equals("ancestry") ? FrameLayout.BLOOM_FILTER : slots.fields[1], false);
                    root = new FunctionRoot(language, slots.layout.build(), "typed tail " + kind, null,
                        new int[0], slots.fields, strict ? new int[]{0, 1, 2} : new int[]{0, 1}, body, metrics, input.getLeaves(), CoreRepresentation.UNKNOWN, null,
                        strict ? new boolean[]{false, true} : new boolean[]{false}, null, null, new int[0], input.getLogical(), false,
                        new int[0][], false, FunctionRootRole.FUNCTION, true);
                    root.configureTypedInput(input);
                }
                var target = root.getCallTarget(); var closure = new Closure(null, input.getLogical().getLogicalArity(), target);
                var caller = (OptimizedCallTarget) new TailCaller(language, input, target, metrics, kind.equals("dynamic")).getCallTarget();
                var pointer = new Object(); Barrier.action = 0;
                caller.call(0L, pointer, closure); clear(language);
                caller.compile(true); caller.waitForCompilation(); assertTrue(caller.isValidLastTier(), kind);
                long bounces = metrics.getTailBounces();
                Object result = caller.call(-1L, pointer, closure);
                if (kind.equals("ancestry")) assertEquals(root.mask, result, "A real bounce resets inherited ancestry");
                else assertSame(pointer, result, kind);
                assertEquals(bounces + 1, metrics.getTailBounces(), kind);
                clear(language);
            }
        });
    }
    @Test void compiledLeafTailRetainsLazyValueThenInvalidatesUninlinedCallerOnReplacement() throws ReflectiveOperationException {
        withLanguage(false, language -> {
            var input = input(language); var slots = new Slots(); var metrics = new Metrics(true);
            var local = new LocalRead(slots.fields[1], false);
            var root = new FunctionRoot(language, slots.layout.build(), "typed tail leaf", null,
                new int[0], slots.fields, new int[]{0, 1}, local, metrics,
                input.getLeaves(), CoreRepresentation.UNKNOWN, null, new boolean[]{false}, null, null,
                new int[0], input.getLogical(), false, new int[0][], false, FunctionRootRole.FUNCTION, true);
            root.configureTypedInput(input);
            var callee = (OptimizedCallTarget) root.getCallTarget();
            var caller = (OptimizedCallTarget) new TailCaller(language, input, callee, metrics).getCallTarget();
            var pointer = new Thunk(new RootNode(language) {
                @Override public Object execute(VirtualFrame frame) { throw new AssertionError("Lazy capture was forced"); }
            }.getCallTarget(), null);
            assertSame(pointer, caller.call(0L, pointer)); clear(language);
            callee.compile(true); callee.waitForCompilation(); assertTrue(callee.isValidLastTier());
            caller.compile(true); caller.waitForCompilation(); assertTrue(caller.isValidLastTier());
            long bounces = metrics.getTailBounces(); long address = caller.getCodeAddress();
            assertSame(pointer, caller.call(-1L, pointer));
            assertEquals(bounces, metrics.getTailBounces(), "First compiled leaf call must not bounce on a bloom collision");
            assertTrue(caller.isValidLastTier()); assertEquals(address, caller.getCodeAddress()); clear(language);
            var copied = (FunctionRoot) NodeUtil.cloneNode(root);
            var copiedCaller = (OptimizedCallTarget) new TailCaller(language, input, copied.getCallTarget(), new Metrics(false)).getCallTarget();
            assertSame(pointer, copiedCaller.call(0L, pointer));
            copiedCaller.compile(true); copiedCaller.waitForCompilation(); assertTrue(copiedCaller.isValidLastTier());
            var reentries = new int[1];
            var nested = new GuestRoot(language, new FrameLayout().build()) {
                @Override public long bloom(VirtualFrame frame) { return 0L; }
                @Override public Object execute(VirtualFrame frame) { reentries[0]++; return frame.getArguments()[0]; }
            }.getCallTarget();
            Expr reentrant = local.replace(new Expr() {
                @Override public Object execute(VirtualFrame frame) { return Calls.target(nested, new Object[]{frame.getObject(slots.fields[1])}); }
            }, null);
            assertFalse(callee.isValidLastTier()); assertFalse(caller.isValidLastTier());
            assertTrue(copiedCaller.isValidLastTier(), "A different root copy owns a different proof epoch");
            assertSame(pointer, caller.call(-1L, pointer)); assertEquals(1, reentries[0]);
            assertEquals(bounces + 1, metrics.getTailBounces()); clear(language);
            assertSame(pointer, copiedCaller.call(-1L, pointer)); assertTrue(copiedCaller.isValidLastTier()); clear(language);
            reentrant.replace(new LocalRead(slots.fields[1], false));
            callee.compile(true); callee.waitForCompilation(); assertTrue(callee.isValidLastTier());
            caller.compile(true); caller.waitForCompilation(); assertTrue(caller.isValidLastTier());
            bounces = metrics.getTailBounces(); address = caller.getCodeAddress();
            assertSame(pointer, caller.call(-1L, pointer)); assertEquals(bounces, metrics.getTailBounces());
            assertEquals(1, reentries[0]); assertTrue(caller.isValidLastTier()); assertEquals(address, caller.getCodeAddress()); clear(language);
        });
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
