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
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;

/** Small shared GC/heap-hint ABI models; installed-original acquisition is separate. */
public class CompilerHeapHintTest {
    private Map<String, Object> scalar(String rep, boolean evaluated) {
        return Map.of("kind", rep == null ? "void" : rep.equals("AddrRep") ? "address" : rep.startsWith("BoxedRep") ? "closure" : "long",
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

    // Independent GHC 9.14.1 signatures; no installed-Core fixture is needed for
    // the JVM boundary's buffer safety, return values or malformed ABI controls.
    private final String[][] gcCalls = {
        {"getRTSStatsEnabled", "IntRep", "safe"}, {"getRTSStats", "", "safe"},
        {"performGC", "", "safe"}, {"performMajorGC", "", "safe"},
        {"performBlockingMajorGC", "", "safe"}
    };
    private Map<String, Object> gcTuple(String rep, boolean evaluated) {
        return rep.isEmpty() ? tuple(evaluated) : Map.of("kind", "unknown", "aggregate", "unboxed-tuple",
            "primReps", List.of(rep), "evaluated", evaluated, "components", List.of(state, scalar(rep, true)));
    }
    private Map<String, Object> gcDeclaration(String[] call) {
        var arguments = call[0].equals("getRTSStats") ? List.of(scalar("AddrRep", false), scalar(null, false)) : List.of(scalar(null, false));
        return Map.of("schema", 1, "target", Map.of("kind", "static", "symbol", call[0], "unit", "ghc-internal", "isFunction", true),
            "convention", "ccall", "safety", call[2], "arity", arguments.size(), "suppliedArity", arguments.size(),
            "argumentReps", arguments, "resultRep", gcTuple(call[1], false));
    }
    @Test public void gcCallsRequireTheOriginalUnitSafetyAndStateResultAbi() {
        for (var call : gcCalls) {
            var declaration = gcDeclaration(call); var result = gcTuple(call[1], true);
            var arguments = call[0].equals("getRTSStats") ? List.of(scalar("AddrRep", true), state) : List.of(state);
            var flags = java.util.Collections.nCopies(arguments.size(), false);
            assertEquals(call[0], validate(declaration, arguments, flags, result).getSymbol());
            @SuppressWarnings("unchecked") var target = (Map<String, Object>) declaration.get("target");
            for (var wrong : List.of(plus(declaration, "target", plus(target, "unit", "main")),
                    plus(declaration, "target", plus(target, "isFunction", false)),
                    plus(declaration, "safety", call[2].equals("safe") ? "unsafe" : "safe"),
                    plus(declaration, "arity", 99), plus(declaration, "suppliedArity", 0), plus(declaration, "resultRep", integer)))
                assertThrows(RuntimeFault.class, () -> validate(wrong, arguments, flags, result), call[0]);
            assertThrows(RuntimeFault.class, () -> validate(declaration, arguments.subList(0, arguments.size() - 1), flags, result));
        }
    }
    Map<String, Object> gcModule(String[] call) {
        boolean stats = call[0].equals("getRTSStats"), query = !call[1].isEmpty();
        var proof = gcTuple(call[1], true); var result = query ? scalar(call[1], true) : integer;
        var operands = stats ? List.of(List.of("var", "buffer", Map.of("rep", scalar("AddrRep", true))),
            List.of("var", "state", Map.of("rep", state))) : List.of(List.of("var", "state", Map.of("rep", state)));
        var application = List.of("app", head, operands, java.util.Collections.nCopies(operands.size(), false),
            false, false, Map.of("foreignCall", gcDeclaration(call), "rep", proof));
        var fields = query ? List.of(Map.of("id", "next", "lifted", false, "rep", state),
            Map.of("id", "answer", "lifted", false, "rep", result)) : List.of(Map.of("id", "next", "lifted", false, "rep", state));
        var body = List.of("case", application, "tuple", List.of(List.of("data", query ? "tuple2" : "tuple1",
            query ? List.of("next", "answer") : List.of("next"), stats ? List.of("lit", "int", "42", Map.of("rep", integer))
                : List.of("var", query ? "answer" : "bytes", Map.of("rep", result)),
            Map.of("binders", fields))), Map.of("rep", result, "binder", Map.of("id", "tuple", "lifted", false, "rep", proof)));
        var parameter = Map.of("id", stats ? "buffer" : "bytes", "lifted", false, "rep", stats ? scalar("AddrRep", true) : integer);
        return Map.of("instrument", true, "constructors", List.of(
            Map.of("id", "tuple1", "kind", "unboxed-tuple", "arity", 1, "tag", 1),
            Map.of("id", "tuple2", "kind", "unboxed-tuple", "arity", 2, "tag", 1)),
            "bindings", List.of(Map.of("id", "gc", "name", "gc", "arity", 2, "lifted", true, "rep", closure,
                "expr", List.of("lam", List.of(parameter, Map.of("id", "state", "lifted", false, "rep", state)),
                    body, Map.of("rep", closure, "resultRep", result)))));
    }
    @Test public void gcCallsReturnHonestResultsFromTheFirstCompiledCall() throws ReflectiveOperationException {
        for (var backend : List.of("ast", "bytecode")) for (var call : gcCalls) {
            if (call[0].equals("getRTSStats")) continue;
            try (var context = context()) {
                context.initialize("thc"); context.enter(); var threads = Language.currentState().getThreads(); threads.enterCurrent(null, false, true, null);
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, gcModule(call), true) : new BytecodeProgram(language, gcModule(call));
                    var target = program.entryTarget("gc");
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    long compiledBefore = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    long answer = ((Number) Calls.target(target, new Object[]{0L, 42L, Unit.INSTANCE})).longValue();
                    assertEquals(call[0].equals("getRTSStatsEnabled") ? 0L : 42L, answer, backend + "/" + call[0]);
                    assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > compiledBefore, "First call executed compiled code");
                    assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                    assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
                } finally { threads.leaveCurrent(); context.leave(); }
            }
        }
    }
    @Test public void unavailableStatsDoesNotReadOrWriteItsBufferOnEitherBackend() {
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, gcModule(gcCalls[1])) : new BytecodeProgram(language, gcModule(gcCalls[1]));
                var target = program.entryTarget("gc"); var bytes = new byte[]{11, 22, 33, 44};
                // Null is independently useful: rejection must precede any buffer access.
                for (var buffer : List.of(ManagedAddress.fromByteArray(bytes), ManagedAddress.nullAddress())) {
                    var failure = assertThrows(RuntimeFault.class, () -> callScalarTestTarget(target, new Object[]{0L, buffer, Unit.INSTANCE}));
                    assertEquals("GHC RTS statistics are unavailable on the JVM; getRTSStatsEnabled is false", failure.getMessage());
                    assertArrayEquals(new byte[]{11, 22, 33, 44}, bytes);
                }
                assertThrows(RuntimeFault.class, () -> callScalarTestTarget(target, new Object[]{0L, ManagedAddress.fromByteArray(bytes), "not-state"}));
                assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
            } finally { context.leave(); }
        }
    }
}
