// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeUtil;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.Language;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;
import static thc.runtime.ScalarValueTestSupport.*;

/** All six primitive Int carriers survive a real compiled blocking cut. The
 * completed first take is deliberately empty when the saved suffix resumes. */
class NarrowIntegerContinuationTest {
    @Test void onlyCapturingRootsDeclareMaterializableFramesBeforePublicationAndCloning() throws Exception {
        var declaration = FunctionRoot.class.getDeclaredMethod("requiresMaterializableFrame"); assertTrue(declaration.trySetAccessible());
        record Policy(boolean async, boolean delimited) {}
        for (var policy : list(new Policy(false, false), new Policy(true, false), new Policy(false, true))) {
            var layout = new FrameLayout(); int slot = layout.bind("narrow");
            var proof = new CoreRepresentation(CoreKind.LONG, true, true, list("Int32Rep"), null, null, null, null, null);
            var body = new Expr() { @Override public Object execute(VirtualFrame frame) { throw new IllegalStateException("Policy preparation must not execute guest code"); } };
            var root = new FunctionRoot(null, layout.build(), "capture policy", null, new int[0], new int[]{slot}, new int[]{0}, body,
                new Metrics(true), new CoreRepresentation[]{proof}, body.getRepresentation(), body.getCoreSourceLocation(), new boolean[0], null, null,
                new int[0], null, policy.async(), new int[0][], policy.delimited(), FunctionRootRole.FUNCTION, false);
            assertEquals(policy.async() || policy.delimited(), declaration.invoke(root));
            assertEquals(FrameSlotKind.Int, root.getFrameDescriptor().getSlotKind(slot)); assertSame(root, root.getCallTarget().getRootNode());
            assertEquals(policy.delimited(), root.getDelimitedControlEnabled());
            var clone = NodeUtil.cloneNode(root); assertNotSame(root, clone);
            assertEquals(policy.async() || policy.delimited(), declaration.invoke(clone)); assertSame(clone, clone.getCallTarget().getRootNode());
            assertEquals(policy.delimited(), clone.getDelimitedControlEnabled()); assertEquals(FrameSlotKind.Int, clone.getFrameDescriptor().getSlotKind(slot));
        }
    }
    private final Map<String, Object> stateRep = map("kind", "void", "primReps", list(), "evaluated", true),
        mvarRep = map("kind", "object", "primReps", list("BoxedRep (Just Unlifted)"), "evaluated", true),
        dataRep = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
    private final List<Map<String, Object>> ints = Arrays.stream(NarrowInteger.values()).map(integer -> map("kind", "long", "primReps", list(integer.getRep()), "evaluated", true)).toList();
    private final Map<String, Object> pair = map("kind", "unknown", "aggregate", "unboxed-tuple", "primReps", list("BoxedRep (Just Lifted)"), "components", list(stateRep, dataRep), "evaluated", false);
    private final Map<String, Object> resultRep = resultRep();
    private Map<String, Object> resultRep() {
        var components = new ArrayList<>(list(stateRep)); components.addAll(ints);
        return map("kind", "unknown", "aggregate", "unboxed-tuple", "primReps", Arrays.stream(NarrowInteger.values()).map(NarrowInteger::getRep).toList(), "components", components, "evaluated", false);
    }
    private final Object[] values = {-128, 255, -32768, 65535, Integer.MIN_VALUE, -1};
    private List<Object> variable(String name, Map<String, Object> rep) { return list("var", name, map("rep", rep)); }
    private List<Object> afterTake(String cell, Object suffix) {
        var call = list("app", list("prim", "takeMVar#"), list(variable(cell, mvarRep), list("void", map("rep", stateRep))),
            list(false, false), false, false, map("rep", pair));
        return list("case", call, cell + "Result", list(list("data", "Pair", list(cell + "State", cell + "Value"), suffix,
            map("binders", list(map("id", cell + "State", "rep", stateRep), map("id", cell + "Value", "rep", dataRep))))),
            map("rep", resultRep, "binder", map("id", cell + "Result", "rep", pair)));
    }
    /** Lazy RTS dependency only; these private cells never request blocked-owner delivery. */
    private Map<String, Object> blockedMVarDependency() {
        return map("id", CoreBlockedExceptions.MVAR, "name", CoreBlockedExceptions.MVAR,
            "type", "SomeException", "lifted", true, "arity", 0, "rep", dataRep,
            "expr", variable(CoreBlockedExceptions.MVAR, dataRep));
    }
    private Map<String, Object> module() {
        var arguments = new ArrayList<List<Object>>(); arguments.add(list("void", map("rep", stateRep)));
        for (int i = 0; i < ints.size(); i++) arguments.add(variable("n" + i, ints.get(i)));
        var tuple = list("app", list("con", "Result", 7), arguments, Collections.nCopies(7, false), false, false, map("rep", resultRep));
        var formals = new LinkedHashMap<String, Map<String, Object>>(); formals.put("prefix", mvarRep); formals.put("blocked", mvarRep);
        for (int i = 0; i < ints.size(); i++) formals.put("n" + i, ints.get(i));
        var parameters = new ArrayList<Map<String, Object>>();
        formals.forEach((id, rep) -> parameters.add(map("id", id, "name", id, "lifted", false, "coercion", false, "rep", rep)));
        return map("instrument", true, "bindings", list(map("id", "entry", "name", "entry", "lifted", true,
            "expr", list("lam", parameters, afterTake("prefix", afterTake("blocked", tuple)), map("resultRep", resultRep))), blockedMVarDependency()),
            "constructors", list(map("id", "Pair", "name", "Pair", "kind", "unboxed-tuple", "arity", 2), map("id", "Result", "name", "Result", "kind", "unboxed-tuple", "arity", 7)));
    }
    private Object wideControl(Object value) {
        if (value instanceof Map<?, ?> map) { var result = new LinkedHashMap<Object, Object>(); map.forEach((key, item) -> result.put(key, wideControl(item))); return result; }
        if (value instanceof List<?> values) { var result = new ArrayList<Object>(); for (var item : values) result.add(wideControl(item)); return result; }
        if (value instanceof String text) for (var integer : NarrowInteger.values()) if (integer.getRep().equals(text)) return "IntRep";
        return value;
    }
    private Object[] arguments(ManagedMVar prefix, ManagedMVar blocked, Object[] values) {
        var result = new Object[values.length + 3]; result[0] = 0L; result[1] = prefix; result[2] = blocked;
        System.arraycopy(values, 0, result, 3, values.length); return result;
    }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private void checkResult(Object result, TupleShape shape, Object[] actualValues, boolean narrow) {
        var tuple = TupleResults.ownedTupleResult(result, shape); assertEquals(6, shape.getWidth());
        for (int i = 0; i < actualValues.length; i++) {
            assertEquals(narrow, shape.getLayout().isInt(i)); assertEquals(!narrow, shape.getLayout().isLong(i));
            // Keep the distinct boxed carriers; a numeric conditional would widen Integer to Long.
            Object actual;
            if (narrow) actual = shape.getLayout().getInt(tuple, i); else actual = shape.getLayout().getLong(tuple, i);
            assertEquals(actualValues[i], actual);
            assertEquals(list(narrow ? NarrowInteger.values()[i].getRep() : "IntRep"), shape.getLeaves()[i].getPrimReps());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode", "ast-long-control", "bytecode-long-control"})
    void firstCompiledCutPreservesAllNarrowFieldsAndDoesNotReplayCompletedTake(String mode) throws Exception {
        var backend = mode.split("-", 2)[0]; boolean narrow = !mode.endsWith("long-control");
        var actualValues = new Object[values.length];
        for (int i = 0; i < values.length; i++) { if (narrow) actualValues[i] = values[i]; else actualValues[i] = NarrowInteger.values()[i].widen((Integer) values[i]); }
        var input = narrow ? module() : object(wideControl(module()));
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            final Language language; final Language.State owner; final ExecutableProgram program; final RootCallTarget target; final TupleShape shape;
            try {
                language = TruffleLanguage.LanguageReference.create(Language.class).get(null); owner = Language.currentState();
                program = backend.equals("ast") ? new Program(language, input, true) : new BytecodeProgram(language, input, true);
                target = program.entryTarget("entry"); shape = Objects.requireNonNull(((GuestRoot) target.getRootNode()).getTupleResult());
                for (int i = 0; i < 5; i++) {
                    var prefix = new ManagedMVar(); assertTrue(prefix.tryPut("prefix")); var blocked = new ManagedMVar(); assertTrue(blocked.tryPut("suffix"));
                    checkResult(callScalarTestTarget(target, arguments(prefix, blocked, actualValues)), shape, actualValues, narrow);
                    assertTrue(prefix.isEmpty()); assertTrue(blocked.isEmpty());
                }
                target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target);
                // Restore a retired host call stub without entering guest code.
                var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target); valid(target);
            } finally { context.leave(); }
            var prefix = new ManagedMVar(); assertTrue(prefix.tryPut("once")); var blocked = new ManagedMVar();
            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); var answer = new CompletableFuture<SavedGuestContinuation>();
            var worker = new Thread(() -> {
                context.enter(); owner.getThreads().enterCurrent(null, false, true, null);
                try {
                    var captured = Objects.requireNonNull(SavedGuestContinuations.savedGuestContinuation(callScalarTestTarget(target, arguments(prefix, blocked, actualValues))));
                    Objects.requireNonNull(captured.asyncRequest()).acknowledge(); answer.complete(captured);
                } catch (Throwable failure) { answer.completeExceptionally(failure); }
                finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); context.leave(); }
            });
            worker.start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (blocked.pendingCounts().getTakers() != 1 && !answer.isDone() && System.nanoTime() < deadline) Thread.sleep(1);
                if (answer.isCompletedExceptionally()) answer.get(1, TimeUnit.SECONDS);
                assertEquals(1, blocked.pendingCounts().getTakers());
                assertTrue(prefix.isEmpty(), "The first effect completed before the blocked cut");
                owner.getThreads().send(Objects.requireNonNull(owner.getThreads().pollState(worker).getCurrent()).getIdentity(), "narrow cut");
                var captured = answer.get(10, TimeUnit.SECONDS); worker.join(5000); assertFalse(worker.isAlive());
                assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                var completed = new CompletableFuture<thc.runtime.Unit>();
                var resumer = new Thread(() -> {
                    context.enter();
                    try {
                        assertTrue(blocked.tryPut("continue")); checkResult(captured.continueWith(thc.runtime.Unit.INSTANCE), shape, actualValues, narrow);
                        assertTrue(prefix.isEmpty(), "Resumption must not replay the completed first take"); assertTrue(blocked.isEmpty());
                        assertSame(target, program.entryTarget("entry")); if (backend.equals("ast")) assertThrows(RuntimeFault.class, () -> captured.continueWith(thc.runtime.Unit.INSTANCE));
                        var handoff = language.getHandoffState().get(); assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getArguments().retainedReferences());
                        assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getResults().retainedReferences()); assertNull(handoff.getPending());
                        completed.complete(thc.runtime.Unit.INSTANCE);
                    } catch (Throwable failure) { completed.completeExceptionally(failure); } finally { context.leave(); }
                });
                resumer.start();
                try { completed.get(10, TimeUnit.SECONDS); } finally { if (!completed.isDone()) context.close(true); resumer.join(5000); }
                assertFalse(resumer.isAlive());
            } finally { if (worker.isAlive()) context.close(true); worker.join(5000); }
        }
    }
}
