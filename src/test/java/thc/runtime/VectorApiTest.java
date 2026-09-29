// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import java.nio.file.*;
import java.util.*;
import jdk.incubator.vector.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.CoreModules;
import thc.Json;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Genuine optimized Haskell loops, with an independent native GHC scalar oracle. */
@SuppressWarnings("unchecked")
class VectorApiTest {
    private static final Path ROOT = Path.of(System.getProperty("thc.projectRoot"));
    private static final Path DATA = ROOT.resolve("build/vector-api");
    private static final String PREFIX = "main:VectorLoops.";

    private static Context context() {
        return Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").build();
    }
    private static Map<String, Object> module() throws Exception {
        var modules = new ArrayList<Map<String, Object>>();
        for (String file : List.of("THC.Prim.json", "VectorLoops.json"))
            modules.add((Map<String, Object>) Json.parse(Files.readString(DATA.resolve("core").resolve(file))));
        return CoreModules.merge(modules);
    }
    private static ExecutableProgram program(String backend, String entry) throws Exception {
        var source = CoreModules.reachable(module(), PREFIX + entry, true);
        source.put("instrument", true);
        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
        return backend.equals("ast") ? new Program(language, source) : new BytecodeProgram(language, source);
    }
    private static Object call(ExecutableProgram program, String entry, Object... arguments) {
        return Calls.target(program.hostEntryTarget(arguments.length), new Object[]{program.entryValue(PREFIX + entry), arguments});
    }
    private static void compile(ExecutableProgram program, String entry) throws Exception {
        RootCallTarget target = program.entryTarget(PREFIX + entry);
        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
    }
    private static List<Number> expected(Map<String, String[]> rows, int lanes, int count, int field) {
        return (List<Number>) Json.parse(rows.get(lanes + ":" + count)[field]);
    }
    private static Map<String, String[]> oracle() throws Exception {
        var rows = new HashMap<String, String[]>();
        for (String line : Files.readAllLines(DATA.resolve("oracle.tsv"))) {
            String[] fields = line.split("\t"); rows.put(fields[0] + ":" + fields[1], fields);
        }
        assertEquals(49, rows.size()); return rows;
    }
    private static byte[] floats(int count, boolean second) {
        byte[] bytes = new byte[count * 4];
        for (int i = 0; i < count; i++) ManagedByteArray.writeFloat(bytes, i, second ? i % 7 + .25f : i * .5f - 5);
        return bytes;
    }
    private static byte[] integers(int count) {
        byte[] bytes = new byte[count * 4];
        for (int i = 0; i < count; i++) ManagedByteArray.writeInt32(bytes, i, i * 7 - 33);
        return bytes;
    }
    private static VectorSpecies<Float> floatSpecies(long width) {
        return width == 0 ? FloatVector.SPECIES_PREFERRED : width == -1 ? FloatVector.SPECIES_MAX : FloatVector.SPECIES_PREFERRED.withShape(VectorShape.forBitSize((int) width));
    }
    private static float[] javaFloating(VectorSpecies<Float> species, int count, boolean squares) {
        float[] a = new float[count], b = new float[count], out = new float[count];
        for (int i = 0; i < count; i++) { a[i] = i * .5f - 5; b[i] = i % 7 + .25f; }
        for (int i = 0; i < count; i += species.length()) {
            var mask = species.indexInRange(i, count);
            var va = FloatVector.fromArray(species, a, i, mask);
            var vb = FloatVector.fromArray(species, b, i, mask);
            (squares ? va.mul(va).add(vb.mul(vb)).neg() : va.mul(vb)).intoArray(out, i, mask);
        }
        return out;
    }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void actualHaskellLoopsMatchNativeResults(String backend) throws Exception {
        var rows = oracle();
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                for (String entry : List.of("multiplyFloat", "negativeSquares", "selectInt32", "reverseBlocksInt32"))
                    for (long width : new long[]{0, -1, 64, 128, 256, 512}) {
                        var program = program(backend, entry);
                        int lanes = floatSpecies(width).length();
                        for (int pass = 0; pass < 2; pass++) {
                            for (int count : new int[]{13, 0, 1, 3, 5, 21, 65}) {
                                byte[] output = new byte[count * 4]; Arrays.fill(output, (byte) 0x55);
                                Object answer;
                                boolean floating = entry.equals("multiplyFloat") || entry.equals("negativeSquares");
                                if (floating) answer = call(program, entry, width, (long) count, floats(count, false), floats(count, true), output, Unit.INSTANCE);
                                else if (entry.equals("selectInt32")) answer = call(program, entry, width, (long) count, 4, 0xa55aL, integers(count), output, Unit.INSTANCE);
                                else answer = call(program, entry, width, (long) count, integers(count), output, Unit.INSTANCE);
                                assertSame(Unit.INSTANCE, answer);
                                int field = switch (entry) { case "multiplyFloat" -> 2; case "negativeSquares" -> 3; case "selectInt32" -> 4; default -> 5; };
                                var want = expected(rows, lanes, count, field);
                                float[] java = floating ? javaFloating(floatSpecies(width), count, entry.equals("negativeSquares")) : null;
                                for (int i = 0; i < count; i++) {
                                    if (floating) assertEquals(want.get(i).floatValue(), ManagedByteArray.readFloat(output, i), entry + "/" + width + "/" + count + "/" + i);
                                    else assertEquals(want.get(i).intValue(), ManagedByteArray.readInt32(output, i), entry + "/" + width + "/" + count + "/" + i);
                                    if (floating) assertEquals(java[i], ManagedByteArray.readFloat(output, i));
                                }
                            }
                            if (pass == 0) compile(program, entry);
                        }
                        assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > 0, entry + " must execute compiled code");
                    }
            } finally { context.leave(); }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void compiledFullBlocksCanEnterAMaskedTail(String backend) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var program = program(backend, "multiplyFloat");
                for (int pass = 0; pass < 2; pass++) {
                    for (int count : pass == 0 ? new int[]{16, 16, 16} : new int[]{16, 13, 1, 16}) {
                        byte[] output = new byte[count * 4];
                        assertSame(Unit.INSTANCE, call(program, "multiplyFloat", 128L, (long) count,
                            floats(count, false), floats(count, true), output, Unit.INSTANCE));
                        float[] want = javaFloating(FloatVector.SPECIES_128, count, false);
                        for (int i = 0; i < count; i++) assertEquals(want[i], ManagedByteArray.readFloat(output, i));
                        if (pass == 1 && count == 13) compile(program, "multiplyFloat");
                    }
                    if (pass == 0) compile(program, "multiplyFloat");
                }
                assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > 0);
            } finally { context.leave(); }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void runtimeSpeciesMasksConversionsAndTupleProperties(String backend) throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var properties = program(backend, "floatSpeciesProperties");
                var count = program(backend, "selectedLaneCount");
                var convert = program(backend, "convertedSum");
                var mutable = program(backend, "sumMutableFloat");
                for (long width : new long[]{0,-1,64,128,256,512}) {
                    var species = floatSpecies(width);
                    Object result = call(properties, "floatSpeciesProperties", width);
                    var shape = ((GuestRoot) properties.entryTarget(PREFIX + "floatSpeciesProperties").getRootNode()).getTupleResult();
                    var fields = TupleResults.ownedTupleResult(result, shape);
                    long[] want = {species.length(), species.elementSize(), species.vectorBitSize(), species.vectorByteSize()};
                    for (int i = 0; i < want.length; i++) assertEquals(want[i], shape.getLayout().getLong(fields, i));
                    for (long bits : new long[]{0, -1, 0xa55a, 1}) {
                        int remaining = Math.max(0, species.length() - 1);
                        long active = bits & ((1L << remaining) - 1);
                        assertEquals((long) Long.bitCount(active), call(count, "selectedLaneCount", width, (long) remaining, bits));
                    }
                    int n = Math.max(1, species.length() - 1);
                    float sum = 0; for (int i = 0; i < n; i++) sum += i * 7 - 33;
                    assertEquals(sum, call(convert, "convertedSum", width, (long) n, integers(n)));
                    Object reduced = call(mutable, "sumMutableFloat", width, 13L, floats(13, false), Unit.INSTANCE);
                    var tuple = ((GuestRoot) mutable.entryTarget(PREFIX + "sumMutableFloat").getRootNode()).getTupleResult();
                    var owned = TupleResults.ownedTupleResult(reduced, tuple);
                    assertEquals(-26.0f, tuple.getLayout().getFloat(owned, 0));
                }
            } finally { context.leave(); }
        }
    }

    @Test void carriersAndMaskedOwnershipUseJdkSemantics() {
        var species = FloatVector.SPECIES_128;
        Object value = VectorApiOp.BROADCAST_FLOAT.execute(new Object[]{species, 2.0f});
        assertInstanceOf(FloatVector.class, value);
        assertSame(species, VectorApiOp.VEC_SPECIES.execute(new Object[]{value}));
        var mask = species.indexInRange(0, 3);
        var owner = ManagedAllocation.mutable(12, 8);
        VectorApiOp.WRITE_FLOAT_VECTOR.execute(new Object[]{owner, 0L, value, mask, Unit.INSTANCE});
        var read = (FloatVector) VectorApiOp.READ_FLOAT_VECTOR.execute(new Object[]{species, owner, 0L, mask, Unit.INSTANCE});
        assertArrayEquals(new float[]{2,2,2,0}, read.toArray());
        assertThrows(RuntimeFault.class, () -> VectorApiOp.READ_FLOAT_VECTOR.execute(new Object[]{species, owner, 0L, species.maskAll(true), Unit.INSTANCE}));
        assertThrows(ClassCastException.class, () -> VectorApiOp.VEC_ADD.execute(new Object[]{value, FloatVector.zero(FloatVector.SPECIES_256)}));
        assertThrows(IllegalArgumentException.class, () -> VectorApiOp.FLOAT_SPECIES.execute(new Object[]{63L}));
        for (VectorSpecies<?> s : List.of(ByteVector.SPECIES_128, ShortVector.SPECIES_128, IntVector.SPECIES_128, LongVector.SPECIES_128, FloatVector.SPECIES_128, DoubleVector.SPECIES_128)) {
            var alternating = VectorMask.fromLong(s, 0xa55a);
            assertEquals((long) alternating.trueCount(), VectorApiOp.MASK_TRUE_COUNT.execute(new Object[]{alternating}));
            assertEquals(alternating.toLong(), VectorApiOp.MASK_TO_BITS.execute(new Object[]{alternating}));
            for (long offset : new long[]{-3, 0, 3, Long.MAX_VALUE})
                assertEquals(s.indexInRange(offset, 5L), VectorApiOp.SPECIES_INDEX_IN_RANGE.execute(new Object[]{s, offset, 5L}));
        }
    }

    @Test void everyLaneFamilyUsesItsJdkCarrierAndScalarWidth() {
        Object[] samples = {3, 300, 70000, 9000000000L, 1.25f, 1.25d};
        String[] lanes = {"INT8", "INT16", "INT32", "INT64", "FLOAT", "DOUBLE"};
        Class<?>[] carriers = {ByteVector.class, ShortVector.class, IntVector.class, LongVector.class, FloatVector.class, DoubleVector.class};
        for (int i = 0; i < lanes.length; i++) {
            String lane = lanes[i];
            Object species = VectorApiOp.valueOf(lane + "_SPECIES").execute(new Object[]{128L});
            Object value = VectorApiOp.valueOf("BROADCAST_" + lane).execute(new Object[]{species, samples[i]});
            assertInstanceOf(carriers[i], value);
            assertEquals(samples[i], VectorApiOp.valueOf("VEC_" + lane + "_LANE").execute(new Object[]{value, 1L}));
            var active = ((VectorSpecies<?>) species).indexInRange(0, 1);
            assertEquals(samples[i], VectorApiOp.valueOf("VEC_" + lane + "_REDUCE_ADD").execute(new Object[]{value, active}));
            byte[] bytes = new byte[16];
            VectorApiOp.valueOf("WRITE_" + lane + "_VECTOR").execute(new Object[]{bytes, 0L, value, active, Unit.INSTANCE});
            Object restored = VectorApiOp.valueOf("READ_" + lane + "_VECTOR").execute(new Object[]{species, bytes, 0L, active, Unit.INSTANCE});
            assertInstanceOf(carriers[i], restored);
            assertEquals(samples[i], VectorApiOp.valueOf("VEC_" + lane + "_LANE").execute(new Object[]{restored, 0L}));
        }
    }

    @Test void inactiveLanesPreservePointersAndRejectedWritesPreserveAllCells() {
        var owner = ManagedAllocation.mutable(32, 8);
        var pointer = ManagedAddress.fromByteArray(new byte[]{7});
        owner.writeAddressByteOffset(0, pointer);
        owner.writeAddressByteOffset(16, pointer);
        var species = FloatVector.SPECIES_256;
        var value = FloatVector.broadcast(species, 2);
        var partialPointer = VectorMask.fromLong(species, 0b010011);
        assertThrows(RuntimeFault.class, () -> VectorApiOp.WRITE_FLOAT_VECTOR.execute(new Object[]{owner, 0L, value, partialPointer, Unit.INSTANCE}));
        assertSame(pointer, owner.readAddressByteOffset(0));
        assertSame(pointer, owner.readAddressByteOffset(16));
        var firstPointer = VectorMask.fromLong(species, 0b000011);
        VectorApiOp.WRITE_FLOAT_VECTOR.execute(new Object[]{owner, 0L, value, firstPointer, Unit.INSTANCE});
        assertSame(pointer, owner.readAddressByteOffset(16));
        assertArrayEquals(new float[]{2,2,0,0,0,0,0,0}, ((FloatVector) VectorApiOp.READ_FLOAT_VECTOR.execute(new Object[]{species, owner, 0L, firstPointer, Unit.INSTANCE})).toArray());
    }
}
