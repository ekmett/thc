// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.*;
import com.oracle.truffle.api.instrumentation.*;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.Language;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LocalReadLongFallbackTest {
    private static final class State { int prefix, compiled, completed; UnexpectedResultException caught; }
    private static final class Owner extends ContextRoot {
        @Child private Expr read;
        private final State state;
        Owner(Language language, LocalRead read, State state) {
            super(language, FrameDescriptor.newBuilder().build()); this.read = read; this.state = state;
        }
        @Override public String getName() { return "LocalRead long fallback"; }
        @Override protected ExecutionSignature prepareForAOT() { return ExecutionSignature.GENERIC; }
        @Override public com.oracle.truffle.api.source.SourceSection getSourceSection() {
            var location = read.getCoreSourceLocation(); return location == null ? null : location.getSection();
        }
        @Override public Object execute(VirtualFrame callFrame) {
            state.prefix++; if (CompilerDirectives.inCompiledCode()) state.compiled++;
            try { long result = read.executeLong((MaterializedFrame) callFrame.getArguments()[0]); state.completed++; return result; }
            catch (UnexpectedResultException unexpected) { state.caught = unexpected; return unexpected; }
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
    private static void compile(RootCallTarget target) throws Exception {
        var optimized = (OptimizedCallTarget) target;
        assertFalse(optimized.wasExecuted()); optimized.prepareForAOT();
        assertTrue(optimized.compile(true)); assertTrue(optimized.isValidLastTier());
        Truffle.getRuntime().getClass().getMethod("bypassedInstalledCode", OptimizedCallTarget.class).invoke(Truffle.getRuntime(), optimized);
    }
    @Test void firstPrimitiveReadRetainsInstalledCaller() throws Exception { check(0); }
    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9})
    void boxedCellsAndFailuresPreserveReadContract(int mode) throws Exception { check(mode); }
    private static void check(int mode) throws Exception {
        try (var context = context(true)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var builder = FrameDescriptor.newBuilder();
                builder.addSlot(mode == 1 ? FrameSlotKind.Long : FrameSlotKind.Object, "value", null);
                var descriptor = builder.build();
                var frame = Truffle.getRuntime().createMaterializedFrame(new Object[0], descriptor);
                Object marker = new Object(); RecCell cell = null;
                if (mode == 0 || mode == 1) frame.setLong(0, 0x123456789abcdefL);
                else if (mode == 2) frame.setObject(0, Long.valueOf(0x123456789abcdefL));
                else if (mode == 3 || mode == 5 || mode == 6) {
                    cell = new RecCell(); cell.setValue(mode == 5 ? marker : Long.valueOf(0x123456789abcdefL));
                    cell.setInitialized(mode != 6); frame.setObject(0, cell);
                } else if (mode == 4) frame.setObject(0, marker);
                else if (mode == 8) frame.setInt(0, 17);
                else frame.setObject(0, null);
                var state = new State();
                var read = new LocalRead(0, mode == 3 || mode == 5 || mode == 6 || mode == 7,
                    mode == 1 ? FrameSlotKind.Long : null);
                var owner = new Owner(language, read, state); var target = (OptimizedCallTarget) owner.getCallTarget();
                compile(target); assertEquals(0, state.prefix);
                if (mode == 6 || mode == 7) {
                    var failure = assertThrows(RuntimeFault.class, () -> Calls.target(target, new Object[]{frame}));
                    assertEquals(mode == 6 ? "Recursive binding read before initialization" : "Uninitialized local binding", failure.getMessage());
                } else {
                    Object result = Calls.target(target, new Object[]{frame});
                    if (mode <= 3) {
                        assertEquals(0x123456789abcdefL, result); assertEquals(1, state.completed);
                        assertTrue(target.isValidLastTier(), "first valid typed read must preserve installed caller");
                    } else {
                        assertSame(state.caught, result);
                        Object wrong = ((UnexpectedResultException) result).getResult();
                        if (mode == 4 || mode == 5) assertSame(marker, wrong);
                        else if (mode == 8) assertEquals(17, wrong);
                        else assertNull(wrong);
                    }
                }
                assertEquals(1, state.prefix); assertEquals(1, state.compiled);
                assertSame(owner, read.getRootNode());
                if (cell != null) { assertSame(cell, frame.getObject(0)); assertEquals(mode != 6, cell.getInitialized()); }
                if (mode == 0) assertEquals(FrameSlotKind.Object, descriptor.getSlotKind(0), "fast activation tag must work with a widened descriptor");
            } finally { context.leave(); }
        }
    }
    @Test void boxedReadPreservesOriginalSourceEvent() throws Exception {
        try (var context = context(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var source = Source.newBuilder("thc", "x", "LocalRead.hs").build();
                var read = new LocalRead(0, false); read.located(new CoreSourceLocation(source.createSection(0, 1), List.of()));
                var state = new State(); var owner = new Owner(language, read, state);
                var builder = FrameDescriptor.newBuilder(); builder.addSlot(FrameSlotKind.Object, "value", null);
                var frame = Truffle.getRuntime().createMaterializedFrame(new Object[0], builder.build()); frame.setObject(0, 42L);
                int[] events = {0};
                var attachment = context.getEngine().getInstruments().get("thc-activation-test").lookup(AstInstrumentationTest.Attachment.class);
                var binding = attachment.attachStatements(source, new ExecutionEventListener() {
                    @Override public void onEnter(EventContext event, VirtualFrame actual) { assertSame(frame, actual); events[0]++; }
                    @Override public void onReturnValue(EventContext event, VirtualFrame actual, Object value) { assertEquals(42L, value); }
                    @Override public void onReturnExceptional(EventContext event, VirtualFrame actual, Throwable failure) { fail(failure); }
                });
                try {
                    assertEquals(42L, Calls.target(owner.getCallTarget(), new Object[]{frame}));
                    assertEquals(1, events[0]); assertEquals(1, state.prefix); assertEquals(1, state.completed);
                    assertSame(owner, read.getRootNode());
                } finally { binding.dispose(); }
            } finally { context.leave(); }
        }
    }

    private static final class OwnFrameRoot extends ContextRoot {
        @Child private Expr read = new LocalRead(0, false);
        private final State state;
        OwnFrameRoot(Language language, State state) { super(language, descriptor()); this.state = state; }
        private static FrameDescriptor descriptor() {
            var builder = FrameDescriptor.newBuilder();
            builder.addSlot(FrameSlotKind.Object, "boxed", null);
            return builder.build();
        }
        @Override protected ExecutionSignature prepareForAOT() { return ExecutionSignature.GENERIC; }
        @Override public Object execute(VirtualFrame frame) {
            state.prefix++;
            if (CompilerDirectives.inCompiledCode()) state.compiled++;
            frame.setObject(0, frame.getArguments()[0]);
            try {
                long result = read.executeLong(frame);
                state.completed++;
                return result;
            } catch (UnexpectedResultException unexpected) { throw new AssertionError(unexpected); }
        }
    }
    @Test void firstBoxedReadUsesOwnFreshFrame() throws Exception {
        try (var context = context(true)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var state = new State(); var root = new OwnFrameRoot(language, state);
                var target = (OptimizedCallTarget) root.getCallTarget();
                compile(target);
                assertEquals(0, state.prefix);
                assertEquals(0x123456789abcdefL, Calls.target(target, new Object[]{Long.valueOf(0x123456789abcdefL)}));
                assertEquals(1, state.prefix); assertEquals(1, state.compiled); assertEquals(1, state.completed);
                assertTrue(target.isValidLastTier());
            } finally { context.leave(); }
        }
    }
}
