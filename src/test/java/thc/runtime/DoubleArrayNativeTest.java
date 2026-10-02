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
public class DoubleArrayNativeTest {
    private static String coreEntry(String name) {
        return "main:" + (name.startsWith("unboxed") ? "UnboxedDoubleArrays" : "DoubleArrayAudit") + "." + name;
    }
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));

    private final List<String> names =
        List.of("unboxedDoubleAccum", "unboxedDoubleST", "moveDoubleBits", "indexDoubleBits");
    private final List<ByteArrayOp> operations =
        List.of(ByteArrayOp.READ_DOUBLE, ByteArrayOp.WRITE_DOUBLE, ByteArrayOp.INDEX_DOUBLE);
    private Map<String, Object> manifest() throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(root.resolve("build/double-arrays/manifest.json")));
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

    private long model(String name, long seed) {
        require(names.contains(name));
        if (name.equals("moveDoubleBits") || name.equals("indexDoubleBits")) {
            require(!signalingNaN(seed), "Signaling NaN movement is outside the evidence domain");
            return seed;
        }
        // Fixed-point quarters keep the public arithmetic independent of host FP.
        long x = (seed & 65535L) - 32768L;
        var cells = new long[8];
        Arrays.fill(cells, x * 4);
        if (name.equals("unboxedDoubleAccum")) {
            int[] indices = {0, 3, 0, 7};
            long[] quarters = {13, 22, -8, 2 * x};
            for (int i = 0; i < indices.length; i++) cells[indices[i]] += quarters[i];
        } else {
            if (!name.equals("unboxedDoubleST"))
                throw new IllegalStateException("Check failed.");
            long before = cells[0];
            cells[3] = before + 1;
            long after = cells[3];
            cells[7] = 3 * after - 4 * x + 2;
        }
        return cells[0] * 7 + cells[3] * 11 + cells[7] * 13;
    }
    private boolean signalingNaN(long bits) {
        return (bits & 0x7ff0000000000000L) == 0x7ff0000000000000L && (bits & 0x000fffffffffffffL) != 0
            && (bits & 0x0008000000000000L) == 0;
    }
    private final List<Long> magnitudes =
        List.of(0L, 1L, 2L, 3L, 0x000fffffffffffffL, 0x0010000000000000L, 0x3fefffffffffffffL, 0x3ff0000000000000L,
            0x3ff0000000000001L, 0x7fefffffffffffffL, 0x7ff0000000000000L, 0x7ff8000000000000L, 0x7ff8000000001234L,
            0x7fffffffffffffffL, 0x5555555555555555L, 0x55aa55aa55aa55aaL, 0x0123456789abcdefL);
    private List<Long> inputs() {
        var values = new TreeSet<Long>();
        for (long x = -16; x <= 16; x++) values.add(x);
        values.addAll(List.of(Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE));
        for (int bit = 0; bit <= 63; bit++)
            for (long delta = -1; delta <= 1; delta++)
                for (long sign : List.of(-1L, 1L)) values.add(sign * ((1L << bit) + delta));
        for (long bits : magnitudes)
            for (long sign : List.of(0L, Long.MIN_VALUE)) values.add(bits | sign);
        for (int bit = 0; bit <= 50; bit++)
            for (long sign : List.of(0L, Long.MIN_VALUE)) values.add(0x7ff8000000000000L | (1L << bit) | sign);
        return values.stream().filter(v -> !signalingNaN(v)).toList();
    }
    private record Row(String name, long input, long answer) {}
    private List<Row> expectedRows() {
        var rows = new ArrayList<Row>();
        for (var name : names)
            for (long input : inputs()) rows.add(new Row(name, input, model(name, input)));
        return rows;
    }
    private List<Row> verifyRows(String text) {
        var lines = new ArrayList<>(Arrays.asList(text.split("\\r\\n|\\n|\\r", -1)));
        if (!lines.isEmpty() && lines.getLast().isEmpty())
            lines.removeLast();
        var rows = new ArrayList<Row>();
        for (var line : lines) {
            var fields = line.split("\t", -1);
            require(fields.length == 3, "Double oracle requires name/input/result");
            rows.add(new Row(fields[0], Long.parseLong(fields[1]), Long.parseLong(fields[2])));
        }
        require(rows.equals(expectedRows()), "Double oracle/model mismatch or incomplete, duplicate, reordered inputs");
        return rows;
    }
    @Test
    public void independentModelsAndBinary64DomainAreExact() {
        var values = inputs();
        assertEquals(506, values.size());
        assertEquals(values.stream().distinct().sorted().toList(), values);
        for (long bits : magnitudes)
            for (long sign : List.of(0L, Long.MIN_VALUE)) assertTrue(values.contains(bits | sign));
        for (int bit = 0; bit <= 63; bit++)
            for (long delta = -1; delta <= 1; delta++)
                for (long sign : List.of(-1L, 1L)) {
                    long bits = sign * ((1L << bit) + delta);
                    if (!signalingNaN(bits))
                        assertTrue(values.contains(bits));
                }
        for (int bit = 0; bit <= 50; bit++)
            for (long sign : List.of(0L, Long.MIN_VALUE))
                assertTrue(values.contains(0x7ff8000000000000L | (1L << bit) | sign));
        var all = new ArrayList<>(values);
        all.addAll(List.of(0L, 65535L, Long.MIN_VALUE, Long.MAX_VALUE));
        for (long raw : all) {
            long x = (raw & 65535) - 32768;
            assertEquals(150 * x + 277, model("unboxedDoubleAccum", raw));
            assertEquals(176 * x + 76, model("unboxedDoubleST", raw));
            for (var name : names.subList(0, 2)) assertTrue(Math.abs(model(name, raw)) < (1L << 24));
            assertFalse(signalingNaN(raw));
            for (var name : names.subList(2, names.size())) assertEquals(raw, model(name, raw));
        }
        for (long raw : List.of(0x7ff0000000000001L, 0x7ff7ffffffffffffL, 0xfff0000000001234L)) {
            assertTrue(signalingNaN(raw));
            for (var name : names.subList(2, names.size()))
                assertThrows(IllegalArgumentException.class, () -> model(name, raw));
        }
        assertThrows(IllegalArgumentException.class, () -> model("unknown", 0));
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
        assertEquals(2024, rows.size());
        assertEquals(rows, verifyRows(valid));
        var duplicate = new ArrayList<>(rows);
        duplicate.add(rows.getFirst());
        var first = rows.getFirst();
        var corrupt = List.of("", text(rows.subList(1, rows.size())), text(duplicate), text(rows.reversed()),
            text(firstChanged(rows, rows.get(1))),
            text(firstChanged(rows, new Row(first.name, first.input, first.answer + 1))),
            text(firstChanged(rows, new Row("unknown", first.input, first.answer))), valid.replaceFirst("\t", " "),
            valid.replaceFirst("\t", "\textra\t"), valid + "\n", "moveDoubleBits\t9223372036854775808\t0\n",
            "moveDoubleBits\tnan\t0\n");
        for (int i = 0; i < corrupt.size(); i++) {
            var bad = corrupt.get(i);
            assertThrows(IllegalArgumentException.class, () -> verifyRows(bad), "mutation " + i);
        }
    }
    @Test
    public void nativePublicArraysAndBitMovementWithInlining() throws Exception {
        nativeChecks(true);
    }
    @Test
    public void nativePublicArraysAndBitMovementAcrossResidualCalls() throws Exception {
        nativeChecks(false);
    }
    private Set<String> requiredPrimitives(String name) {
        if (name.equals("moveDoubleBits") || name.equals("indexDoubleBits"))
            return Set.of("newByteArray#", "unsafeFreezeByteArray#", "writeIntArray#", "indexIntArray#",
                "writeDoubleArray#", name.equals("moveDoubleBits") ? "readDoubleArray#" : "indexDoubleArray#");
        return Set.of("readDoubleArray#", "writeDoubleArray#", "indexDoubleArray#",
            "newByteArray#", "unsafeFreezeByteArray#");
    }
    private void checkCore(ArrayCoreEvidence evidence, String name) {
        require(evidence.getPrimitiveCounts().keySet().containsAll(requiredPrimitives(name)),
            name + " lost a required memory operation");
        if (!List.of("moveDoubleBits", "indexDoubleBits").contains(name)) return;
        boolean read = name.equals("moveDoubleBits");
        var primitive = read ? "readDoubleArray#" : "indexDoubleArray#";
        require(evidence.getBindings().stream().anyMatch(helper -> {
            var id = helper.get("id");
            if (Objects.equals(id, evidence.getRoot().get("id"))) return false;
            var expr = (List<Object>) helper.get("expr");
            var info = metadata(expr);
            if (info == null || !(info.get("resultRep") instanceof Map<?, ?> rep)
                || !List.of("DoubleRep").equals(rep.get("primReps"))) return false;
            if (read) {
                if (!"unboxed-tuple".equals(rep.get("aggregate"))
                    || !(rep.get("components") instanceof List<?> parts) || parts.size() != 2
                    || !(parts.get(0) instanceof Map<?, ?> state) || !"void".equals(state.get("kind"))
                    || !List.of().equals(state.get("primReps"))
                    || !(parts.get(1) instanceof Map<?, ?> value) || !"double".equals(value.get("kind"))
                    || !List.of("DoubleRep").equals(value.get("primReps"))) return false;
            } else if (!"double".equals(rep.get("kind"))) return false;
            return evidence.nodes(expr).stream().anyMatch(n -> prefix(n).equals(List.of("prim", primitive)))
                && evidence.getBindings().stream().filter(b -> !Objects.equals(b.get("id"), id))
                    .flatMap(b -> applications(b.get("expr")).stream())
                    .anyMatch(call -> prefix((List<?>) call.get(1)).equals(List.of("var", id))
                        && Objects.equals((long) ((List<?>) call.get(2)).size(), helper.get("arity")));
        }), name + " lost its called residual Double result path");
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
            for (var name : List.of("moveDoubleBits", "indexDoubleBits"))
                for (var mutation : name.equals("moveDoubleBits") ? List.of("call", "lane", "state") : List.of("call", "lane")) {
                    var module = merged(paths);
                    var evidence = new ArrayCoreEvidence(module, coreEntry(name));
                    checkCore(evidence, name);
                    var primitive = name.equals("moveDoubleBits") ? "readDoubleArray#" : "indexDoubleArray#";
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
        assertEquals(inputs(), manifest.get("inputs"));
        assertEquals(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? "little" : "big", manifest.get("byteOrder"));
        assertEquals(64, ((Number) manifest.get("wordBits")).intValue());
        assertEquals(true, manifest.get("signalingNaNsExcluded"));
        for (var kind : List.of("inputHashes", "artifactHashes"))
            for (var e : ((Map<String, String>) manifest.get(kind)).entrySet()) {
                var actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(e.getKey()))));
                assertEquals(e.getValue(), actual,
                    "Stale Double-array fixture: " + e.getKey() + "; regenerate the Double-array fixtures");
            }
        var rows = new LinkedHashMap<String, List<Row>>();
        for (var row : verifyRows(Files.readString(root.resolve("build/double-arrays/oracle.tsv"))))
            rows.computeIfAbsent(row.name, k -> new ArrayList<>()).add(row);
        assertEquals(new HashSet<>(names), rows.keySet());
        assertEquals(2024L, manifest.get("nativeRows"));
        assertEquals(
            ((Number) manifest.get("nativeRows")).intValue(), rows.values().stream().mapToInt(List::size).sum());
        var stages = (Map<String, List<String>>) manifest.get("stages");
        assertEquals(Set.of("pre", "post"), stages.keySet());
        for (var stage : stages.entrySet()) {
            var module = merged(stage.getValue());
            for (var name : names) {
                var evidence = new ArrayCoreEvidence(module, coreEntry(name));
                checkCore(evidence, name);
                var cases = rows.get(name);
                assertEquals(cases.size(), new HashSet<>(cases.stream().map(Row::input).toList()).size());
                assertEquals(inputs(), cases.stream().map(Row::input).toList());
                for (var row : cases) {
                    assertEquals(model(name, row.input), row.answer, "Native " + name + "(" + row.input + ")");
                    long exponent = row.input & 0x7ff0000000000000L, fraction = row.input & 0x000fffffffffffffL;
                    assertFalse(
                        exponent == 0x7ff0000000000000L && fraction != 0 && (row.input & 0x0008000000000000L) == 0);
                }
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
                            checkCases(cases, stage.getKey(), backend, name, inlining, p, entry,
                                false, language);
                            for (var target : activeTargets(entry)) compile(target);
                            checkCases(cases, stage.getKey(), backend, name, inlining, p, entry,
                                true, language);
                            for (var counter : List.of("unsupportedTraps", "blackholes"))
                                assertEquals(0L, count(p, counter), counter);
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
        return operation == ByteArrayOp.INDEX_DOUBLE ? "indexDoubleBits" : "moveDoubleBits";
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
    public void longIndexAliasesExecuteWhileDoubleStateShapeAndSaturationGuardsRemain() throws Exception {
        var paths = paths();
        for (var backend : List.of("ast", "bytecode")) try (var context = context(true)) {
                context.initialize("thc");
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (var operation : operations)
                        for (int mutation = 0; mutation <= 9; mutation++)
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
                                        if (operation == ByteArrayOp.WRITE_DOUBLE)
                                            metadata(args.get(2)).put("rep", wrong("long", "IntRep"));
                                        else
                                            metadata.put("rep", wrong("long", "IntRep"));
                                    }
                                    case 8 -> {
                                        if (operation == ByteArrayOp.READ_DOUBLE) {
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
                                        if (operation == ByteArrayOp.READ_DOUBLE) {
                                            var proof = (Map<String, Object>) metadata.get("rep");
                                            ((List<Object>) proof.get("components")).set(1, wrong("float", "FloatRep"));
                                            proof.put("primReps", List.of("FloatRep"));
                                        } else
                                            flags.set(1, true);
                                    }
                                }
                                if (mutation == 6) {
                                    var name = owner(operation);
                                    var p = program(
                                        language, changed(module, "diagnosticUnsupported", diagnostic), backend);
                                    for (long seed : List.of(5L, 0L, Long.MIN_VALUE)) {
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
        // Guest allocations retain their owner across unsafe freeze. Its element
        // and logical-range guards run before the raw Double backing-array guard.
        long[] invalid = {Long.MIN_VALUE, -1, 1, 1L << 32, 1L << 61, Long.MAX_VALUE};
        String[] guards = {"element", "element", "range", "range", "element", "element"};
        for (var backend : List.of("ast", "bytecode")) try (var context = context(true)) {
                context.initialize("thc");
                context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (var operation : operations)
                        for (int i = 0; i < invalid.length; i++) {
                            long index = invalid[i];
                            var guard = guards[i];
                            var name = owner(operation);
                            var module = CoreModules.reachable(merged(paths), coreEntry(name));
                            var app = application(module, operation);
                            var args = (List<Object>) app.get(2);
                            args.set(1, Arrays.asList("lit", "int", Long.toString(index), metadata(args.get(1))));
                            var p = program(language, module, backend);
                            var function = context.asValue(new EntryValue(p, coreEntry(name), 1));
                            var failure = assertThrows(PolyglotException.class, () -> function.execute(5L));
                            assertEquals(RuntimeFault.class.getName() + ": Managed allocation " + guard
                                    + " outside its backing storage",
                                failure.getMessage(), backend + "/" + operation + "/" + index);
                            released(language);
                            assertEquals(0L, count(p, "unsupportedTraps"));
                        }
                    for (var name : List.of("moveDoubleBits", "indexDoubleBits")) {
                        var good = program(language, CoreModules.reachable(merged(paths), coreEntry(name)), backend);
                        assertEquals(5L, context.asValue(new EntryValue(good, coreEntry(name), 1)).execute(5L).asLong());
                        released(language);
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
