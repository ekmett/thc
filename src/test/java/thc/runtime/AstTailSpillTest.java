// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import java.lang.management.ManagementFactory;
import com.sun.management.HotSpotDiagnosticMXBean;
import java.util.List;
import java.util.Objects;
import static org.junit.jupiter.api.Assertions.*;

class AstTailSpillTest {
    private final CoreRepresentation proof = new CoreRepresentation(CoreKind.LONG, true, true, List.of("IntRep"), null, null, null, null, null);
    @Test void longIdentitySideChainRetainsOneLiveAnchorAndNeverReplaysItsPrefix() throws Exception { chain(false, -1, false, false, false, false, CoreKind.LONG, true, true); }
    @Test void retainedNonTailCallerRunsItsSuffixOnceAfterTheCompactedLoop() throws Exception { chain(true, -1, false, false, false, false, CoreKind.LONG, true, true); }
    @Test void savedMaskCleanupUnwindsBeforeTheMatchingTailAnchor() throws Exception { chain(false, 256, false, false, false, false, CoreKind.LONG, true, true); }
    @Test void maskAlreadyLiveAtFirstSpillRetainsTheExactOuterCatcher() throws Exception { chain(false, 16, false, false, false, false, CoreKind.LONG, true, true); }
    @Test void firstInstalledEntrySpillsWithoutASettlingCall() throws Exception { chain(false, -1, true, false, false, false, CoreKind.LONG, true, true); }
    @Test void sharedUpdatePublishesOnlyTheCompletedCompactedResult() throws Exception { chain(false, -1, false, true, false, false, CoreKind.LONG, true, true); }
    @Test void failedSharedUpdateKeepsItsFailureWithoutReplayingTheCompactedPrefix() throws Exception { chain(false, 16, false, true, true, false, CoreKind.LONG, true, true); }
    @Test void savedCatchKeepsTheOriginalUnforcedPayloadAndMaskCleanup() throws Exception { chain(false, 16, false, false, false, true, CoreKind.LONG, true, true); }
    @Test void constructorIdentityAndLazyFieldSurviveFirstInstalledOutlinedSpill() throws Exception { chain(false, -1, true, false, false, false, CoreKind.DATA, true, true); }
    @Test void closureIdentityAndUnenteredBodySurviveFirstInstalledOutlinedSpill() throws Exception { chain(false, -1, true, false, false, false, CoreKind.CLOSURE, true, true); }
    @Test void addressIdentityAndBackingSurviveFirstInstalledOutlinedSpill() throws Exception { chain(false, -1, true, false, false, false, CoreKind.ADDRESS, true, true); }
    @Test void referenceLoopKeepsMaskCleanupAndNonTailSuffixOnce() throws Exception { chain(true, 16, false, false, false, false, CoreKind.DATA, true, true); }
    @Test void referenceSharedUpdateKeepsItsOriginalObject() throws Exception { chain(false, -1, false, true, false, false, CoreKind.CLOSURE, true, true); }
    @Test void referenceCatchKeepsLazyExceptionAndAddressOwnership() throws Exception { chain(false, 16, false, false, false, true, CoreKind.ADDRESS, true, true); }
    @Test void lazyReferenceResultKeepsOrdinaryForceCompletion() throws Exception { chain(false, -1, false, false, false, false, CoreKind.DATA, false, false); }
    @Test void unknownObjectResultKeepsOrdinaryCompletion() throws Exception { chain(false, -1, false, false, false, false, CoreKind.OBJECT, true, false); }
    private void checkRequestedStackSize() {
        String expected = System.getProperty("thc.test.stackKiB"); if (expected != null) assertEquals(expected, ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).getVMOption("ThreadStackSize").getValue(), "Observe the actual bounded test JVM, not argument order");
    }
    @Test void nestedAnchorDoesNotCaptureTailTransfersAcrossItsNonTailCaller() {
        checkRequestedStackSize(); try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            context.initialize("thc"); context.enter(); try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var metrics = new Metrics(true);
                class Roots {
                    FunctionRoot root(String label, Expr body, boolean side) { return new FunctionRoot(language, new FrameLayout().build(), label, null, new int[0], new int[0], new int[0], body, metrics, new CoreRepresentation[0], proof, body.getCoreSourceLocation(), new boolean[0], null, null, new int[0], null, false, new int[0][], false, side ? FunctionRootRole.PASS_THROUGH : FunctionRootRole.FUNCTION, true); }
                    RootCallTarget sides(String label, Expr last) {
                        var target = root(label + "-final", last, true).getCallTarget();
                        for (int index = 0; index < 128; index++) { var child = target; target = root(label + "-" + index, new Expr() {
                            @Child private DirectCallNode call = DirectCallNode.create(child);
                            { setRepresentation(proof); }
                            @Override public Object execute(VirtualFrame frame) { return AstControl.complete(this, Calls.direct(call, new Object[]{((GuestRoot) getRootNode()).bloom(frame)}), child, null, true); }
                        }, true).getCallTarget(); } return target;
                    }
                }
                var roots = new Roots(); FunctionRoot[] outer = new FunctionRoot[1]; int[] entries = {0}, suffixes = {0};
                var innerTarget = roots.sides("inner", new Expr() { @Child private IndirectCallerNode caller = new IndirectCallerNode(metrics); { setRepresentation(proof); } @Override public Object execute(VirtualFrame frame) { return caller.call(frame, outer[0].getCallTarget(), new Object[]{((GuestRoot) getRootNode()).bloom(frame)}, true); } });
                var inner = roots.root("inner normal anchor", new Expr() { @Child private DirectCallNode call = DirectCallNode.create(innerTarget); { setRepresentation(proof); } @Override public Object execute(VirtualFrame frame) { return AstControl.complete(this, Calls.direct(call, new Object[]{((GuestRoot) getRootNode()).bloom(frame)}), innerTarget, null, true); } }, false);
                var outerTarget = roots.sides("outer", new Expr() {
                    @Child private DirectCallerNode caller = new DirectCallerNode(inner.getCallTarget(), metrics); { setRepresentation(proof); }
                    @Override public Object execute(VirtualFrame frame) {
                        Object value; try { value = caller.call(frame, new Object[]{0L}, false); } catch (AstCapture cut) { throw cut.append(new AstResumeStep() { @Override public Object resume(VirtualFrame frame, Object input) { suffixes[0]++; return (Long) input + 4; } }); }
                        suffixes[0]++; return (Long) value + 4;
                    }
                });
                outer[0] = roots.root("outer normal anchor", new Expr() {
                    @Child private DirectCallNode call = DirectCallNode.create(outerTarget); { setRepresentation(proof); }
                    @Override public Object execute(VirtualFrame frame) { if (++entries[0] > 2) throw new IllegalStateException("A non-tail call's saved prefix replayed"); if (entries[0] == 2) return 73L; return AstControl.complete(this, Calls.direct(call, new Object[]{((GuestRoot) getRootNode()).bloom(frame)}), outerTarget, null, true); }
                }, false);
                assertEquals(77L, Calls.target(outer[0].getCallTarget(), new Object[]{0L})); assertEquals(2, entries[0]); assertEquals(1, suffixes[0]); assertEquals(2L, AstStackKt.astStackScope(outer[0]).getTailAnchors()); assertNull(AstStackKt.astStackScope(outer[0]).getTailAnchor()); assertEquals(0, AstStackKt.astStackScope(outer[0]).getDepth());
            } finally { context.leave(); }
        }
    }
    private void chain(boolean nonTail, int cleanup, boolean compiled, boolean shared, boolean failure, boolean caught, CoreKind kind, boolean evaluated, boolean compact) throws Exception {
        checkRequestedStackSize(); var builder = Context.newBuilder("thc").allowExperimentalOptions(true);
        if (compiled) builder.option("engine.BackgroundCompilation", "false").option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", "false"); else builder.option("engine.Compilation", "false");
        try (var context = builder.build()) { context.initialize("thc"); context.enter(); try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var metrics = new Metrics(true); var proof = kind == CoreKind.LONG ? this.proof : new CoreRepresentation(kind, evaluated, true, List.of(kind == CoreKind.ADDRESS ? "AddrRep" : "BoxedRep (Just Lifted)"), null, null, null, null, null);
            int count = 1024, laps = compiled || !compact ? 1 : 20; int[] prefixes = new int[count];
            class Counts { int iterations, finished, cleanups, catches, outerPrefixes, outerSuffixes; }
            var counts = new Counts(); var payload = new Thunk(new RootNode(language) { @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Exception payload was forced"); } }.getCallTarget(), null); byte[] bytes = {11, 37, 59};
            Object expected = switch (kind) {
                case DATA, OBJECT -> new DataLayout(language, "TailBox", "TailBox", new String[]{"LiftedRep"}).create(new Object[]{payload});
                case CLOSURE -> new Closure(null, 1, new RootNode(language) { @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Returned closure was entered"); } }.getCallTarget());
                case ADDRESS -> ManagedAddress.Companion.fromByteArray(bytes).plus(1);
                default -> 73L;
            };
            var resultThunk = evaluated ? null : new Thunk(new RootNode(language) { @Override public Object execute(VirtualFrame frame) { return expected; } }.getCallTarget(), null);
            java.util.function.Consumer<Object> checkAnswer = answer -> { if (kind == CoreKind.LONG) assertEquals(nonTail ? 77L : 73L, answer); else assertSame(expected, answer, "No copying, forcing, or carrier substitution at a tail return"); };
            FunctionRoot[] owner = new FunctionRoot[1]; RootCallTarget next = null;
            for (int i = count - 1; i >= 0; i--) { final int index = i; var following = next;
                var body = new Expr() {
                    @Child private DirectCallNode call = following == null ? null : DirectCallNode.create(following);
                    @Child private AstCaseArm arm = kind == CoreKind.LONG || following == null ? null : new AstCaseArm(following, null, new int[0], true);
                    @Child private TailCheck tail = new TailCheck(metrics);
                    { setRepresentation(proof); }
                    @Override public Object execute(VirtualFrame frame) {
                        prefixes[index]++; long bloom = ((GuestRoot) getRootNode()).bloom(frame); assertEquals(owner[0].mask, bloom, "Omitted side roots never claim catchers"); var ambient = SynchronousMasking.current(this); assertEquals(cleanup >= 0 && index > cleanup ? MaskingState.MASKED_INTERRUPTIBLE : MaskingState.UNMASKED, ambient);
                        if (following == null) { if (++counts.finished == laps) { if (failure || caught) throw new GuestException(payload, this); return resultThunk == null ? expected : resultThunk; } tail.check(frame, owner[0].getCallTarget(), new Object[]{bloom}); fail("The exact retained owner must catch the next transfer"); }
                        if (index != cleanup) return arm != null ? arm.execute(frame) : AstControl.complete(this, Calls.direct(Objects.requireNonNull(call), new Object[]{bloom}), following, null, true);
                        boolean captured = false; SynchronousMasking.set(this, MaskingState.MASKED_INTERRUPTIBLE);
                        try { return arm != null ? arm.execute(frame) : AstControl.complete(this, Calls.direct(Objects.requireNonNull(call), new Object[]{bloom}), following, null, true); }
                        catch (AstCapture cut) { captured = true; throw cut.enclose(steps -> new AstResumeStep() {
                            @Override public Object resume(VirtualFrame frame, Object input) {
                                SynchronousMasking.set(owner[0], MaskingState.MASKED_INTERRUPTIBLE);
                                try { return AstContinuationKt.resumeAstSteps(frame, steps, input); } catch (GuestException guest) { if (!caught) throw guest; assertSame(payload, guest.getPayload()); counts.catches++; return expected; } finally { counts.cleanups++; SynchronousMasking.set(owner[0], ambient); }
                            }
                        }); } catch (GuestException guest) { if (!caught) throw guest; assertSame(payload, guest.getPayload()); counts.catches++; return expected; } finally { if (!captured) counts.cleanups++; SynchronousMasking.set(this, ambient); }
                    }
                };
                next = new FunctionRoot(language, new FrameLayout().build(), "side-" + index, null, new int[0], new int[0], new int[0], body, metrics, new CoreRepresentation[0], proof, body.getCoreSourceLocation(), new boolean[0], null, null, new int[0], null, false, new int[0][], false, FunctionRootRole.PASS_THROUGH, true).getCallTarget();
            }
            var first = Objects.requireNonNull(next); var body = new Expr() { @Child private DirectCallNode call = DirectCallNode.create(first); { setRepresentation(proof); } @Override public Object execute(VirtualFrame frame) { counts.iterations++; return AstControl.complete(this, Calls.direct(call, new Object[]{owner[0].bloom(frame)}), first, null, true); } };
            owner[0] = new FunctionRoot(language, new FrameLayout().build(), "retained owner", null, new int[0], new int[0], new int[0], body, metrics, new CoreRepresentation[0], proof, body.getCoreSourceLocation(), new boolean[0], null, null, new int[0], null, false, new int[0][], false, FunctionRootRole.FUNCTION, true); var scope = AstStackKt.astStackScope(owner[0]); RootCallTarget target;
            if (!nonTail) target = owner[0].getCallTarget(); else {
                var outer = new Expr() {
                    @Child private DirectCallerNode caller = new DirectCallerNode(owner[0].getCallTarget(), metrics); { setRepresentation(proof); }
                    @Override public Object execute(VirtualFrame frame) { counts.outerPrefixes++; Object answer;
                        try { answer = caller.call(frame, new Object[]{0L}, false); } catch (AstCapture cut) { throw cut.append(new AstResumeStep() { @Override public Object resume(VirtualFrame frame, Object input) { counts.outerSuffixes++; return kind == CoreKind.LONG ? (Long) input + 4L : Objects.requireNonNull(input); } }); }
                        counts.outerSuffixes++; return kind == CoreKind.LONG ? (Long) answer + 4L : answer;
                    }
                };
                target = new FunctionRoot(language, new FrameLayout().build(), "non-tail outer", null, new int[0], new int[0], new int[0], outer, metrics, new CoreRepresentation[0], proof, outer.getCoreSourceLocation(), new boolean[0], null, null, new int[0], null, false, new int[0][], false, FunctionRootRole.FUNCTION, true).getCallTarget();
            }
            if (compiled) { target.getClass().getMethod("compile", boolean.class).invoke(target, true); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target); }
            long beforeCompiled = metrics.getCompiledEntries();
            if (shared) { var thunk = new Thunk(target, null); var force = new RootNode(language) { @Child private Force force = new Force(metrics); @Override public Object execute(VirtualFrame frame) { return force.execute(frame, thunk); } }.getCallTarget(); for (int i = 0; i < 2; i++) { if (failure) assertThrows(GuestException.class, () -> Calls.target(force, new Object[0])); else checkAnswer.accept(Calls.target(force, new Object[0])); } assertEquals(failure ? 3 : 2, thunk.getState()); assertNull(thunk.getOwner()); }
            else checkAnswer.accept(Calls.target(target, new Object[]{0L}));
            if (compiled) { assertEquals(beforeCompiled + 1, metrics.getCompiledEntries(), "The first invocation entered the installed root"); assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), "No settling invocation or compilation retry is allowed"); }
            assertEquals(laps, counts.iterations); boolean allOnce = true; for (int prefix : prefixes) if (prefix != laps) { allOnce = false; break; } assertTrue(allOnce, "Every side prefix executes exactly once per real loop iteration");
            if (compact) { assertTrue(scope.getCompactedFrames() > count * laps - 128 * laps); assertEquals(1L, scope.getTailAnchors(), "One saved-suffix catcher, not one wrapper per spill/lap"); assertTrue(scope.getMaxParkedSpillParents() <= 2, "Identity sides are omitted before publication; only the real cleanup/non-tail parent may park"); }
            else { assertEquals(0L, scope.getCompactedFrames()); assertEquals(0L, scope.getTailAnchors()); }
            assertEquals(0L, metrics.getTrampolineIterations()); assertEquals(nonTail ? 1 : 0, counts.outerPrefixes); assertEquals(nonTail ? 1 : 0, counts.outerSuffixes); assertEquals(cleanup >= 0 ? laps : 0, counts.cleanups); assertEquals(caught ? 1 : 0, counts.catches); assertEquals(0, payload.getState(), "Catch/update transport never enters the exception payload");
            if (expected instanceof DataValue value) assertSame(payload, value.getLayout().read(value, 0)); if (expected instanceof ManagedAddress address) { bytes[1] = 83; assertEquals(83L, address.readWord8(0), "The exact address retains its original backing and offset"); }
            if (resultThunk != null) assertEquals(2, resultThunk.getState(), "Lazy results still run their required force"); assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(owner[0])); assertNull(scope.getTailAnchor()); assertEquals(0, scope.getDepth()); assertFalse(scope.getDriving()); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
        } finally { context.leave(); } }
    }
}
