// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExecutionSignature;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

class JoinReferenceTransferTest {
    private static final class State {
        int reads;
        int compiled;
        MaterializedFrame frame;
    }
    private static final class Input extends Expr {
        private final State state;
        Input(State state, CoreRepresentation proof) {
            this.state = state;
            setRepresentation(proof);
        }
        @Override public Object execute(VirtualFrame frame) {
            if (frame != state.frame) throw new AssertionError("changed borrowed frame");
            state.reads++;
            if (CompilerDirectives.inCompiledCode()) state.compiled++;
            return frame.getArguments()[0];
        }
    }
    private static final class Owner extends ContextRoot {
        @Child private LocalJoinCall call;
        private final LocalJoinTarget destination;
        Owner(Language language, LocalJoinCall call, LocalJoinTarget destination) {
            super(language, FrameDescriptor.newBuilder().build());
            this.call = call;
            this.destination = destination;
        }
        @Override protected ExecutionSignature prepareForAOT() { return ExecutionSignature.GENERIC; }
        @Override public Object execute(VirtualFrame incoming) {
            var frame = (MaterializedFrame) incoming.getArguments()[0];
            try {
                call.execute(frame);
                throw new AssertionError("join returned");
            } catch (LocalJoinJump jump) {
                if (jump != destination.getJump()) throw new AssertionError("changed jump identity");
                return frame.getObject(1);
            }
        }
    }
    private static Context context(boolean compilation) {
        var builder = Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.SingleTierCompilationThreshold", "10000000")
            .option("engine.CompilationFailureAction", "Throw").option("compiler.DiagnoseFailure", "false")
            .option("compiler.MaximumGraalGraphSize", "1000");
        if (!compilation) builder.option("engine.Compilation", "false");
        return builder.build();
    }
    private static CoreRepresentation proof(int kind) {
        CoreKind reference = switch (kind) {
            case 0 -> CoreKind.DATA;
            case 1 -> CoreKind.CLOSURE;
            default -> CoreKind.ADDRESS;
        };
        return new CoreRepresentation(reference, true, true, List.of(kind == 2 ? "AddrRep" : "BoxedRep (Just Lifted)"), null, null, null, null, null);
    }
    private static Object value(Language language, int kind) {
        return switch (kind) {
            case 0 -> new DataLayout(language, "JoinData", "JoinData", new String[0]).create(new Object[0]);
            case 1 -> new Closure(null, 0, RootNode.createConstantNode(1L).getCallTarget());
            default -> ManagedAddress.nullAddress();
        };
    }
    private static MaterializedFrame frame(Object input, boolean primitiveFormal) {
        var builder = FrameDescriptor.newBuilder();
        builder.addSlot(FrameSlotKind.Object, "staging", null);
        builder.addSlot(primitiveFormal ? FrameSlotKind.Long : FrameSlotKind.Object, "formal", null);
        var frame = Truffle.getRuntime().createMaterializedFrame(new Object[]{input}, builder.build());
        if (primitiveFormal) frame.setLong(1, 19L);
        else frame.setObject(1, new Object());
        return frame;
    }
    private static void compile(RootCallTarget target) throws Exception {
        var optimized = (OptimizedCallTarget) target;
        assertFalse(optimized.wasExecuted());
        optimized.prepareForAOT();
        assertTrue(optimized.compile(true));
        assertTrue(optimized.isValidLastTier());
        var runtime = Truffle.getRuntime();
        runtime.getClass().getMethod("bypassedInstalledCode", OptimizedCallTarget.class).invoke(runtime, optimized);
    }
    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void firstCompiledReferenceTransferPreservesIdentityAndWidening(int mode) throws Exception {
        int kind = mode % 3;
        boolean widen = mode >= 3;
        try (var context = context(true)) {
            context.initialize("thc");
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var proof = proof(kind);
                var state = new State();
                var marker = value(language, kind);
                var destination = new LocalJoinTarget(new Object(), 1, new int[]{1}, new CoreRepresentation[]{proof});
                var call = new LocalJoinCall(language, destination, new Expr[]{new Input(state, proof)}, new int[]{0}, new Metrics(false));
                var owner = new Owner(language, call, destination);
                var target = (OptimizedCallTarget) owner.getCallTarget();
                var frame = frame(marker, widen);
                state.frame = frame;
                compile(target);
                assertEquals(0, state.reads);
                assertSame(marker, Calls.target(target, new Object[]{frame}));
                assertSame(marker, frame.getObject(1));
                assertEquals(FrameSlotKind.Object, frame.getFrameDescriptor().getSlotKind(1));
                assertEquals(FrameSlotKind.Illegal.tag, frame.getTag(0), "staging cleared only after formal publication");
                assertEquals(1, state.reads);
                assertEquals(1, state.compiled);
                assertSame(owner, call.getRootNode());
                if (!widen) assertTrue(target.isValidLastTier());
                else assertFalse(target.isValidLastTier(), "existing descriptor widening invalidation remains");
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @ValueSource(ints = {0, 1, 2})
    void invalidStagedReferenceIsRejectedBeforePublication(int kind) throws Exception {
        try (var context = context(false)) {
            context.initialize("thc");
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var proof = proof(kind);
                var state = new State();
                var destination = new LocalJoinTarget(new Object(), 1, new int[]{1}, new CoreRepresentation[]{proof});
                var call = new LocalJoinCall(language, destination, new Expr[]{new Input(state, proof)}, new int[]{0}, new Metrics(false));
                new Owner(language, call, destination).getCallTarget();
                var frame = frame(null, false);
                Object previous = frame.getObject(1);
                frame.setObject(0, 42L);
                var transfer = LocalJoinCall.class.getDeclaredMethod("transfer", VirtualFrame.class);
                transfer.setAccessible(true);
                var failure = assertThrows(InvocationTargetException.class, () -> transfer.invoke(call, frame));
                var fault = assertInstanceOf(RuntimeFault.class, failure.getCause());
                assertEquals(switch (kind) {
                    case 0 -> "Expected constructor join argument";
                    case 1 -> "Expected closure join argument";
                    default -> "Expected address join argument";
                }, fault.getMessage());
                assertSame(previous, frame.getObject(1));
                assertEquals(42L, frame.getObject(0));
                assertEquals(0, state.reads);
            } finally { context.leave(); }
        }
    }
}
