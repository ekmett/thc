// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.TruffleLanguage;
import java.util.*;
import java.util.function.Consumer;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreBackendTestSupport.*;

class GuestExceptionsTest {
    private List<Object> variable(String id) { return list("var", id); }
    private List<Object> raised(List<Object> payload) { return list("app", list("prim", "raise#"), list(payload), list(true)); }
    private Map<String, Object> binding(String id, List<Object> expression) { return binding(id, expression, 0); }
    private Map<String, Object> binding(String id, List<Object> expression, int arity) {
        return map("id", id, "name", id, "type", "Synthetic", "lifted", true, "arity", arity, "expr", expression);
    }
    private Map<String, Object> module(List<Map<String, Object>> bindings) { return module(bindings, list()); }
    private Map<String, Object> module(List<Map<String, Object>> bindings, List<Map<String, Object>> constructors) {
        return map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.Raise", "bindings", bindings, "constructors", constructors);
    }
    private void runWithProgram(Map<String, Object> data, Consumer<Program> action) {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try { action.accept(new Program(TruffleLanguage.LanguageReference.create(Language.class).get(null), data, false, false)); }
            finally { context.leave(); }
        }
    }
    private Object invoke(Program program) { return Calls.target(program.hostEntryTarget(0), new Object[]{program.entryValue("entry"), new Object[0]}); }
    @Test void raiseKeepsBottomPayloadUnevaluatedAndMemoizesFailureData() {
        var data = module(list(binding("payload", variable("payload")), binding("entry", raised(variable("payload")))));
        runWithProgram(data, program -> {
            var payload = (Thunk) program.entryValue("payload");
            var first = assertThrows(GuestException.class, () -> invoke(program));
            assertSame(payload, first.getPayload()); assertEquals(0, payload.getState(), "raise# must not force its exception argument");
            var second = assertThrows(GuestException.class, () -> invoke(program));
            assertNotSame(first, second, "Concurrent unwinds need separate mutable Truffle stack traces");
            assertSame(first.getPayload(), second.getPayload(), "The lazy Haskell payload remains shared");
            assertEquals(1L, program.diagnostics().get("thunkEvaluations")); assertEquals(0L, program.diagnostics().get("blackholes"));
        });
    }
    @Test void raisingAnEvaluatedConstructorPreservesItsPayloadIdentity() {
        var constructor = map("id", "Payload", "name", "Payload", "arity", 0, "tag", 1, "kind", "boxed", "strictFields", list(), "fieldLifted", list(), "fieldReps", list());
        var data = module(list(binding("payload", list("con", "Payload", 0)), binding("entry", raised(variable("payload")))), list(constructor));
        runWithProgram(data, program -> {
            var payload = (DataValue) program.entryValue("payload"); var exception = assertThrows(GuestException.class, () -> invoke(program));
            assertSame(payload, exception.getPayload()); assertEquals("Payload", ((DataValue) exception.getPayload()).getLayout().getName());
        });
    }
    @Test void coldRaiseBranchStaysLazyCompilesAndReportsAGuestException() {
        var body = list("case", variable("input"), "choice", list(list("default", null, list(), variable("input")), list("lit", list("int", "0"), list(), raised(variable("payload")))));
        var input = map("id", "input", "name", "input", "type", "Int#", "lifted", false, "coercion", false);
        var data = module(list(binding("payload", variable("payload")), binding("entry", list("lam", list(input), body), 1)));
        var request = Json.stringify(map("entry", "entry", "modules", list(data)));
        try (var context = Main.executionContext(false)) {
            var function = context.eval("thc", request);
            for (int i = 0; i < 40; i++) assertEquals(17L, function.execute(17L).asLong());
            assertTrue(function.invokeMember("compile").asBoolean()); assertEquals(23L, function.execute(23L).asLong());
            var exception = assertThrows(PolyglotException.class, () -> function.execute(0L));
            assertTrue(exception.isGuestException()); assertFalse(exception.isHostException());
            var metrics = object(Json.parse(function.getMember("diagnostics").asString()));
            assertEquals(0L, metrics.get("blackholes")); assertEquals(0L, metrics.get("thunkEvaluations"), "The bottom exception payload is still not entered");
            assertTrue(((Number) metrics.get("compiledEntries")).longValue() > 0);
        }
    }
}
