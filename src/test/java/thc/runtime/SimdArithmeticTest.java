// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.*;
import jdk.incubator.vector.*;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.EntryValue;
import thc.Json;
import thc.Language;
import java.io.File;
import java.nio.file.Files;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class SimdArithmeticTest {
    private final File root = new File(System.getProperty("thc.projectRoot")), directory = new File(root, "build/simd-arithmetic");
    private Map<String, Object> json(File file) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(file.toPath())); }
    private record Shape(String name, String primitive, int lanes, int width, String scalar, int pattern) {
        boolean shuffle() { return primitive.startsWith("shuffle"); }
        boolean unsigned() { return scalar.startsWith("Word"); }
        boolean floating() { return scalar.equals("Float") || scalar.equals("Double"); }
    }
    private record Row(String name, long a, long b, long result) {}
    private record Evidence(List<Shape> shapes, List<Row> rows) {}
    private Evidence evidence() throws Exception {
        var manifest = json(new File(directory, "manifest.json"));
        assertEquals("9.14.1", manifest.get("ghc")); assertEquals("scalar-lane", manifest.get("nativeMode"));
        var hashes = new LinkedHashMap<>((Map<String, String>) manifest.get("inputHashes")); hashes.putAll((Map<String, String>) manifest.get("artifactHashes"));
        for (var item : hashes.entrySet()) {
            var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, item.getKey()).toPath())));
            assertEquals(item.getValue(), digest, item.getKey());
        }
        var shapes = new ArrayList<Shape>();
        for (var record : (List<Map<String, Object>>) manifest.get("entries"))
            shapes.add(new Shape((String) record.get("name"), (String) record.get("primitive"), ((Number) record.get("lanes")).intValue(),
                ((Number) record.get("width")).intValue(), (String) record.get("scalar"), ((Number) record.get("pattern")).intValue()));
        var expected = new LinkedHashSet<String>(); for (var op : GeneratedVectors.operations) if (op.startsWith("quot") || op.startsWith("rem") || op.startsWith("shuffle")) expected.add(op);
        var primitives = new LinkedHashSet<String>(); var names = new ArrayList<String>(); for (var shape : shapes) { primitives.add(shape.primitive); names.add(shape.name); }
        assertEquals(78, expected.size()); assertEquals(expected, primitives); assertEquals(138, shapes.size());
        var rows = new ArrayList<Row>(); var rowNames = new LinkedHashSet<String>();
        for (var line : Files.readAllLines(new File(directory, "oracle.tsv").toPath())) {
            var fields = line.split("\t", -1); assertEquals(4, fields.length);
            rows.add(new Row(fields[0], Long.parseLong(fields[1]), Long.parseLong(fields[2]), Long.parseLong(fields[3]))); rowNames.add(fields[0]);
        }
        assertEquals(((Number) manifest.get("rows")).intValue(), rows.size()); assertEquals(names, new ArrayList<>(rowNames));
        return new Evidence(shapes, rows);
    }
    private BigInteger lane(long raw, Shape shape) {
        var modulus = BigInteger.ONE.shiftLeft(shape.width); var bits = BigInteger.valueOf(raw).mod(modulus);
        return !shape.unsigned() && !shape.floating() && bits.testBit(shape.width - 1) ? bits.subtract(modulus) : bits;
    }
    /** Independent scalar BigInteger division and bit-lane selection, no Vector API. */
    private long model(Shape shape, long a, long b) {
        long checksum = 0;
        for (int index = 0; index < shape.lanes; index++) {
            BigInteger value;
            if (shape.shuffle()) {
                int selected = switch (shape.pattern) {
                    case 0 -> shape.lanes - 1 - index + (index % 2 == 1 ? shape.lanes : 0);
                    case 1 -> index + 1;
                    default -> index % 2 == 1 ? 2 * shape.lanes - 1 : 0;
                };
                value = selected >= shape.lanes ? lane(b - (selected - shape.lanes) * 7919L, shape) : lane(a + selected * 104729L, shape);
            } else {
                var x = lane(a + index * 104729L, shape); var y = lane(b - index * 7919L, shape);
                value = shape.primitive.startsWith("quot") ? x.divide(y) : x.remainder(y);
            }
            checksum += value.longValue() * (2L * index + 1);
        }
        return checksum;
    }
    @Test void nativeScalarOracleAgreesWithIndependentLaneModel() throws Exception {
        var evidence = evidence(); var byName = new LinkedHashMap<String, Shape>(); for (var shape : evidence.shapes) byName.put(shape.name, shape);
        for (var row : evidence.rows) assertEquals(row.result, model(Objects.requireNonNull(byName.get(row.name)), row.a, row.b), row.toString());
    }
    private List<RootCallTarget> targets(RootCallTarget entry) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget, Boolean>()); var result = new ArrayList<RootCallTarget>();
        visit(entry, seen, result); return result;
    }
    private void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> result) {
        if (!seen.add(target)) return;
        var body = target.getRootNode(); var nodes = new ArrayList<Node>(); nodes.add(body);
        if (body instanceof BytecodeRoot root) for (var instruction : root.getBytecodeNode().getInstructions())
            for (var argument : instruction.getArguments()) if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) {
                var cached = argument.asCachedNode(); if (cached != null) nodes.add(cached);
            }
        for (var node : nodes) for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class))
            if (call.getCurrentCallTarget() instanceof RootCallTarget child && child.getRootNode() instanceof GuestRoot) visit(child, seen, result);
        result.add(target);
    }
    private boolean valid(RootCallTarget target) throws Exception { return Boolean.TRUE.equals(target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private long compiled(ExecutableProgram program) { return ((Number) program.diagnostics().get("compiledEntries")).longValue(); }
    @FunctionalInterface private interface Action { void run(Context context, Language language) throws Exception; }
    private void language(Action action) throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("compiler.Inlining", "false")
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.SingleTierCompilationThreshold", "10000000").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try { action.run(context, TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    private void check(Value function, Row row, String backend) { assertEquals(row.result, function.execute(row.a, row.b).asLong(), backend + "/" + row); }
    @Test void originalCoreKeepsExactFirstInstalledEntriesOnBothBackends() throws Exception {
        var evidence = evidence(); var module = json(new File(directory, "pre-core/SimdArithmeticAudit.json"));
        var cases = new LinkedHashMap<String, List<Row>>(); for (var row : evidence.rows) cases.computeIfAbsent(row.name, ignored -> new ArrayList<>()).add(row);
        for (var backend : List.of("ast", "bytecode")) language((context, language) -> {
            for (var shape : evidence.shapes) {
                var label = backend + "/" + shape.name; var audit = json(new File(directory, shape.name + "-audit.json"));
                assertEquals(true, audit.get("accepted")); assertEquals(List.of(), audit.get("missingGlobals")); assertEquals(List.of(), audit.get("issues"));
                assertEquals(1, ((List<?>) audit.get("reachableBindings")).size(), label);
                var input = new LinkedHashMap<>(CoreModules.reachable(module, shape.name)); input.put("instrument", true);
                ExecutableProgram program = backend.equals("ast") ? new Program(language, input) : new BytecodeProgram(language, input);
                var function = context.asValue(new EntryValue(program, shape.name, 2));
                for (var row : cases.get(shape.name)) check(function, row, backend);
                assertEquals(0L, compiled(program), label + " interpreted");
                var entry = program.entryTarget(shape.name); var host = program.hostEntryTarget(2); var active = targets(host);
                assertEquals(2, active.size(), label + " exact target graph"); assertTrue(active.contains(entry));
                for (var target : active) if (target != host) {
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true); assertTrue(valid(target), label);
                    var runtime = Truffle.getRuntime();
                    runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target);
                    assertTrue(valid(target), label);
                }
                assertTrue(function.invokeMember("compile").asBoolean(), label);
                for (var row : cases.get(shape.name).reversed()) {
                    long before = compiled(program); check(function, row, backend); assertEquals(before + 1, compiled(program), label + " first-installed/" + row);
                    assertEquals(active, targets(host)); assertSame(entry, program.entryTarget(shape.name));
                    for (var target : active) assertTrue(valid(target), label + "/" + row);
                    var state = language.getHandoffState().get();
                    assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getArguments().retainedReferences());
                    assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getResults().retainedReferences());
                }
            }
        });
    }
    private List<List<Object>> nodes(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof List<?> list) { result.add((List<Object>) list); for (var item : list) result.addAll(nodes(item)); }
        else if (value instanceof Map<?, ?> map) for (var item : map.values()) result.addAll(nodes(item));
        return result;
    }
    @Test void literalShuffleIndicesAreCheckedBeforeExecution() throws Exception {
        evidence(); var module = json(new File(directory, "pre-core/SimdArithmeticAudit.json"));
        var shapes = new LinkedHashMap<String, Integer>(); shapes.put("Int8X16", 16); shapes.put("Int8X64", 64); shapes.put("Word16X32", 32);
        for (var backend : List.of("ast", "bytecode")) language((context, language) -> {
            for (var shape : shapes.entrySet()) {
                var primitive = "shuffle" + shape.getKey() + "#"; var original = CoreModules.reachable(module, "shuffle" + shape.getKey() + "Pattern0");
                for (var bad : Arrays.asList(-1L, 2L * shape.getValue(), Long.MAX_VALUE, null)) {
                    var input = (Map<String, Object>) Json.parse(Json.stringify(original)); var matches = new ArrayList<List<Object>>();
                    for (var node : nodes(input)) if (!node.isEmpty() && Objects.equals(node.getFirst(), "app") && node.size() > 1 &&
                        node.get(1) instanceof List<?> function && function.size() > 1 && primitive.equals(function.get(1))) matches.add(node);
                    assertEquals(1, matches.size()); var call = matches.getFirst(); var tuple = (List<Object>) ((List<?>) call.get(2)).get(2);
                    var fields = (List<Object>) tuple.get(2); var index = (List<Object>) fields.getFirst();
                    if (bad == null) fields.set(0, new ArrayList<>(List.of("var", "not-a-literal", index.getLast()))); else index.set(2, bad.toString());
                    assertThrows(RuntimeFault.class, () -> { if (backend.equals("ast")) new Program(language, input); else new BytecodeProgram(language, input); },
                        backend + "/" + shape.getKey() + "/" + bad);
                }
            }
        });
    }
    @Test void divisionRejectsAZeroInAnyLaneAndChecksEveryUnsignedBit() {
        for (int bits : List.of(128, 256, 512)) {
            var species = VectorShape.forBitSize(bits);
            var bytes = ByteVector.broadcast(ByteVector.SPECIES_128.withShape(species), (byte) -1);
            var shorts = ShortVector.broadcast(ShortVector.SPECIES_128.withShape(species), (short) -1);
            var ints = IntVector.broadcast(IntVector.SPECIES_128.withShape(species), -1);
            var longs = LongVector.broadcast(LongVector.SPECIES_128.withShape(species), -1L);
            for (boolean unsigned : List.of(false, true)) {
                assertThrows(ArithmeticException.class, () -> VectorIntegerDivision.quotByte(bytes, bytes.withLane(bytes.length() - 1, (byte) 0), unsigned));
                assertThrows(ArithmeticException.class, () -> VectorIntegerDivision.remShort(shorts, shorts.withLane(shorts.length() - 1, (short) 0), unsigned));
                assertThrows(ArithmeticException.class, () -> VectorIntegerDivision.quotInt(ints, ints.withLane(ints.length() - 1, 0), unsigned));
                assertThrows(ArithmeticException.class, () -> VectorIntegerDivision.remLong(longs, longs.withLane(longs.length() - 1, 0), unsigned));
            }
            var quotient = VectorIntegerDivision.quotLong(longs, LongVector.broadcast(longs.species(), 2L), true);
            var remainder = VectorIntegerDivision.remLong(longs, LongVector.broadcast(longs.species(), 2L), true);
            for (int lane = 0; lane < longs.length(); lane++) { assertEquals(Long.MAX_VALUE, quotient.lane(lane)); assertEquals(1L, remainder.lane(lane)); }
        }
    }
}
