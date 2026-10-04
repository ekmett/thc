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
    private Map<String, Object> floatingCall(String kind, String operation, String variant) {
        var proof = map("kind", kind, "primReps", list(kind.equals("float") ? "FloatRep" : "DoubleRep"), "evaluated", true);
        var resultProof = variant.equals("result")
            ? map("kind", kind.equals("float") ? "double" : "float", "primReps", list(kind.equals("float") ? "DoubleRep" : "FloatRep"), "evaluated", true) : proof;
        var parameter = map("id", "x", "name", "x", "lifted", false, "rep", proof);
        var operand = list("var", "x", map("rep", proof));
        var arguments = variant.equals("arity") ? list() : operation.startsWith("power") || operation.equals("**##")
            ? list(operand, operand) : list(operand);
        var body = list("app", list("prim", operation), arguments,
            variant.equals("arity") ? list() : arguments.size() == 2 ? list(false, false) : list(false), false, false, map("rep", resultProof));
        var binding = map("id", "entry", "name", "entry", "arity", 1, "lifted", true, "rep", closure,
            "expr", list("lam", list(parameter), body, map("rep", closure, "resultRep", resultProof)));
        return map("bindings", list(binding), "constructors", list(), "instrument", true);
    }
    private record MathCase(String operation, double input, double expected) {}
    @Test void scalarMathAndSqrtEdgesSurviveFirstCompiledCalls() throws Exception {
        // Exact mathematical identities, domain controls and independently rounded
        // sqrt(2)/sqrt(min-subnormal), rather than another JVM Math oracle.
        var cases = list(new MathCase("sqrt", 4, 2), new MathCase("sqrt", 0, 0),
            new MathCase("sqrt", -0.0, -0.0), new MathCase("sqrt", -1, Double.NaN),
            new MathCase("sqrt", Double.NaN, Double.NaN), new MathCase("sqrt", Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
            new MathCase("sqrt", Double.NEGATIVE_INFINITY, Double.NaN),
            new MathCase("fabs", -3, 3), new MathCase("exp", 0, 1), new MathCase("expm1", -0.0, -0.0),
            new MathCase("log", 1, 0), new MathCase("log1p", -0.0, -0.0),
            new MathCase("sin", -0.0, -0.0), new MathCase("cos", 0, 1),
            new MathCase("tan", -0.0, -0.0), new MathCase("asin", -0.0, -0.0),
            new MathCase("acos", 1, 0), new MathCase("atan", -0.0, -0.0),
            new MathCase("sinh", -0.0, -0.0), new MathCase("cosh", 0, 1),
            new MathCase("tanh", -0.0, -0.0), new MathCase("power", 2, 4));
        for (var backend : list("ast", "bytecode")) try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw")
                .option("engine.SingleTierCompilationThreshold", "10000000").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var kind : list("float", "double")) {
                    var selected = new ArrayList<>(cases);
                    selected.add(new MathCase("sqrt", 2, kind.equals("float")
                        ? Float.intBitsToFloat(0x3fb504f3) : Double.longBitsToDouble(0x3ff6a09e667f3bcdL)));
                    selected.add(new MathCase("sqrt", kind.equals("float") ? Float.MIN_VALUE : Double.MIN_VALUE,
                        kind.equals("float") ? Float.intBitsToFloat(0x1a3504f3) : Double.longBitsToDouble(0x1e60000000000000L)));
                    for (var operation : selected.stream().map(MathCase::operation).distinct().toList()) {
                        var name = operation.equals("power") && kind.equals("double") ? "**##"
                            : operation + (kind.equals("float") ? "Float#" : "Double#");
                        var source = floatingCall(kind, name, "valid");
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, source) : new BytecodeProgram(language, source);
                        var target = program.entryTarget("entry");
                        var host = program.hostEntryTarget(1); var entry = program.entryValue("entry");
                        var rows = selected.stream().filter(row -> row.operation.equals(operation)).toList();
                        boolean compiled = Set.of("sqrt", "fabs", "power").contains(operation);
                        for (int phase = 0; phase < (compiled ? 2 : 1); phase++) {
                            if (phase == 1) {
                                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                            }
                            // Compile after the ordinary first input; the edge inputs stay cold.
                            for (var row : phase == 0 ? rows.subList(0, 1) : rows) {
                                Object input;
                                if (kind.equals("float")) input = (float) row.input; else input = row.input;
                                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                                var actual = Calls.target(host, new Object[]{entry, new Object[]{input}});
                                var label = backend + "/" + name + "/" + row.input;
                                if (kind.equals("float")) {
                                    assertInstanceOf(Float.class, actual, label);
                                    if (Double.isNaN(row.expected)) assertTrue(Float.isNaN((Float) actual), label);
                                    else assertEquals(Float.floatToRawIntBits((float) row.expected), Float.floatToRawIntBits((Float) actual), label);
                                } else {
                                    assertInstanceOf(Double.class, actual, label);
                                    if (Double.isNaN(row.expected)) assertTrue(Double.isNaN((Double) actual), label);
                                    else assertEquals(Double.doubleToRawLongBits(row.expected), Double.doubleToRawLongBits((Double) actual), label);
                                }
                                if (phase == 1) {
                                    assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before, label);
                                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label);
                                }
                            }
                        }
                    }
                    for (var variant : list("result", "arity")) {
                        var source = floatingCall(kind, "sqrt" + (kind.equals("float") ? "Float#" : "Double#"), variant);
                        var failure = assertThrows(RuntimeFault.class, () -> {
                            ExecutableProgram program = backend.equals("ast") ? new Program(language, source) : new BytecodeProgram(language, source);
                            program.entryTarget("entry");
                        });
                        assertTrue(Objects.toString(failure.getMessage(), "").contains(variant.equals("arity")
                            ? "Primitive arity mismatch" : "Conflicting Core representation proofs"), failure.getMessage());
                    }
                }
            } finally { context.leave(); }
        }
    }
    @Test void typedPayloadCannotClaimADifferentFloatingKind() {
        assertThrows(UnsupportedCore.class, () -> new CoreFloatingLiteral.Single(0).decode("double"));
        assertThrows(UnsupportedCore.class, () -> new CoreFloatingLiteral.Double(0).decode("float"));
        assertThrows(UnsupportedCore.class, () -> new CoreFloatingLiteral.Single(0).decode("int"));
        assertThrows(UnsupportedCore.class, () -> CoreFloatingLiteral.fromDocument(Map.of("floatBits", "0")).decode("double"));
        assertThrows(UnsupportedCore.class, () -> CoreFloatingLiteral.fromDocument(Map.of("floatBits", "0", "doubleBits", "0")));
        assertThrows(NumberFormatException.class, () -> CoreFloatingLiteral.fromDocument(Map.of("floatBits", "100000000")));
        assertThrows(NumberFormatException.class, () -> CoreFloatingLiteral.fromDocument(Map.of("doubleBits", "not-bits")));
    }
}
