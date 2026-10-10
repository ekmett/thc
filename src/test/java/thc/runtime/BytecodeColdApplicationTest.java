// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.dsl.UnsupportedSpecializationException;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import thc.Language;

import static org.junit.jupiter.api.Assertions.*;

class BytecodeColdApplicationTest {
    @FunctionalInterface
    private interface LanguageAction {
        void run(Language language) throws Exception;
    }

    private static void withLanguage(LanguageAction action) throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc");
            context.enter();
            try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); }
            finally { context.leave(); }
        }
    }

    private static void compile(RootCallTarget target) throws Exception {
        var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
        type.getMethod("compile", boolean.class).invoke(target, true);
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
        var runtime = Truffle.getRuntime();
        runtime.getClass().getMethod("bypassedInstalledCode", type).invoke(runtime, target);
    }

    private static void valid(RootCallTarget target) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
    }

    private static RootCallTarget target(Language language, Function<VirtualFrame, Object> body) {
        return new RootNode(language) {
            @Override public Object execute(VirtualFrame frame) { return body.apply(frame); }
        }.getCallTarget();
    }

    private static BytecodeRoot root(Language language, Object closure, Metrics metrics) throws Exception {
        return root(language, closure, metrics, false);
    }

    private static BytecodeRoot root(Language language, Object closure, Metrics metrics, boolean checkpoint) throws Exception {
        return root(language, closure, metrics, checkpoint, null, () -> {});
    }

    private static BytecodeRoot root(Language language, Object closure, Metrics metrics,
                                     boolean checkpoint, ArgumentLayout layout, Runnable sourceRead) throws Exception {
        RootCallTarget coldTarget = PreparedDispatch.prepareApplication(language,
            layout == null ? 1 : layout.getLogicalArity(), false, metrics, layout, false, true, checkpoint, null);
        compile(coldTarget);
        var root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
            if (b.isParsingSources()) {
                sourceRead.run();
                b.beginSource(Source.newBuilder("thc", "call f 42", "ColdApply.hs").build());
                b.beginSourceSection(0, 9);
            }
            b.beginRoot();
            b.emitEnterRoot(metrics);
            b.beginReturn();
            if (layout == null) b.beginApply(1, false, metrics, new boolean[]{true}, coldTarget);
            else {
                var strict = new boolean[layout.getLogicalArity()];
                Arrays.fill(strict, true);
                b.beginApplyCompact(layout, false, metrics, strict, coldTarget);
            }
            if (closure == null) b.emitLoadNull(); else b.emitLoadConstant(closure);
            b.emitLoadConstant(42L);
            if (layout == null) b.endApply(); else b.endApplyCompact();
            b.endReturn();
            b.endRoot();
            if (b.isParsingSources()) { b.endSourceSection(); b.endSource(); }
        }).getNode(0);
        // Use the production capture policy, not the factoring lane's private hook.
        root.configureDelimited(checkpoint);
        return root;
    }

    private record InstructionShape(String name, List<String> arguments) {}

    private static List<InstructionShape> code(BytecodeRoot root) {
        var result = new ArrayList<InstructionShape>();
        for (var instruction : root.getBytecodeNode().getInstructions()) {
            var arguments = new ArrayList<String>();
            for (var argument : instruction.getArguments()) arguments.add(argument.toString());
            result.add(new InstructionShape(instruction.getName(), arguments));
        }
        return result;
    }

    @Test void branchFreeApplyEntersItsFirstInstalledCallWithEitherCapturePolicy() throws Exception {
        withLanguage(language -> {
            for (boolean checkpoint : new boolean[]{false, true}) {
                int[] calls = {0};
                var closure = new Closure(null, 1, target(language, frame -> {
                    calls[0]++; return frame.getArguments()[1];
                }));
                var metrics = new Metrics(true);
                var root = root(language, closure, metrics, checkpoint);
                compile(root.getCallTarget());
                assertEquals(0, calls[0]); assertEquals(0L, metrics.getCompiledEntries());
                assertEquals(42L, Calls.target(root.getCallTarget(), new Object[]{0L}));
                assertEquals(1, calls[0]); assertEquals(2L, metrics.getCompiledEntries());
                valid(root.getCallTarget());
            }
        });
    }

    @Test void compactEmptyTupleSourceReplayAndCloneKeepIndependentColdChildren() throws Exception {
        withLanguage(language -> {
            var layout = Objects.requireNonNull(ArgumentLayout.fromProofs(List.of(
                    new CoreRepresentation(CoreKind.UNKNOWN, false, true, List.of(), List.of(),
                            null, null, null, null),
                    new CoreRepresentation(CoreKind.LONG, false, false, null, null,
                            null, null, null, null))));
            int[] calls = {0};
            int[] sources = {0};
            var callee = new GuestRoot(language, FrameDescriptor.newBuilder().build()) {
                @Override public long bloom(VirtualFrame frame) { return 0L; }
                @Override public Object execute(VirtualFrame frame) {
                    calls[0]++; assertEquals(2, frame.getArguments().length); return frame.getArguments()[1];
                }
            };
            callee.configureInput$org_intelligence_thc(layout);
            var closure = new Closure(null, 2, callee.getCallTarget());
            var metrics = new Metrics(true);
            var root = root(language, closure, metrics, true, layout, () -> sources[0]++);
            compile(root.getCallTarget());
            assertEquals(0, calls[0]); assertEquals(0, sources[0]);
            assertEquals(42L, Calls.target(root.getCallTarget(), new Object[]{0L}));
            assertEquals(2L, metrics.getCompiledEntries()); valid(root.getCallTarget());
            var before = code(root);
            root.getBytecodeNode().ensureSourceInformation();
            assertEquals(1, sources[0]); assertEquals(before, code(root));
            assertEquals(42L, Calls.target(root.getCallTarget(), new Object[]{0L}));
            assertEquals(4L, metrics.getCompiledEntries()); valid(root.getCallTarget());
            var cloneMethod = root.getClass().getDeclaredMethod("cloneUninitialized");
            cloneMethod.setAccessible(true);
            var clone = (BytecodeRoot) cloneMethod.invoke(root);
            assertNotSame(root, clone);
            compile(clone.getCallTarget());
            assertEquals(2, calls[0]);
            assertEquals(42L, Calls.target(clone.getCallTarget(), new Object[]{0L}));
            assertEquals(3, calls[0]); assertEquals(6L, metrics.getCompiledEntries());
            valid(clone.getCallTarget()); valid(root.getCallTarget());
        });
    }

    @Test void firstInstalledGenericApplicationReturnsItsLazyResultWithoutDemand() throws Exception {
        withLanguage(language -> {
            for (boolean checkpoint : new boolean[]{false, true}) {
                int[] demands = {0};
                Thunk lazy = new Thunk(target(language, frame -> { demands[0]++; return 71L; }), null);
                Closure function = new Closure(null, 1, target(language, frame -> lazy));
                Metrics metrics = new Metrics(true);
                BytecodeRoot caller = root(language, function, metrics, checkpoint);
                compile(caller.getCallTarget());
                assertEquals(0, demands[0]);
                assertSame(lazy, Calls.target(caller.getCallTarget(), new Object[]{0L}));
                assertEquals(0, demands[0]); assertEquals(0, lazy.getState());
                assertEquals(2L, metrics.getCompiledEntries()); valid(caller.getCallTarget());
            }
        });
    }

    @Test void coldPapDoesNotEnterItsUnsaturatedFunction() throws Exception {
        withLanguage(language -> {
            int[] calls = {0};
            var closure = new Closure(null, 2, target(language, frame -> {
                calls[0]++; throw new IllegalStateException("PAP entered");
            }));
            var metrics = new Metrics(true);
            var root = root(language, closure, metrics, true);
            compile(root.getCallTarget());
            var pap = (Closure) Calls.target(root.getCallTarget(), new Object[]{0L});
            assertEquals(0, calls[0]); assertEquals(1, pap.arity); assertSame(closure.target, pap.target);
            assertArrayEquals(new Object[]{42L}, pap.supplied);
            assertEquals(2L, metrics.getCompiledEntries()); valid(root.getCallTarget());
        });
    }

    @Test void coldZeroArityPrefixKeepsTheRemainingArgumentAndRunsOnce() throws Exception {
        withLanguage(language -> {
            int[] first = {0};
            int[] last = {0};
            var finish = new Closure(null, 1, target(language, frame -> {
                last[0]++; return frame.getArguments()[1];
            }));
            var closure = new Closure(null, 0, target(language, frame -> { first[0]++; return finish; }));
            var metrics = new Metrics(true);
            var root = root(language, closure, metrics, true);
            compile(root.getCallTarget());
            assertEquals(0, first[0]); assertEquals(0, last[0]);
            assertEquals(42L, Calls.target(root.getCallTarget(), new Object[]{0L}));
            assertEquals(1, first[0]); assertEquals(1, last[0]);
            assertEquals(2L, metrics.getCompiledEntries()); valid(root.getCallTarget());
            var cloneMethod = root.getClass().getDeclaredMethod("cloneUninitialized");
            cloneMethod.setAccessible(true);
            var interpreted = (BytecodeRoot) cloneMethod.invoke(root);
            assertEquals(42L, Calls.target(interpreted.getCallTarget(), new Object[]{0L}));
            assertEquals(2, first[0]); assertEquals(2, last[0]);
            assertEquals(3L, metrics.getCompiledEntries(), "An interpreter overapplication retains the same helper-owned suffix");
        });
    }

    @Test void ordinaryInterpreterCallsStillPopulateTheObservedTargetPic() throws Exception {
        withLanguage(language -> {
            int[] calls = {0};
            var closure = new Closure(null, 1, target(language, frame -> {
                calls[0]++; return frame.getArguments()[1];
            }));
            var metrics = new Metrics(true);
            var root = root(language, closure, metrics);
            assertEquals(42L, Calls.target(root.getCallTarget(), new Object[]{0L}));
            assertEquals(1, calls[0]); assertEquals(0L, metrics.getCompiledEntries());
            assertEquals(0L, metrics.getIndirectCalls());
            compile(root.getCallTarget());
            assertEquals(42L, Calls.target(root.getCallTarget(), new Object[]{0L}));
            assertEquals(2, calls[0]); assertEquals(0L, metrics.getIndirectCalls());
            assertEquals(1L, metrics.getCompiledEntries()); valid(root.getCallTarget());
        });
    }

    @Test void invalidFunctionDoesNotDispatchACall() throws Exception {
        withLanguage(language -> {
            for (Object invalid : new Object[]{null, 7L}) {
                var metrics = new Metrics(true);
                var root = root(language, invalid, metrics);
                compile(root.getCallTarget());
                if (invalid == null) assertThrows(UnsupportedSpecializationException.class,
                        () -> Calls.target(root.getCallTarget(), new Object[]{0L}));
                else assertThrows(ClassCastException.class,
                        () -> Calls.target(root.getCallTarget(), new Object[]{0L}));
                assertEquals(0L, metrics.getIndirectCalls());
            }
        });
    }

    private static void missing(Executable action) {
        var failure = assertThrows(NullPointerException.class, action);
        assertEquals(NullPointerException.class, failure.getClass());
    }

    @Test void outlinedNullChecksRetainTypesAndNonNullIdentity() {
        Object[] values = {new Object(), null};
        assertSame(values, ColdCallChecks.values(values));
        var target = new RootNode(null) {
            @Override public Object execute(VirtualFrame frame) { return null; }
        }.getCallTarget();
        var transfer = new TailCall(target, values, null);
        assertSame(target, ColdCallChecks.target(target));
        assertSame(transfer, ColdCallChecks.transfer(transfer));
        missing(() -> ColdCallChecks.values(null));
        missing(() -> ColdCallChecks.typedInput(null));
        missing(() -> ColdCallChecks.guestRoot(null));
        missing(() -> ColdCallChecks.target(null));
        missing(() -> ColdCallChecks.transfer(null));
        assertThrows(ClassCastException.class, () -> ColdCallChecks.target(new Object()));
        assertThrows(ClassCastException.class, () -> ColdCallChecks.transfer(new Object()));
        assertThrows(ClassCastException.class, () -> ColdCallChecks.guestRoot(target.getRootNode()));
        assertEquals(2, values.length);
        assertNull(values[1]);
    }
}
