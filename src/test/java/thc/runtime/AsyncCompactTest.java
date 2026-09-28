// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

class AsyncCompactTest {
    private static List<Object> list(Object... xs) { return Arrays.asList(xs); }
    private static Map<String, Object> map(Object... xs) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < xs.length; i += 2) result.put((String) xs[i], xs[i + 1]);
        return result;
    }
    private static Map<String, Object> module(boolean sharing) {
        var state = map("kind", "void", "primReps", List.of(), "evaluated", true);
        var region = map("kind", "object", "primReps", list("BoxedRep (Just Unlifted)"), "evaluated", true);
        var data = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
        var tuple = map("kind", "unknown", "aggregate", "unboxed-tuple", "primReps", list("BoxedRep (Just Lifted)"),
                "components", list(state, data), "evaluated", false);
        var add = list("app", list("prim", sharing ? "compactAddWithSharing#" : "compactAdd#"),
                list(list("var", "region", map("rep", region)), list("var", "value", map("rep", data)),
                        list("void", map("rep", state))), list(false, true, false), false, false, map("rep", tuple));
        var bindings = new ArrayList<Object>();
        bindings.add(map("id", "copy", "name", "copy", "lifted", true, "expr", list("lam",
                list(map("id", "region", "lifted", false, "rep", region), map("id", "value", "lifted", true, "rep", data)),
                add, map("resultRep", tuple))));
        for (String failure : CompactOp.getFailures()) bindings.add(map("id", failure, "name", failure,
                "lifted", true, "expr", list("lit", "int", "7")));
        return map("bindings", bindings, "instrument", true);
    }
    private static final class BlockedChild extends GuestRoot {
        final ManagedMVar cell = new ManagedMVar();
        final AtomicInteger prefixes = new AtomicInteger();
        final AtomicInteger installed = new AtomicInteger();
        @Child private DelayThread delay = new DelayThread(new Literal(300_000L), new Literal(Unit.INSTANCE), true,
                CoreRepresentations.parse(map("kind", "void", "primReps", List.of(), "evaluated", true)));
        BlockedChild(Language language) { super(language, new FrameLayout().build()); }
        @Override public long bloom(VirtualFrame frame) { return 0; }
        @Override public boolean getAsynchronousExceptions() { return true; }
        private Object take(VirtualFrame frame, Object input) {
            assertSame(Unit.INSTANCE, input);
            try { return cell.take(this, true); }
            catch (AsyncBlocked blocked) {
                throw new AstCapture(blocked.getRequest(), SynchronousMasking.current(this)).append(this::take);
            }
        }
        @Override public Object execute(VirtualFrame frame) {
            prefixes.incrementAndGet();
            if (CompilerDirectives.inCompiledCode()) installed.incrementAndGet();
            try {
                try { delay.execute(frame); }
                catch (AstCapture cut) { throw cut.append(this::take); }
                return take(frame, Unit.INSTANCE);
            } catch (AstCapture cut) { return cut.freeze(this, frame.materialize()); }
        }
    }
    private static void compile(RootCallTarget target) throws Exception {
        assertEquals(true, target.getClass().getMethod("compile", boolean.class).invoke(target, true));
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "ast-sharing", "bytecode", "bytecode-sharing",
            "ast-failure", "ast-sharing-failure", "bytecode-failure", "bytecode-sharing-failure"})
    void firstInstalledCopyPreservesPrivateTraversalAcrossDelivery(String mode) throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            final Language.State owner; final ExecutableProgram program; final RootCallTarget caller, resume;
            final BlockedChild child; final ManagedCompact region; final DataLayout leaf, pair;
            final DataValue original; final TupleShape shape;
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                owner = Language.currentState();
                program = mode.startsWith("ast") ? new Program(language, module(mode.contains("sharing")), true)
                        : new BytecodeProgram(language, module(mode.contains("sharing")), true);
                caller = program.entryTarget("copy"); shape = ((GuestRoot) caller.getRootNode()).getTupleResult();
                child = new BlockedChild(language);
                leaf = new DataLayout(language, "test:Leaf", "Leaf", new String[]{"IntRep"});
                pair = new DataLayout(language, "test:Pair", "Pair", new String[]{"LiftedRep", "LiftedRep"});
                region = new ManagedCompact(owner.compactRegions, 4096);
                original = pair.create(new Object[]{leaf.create(new Object[]{5L}), new Thunk(child.getCallTarget(), null)});
                resume = new RootNode(language) {
                    @Child private Force force = new Force(new Metrics(false), true);
                    @Override public Object execute(VirtualFrame frame) {
                        return force.drainStack((SavedGuestContinuation) frame.getArguments()[0], shape);
                    }
                }.getCallTarget();
                // No target execution or suspension before these explicit compilations.
                compile(child.getCallTarget()); compile(caller);
            } finally { context.leave(); }
            var answer = new CompletableFuture<SavedGuestContinuation>();
            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
            var worker = new Thread(() -> {
                context.enter(); owner.getThreads().enterCurrent(null, false, true, null);
                try {
                    Object result = Calls.target(caller, new Object[]{0L, region, original});
                    var saved = Objects.requireNonNull(SavedGuestContinuations.savedGuestContinuation(result));
                    Objects.requireNonNull(saved.asyncRequest()).acknowledge(); answer.complete(saved);
                } catch (Throwable failure) { answer.completeExceptionally(failure); }
                finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); }
            });
            worker.start();
            try {
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while ((owner.getThreads().pollState(worker).getCurrent() == null ||
                        owner.getThreads().pollState(worker).getCurrent().getIdentity().getStatus() != GuestThreadStatus.DELAY)
                        && !answer.isDone() && System.nanoTime() < until) Thread.sleep(1);
                if (answer.isCompletedExceptionally()) answer.get(1, TimeUnit.SECONDS);
                assertEquals(GuestThreadStatus.DELAY, owner.getThreads().pollState(worker).getCurrent().getIdentity().getStatus());
                var request = owner.getThreads().send(owner.getThreads().pollState(worker).getCurrent().getIdentity(), "copy cut");
                var saved = answer.get(10, TimeUnit.SECONDS); worker.join(5000); assertFalse(worker.isAlive());
                assertSame(request, saved.asyncRequest()); assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState());
                assertEquals(1, child.installed.get()); assertTrue(request.compiledCapture);
                assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                context.enter();
                try {
                    assertEquals(0, region.getGeneration()); assertTrue(region.getObjects().isEmpty());
                    assertEquals(0, region.snapshot(List::size).intValue(), "Parked private shells do not block committed-region inspection");
                    if (mode.endsWith("failure")) {
                        assertTrue(child.cell.tryPut(new Closure(null, 1, child.getCallTarget())));
                        var failure = assertThrows(GuestException.class, () -> Calls.target(resume, new Object[]{saved}));
                        assertEquals(7L, failure.getPayload());
                        assertEquals(0, region.getGeneration()); assertEquals(0, region.snapshot(List::size).intValue());
                        var next = TupleResults.ownedTupleResult(Calls.target(caller,
                                new Object[]{0L, region, leaf.create(new Object[]{99L})}), shape);
                        var copied = (DataValue) shape.getLayout().getObject(next, 0);
                        assertEquals(99L, leaf.readLong(copied, 0));
                        assertTrue(owner.compactRegions.contains(region, copied));
                        assertEquals(1, region.snapshot(List::size).intValue());
                    } else {
                        assertTrue(child.cell.tryPut(leaf.create(new Object[]{37L})));
                        var result = TupleResults.ownedTupleResult(Calls.target(resume, new Object[]{saved}), shape);
                        var copied = (DataValue) shape.getLayout().getObject(result, 0);
                        assertNotSame(original, copied);
                        assertEquals(5L, leaf.readLong((DataValue) pair.read(copied, 0), 0));
                        assertEquals(37L, leaf.readLong((DataValue) pair.read(copied, 1), 0));
                        assertEquals(3, region.snapshot(List::size).intValue());
                        assertTrue(owner.compactRegions.contains(region, copied));
                    }
                    assertEquals(1, child.prefixes.get()); assertTrue(child.cell.isEmpty());
                    assertEquals(1, region.getGeneration());
                    assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(caller.getRootNode()));
                    assertSame(caller, program.entryTarget("copy"));
                    var pools = shape.getLanguage().getHandoffState().get();
                    assertEquals(0, pools.getArguments().getDepth()); assertEquals(0, pools.getResults().getDepth());
                    assertEquals(0, pools.getArguments().retainedReferences()); assertEquals(0, pools.getResults().retainedReferences());
                } finally { context.leave(); }
            } finally { if (worker.isAlive()) context.close(true); worker.join(5000); }
        }
    }
}
