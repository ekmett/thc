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
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
public class Int8ArrayNativeTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final List<String> names = List.of("unboxedInt8Accum", "unboxedInt8ST", "unboxedWord8Accum",
        "unboxedWord8ST", "aliasBytes", "emptyBytes", "rawSignedRead", "rawUnsignedRead", "rawSignedIndex");
    private final List<ByteArrayOp> operations =
        List.of(ByteArrayOp.READ_INT8, ByteArrayOp.WRITE_INT8, ByteArrayOp.INDEX_INT8, ByteArrayOp.READ_WORD8);
    private Map<String, Object> manifest() throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(root.resolve("build/int8-arrays/manifest.json")));
    }
    private Map<String, Object> merged(List<String> paths) throws Exception {
        var modules = new ArrayList<Map<String, Object>>();
        for (var path : paths) modules.add((Map<String, Object>) Json.parse(Files.readString(root.resolve(path))));
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
    private long lane(long x, boolean unsigned) {
        long bits = x & 255L;
        return unsigned || bits < 128 ? bits : bits - 256;
    }
    private long model(String name, long raw) {
        require(names.contains(name));
        if (name.equals("emptyBytes"))
            return raw;
        if (name.startsWith("raw"))
            return lane(raw, name.equals("rawUnsignedRead"));
        if (name.equals("aliasBytes")) {
            long a = (raw + 101) & 255L, b = (raw + 37) & 255L;
            return 3 * lane(raw, false) + 5 * (raw & 255L) + 20 * lane(a, false) + 34 * b + 17 * lane(b, false)
                + 19 * a;
        }
        long[] cells =
            name.endsWith("Accum") ? new long[] {raw + 1, raw + 5, 2 * raw} : new long[] {raw, raw + 7, 2 * raw + 21};
        long[] weights = {7, 11, 13};
        long sum = 0;
        for (int i = 0; i < cells.length; i++) sum += weights[i] * lane(cells[i], name.contains("Word8"));
        return sum;
    }
    // This inventory is independent of the producer and its manifest. Long arithmetic wraps at 64 bits.
    private final List<Long> inputs = inputs();
    private List<Long> inputs() {
        var values = new TreeSet<Long>();
        for (long x = -256; x <= 255; x++) values.add(x);
        for (int bit = 0; bit <= 63; bit++)
            for (long delta = -1; delta <= 1; delta++)
                for (long sign : List.of(-1L, 1L)) values.add(sign * ((1L << bit) + delta));
        values.addAll(List.of(0x5555555555555555L, 0xaaaaaaaaaaaaaaaaL, 0x0123456789abcdefL, 0xfedcba9876543210L));
        return new ArrayList<>(values);
    }
    private record Row(long input, long answer) {}
    private Map<String, List<Row>> checkedRows(String text) {
        var lines = new ArrayList<>(Arrays.asList(text.split("\\r\\n|\\n|\\r", -1)));
        if (!lines.isEmpty() && lines.getLast().isEmpty())
            lines.removeLast();
        require(lines.size() == names.size() * inputs.size(), "Int8 oracle row count mismatch");
        var rows = new LinkedHashMap<String, List<Row>>();
        for (var name : names) rows.put(name, new ArrayList<>());
        for (int index = 0; index < lines.size(); index++) {
            var fields = lines.get(index).split("\t", -1);
            require(fields.length == 3, "Int8 oracle columns at row " + index);
            var name = names.get(index / inputs.size());
            long raw = inputs.get(index % inputs.size());
            require(fields[0].equals(name) && Objects.equals(longOrNull(fields[1]), raw),
                "Int8 oracle inventory/order at row " + index);
            var answer = longOrNull(fields[2]);
            require(Objects.equals(answer, model(name, raw)), "Int8 native/model mismatch at row " + index);
            rows.get(name).add(new Row(raw, answer));
        }
        return rows;
    }
    private Long longOrNull(String text) {
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
    @Test
    public void independentInventoryCoversEveryByteAndFullWidthBoundary() {
        assertEquals(846, inputs.size());
        assertEquals(7614, names.size() * inputs.size());
        assertEquals(inputs, inputs.stream().distinct().sorted().toList());
        var range = new ArrayList<Long>();
        for (long x = -256; x <= 255; x++) range.add(x);
        assertTrue(inputs.containsAll(range));
        for (int bit = 0; bit <= 63; bit++)
            for (long delta = -1; delta <= 1; delta++)
                for (long sign : List.of(-1L, 1L)) assertTrue(inputs.contains(sign * ((1L << bit) + delta)));
        for (var name : names)
            if (!name.equals("emptyBytes"))
                for (long raw = 0; raw <= 255; raw++) {
                    assertEquals(model(name, raw), model(name, raw + 256), name + "/" + raw);
                    assertEquals(model(name, raw), model(name, raw - 256), name + "/" + raw);
                }
        assertEquals(-128L, model("rawSignedRead", 128));
        assertEquals(-1L, model("rawSignedIndex", 255));
        assertEquals(128L, model("rawUnsignedRead", 128));
        assertEquals(255L, model("rawUnsignedRead", -1));
        assertNotEquals(model("unboxedInt8ST", 128), model("unboxedWord8ST", 128));
    }
    private long wide(byte value, boolean unsigned) {
        return unsigned ? ((long) value) & 255L : value;
    }
    @Test
    public void independentModelMatchesSequentialCellsAndAliasSnapshots() {
        for (long raw : inputs) {
            for (boolean unsigned : List.of(false, true)) {
                var accum = new byte[8];
                Arrays.fill(accum, (byte) raw);
                int[] indices = {-3, 0, -3, 4};
                long[] values = {3, 5, -2, raw & 255L};
                for (int i = 0; i < indices.length; i++)
                    accum[indices[i] + 3] = (byte) (accum[indices[i] + 3] + values[i]);
                var st = new byte[8];
                Arrays.fill(st, (byte) raw);
                st[3] = (byte) (st[0] + 7);
                st[7] = (byte) (3 * st[3] - st[0]);
                for (var suffix : List.of("Accum", "ST")) {
                    var cells = suffix.equals("Accum") ? accum : st;
                    assertEquals(
                        7 * wide(cells[0], unsigned) + 11 * wide(cells[3], unsigned) + 13 * wide(cells[7], unsigned),
                        model("unboxed" + (unsigned ? "Word8" : "Int8") + suffix, raw));
                }
            }
            byte[] bytes = {(byte) raw, (byte) (raw ^ 0x55L)};
            long before = bytes[0], beforeUnsigned = before & 255L;
            bytes[0] = (byte) (raw + 101);
            bytes[1] = (byte) (raw + 37);
            long first = bytes[0], second = bytes[1];
            assertEquals(3 * before + 5 * beforeUnsigned + 7 * first + 11 * (second & 255L) + 13 * first + 17 * second
                    + 19 * (first & 255L) + 23 * (second & 255L),
                model("aliasBytes", raw));
            assertEquals(raw, model("emptyBytes", raw));
            assertEquals((long) (byte) raw, model("rawSignedRead", raw));
            assertEquals((long) (byte) raw, model("rawSignedIndex", raw));
            assertEquals(((long) (byte) raw) & 255L, model("rawUnsignedRead", raw));
        }
    }
    private String text(List<String> rows) {
        return String.join("\n", rows) + "\n";
    }
    private void reject(List<String> rows) {
        assertThrows(IllegalArgumentException.class, () -> checkedRows(text(rows)));
    }
    @Test
    public void exactOracleRejectsMissingDuplicateReorderedMalformedAndWrongRows() {
        var lines = new ArrayList<String>();
        var expected = new LinkedHashMap<String, List<Row>>();
        for (var name : names) {
            var rows = new ArrayList<Row>();
            for (long input : inputs) {
                lines.add(name + "\t" + input + "\t" + model(name, input));
                rows.add(new Row(input, model(name, input)));
            }
            expected.put(name, rows);
        }
        assertEquals(expected, checkedRows(text(lines)));
        assertEquals(expected, checkedRows(String.join("\n", lines)));
        reject(List.of());
        reject(lines.subList(1, lines.size()));
        var copy = new ArrayList<>(lines);
        copy.add(lines.getFirst());
        reject(copy);
        reject(lines.reversed());
        copy = new ArrayList<>(lines);
        copy.set(1, copy.getFirst());
        reject(copy);
        copy = new ArrayList<>(lines);
        Collections.swap(copy, 0, 1);
        reject(copy);
        copy = new ArrayList<>(lines.subList(inputs.size(), lines.size()));
        copy.addAll(lines.subList(0, inputs.size()));
        reject(copy);
        for (int nameIndex = 0; nameIndex < names.size(); nameIndex++) {
            copy = new ArrayList<>(lines);
            int index = nameIndex * inputs.size();
            var line = copy.get(index);
            copy.set(index,
                line.substring(0, line.lastIndexOf('\t')) + "\t"
                    + (model(names.get(nameIndex), inputs.getFirst()) ^ 1L));
            reject(copy);
        }
        for (var bad : List.of("unknown\t0\t0", "", "unboxedInt8Accum\t0", lines.getFirst() + "\t0",
                 "unboxedInt8Accum\tbad\t0", "unboxedInt8Accum\t9223372036854775808\t0",
                 "unboxedInt8Accum\t" + inputs.getFirst() + "\t", "unboxedInt8Accum\t" + inputs.getFirst() + "\t1.5",
                 "unboxedInt8Accum\t" + inputs.getFirst() + "\t9223372036854775808")) {
            copy = new ArrayList<>(lines);
            copy.set(0, bad);
            reject(copy);
        }
        copy = new ArrayList<>(lines);
        copy.add("");
        reject(copy);
    }
    private Set<String> requiredPrimitives(String name) {
        if (name.equals("aliasBytes"))
            return Set.of("newByteArray#", "unsafeFreezeByteArray#", "readInt8Array#", "writeInt8Array#",
                "indexInt8Array#", "readWord8Array#", "writeWord8Array#", "indexWord8Array#");
        if (name.equals("emptyBytes"))
            return Set.of("newByteArray#", "unsafeFreezeByteArray#", "sizeofByteArray#");
        if (List.of("rawSignedRead", "rawUnsignedRead", "rawSignedIndex").contains(name)) {
            var result = new HashSet<>(Set.of("newByteArray#", "writeInt8Array#",
                name.equals("rawSignedRead")         ? "readInt8Array#"
                    : name.equals("rawUnsignedRead") ? "readWord8Array#"
                                                     : "indexInt8Array#"));
            if (name.equals("rawSignedIndex"))
                result.add("unsafeFreezeByteArray#");
            return result;
        }
        require(names.contains(name));
        var kind = name.contains("Word8") ? "Word8" : "Int8";
        var result = new HashSet<>(Set.of("newByteArray#", "unsafeFreezeByteArray#", "read" + kind + "Array#",
            "write" + kind + "Array#", "index" + kind + "Array#", "plus" + kind + "#"));
        if (name.endsWith("ST"))
            result.addAll(Set.of("sub" + kind + "#", "times" + kind + "#"));
        return result;
    }
    private long checkedCalls(Map<String, Object> module, String name) {
        var evidence = new ArrayCoreEvidence(module, name);
        require(evidence.getPrimitiveCounts().keySet().containsAll(requiredPrimitives(name)),
            name + " missing required primitive");
        return evidence.loweredImmediateStateCalls();
    }
    @Test
    public void genuineCoreMustRetainEveryRequiredPrimitive() throws Exception {
        for (var paths : ((Map<String, List<String>>) manifest().get("stages")).values())
            for (var name : names) {
                var module = merged(paths);
                assertEquals(1L, checkedCalls(module, name));
                for (var primitive : requiredPrimitives(name)) {
                    var changed = (Map<String, Object>) Json.parse(Json.stringify(module));
                    var nodes = new ArrayCoreEvidence(changed, name)
                                    .nodes(changed)
                                    .stream()
                                    .filter(n -> prefix(n).equals(List.of("prim", primitive)))
                                    .toList();
                    assertTrue(!nodes.isEmpty(), name + "/" + primitive);
                    for (var node : nodes) node.set(1, "missingArrayPrimitive#");
                    assertThrows(
                        IllegalArgumentException.class, () -> checkedCalls(changed, name), name + "/" + primitive);
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
    private void nativeChecks(boolean inlining) throws Exception {
        var manifest = manifest();
        assertEquals(names, manifest.get("entries"));
        assertEquals(inputs, manifest.get("inputs"));
        assertEquals((long) names.size() * inputs.size(), manifest.get("nativeRows"));
        assertEquals(64, ((Number) manifest.get("wordBits")).intValue());
        assertEquals(8, ((Number) manifest.get("elementBits")).intValue());
        for (var kind : List.of("inputHashes", "artifactHashes"))
            for (var e : ((Map<String, String>) manifest.get(kind)).entrySet()) {
                var actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(e.getKey()))));
                assertEquals(e.getValue(), actual,
                    "Stale 8-bit-array fixture: " + e.getKey() + "; regenerate the 8-bit-array fixtures");
            }
        var rows = checkedRows(Files.readString(root.resolve("build/int8-arrays/oracle.tsv")));
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
                            var linked = CoreModules.reachable(module, name);
                            var bindings = (List<Map<String, Object>>) linked.get("bindings");
                            var p = program(language, changed(linked, "instrument", true), backend);
                            var entry = p.entryTarget(
                                (String) single(bindings.stream().filter(b -> name.equals(b.get("name"))).toList())
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
            Object expected;
            if (((GuestRoot) entry.getRootNode()).getScalarResultProof().isInt())
                expected = (int) row.answer;
            else
                expected = row.answer;
            assertEquals(expected, Calls.target(entry, new Object[] {0L, row.input}), label);
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
        return "aliasBytes";
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
                                var module = CoreModules.reachable(merged(paths), owner(operation));
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
                                        if (operation == ByteArrayOp.WRITE_INT8)
                                            metadata(args.get(2)).put("rep", wrong("long", "IntRep"));
                                        else
                                            metadata.put("rep", wrong("long", "IntRep"));
                                    }
                                    case 8 -> {
                                        if (List.of(ByteArrayOp.READ_INT8, ByteArrayOp.READ_WORD8)
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
                                        if (List.of(ByteArrayOp.READ_INT8, ByteArrayOp.READ_WORD8)
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
                                                operation.getPrimitive().contains("Word8") ? "Int8Rep" : "Word8Rep";
                                            case 11 -> "Int64Rep";
                                            case 12 -> "Word64Rep";
                                            case 13 -> "WordRep";
                                            case 14 -> "Int16Rep";
                                            case 15 -> "Word16Rep";
                                            case 16 -> "Int32Rep";
                                            case 17 -> "Word32Rep";
                                            default ->
                                                operation.getPrimitive().contains("Word8") ? "Word8Rep" : "Int8Rep";
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
                                                new Object[] {p.entryValue(name), new Object[] {seed}}),
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
                                                new Object[] {p.entryValue(owner(operation)), new Object[] {5L}}));
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
                        var module = CoreModules.reachable(merged(paths), owner(operation));
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
                            var module = CoreModules.reachable(merged(paths), name);
                            var app = application(module, operation);
                            var args = (List<Object>) app.get(2);
                            args.set(1, Arrays.asList("lit", "int", Long.toString(index), metadata(args.get(1))));
                            var p = program(language, module, backend);
                            var function = context.asValue(new EntryValue(p, name, 1));
                            var failure = assertThrows(PolyglotException.class, () -> function.execute(5L));
                            assertEquals(
                                RuntimeFault.class.getName() + ": Managed allocation range outside its backing storage",
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
