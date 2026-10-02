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
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class Int32ArrayNativeTest {
    private static String coreEntry(String name) {
        return "main:" + (name.startsWith("unboxed") ? "Unboxed32Arrays" : "Int32ArrayAudit") + "." + name;
    }
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));

    private final List<String> names = List.of("unboxedInt32Accum", "unboxedInt32ST", "unboxedWord32Accum",
        "unboxedWord32ST", "aliasInt32Bytes", "aliasWord32Bytes");
    private final List<ByteArrayOp> operations = List.of(ByteArrayOp.READ_INT32, ByteArrayOp.WRITE_INT32,
        ByteArrayOp.INDEX_INT32, ByteArrayOp.READ_WORD32, ByteArrayOp.WRITE_WORD32, ByteArrayOp.INDEX_WORD32);
    private Map<String, Object> manifest() throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(root.resolve("build/int32-arrays/manifest.json")));
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
    private void compile(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), "initial installation");
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

    private final List<Long> inputs = inputs();
    private List<Long> inputs() {
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
    private record Row(String name, long input, long answer) {}
    private List<Row> expectedRows(ByteOrder order) {
        var rows = new ArrayList<Row>();
        for (var name : names)
            for (long input : inputs) rows.add(new Row(name, input, model(name, input, order)));
        return rows;
    }
    private List<Row> parseRows(String text) {
        var lines = new ArrayList<>(Arrays.asList(text.split("\\r\\n|\\n|\\r", -1)));
        if (!lines.isEmpty() && lines.getLast().isEmpty())
            lines.removeLast();
        var rows = new ArrayList<Row>();
        for (var line : lines) {
            var fields = line.split("\t", -1);
            require(fields.length == 3, "Int32-array oracle requires name/input/result");
            rows.add(new Row(fields[0], Long.parseLong(fields[1]), Long.parseLong(fields[2])));
        }
        return rows;
    }
    private List<Row> checkedRows(String text) {
        return checkedRows(text, ByteOrder.nativeOrder());
    }
    private List<Row> checkedRows(String text, ByteOrder order) {
        var rows = parseRows(text);
        require(rows.equals(expectedRows(order)), "Int32-array native/model mismatch or incomplete/reordered corpus");
        return rows;
    }
    private final List<String> literalNames = List.of("noinlineInt32Literal", "noinlineWord32Literal");
    private final List<Long> literalInputs =
        List.of(Long.MIN_VALUE, -2147483648L, -1L, 0L, 1L, 2147483647L, Long.MAX_VALUE);
    private final List<Row> literalRows = literalRows();
    private List<Row> literalRows() {
        var rows = new ArrayList<Row>();
        for (var name : literalNames)
            for (long input : literalInputs)
                rows.add(new Row(name, input, input + (name.equals(literalNames.get(0)) ? -2147483648L : 4294967295L)));
        return rows;
    }
    private List<Row> checkedLiteralRows(String text) {
        var rows = parseRows(text);
        require(rows.equals(literalRows), "Noinline narrow-literal corpus mismatch");
        return rows;
    }
    private Set<String> required(String name) {
        var kind = name.contains("Word32") ? "Word32" : "Int32";
        var conversions = kind.equals("Int32") ? Set.of("intToInt32#", "int32ToInt#")
                                               : Set.of("wordToWord32#", "word32ToWord#", "int2Word#", "word2Int#");
        var result = new HashSet<>(Set.of("newByteArray#", "unsafeFreezeByteArray#", "read" + kind + "Array#",
            "write" + kind + "Array#", "index" + kind + "Array#"));
        result.addAll(conversions);
        if (name.startsWith("alias"))
            result.addAll(Set.of("writeWord8Array#", "indexWord8Array#", "word8ToWord#", "wordToWord8#"));
        return result;
    }
    private void checkedCore(String name, ArrayCoreEvidence evidence) {
        require(names.contains(name), "Unknown Int32-array root: " + name);
        require(evidence.getPrimitiveCounts().keySet().containsAll(required(name)),
            "Missing Int32-array primitive: " + name);
    }
    private final Map<String, Object> unknownLiteralProof = unknownLiteralProof();
    private Map<String, Object> unknownLiteralProof() {
        var result = new LinkedHashMap<String, Object>();
        result.put("kind", "unknown");
        result.put("primReps", null);
        result.put("evaluated", false);
        return result;
    }
    private List<List<Object>> literalArguments(String name, ArrayCoreEvidence evidence) {
        require(literalNames.contains(name), "Unknown narrow-literal entry: " + name);
        var kind = name.equals(literalNames.get(0)) ? "Int32" : "Word32";
        var literal = List.of("lit", kind.toLowerCase(Locale.ROOT),
            kind.equals("Int32") ? "-2147483648" : "4294967295");
        var proof = Map.of("kind", "long", "primReps", List.of(kind + "Rep"), "evaluated", true);
        var workerId = coreEntry("literal" + kind + "Worker");
        var worker = evidence.getAllBindings().get(workerId);
        require(worker != null, name + ": missing opaque worker");
        var expression = (List<?>) worker.get("expr");
        require("lam".equals(expression.getFirst()), name + ": missing worker formals");
        var formals = (List<Map<String, Object>>) expression.get(1);
        require(formals.size() == 2 && Objects.equals(formals.get(0).get("rep"),
                    Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true))
                && Objects.equals(formals.get(1).get("rep"), proof),
            name + ": changed worker parameter ABI");
        var arguments = new ArrayList<List<Object>>();
        for (var binding : evidence.getBindings())
            for (var call : applications(binding.get("expr")))
                if (call.get(1) instanceof List<?> callee
                    && prefix(callee).equals(List.of("var", workerId))) {
                    var args = (List<?>) call.get(2);
                    require(args.size() == 2 && args.get(1) instanceof List<?>, name + ": missing narrow argument");
                    var argument = (List<Object>) args.get(1);
                    require(take(argument, 3).equals(literal), name + ": narrow literal changed");
                    require(metadata(argument) != null && Objects.equals(metadata(argument).get("rep"), proof),
                        name + ": narrow literal proof changed");
                    arguments.add(argument);
                }
        require(!arguments.isEmpty(), name + ": missing opaque worker call");
        return arguments;
    }
    private Map<String, Object> literalModule(List<String> paths, String name, boolean unknownProof) throws Exception {
        var module = merged(paths);
        var arguments = literalArguments(name, new ArrayCoreEvidence(module, coreEntry(name)));
        if (unknownProof)
            // Keep the genuine export exact. Separately project the older erased
            // argument proof to exercise intrinsic refinement across a worker call.
            for (var argument : arguments) metadata(argument).put("rep", unknownLiteralProof);
        return module;
    }
    private long decode(long value, boolean unsigned) {
        long bits = value & 0xffff_ffffL;
        return !unsigned && bits >= 0x8000_0000L ? bits - 0x1_0000_0000L : bits;
    }
    private long model(String name, long seed) {
        return model(name, seed, ByteOrder.nativeOrder());
    }
    private long model(String name, long seed, ByteOrder order) {
        require(names.contains(name), "Unknown Int32-array entry: " + name);
        boolean unsigned = name.contains("Word32");
        long bits = seed & 0xffff_ffffL;
        if (name.endsWith("Accum"))
            return 7 * decode(bits + 1, unsigned) + 11 * decode(bits + 5, unsigned) + 13 * decode(2 * bits, unsigned);
        if (name.endsWith("ST"))
            return 7 * decode(bits, unsigned) + 11 * decode(bits + 7, unsigned) + 13 * decode(2 * bits + 21, unsigned);
        if (!List.of("aliasInt32Bytes", "aliasWord32Bytes").contains(name))
            throw new IllegalStateException("Check failed.");
        var bytes = new long[8];
        for (int offset = 0; offset < 8; offset++) {
            long value = offset < 4 ? bits : bits ^ 0x55aa55aaL;
            int position = offset % 4, shift = (order == ByteOrder.LITTLE_ENDIAN ? position : 3 - position) * 8;
            bytes[offset] = (value >>> shift) & 255;
        }
        bytes[3] = (seed + 101) & 255;
        bytes[4] = (seed + 37) & 255;
        return 3 * decode(bits, unsigned) + 16 * element(bytes, 0, order, unsigned)
            + 20 * element(bytes, 4, order, unsigned) + 17 * bytes[0] + 19 * bytes[3] + 23 * bytes[4] + 29 * bytes[7];
    }
    private long element(long[] bytes, int offset, ByteOrder order, boolean unsigned) {
        long value = 0;
        for (int b = 0; b <= 3; b++) {
            int shift = (order == ByteOrder.LITTLE_ENDIAN ? b : 3 - b) * 8;
            value |= bytes[offset + b] << shift;
        }
        return decode(value, unsigned);
    }
    private long widen(long bits, boolean unsigned) {
        return unsigned ? bits & 0xffff_ffffL : (long) (int) bits;
    }
    @Test
    public void independentModelsCoverNarrowCellUpdatesAndBothByteOrders() {
        assertEquals(397, inputs.size());
        assertEquals(inputs.stream().sorted().distinct().toList(), inputs);
        for (int bit = 0; bit <= 63; bit++)
            for (long delta = -1; delta <= 1; delta++)
                for (long sign : List.of(-1L, 1L)) assertTrue(inputs.contains(sign * ((1L << bit) + delta)));
        for (boolean unsigned : List.of(false, true)) {
            var kind = unsigned ? "Word32" : "Int32";
            for (long raw : inputs) {
                long bits = raw & 0xffff_ffffL;
                var accum = new long[8];
                var st = new long[8];
                Arrays.fill(accum, bits);
                Arrays.fill(st, bits);
                int[] indices = {-3, 0, -3, 4};
                long[] values = {3, 5, -2, bits};
                for (int i = 0; i < indices.length; i++)
                    accum[indices[i] + 3] = (accum[indices[i] + 3] + values[i]) & 0xffff_ffffL;
                st[3] = (st[0] + 7) & 0xffff_ffffL;
                st[7] = (3 * st[3] - bits) & 0xffff_ffffL;
                for (var suffix : List.of("Accum", "ST")) {
                    var cells = suffix.equals("Accum") ? accum : st;
                    assertEquals(
                        7 * widen(cells[0], unsigned) + 11 * widen(cells[3], unsigned) + 13 * widen(cells[7], unsigned),
                        model("unboxed" + kind + suffix, raw));
                }
                for (var order : List.of(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
                    boolean little = order == ByteOrder.LITTLE_ENDIAN;
                    int shiftA = little ? 24 : 0, shiftB = little ? 0 : 24;
                    long a = (bits & ~(255L << shiftA)) | (((raw + 101) & 255) << shiftA),
                         b = ((bits ^ 0x55aa55aaL) & ~(255L << shiftB)) | (((raw + 37) & 255) << shiftB),
                         first = little ? a & 255 : a >>> 24, last = little ? b >>> 24 : b & 255;
                    assertEquals(3 * widen(bits, unsigned) + 16 * widen(a, unsigned) + 20 * widen(b, unsigned)
                            + 17 * first + 19 * ((raw + 101) & 255) + 23 * ((raw + 37) & 255) + 29 * last,
                        model("alias" + kind + "Bytes", raw, order), kind + "/" + raw + "/" + order);
                }
            }
        }
        for (var name : names)
            for (long x : List.of(0L, 1L, 0x7fffffffL, 0x80000000L, 0xffffffffL))
                for (var order : List.of(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
                    assertEquals(model(name, x, order), model(name, x + 0x1_0000_0000L, order));
                    assertEquals(model(name, x, order), model(name, x - 0x1_0000_0000L, order));
                }
        for (var pair : List.of(List.of("unboxedInt32Accum", "unboxedWord32Accum"),
                 List.of("unboxedInt32ST", "unboxedWord32ST"), List.of("aliasInt32Bytes", "aliasWord32Bytes")))
            for (var order : List.of(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN))
                assertNotEquals(model(pair.get(0), 0x80000000L, order), model(pair.get(1), 0x80000000L, order));
        assertNotEquals(
            model("aliasInt32Bytes", 0, ByteOrder.LITTLE_ENDIAN), model("aliasInt32Bytes", 0, ByteOrder.BIG_ENDIAN));
        assertThrows(IllegalArgumentException.class, () -> model("unknownAccum", 0));
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
            assertEquals(2382, rows.size());
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
                "unboxedInt32Accum\t9223372036854775808\t0\n", "unboxedInt32Accum\t0\tnot-an-int\n", valid + "\n");
            for (int i = 0; i < bad.size(); i++) {
                var corrupt = bad.get(i);
                assertThrows(
                    IllegalArgumentException.class, () -> checkedRows(corrupt, order), order + "/mutation" + i);
            }
        }

        assertEquals(literalRows, checkedLiteralRows(text(literalRows)));
        var duplicate = new ArrayList<>(literalRows);
        duplicate.add(literalRows.getFirst());
        var first = literalRows.getFirst();
        for (var corrupt : List.of(List.<Row>of(), literalRows.subList(1, literalRows.size()), duplicate,
                 literalRows.reversed(), firstChanged(literalRows, new Row(first.name, first.input, first.answer + 1)),
                 firstChanged(literalRows, new Row(first.name, 0, first.answer))))
            assertThrows(IllegalArgumentException.class, () -> checkedLiteralRows(text(corrupt)));
    }
    @Test
    public void genuineNoinlineNarrowLiteralsAndUnknownProofControlsInCompiledCode() throws Exception {
        var manifest = manifest();
        var entries = literalNames;
        assertEquals(entries, manifest.get("literalEntries"));
        assertEquals(
            literalInputs, ((List<Number>) manifest.get("literalInputs")).stream().map(Number::longValue).toList());
        for (var kind : List.of("inputHashes", "artifactHashes"))
            for (var e : ((Map<String, String>) manifest.get(kind)).entrySet()) {
                var actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(e.getKey()))));
                assertEquals(e.getValue(), actual, "Stale literal fixture: " + e.getKey());
            }
        var rows = new LinkedHashMap<String, List<Row>>();
        for (var row : checkedLiteralRows(Files.readString(root.resolve("build/int32-arrays/literal-oracle.tsv"))))
            rows.computeIfAbsent(row.name, k -> new ArrayList<>()).add(row);
        assertEquals(new HashSet<>(entries), rows.keySet());
        assertEquals(14, rows.values().stream().mapToInt(List::size).sum());
        assertEquals(14, ((Number) manifest.get("literalNativeRows")).intValue());
        for (var stage : ((Map<String, List<String>>) manifest.get("stages")).entrySet())
            for (var name : entries) {
                var cases = rows.get(name);
                assertEquals(((List<Number>) manifest.get("literalInputs")).stream().map(Number::longValue).toList(),
                    cases.stream().map(Row::input).toList());
                for (var row : cases)
                    assertEquals(row.input + (name.equals(entries.get(0)) ? -2147483648L : 4294967295L), row.answer);
                for (boolean unknownProof : List.of(false, true))
                    for (var backend : List.of("ast", "bytecode"))
                        for (boolean inlining : List.of(false, true)) try (var context = context(inlining)) {
                                context.initialize("thc");
                                context.enter();
                                try {
                                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                                    var linked = CoreModules.reachable(
                                        literalModule(stage.getValue(), name, unknownProof), coreEntry(name));
                                    var program = program(language, changed(linked, "instrument", true), backend);
                                    var entry = program.entryTarget(coreEntry(name));
                                    var label = name + "/inlining=" + inlining + "/unknownProof=" + unknownProof;
                                    checkCases(cases, stage.getKey(), backend, label, program, entry, false, language);
                                    for (var target : activeTargets(entry)) compile(target);
                                    checkCases(cases, stage.getKey(), backend, label, program, entry, true, language);
                                    for (var counter : List.of("unsupportedTraps", "blackholes"))
                                        assertEquals(0L, count(program, counter), counter);
                                } finally {
                                    context.leave();
                                }
                            }
            }
    }
    @Test
    public void nativePublicArraysAndByteAliases() throws Exception {
        var manifest = manifest();
        assertEquals(names, manifest.get("entries"));
        assertEquals(inputs, ((List<Number>) manifest.get("inputs")).stream().map(Number::longValue).toList());
        assertEquals(names.size() * inputs.size(), ((Number) manifest.get("nativeRows")).intValue());
        assertEquals(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? "little" : "big", manifest.get("byteOrder"));
        assertEquals(64, ((Number) manifest.get("wordBits")).intValue());
        assertEquals(32, ((Number) manifest.get("elementBits")).intValue());
        for (var kind : List.of("inputHashes", "artifactHashes"))
            for (var e : ((Map<String, String>) manifest.get(kind)).entrySet()) {
                var actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(e.getKey()))));
                assertEquals(e.getValue(), actual,
                    "Stale 32-bit-array fixture: " + e.getKey() + "; regenerate the 32-bit-array fixtures");
            }
        var rows = new LinkedHashMap<String, List<Row>>();
        for (var row : checkedRows(Files.readString(root.resolve("build/int32-arrays/oracle.tsv"))))
            rows.computeIfAbsent(row.name, k -> new ArrayList<>()).add(row);
        assertEquals(new HashSet<>(names), rows.keySet());
        assertEquals(
            ((Number) manifest.get("nativeRows")).intValue(), rows.values().stream().mapToInt(List::size).sum());
        var stages = (Map<String, List<String>>) manifest.get("stages");
        assertEquals(Set.of("pre", "post"), stages.keySet());
        for (var stage : stages.entrySet()) {
            var module = merged(stage.getValue());
            for (var name : names) {
                var evidence = new ArrayCoreEvidence(module, coreEntry(name));
                checkedCore(name, evidence);
                var cases = rows.get(name);
                assertEquals(cases.size(), new HashSet<>(cases.stream().map(Row::input).toList()).size());
                assertEquals(
                    new HashSet<>(((List<Number>) manifest.get("inputs")).stream().map(Number::longValue).toList()),
                    new HashSet<>(cases.stream().map(Row::input).toList()));
                for (var row : cases)
                    assertEquals(model(name, row.input), row.answer, "Native " + name + "(" + row.input + ")");
                for (var backend : List.of("ast", "bytecode")) try (var context = context(true)) {
                        context.initialize("thc");
                        context.enter();
                        try {
                            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                            var linked = CoreModules.reachable(module, coreEntry(name));
                            var p = program(language, changed(linked, "instrument", true), backend);
                            var entry = p.entryTarget(coreEntry(name));
                            checkCases(cases, stage.getKey(), backend, name, p, entry, false, language);
                            for (var target : activeTargets(entry)) compile(target);
                            checkCases(cases, stage.getKey(), backend, name, p, entry, true, language);
                            for (var counter : List.of("unsupportedTraps", "blackholes"))
                                assertEquals(0L, count(p, counter), counter);
                        } finally {
                            context.leave();
                        }
                    }
            }
        }
    }
    private void checkCases(List<Row> cases, String stage, String backend, String name,
        ExecutableProgram program, RootCallTarget entry, boolean compiled, Language language) throws Exception {
        boolean firstCompiledCall = compiled;
        for (var row : cases) {
            var label = stage + "/" + backend + "/" + name + "/" + row.input;
            long before = firstCompiledCall ? count(program, "compiledEntries") : 0L;
            assertEquals(row.answer, Calls.target(entry, new Object[] {0L, row.input}), label);
            if (firstCompiledCall) {
                assertTrue(count(program, "compiledEntries") > before, label + " first installed call");
                firstCompiledCall = false;
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
        return operation.getPrimitive().contains("Word32") ? "aliasWord32Bytes" : "aliasInt32Bytes";
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
                        for (int mutation = 0; mutation <= 13; mutation++)
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
                                        if (List.of(ByteArrayOp.WRITE_INT32, ByteArrayOp.WRITE_WORD32)
                                                .contains(operation))
                                            metadata(args.get(2)).put("rep", wrong("long", "IntRep"));
                                        else
                                            metadata.put("rep", wrong("long", "IntRep"));
                                    }
                                    case 8 -> {
                                        if (List.of(ByteArrayOp.READ_INT32, ByteArrayOp.READ_WORD32)
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
                                        if (List.of(ByteArrayOp.READ_INT32, ByteArrayOp.READ_WORD32)
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
                                                operation.getPrimitive().contains("Word32") ? "Int32Rep" : "Word32Rep";
                                            case 11 -> "Int64Rep";
                                            case 12 -> "Word64Rep";
                                            default -> "WordRep";
                                        };
                                        var payload = wrong("long", rep);
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
                                boolean sameCarrier = mutation == 6 || (!operation.getTuple() && mutation == 10);
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
                            var guard = index < 0 || index > Long.MAX_VALUE / 4 ? "element" : "range";
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
    private static Map<String, Object> changed(Map<String, Object> module, String key, Object value) {
        var result = new LinkedHashMap<>(module);
        result.put(key, value);
        return result;
    }
    private static long count(ExecutableProgram program, String key) {
        return ((Number) program.diagnostics().get(key)).longValue();
    }
}
