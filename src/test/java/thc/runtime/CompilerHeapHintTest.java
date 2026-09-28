// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.ContextProfile;
import thc.Language;
import thc.Main;
import static org.junit.jupiter.api.Assertions.*;

/** Small ABI models; retained original parseDynamicFlagsFull is checked separately. */
public class CompilerHeapHintTest {
    private Map<String, Object> scalar(String rep, boolean evaluated) {
        return Map.of("kind", rep == null ? "void" : rep.startsWith("BoxedRep") ? "closure" : "long",
            "primReps", rep == null ? List.of() : List.of(rep), "evaluated", evaluated);
    }
    private final Map<String, Object> integer = scalar("IntRep", true), state = scalar(null, true), closure = scalar("BoxedRep (Just Lifted)", true);
    private Map<String, Object> tuple(boolean evaluated) {
        return Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "primReps", List.of(), "evaluated", evaluated, "components", List.of(state));
    }
    private final Map<String, Object> target = Map.of("kind", "static", "symbol", "setHeapSize", "unit", "ghc-9.14.1-inplace", "isFunction", true);
    private final Map<String, Object> declaration = Map.of("schema", 1, "target", target, "convention", "ccall", "safety", "unsafe", "arity", 2, "suppliedArity", 2,
        "argumentReps", List.of(scalar("IntRep", false), scalar(null, false)), "resultRep", tuple(false));
    private final List<?> head = List.of("var", "original-setHeapSize-FCallId", Map.of("rep", closure));
    private Map<String, Object> plus(Map<String, Object> map, String key, Object value) {
        var result = new LinkedHashMap<>(map); result.put(key, value); return result;
    }
    private GcForeignOp validate(Map<String, Object> call, List<?> arguments, List<?> flags, Object result) {
        return CoreGcForeign.validate(Map.of("foreignCall", call, "rep", result), arguments, flags, result);
    }
    private GcForeignOp validate(Map<String, Object> call) { return validate(call, List.of(integer, state), List.of(false, false), tuple(true)); }
    @Test public void originalCompilerUnitAndExactUnsafeStateAbiAreRequired() {
        assertEquals(GcForeignOp.HEAP_HINT, validate(declaration));
        CoreGcForeign.validateHead(head, false);
        assertThrows(RuntimeFault.class, () -> CoreGcForeign.validateHead(head, true));
        var wrong = new ArrayList<Map<String, Object>>();
        wrong.add(plus(declaration, "target", plus(target, "unit", "ghc-internal")));
        wrong.add(plus(declaration, "target", plus(target, "unit", "ghc-9.14.1-other")));
        wrong.add(plus(declaration, "target", plus(target, "isFunction", false)));
        wrong.add(plus(declaration, "target", plus(target, "kind", "dynamic")));
        wrong.add(plus(declaration, "schema", true)); wrong.add(plus(declaration, "safety", "safe"));
        wrong.add(plus(declaration, "safety", "interruptible")); wrong.add(plus(declaration, "convention", "capi"));
        wrong.add(plus(declaration, "arity", 1)); wrong.add(plus(declaration, "suppliedArity", 1));
        wrong.add(plus(declaration, "argumentReps", List.of(scalar("WordRep", false), scalar(null, false)))); wrong.add(plus(declaration, "resultRep", integer));
        for (var call : wrong) assertThrows(RuntimeFault.class, () -> validate(call));
        assertNull(validate(plus(declaration, "target", plus(target, "symbol", "setHeapSize_alias"))));
        assertNull(validate(plus(declaration, "target", plus(target, "symbol", "enableTimingStats"))));
        assertThrows(RuntimeFault.class, () -> validate(declaration, List.of(scalar("WordRep", true), state), List.of(false, false), tuple(true)));
        assertThrows(RuntimeFault.class, () -> validate(declaration, List.of(integer, integer), List.of(false, false), tuple(true)));
        assertThrows(RuntimeFault.class, () -> validate(declaration, List.of(integer), List.of(false, false), tuple(true)));
        assertThrows(RuntimeFault.class, () -> validate(declaration, List.of(integer, state), List.of(true, false), tuple(true)));
        assertThrows(RuntimeFault.class, () -> validate(declaration, List.of(integer, state), List.of(false, false), integer));
    }
    private Map<String, Object> module(Map<String, Object> call, Object hint) {
        var application = List.of("app", head, List.of(hint, List.of("var", "state", Map.of("rep", state))),
            List.of(false, false), false, false, Map.of("foreignCall", call, "rep", tuple(true)));
        var body = List.of("case", application, "tuple", List.of(List.of("data", "tuple1", List.of("next"),
            List.of("var", "bytes", Map.of("rep", integer)), Map.of("binders", List.of(Map.of("id", "next", "lifted", false, "rep", state))))),
            Map.of("rep", integer, "binder", Map.of("id", "tuple", "lifted", false, "rep", tuple(true))));
        return Map.of("instrument", true,
            "constructors", List.of(Map.of("id", "tuple1", "kind", "unboxed-tuple", "arity", 1, "tag", 1)),
            "bindings", List.of(Map.of("id", "hint", "name", "hint", "arity", 2, "lifted", true, "rep", closure,
                "expr", List.of("lam", List.of(Map.of("id", "bytes", "lifted", false, "rep", integer), Map.of("id", "state", "lifted", false, "rep", state)),
                    body, Map.of("rep", closure, "resultRep", integer)))));
    }
    private Map<String, Object> module(Map<String, Object> call) { return module(call, List.of("var", "bytes", Map.of("rep", integer))); }
    private Context context() { return Main.withContextProfile(Context.newBuilder("thc"), ContextProfile.SYNCHRONOUS_TEST).build(); }
    @Test public void bothBackendsEvaluateTheHintAndPreserveTheStateOnlyResult() {
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter(); var threads = Language.currentState().getThreads(); threads.enterCurrent(null, false, true, null);
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, module(declaration)) : new BytecodeProgram(language, module(declaration));
                var target = program.entryTarget("hint");
                for (long bytes : new long[]{Long.MIN_VALUE, -1L, 0L, 1L, 4095L, 4096L, 4097L, Long.MAX_VALUE})
                    assertEquals(bytes, Calls.target(target, new Object[]{0L, bytes, thc.runtime.Unit.INSTANCE}), backend + "/" + bytes);
                assertThrows(RuntimeFault.class, () -> Calls.target(target, new Object[]{0L, 42L, "not-state"}));
                assertEquals(42L, Calls.target(target, new Object[]{0L, 42L, thc.runtime.Unit.INSTANCE}));
                assertEquals(0, language.getHandoffState().get().getResults().getDepth()); assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
            } finally { threads.leaveCurrent(); context.leave(); }
        }
    }
    @Test public void unknownSymbolsStillRejectWhenReachedThroughBothFullLowerers() {
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var input = module(plus(declaration, "target", plus(target, "symbol", "setHeapSize_alias")));
                ExecutableProgram program = backend.equals("ast") ? new Program(language, input) : new BytecodeProgram(language, input);
                var target = program.entryTarget("hint");
                assertEquals(0L, program.diagnostics().get("unsupportedTraps"));
                var error = assertThrows(UnsupportedCore.class, () -> Calls.target(target, new Object[]{0L, 42L, Unit.INSTANCE}));
                assertEquals("Unsupported foreign call: setHeapSize_alias", error.getMessage());
                assertEquals(1L, program.diagnostics().get("unsupportedTraps"));
            } finally { context.leave(); }
        }
    }
    @Test public void discardedHintOperandIsStillEvaluated() {
        var quotient = List.of("app", List.of("prim", "quotInt#"), List.of(List.of("lit", "int", "1", Map.of("rep", integer)),
            List.of("lit", "int", "0", Map.of("rep", integer))), List.of(false, false), false, false, Map.of("rep", integer));
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter(); var threads = Language.currentState().getThreads(); threads.enterCurrent(null, false, true, null);
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var input = module(declaration, quotient);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, input) : new BytecodeProgram(language, input);
                var target = program.entryTarget("hint");
                assertThrows(ArithmeticException.class, () -> Calls.target(target, new Object[]{0L, 42L, thc.runtime.Unit.INSTANCE}));
            } finally { threads.leaveCurrent(); context.leave(); }
        }
    }
}
