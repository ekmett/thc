// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import thc.*;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static thc.Main.executionContext;

@SuppressWarnings("unchecked")
public class IntArrayNativeTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final List<String> names =
        List.of("unboxedAccum", "unboxedST", "unboxedEmpty", "orderedInts", "aliasIntBytes");
    private final List<ByteArrayOp> operations =
        List.of(ByteArrayOp.READ_INT, ByteArrayOp.WRITE_INT, ByteArrayOp.INDEX_INT);
    private Map<String, Object> manifest() throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(root.resolve("build/int-arrays/manifest.json")));
    }
    private Map<String, Object> merged(List<String> paths) throws Exception {
        var modules = new ArrayList<Map<String, Object>>();
        for (var path : paths) modules.add((Map<String, Object>) Json.parse(Files.readString(root.resolve(path))));
        return CoreModules.merge(modules);
    }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private void valid(RootCallTarget target, String label) throws Exception {
        assertEquals(true,
            Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("isValidLastTier").invoke(target),
            label);
    }
    private final List<Long> inputs = inputs();
    private List<Long> inputs() {
        var values = new TreeSet<Long>();
        for (long x = -16; x <= 16; x++) values.add(x);
        values.addAll(List.of(Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE));
        for (int bit = 0; bit <= 63; bit++)
            for (long delta = -1; delta <= 1; delta++)
                for (long sign : List.of(-1L, 1L)) values.add(sign * ((1L << bit) + delta));
        for (var bits : List.of("5555555555555555", "aaaaaaaaaaaaaaaa", "55aa55aa55aa55aa", "aa55aa55aa55aa55",
                 "0123456789abcdef", "fedcba9876543210"))
            values.add(Long.parseUnsignedLong(bits, 16));
        return new ArrayList<>(values);
    }
    private record Row(String name, long input, long answer) {}
    private List<Row> expectedRows(ByteOrder order) {
        var rows = new ArrayList<Row>();
        for (var name : names)
            for (long input : inputs) rows.add(new Row(name, input, mathematical(name, input, order)));
        return rows;
    }
    private List<Row> checkedRows(String text) {
        return checkedRows(text, ByteOrder.nativeOrder());
    }
    private List<Row> checkedRows(String text, ByteOrder order) {
        var lines = new ArrayList<>(Arrays.asList(text.split("\\r\\n|\\n|\\r", -1)));
        if (!lines.isEmpty() && lines.getLast().isEmpty())
            lines.removeLast();
        var rows = new ArrayList<Row>();
        for (var line : lines) {
            var fields = line.split("\t", -1);
            require(fields.length == 3, "Int-array oracle requires name/input/result");
            rows.add(new Row(fields[0], Long.parseLong(fields[1]), Long.parseLong(fields[2])));
        }
        require(rows.equals(expectedRows(order)), "Int-array native/model mismatch or incomplete/reordered corpus");
        return rows;
    }
    private final Map<String, Map<String, Integer>> exactCounts = Map.of("orderedInts",
        Map.of("newByteArray#", 2, "readIntArray#", 2, "writeIntArray#", 5, "unsafeFreezeByteArray#", 2,
            "indexIntArray#", 4),
        "aliasIntBytes",
        Map.of("newByteArray#", 1, "writeIntArray#", 2, "writeWord8Array#", 2, "readIntArray#", 2,
            "unsafeFreezeByteArray#", 1, "indexIntArray#", 2, "indexWord8Array#", 4));
    private Set<String> required(String name) {
        if (name.equals("unboxedEmpty"))
            return Set.of();
        var result = new HashSet<>(
            Set.of("newByteArray#", "unsafeFreezeByteArray#", "readIntArray#", "writeIntArray#", "indexIntArray#"));
        if (name.equals("orderedInts"))
            result.add("sizeofByteArray#");
        if (name.equals("aliasIntBytes"))
            result.addAll(Set.of("writeWord8Array#", "indexWord8Array#"));
        return result;
    }
    private Map<String, Integer> checkedCore(String name, ArrayCoreEvidence evidence) {
        require(names.contains(name), "Unknown Int-array root: " + name);
        var counts = evidence.getPrimitiveCounts();
        require(counts.keySet().containsAll(required(name)), "Missing Int-array primitive: " + name);
        require(exactCounts.getOrDefault(name, Map.of())
                    .entrySet()
                    .stream()
                    .allMatch(e -> e.getValue().equals(counts.get(e.getKey()))),
            "Int-array use count changed: " + name);
        return counts;
    }
    private static BigInteger n(long value) {
        return BigInteger.valueOf(value);
    }
    // A list/byte model independent of the native oracle and managed VarHandle.
    private long mathematical(String name, long seed) {
        return mathematical(name, seed, ByteOrder.nativeOrder());
    }
    private long mathematical(String name, long seed, ByteOrder order) {
        require(names.contains(name), "Unknown Int-array entry: " + name);
        var x = n(seed);
        if (name.equals("unboxedEmpty"))
            return x.add(n(7)).longValue();
        if (name.equals("unboxedAccum") || name.equals("unboxedST")) {
            var a = new ArrayList<>(Collections.nCopies(8, x));
            if (name.equals("unboxedAccum")) {
                int[] keys = {-3, 0, -3, 4};
                var values = List.of(n(3), n(5), n(-2), x);
                for (int i = 0; i < keys.length; i++) {
                    int k = keys[i] + 3;
                    a.set(k, a.get(k).add(values.get(i)));
                }
            } else {
                var before = a.getFirst();
                a.set(3, before.add(n(7)));
                var after = a.get(3);
                a.set(7, n(3).multiply(after).subtract(x));
            }
            return a.get(0).multiply(n(7)).add(a.get(3).multiply(n(11))).add(a.get(7).multiply(n(13))).longValue();
        }
        var pattern = new BigInteger("55aa55aa55aa55aa", 16);
        if (name.equals("orderedInts")) {
            var a = new ArrayList<>(List.of(x, x.add(n(1)), x.xor(pattern)));
            var b = List.of(x.add(n(71)));
            var before = a.get(1);
            a.set(1, x.add(n(17)));
            var after = a.get(1);
            return before.multiply(n(3))
                .add(after.multiply(n(5)))
                .add(a.get(0).multiply(n(7)))
                .add(a.get(1).multiply(n(11)))
                .add(a.get(2).multiply(n(13)))
                .add(b.get(0).multiply(n(17)))
                .add(n(32))
                .longValue();
        }
        if (!name.equals("aliasIntBytes"))
            throw new IllegalStateException("Check failed.");
        boolean little = order == ByteOrder.LITTLE_ENDIAN;
        var modulus = BigInteger.ONE.shiftLeft(64);
        var bytes = new ArrayList<BigInteger>();
        for (var value : List.of(x, x.xor(pattern)))
            for (int b = 0; b <= 7; b++) bytes.add(value.mod(modulus).shiftRight((little ? b : 7 - b) * 8).and(n(255)));
        bytes.set(7, x.add(n(101)).mod(n(256)));
        bytes.set(8, x.add(n(37)).mod(n(256)));
        var a = cell(bytes, 0, little, modulus);
        var b = cell(bytes, 1, little, modulus);
        return a.multiply(n(3))
            .add(b.multiply(n(5)))
            .add(a.multiply(n(7)))
            .add(b.multiply(n(11)))
            .add(bytes.get(0).multiply(n(17)))
            .add(bytes.get(7).multiply(n(19)))
            .add(bytes.get(8).multiply(n(23)))
            .add(bytes.get(15).multiply(n(29)))
            .longValue();
    }
    private BigInteger cell(List<BigInteger> bytes, int index, boolean little, BigInteger modulus) {
        var value = BigInteger.ZERO;
        for (int b = 0; b <= 7; b++) value = value.or(bytes.get(index * 8 + b).shiftLeft((little ? b : 7 - b) * 8));
        return value.testBit(63) ? value.subtract(modulus) : value;
    }
    @Test
    public void independentModelsCoverFullWidthUpdatesAndBothByteOrders() {
        assertEquals(393, inputs.size());
        assertEquals(inputs.stream().sorted().distinct().toList(), inputs);
        for (int bit = 0; bit <= 63; bit++)
            for (long delta = -1; delta <= 1; delta++)
                for (long sign : List.of(-1L, 1L)) assertTrue(inputs.contains(sign * ((1L << bit) + delta)));
        for (long x : inputs) {
            assertEquals(44 * x + 62, mathematical("unboxedAccum", x));
            assertEquals(44 * x + 350, mathematical("unboxedST", x));
            assertEquals(x + 7, mathematical("unboxedEmpty", x));
            assertEquals(3 * (x + 1) + 16 * (x + 17) + 7 * x + 13 * (x ^ 0x55aa55aa55aa55aaL) + 17 * (x + 71) + 32,
                mathematical("orderedInts", x));
            for (var order : List.of(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
                boolean little = order == ByteOrder.LITTLE_ENDIAN;
                int shiftA = little ? 56 : 0, shiftB = little ? 0 : 56;
                long a = (x & ~(255L << shiftA)) | (((x + 101) & 255) << shiftA);
                long b = ((x ^ 0x55aa55aa55aa55aaL) & ~(255L << shiftB)) | (((x + 37) & 255) << shiftB);
                long first = little ? a & 255 : a >>> 56, last = little ? b >>> 56 : b & 255;
                assertEquals(10 * a + 16 * b + 17 * first + 19 * ((x + 101) & 255) + 23 * ((x + 37) & 255) + 29 * last,
                    mathematical("aliasIntBytes", x, order), x + "/" + order);
            }
        }
        assertNotEquals(mathematical("aliasIntBytes", 0, ByteOrder.LITTLE_ENDIAN),
            mathematical("aliasIntBytes", 0, ByteOrder.BIG_ENDIAN));
        assertThrows(IllegalArgumentException.class, () -> mathematical("unknown", 0));
    }
    private String text(List<Row> rows) {
        return String.join("\n", rows.stream().map(r -> r.name + "\t" + r.input + "\t" + r.answer).toList()) + "\n";
    }
    private List<Row> firstChanged(List<Row> rows, Row first) {
        var copy = new ArrayList<>(rows);
        copy.set(0, first);
        return copy;
    }
    @Test
    public void exactCorpusRejectsMissingDuplicateReorderedMalformedAndWrongRows() {
        for (var order : List.of(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            var rows = expectedRows(order);
            var valid = text(rows);
            var first = rows.getFirst();
            assertEquals(1965, rows.size());
            assertEquals(rows, checkedRows(valid, order));
            var duplicate = new ArrayList<>(rows);
            duplicate.add(first);
            var repeated = new ArrayList<>(rows);
            repeated.set(1, first);
            var bad = List.of("", text(rows.subList(1, rows.size())), text(duplicate), text(rows.reversed()),
                text(repeated), text(firstChanged(rows, new Row("unknown", first.input, first.answer))),
                text(firstChanged(rows, new Row(first.name, first.input + 1, first.answer))),
                text(firstChanged(rows, new Row(first.name, first.input, first.answer + 1))),
                valid.replaceFirst("\t", " "), valid.replaceFirst("\t", "\textra\t"),
                "unboxedAccum\t9223372036854775808\t0\n", "unboxedAccum\t0\tnot-an-int\n", valid + "\n");
            for (int i = 0; i < bad.size(); i++) {
                var corrupt = bad.get(i);
                assertThrows(
                    IllegalArgumentException.class, () -> checkedRows(corrupt, order), order + "/mutation" + i);
            }
        }
    }
    @Test
    public void exportedCoreRequiresAllPrimitiveNamesAndExactUseCounts() throws Exception {
        var source = merged(paths());
        for (var name : names) {
            checkedCore(name, new ArrayCoreEvidence(source, name));
            for (var primitive : required(name))
                for (boolean all : List.of(false, true)) {
                    if (!all && exactCounts.getOrDefault(name, Map.of()).getOrDefault(primitive, 0) < 2)
                        continue;
                    var module = (Map<String, Object>) Json.parse(Json.stringify(source));
                    var evidence = new ArrayCoreEvidence(module, name);
                    var uses = new ArrayList<List<Object>>();
                    for (var binding : evidence.getBindings())
                        for (var node : evidence.nodes(binding.get("expr")))
                            if (prefix(node).equals(List.of("prim", primitive)))
                                uses.add(node);
                    assertTrue(!uses.isEmpty());
                    for (var node : all ? uses : uses.subList(0, Math.min(1, uses.size())))
                        node.set(1, "missingArrayPrimitive#");
                    assertThrows(IllegalArgumentException.class,
                        ()
                            -> checkedCore(name, new ArrayCoreEvidence(module, name)),
                        name + "/" + primitive + "/all=" + all);
                }
        }
    }
    @Test
    @EnabledOnOs(OS.WINDOWS)
    public void nativeWindowsOracleRunsFromAPathContainingSpaces(@TempDir Path temporary) throws Exception {
        var inputs = (Map<String, String>) manifest().get("inputHashes");
        assertTrue(inputs.containsKey("compiler/export.ps1"));
        assertTrue(inputs.containsKey("scripts/windows-common.ps1"));
        assertFalse(inputs.containsKey("compiler/export.sh"));
        var executable = Files.copy(root.resolve("build/int-arrays/native/int-array-oracle.exe"),
            temporary.resolve("native array oracle.exe"));
        var expected = Files.readAllLines(root.resolve("build/int-arrays/oracle.tsv")).getFirst();
        var fields = expected.split("\t");
        var request = temporary.resolve("request.tsv");
        Files.writeString(request, fields[0] + "\t" + fields[1] + "\n");
        var output = temporary.resolve("output.tsv");
        var process = new ProcessBuilder(executable.toString()).directory(temporary.toFile())
            .redirectInput(request.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Native Windows array oracle timed out");
            assertEquals(0, process.exitValue(), Files.readString(output));
            assertEquals(expected, Files.readString(output).strip());
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    @Test
    public void genuinePublicArraysAndPrimitiveAliasesMatchNativeAndIndependentModel() throws Exception {
        var manifest = manifest();
        assertEquals(names, manifest.get("entries"));
        assertEquals(inputs, ((List<Number>) manifest.get("inputs")).stream().map(Number::longValue).toList());
        assertEquals(names.size() * inputs.size(), ((Number) manifest.get("nativeRows")).intValue());
        assertEquals(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? "little" : "big", manifest.get("byteOrder"));
        assertEquals(64, ((Number) manifest.get("wordBits")).intValue());
        for (var kind : List.of("inputHashes", "artifactHashes"))
            for (var e : ((Map<String, String>) manifest.get(kind)).entrySet()) {
                var actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(e.getKey()))));
                assertEquals(e.getValue(), actual,
                    "Stale Int-array fixture: " + e.getKey() + "; regenerate the Int-array fixtures");
            }
        var rows = new LinkedHashMap<String, List<Row>>();
        for (var row : checkedRows(Files.readString(root.resolve("build/int-arrays/oracle.tsv"))))
            rows.computeIfAbsent(row.name, k -> new ArrayList<>()).add(row);
        assertEquals(new HashSet<>(names), rows.keySet());
        assertEquals(
            ((Number) manifest.get("nativeRows")).intValue(), rows.values().stream().mapToInt(List::size).sum());
        var stages = (Map<String, List<String>>) manifest.get("stages");
        assertEquals(Set.of("pre", "post"), stages.keySet());
        for (var stage : stages.entrySet()) {
            var module = merged(stage.getValue());
            for (var name : names) {
                checkedCore(name, new ArrayCoreEvidence(module, name));
                var cases = rows.get(name);
                assertEquals(cases.size(), cases.stream().map(Row::input).distinct().count());
                assertEquals(
                    new HashSet<>(((List<Number>) manifest.get("inputs")).stream().map(Number::longValue).toList()),
                    new HashSet<>(cases.stream().map(Row::input).toList()));
                for (var row : cases)
                    assertEquals(mathematical(name, row.input), row.answer, "Native " + name + "(" + row.input + ")");
                for (var backend : List.of("ast", "bytecode")) try (var context = executionContext()) {
                        context.initialize("thc");
                        context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            var label = stage.getKey() + "/" + backend + "/" + name;
                            var p = program(
                                language, changed(CoreModules.reachable(module, name), "instrument", true), backend);
                            var host = p.hostEntryTarget(1);
                            var function = context.asValue(new EntryValue(p, name, 1));
                            for (var row : cases)
                                assertEquals(
                                    row.answer, function.execute(row.input).asLong(), label + "(" + row.input + ")");
                            assertTrue(function.invokeMember("compile").asBoolean(), label + " installation");
                            var original = p.entryTarget(name);
                            var active = new ArrayList<RootCallTarget>();
                            for (var call : NodeUtil.findAllNodeInstances(host.getRootNode(), DirectCallNode.class))
                                if (call.getCallTarget() == original)
                                    active.add((RootCallTarget) call.getCurrentCallTarget());
                            if (active.isEmpty())
                                active.add(original);
                            for (var row : cases.reversed()) {
                                long before = count(p, "compiledEntries");
                                assertEquals(
                                    row.answer, function.execute(row.input).asLong(), label + "(" + row.input + ")");
                                assertTrue(count(p, "compiledEntries") > before,
                                    label + "(" + row.input + ") must enter installed guest code");
                                valid(host, label + " host remains installed");
                                for (var target : active) valid(target, label + " active target remains installed");
                            }
                            for (var counter : List.of("unsupportedTraps", "blackholes"))
                                assertEquals(0L, count(p, counter), label + "/" + counter);
                            assertEquals(0, language.getHandoffState().get().getResults().getDepth(),
                                label + " releases tuple results");
                            if (List.of("orderedInts", "aliasIntBytes").contains(name))
                                assertEquals(0L, language.getHandoffState().get().getResults().getAllocations(),
                                    label + " direct tuple destinations");
                        } finally {
                            context.leave();
                        }
                    }
            }
        }
    }
    private List<List<Object>> applications(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof List<?> list) {
            if (!list.isEmpty() && "app".equals(list.getFirst()))
                result.add((List<Object>) list);
            for (var item : list) result.addAll(applications(item));}else if(value instanceof Map<?,?> map)
            for (var item : map.values()) result.addAll(applications(item));
        return result;
    }
    private List<String> paths() throws Exception {
        return ((Map<String, List<String>>) manifest().get("stages")).get("pre");
    }
    private List<Object> application(Map<String, Object> module, ByteArrayOp operation) {
        return applications(module)
            .stream()
            .filter(a -> prefix((List<?>) a.get(1)).equals(List.of("prim", operation.getPrimitive())))
            .findFirst()
            .orElseThrow();
    }
    private Map<String, Object> metadata(Object node) {
        return CoreRepresentations.metadata((List<Object>) node);
    }
    private Map<String, Object> rep(Object node) {
        return (Map<String, Object>) metadata(node).get("rep");
    }
    @Test
    public void exactIntStateShapesSaturationAndLevityAreRequiredInBothLoadModes() throws Exception {
        var paths = paths();
        for (var backend : List.of("ast", "bytecode")) try (var context = executionContext()) {
                context.initialize("thc");
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (var operation : operations)
                        for (int mutation = 0; mutation <= 9; mutation++)
                            for (boolean diagnostic : List.of(false, true)) {
                                var module = CoreModules.reachable(merged(paths), "orderedInts");
                                var app = application(module, operation);
                                var args = (List<Object>) app.get(2);
                                var flags = (List<Object>) app.get(3);
                                var metadata = metadata(app);
                                switch (mutation) {
                                    case 0 -> {
                                        args.removeLast();
                                        flags.removeLast();
                                        metadata.remove("callDemand");
                                    }
                                    case 1 -> {
                                        args.add(args.getFirst());
                                        flags.add(false);
                                        metadata.remove("callDemand");
                                    }
                                    case 2 -> metadata.remove("rep");
                                    case 3 ->
                                        metadata.put("rep",
                                            Map.of("kind", "long", "primReps", List.of("WordRep"), "evaluated", true));
                                    case 4 -> flags.set(0, true);
                                    case 5 -> rep(args.get(0)).put("kind", "unknown");
                                    case 6 -> rep(args.get(1)).put("primReps", List.of("Int64Rep"));
                                    case 7 -> rep(args.get(0)).put("primReps", List.of("BoxedRep (Just Lifted)"));
                                    case 8 -> {
                                        if (operation == ByteArrayOp.READ_INT) {
                                            var proof = (Map<String, Object>) metadata.get("rep");
                                            ((List<Object>) proof.get("components"))
                                                .set(0,
                                                    Map.of("kind", "unknown", "aggregate", "unboxed-tuple",
                                                        "components", List.of(), "primReps", List.of(), "evaluated",
                                                        true));
                                        } else
                                            metadata.put("rep",
                                                Map.of("kind", "unknown", "primReps", List.of(), "evaluated", true));
                                    }
                                    case 9 -> {
                                        if (operation == ByteArrayOp.READ_INT)
                                            ((Map<String, Object>) metadata.get("rep")).put("kind", "long");
                                        else
                                            flags.set(1, true);
                                    }
                                }
                                boolean sameCarrier =
                                    mutation == 6 || (mutation == 3 && operation == ByteArrayOp.INDEX_INT);
                                var label = backend + "/" + operation.getPrimitive() + "/mutation" + mutation + "/"
                                    + diagnostic;
                                if (sameCarrier)
                                    assertDoesNotThrow(
                                        ()
                                            -> program(language, changed(module, "diagnosticUnsupported", diagnostic),
                                                backend),
                                        label);
                                else
                                    assertThrows(RuntimeFault.class,
                                        ()
                                            -> program(language, changed(module, "diagnosticUnsupported", diagnostic),
                                                backend),
                                        label);
                            }
                    for (var operation : operations) {
                        var module = CoreModules.reachable(merged(paths), "orderedInts");
                        var app = application(module, operation);
                        var primitive = new ArrayList<>((List<?>) app.get(1));
                        app.clear();
                        app.addAll(primitive);
                        assertThrows(UnsupportedCore.class, () -> program(language, module, backend));
                    }
                } finally {
                    context.leave();
                }
            }
    }
    @Test
    public void invalidElementIndicesFailOnBothBackendsWithoutComparingNativeUndefinedAccesses() throws Exception {
        var paths = paths();
        for (var backend : List.of("ast", "bytecode")) try (var context = executionContext()) {
                context.initialize("thc");
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (var operation : operations)
                        for (long index : List.of(Long.MIN_VALUE, -1L, 3L, 1L << 32, 1L << 61, Long.MAX_VALUE)) {
                            var module = CoreModules.reachable(merged(paths), "orderedInts");
                            var app = application(module, operation);
                            var args = (List<Object>) app.get(2);
                            args.set(1, Arrays.asList("lit", "int", Long.toString(index), metadata(args.get(1))));
                            var p = program(language, module, backend);
                            var function = context.asValue(new EntryValue(p, "orderedInts", 1));
                            var failure = assertThrows(PolyglotException.class, () -> function.execute(5L));
                            var guard = index < 0 || index > Long.MAX_VALUE / 8 ? "element" : "range";
                            assertEquals(RuntimeFault.class.getName() + ": Managed allocation " + guard
                                    + " outside its backing storage",
                                failure.getMessage(), backend + "/" + operation + "/" + index);
                            assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                            assertEquals(0L, count(p, "unsupportedTraps"));
                        }
                    var good = context.asValue(
                        new EntryValue(program(language, CoreModules.reachable(merged(paths), "orderedInts"), backend),
                            "orderedInts", 1));
                    assertEquals(mathematical("orderedInts", 5), good.execute(5L).asLong());
                } finally {
                    context.leave();
                }
            }
    }
    private static void require(boolean condition, String message) {
        if (!condition)
            throw new IllegalArgumentException(message);
    }
    private static List<?> prefix(List<?> list) {
        return list.subList(0, Math.min(2, list.size()));
    }
    private static Map<String, Object> changed(Map<String, Object> module, String key, Object value) {
        var result = new LinkedHashMap<>(module);
        result.put(key, value);
        return result;
    }
    private static long count(ExecutableProgram program, String key) {
        return ((Number) program.diagnostics().get(key)).longValue();
    }
}
