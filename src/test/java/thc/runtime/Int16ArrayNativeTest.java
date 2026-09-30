// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import java.math.BigInteger;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.*;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import thc.*;
import java.nio.file.*;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class Int16ArrayNativeTest {
    private static String coreEntry(String name) {
        return "main:" + (name.startsWith("unboxed") ? "Unboxed16Arrays" : "Int16ArrayAudit") + "." + name;
    }
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));

    private final List<String> names = List.of("unboxedInt16Accum", "unboxedInt16ST", "unboxedWord16Accum",
        "unboxedWord16ST", "aliasInt16Bytes", "aliasWord16Bytes");
    private final List<ByteArrayOp> operations = List.of(ByteArrayOp.READ_INT16, ByteArrayOp.WRITE_INT16,
        ByteArrayOp.INDEX_INT16, ByteArrayOp.READ_WORD16, ByteArrayOp.WRITE_WORD16, ByteArrayOp.INDEX_WORD16);
    private Map<String, Object> manifest() throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(root.resolve("build/int16-arrays/manifest.json")));
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
        // Match EntryValue.compile: installation alone does not restore a
        // retired shared boundary before the first measured guest call.
        var runtime = Truffle.getRuntime();
        runtime.getClass()
            .getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"))
            .invoke(runtime, target);
        valid(target, "installation after boundary restoration");
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
                    && active.getRootNode() instanceof GuestRoot)
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

    private long decode(long value, boolean unsigned) {
        long bits = value & 0xffffL;
        return !unsigned && bits >= 0x8000L ? bits - 0x1_0000L : bits;
    }
    private long model(String name, long seed) {
        return model(name, seed, ByteOrder.nativeOrder());
    }
    private long model(String name, long seed, ByteOrder byteOrder) {
        require(names.contains(name));
        boolean unsigned = name.contains("Word16");
        long bits = seed & 0xffffL;
        if (name.endsWith("Accum") || name.endsWith("ST")) {
            var cells = new long[8];
            Arrays.fill(cells, decode(bits, unsigned));
            if (name.endsWith("Accum")) {
                int[] indices = {0, 3, 0, 7};
                long[] deltas = {3, 5, -2, bits};
                for (int i = 0; i < indices.length; i++)
                    cells[indices[i]] = decode(cells[indices[i]] + deltas[i], unsigned);
            } else {
                long before = cells[0];
                cells[3] = decode(before + 7, unsigned);
                long after = cells[3];
                cells[7] = decode(3 * after - decode(bits, unsigned), unsigned);
            }
            return 7 * cells[0] + 11 * cells[3] + 13 * cells[7];
        }
        if (!List.of("aliasInt16Bytes", "aliasWord16Bytes").contains(name))
            throw new IllegalStateException("Check failed.");
        var bytes = new long[4];
        for (int offset = 0; offset < 4; offset++) {
            long value = offset < 2 ? bits : bits ^ 0x55aaL;
            int position = offset % 2, shift = (byteOrder == ByteOrder.LITTLE_ENDIAN ? position : 1 - position) * 8;
            bytes[offset] = (value >>> shift) & 255;
        }
        bytes[1] = (seed + 101) & 255;
        bytes[2] = (seed + 37) & 255;
        return 3 * decode(bits, unsigned) + 16 * element(bytes, 0, byteOrder, unsigned)
            + 20 * element(bytes, 2, byteOrder, unsigned) + 17 * bytes[0] + 19 * bytes[1] + 23 * bytes[2]
            + 29 * bytes[3];
    }
    private long element(long[] bytes, int offset, ByteOrder order, boolean unsigned) {
        long value = 0;
        for (int b = 0; b <= 1; b++) {
            int shift = (order == ByteOrder.LITTLE_ENDIAN ? b : 1 - b) * 8;
            value |= bytes[offset + b] << shift;
        }
        return decode(value, unsigned);
    }
    // Independently pinned input inventory; the producer/manifest cannot silently shrink the domain.
    private final List<Long> inputs = inputs();
    private List<Long> inputs() {
        var values = new TreeSet<Long>();
        for (long x = -16; x <= 16; x++) values.add(x);
        values.addAll(List.of(Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE));
        for (int bit = 0; bit <= 63; bit++)
            for (long delta = -1; delta <= 1; delta++)
                for (long sign : List.of(-1L, 1L)) values.add(sign * ((1L << bit) + delta));
        values.addAll(List.of(0x5555555555555555L, 0xaaaaaaaaaaaaaaaaL, 0x55aa55aa55aa55aaL, 0xaa55aa55aa55aa55L,
            0x0123456789abcdefL, 0xfedcba9876543210L, 0x8000000080000000L, 0xffffffff00000000L, 0x800000007fffffffL,
            0x7fffffff80000000L, 0xffffffff7fffffffL, 0x0000000100000001L, 0x12345678abcdef01L, 0x80008000L,
            0xffff0000L, 0x80007fffL, 0x7fff8000L, 0xffff7fffL, 0x00010001L, 0x12345678abcd8000L));
        return new ArrayList<>(values);
    }
    private final Map<String, Long> literalValues = literalValues();
    private Map<String, Long> literalValues() {
        var values = new LinkedHashMap<String, Long>();
        values.put("noinlineInt16Literal", -32768L);
        values.put("noinlineWord16Literal", 65535L);
        return values;
    }
    private final List<Long> literalInputs = List.of(Long.MIN_VALUE, -32768L, -1L, 0L, 1L, 32767L, Long.MAX_VALUE);
    private long literalModel(String name, long raw) {
        return raw + Objects.requireNonNull(literalValues.get(name));
    }
    private record Row(long input, long answer) {}
    private Map<String, List<Row>> checkedRows(String text) {
        return checkedRows(text, false);
    }
    private Map<String, List<Row>> checkedRows(String text, boolean literals) {
        var entries = literals ? new ArrayList<>(literalValues.keySet()) : names;
        var values = literals ? literalInputs : inputs;
        var lines = new ArrayList<>(Arrays.asList(text.split("\\r\\n|\\n|\\r", -1)));
        if (!lines.isEmpty() && lines.getLast().isEmpty())
            lines.removeLast();
        require(lines.size() == entries.size() * values.size(), "Int16 oracle row count mismatch");
        var rows = new LinkedHashMap<String, List<Row>>();
        for (var name : entries) rows.put(name, new ArrayList<>());
        for (int index = 0; index < lines.size(); index++) {
            var fields = lines.get(index).split("\t", -1);
            require(fields.length == 3, "Int16 oracle columns at row " + index);
            var name = entries.get(index / values.size());
            long raw = values.get(index % values.size());
            require(fields[0].equals(name) && Objects.equals(longOrNull(fields[1]), raw),
                "Int16 oracle inventory/order at row " + index);
            var answer = longOrNull(fields[2]);
            require(Objects.equals(answer, literals ? literalModel(name, raw) : model(name, raw)),
                "Int16 native/model mismatch at row " + index);
            rows.get(name).add(new Row(raw, answer));
        }
        return rows;
    }
    private Long longOrNull(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
    @Test
    public void independentInventoryAndNarrowingCoverFullWidthBoundaries() {
        assertEquals(403, inputs.size());
        assertEquals(2418, names.size() * inputs.size());
        assertEquals(inputs, inputs.stream().distinct().sorted().toList());
        for (int bit = 0; bit <= 63; bit++)
            for (long delta = -1; delta <= 1; delta++)
                for (long sign : List.of(-1L, 1L)) assertTrue(inputs.contains(sign * ((1L << bit) + delta)));
        for (var name : names)
            for (long raw : List.of(0L, 1L, 0x7fffL, 0x8000L, 0xffffL))
                for (var order : List.of(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
                    assertEquals(model(name, raw, order), model(name, raw + 65536L, order));
                    assertEquals(model(name, raw, order), model(name, raw - 65536L, order));
                }
        for (var pair : List.of(List.of("unboxedInt16Accum", "unboxedWord16Accum"),
                 List.of("unboxedInt16ST", "unboxedWord16ST"), List.of("aliasInt16Bytes", "aliasWord16Bytes")))
            assertNotEquals(model(pair.get(0), 0x8000L), model(pair.get(1), 0x8000L));
    }
    private long wide(long value, boolean unsigned) {
        return unsigned ? value & 65535L : (long) (short) value;
    }
    @Test
    public void independentModelMatchesClosedFormCellsAndBothEndianAliasMasks() {
        for (long raw : inputs)
            for (boolean unsigned : List.of(false, true)) {
                var kind = unsigned ? "Word16" : "Int16";
                assertEquals(7 * wide(raw + 1, unsigned) + 11 * wide(raw + 5, unsigned) + 13 * wide(2 * raw, unsigned),
                    model("unboxed" + kind + "Accum", raw));
                assertEquals(7 * wide(raw, unsigned) + 11 * wide(raw + 7, unsigned) + 13 * wide(2 * raw + 21, unsigned),
                    model("unboxed" + kind + "ST", raw));
                for (var order : List.of(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
                    boolean lowFirst = order == ByteOrder.LITTLE_ENDIAN;
                    int shiftA = lowFirst ? 8 : 0, shiftB = 8 - shiftA;
                    long before = raw & 65535L, other = (raw ^ 0x55aaL) & 65535L,
                         a = (before & ~(255L << shiftA)) | (((raw + 101L) & 255L) << shiftA),
                         b = (other & ~(255L << shiftB)) | (((raw + 37L) & 255L) << shiftB),
                         byte0 = lowFirst ? a & 255L : a >>> 8, byte3 = lowFirst ? b >>> 8 : b & 255L;
                    assertEquals(3 * wide(before, unsigned) + 16 * wide(a, unsigned) + 20 * wide(b, unsigned)
                            + 17 * byte0 + 19 * ((raw + 101L) & 255L) + 23 * ((raw + 37L) & 255L) + 29 * byte3,
                        model("alias" + kind + "Bytes", raw, order), kind + "/" + raw + "/" + order);
                }
            }
        assertNotEquals(
            model("aliasInt16Bytes", 0L, ByteOrder.LITTLE_ENDIAN), model("aliasInt16Bytes", 0L, ByteOrder.BIG_ENDIAN));
    }
    @Test
    public void literalModelWrapsOnlyTheMachineResult() {
        for (var e : literalValues.entrySet())
            for (long raw : literalInputs)
                assertEquals(BigInteger.valueOf(raw).add(BigInteger.valueOf(e.getValue())).longValue(),
                    literalModel(e.getKey(), raw));
        assertEquals(Long.MAX_VALUE - 32767L, literalModel("noinlineInt16Literal", Long.MIN_VALUE));
        assertEquals(Long.MIN_VALUE + 65534L, literalModel("noinlineWord16Literal", Long.MAX_VALUE));
        assertEquals(-32768L, literalModel("noinlineInt16Literal", 0L));
        assertEquals(65535L, literalModel("noinlineWord16Literal", 0L));
    }
    private String text(List<String> rows) {
        return String.join("\n", rows) + "\n";
    }
    private void reject(List<String> rows, boolean literals) {
        assertThrows(IllegalArgumentException.class, () -> checkedRows(text(rows), literals));
    }
    private long answer(String name, long raw, boolean literals) {
        return literals ? literalModel(name, raw) : model(name, raw);
    }
    @Test
    public void exactOraclesRejectMissingDuplicateReorderedMalformedAndWrongRows() {
        for (boolean literals : List.of(false, true)) {
            var entries = literals ? new ArrayList<>(literalValues.keySet()) : names;
            var values = literals ? literalInputs : inputs;
            var lines = new ArrayList<String>();
            var expected = new LinkedHashMap<String, List<Row>>();
            for (var name : entries) {
                var rows = new ArrayList<Row>();
                for (long input : values) {
                    lines.add(name + "\t" + input + "\t" + answer(name, input, literals));
                    rows.add(new Row(input, answer(name, input, literals)));
                }
                expected.put(name, rows);
            }
            assertEquals(expected, checkedRows(text(lines), literals));
            assertEquals(expected, checkedRows(String.join("\n", lines), literals));
            reject(List.of(), literals);
            reject(lines.subList(1, lines.size()), literals);
            var copy = new ArrayList<>(lines);
            copy.add(lines.getFirst());
            reject(copy, literals);
            reject(lines.reversed(), literals);
            copy = new ArrayList<>(lines);
            copy.set(1, copy.getFirst());
            reject(copy, literals);
            copy = new ArrayList<>(lines);
            Collections.swap(copy, 0, 1);
            reject(copy, literals);
            copy = new ArrayList<>(lines.subList(values.size(), lines.size()));
            copy.addAll(lines.subList(0, values.size()));
            reject(copy, literals);
            for (int nameIndex = 0; nameIndex < entries.size(); nameIndex++) {
                copy = new ArrayList<>(lines);
                int index = nameIndex * values.size();
                var line = copy.get(index);
                copy.set(index,
                    line.substring(0, line.lastIndexOf('\t')) + "\t"
                        + (answer(entries.get(nameIndex), values.getFirst(), literals) ^ 1L));
                reject(copy, literals);
            }
            for (var bad : List.of("unknown\t0\t0", "", entries.getFirst() + "\t0", lines.getFirst() + "\t0",
                     entries.getFirst() + "\tbad\t0", entries.getFirst() + "\t9223372036854775808\t0",
                     entries.getFirst() + "\t" + values.getFirst() + "\t",
                     entries.getFirst() + "\t" + values.getFirst() + "\t1.5",
                     entries.getFirst() + "\t" + values.getFirst() + "\t9223372036854775808")) {
                copy = new ArrayList<>(lines);
                copy.set(0, bad);
                reject(copy, literals);
            }
            copy = new ArrayList<>(lines);
            copy.add("");
            reject(copy, literals);
        }
    }
    private Map<String, Integer> exactAliasPrimitives(String name) {
        require(List.of("aliasInt16Bytes", "aliasWord16Bytes").contains(name));
        var kind = name.contains("Word16") ? "Word16" : "Int16";
        var result = new HashMap<String, Integer>();
        String[] primitives = {"newByteArray#", "unsafeFreezeByteArray#", "write" + kind + "Array#",
            "read" + kind + "Array#", "index" + kind + "Array#", "writeWord8Array#", "indexWord8Array#", "*#", "+#",
            "xorI#", "word8ToWord#", "wordToWord8#"};
        int[] counts = {1, 1, 2, 3, 2, 2, 4, 9, 10, 1, 4, 2};
        for (int i = 0; i < counts.length; i++) result.put(primitives[i], counts[i]);
        result.putAll(kind.equals("Int16")
                ? Map.of("int2Word#", 2, "word2Int#", 4, "intToInt16#", 2, "int16ToInt#", 5)
                : Map.of("int2Word#", 4, "word2Int#", 9, "wordToWord16#", 2, "word16ToWord#", 5));
        return result;
    }
    private Set<String> requiredPrimitives(String name) {
        require(names.contains(name));
        if (name.startsWith("alias"))
            return exactAliasPrimitives(name).keySet();
        var kind = name.contains("Word16") ? "Word16" : "Int16";
        var conversions = kind.equals("Int16") ? Set.of("intToInt16#", "int16ToInt#")
                                               : Set.of("wordToWord16#", "word16ToWord#", "int2Word#", "word2Int#");
        var result = new HashSet<>(Set.of("newByteArray#", "unsafeFreezeByteArray#", "read" + kind + "Array#",
            "write" + kind + "Array#", "index" + kind + "Array#", "plus" + kind + "#"));
        result.addAll(conversions);
        if (name.endsWith("ST"))
            result.addAll(Set.of("sub" + kind + "#", "times" + kind + "#"));
        return result;
    }

    private long checkedCalls(Map<String, Object> module, String name) {
        var evidence = new ArrayCoreEvidence(module, coreEntry(name));
        require(evidence.getPrimitiveCounts().keySet().containsAll(requiredPrimitives(name)),
            name + " missing required primitive");
        if (name.startsWith("alias"))
            require(evidence.getPrimitiveCounts().equals(exactAliasPrimitives(name)),
                name + " exact alias primitive counts changed");
        return evidence.loweredImmediateStateCalls();
    }
    private final Map<String, Object> unknownLiteralProof = unknownLiteralProof();
    private Map<String, Object> unknownLiteralProof() {
        var result = new LinkedHashMap<String, Object>();
        result.put("kind", "unknown");
        result.put("primReps", null);
        result.put("evaluated", false);
        return result;
    }

    private long literalCalls(Map<String, Object> module, String name) {
        return literalCalls(module, name, false);
    }
    private long literalCalls(Map<String, Object> module, String name, boolean unknownProof) {
        long value = Objects.requireNonNull(literalValues.get(name));
        boolean signed = name.equals("noinlineInt16Literal");
        var kind = signed ? "int16" : "word16";
        var payload = signed ? "Int16Rep" : "Word16Rep";
        var workerName = signed ? "literalInt16Worker" : "literalWord16Worker";
        var evidence = new ArrayCoreEvidence(module, coreEntry(name));
        var workers = evidence.getBindings().stream().filter(b -> coreEntry(workerName).equals(b.get("id"))).toList();
        require(workers.size() == 1, name + " required literal worker disappeared");
        var worker = single(workers);
        var root = evidence.getRoot();
        require(new HashSet<>(evidence.getBindings().stream().map(b -> b.get("id")).toList())
                    .equals(Set.of(root.get("id"), worker.get("id"))),
            name + " literal closure changed");
        for (var binding : List.of(root, worker)) {
            var reps = binding == root ? List.of("IntRep") : List.of("IntRep", payload);
            var expr = (List<?>) binding.get("expr");
            require(!expr.isEmpty() && "lam".equals(expr.getFirst()), name + " expected literal lambda");
            var formals = (List<Map<String, Object>>) expr.get(1);
            boolean valid = formals.size() == reps.size();
            for (int i = 0; valid && i < formals.size(); i++)
                valid = Objects.equals(formals.get(i).get("rep"),
                    Map.of("kind", "long", "primReps", List.of(reps.get(i)), "evaluated", true));
            require(valid, name + " literal formal shape changed");
            require(evidence.guestLambdas(expr).size() == 1, name + " extra literal guest lambda");
            require(evidence.globalReferences(expr).equals(binding == root ? List.of(worker.get("id")) : List.of()),
                name + " unexpected literal global call");
        }
        var rootExpr = (List<Object>) root.get("expr");
        var call = (List<?>) rootExpr.get(2);
        require(call.size() >= 6 && "app".equals(call.get(0)) && call.get(1) instanceof List<?> f
                && prefix(f).equals(List.of("var", worker.get("id")))
                && Objects.equals(call.get(3), List.of(false, false)) && Boolean.FALSE.equals(call.get(4))
                && Boolean.FALSE.equals(call.get(5)),
            name + " literal worker must be called directly");
        var args = (List<List<Object>>) call.get(2);
        require(args.size() == 2, name + " literal worker must be saturated");
        var formal = single((List<Map<String, Object>>) rootExpr.get(1));
        require(prefix(args.get(0)).equals(List.of("var", formal.get("id"))), name + " lost dynamic input");
        var expectedProof = unknownProof ? unknownLiteralProof
                                         : Map.of("kind", "long", "primReps", List.of(payload), "evaluated", true);
        require(take(args.get(1), 3).equals(List.of("lit", kind, Long.toString(value)))
                && Objects.equals(
                    metadata(args.get(1)) == null ? null : metadata(args.get(1)).get("rep"), expectedProof),
            name + " literal intrinsic proof changed");
        var expected = signed ? Map.of("+#", 1, "int16ToInt#", 1) : Map.of("+#", 1, "word16ToWord#", 1, "word2Int#", 1);
        require(evidence.getPrimitiveCounts().equals(expected), name + " literal worker primitives changed");
        return evidence.getBindings().stream().mapToInt(b -> evidence.guestLambdas(b.get("expr")).size()).sum();
    }
    private Map<String, Object> literalModule(List<String> paths, String name, boolean unknownProof) throws Exception {
        var module = merged(paths);
        assertEquals(2L, literalCalls(module, name));
        if (unknownProof) {
            // Keep the genuine export exact. Separately project the older erased
            // argument proof to exercise intrinsic refinement across a worker call.
            var rootExpr = (List<?>) new ArrayCoreEvidence(module, coreEntry(name)).getRoot().get("expr");
            var call = (List<?>) rootExpr.get(2);
            var literal = (List<Object>) ((List<?>) call.get(2)).get(1);
            metadata(literal).put("rep", unknownLiteralProof);
            assertEquals(2L, literalCalls(module, name, true));
        }
        return module;
    }
    @Test
    public void genuineCoreMustRetainRequiredAndExactAliasPrimitives() throws Exception {
        for (var paths : ((Map<String, List<String>>) manifest().get("stages")).values())
            for (var name : names) {
                var module = merged(paths);
                assertEquals(1L, checkedCalls(module, name));
                for (var primitive : requiredPrimitives(name)) {
                    var changed = (Map<String, Object>) thc.CoreCbdFixtures.snapshot(module);
                    var nodes = new ArrayCoreEvidence(changed, coreEntry(name))
                                    .nodes(changed)
                                    .stream()
                                    .filter(n -> prefix(n).equals(List.of("prim", primitive)))
                                    .toList();
                    assertTrue(!nodes.isEmpty(), name + "/" + primitive);
                    for (var node : nodes) node.set(1, "missingArrayPrimitive#");
                    assertThrows(
                        IllegalArgumentException.class, () -> checkedCalls(changed, name), name + "/" + primitive);
                }
                if (name.startsWith("alias"))
                    for (var replacement : List.of("+#", "readIntArray#")) {
                        var changed = (Map<String, Object>) thc.CoreCbdFixtures.snapshot(module);
                        var primitive = new ArrayCoreEvidence(changed, coreEntry(name))
                                            .nodes(CoreModules.reachable(changed, coreEntry(name)))
                                            .stream()
                                            .filter(n -> prefix(n).equals(List.of("prim", "*#")))
                                            .findFirst()
                                            .orElseThrow();
                        primitive.set(1, replacement);
                        assertThrows(IllegalArgumentException.class,
                            () -> checkedCalls(changed, name), name + "/" + replacement);
                    }
            }
    }
    @Test
    public void genuineLiteralProofAndDirectWorkerControlsRejectMutations() throws Exception {
        for (var stage : ((Map<String, List<String>>) manifest().get("stages")).entrySet())
            for (var name : literalValues.keySet()) {
                var module = merged(stage.getValue());
                assertEquals(2L, literalCalls(module, name));
                for (var mutation :
                    List.of("conditional", "arity", "dynamic", "kind", "value", "wrong-proof", "unknown-proof",
                        "wrong-formal", "extra-lambda", "worker", "flags", "extra-global", "primitives")) {
                    var changed = (Map<String, Object>) thc.CoreCbdFixtures.snapshot(module);
                    var evidence = new ArrayCoreEvidence(changed, coreEntry(name));
                    var rootExpr = (List<Object>) evidence.getRoot().get("expr");
                    var worker = single(evidence.getBindings()
                            .stream()
                            .filter(b -> !Objects.equals(b.get("id"), evidence.getRoot().get("id")))
                            .toList());
                    var workerExpr = (List<Object>) worker.get("expr");
                    var call = (List<Object>) rootExpr.get(2);
                    var args = (List<Object>) call.get(2);
                    var literal = (List<Object>) args.get(1);
                    switch (mutation) {
                        case "conditional" ->
                            rootExpr.set(2,
                                List.of("case", List.of("lit", "int", "0"), "v",
                                    List.of(Arrays.asList("default", null, List.of(), call))));
                        case "arity" -> args.remove(1);
                        case "dynamic" -> args.set(0, List.of("lit", "int", "0"));
                        case "kind" -> literal.set(1, name.equals("noinlineInt16Literal") ? "int" : "word");
                        case "value" -> literal.set(2, "1");
                        case "wrong-proof" ->
                            metadata(literal).put(
                                "rep", Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true));
                        case "unknown-proof" -> metadata(literal).put("rep", unknownLiteralProof);
                        case "wrong-formal" ->
                            ((Map<String, Object>) ((List<?>) workerExpr.get(1)).get(1))
                                .put("rep", Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true));
                        case "extra-lambda" -> workerExpr.set(2, List.of("lam", List.of(), workerExpr.get(2)));
                        case "worker" -> worker.put("name", "wrongWorker");
                        case "flags" -> call.set(3, List.of(true, false));
                        case "extra-global" -> workerExpr.set(2, List.of("var", evidence.getRoot().get("id")));
                        case "primitives" ->
                            evidence.nodes(workerExpr)
                                .stream()
                                .filter(n -> prefix(n).equals(List.of("prim", "+#")))
                                .findFirst()
                                .orElseThrow()
                                .set(1, "-#");
                    }
                    assertThrows(IllegalArgumentException.class,
                        () -> literalCalls(changed, name), stage.getKey() + "/" + name + "/" + mutation);
                }
            }
    }
    @Test
    public void nativePublicArraysAndByteAliasesWithInlining() throws Exception {
        nativeChecks(true);
    }
    @Test
    public void nativePublicArraysAndByteAliasesAcrossResidualCalls() throws Exception {
        nativeChecks(false);
    }
    @Test
    public void genuineNoinlineNarrowLiteralsAndUnknownProofControlsInCompiledCode() throws Exception {
        var manifest = manifest();
        var entries = new ArrayList<>(literalValues.keySet());
        assertEquals(entries, manifest.get("literalEntries"));
        assertEquals(literalInputs, manifest.get("literalInputs"));assertEquals(Set.of("pre","post"),((Map<?,?>)manifest.get("stages")).keySet());
        for (var kind : List.of("inputHashes", "artifactHashes"))
            for (var e : ((Map<String, String>) manifest.get(kind)).entrySet()) {
                var actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(e.getKey()))));
                assertEquals(e.getValue(), actual, "Stale literal fixture: " + e.getKey());
            }
        var rows = checkedRows(Files.readString(root.resolve("build/int16-arrays/literal-oracle.tsv")), true);
        assertEquals(14L, manifest.get("literalNativeRows"));
        for (var stage : ((Map<String, List<String>>) manifest.get("stages")).entrySet())
            for (var name : entries) {
                var cases = rows.get(name);
                long expectedCalls = literalCalls(merged(stage.getValue()), name);
                assertEquals(((List<Number>) manifest.get("literalInputs")).stream().map(Number::longValue).toList(),
                    cases.stream().map(Row::input).toList());
                for (var row : cases)
                    assertEquals(row.input + (name.equals(entries.get(0)) ? -32768L : 65535L), row.answer);
                for (boolean unknownProof : List.of(false, true))
                    for (var backend : List.of("ast", "bytecode"))
                        for (boolean inlining : List.of(false, true)) try (var context = context(inlining)) {
                                context.initialize("thc");
                                context.enter();
                                try {
                                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                                    var linked = CoreModules.reachable(
                                        literalModule(stage.getValue(), name, unknownProof), coreEntry(name));
                                    var bindings = (List<Map<String, Object>>) linked.get("bindings");
                                    var program = program(language, changed(linked, "instrument", true), backend);
                                    var entry = program.entryTarget((String) single(
                                        bindings.stream().filter(b -> coreEntry(name).equals(b.get("id"))).toList())
                                            .get("id"));
                                    for (var row : cases) {
                                        assertEquals(row.answer, Calls.target(entry, new Object[] {0L, row.input}));
                                        released(language);
                                    }
                                    var targets = activeTargets(entry);
                                    assertEquals((int) expectedCalls, targets.size(),
                                        stage.getKey() + "/" + backend + "/" + name + " entry and opaque worker");
                                    var labels = new HashSet<String>();
                                    for (var binding : bindings) labels.add(lambdaLabel((List<?>) binding.get("expr")));
                                    assertEquals(labels,
                                        new HashSet<>(targets.stream().map(t -> t.getRootNode().getName()).toList()),
                                        stage.getKey() + "/" + backend + "/" + name + " guest labels");
                                    for (var target : targets) compile(target);
                                    for (var row : cases) {
                                        var label = stage.getKey() + "/" + backend + "/" + name + "/" + row.input
                                            + "/inlining=" + inlining + "/unknownProof=" + unknownProof;
                                        long before = count(program, "compiledEntries");
                                        assertEquals(
                                            row.answer, Calls.target(entry, new Object[] {0L, row.input}), label);
                                        assertEquals(expectedCalls, count(program, "compiledEntries") - before,
                                            label + " exact compiled entries");
                                        var active = activeTargets(entry);
                                        assertEquals(targets.size(), active.size(), label + " active target count");
                                        assertTrue(
                                            active.stream().allMatch(t -> targets.stream().anyMatch(old -> old == t)),
                                            label + " active identities");
                                        for (var target : targets) valid(target, label);
                                        released(language);
                                    }
                                    for (var counter : List.of("unsupportedTraps", "blackholes"))
                                        assertEquals(0L, count(program, counter), counter);
                                } finally {
                                    context.leave();
                                }
                            }
            }
    }
    private void nativeChecks(boolean inlining) throws Exception {
        var manifest = manifest();
        assertEquals(names, manifest.get("entries"));
        assertEquals(inputs, manifest.get("inputs"));
        assertEquals((long) names.size() * inputs.size(), manifest.get("nativeRows"));
        assertEquals(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? "little" : "big", manifest.get("byteOrder"));
        assertEquals(64, ((Number) manifest.get("wordBits")).intValue());
        assertEquals(16, ((Number) manifest.get("elementBits")).intValue());
        for (var kind : List.of("inputHashes", "artifactHashes"))
            for (var e : ((Map<String, String>) manifest.get(kind)).entrySet()) {
                var actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(e.getKey()))));
                assertEquals(e.getValue(), actual,
                    "Stale 16-bit-array fixture: " + e.getKey() + "; regenerate the 16-bit-array fixtures");
            }
        var rows = checkedRows(Files.readString(root.resolve("build/int16-arrays/oracle.tsv")));
        var stages = (Map<String, List<String>>) manifest.get("stages");
        assertEquals(Set.of("pre", "post"), stages.keySet());
        for (var stage : stages.entrySet()) {
            var module = merged(stage.getValue());
            for (var name : names) {
                var cases = rows.get(name);
                long expectedCalls = checkedCalls(module, name);
                for (var backend : List.of("ast", "bytecode")) try (var context = context(inlining)) {
                        context.initialize("thc");
                        context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            var linked = CoreModules.reachable(module, coreEntry(name));
                            var bindings = (List<Map<String, Object>>) linked.get("bindings");
                            var p = program(language, changed(linked, "instrument", true), backend);
                            var entry = p.entryTarget(
                                (String) single(bindings.stream().filter(b -> coreEntry(name).equals(b.get("id"))).toList())
                                    .get("id"));
                            // The checked immediate runRW State# lambda is beta-reduced.
                            var expectedLabels = new HashSet<String>();
                            for (var binding : bindings) expectedLabels.add(lambdaLabel((List<?>) binding.get("expr")));
                            List<RootCallTarget> targets = List.of();
                            checkCases(cases, stage.getKey(), backend, name, inlining, p, entry, expectedCalls, targets,
                                false, language);
                            targets = activeTargets(entry);
                            assertEquals((int) expectedCalls, targets.size(),
                                stage.getKey() + "/" + backend + "/" + name + " active guest roots");
                            assertEquals(expectedLabels,
                                new HashSet<>(targets.stream().map(t -> t.getRootNode().getName()).toList()),
                                stage.getKey() + "/" + backend + "/" + name + " guest root labels");
                            for (var target : targets) compile(target);
                            long allocations = language.getHandoffState().get().getResults().getAllocations();
                            checkCases(cases, stage.getKey(), backend, name, inlining, p, entry, expectedCalls, targets,
                                true, language);
                            assertEquals(allocations, language.getHandoffState().get().getResults().getAllocations(),
                                "Pooled results reused");
                            for (var counter : List.of("unsupportedTraps", "blackholes"))
                                assertEquals(0L, count(p, counter), counter);
                        } finally {
                            context.leave();
                        }
                    }
            }
        }
    }
    private String lambdaLabel(List<?> expression) {
        assertEquals("lam", expression.get(0));
        var formals = (List<Map<String, Object>>) expression.get(1);
        return "lambda " + String.join(", ", formals.stream().map(f -> String.valueOf(f.get("name"))).toList());
    }
    private void checkCases(List<Row> cases, String stage, String backend, String name, boolean inlining,
        ExecutableProgram program, RootCallTarget entry, long expectedCalls, List<RootCallTarget> targets,
        boolean compiled, Language language) throws Exception {
        for (var row : cases) {
            var label = stage + "/" + backend + "/" + name + "/" + row.input + "/inlining=" + inlining;
            long before = count(program, "compiledEntries");
            assertEquals(row.answer, Calls.target(entry, new Object[] {0L, row.input}), label);
            if (compiled) {
                assertEquals(
                    expectedCalls, count(program, "compiledEntries") - before, label + " exact compiled entries");
                var active = activeTargets(entry);
                assertEquals(targets.size(), active.size(), label + " active target count");
                assertTrue(active.stream().allMatch(t -> targets.stream().anyMatch(old -> old == t)),
                    label + " active target identities");
                for (var target : targets) valid(target, label);
            }
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
        return operation.getPrimitive().contains("Word16") ? "aliasWord16Bytes" : "aliasInt16Bytes";
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
    public void loweredAliasesExecuteWhileCarrierStateShapeAndSaturationGuardsRemain() throws Exception {
        var paths = paths();
        for (var backend : List.of("ast", "bytecode")) try (var context = context(true)) {
                context.initialize("thc");
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (var operation : operations)
                        for (int mutation = 0; mutation <= 18; mutation++)
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
                                    case 3 -> metadata.put("rep", wrong("float", "FloatRep"));
                                    case 4 -> flags.set(0, true);
                                    case 5 -> rep(args.get(0)).put("kind", "unknown");
                                    case 6 -> rep(args.get(1)).put("primReps", List.of("Int64Rep"));
                                    case 7 -> {
                                        if (List.of(ByteArrayOp.WRITE_INT16, ByteArrayOp.WRITE_WORD16)
                                                .contains(operation))
                                            metadata(args.get(2)).put("rep", wrong("long", "IntRep"));
                                        else
                                            metadata.put("rep", wrong("long", "IntRep"));
                                    }
                                    case 8 -> {
                                        if (List.of(ByteArrayOp.READ_INT16, ByteArrayOp.READ_WORD16)
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
                                        if (List.of(ByteArrayOp.READ_INT16, ByteArrayOp.READ_WORD16)
                                                .contains(operation)) {
                                            var proof = (Map<String, Object>) metadata.get("rep");
                                            ((List<Object>) proof.get("components")).set(1, wrong("float", "FloatRep"));
                                            proof.put("primReps", List.of("FloatRep"));
                                        } else
                                            flags.set(1, true);
                                    }
                                    default -> {
                                        var rep = switch (mutation) {
                                            case 10 ->
                                                operation.getPrimitive().contains("Word16") ? "Int16Rep" : "Word16Rep";
                                            case 11 -> "Int64Rep";
                                            case 12 -> "Word64Rep";
                                            case 13 -> "WordRep";
                                            case 14 -> "Int8Rep";
                                            case 15 -> "Word8Rep";
                                            case 16 -> "Int32Rep";
                                            case 17 -> "Word32Rep";
                                            default ->
                                                operation.getPrimitive().contains("Word16") ? "Word16Rep" : "Int16Rep";
                                        };
                                        var payload = wrong(mutation == 18 ? "unknown" : "long", rep);
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
                                // Narrow scalar aliases share Int; machine offsets stay Long. A unilateral tuple change
                                // still conflicts with the untouched case binder's ABI.
                                boolean sameCarrier = mutation == 6
                                    || (!operation.getTuple()
                                        && (mutation == 10 || (mutation >= 14 && mutation <= 17)));
                                if (sameCarrier) {
                                    var name = owner(operation);
                                    var p = program(
                                        language, changed(module, "diagnosticUnsupported", diagnostic), backend);
                                    for (long seed : List.of(5L, -1L, Long.MIN_VALUE)) {
                                        assertEquals(model(name, seed),
                                            Calls.target(p.hostEntryTarget(1),
                                                new Object[] {p.entryValue(coreEntry(name)), new Object[] {seed}}),
                                            backend + "/" + operation + "/mutation" + mutation + "/" + diagnostic + "/"
                                                + seed);
                                        released(language);
                                    }
                                } else if (diagnostic && mutation == 18 && operation.getTuple()) {
                                    // Unknown tuple leaves retain the existing diagnostic frontier.
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
                        for (long index : List.of(Long.MIN_VALUE, -1L, 2L, 1L << 32, 1L << 62, Long.MAX_VALUE)) {
                            var name = owner(operation);
                            var module = CoreModules.reachable(merged(paths), coreEntry(name));
                            var app = application(module, operation);
                            var args = (List<Object>) app.get(2);
                            args.set(1, Arrays.asList("lit", "int", Long.toString(index), metadata(args.get(1))));
                            var p = program(language, module, backend);
                            var function = context.asValue(new EntryValue(p, coreEntry(name), 1));
                            var failure = assertThrows(PolyglotException.class, () -> function.execute(5L));
                            var guard = index < 0 || index > Long.MAX_VALUE / 2 ? "element" : "range";
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
    private static List<?> take(List<?> list, int count) {
        return list.subList(0, Math.min(count, list.size()));
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

    public void checkRetiredBoundaryBeforeFirstTwoRootCall() throws Exception {
        var manifest = manifest();
        for (var kind : List.of("inputHashes", "artifactHashes"))
            for (var e : ((Map<String, String>) manifest.get(kind)).entrySet()) {
                var actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(e.getKey()))));
                assertEquals(e.getValue(), actual, "Stale boundary-control fixture: " + e.getKey());
            }
        var paths = ((Map<String, List<String>>) manifest.get("stages")).get("post");
        // A genuine opaque worker retains two executable roots after runRW
        // state applications are beta-reduced by the loaders.
        var name = "noinlineInt16Literal";
        var module = merged(paths);
        assertEquals(2L, literalCalls(module, name), "Genuine public entry and opaque literal worker");
        var evidence = new ArrayCoreEvidence(module, coreEntry(name));
        var labels = new HashSet<String>();
        for (var binding : evidence.getBindings())
            for (var expression : evidence.guestLambdas(binding.get("expr"))) {
                var formals = (List<Map<String, Object>>) expression.get(1);
                labels.add(
                    "lambda " + String.join(", ", formals.stream().map(f -> String.valueOf(f.get("name"))).toList()));
            }
        var rows = checkedRows(Files.readString(root.resolve("build/int16-arrays/literal-oracle.tsv")), true).get(name);
        var first = rows.getFirst();
        long input = first.input, answer = first.answer;
        assertEquals(Long.MIN_VALUE, input);
        for (var backendName : List.of("ast", "bytecode")) try (var context = context(false)) {
                context.initialize("thc");
                context.enter();
                try {
                    var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                    var rawCompile = type.getMethod("compile", boolean.class);
                    var callCount = type.getMethod("getCallCount");
                    var runtime = Truffle.getRuntime();
                    var repair = runtime.getClass().getMethod("bypassedInstalledCode", type);
                    var jvmci = Class.forName("jdk.vm.ci.runtime.JVMCI").getMethod("getRuntime").invoke(null);
                    var backend =
                        Class.forName("jdk.vm.ci.runtime.JVMCIRuntime").getMethod("getHostJVMCIBackend").invoke(jvmci);
                    var metaAccess =
                        Class.forName("jdk.vm.ci.runtime.JVMCIBackend").getMethod("getMetaAccess").invoke(backend);
                    var method = type.getDeclaredMethod("callBoundary", Object[].class);
                    var boundary = Class.forName("jdk.vm.ci.meta.MetaAccessProvider")
                                       .getMethod("lookupJavaMethod", java.lang.reflect.Executable.class)
                                       .invoke(metaAccess, method);
                    var reprofile = Class.forName("jdk.vm.ci.meta.ResolvedJavaMethod").getMethod("reprofile");
                    var hasCode =
                        Class.forName("jdk.vm.ci.hotspot.HotSpotResolvedJavaMethod").getMethod("hasCompiledCode");
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var program = program(
                        language, changed(CoreModules.reachable(module, coreEntry(name)), "instrument", true), backendName);
                    var entry = program.entryTarget(coreEntry(name));
                    for (var row : rows) assertEquals(row.answer, Calls.target(entry, new Object[] {0L, row.input}));
                    var targets = activeTargets(entry);
                    assertEquals(2, targets.size(), backendName + " active public and opaque worker roots");
                    assertEquals(labels, new HashSet<>(targets.stream().map(t -> t.getRootNode().getName()).toList()),
                        backendName + " source-derived root labels");
                    for (var target : targets) compile(target);
                    try {
                        // The old helper leaves valid guest nmethods behind a retired
                        // stub. The outer entry interprets and repairs the boundary;
                        // its opaque worker call enters compiled code. This loses one entry,
                        // not both, so it does not explain the historical delta-zero failure.
                        reprofile.invoke(boundary);
                        assertEquals(false, hasCode.invoke(boundary));
                        for (var target : targets) {
                            rawCompile.invoke(target, true);
                            valid(target, backendName + " raw compilation");
                        }
                        assertEquals(
                            false, hasCode.invoke(boundary), backendName + " raw compilation leaves boundary retired");
                        long before = count(program, "compiledEntries");
                        var callsBefore = calls(targets, callCount);
                        assertEquals(answer, Calls.target(entry, new Object[] {0L, input}));
                        System.out.println("INT16_BOUNDARY " + backendName
                            + " negative delta=" + (count(program, "compiledEntries") - before)
                            + " calls=" + callsBefore + "->" + calls(targets, callCount));
                        assertEquals(1L, count(program, "compiledEntries") - before,
                            backendName + " retired boundary bypasses only the public root");
                        var expectedCalls = new ArrayList<Integer>();
                        for (int index = 0; index < targets.size(); index++)
                            expectedCalls.add(callsBefore.get(index) + (targets.get(index) == entry ? 1 : 0));
                        assertEquals(expectedCalls, calls(targets, callCount),
                            backendName + " only the public root interpreted once");
                        assertEquals(
                            true, hasCode.invoke(boundary), backendName + " public entry repaired the shared stub");
                        unchanged(targets, entry, backendName, language);
                        // Positive control installs the prerequisite without any guest
                        // call, then demands both exact entries on the first invocation.
                        reprofile.invoke(boundary);
                        assertEquals(false, hasCode.invoke(boundary));
                        long beforeSetup = count(program, "compiledEntries");
                        var callsBeforeSetup = calls(targets, callCount);
                        for (var target : targets) compile(target);
                        assertEquals(true, hasCode.invoke(boundary));
                        assertEquals(beforeSetup, count(program, "compiledEntries"),
                            backendName + " setup does not execute compiled guest code");
                        assertEquals(callsBeforeSetup, calls(targets, callCount),
                            backendName + " setup does not settle interpreted calls");
                        unchanged(targets, entry, backendName, language);
                        assertEquals(answer, Calls.target(entry, new Object[] {0L, input}));
                        System.out.println("INT16_BOUNDARY " + backendName
                            + " positive delta=" + (count(program, "compiledEntries") - beforeSetup)
                            + " calls=" + callsBeforeSetup + "->" + calls(targets, callCount));
                        assertEquals(2L, count(program, "compiledEntries") - beforeSetup,
                            backendName + " first repaired call enters both roots");
                        assertEquals(callsBeforeSetup, calls(targets, callCount),
                            backendName + " repaired call never enters the interpreter");
                        unchanged(targets, entry, backendName, language);
                    } finally {
                        repair.invoke(runtime, entry);
                    }
                } finally {
                    context.leave();
                }
            }
    }
    private List<Integer> calls(List<RootCallTarget> targets, java.lang.reflect.Method callCount) throws Exception {
        var calls = new ArrayList<Integer>();
        for (var target : targets) calls.add((Integer) callCount.invoke(target));
        return calls;
    }
    private void unchanged(List<RootCallTarget> targets, RootCallTarget entry, String backendName, Language language)
        throws Exception {
        assertEquals(targets, activeTargets(entry), backendName + " active target identities");
        for (var target : targets) valid(target, backendName + " retained " + target.getRootNode().getName());
        released(language);
    }
}
