// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.Main;
import static org.junit.jupiter.api.Assertions.*;

/** Unavailable foreign execution is a reached failure, never a fabricated result. */
class UnsupportedForeignCallTest {
    private final Map<String, Object> integer = scalar("long", List.of("IntRep"), true);
    private final Map<String, Object> cint = scalar("long", List.of("Int32Rep"), true);
    private final Map<String, Object> state = scalar("void", List.of(), true);
    private final Map<String, Object> closure = scalar("closure", List.of("BoxedRep (Just Lifted)"), true);
    private final Map<String, Object> reference = scalar("object", List.of("BoxedRep (Just Lifted)"), true);
    private final Map<String, Object> cell = scalar("object", List.of("BoxedRep (Just Unlifted)"), true);

    private Map<String, Object> scalar(String kind, List<String> reps, boolean evaluated) {
        return Map.of("kind", kind, "primReps", reps, "evaluated", evaluated);
    }
    private Map<String, Object> changed(Map<String, Object> original, String key, Object value) {
        var copy = new LinkedHashMap<>(original); copy.put(key, value); return copy;
    }
    private Map<String, Object> tuple(boolean withValue) {
        return Map.of("kind", "unknown", "primReps", withValue ? List.of("IntRep") : List.of(),
            "evaluated", false, "aggregate", "unboxed-tuple", "components", withValue ? List.of(state, integer) : List.of(state));
    }
    private Map<String, Object> binder(String id, Map<String, Object> rep, boolean lifted) {
        return Map.of("id", id, "name", id, "rep", rep, "lifted", lifted);
    }
    private List<Object> variable(String id, Map<String, Object> rep) { return List.of("var", id, Map.of("rep", rep)); }
    private List<Object> literal(long value) { return List.of("lit", "int", Long.toString(value), Map.of("rep", integer)); }
    private List<Object> stateValue() { return List.of("void", Map.of("rep", state)); }
    private List<Object> caseOf(Object value, Map<String, Object> rep, String id, Object body) {
        return List.of("case", value, id, List.of(Arrays.asList("default", null, List.of(), body)),
            Map.of("rep", integer, "binder", binder(id, rep, false)));
    }
    private Map<String, Object> descriptor(String symbol, Map<String, Object> result) {
        return Map.of("schema", 1, "target", Map.of("kind", "static", "symbol", symbol,
            "unit", "ghci-9.14.1-inplace", "isFunction", true), "convention", "ccall", "safety", "unsafe",
            "arity", 2, "suppliedArity", 2,
            "argumentReps", List.of(changed(cint, "evaluated", false), changed(state, "evaluated", false)), "resultRep", result);
    }
    private List<Object> foreignCall(Map<String, Object> declaration, Map<String, Object> result) {
        return List.of("app", variable("original-foreign-id", closure),
            List.of(List.of("lit", "int", "0", Map.of("rep", cint)), stateValue()),
            List.of(false, false), false, false, Map.of("rep", result, "foreignCall", declaration));
    }
    private Map<String, Object> module(Object cold) {
        var body = List.of("case", variable("choice", integer), "selected", List.of(
            List.of("lit", List.of("int", "0"), List.of(), literal(42)), Arrays.asList("default", null, List.of(), cold)),
            Map.of("rep", integer, "binder", binder("selected", integer, false)));
        return Map.of("instrument", true, "diagnosticUnsupported", false, "constructors", List.of(), "bindings", List.of(
            Map.of("id", "entry", "name", "entry", "arity", 3, "lifted", true, "rep", closure,
                "expr", List.of("lam", List.of(binder("choice", integer, false), binder("cell", cell, false),
                    binder("replacement", reference, true)), body, Map.of("rep", closure, "resultRep", integer)))));
    }
    private Object effectAfter(Object call, Map<String, Object> result) {
        var write = List.of("app", List.of("prim", "writeMutVar#"),
            List.of(variable("cell", cell), variable("replacement", reference), stateValue()),
            List.of(false, true, false), false, false, Map.of("rep", state));
        return caseOf(call, result, "foreign-result", caseOf(write, state, "written", literal(99)));
    }
    private ExecutableProgram program(Language language, Map<String, Object> input, String backend, boolean async) {
        return backend.equals("ast") ? new Program(language, input, async) : new BytecodeProgram(language, input, async);
    }
    private void checkReachedOnly(String symbol, Map<String, Object> result) {
        var declaration = descriptor(symbol, result);
        var input = module(effectAfter(foreignCall(declaration, result), result));
        for (String backend : List.of("ast", "bytecode")) for (boolean async : List.of(false, true)) {
            try (var context = Main.executionContext(false)) {
                context.initialize("thc"); context.enter();
                var threads = Language.currentState().getThreads(); threads.enterCurrent(null, false, true, null);
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var p = program(language, input, backend, async);
                    var target = p.entryTarget("entry");
                    var initial = new Object(); var replacement = new Object(); var storage = new ManagedMutVar(initial);
                    assertEquals(0L, p.diagnostics().get("unsupportedTraps"));
                    assertEquals(42L, Calls.target(target, new Object[]{0L, 0L, storage, replacement}));
                    assertSame(initial, storage.getValue());
                    assertEquals(0L, p.diagnostics().get("unsupportedTraps"));
                    var failure = assertThrows(UnsupportedCore.class,
                        () -> Calls.target(target, new Object[]{0L, 1L, storage, replacement}), backend + "/" + async);
                    assertEquals(UnsupportedCore.class, failure.getClass());
                    assertEquals("Unsupported foreign call: " + symbol, failure.getMessage());
                    assertSame(initial, storage.getValue(), "foreign failure must not continue with a fabricated result");
                    assertEquals(1L, p.diagnostics().get("unsupportedTraps"));
                    assertEquals("reject-at-load", p.diagnostics().get("unsupportedPolicy"));
                    assertEquals("trap-when-reached", p.diagnostics().get("foreignUnsupportedPolicy"));
                    assertEquals(List.of(failure.getMessage()), p.diagnostics().get("deferredUnsupported"));
                    var handoff = language.getHandoffState().get();
                    assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getArguments().retainedReferences());
                    assertEquals(0, handoff.getResults().getDepth()); assertEquals(0, handoff.getResults().retainedReferences());
                    assertNull(handoff.getPending());
                    assertEquals(42L, Calls.target(target, new Object[]{0L, 0L, storage, replacement}));
                    assertSame(initial, storage.getValue()); assertEquals(1L, p.diagnostics().get("unsupportedTraps"));
                    // The stateful continuation is real, not an inert test expression.
                    var control = program(language, module(effectAfter(literal(7), integer)), backend, async);
                    assertEquals(99L, Calls.target(control.entryTarget("entry"), new Object[]{0L, 1L, storage, replacement}));
                    assertSame(replacement, storage.getValue());
                } finally { threads.leaveCurrent(); context.leave(); }
            }
        }
    }
    @Test void genuineInitLinkerStateTupleTrapsOnlyInTheSelectedAlternative() { checkReachedOnly("initLinker_", tuple(false)); }
    @Test void nonemptyTupleFailureDoesNotWriteOrBorrowAResult() { checkReachedOnly("unavailable_state_int", tuple(true)); }
    @Test void scalarFailureNeverFabricatesAResult() { checkReachedOnly("unavailable_scalar", integer); }

    @Test void knownMalformedAbiStillRejectsAnUnselectedAlternativeAtPreparation() {
        var original = descriptor("setHeapSize", tuple(false));
        var target = Map.<String, Object>of("kind", "static", "symbol", "setHeapSize", "unit", "ghc-9.14.1-inplace", "isFunction", true);
        var bad = changed(changed(original, "target", target), "safety", "safe");
        assertPreparationFailure(module(effectAfter(foreignCall(bad, tuple(false)), tuple(false))), RuntimeFault.class, "Invalid");
        var polyglot = changed(original, "target", Map.of("kind", "static", "symbol", "thc_polyglot_v1_eval", "isFunction", true));
        assertPreparationFailure(module(effectAfter(foreignCall(polyglot, tuple(false)), tuple(false))), RuntimeFault.class,
            "Invalid polyglot foreign call: calling convention");
    }
    @Test void missingBindingsAndUnknownPrimitivesStillRejectAtPreparation() {
        assertPreparationFailure(module(List.of("var", "Missing.libraryBody")), UnsupportedCore.class, "Unresolved external binding");
        assertPreparationFailure(module(List.of("app", List.of("prim", "futurePrim#"), List.of(), List.of())),
            UnsupportedCore.class, "Unsupported primitive futurePrim#");
    }
    private void assertPreparationFailure(Map<String, Object> input, Class<? extends RuntimeFault> type, String message) {
        for (String backend : List.of("ast", "bytecode")) for (boolean async : List.of(false, true)) {
            try (var context = Main.executionContext(false)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var failure = assertThrows(type, () -> program(language, input, backend, async).entryTarget("entry"));
                    assertEquals(type, failure.getClass()); assertTrue(failure.getMessage().contains(message), failure.getMessage());
                } finally { context.leave(); }
            }
        }
    }
}
