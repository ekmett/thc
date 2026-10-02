// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.*;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import thc.*;
import java.nio.file.*;
import java.nio.ByteOrder;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class FloatWordArrayNativeTest {
    private static String coreEntry(String name) {
        return "main:" + (name.startsWith("unboxedFloat") ? "UnboxedFloatArrays"
            : name.startsWith("unboxedWord") ? "UnboxedWordArrays"
            : name.equals("aliasWordBytes") ? "WordArrayAudit" : "FloatArrayAudit") + "." + name;
    }
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));

    private final List<String> names = List.of("unboxedFloatAccum", "unboxedFloatST", "unboxedWordAccum",
        "unboxedWordST", "moveFloatBits", "indexFloatBits", "aliasWordBytes");
    private final List<ByteArrayOp> operations = List.of(ByteArrayOp.READ_FLOAT, ByteArrayOp.WRITE_FLOAT,
        ByteArrayOp.INDEX_FLOAT, ByteArrayOp.READ_WORD, ByteArrayOp.WRITE_WORD, ByteArrayOp.INDEX_WORD);
    private Map<String, Object> manifest() throws Exception {
        return (Map<String, Object>) Json.parse(
            Files.readString(root.resolve("build/float-word-arrays/manifest.json")));
    }
    private Map<String, Object> merged(List<String> paths) throws Exception {
        var modules = new ArrayList<Map<String, Object>>();
        for (var path : paths) modules.add(thc.CoreCbdFixtures.read(root.resolve(path)));
        return CoreModules.merge(modules);
    }
    private ExecutableProgram program(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private Context context(boolean inlining) {
        return Context.newBuilder("thc")
            .allowExperimentalOptions(true)
            .option("compiler.Inlining", Boolean.toString(inlining))
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .option("engine.SingleTierCompilationThreshold", "10000000")
            .build();
    }
    private void valid(RootCallTarget target, String label) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label);
    }
    private void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
        valid(target, "initial installation");
    }
    private List<RootCallTarget> activeTargets(RootCallTarget entry) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget, Boolean>());
        var targets = new ArrayList<RootCallTarget>();
        visit(entry, seen, targets);
        return targets;
    }
    private void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> targets) {
        if (!seen.add(target))
            return;
        var root = target.getRootNode();
        var nodes = new ArrayList<Node>();
        nodes.add(root);
        // Bytecode DSL operation caches are not ordinary @Children fields.
        // Its public instruction API exposes the actual adopted cached nodes.
        if (root instanceof BytecodeRoot bytecode)
            for (var instruction : bytecode.getBytecodeNode().getInstructions())
                for (var argument : instruction.getArguments())
                    if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) {
                        var node = argument.asCachedNode();
                        if (node != null)
                            nodes.add(node);
                    }
        for (var node : nodes)
            for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class))
                if (call.getCurrentCallTarget() instanceof RootCallTarget active
                    && active.getRootNode() instanceof GuestRoot
                    && ((com.oracle.truffle.runtime.OptimizedCallTarget) active).getCallCount() > 0)
                    visit(active, seen, targets);
        targets.add(target); // Install callees before their callers.
    }
    private void released(Language language) {
        var state = language.getHandoffState().get();
        assertEquals(0, state.getResults().getDepth());
        assertEquals(0, state.getResults().retainedReferences());
        assertEquals(0, state.getArguments().getDepth());
        assertEquals(0, state.getArguments().retainedReferences());
    }

    private static BigInteger word(long value) {
        return BigInteger.valueOf(value).mod(BigInteger.ONE.shiftLeft(64));
    }
    private long model(String name, long seed) {
        return model(name, seed, ByteOrder.nativeOrder());
    }
    private long model(String name, long seed, ByteOrder byteOrder) {
        require(names.contains(name));
        if (List.of("moveFloatBits", "indexFloatBits").contains(name)) {
            require(!signalingNaN(seed), "Signaling NaN movement is outside the evidence domain");
            return seed & 0xffff_ffffL;
        }
        if (name.startsWith("unboxedFloat")) {
            // Integer fixed-point quarters: no host floating arithmetic in the model.
            long x = (seed & 65535L) - 32768L;
            var cells = new long[8];
            Arrays.fill(cells, x * 4);
            if (name.equals("unboxedFloatAccum")) {
                int[] indices = {0, 3, 0, 7};
                long[] quarters = {13, 22, -8, 2 * x};
                for (int i = 0; i < indices.length; i++) cells[indices[i]] += quarters[i];
            } else {
                if (!name.equals("unboxedFloatST"))
                    throw new IllegalStateException("Check failed.");
                long before = cells[0];
                cells[3] = before + 1;
                cells[7] = 3 * cells[3] - 4 * x + 2;
            }
            return cells[0] * 7 + cells[3] * 11 + cells[7] * 13;
        }
        // BigInteger modulo arithmetic independently models full-width Word cells.
        var modulus = BigInteger.ONE.shiftLeft(64);
        if (name.equals("unboxedWordAccum") || name.equals("unboxedWordST")) {
            var cells = new BigInteger[8];
            Arrays.fill(cells, word(seed));
            if (name.equals("unboxedWordAccum")) {
                int[] indices = {0, 3, 0, 7};
                var deltas = List.of(word(3), word(5), word(-2), word(seed));
                for (int i = 0; i < indices.length; i++)
                    cells[indices[i]] = cells[indices[i]].add(deltas.get(i)).mod(modulus);
            } else {
                cells[3] = cells[0].add(word(7)).mod(modulus);
                cells[7] = cells[3].multiply(word(3)).subtract(word(seed)).mod(modulus);
            }
            return cells[0]
                .multiply(word(7))
                .add(cells[3].multiply(word(11)))
                .add(cells[7].multiply(word(13)))
                .mod(modulus)
                .longValue();
        }
        if (!name.equals("aliasWordBytes"))
            throw new IllegalStateException("Check failed.");
        var bytes = new long[16];
        for (int offset = 0; offset < 16; offset++) {
            long value = offset < 8 ? seed : seed ^ 0x55aa55aa55aa55aaL;
            int position = offset % 8, shift = (byteOrder == ByteOrder.LITTLE_ENDIAN ? position : 7 - position) * 8;
            bytes[offset] = (value >>> shift) & 255;
        }
        bytes[7] = (seed + 101) & 255;
        bytes[8] = (seed + 37) & 255;
        return BigInteger.valueOf(3)
            .multiply(word(seed))
            .add(BigInteger.valueOf(16).multiply(element(bytes, 0, byteOrder)))
            .add(BigInteger.valueOf(20).multiply(element(bytes, 8, byteOrder)))
            .add(BigInteger.valueOf(17).multiply(word(bytes[0])))
            .add(BigInteger.valueOf(19).multiply(word(bytes[7])))
            .add(BigInteger.valueOf(23).multiply(word(bytes[8])))
            .add(BigInteger.valueOf(29).multiply(word(bytes[15])))
            .mod(modulus)
            .longValue();
    }
    private BigInteger element(long[] bytes, int offset, ByteOrder order) {
        var value = BigInteger.ZERO;
        for (int b = 0; b <= 7; b++) {
            int shift = (order == ByteOrder.LITTLE_ENDIAN ? b : 7 - b) * 8;
            value = value.or(BigInteger.valueOf(bytes[offset + b]).shiftLeft(shift));
        }
        return value;
    }
    private boolean signalingNaN(long bits) {
        return (bits & 0x7f800000L) == 0x7f800000L && (bits & 0x007fffffL) != 0 && (bits & 0x00400000L) == 0;
    }
    private List<Long> wordInputs() {
        var values = new TreeSet<Long>();
        for (long x = -16; x <= 16; x++) values.add(x);
        values.addAll(List.of(Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE));
        for (int bit = 0; bit <= 63; bit++)
            for (long delta = -1; delta <= 1; delta++)
                for (long sign : List.of(-1L, 1L)) values.add(sign * ((1L << bit) + delta));
        for (var bits : List.of("5555555555555555", "aaaaaaaaaaaaaaaa", "55aa55aa55aa55aa", "aa55aa55aa55aa55",
                 "0123456789abcdef", "fedcba9876543210", "8000000080000000", "ffffffff00000000", "800000007fffffff",
                 "7fffffff80000000", "ffffffff7fffffff", "0000000100000001", "12345678abcdef01"))
            values.add(Long.parseUnsignedLong(bits, 16));
        return new ArrayList<>(values);
    }

    private final List<Long> magnitudes = List.of(0L, 1L, 2L, 3L, 0x007fffffL, 0x00800000L, 0x3f7fffffL, 0x3f800000L,
        0x3f800001L, 0x7f7fffffL, 0x7f800000L, 0x7fc00000L, 0x7fc01234L, 0x7fffffffL);
    private List<Long> movementInputs() {
        var values = new TreeSet<>(wordInputs());
        var bits = new HashSet<Long>();
        for (long value : magnitudes) {
            bits.add(value);
            bits.add(value | 0x80000000L);
        }
        for (int bit = 0; bit <= 21; bit++)
            for (long sign : List.of(0L, 0x80000000L)) bits.add(0x7fc00000L | (1L << bit) | sign);
        for (long value : bits)
            for (long upper : List.of(0L, 0x1234567800000000L, -0x100000000L)) values.add(value | upper);
        return values.stream().filter(v -> !signalingNaN(v)).toList();
    }
    private Map<String, List<Long>> inputsByEntry() {
        var inputs = new LinkedHashMap<String, List<Long>>();
        for (var name : names)
            inputs.put(
                name, List.of("moveFloatBits", "indexFloatBits").contains(name) ? movementInputs() : wordInputs());
        return inputs;
    }
    private record Row(String name, long input, long answer) {}
    private List<Row> expectedRows() {
        var rows = new ArrayList<Row>();
        for (var entry : inputsByEntry().entrySet())
            for (long input : entry.getValue()) rows.add(new Row(entry.getKey(), input, model(entry.getKey(), input)));
        return rows;
    }
    private List<Row> verifyRows(String text) {
        var lines = new ArrayList<>(Arrays.asList(text.split("\\r\\n|\\n|\\r", -1)));
        if (!lines.isEmpty() && lines.getLast().isEmpty())
            lines.removeLast();
        var rows = new ArrayList<Row>();
        for (var line : lines) {
            var fields = line.split("\t", -1);
            require(fields.length == 3, "Float/Word oracle requires name/input/result");
            rows.add(new Row(fields[0], Long.parseLong(fields[1]), Long.parseLong(fields[2])));
        }
        require(
            rows.equals(expectedRows()), "Float/Word oracle/model mismatch or incomplete, duplicate, reordered inputs");
        return rows;
    }
    @Test
    public void independentDomainsPreserveFloatBitsAndAllMachineWords() {
        var words = wordInputs();
        var movement = movementInputs();
        assertEquals(397, words.size());
        assertEquals(590, movement.size());
        assertEquals(words.stream().distinct().sorted().toList(), words);
        assertEquals(movement.stream().distinct().sorted().toList(), movement);
        for (int bit = 0; bit <= 63; bit++)
            for (long delta = -1; delta <= 1; delta++)
                for (long sign : List.of(-1L, 1L)) assertTrue(words.contains(sign * ((1L << bit) + delta)));
        assertTrue(words.stream().anyMatch(this::signalingNaN));
        for (long bits : magnitudes)
            for (long sign : List.of(0L, 0x80000000L))
                for (long upper : List.of(0L, 0x1234567800000000L, -0x100000000L))
                    assertTrue(movement.contains(bits | sign | upper));
        for (int bit = 0; bit <= 21; bit++)
            for (long sign : List.of(0L, 0x80000000L)) assertTrue(movement.contains(0x7fc00000L | (1L << bit) | sign));
        for (long raw : movement) {
            assertFalse(signalingNaN(raw));
            for (var name : List.of("moveFloatBits", "indexFloatBits"))
                assertEquals(raw & 0xffffffffL, model(name, raw));
        }
        for (long raw : List.of(0x7f800001L, 0x7fbfffffL, 0xff800123L)) {
            assertTrue(signalingNaN(raw));
            for (var name : List.of("moveFloatBits", "indexFloatBits"))
                assertThrows(IllegalArgumentException.class, () -> model(name, raw));
            // Integer storage and bounded public arithmetic do not exclude these words.
            for (var name : names)
                if (!List.of("moveFloatBits", "indexFloatBits").contains(name))
                    model(name, raw);
        }
        assertThrows(IllegalArgumentException.class, () -> model("unknown", 0));
    }
    // All quantities are quarter-integers. Reduce only the denominator's two
    // powers of two, then bound the numerator to binary32's 24-bit precision.
    private long exact(long quarters) {
        long numerator = Math.abs(quarters);
        for (int i = 0; i < 2; i++)
            if (numerator % 2 == 0)
                numerator /= 2;
        assertTrue(numerator < (1L << 24), "Non-exact binary32 quarter-integer " + quarters);
        return quarters;
    }
    @Test
    public void independentPublicArithmeticHasExactBinary32Intermediates() {
        var values = new ArrayList<>(wordInputs());
        values.addAll(List.of(0L, 65535L, Long.MIN_VALUE, Long.MAX_VALUE));
        for (long raw : values) {
            long x = (raw & 65535) - 32768, q = exact(x * 4);
            var accum = List.of(exact(exact(q + 13) - 8), exact(q + 22), exact(q + exact(q / 2)));
            long after = exact(q + 1);
            var st = List.of(q, after, exact(exact(exact(3 * after) - q) + 2));
            for (var name : List.of("unboxedFloatAccum", "unboxedFloatST")) {
                var cells = name.equals("unboxedFloatAccum") ? accum : st;
                var weighted = new ArrayList<Long>();
                int[] weights = {7, 11, 13};
                for (int i = 0; i < cells.size(); i++) weighted.add(exact(cells.get(i) * weights[i]));
                long answer = exact(4 * exact(exact(weighted.get(0) + weighted.get(1)) + weighted.get(2)));
                assertEquals(0L, answer % 4);
                assertEquals(answer / 4, model(name, raw));
            }
            assertEquals(150 * x + 277, model("unboxedFloatAccum", raw));
            assertEquals(176 * x + 76, model("unboxedFloatST", raw));
            assertEquals(44 * raw + 62, model("unboxedWordAccum", raw));
            assertEquals(44 * raw + 350, model("unboxedWordST", raw));
        }
    }
    private int shift(int index, ByteOrder order) {
        return 8 * (order == ByteOrder.LITTLE_ENDIAN ? index : 7 - index);
    }
    private long byteValue(long value, int index, ByteOrder order) {
        return (value >>> shift(index, order)) & 255;
    }
    @Test
    public void independentWordAliasingChecksBothByteOrders() {
        for (var order : List.of(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN))
            for (long raw : wordInputs()) {
                long first = (raw & ~(255L << shift(7, order))) | (((raw + 101) & 255) << shift(7, order)),
                     second = ((raw ^ 0x55aa55aa55aa55aaL) & ~(255L << shift(0, order)))
                    | (((raw + 37) & 255) << shift(0, order));
                long expected = 3 * raw + 16 * first + 20 * second + 17 * byteValue(first, 0, order)
                    + 19 * byteValue(first, 7, order) + 23 * byteValue(second, 0, order)
                    + 29 * byteValue(second, 7, order);
                assertEquals(expected, model("aliasWordBytes", raw, order));
            }
        assertNotEquals(
            model("aliasWordBytes", 0, ByteOrder.LITTLE_ENDIAN), model("aliasWordBytes", 0, ByteOrder.BIG_ENDIAN));
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
    public void independentOracleRejectsCorruptOrIncompleteRows() {
        var rows = expectedRows();
        var valid = text(rows);
        assertEquals(3165, rows.size());
        assertEquals(rows, verifyRows(valid));
        var duplicate = new ArrayList<>(rows);
        duplicate.add(rows.getFirst());
        var first = rows.getFirst();
        var corrupt = List.of("", text(rows.subList(1, rows.size())), text(duplicate), text(rows.reversed()),
            text(firstChanged(rows, rows.get(1))),
            text(firstChanged(rows, new Row(first.name, first.input, first.answer + 1))),
            text(firstChanged(rows, new Row("unknown", first.input, first.answer))), valid.replaceFirst("\t", " "),
            valid.replaceFirst("\t", "\textra\t"), valid + "\n", "moveFloatBits\t9223372036854775808\t0\n",
            "moveFloatBits\t0\tnan\n");
        for (int i = 0; i < corrupt.size(); i++) {
            var bad = corrupt.get(i);
            assertThrows(IllegalArgumentException.class, () -> verifyRows(bad), "mutation " + i);
        }
    }
    @Test
    public void nativePublicArraysAndFloatMovementWithInlining() throws Exception {
        nativeChecks(true);
    }
    @Test
    public void nativePublicArraysAndFloatMovementAcrossResidualCalls() throws Exception {
        nativeChecks(false);
    }

    private Set<String> requiredPrimitives(String name) {
        if (name.equals("aliasWordBytes"))
            return Set.of("newByteArray#", "unsafeFreezeByteArray#", "readWordArray#", "writeWordArray#",
                "indexWordArray#", "writeWord8Array#", "indexWord8Array#");
        if (name.equals("moveFloatBits") || name.equals("indexFloatBits"))
            return Set.of("newByteArray#", "unsafeFreezeByteArray#", "writeWord32Array#", "indexWord32Array#",
                "writeFloatArray#", name.equals("moveFloatBits") ? "readFloatArray#" : "indexFloatArray#");
        var lane = name.startsWith("unboxedFloat") ? "Float" : "Word";
        return Set.of("read" + lane + "Array#", "write" + lane + "Array#", "index" + lane + "Array#",
            "newByteArray#", "unsafeFreezeByteArray#");
    }
    private void checkCore(ArrayCoreEvidence evidence, String name) {
        require(evidence.getPrimitiveCounts().keySet().containsAll(requiredPrimitives(name)),
            name + " lost a required memory operation");
        if (!List.of("moveFloatBits", "indexFloatBits").contains(name)) return;
        boolean read = name.equals("moveFloatBits");
        var primitive = read ? "readFloatArray#" : "indexFloatArray#";
        require(evidence.getBindings().stream().anyMatch(helper -> {
            var id = helper.get("id");
            if (Objects.equals(id, evidence.getRoot().get("id"))) return false;
            var expr = (List<Object>) helper.get("expr");
            var info = metadata(expr);
            if (info == null || !(info.get("resultRep") instanceof Map<?, ?> rep)
                || !List.of("FloatRep").equals(rep.get("primReps"))) return false;
            if (read) {
                if (!"unboxed-tuple".equals(rep.get("aggregate"))
                    || !(rep.get("components") instanceof List<?> parts) || parts.size() != 2
                    || !(parts.get(0) instanceof Map<?, ?> state) || !"void".equals(state.get("kind"))
                    || !List.of().equals(state.get("primReps"))
                    || !(parts.get(1) instanceof Map<?, ?> value) || !"float".equals(value.get("kind"))
                    || !List.of("FloatRep").equals(value.get("primReps"))) return false;
            } else if (!"float".equals(rep.get("kind"))) return false;
            return evidence.nodes(expr).stream().anyMatch(n -> prefix(n).equals(List.of("prim", primitive)))
                && evidence.getBindings().stream().filter(b -> !Objects.equals(b.get("id"), id))
                    .flatMap(b -> applications(b.get("expr")).stream())
                    .anyMatch(call -> prefix((List<?>) call.get(1)).equals(List.of("var", id))
                        && Objects.equals((long) ((List<?>) call.get(2)).size(), helper.get("arity")));
        }), name + " lost its called residual Float result path");
    }
    @Test
    public void coreEvidenceRequiresMemoryOperations() throws Exception {
        for (var paths : ((Map<String, List<String>>) manifest().get("stages")).values())
            for (var name : names) {
                checkCore(new ArrayCoreEvidence(merged(paths), coreEntry(name)), name);
                for (var primitive : requiredPrimitives(name)) {
                    var module = merged(paths);
                    var evidence = new ArrayCoreEvidence(module, coreEntry(name));
                    for (var binding : evidence.getBindings())
                        for (var node : evidence.nodes(binding.get("expr")))
                            if (prefix(node).equals(List.of("prim", primitive))) node.set(1, "missing#");
                    assertThrows(IllegalArgumentException.class,
                        () -> checkCore(new ArrayCoreEvidence(module, coreEntry(name)), name), name + "/" + primitive);
                }
            }
    }
    @Test
    public void residualCoreEvidenceRequiresCalledTypedLaneResults() throws Exception {
        for (var paths : ((Map<String, List<String>>) manifest().get("stages")).values())
            for (var name : List.of("moveFloatBits", "indexFloatBits"))
                for (var mutation : name.equals("moveFloatBits") ? List.of("call", "lane", "state") : List.of("call", "lane")) {
                    var module = merged(paths);
                    var evidence = new ArrayCoreEvidence(module, coreEntry(name));
                    checkCore(evidence, name);
                    var primitive = name.equals("moveFloatBits") ? "readFloatArray#" : "indexFloatArray#";
                    var helper = evidence.getBindings().stream()
                        .filter(b -> !Objects.equals(b.get("id"), evidence.getRoot().get("id")))
                        .filter(b -> evidence.nodes(b.get("expr")).stream()
                            .anyMatch(n -> prefix(n).equals(List.of("prim", primitive))))
                        .findFirst().orElseThrow();
                    var rep = (Map<String, Object>) metadata(helper.get("expr")).get("resultRep");
                    switch (mutation) {
                        case "call" -> {
                            for (var binding : evidence.getBindings())
                                for (var call : applications(binding.get("expr")))
                                    if (prefix((List<?>) call.get(1)).equals(List.of("var", helper.get("id"))))
                                        call.set(1, List.of("prim", primitive));
                        }
                        case "lane" -> rep.put("primReps", List.of("IntRep"));
                        case "state" -> ((Map<String, Object>) ((List<?>) rep.get("components")).getFirst())
                            .put("kind", "long");
                    }
                    var failure = assertThrows(IllegalArgumentException.class,
                        () -> checkCore(new ArrayCoreEvidence(module, coreEntry(name)), name), name + "/" + mutation);
                    assertTrue(failure.getMessage().contains("called residual"), failure.getMessage());
                }
    }

    private void nativeChecks(boolean inlining) throws Exception {
        var manifest = manifest();
        assertEquals(names, manifest.get("entries"));
        assertEquals(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? "little" : "big", manifest.get("byteOrder"));
        assertEquals(64, ((Number) manifest.get("wordBits")).intValue());
        assertEquals(true, manifest.get("signalingNaNsExcluded"));
        assertEquals(Set.of("moveFloatBits", "indexFloatBits"),
            new HashSet<>((List<String>) manifest.get("signalingNaNExclusionScope")));
        assertEquals(new HashSet<>(names), ((Map<String, ?>) manifest.get("inputsByEntry")).keySet());
        assertEquals(inputsByEntry(), manifest.get("inputsByEntry"));
        for (var kind : List.of("inputHashes", "artifactHashes"))
            for (var e : ((Map<String, String>) manifest.get(kind)).entrySet()) {
                var actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(e.getKey()))));
                assertEquals(e.getValue(), actual,
                    "Stale Float/Word-array fixture: " + e.getKey() + "; regenerate the Float/Word-array fixtures");
            }
        var rows = new LinkedHashMap<String, List<Row>>();
        for (var row : verifyRows(Files.readString(root.resolve("build/float-word-arrays/oracle.tsv"))))
            rows.computeIfAbsent(row.name, k -> new ArrayList<>()).add(row);
        assertEquals(new HashSet<>(names), rows.keySet());
        assertEquals(3165L, manifest.get("nativeRows"));
        assertEquals(
            ((Number) manifest.get("nativeRows")).intValue(), rows.values().stream().mapToInt(List::size).sum());
        var stages = (Map<String, List<String>>) manifest.get("stages");
        assertEquals(Set.of("pre", "post"), stages.keySet());
        for (var stage : stages.entrySet()) {
            var module = merged(stage.getValue());
            for (var name : names) {
                checkCore(new ArrayCoreEvidence(module, coreEntry(name)), name);
                var cases = rows.get(name);
                boolean movement = List.of("moveFloatBits", "indexFloatBits").contains(name);
                assertEquals(movement ? 590 : 397, cases.size(), name + " pinned input domain");
                assertEquals(cases.size(), new HashSet<>(cases.stream().map(Row::input).toList()).size());
                assertEquals(inputsByEntry().get(name), cases.stream().map(Row::input).toList());
                for (var row : cases) {
                    assertEquals(model(name, row.input), row.answer, "Native " + name + "(" + row.input + ")");
                    if (movement) {
                        long bits = row.input & 0xffff_ffffL, exponent = bits & 0x7f80_0000L,
                             fraction = bits & 0x007f_ffffL;
                        assertFalse(exponent == 0x7f80_0000L && fraction != 0 && (bits & 0x0040_0000L) == 0);
                    }
                }
                for (var backend : List.of("ast", "bytecode")) try (var context = context(inlining)) {
                        context.initialize("thc");
                        context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            var linked = CoreModules.reachable(module, coreEntry(name));
                            var bindings = (List<Map<String, Object>>) linked.get("bindings");
                            var program = program(language, changed(linked, "instrument", true), backend);
                            var entry = program.entryTarget(
                                (String) single(bindings.stream().filter(b -> coreEntry(name).equals(b.get("id"))).toList())
                                    .get("id"));
                            checkCases(cases, stage.getKey(), backend, name, inlining, program, entry,
                                false, language);
                            for (var target : activeTargets(entry)) compile(target);
                            checkCases(cases, stage.getKey(), backend, name, inlining, program, entry,
                                true, language);
                            for (var counter : List.of("unsupportedTraps", "blackholes"))
                                assertEquals(0L, count(program, counter), counter);
                        } finally {
                            context.leave();
                        }
                    }
            }
        }
    }
    private void checkCases(List<Row> cases, String stage, String backend, String name, boolean inlining,
        ExecutableProgram program, RootCallTarget entry, boolean compiled, Language language) {
        for (var row : cases) {
            var label = stage + "/" + backend + "/" + name + "/" + row.input + "/inlining=" + inlining;
            long before = count(program, "compiledEntries");
            assertEquals(row.answer, Calls.target(entry, new Object[] {0L, row.input}), label);
            if (compiled)
                assertTrue(count(program, "compiledEntries") > before, label + " must enter installed guest code");
            released(language);
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
    private String owner(ByteArrayOp operation) {
        return switch (operation) {
            case INDEX_FLOAT -> "indexFloatBits";
            case READ_FLOAT, WRITE_FLOAT -> "moveFloatBits";
            default -> "aliasWordBytes";
        };
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
    private Map<String, Object> wrong(String kind, String rep) {
        return Map.of("kind", kind, "primReps", List.of(rep), "evaluated", true);
    }
    @Test
    public void longAliasesExecuteWhileFloatStateShapeAndSaturationGuardsRemain() throws Exception {
        var paths = paths();
        for (var backend : List.of("ast", "bytecode")) try (var context = context(true)) {
                context.initialize("thc");
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (var operation : operations)
                        for (int mutation = 0; mutation <= 15; mutation++)
                            for (boolean diagnostic : List.of(false, true)) {
                                var module = CoreModules.reachable(merged(paths), coreEntry(owner(operation)));
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
                                    case 3 -> metadata.put("rep", wrong("double", "DoubleRep"));
                                    case 4 -> flags.set(0, true);
                                    case 5 -> rep(args.get(0)).put("kind", "unknown");
                                    case 6 -> rep(args.get(1)).put("primReps", List.of("Int64Rep"));
                                    case 7 -> {
                                        if (List.of(ByteArrayOp.WRITE_FLOAT, ByteArrayOp.WRITE_WORD)
                                                .contains(operation))
                                            metadata(args.get(2)).put("rep", wrong("long", "IntRep"));
                                        else
                                            metadata.put("rep", wrong("long", "IntRep"));
                                    }
                                    case 8 -> {
                                        if (List.of(ByteArrayOp.READ_FLOAT, ByteArrayOp.READ_WORD)
                                                .contains(operation)) {
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
                                        if (List.of(ByteArrayOp.READ_FLOAT, ByteArrayOp.READ_WORD)
                                                .contains(operation)) {
                                            var proof = (Map<String, Object>) metadata.get("rep");
                                            ((List<Object>) proof.get("components"))
                                                .set(1, wrong("double", "DoubleRep"));
                                            proof.put("primReps", List.of("DoubleRep"));
                                        } else
                                            flags.set(1, true);
                                    }
                                    default -> {
                                        var rep = switch (mutation) {
                                            case 10 ->
                                                operation.getPrimitive().contains("Float") ? "Word32Rep" : "Word64Rep";
                                            case 11 -> "Int32Rep";
                                            case 12 -> "Int64Rep";
                                            case 13 -> "IntRep";
                                            case 14 ->
                                                operation.getPrimitive().contains("Float") ? "DoubleRep" : "Word32Rep";
                                            default ->
                                                operation.getPrimitive().contains("Float") ? "FloatRep" : "WordRep";
                                        };
                                        var kind = mutation == 15     ? "unknown"
                                            : rep.equals("DoubleRep") ? "double"
                                                                      : "long";
                                        var payload = wrong(kind, rep);
                                        if (operation.getPrimitive().startsWith("write"))
                                            metadata(args.get(2)).put("rep", payload);
                                        else if (operation.getTuple()) {
                                            var proof = (Map<String, Object>) metadata.get("rep");
                                            ((List<Object>) proof.get("components")).set(1, payload);
                                            proof.put("primReps", List.of(rep));
                                        } else
                                            metadata.put("rep", payload);
                                    }
                                }
                                // Tuple payload metadata must still agree with its case binder.

                                boolean word =
                                    List.of(ByteArrayOp.READ_WORD, ByteArrayOp.WRITE_WORD, ByteArrayOp.INDEX_WORD)
                                        .contains(operation);
                                boolean sameCarrier = mutation == 6
                                    || (word && !operation.getTuple()
                                        && (mutation == 7 || mutation == 10 || mutation == 12 || mutation == 13));
                                if (sameCarrier) {
                                    var name = owner(operation);
                                    var p = program(
                                        language, changed(module, "diagnosticUnsupported", diagnostic), backend);
                                    for (long seed : List.of(5L, 0x80000000L, Long.MIN_VALUE)) {
                                        assertEquals(model(name, seed),
                                            Calls.target(p.hostEntryTarget(1),
                                                new Object[] {p.entryValue(coreEntry(name)), new Object[] {seed}}),
                                            backend + "/" + operation + "/mutation" + mutation + "/" + diagnostic + "/"
                                                + seed);
                                        released(language);
                                    }
                                } else if (diagnostic && mutation == 15 && operation.getTuple()) {
                                    // An unknown tuple component is the existing unsupported aggregate frontier.
                                    // Diagnostic mode may defer it, but must trap on demand.
                                    var p = program(language,
                                        changed(changed(module, "diagnosticUnsupported", true), "instrument", true),
                                        backend);
                                    var reason = "Unsupported Core aggregate representation: unboxed-tuple has "
                                                 + "unsupported fields";
                                    assertTrue(((List<?>) p.diagnostics().get("deferredUnsupported")).contains(reason));
                                    assertEquals(0L, count(p, "unsupportedTraps"));
                                    var failure = assertThrows(RuntimeFault.class,
                                        ()
                                            -> Calls.target(p.hostEntryTarget(1),
                                                new Object[] {p.entryValue(coreEntry(owner(operation))), new Object[] {5L}}));
                                    assertEquals(
                                        "Diagnostic unsupported path reached: " + reason, failure.getMessage());
                                    assertEquals(1L, count(p, "unsupportedTraps"));
                                    released(language);
                                } else
                                    assertThrows(RuntimeFault.class,
                                        ()
                                            -> program(language, changed(module, "diagnosticUnsupported", diagnostic),
                                                backend),
                                        backend + "/" + operation + "/mutation" + mutation + "/" + diagnostic);
                            }
                    for (var operation : operations) {
                        var module = CoreModules.reachable(merged(paths), coreEntry(owner(operation)));
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
    public void invalidElementOffsetsAreGuardedOnBothBackendsWithoutNativeUndefinedAccesses() throws Exception {
        var paths = paths();
        for (var backend : List.of("ast", "bytecode")) try (var context = context(true)) {
                context.initialize("thc");
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (var operation : operations)
                        for (long index :
                            List.of(Long.MIN_VALUE, -1L, operation.getPrimitive().contains("Float") ? 1L : 2L, 1L << 32,
                                1L << 61, 1L << 62, Long.MAX_VALUE)) {
                            var name = owner(operation);
                            var module = CoreModules.reachable(merged(paths), coreEntry(name));
                            var app = application(module, operation);
                            var args = (List<Object>) app.get(2);
                            args.set(1, Arrays.asList("lit", "int", Long.toString(index), metadata(args.get(1))));
                            var p = program(language, module, backend);
                            var function = context.asValue(new EntryValue(p, coreEntry(name), 1));
                            var failure = assertThrows(PolyglotException.class, () -> function.execute(5L));
                            int width = operation.getPrimitive().contains("Float") ? 4 : 8;
                            var guard = index < 0 || index > Long.MAX_VALUE / width ? "element" : "range";
                            assertEquals(RuntimeFault.class.getName() + ": Managed allocation " + guard
                                    + " outside its backing storage",
                                failure.getMessage(), backend + "/" + operation + "/" + index);
                            released(language);
                            assertEquals(0L, count(p, "unsupportedTraps"));
                        }
                } finally {
                    context.leave();
                }
            }
    }
    private static void require(boolean condition) {
        require(condition, "Failed requirement.");
    }
    private static void require(boolean condition, String message) {
        if (!condition)
            throw new IllegalArgumentException(message);
    }
    private static List<?> prefix(List<?> list) {
        return list.subList(0, Math.min(2, list.size()));
    }
    private static <T> T single(List<T> list) {
        if (list.size() != 1)
            throw new IllegalArgumentException("Expected a single element");
        return list.getFirst();
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
