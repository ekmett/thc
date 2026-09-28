// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarValueTestSupport.*;

class CoreFloatingLiteralTest {
    private final Map<String, Object> closure = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private Map<String, Object> binding(String id, String kind, Object value) {
        var proof = map("kind", kind, "primReps", list(kind.equals("float") ? "FloatRep" : "DoubleRep"), "evaluated", true);
        var parameter = map("id", "unused", "name", "unused", "lifted", false,
            "rep", map("kind", "long", "primReps", list("IntRep"), "evaluated", true));
        return map("id", id, "name", id, "lifted", true, "arity", 1, "rep", closure,
            "expr", list("lam", list(parameter), list("lit", kind, value, map("rep", proof)), map("rep", closure, "resultRep", proof)));
    }
    private record Literal(String id, String kind, CoreFloatingLiteral value) {}
    private void check(List<Literal> literals, String backend, String id, Object value) {
        CoreFloatingLiteral literal = null;
        for (var candidate : literals) if (candidate.id().equals(id)) { literal = candidate.value(); break; }
        switch (literal) {
            case CoreFloatingLiteral.Single single -> assertEquals(single.bits(), Float.floatToRawIntBits((Float) value), backend + "/" + id);
            case CoreFloatingLiteral.Double wide -> assertEquals(wide.bits(), Double.doubleToRawLongBits((Double) value), backend + "/" + id);
            case null -> {
                if (id.equals("jsonFloat")) assertEquals(Integer.MIN_VALUE, Float.floatToRawIntBits((Float) value));
                else assertEquals(1.25, value);
            }
        }
    }
    @Test void rawBitsAndLegacyStringsSurviveInterpretedAndFirstInstalledCalls() throws Exception {
        var literals = list(
            new Literal("fzero", "float", new CoreFloatingLiteral.Single(Integer.MIN_VALUE)),
            new Literal("fnan", "float", new CoreFloatingLiteral.Single(0x7fc01234)),
            new Literal("fsubnormal", "float", new CoreFloatingLiteral.Single(1)),
            new Literal("dzero", "double", new CoreFloatingLiteral.Double(Long.MIN_VALUE)),
            new Literal("dnan", "double", new CoreFloatingLiteral.Double(0x7ff8000000005678L)),
            new Literal("dsubnormal", "double", new CoreFloatingLiteral.Double(1)));
        var bindings = new ArrayList<Map<String, Object>>();
        for (var literal : literals) bindings.add(binding(literal.id(), literal.kind(), literal.value()));
        bindings.add(binding("jsonFloat", "float", "-0.0"));
        bindings.add(binding("jsonDouble", "double", "1.25"));
        for (var backend : list("ast", "bytecode")) try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw")
                .option("engine.SingleTierCompilationThreshold", "10000000").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var module = map("bindings", bindings, "constructors", list(), "instrument", true);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module, false);
                for (var binding : bindings) {
                    var id = (String) binding.get("id");
                    var target = program.entryTarget(id);
                    check(literals, backend, id, Calls.target(target, new Object[]{0L, 0L}));
                    var targetType = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                    targetType.getMethod("compile", boolean.class).invoke(target, true);
                    assertEquals(true, targetType.getMethod("isValidLastTier").invoke(target));
                    var runtime = Truffle.getRuntime();
                    runtime.getClass().getMethod("bypassedInstalledCode", targetType).invoke(runtime, target);
                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    check(literals, backend, id, Calls.target(target, new Object[]{0L, 0L}));
                    assertSame(target, program.entryTarget(id));
                    assertEquals(true, targetType.getMethod("isValidLastTier").invoke(target));
                    assertEquals(1L, ((Number) program.diagnostics().get("compiledEntries")).longValue() - before);
                }
            } finally { context.leave(); }
        }
    }
    @Test void typedPayloadCannotClaimADifferentFloatingKind() {
        assertThrows(UnsupportedCore.class, () -> new CoreFloatingLiteral.Single(0).decode("double"));
        assertThrows(UnsupportedCore.class, () -> new CoreFloatingLiteral.Double(0).decode("float"));
        assertThrows(UnsupportedCore.class, () -> new CoreFloatingLiteral.Single(0).decode("int"));
    }
}
