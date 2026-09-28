// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarValueTestSupport.*;

/** Independent protocol controls; authentic exported/native calls live in PinnedPointerCellsTest. */
class CoreTouchTest {
    private static Map<String, Object> proof(String kind, String rep, boolean evaluated) {
        return map("kind", kind, "primReps", rep == null ? list() : list(rep), "evaluated", evaluated);
    }
    private static Map<String, Object> proof(String kind, String rep) { return proof(kind, rep, true); }
    private final Map<String, Object> state = proof("void", null);
    private final String lifted = "BoxedRep (Just Lifted)", unlifted = "BoxedRep (Just Unlifted)";
    private final Map<String, Object> closure = proof("closure", lifted);
    private List<Object> variable(String id, Map<String, Object> rep) { return list("var", id, map("rep", rep)); }
    private Map<String, Object> parameter(String id, Map<String, Object> rep) {
        return map("id", id, "name", id, "lifted", rep.get("primReps").equals(list(lifted)), "rep", rep);
    }
    private List<Object> application(Map<String, Object> kept) {
        return application(kept, state, state, list(kept.get("primReps").equals(list(lifted)), false), variable("kept", kept));
    }
    private List<Object> application(Map<String, Object> kept, Map<String, Object> token,
                                    Map<String, Object> result, List<?> flags, List<?> payload) {
        return list("app", list("prim", "touch#"), list(payload, variable("s", token)), flags, false, false, map("rep", result));
    }
    private Map<String, Object> module(Map<String, Object> kept) { return module(kept, application(kept), kept); }
    private Map<String, Object> module(Map<String, Object> kept, List<?> body, Map<String, Object> stored) {
        return map("instrument", true, "constructors", list(), "bindings", list(map("id", "main", "name", "main",
            "arity", 2, "lifted", true, "rep", closure, "expr", list("lam", list(parameter("kept", stored), parameter("s", state)),
                body, map("rep", closure, "resultRep", state)))));
    }
    private ExecutableProgram program(Language language, String backend, Map<String, Object> module) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    @FunctionalInterface private interface ContextAction { void run(Language language, String backend, boolean inlining) throws Exception; }
    private void contexts(ContextAction block) throws Exception {
        for (var backend : list("ast", "bytecode")) for (boolean inlining : list(false, true))
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("compiler.Inlining", Boolean.toString(inlining)).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
                .option("engine.SingleTierCompilationThreshold", "10000000").build()) {
                context.initialize("thc"); context.enter();
                try { block.run(TruffleLanguage.LanguageReference.create(Language.class).get(null), backend, inlining); }
                finally { context.leave(); }
            }
    }
    private void released(Language language) {
        var pools = language.getHandoffState().get();
        assertEquals(0, pools.getArguments().getDepth()); assertEquals(0, pools.getResults().getDepth());
        assertEquals(0, pools.getArguments().retainedReferences()); assertEquals(0, pools.getResults().retainedReferences());
    }
    private Thunk bottom() {
        return new Thunk(new RootNode(null) {
            @Override public Object execute(VirtualFrame frame) { throw new AssertionError("touch# entered its payload"); }
        }.getCallTarget(), null);
    }
    @Test void exactReferenceLevityFlagsAndBareStateProofsAreRequired() {
        for (var kind : list("object", "data", "closure")) for (var rep : list(lifted, unlifted))
            for (boolean evaluated : list(false, true)) {
                var kept = proof(kind, rep, evaluated);
                var flags = list(rep.equals(lifted), false);
                assertDoesNotThrow(() -> CoreTouch.validateRaw(list(kept, state), flags, state));
                assertDoesNotThrow(() -> CoreTouch.validate(list(CoreRepresentations.parse(kept),
                    CoreRepresentations.parse(state)), flags, CoreRepresentations.parse(state)));
            }
        var owner = proof("object", lifted, false);
        var invalid = list(null, proof("long", "IntRep"), proof("address", "AddrRep"), proof("object", "BoxedRep Nothing"),
            without(owner, "evaluated"), with(owner, "evaluated", 1), with(owner, "extra", null), with(owner, "vector", null),
            with(owner, "aggregate", "unboxed-tuple"), with(owner, "components", list()));
        for (var bad : invalid) assertThrows(RuntimeFault.class, () -> CoreTouch.validateRaw(list(bad, state), list(true, false), state));
        for (var bad : list(null, owner, without(state, "evaluated"), with(state, "extra", null),
            with(state, "aggregate", "unboxed-tuple"), with(state, "primReps", list("IntRep")))) {
            assertThrows(RuntimeFault.class, () -> CoreTouch.validateRaw(list(owner, bad), list(true, false), state));
            assertThrows(RuntimeFault.class, () -> CoreTouch.validateRaw(list(owner, state), list(true, false), bad));
        }
        for (var flags : list(list(), list(true), list(false, false), list(true, true), list(1, false), list(true, false, false)))
            assertThrows(RuntimeFault.class, () -> CoreTouch.validateRaw(list(owner, state), flags, state));
        for (var args : list(list(), list(owner), list(owner, state, state)))
            assertThrows(RuntimeFault.class, () -> CoreTouch.validateRaw(args, list(true, false), state));
    }
    @Test void bothLoadersRejectRawAndLexicalProofSubstitution() throws Exception {
        contexts((language, backend, inlining) -> {
            var owner = proof("object", lifted, false);
            var payload = variable("kept", owner);
            var bodies = list(application(owner, state, state, list(false, false), payload), application(with(owner, "extra", 0)),
                application(owner, with(state, "evaluated", 1), state, list(true, false), payload),
                application(owner, state, with(state, "extra", 0), list(true, false), payload),
                application(owner, state, with(proof("unknown", null), "aggregate", "unboxed-tuple", "components", list(state)), list(true, false), payload),
                application(proof("long", "IntRep")), application(owner, proof("long", "IntRep"), state, list(true, false), payload));
            for (var body : bodies) assertThrows(RuntimeFault.class, () -> program(language, backend, module(owner, body, owner)), backend + "/" + body);
            for (var stored : list(proof("long", "IntRep"), proof("address", "AddrRep"), proof("object", unlifted)))
                assertThrows(RuntimeFault.class, () -> program(language, backend, module(owner, application(owner), stored)));
        });
    }
    @Test void liftedBottomAndUnliftedAllocationStayLazyThroughFirstInstalledCalls() throws Exception {
        contexts((language, backend, inlining) -> {
            for (var kind : list("object", "data", "closure", "unlifted-object")) {
                boolean unliftedCase = kind.equals("unlifted-object");
                var rep = proof(unliftedCase ? "object" : kind, unliftedCase ? unlifted : lifted, unliftedCase);
                var program = program(language, backend, module(rep));
                var target = program.entryTarget("main");
                var lazy = bottom();
                var allocation = ManagedAllocation.mutable(16, 8);
                Object value = unliftedCase ? allocation : lazy;
                CheckedConsumer<Boolean> call = compiled -> {
                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    assertSame(thc.runtime.Unit.INSTANCE, Calls.target(target, new Object[]{0L, value, thc.runtime.Unit.INSTANCE}));
                    assertEquals(0, lazy.getState());
                    if (compiled) {
                        assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), backend + "/" + inlining + "/" + kind);
                    }
                    released(language);
                };
                for (int i = 0; i < 3; i++) call.accept(false);
                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                call.accept(true); // First invocation after installation, without settling/recompilation.
                call.accept(true);
                assertThrows(RuntimeFault.class, () -> Calls.target(target, new Object[]{0L, value, 1L}));
                assertEquals(0, lazy.getState()); released(language);
            }
        });
    }
    @Test void nonreturningLiftedExpressionIsDelayedRatherThanEntered() throws Exception {
        contexts((language, backend, inlining) -> {
            var owner = proof("object", lifted, false);
            var raise = list("app", list("prim", "raise#"), list(variable("kept", owner)), list(true), false, false, map("rep", owner));
            var program = program(language, backend, module(owner, application(owner, state, state, list(true, false), raise), owner));
            assertSame(thc.runtime.Unit.INSTANCE, Calls.target(program.entryTarget("main"), new Object[]{0L, new Object(), thc.runtime.Unit.INSTANCE}));
            released(language);
        });
    }
    @Test void stateValidationPrecedesFenceAndAstOperandsAreEvaluatedOnce() {
        var lazy = bottom();
        assertSame(thc.runtime.Unit.INSTANCE, Touch.preserve(lazy, thc.runtime.Unit.INSTANCE));
        for (var invalid : list(null, 1L, new Object(), bottom())) assertThrows(RuntimeFault.class, () -> Touch.preserve(lazy, invalid));
        assertEquals(0, lazy.getState());
        var calls = new ArrayList<String>();
        var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], new FrameLayout().build());
        var node = new TouchExpression(operand(calls, "kept", lazy), operand(calls, "state", thc.runtime.Unit.INSTANCE), CoreRepresentations.parse(state));
        assertSame(thc.runtime.Unit.INSTANCE, node.execute(frame)); assertEquals(list("kept", "state"), calls);
        assertEquals(0, lazy.getState()); calls.clear();
        var invalid = new TouchExpression(operand(calls, "kept", lazy), operand(calls, "state", 1L), CoreRepresentations.parse(state));
        assertThrows(RuntimeFault.class, () -> invalid.execute(frame));
        assertEquals(list("kept", "state"), calls); assertEquals(0, lazy.getState());
    }
    private Expr operand(List<String> calls, String label, Object value) {
        return new Expr() {
            @Override public Object execute(VirtualFrame frame) { calls.add(label); return value; }
        };
    }
}
