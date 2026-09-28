// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.*;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorShape;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import java.io.File;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** One original-Core corpus for 24 shapes; the other six have SIMD128 coverage. */
@SuppressWarnings("unchecked")
class SimdAddressFamiliesTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final String directory = "build/simd-address-families";
    private final List<String> names = List.of("int8X32", "word8X32", "int8X64", "word8X64", "int16X32", "word16X32",
        "int32X4", "word32X4", "floatX4", "doubleX2", "int16X16", "word16X16", "int32X8", "word32X8", "int32X16", "word32X16",
        "int64X4", "word64X4", "int64X8", "word64X8", "floatX8", "floatX16", "doubleX4", "doubleX8");
    private final List<String> entries = entries();
    private List<String> entries() {
        var result = new ArrayList<String>();
        for (var shape : names) for (var operation : List.of("Index", "Read", "Write")) for (var mode : List.of("Packed", "Scalar")) result.add(shape + operation + mode);
        return result;
    }
    private final List<Long> seeds = List.of(Long.MIN_VALUE, -129L, -1L, 0L, 1L, 127L, 65535L, Long.MAX_VALUE);
    // Every shape and every typed carrier/operation is compiled; the entire
    // 144-operation corpus is interpreted against both independent models.
    private final Set<String> compiled = Set.of("int8X32IndexPacked", "word8X32ReadScalar", "int8X64WritePacked",
        "word8X64IndexScalar", "int16X32ReadPacked", "word16X32WriteScalar", "int32X4IndexPacked", "word32X4ReadScalar",
        "floatX4WritePacked", "doubleX2IndexScalar", "int16X16IndexPacked", "word16X16ReadScalar", "word16X16WritePacked",
        "int32X8WritePacked", "word32X8IndexScalar", "int32X16ReadPacked", "word32X16WriteScalar", "int64X4IndexPacked",
        "word64X4ReadScalar", "int64X8WritePacked", "word64X8IndexScalar", "floatX8ReadPacked", "floatX16IndexScalar", "doubleX4WritePacked", "doubleX8ReadScalar");
    private final Pattern pattern = Pattern.compile("(int8|word8|int16|word16|int32|word32|int64|word64|float|double)X(2|4|8|16|32|64)(Index|Read|Write)(Packed|Scalar)");
    private static final class Shape {
        final String scalar, operation, mode;
        final int lanes, width, bytes, stride;
        final boolean write, signed;
        Shape(String name, Pattern pattern) {
            var match = pattern.matcher(name); if (!match.matches()) throw new IllegalArgumentException(name);
            scalar = match.group(1); lanes = Integer.parseInt(match.group(2)); operation = match.group(3); mode = match.group(4);
            width = switch (scalar) { case "float" -> 4; case "double" -> 8; default -> Integer.parseInt(scalar.replaceFirst("^[^0-9]*", "")) / 8; };
            bytes = lanes * width; stride = mode.equals("Scalar") ? width : bytes; write = operation.equals("Write"); signed = scalar.startsWith("int");
        }
    }
    private record Input(String entry, long seed, long offset) {}
    private final List<Input> requests = requests();
    private List<Input> requests() {
        var result = new ArrayList<Input>();
        for (var entry : entries) { var shape = new Shape(entry, pattern);
            for (long seed : seeds) for (long offset : entry.endsWith("Scalar") ? List.of(0L, 1L, 2L * shape.lanes) : List.of(0L, 1L, 2L)) result.add(new Input(entry, seed, offset));
        }
        return result;
    }
    private byte[] initial(Input input) {
        var bytes = new byte[new Shape(input.entry, pattern).bytes * 3]; for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (input.seed + i * 37L); return bytes;
    }
    private record Expected(long answer, byte[] bytes) {}
    // Scalar byte arithmetic only: no Vector API or runtime helper is used here.
    private Expected expected(Input input) {
        var shape = new Shape(input.entry, pattern); var bytes = initial(input); int offset = (int) input.offset * shape.stride;
        if (shape.write) for (int lane = 0; lane < shape.lanes; lane++) {
            long value = input.seed * 23 + lane * 97; for (int octet = 0; octet < shape.width; octet++) bytes[offset + lane * shape.width + octet] = (byte) (value >>> (octet * 8));
        }
        long answer = 0;
        if (!shape.write) for (int lane = 0; lane < shape.lanes; lane++) {
            long bits = 0; for (int octet = 0; octet < shape.width; octet++) bits |= (bytes[offset + lane * shape.width + octet] & 255L) << (8 * octet);
            long value = shape.signed && shape.width < 8 ? bits << (64 - shape.width * 8) >> (64 - shape.width * 8) : bits;
            answer += value * (2L * lane + 1);
        }
        return new Expected(answer, bytes);
    }
    private long checksum(byte[] bytes) { long sum = 0; for (int i = 0; i < bytes.length; i++) sum += (bytes[i] & 255L) * (2L * i + 1); return sum; }
    private Map<String, Object> read(String path) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(new File(root, path).toPath())); }
    private Map<String, Object> manifest() throws Exception {
        assertEquals(ByteOrder.LITTLE_ENDIAN, ByteOrder.nativeOrder(), "Native corpus target byte order");
        var data = read(directory + "/manifest.json");
        assertEquals(1L, data.get("schema")); assertEquals("9.14.1", data.get("ghc")); assertEquals(entries, data.get("entries")); assertEquals(3456L, data.get("scalarRows"));
        var primitives = new ArrayList<String>();
        for (var name : entries) {
            var shape = new Shape(name, pattern); var scalar = Character.toUpperCase(shape.scalar.charAt(0)) + shape.scalar.substring(1); var vector = scalar + "X" + shape.lanes;
            primitives.add(shape.operation.toLowerCase(Locale.ROOT) + (shape.mode.equals("Scalar") ? scalar + "OffAddrAs" + vector : vector + "OffAddr") + "#");
        }
        assertEquals(primitives, data.get("primitives"));
        for (var kind : List.of("inputHashes", "artifactHashes")) {
            var hashes = (Map<String, String>) data.get(kind); assertFalse(hashes.isEmpty());
            for (var entry : hashes.entrySet()) {
                var file = root.toPath().resolve(entry.getKey()).normalize();
                if (new File(entry.getKey()).isAbsolute() || !file.startsWith(root.toPath())) throw new IllegalArgumentException("Invalid artifact path");
                var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
                assertEquals(entry.getValue(), actual, "Stale SIMD address " + kind + ": " + entry.getKey());
            }
        }
        boolean native128 = !Set.of("arm64", "aarch64").contains(System.getProperty("os.arch").toLowerCase(Locale.ROOT));
        assertEquals(native128 ? 576L : 0L, data.get("nativeVector128Rows"));
        for (var mode : native128 ? List.of("scalar", "vector128") : List.of("scalar")) {
            var selected = new ArrayList<Input>(); for (var input : requests) if (mode.equals("scalar") || new Shape(input.entry, pattern).bytes == 16) selected.add(input);
            var lines = Files.readAllLines(new File(root, directory + "/" + mode + "-oracle.tsv").toPath()); assertEquals(selected.size(), lines.size());
            for (int i = 0; i < selected.size(); i++) {
                var input = selected.get(i); var expected = expected(input);
                assertEquals(List.of(input.entry, Long.toString(input.seed), Long.toString(input.offset), Long.toString(expected.answer), Long.toString(checksum(expected.bytes))),
                    Arrays.asList(lines.get(i).split("\t", -1)), mode + " native/model: " + input);
            }
        }
        return data;
    }
    private List<RootCallTarget> targets(RootCallTarget entry) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget, Boolean>()); var result = new ArrayList<RootCallTarget>(); visit(entry, seen, result); return result;
    }
    private void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> result) {
        if (!seen.add(target)) return; var body = target.getRootNode(); var nodes = new ArrayList<Node>(); nodes.add(body);
        if (body instanceof BytecodeRoot root) for (var instruction : root.getBytecodeNode().getInstructions()) for (var argument : instruction.getArguments())
            if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) { var cached = argument.asCachedNode(); if (cached != null) nodes.add(cached); }
        for (var node : nodes) for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class))
            if (call.getCurrentCallTarget() instanceof RootCallTarget child && child.getRootNode() instanceof GuestRoot) visit(child, seen, result);
        result.add(target);
    }
    private List<List<?>> nodes(Object value) {
        var result = new ArrayList<List<?>>();
        if (value instanceof List<?> list) { result.add(list); for (var item : list) result.addAll(nodes(item)); }
        else if (value instanceof Map<?, ?> map) for (var item : map.values()) result.addAll(nodes(item));
        return result;
    }
    private long count(ExecutableProgram p) { return ((Number) p.diagnostics().get("compiledEntries")).longValue(); }
    private List<Object> callCounts(List<RootCallTarget> active) throws Exception {
        var result = new ArrayList<Object>(); for (var target : active) result.add(target.getClass().getMethod("getCallCount").invoke(target)); return result;
    }
    private void call(RootCallTarget entry, Input input, Language language, String stage, String backend) {
        var pools = language.getHandoffState().get(); var bytes = initial(input); var expected = expected(input);
        try {
            assertEquals(expected.answer, Calls.target(entry, new Object[]{0L, ManagedAddress.Companion.fromByteArray(bytes), input.offset, input.seed}), stage + "/" + backend + "/" + input);
            assertArrayEquals(expected.bytes, bytes, stage + "/" + backend + "/" + input + " full storage");
        } finally {
            assertEquals(0, pools.getArguments().getDepth()); assertEquals(0, pools.getResults().getDepth());
            assertEquals(0, pools.getArguments().retainedReferences()); assertEquals(0, pools.getResults().retainedReferences()); assertNull(pools.getPending());
        }
    }
    @Test void genuineCoreMatchesNativeModelsOnTheFirstInstalledCallInBothBackends() throws Exception {
        var evidence = manifest(); var stages = (Map<String, Map<String, Object>>) evidence.get("stages"); assertTrue(stages.containsKey("pre"));
        for (var stageItem : stages.entrySet()) {
            var stage = stageItem.getKey(); var data = stageItem.getValue(); var audit = read((String) data.get("audit"));
            assertEquals(true, audit.get("accepted")); assertEquals(List.of(), audit.get("issues")); assertEquals(List.of(), audit.get("missingGlobals"));
            var core = read((String) data.get("core")); var selected = (List<String>) data.get("entries");
            var expectedSelected = new ArrayList<String>(); for (var name : entries) if (stage.equals("pre") || new Shape(name, pattern).bytes == 16) expectedSelected.add(name);
            assertEquals(expectedSelected, selected);
            for (var backend : List.of("ast", "bytecode")) try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("compiler.Inlining", "false").option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.SingleTierCompilationThreshold", "10000000").option("engine.CompilationFailureAction", "Throw").build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (var name : selected) {
                        var linked = new LinkedHashMap<>(CoreModules.reachable(core, name)); linked.put("instrument", true);
                        var bindings = (List<Map<String, Object>>) linked.get("bindings"); assertEquals(1, bindings.size());
                        long expectedEntries = name.contains("Index") ? 1L : 2L, lambdas = 0;
                        for (var node : nodes(bindings)) if (!node.isEmpty() && Objects.equals(node.getFirst(), "lam")) lambdas++;
                        assertEquals(expectedEntries, lambdas);
                        ExecutableProgram p = backend.equals("ast") ? new Program(language, linked) : new BytecodeProgram(language, linked);
                        var entry = p.entryTarget(name); var corpus = new ArrayList<Input>(); for (var input : requests) if (input.entry.equals(name)) corpus.add(input);
                        var pools = language.getHandoffState().get(); for (var input : corpus) call(entry, input, language, stage, backend);
                        assertEquals(0L, ((Number) p.diagnostics().get("unsupportedTraps")).longValue()); if (!compiled.contains(name)) continue;
                        var active = targets(entry); assertEquals(expectedEntries, (long) active.size()); var callCounts = callCounts(active);
                        long arguments = pools.getArguments().getAllocations(), results = pools.getResults().getAllocations();
                        var runtime = Truffle.getRuntime(); var targetClass = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                        for (var target : active) {
                            target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                            assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); runtime.getClass().getMethod("bypassedInstalledCode", targetClass).invoke(runtime, target);
                        }
                        assertEquals(0L, count(p)); assertEquals(callCounts, callCounts(active));
                        for (var input : corpus.reversed()) {
                            long before = count(p); call(entry, input, language, stage, backend); assertEquals(expectedEntries, count(p) - before, stage + "/" + backend + "/" + name + " exact first installed entries");
                            assertEquals(active, targets(entry)); for (var target : active) assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                            assertEquals(arguments, pools.getArguments().getAllocations()); assertEquals(results, pools.getResults().getAllocations());
                        }
                        assertEquals(callCounts, callCounts(active)); assertEquals(0L, ((Number) p.diagnostics().get("unsupportedTraps")).longValue());
                    }
                } finally { context.leave(); }
            }
        }
    }
    private byte[] bytes(int size, int multiplier, int bias) { var bytes = new byte[size]; for (int i = 0; i < size; i++) bytes[i] = (byte) (i * multiplier + bias); return bytes; }
    /** Shared SIMD128 tests cover storage kinds and native lifetime. These check
     * the only new ownership dimension: whole 32-/64-byte spans and species. */
    @Test void wideRegionsPreservePointerCellsAndRejectWrongSpeciesBeforeWriting() {
        for (int size : List.of(32, 64)) {
            var owner = ManagedAllocation.mutable(size * 3L, 8); var address = ManagedAddress.Companion.fromAllocation(owner);
            var species = ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(size * 8)); var vector = ByteVector.fromArray(species, bytes(size, 17, 0), 0);
            var pointer = address.plus(size * 2L); owner.writeAddressByteOffset(size * 2L, pointer);
            address.writeVectorBytes(0, 1, vector, size); assertEquals(vector, address.readVectorBytes(0, 1, size)); assertSame(pointer, owner.readAddressByteOffset(size * 2L));
            for (long offset : List.of(Long.MIN_VALUE, Long.MAX_VALUE, -1L, size * 2L + 1)) {
                assertThrows(RuntimeFault.class, () -> address.writeVectorBytes(offset, 1, vector, size)); assertEquals(vector, address.readVectorBytes(0, 1, size));
            }
            assertThrows(RuntimeFault.class, () -> address.readVectorBytes(size + 1L, 1, size));
            assertThrows(RuntimeFault.class, () -> address.writeVectorBytes(0, 1, ByteVector.zero(ByteVector.SPECIES_128), size));
            owner.shrink(size); assertThrows(RuntimeFault.class, () -> address.readVectorBytes(1, 1, size)); assertEquals(vector, address.readVectorBytes(0, 1, size));
        }
    }
    @Test void wideNativeRegionsKeepTheExistingLifetimeAndWholeSpanChecks() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").equals("Linux") && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")));
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).build()) {
            context.initialize("thc"); context.enter();
            try {
                var registry = Language.currentState().getNativeAllocations();
                for (int size : List.of(32, 64)) {
                    var address = registry.malloc(size * 3L); var species = ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(size * 8));
                    var vector = ByteVector.fromArray(species, bytes(size, 47, 129), 0);
                    try {
                        address.writeVectorBytes(1, size, vector, size); assertEquals(vector, address.plus(size * 2L).readVectorBytes(-1, size, size));
                        var actual = new byte[size]; for (int i = 0; i < size; i++) actual[i] = (byte) address.readWord8(size + (long) i); assertArrayEquals(vector.toArray(), actual);
                        assertThrows(RuntimeFault.class, () -> address.writeVectorBytes(2L * size + 1, 1, vector, size));
                        assertThrows(RuntimeFault.class, () -> address.readVectorBytes(Long.MIN_VALUE, size, size));
                        assertThrows(RuntimeFault.class, () -> address.readVectorBytes(Long.MAX_VALUE, size, size)); assertEquals(vector, address.readVectorBytes(1, size, size));
                    } finally { registry.free(address); }
                    assertThrows(RuntimeFault.class, () -> address.readVectorBytes(0, 1, size)); assertThrows(RuntimeFault.class, () -> address.writeVectorBytes(0, 1, vector, size));
                }
                assertEquals(0, registry.liveCount());
            } finally { context.leave(); }
        }
    }
}
