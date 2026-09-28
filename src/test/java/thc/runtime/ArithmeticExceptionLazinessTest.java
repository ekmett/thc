// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarValueTestSupport.*;

/** Fixture-independent check that implicit original payloads remain lazy. */
class ArithmeticExceptionLazinessTest {
    private Context context() {
        return Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
            .option("compiler.Inlining", "false").option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false").option("engine.SingleTierCompilationThreshold", "10000000")
            .option("engine.CompilationFailureAction", "Throw").build();
    }
    private Map<String, Object> binding(String id, List<Object> expression, Map<String, Object> boxed) {
        return map("id", id, "name", id, "type", "SomeException", "lifted", true, "arity", 0, "rep", boxed, "expr", expression);
    }
    @Test void implicitPayloadIsLazyAndFailureMemoizationSharesItInBothBackends() {
        var boxed = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
        var empty = map("kind", "unknown", "primReps", list(), "aggregate", "unboxed-tuple", "components", list(), "evaluated", true);
        for (var name : list("raiseDivZero#", "raiseOverflow#", "raiseUnderflow#")) for (var backend : list("ast", "bytecode")) {
            var payloadId = Objects.requireNonNull(CoreArithmeticExceptions.payload(name));
            // This deliberate bottom is a strictness control only; the native
            // fixture validates the actual exported exception dictionaries.
            var module = map("schema", 1, "ghc", "9.14.1", "module", "Test.ArithmeticLaziness",
                "bindings", list(binding(payloadId, list("var", payloadId, map("rep", boxed)), boxed),
                    binding("entry", list("app", list("prim", name), list(list("con", "Empty", 0, map("rep", empty))),
                        list(false), false, false, map("rep", boxed)), boxed)),
                "constructors", list(map("id", "Empty", "name", "(# #)", "arity", 0, "kind", "unboxed-tuple", "tag", 1,
                    "strictFields", list(), "fieldLifted", list(), "fieldReps", list())));
            try (var context = context()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var linked = CoreModules.reachable(module, "entry", true);
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                    var payload = (Thunk) program.entryValue(payloadId);
                    var first = assertThrows(GuestException.class, () -> Calls.target(program.hostEntryTarget(), new Object[]{program.entryValue("entry"), new Object[0]}));
                    var second = assertThrows(GuestException.class, () -> Calls.target(program.hostEntryTarget(), new Object[]{program.entryValue("entry"), new Object[0]}));
                    assertNotSame(first, second);
                    assertSame(payload, first.getPayload());
                    assertSame(first.getPayload(), second.getPayload());
                    assertEquals(0, payload.getState(), backend + "/" + name + " never enters its implicit exception CAF");
                    assertEquals(1L, program.diagnostics().get("thunkEvaluations"));
                    assertEquals(0L, program.diagnostics().get("blackholes"));
                } finally { context.leave(); }
            }
        }
    }
}
