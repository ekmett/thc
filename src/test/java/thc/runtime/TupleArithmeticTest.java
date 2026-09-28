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
import thc.Json;
import thc.Language;
import java.io.File;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class TupleArithmeticTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final List<String> names = List.of("quotRemInt", "quotRemWord", "addIntC", "subIntC", "plusWord2", "timesWord2", "addWordC", "subWordC", "timesInt2");
    private Map<String, Object> module() throws Exception { return module("pre"); }
    private Map<String, Object> module(String stage) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(new File(root, "build/tuple-arithmetic/" + stage + "-core/TupleArithmeticAudit.json").toPath())); }
    private Context context() { return Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build(); }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) { return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module); }
    private void valid(RootCallTarget target) throws ReflectiveOperationException { assertEquals(true, Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("isValidLastTier").invoke(target)); }
    private void compile(RootCallTarget target) throws ReflectiveOperationException {
        Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("compile", boolean.class).invoke(target, true); valid(target);
        var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target); valid(target);
    }
    private void checkHashes() throws Exception {
        var manifest = (Map<String, Object>) Json.parse(Files.readString(new File(root, "build/tuple-arithmetic/manifest.json").toPath()));
        for (String kind : List.of("inputHashes", "artifactHashes")) for (var row : ((Map<String, String>) manifest.get(kind)).entrySet()) {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, row.getKey()).toPath()))); assertEquals(row.getValue(), actual, "Stale tuple arithmetic fixture: " + row.getKey());
        }
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
        checkHashes(); var rows = new ArrayList<Row>();
        for (String line : Files.readAllLines(new File(root, "build/tuple-arithmetic/oracle.tsv").toPath())) { var r = line.split("\t", -1); var fields = new ArrayList<Long>(); for (int i = 3; i < r.length; i++) fields.add(Long.parseLong(r[i])); rows.add(new Row(r[0], Long.parseLong(r[1]), Long.parseLong(r[2]), fields)); }
        var rowNames = new LinkedHashSet<String>(); var triples = new LinkedHashSet<List<Object>>(); for (var row : rows) { rowNames.add(row.name); triples.add(List.of(row.name, row.x, row.y)); }
        assertEquals(new LinkedHashSet<>(names), rowNames); assertEquals(rows.size(), triples.size()); for (var row : rows) assertEquals(mathematical(row), row.fields, "Native " + row);
        for (String stage : List.of("pre", "post")) for (String backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var module = module(stage); var reached = new LinkedHashSet<Object>();
                for (String name : names) for (var binding : (List<Map<String, Object>>) CoreModules.reachable(module, name).get("bindings")) reached.add(binding.get("id"));
                var bindings = new ArrayList<Map<String, Object>>(); for (var binding : (List<Map<String, Object>>) module.get("bindings")) if (reached.contains(binding.get("id"))) bindings.add(binding);
                var input = new LinkedHashMap<>(module); input.put("bindings", bindings); var program = program(language, input, backend); var host = program.hostEntryTarget(3); var entries = new LinkedHashMap<String, Object>(); for (String name : names) entries.put(name, program.entryValue(name));
                Consumer<Row> check = row -> { for (int field = 0; field < row.fields.size(); field++) assertEquals(row.fields.get(field), Calls.target(host, new Object[]{entries.get(row.name), new Object[]{row.x, row.y, (long) field}}), stage + "/" + backend + "/" + row + "/" + field); };
                // Establish the final host dispatch before warming individual roots. Nine
                // targets replace its three-entry direct cache with indirect calls; with
                // handoff enabled this changes empty arguments to the ordinary packet.
                // Each root must see that packet during warmup, before we compile it.
                for (String name : names) { Row first = null; for (var row : rows) if (row.name.equals(name)) { first = row; break; } if (first == null) throw new NoSuchElementException(); check.accept(first); }
                for (var row : rows) check.accept(row); for (var binding : bindings) compile(program.entryTarget((String) binding.get("id")));
                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); for (var row : rows.reversed()) check.accept(row);
                long fields = 0; for (var row : rows) fields += row.fields.size(); assertEquals(fields, ((Number) program.diagnostics().get("compiledEntries")).longValue() - before, stage + "/" + backend + ": every checked field must enter installed guest code");
                for (var binding : bindings) valid(program.entryTarget((String) binding.get("id")));
                assertEquals(0L, language.getHandoffState().get().getResults().getAllocations(), "Saturated primitive expressions need no tuple carrier"); assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                for (String key : List.of("unsupportedTraps", "papAllocations", "thunkEvaluations", "blackholes")) assertEquals(0L, ((Number) program.diagnostics().get(key)).longValue(), key);
            } finally { context.leave(); }
        }
    }
    private List<List<Object>> applications(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof List<?> values) { if (!values.isEmpty() && "app".equals(values.getFirst())) result.add((List<Object>) values); for (var child : values) result.addAll(applications(child)); }
        else if (value instanceof Map<?, ?> values) for (var child : values.values()) result.addAll(applications(child)); return result;
    }
    private Map<String, Object> wrap(Object proof) { var result = new LinkedHashMap<String, Object>(); result.put("aggregate", "unboxed-tuple"); result.put("kind", "unknown"); result.put("evaluated", true); result.put("primReps", ((Map<String, Object>) proof).get("primReps")); result.put("components", Arrays.asList(proof)); return result; }
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
                for (String rep : List.of("IntRep", "WordRep", "Int64Rep", "Word64Rep")) for (String name : names) {
                    // Project annotations consistently; logical tuple shape and order stay intact.
                    var input = (Map<String, Object>) project.apply(CoreModules.reachable(module(), name), rep); var p = program(language, input, backend); var expected = mathematical(new Row(name, -13L, 5L, List.of()));
                    for (int field = 0; field < expected.size(); field++) assertEquals(expected.get(field), Calls.target(p.hostEntryTarget(3), new Object[]{p.entryValue(name), new Object[]{-13L, 5L, (long) field}}), backend + "/" + rep + "/" + name + "/" + field);
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
            app -> { var rep = (Map<String, Object>) ((Map<String, Object>) app.get(6)).get("rep"); var last = ((List<Map<String, Object>>) rep.get("components")).getLast(); last.put("kind", "float"); last.put("primReps", List.of("FloatRep")); var flattened = (List<Object>) rep.get("primReps"); flattened.set(flattened.size() - 1, "FloatRep"); },
            app -> { var rep = (Map<String, Object>) ((Map<String, Object>) app.get(6)).get("rep"); ((List<Object>) rep.get("components")).removeLast(); ((List<Object>) rep.get("primReps")).removeLast(); },
            app -> { var rep = (Map<String, Object>) ((Map<String, Object>) app.get(6)).get("rep"); ((List<Object>) rep.get("components")).add(((List<?>) rep.get("components")).getLast()); ((List<Object>) rep.get("primReps")).add(((List<?>) rep.get("primReps")).getLast()); },
            app -> { var rep = (Map<String, Object>) ((Map<String, Object>) app.get(6)).get("rep"); var children = (List<Object>) rep.get("components"); children.set(0, wrap(children.getFirst())); },
            app -> ((Map<String, Object>) app.get(6)).remove("rep"),
            app -> { var rep = (Map<String, Object>) ((Map<String, Object>) app.get(6)).get("rep"); rep.remove("aggregate"); rep.remove("components"); rep.put("kind", "long"); rep.put("primReps", List.of("IntRep")); },
            app -> { var arg = ((List<List<Object>>) app.get(2)).getFirst(); var rep = (Map<String, Object>) Objects.requireNonNull(CoreRepresentations.INSTANCE.metadata(arg)).get("rep"); rep.put("kind", "float"); rep.put("primReps", List.of("FloatRep")); },
            app -> { var arg = ((List<List<Object>>) app.get(2)).getFirst(); ((Map<String, Object>) Objects.requireNonNull(CoreRepresentations.INSTANCE.metadata(arg)).get("rep")).put("kind", "unknown"); },
            app -> ((List<Object>) app.get(3)).set(0, true),
            app -> { ((List<Object>) app.get(2)).remove(1); ((List<Object>) app.get(3)).remove(1); ((Map<String, Object>) app.get(6)).remove("callDemand"); },
            app -> { ((List<Object>) app.get(2)).add(((List<?>) app.get(2)).getFirst()); ((List<Object>) app.get(3)).add(false); ((Map<String, Object>) app.get(6)).remove("callDemand"); });
        for (String backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter(); try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (String name : names) for (int index = 0; index < mutations.size(); index++) for (boolean diagnostic : new boolean[]{false, true}) {
                    var module = CoreModules.reachable(module(), name); var app = primitiveApplication(module, name); mutations.get(index).accept(app); var input = new LinkedHashMap<>(module); input.put("diagnosticUnsupported", diagnostic);
                    assertThrows(RuntimeFault.class, () -> program(language, input, backend), backend + "/" + name + "/mutation" + index);
                }
                for (String name : names) { var module = CoreModules.reachable(module(), name); var app = primitiveApplication(module, name); var primitive = new ArrayList<>((List<?>) app.get(1)); app.clear(); app.addAll(primitive); assertThrows(UnsupportedCore.class, () -> program(language, module, backend), backend + "/" + name + " first-class"); }
            } finally { context.leave(); }
        }
    }
    @Test void undefinedDivisionInputsFailWithoutPublishingResultsAndValidCallsRecover() throws Exception {
        for (String backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter(); try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (String name : List.of("quotRemInt", "quotRemWord")) {
                    var input = new LinkedHashMap<>(CoreModules.reachable(module(), name)); input.put("instrument", true); var program = program(language, input, backend); var entry = program.entryTarget(name);
                    java.util.function.LongSupplier count = () -> ((Number) program.diagnostics().get("compiledEntries")).longValue(); var invalid = new ArrayList<long[]>(); invalid.add(new long[]{1L, 0L}); if (name.equals("quotRemInt")) invalid.add(new long[]{Long.MIN_VALUE, -1L});
                    // Prepare only valid arithmetic before the first installed failure.
                    assertEquals(2L, Calls.target(entry, new Object[]{0L, 7L, 3L, 0L})); assertEquals(1L, Calls.target(entry, new Object[]{0L, 7L, 3L, 1L})); long beforeCompile = count.getAsLong(); compile(entry); assertEquals(beforeCompile, count.getAsLong(), "Compilation must not enter guest code");
                    for (long[] pair : invalid) { long x = pair[0], y = pair[1], before = count.getAsLong(); var failure = assertThrows(RuntimeFault.class, () -> Calls.target(entry, new Object[]{0L, x, y, 0L})); assertEquals("Undefined input to " + name + "#", failure.getMessage()); assertEquals(before + 1, count.getAsLong(), backend + "/" + name + ": cold error enters installed code"); valid(entry); }
                    assertEquals(2L, Calls.target(entry, new Object[]{0L, 7L, 3L, 0L})); assertEquals(1L, Calls.target(entry, new Object[]{0L, 7L, 3L, 1L})); valid(entry); assertEquals(0L, language.getHandoffState().get().getResults().getAllocations());
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
                for (var operation : TupleArithmeticOp.values()) if (operation.isInt()) for (int field = 0; field <= 1; field++) {
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
