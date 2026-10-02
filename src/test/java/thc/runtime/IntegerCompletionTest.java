// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import java.io.File;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.Scalar64Primitives.unsignedDoubleWordQuotient;
import static thc.runtime.ScalarValueTestSupport.*;

class IntegerCompletionTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private static final String DIRECTORY = "build/integer-completion";
    private static final List<String> NAMES = list("quotRemInt8", "quotRemInt16", "quotRemInt32", "quotRemWord8", "quotRemWord16", "quotRemWord32", "shiftRLInt8", "shiftRLInt16", "shiftRLInt32", "quotRemWord2", "mulMay");
    private static final List<Integer> COUNTS = list(311, 311, 311, 312, 312, 312, 128, 192, 320, 544, 320);
    private static final BigInteger MODULUS = BigInteger.ONE.shiftLeft(64);
    private static BigInteger unsigned(long value) { return BigInteger.valueOf(value).mod(MODULUS); }
    private Map<String, Object> json(String path) throws Exception { return object(Json.parse(Files.readString(new File(root, path).toPath()))); }
    private Map<String, Object> module() throws Exception { return module("pre"); }
    private Map<String, Object> module(String stage) throws Exception { return thc.CoreCbdFixtures.read(new File(root, DIRECTORY + "/" + stage + "-core/IntegerCompletionAudit.cbd").toPath()); }
    private static Context context(boolean inlining) {
        return Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", Boolean.toString(inlining))
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
            .option("engine.SingleTierCompilationThreshold", "10000000").build();
    }
    private static ExecutableProgram program(Language language, Map<String, Object> input, String backend) { return backend.equals("ast") ? new Program(language, input) : new BytecodeProgram(language, input); }
    private static void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), target.getRootNode().getName()); }
    private static void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target);
        // Restore the retired JVM-wide boundary without entering guest code.
        var runtime = Truffle.getRuntime();
        runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target); valid(target);
    }
    private static void released(Language language) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences());
        assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences()); assertNull(state.getPending());
    }
    private static String primitive(String name) { return name.equals("mulMay") ? "mulIntMayOflo#" : name.startsWith("shiftRL") ? "uncheckedS" + name.substring(1) + "#" : name + "#"; }
    private record Row(String name, long x, long y, long z, List<Long> fields) {}
    private List<Row> rows() throws Exception {
        var rows = new ArrayList<Row>();
        for (var line : Files.readAllLines(new File(root, DIRECTORY + "/oracle.tsv").toPath())) {
            var f = line.split("\t"); assertEquals(6, f.length);
            rows.add(new Row(f[0], Long.parseLong(f[1]), Long.parseLong(f[2]), Long.parseLong(f[3]), list(Long.parseLong(f[4]), Long.parseLong(f[5]))));
        }
        assertEquals(3373, rows.size());
        var expected = new LinkedHashMap<String, Integer>(); var actual = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < NAMES.size(); i++) expected.put(NAMES.get(i), COUNTS.get(i));
        for (var row : rows) actual.merge(row.name, 1, Integer::sum);
        assertEquals(expected, actual);
        assertEquals(Files.readAllLines(new File(root, DIRECTORY + "/requests.tsv").toPath()),
            rows.stream().map(row -> row.name + "\t" + row.x + "\t" + row.y + "\t" + row.z).toList());
        return rows;
    }
    private static List<Long> longs(BigInteger[] values) { return Arrays.stream(values).map(BigInteger::longValue).toList(); }
    private static BigInteger narrow(Row row, long value, BigInteger width, int bits) {
        var low = BigInteger.valueOf(value).mod(width); return row.name.contains("Int") && low.testBit(bits - 1) ? low.subtract(width) : low;
    }
    private static List<Long> model(Row row) {
        if (row.name.equals("mulMay")) {
            var product = BigInteger.valueOf(row.x).multiply(BigInteger.valueOf(row.y));
            long overflow = product.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0 || product.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0 ? 1L : 0L;
            return list(overflow, overflow);
        }
        if (row.name.equals("quotRemWord2")) return longs(unsigned(row.x).shiftLeft(64).add(unsigned(row.y)).divideAndRemainder(unsigned(row.z)));
        int bits = Integer.parseInt(row.name.replaceFirst("^.*?(\\d+)$", "$1")); var width = BigInteger.ONE.shiftLeft(bits);
        if (row.name.startsWith("shift")) {
            var low = BigInteger.valueOf(row.x).mod(width).shiftRight((int) row.y); long result = (low.testBit(bits - 1) ? low.subtract(width) : low).longValue();
            return list(result, result);
        }
        return longs(narrow(row, row.x, width, bits).divideAndRemainder(narrow(row, row.y, width, bits)));
    }
    private void checkHashes() throws Exception {
        var manifest = json(DIRECTORY + "/manifest.json");
        assertEquals("9.14.1", manifest.get("ghc")); assertEquals(64L, ((Number) manifest.get("wordBits")).longValue());
        assertEquals(NAMES, manifest.get("entries")); assertEquals(3373L, ((Number) manifest.get("nativeRows")).longValue());
        var inputs = new HashSet<>(list("t/fixtures/compiler/IntegerCompletionAudit.hs", "thc.cabal", "t/haskell-fixtures/Main.hs",
            "t/haskell-fixtures/IntegerCompletionFixtures.hs", "t/haskell-fixtures/FixtureSupport.hs", "bin/build-compiler.sh", "bin/export-core.sh",
            "bin/toolchain.sh", "bin/plugin.py", "bin/audit-core.py", "bin/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json"));
        for (var file : Objects.requireNonNull(new File(root, "src/compiler/THC").listFiles())) if (file.getName().endsWith(".hs")) inputs.add("src/compiler/THC/" + file.getName());
        for (var file : Objects.requireNonNull(new File(root, "bin").listFiles())) if (file.getName().startsWith("core_") && file.getName().endsWith(".py")) inputs.add("bin/" + file.getName());
        var commands = list("ghc-version", "ghc-info", "pre-export", "pre-audit", "post-export", "post-audit", "native-build", "native-oracle");
        var artifacts = new HashSet<>(list("requests.tsv", "NativeIntegerCompletion.hs", "native/integer-completion-oracle", "oracle.tsv"));
        for (var stage : list("pre", "post")) { artifacts.add(stage + "-audit.json"); artifacts.add(stage + "-core/IntegerCompletionAudit.cbd"); }
        for (var command : commands) for (var suffix : list("stdout", "stderr", "command.json")) artifacts.add("commands/" + command + "." + suffix);
        for (var kind : list("inputHashes", "artifactHashes")) {
            Set<String> paths = kind.equals("inputHashes") ? inputs : artifacts.stream().map(path -> DIRECTORY + "/" + path).collect(Collectors.toSet());
            var recorded = object(manifest.get(kind)); assertEquals(paths, recorded.keySet(), kind + " must be a closed inventory");
            for (var hash : recorded.entrySet()) assertEquals(hash.getValue(),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, hash.getKey()).toPath()))), hash.getKey());
        }
        for (var command : commands) assertEquals(0L, ((Number) json(DIRECTORY + "/commands/" + command + ".command.json").get("exit")).longValue(), command);
    }
    private static long count(ExecutableProgram p) { return ((Number) p.diagnostics().get("compiledEntries")).longValue(); }
    @Test void nativeAndIndependentModelAgreeInBothBackends() throws Exception {
        checkHashes(); var allRows = rows();
        for (var row : allRows) {
            var exact = model(row);
            if (row.name.equals("mulMay")) {
                assertEquals(row.fields.get(0), row.fields.get(1)); assertTrue(row.fields.get(0) >= 0 && row.fields.get(0) <= 1);
                if (row.fields.get(0) == 0L) assertEquals(0L, exact.get(0), "Native overflow false negative: " + row);
                if (row.x >= -2L && row.x <= 2L && row.y >= -2L && row.y <= 2L) assertEquals(0L, row.fields.get(0));
            } else assertEquals(exact, row.fields, "Native " + row);
        }
        for (var stage : list("pre", "post")) for (var backend : list("ast", "bytecode")) try (var context = context(true)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var original = module(stage);
                for (var name : NAMES) {
                    var evidence = new ArrayCoreEvidence(original, "main:IntegerCompletionAudit." + name);
                    assertTrue(evidence.getPrimitiveCounts().containsKey(primitive(name)),
                        stage + "/" + name + " genuine primitive");
                    var p = program(language, with(CoreModules.reachable(original, "main:IntegerCompletionAudit." + name), "instrument", true), backend); var entry = p.entryTarget("main:IntegerCompletionAudit." + name);
                    var corpus = allRows.stream().filter(row -> row.name.equals(name)).toList();
                    for (boolean compiled : new boolean[]{false, true}) {
                        if (compiled) {
                            long before = count(p);
                            compile(entry);
                            assertEquals(before, count(p), "Compilation setup cannot enter guest code");
                        }
                        boolean firstCompiledCall = compiled;
                        for (var row : compiled ? corpus.reversed() : corpus) {
                            var expected = model(row);
                            // Shift and overflow entries ignore the field selector.
                            int fields = name.startsWith("quotRem") ? 2 : 1;
                            for (int field = 0; field < fields; field++) {
                                long before = firstCompiledCall ? count(p) : 0L;
                                try {
                                    assertEquals(expected.get(field), Calls.target(entry,
                                        new Object[]{0L, row.x, row.y, row.z, (long) field}),
                                        stage + "/" + backend + "/" + row + "/" + field);
                                    if (firstCompiledCall) {
                                        assertTrue(count(p) > before, name + " first installed call");
                                        valid(entry);
                                        firstCompiledCall = false;
                                    }
                                } finally { released(language); }
                            }
                        }
                        if (compiled) valid(entry);
                    }
                    for (var key : list("unsupportedTraps", "blackholes")) assertEquals(0L, ((Number) p.diagnostics().get(key)).longValue(), key);
                }
            } finally { context.leave(); }
        }
    }
    @Test void unsignedDoubleWordDivisionMatchesUnboundedArithmeticIncludingTopBitDivisors() {
        var random = new Random(9141);
        for (int i = 0; i < 20000; i++) {
            long divisor = random.nextLong() | 1L, high = unsigned(random.nextLong()).mod(unsigned(divisor)).longValue(), low = random.nextLong();
            var expected = unsigned(high).shiftLeft(64).add(unsigned(low)).divideAndRemainder(unsigned(divisor));
            long quotient = unsignedDoubleWordQuotient(high, low, divisor);
            assertEquals(expected[0].longValue(), quotient); assertEquals(expected[1].longValue(), low - quotient * divisor);
        }
    }
    @Test void allEightBitQuotientRemainderInputsAreNarrowedByTheOperation() {
        for (long x = -128; x <= 127; x++) for (long y = -128; y <= 127; y++) if (y != 0 && !(x == -128 && y == -1)) {
            assertEquals((int) (x / y), TupleArithmeticOp.QUOT_REM_INT8.firstInt((int) (x | (123L << 8)), (int) (y | (45L << 8))));
            assertEquals((int) (x % y), TupleArithmeticOp.QUOT_REM_INT8.secondInt((int) x, (int) y));
        }
        for (long x = 0; x <= 255; x++) for (long y = 1; y <= 255; y++) {
            assertEquals((int) (x / y), TupleArithmeticOp.QUOT_REM_WORD8.firstInt((int) (x | (123L << 8)), (int) (y | (45L << 8))));
            assertEquals((int) (x % y), TupleArithmeticOp.QUOT_REM_WORD8.secondInt((int) x, (int) y));
        }
    }
    private static List<List<Object>> applications(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof List<?> items) { if (!items.isEmpty() && "app".equals(items.getFirst())) result.add(expression(items)); for (var item : items) result.addAll(applications(item)); }
        else if (value instanceof Map<?, ?> fields) for (var item : fields.values()) result.addAll(applications(item));
        return result;
    }
    private static Object project(Object value, String rep) {
        if (value instanceof Map<?, ?> fields) { var result = new LinkedHashMap<Object, Object>(); for (var field : fields.entrySet()) result.put(field.getKey(), project(field.getValue(), rep)); return result; }
        if (value instanceof List<?> items) { var result = new ArrayList<Object>(); for (var item : items) result.add(project(item, rep)); return result; }
        if (value instanceof String text && Set.of("IntRep", "WordRep", "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep", "Int32Rep", "Word32Rep", "Int64Rep", "Word64Rep").contains(text)) {
            // Vary names sharing the carrier while preserving actual stored widths.
            return Set.of("IntRep", "WordRep", "Int64Rep", "Word64Rep").contains(text) ? rep : text.startsWith("Word") ? text.replaceFirst("Word", "Int") : text.replaceFirst("Int", "Word");
        }
        return value;
    }
    @Test void integralLexicalAnnotationsDoNotChooseWidthSignednessOrTupleOrder() throws Exception {
        for (var backend : list("ast", "bytecode")) try (var context = context(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var rep : list("IntRep", "Word64Rep")) for (var name : NAMES) {
                    var p = program(language, object(project(CoreModules.reachable(module(), "main:IntegerCompletionAudit." + name), rep)), backend);
                    var row = name.equals("quotRemWord2") ? new Row(name, 3L, -13L, 5L, list()) : name.equals("mulMay") ? new Row(name, Long.MIN_VALUE, -1L, 0L, list()) : new Row(name, -13L, 5L, 0L, list());
                    for (int field = 0; field <= 1; field++) assertEquals(model(row).get(field), Calls.target(p.entryTarget("main:IntegerCompletionAudit." + name), new Object[]{0L, row.x, row.y, row.z, (long) field}), backend + "/" + rep + "/" + name);
                    released(language);
                }
            } finally { context.leave(); }
        }
    }
    private static List<Object> application(Map<String, Object> input, String name) {
        var matches = applications(input).stream().filter(app -> expression(app.get(1)).subList(0, 2).equals(list("prim", primitive(name)))).toList();
        assertFalse(matches.isEmpty(), "Missing primitive: " + name); return matches.getFirst();
    }
    @Test void actualCarrierAggregateArityAndSaturationErrorsAreRejected() throws Exception {
        List<Consumer<List<Object>>> mutations = list(
            app -> expression(app.get(2)).set(0, list("lit", "float", "1.0", map("rep", map("kind", "float", "primReps", list("FloatRep"), "evaluated", true)))),
            app -> expression(app.get(3)).set(0, true),
            app -> { expression(app.get(2)).removeLast(); expression(app.get(3)).removeLast(); object(app.get(6)).remove("callDemand"); },
            app -> { var rep = object(object(app.get(6)).get("rep")); rep.put("kind", "float"); rep.put("primReps", list("FloatRep")); rep.remove("aggregate"); rep.remove("components"); });
        for (var backend : list("ast", "bytecode")) try (var context = context(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var name : NAMES) for (int i = 0; i < mutations.size(); i++) for (boolean diagnostic : new boolean[]{false, true}) {
                    // Ordinary scalar lowering takes physical result from the instruction;
                    // tuple lowering additionally validates its explicit protocol.
                    if ((i == 1 || i == 3) && !name.startsWith("quotRem")) continue;
                    var input = CoreModules.reachable(module(), "main:IntegerCompletionAudit." + name); mutations.get(i).accept(application(input, name));
                    assertThrows(RuntimeFault.class, () -> program(language, with(input, "diagnosticUnsupported", diagnostic), backend), backend + "/" + name + "/mutation" + i);
                }
                for (var name : NAMES) {
                    var input = CoreModules.reachable(module(), "main:IntegerCompletionAudit." + name); var app = application(input, name); var prim = new ArrayList<>(expression(app.get(1))); app.clear(); app.addAll(prim);
                    assertThrows(UnsupportedCore.class, () -> program(language, input, backend), backend + "/" + name + " first-class");
                }
            } finally { context.leave(); }
        }
    }
    @Test void undefinedDivisionInputsFailCleanlyAndValidCallsRecover() throws Exception {
        for (var backend : list("ast", "bytecode")) try (var context = context(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var name : NAMES) if (name.startsWith("quotRem")) {
                    var p = program(language, CoreModules.reachable(module(), "main:IntegerCompletionAudit." + name), backend); var target = p.entryTarget("main:IntegerCompletionAudit." + name);
                    var bad = name.equals("quotRemWord2") ? list(new long[]{0L, 1L, 0L}, new long[]{3L, 1L, 3L}, new long[]{-1L, 1L, Long.MIN_VALUE})
                        : list(new long[]{1L, 0L, 0L}, new long[]{1L, 1L << Integer.parseInt(name.replaceFirst("^.*?(\\d+)$", "$1")), 0L});
                    for (var xyz : bad) try { assertThrows(RuntimeFault.class, () -> Calls.target(target, new Object[]{0L, xyz[0], xyz[1], xyz[2], 0L})); } finally { released(language); }
                    var good = name.equals("quotRemWord2") ? new Row(name, 0L, 7L, 3L, list()) : new Row(name, 7L, 3L, 0L, list());
                    for (int field = 0; field <= 1; field++) assertEquals(model(good).get(field), Calls.target(target, new Object[]{0L, good.x, good.y, good.z, (long) field}));
                    released(language);
                }
            } finally { context.leave(); }
        }
    }
}
