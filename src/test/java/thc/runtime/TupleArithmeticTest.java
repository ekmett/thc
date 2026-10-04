// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.Language;
import java.io.File;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Arithmetic values, field order and division faults from native GHC's boundary oracle.
 * Consumes tuple-arithmetic post-core CBD and oracle.tsv; produces no files.
 * WordCarryTest owns cross-call carry transport. Annotation aliases and malformed
 * shapes use representative two-field and three-field operations. */
@SuppressWarnings("unchecked")
class TupleArithmeticTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final List<String> names = List.of("quotRemInt", "quotRemWord", "addIntC", "subIntC", "plusWord2", "timesWord2", "addWordC", "subWordC", "timesInt2");
    private Map<String, Object> module() throws Exception { return thc.CoreCbdFixtures.read(new File(root, "build/tuple-arithmetic/post-core/TupleArithmeticAudit.cbd").toPath()); }
    private Context context() { return Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build(); }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) { return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module); }
    private void valid(RootCallTarget target) throws ReflectiveOperationException { assertEquals(true, Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("isValidLastTier").invoke(target)); }
    private void compile(RootCallTarget target) throws ReflectiveOperationException {
        Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("compile", boolean.class).invoke(target, true); valid(target);
        var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target); valid(target);
    }
    private record Row(String name, long x, long y, List<Long> fields) {
        @Override public String toString() { return "Row(name=" + name + ", x=" + x + ", y=" + y + ", fields=" + fields + ")"; }
    }
    private List<Long> mathematical(Row row) {
        var modulus = BigInteger.ONE.shiftLeft(64); var x = BigInteger.valueOf(row.x); var y = BigInteger.valueOf(row.y); var unsignedX = x.mod(modulus); var unsignedY = y.mod(modulus);
        return switch (row.name) {
            case "quotRemInt" -> List.of(x.divide(y).longValue(), x.remainder(y).longValue());
            case "quotRemWord" -> List.of(unsignedX.divide(unsignedY).longValue(), unsignedX.remainder(unsignedY).longValue());
            case "timesInt2" -> { var result = x.multiply(y); yield List.of(result.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0 || result.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0 ? 1L : 0L, result.shiftRight(64).longValue(), result.longValue()); }
            case "addIntC", "subIntC" -> { var result = row.name.equals("addIntC") ? x.add(y) : x.subtract(y); yield List.of(result.longValue(), result.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0 || result.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0 ? 1L : 0L); }
            case "addWordC", "subWordC" -> { var result = row.name.equals("addWordC") ? unsignedX.add(unsignedY) : unsignedX.subtract(unsignedY); yield List.of(result.longValue(), result.signum() < 0 || result.compareTo(modulus) >= 0 ? 1L : 0L); }
            default -> { var result = row.name.equals("plusWord2") ? unsignedX.add(unsignedY) : unsignedX.multiply(unsignedY); yield List.of(result.shiftRight(64).longValue(), result.longValue()); }
        };
    }
    @Test void nativeFieldsAndUnboundedModelAgreeInBothBackendsAndInstalledCode() throws Exception {
        var rows = new ArrayList<Row>();
        for (String line : Files.readAllLines(new File(root, "build/tuple-arithmetic/oracle.tsv").toPath())) {
            var r = line.split("\t", -1);
            var fields = new ArrayList<Long>();
            for (int i = 3; i < r.length; i++) fields.add(Long.parseLong(r[i]));
            var row = new Row(r[0], Long.parseLong(r[1]), Long.parseLong(r[2]), fields);
            assertEquals(mathematical(row), row.fields, "Native " + row);
            rows.add(row);
        }
        assertEquals(new LinkedHashSet<>(names), new LinkedHashSet<>(rows.stream().map(Row::name).toList()));
        var module = module();
        for (String name : names) {
            var cases = new LinkedHashMap<List<Object>, Long>();
            for (var row : rows) if (row.name.equals(name))
                for (int field = 0; field < row.fields.size(); field++)
                    cases.put(List.of(row.x, row.y, (long) field), row.fields.get(field));
            ScalarValueTestSupport.nativeValues(module, "main:TupleArithmeticAudit." + name,
                cases, true, java.util.function.LongUnaryOperator.identity());
        }
    }
    private List<List<Object>> applications(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof List<?> values) { if (!values.isEmpty() && "app".equals(values.getFirst())) result.add((List<Object>) values); for (var child : values) result.addAll(applications(child)); }
        else if (value instanceof Map<?, ?> values) for (var child : values.values()) result.addAll(applications(child)); return result;
    }
    @Test void integralAnnotationsDoNotSelectArithmeticOrResultOrder() throws Exception {
        var integral = Set.of("IntRep", "WordRep", "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep", "Int32Rep", "Word32Rep", "Int64Rep", "Word64Rep");
        class Project {
            Object apply(Object value, String rep) {
                if (value instanceof Map<?, ?> values) { var result = new LinkedHashMap<Object, Object>(); for (var field : values.entrySet()) result.put(field.getKey(), apply(field.getValue(), rep)); return result; }
                if (value instanceof List<?> values) { var result = new ArrayList<Object>(); for (var child : values) result.add(apply(child, rep)); return result; }
                if (value instanceof String string) return integral.contains(string) ? rep : string; return value;
            }
        }
        var project = new Project();
        for (String backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter(); try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (String rep : List.of("Word64Rep")) for (String name : List.of("quotRemInt", "timesInt2")) {
                    // Project annotations consistently; logical tuple shape and order stay intact.
                    var input = (Map<String, Object>) project.apply(CoreModules.reachable(module(), "main:TupleArithmeticAudit." + name), rep); var p = program(language, input, backend); var expected = mathematical(new Row(name, -13L, 5L, List.of()));
                    for (int field = 0; field < expected.size(); field++) assertEquals(expected.get(field), Calls.target(p.hostEntryTarget(3), new Object[]{p.entryValue("main:TupleArithmeticAudit." + name), new Object[]{-13L, 5L, (long) field}}), backend + "/" + rep + "/" + name + "/" + field);
                }
            } finally { context.leave(); }
        }
    }
    private List<Object> primitiveApplication(Object module, String name) {
        List<Object> result = null;
        for (var app : applications(module)) { var fn = (List<?>) app.get(1); if (fn.subList(0, Math.min(2, fn.size())).equals(List.of("prim", name + "#"))) { if (result != null) throw new IllegalArgumentException("Collection contains more than one matching element."); result = app; } }
        if (result == null) throw new NoSuchElementException("Collection contains no element matching the predicate."); return result;
    }
    @Test void exactPrimitiveShapesAndSaturationAreRequired() throws Exception {
        List<Consumer<List<Object>>> mutations = List.of(
            app -> {
                var rep = (Map<String, Object>) ((Map<String, Object>) app.get(6)).get("rep");
                ((List<Object>) rep.get("components")).removeLast();
                ((List<Object>) rep.get("primReps")).removeLast();
            },
            app -> {
                var rep = (Map<String, Object>) ((Map<String, Object>) app.get(6)).get("rep");
                var last = ((List<Map<String, Object>>) rep.get("components")).getLast();
                last.put("kind", "float"); last.put("primReps", List.of("FloatRep"));
                var flat = (List<Object>) rep.get("primReps"); flat.set(flat.size() - 1, "FloatRep");
            },
            app -> ((List<Object>) app.get(3)).set(0, true),
            app -> {
                ((List<Object>) app.get(2)).removeLast();
                ((List<Object>) app.get(3)).removeLast();
                ((Map<String, Object>) app.get(6)).remove("callDemand");
            });
        for (String backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter(); try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (String name : List.of("quotRemInt", "timesInt2")) for (int index = 0; index < mutations.size(); index++) {
                    var module = CoreModules.reachable(module(), "main:TupleArithmeticAudit." + name); var app = primitiveApplication(module, name); mutations.get(index).accept(app); var input = new LinkedHashMap<>(module);
                    assertThrows(RuntimeFault.class, () -> program(language, input, backend), backend + "/" + name + "/mutation" + index);
                }
                for (String name : List.of("quotRemInt", "timesInt2")) { var module = CoreModules.reachable(module(), "main:TupleArithmeticAudit." + name); var app = primitiveApplication(module, name); var primitive = new ArrayList<>((List<?>) app.get(1)); app.clear(); app.addAll(primitive); assertThrows(UnsupportedCore.class, () -> program(language, module, backend), backend + "/" + name + " first-class"); }
            } finally { context.leave(); }
        }
    }
    @Test void undefinedDivisionInputsFailWithoutPublishingResultsAndValidCallsRecover() throws Exception {
        for (String backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter(); try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (String name : List.of("quotRemInt", "quotRemWord")) {
                    var invalid = new ArrayList<long[]>(); invalid.add(new long[]{1L, 0L}); if (name.equals("quotRemInt")) invalid.add(new long[]{Long.MIN_VALUE, -1L});
                    for (long[] pair : invalid) {
                        // Each undefined input is the first installed call on its own target;
                        // an earlier fault may invalidate that target's compiled code.
                        var input = new LinkedHashMap<>(CoreModules.reachable(module(), "main:TupleArithmeticAudit." + name)); input.put("instrument", true); var program = program(language, input, backend); var entry = program.entryTarget("main:TupleArithmeticAudit." + name);
                        java.util.function.LongSupplier count = () -> ((Number) program.diagnostics().get("compiledEntries")).longValue();
                        assertEquals(2L, Calls.target(entry, new Object[]{0L, 7L, 3L, 0L})); assertEquals(1L, Calls.target(entry, new Object[]{0L, 7L, 3L, 1L})); long beforeCompile = count.getAsLong(); compile(entry); assertEquals(beforeCompile, count.getAsLong(), "Compilation must not enter guest code");
                        var failure = assertThrows(RuntimeFault.class, () -> Calls.target(entry, new Object[]{0L, pair[0], pair[1], 0L}));
                        assertEquals("Undefined input to " + name + "#", failure.getMessage());
                        assertTrue(count.getAsLong() > beforeCompile, backend + "/" + name + ": cold error enters installed code");
                        var state = language.getHandoffState().get(); assertEquals(0, state.getResults().getDepth()); assertNull(state.getPending());
                        // Fault recovery promises correct arithmetic, not code retention.
                        assertEquals(2L, Calls.target(entry, new Object[]{0L, 7L, 3L, 0L})); assertEquals(1L, Calls.target(entry, new Object[]{0L, 7L, 3L, 1L}));
                        assertEquals(0, state.getResults().getDepth()); assertNull(state.getPending());
                    }
                }
            } finally { context.leave(); }
        }
    }
    @Test void narrowDivisionRejectsZeroAfterNarrowingForBothResultFields() {
        var operationsByWidth = List.of(List.of(TupleArithmeticOp.QUOT_REM_INT8, TupleArithmeticOp.QUOT_REM_WORD8), List.of(TupleArithmeticOp.QUOT_REM_INT16, TupleArithmeticOp.QUOT_REM_WORD16), List.of(TupleArithmeticOp.QUOT_REM_INT32, TupleArithmeticOp.QUOT_REM_WORD32));
        int[] widths = {8, 16, 32};
        for (int i = 0; i < widths.length; i++) { int bits = widths[i]; for (var operation : operationsByWidth.get(i)) for (int zero : new int[]{0, (int) (1L << bits)}) {
            assertEquals("Undefined input to " + operation.getPrimitive(), assertThrows(RuntimeFault.class, () -> operation.firstInt(7, zero)).getMessage());
            assertEquals("Undefined input to " + operation.getPrimitive(), assertThrows(RuntimeFault.class, () -> operation.secondInt(7, zero)).getMessage()); assertEquals(2, operation.firstInt(7, 3)); assertEquals(1, operation.secondInt(7, 3));
        } }
    }
    @Test void narrowDivisionFirstInstalledResultAndColdFaultRetainExistingSemantics() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter(); try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var sample : List.of(Map.entry(TupleArithmeticOp.QUOT_REM_INT8, 0),
                                          Map.entry(TupleArithmeticOp.QUOT_REM_WORD32, 1))) {
                    var operation = sample.getKey();
                    int field = sample.getValue();
                    final int resultField = field;
                    class ArithmeticRoot extends RootNode {
                        long compiledEntries = 0L;
                        ArithmeticRoot() { super(language); }
                        @Override public Object execute(VirtualFrame frame) { if (CompilerDirectives.inCompiledCode()) compiledEntries++; int left = (Integer) frame.getArguments()[0], right = (Integer) frame.getArguments()[1]; return resultField == 0 ? operation.firstInt(left, right) : operation.secondInt(left, right); }
                    }
                    var root = new ArithmeticRoot(); var target = root.getCallTarget(); assertEquals(field == 0 ? 2 : 1, target.call(7, 3)); long before = root.compiledEntries; compile(target); assertEquals(before, root.compiledEntries, "Compilation must not execute arithmetic");
                    assertEquals(field == 0 ? 2 : 3, target.call(13, 5)); assertEquals(before + 1, root.compiledEntries, "Immediate installed arithmetic result"); valid(target);
                    var failure = assertThrows(RuntimeFault.class, () -> target.call(7, 0)); assertEquals("Undefined input to " + operation.getPrimitive(), failure.getMessage()); assertEquals(before + 2, root.compiledEntries, "Cold fault enters installed code");
                    // Preserve fault's existing interpreter/invalidation behavior;
                    // only the normal first installed call promises retention.
                    assertEquals(field == 0 ? 2 : 1, target.call(7, 3));
                }
            } finally { context.leave(); }
        }
    }
}
