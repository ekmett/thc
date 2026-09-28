// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.*;

/** Exact vector proof metadata and validation. Runtime species checks live in RuntimeTypes. */
public final class CoreVectors {
    private CoreVectors() {}

    private static Object at(List<?> values, int index) {
        return index < values.size() ? values.get(index) : null;
    }
    /** GHC's shuffle instruction requires a literal tuple of concatenated lane indices. */
    public static int[] shuffleIndices(List<?> raw, int lanes) {
        List<?> constructor = at(raw, 1) instanceof List<?> found ? found : null;
        List<?> fields = at(raw, 2) instanceof List<?> found ? found : null;
        if (!"app".equals(at(raw, 0)) || constructor == null || !"con".equals(at(constructor, 0)) ||
                !Boolean.TRUE.equals(at(raw, 5)) || fields == null || fields.size() != lanes)
            throw new UnsupportedCore("Vector shuffle requires a literal index tuple");
        int[] result = new int[lanes];
        for (int lane = 0; lane < lanes; lane++) {
            List<?> literal = fields.get(lane) instanceof List<?> found ? found : null;
            Long value = null;
            if (literal != null && "lit".equals(at(literal, 0)) && "int".equals(at(literal, 1)) &&
                    at(literal, 2) instanceof String text) {
                try { value = Long.parseLong(text); } catch (NumberFormatException ignored) {}
            }
            if (value == null || value < 0L || value >= 2L * lanes)
                throw new UnsupportedCore("Vector shuffle indices must be literals in 0 until " + (2 * lanes));
            result[lane] = value.intValue();
        }
        return result;
    }
    public static final CoreRepresentation proof = new CoreRepresentation(CoreKind.VECTOR, true, true, List.of("VecRep 2 Int64ElemRep"), null, CoreVector.INT64X2, null, null, null);
    private static final CoreRepresentation lane = new CoreRepresentation(CoreKind.LONG, true, true, List.of("Int64Rep"), null, null, null, null, null);
    public static final CoreRepresentation unpacked = new CoreRepresentation(CoreKind.UNKNOWN, true, true, List.of("Int64Rep", "Int64Rep"), List.of(lane, lane), null, null, null, null);
    public static final CoreRepresentation proof32 = new CoreRepresentation(CoreKind.VECTOR, true, true, List.of("VecRep 4 Int32ElemRep"), null, CoreVector.INT32X4, null, null, null);
    private static final CoreRepresentation lane32 = new CoreRepresentation(CoreKind.LONG, true, true, List.of("Int32Rep"), null, null, null, null, null);
    public static final CoreRepresentation unpacked32 = new CoreRepresentation(CoreKind.UNKNOWN, true, true, Collections.nCopies(4, "Int32Rep"), Collections.nCopies(4, lane32), null, null, null, null);
    public static final CoreRepresentation proof16 = new CoreRepresentation(CoreKind.VECTOR, true, true, List.of("VecRep 8 Int16ElemRep"), null, CoreVector.INT16X8, null, null, null);
    private static final CoreRepresentation lane16 = new CoreRepresentation(CoreKind.LONG, true, true, List.of("Int16Rep"), null, null, null, null, null);
    public static final CoreRepresentation unpacked16 = new CoreRepresentation(CoreKind.UNKNOWN, true, true, Collections.nCopies(8, "Int16Rep"), Collections.nCopies(8, lane16), null, null, null, null);
    public static final Set<String> operations16 = new LinkedHashSet<>(List.of("packInt16X8#", "unpackInt16X8#", "broadcastInt16X8#", "plusInt16X8#", "minusInt16X8#", "negateInt16X8#", "timesInt16X8#"));
    public static final CoreRepresentation proof8 = new CoreRepresentation(CoreKind.VECTOR, true, true, List.of("VecRep 16 Int8ElemRep"), null, CoreVector.INT8X16, null, null, null);
    private static final CoreRepresentation lane8 = new CoreRepresentation(CoreKind.LONG, true, true, List.of("Int8Rep"), null, null, null, null, null);
    public static final CoreRepresentation unpacked8 = new CoreRepresentation(CoreKind.UNKNOWN, true, true, Collections.nCopies(16, "Int8Rep"), Collections.nCopies(16, lane8), null, null, null, null);
    public static final Set<String> operations8 = new LinkedHashSet<>(List.of("packInt8X16#", "unpackInt8X16#", "broadcastInt8X16#", "plusInt8X16#", "minusInt8X16#", "negateInt8X16#", "timesInt8X16#"));
    public static final CoreRepresentation proofWord8 = new CoreRepresentation(CoreKind.VECTOR, true, true, List.of("VecRep 16 Word8ElemRep"), null, CoreVector.WORD8X16, null, null, null);
    private static final CoreRepresentation laneWord8 = new CoreRepresentation(CoreKind.LONG, true, true, List.of("Word8Rep"), null, null, null, null, null);
    public static final CoreRepresentation unpackedWord8 = new CoreRepresentation(CoreKind.UNKNOWN, true, true, Collections.nCopies(16, "Word8Rep"), Collections.nCopies(16, laneWord8), null, null, null, null);
    public static final Set<String> operationsWord8 = new LinkedHashSet<>(List.of("packWord8X16#", "unpackWord8X16#", "broadcastWord8X16#", "plusWord8X16#", "minusWord8X16#", "timesWord8X16#"));
    public static final CoreRepresentation proofWord16 = new CoreRepresentation(CoreKind.VECTOR, true, true, List.of("VecRep 8 Word16ElemRep"), null, CoreVector.WORD16X8, null, null, null);
    private static final CoreRepresentation laneWord16 = new CoreRepresentation(CoreKind.LONG, true, true, List.of("Word16Rep"), null, null, null, null, null);
    public static final CoreRepresentation unpackedWord16 = new CoreRepresentation(CoreKind.UNKNOWN, true, true, Collections.nCopies(8, "Word16Rep"), Collections.nCopies(8, laneWord16), null, null, null, null);
    public static final Set<String> operationsWord16 = new LinkedHashSet<>(List.of("packWord16X8#", "unpackWord16X8#", "broadcastWord16X8#", "plusWord16X8#", "minusWord16X8#", "timesWord16X8#"));
    public static final CoreRepresentation proofWord32 = new CoreRepresentation(CoreKind.VECTOR, true, true, List.of("VecRep 4 Word32ElemRep"), null, CoreVector.WORD32X4, null, null, null);
    private static final CoreRepresentation laneWord32 = new CoreRepresentation(CoreKind.LONG, true, true, List.of("Word32Rep"), null, null, null, null, null);
    public static final CoreRepresentation unpackedWord32 = new CoreRepresentation(CoreKind.UNKNOWN, true, true, Collections.nCopies(4, "Word32Rep"), Collections.nCopies(4, laneWord32), null, null, null, null);
    public static final Set<String> operationsWord32 = new LinkedHashSet<>(List.of("packWord32X4#", "unpackWord32X4#", "broadcastWord32X4#", "plusWord32X4#", "minusWord32X4#", "timesWord32X4#"));
    public static final CoreRepresentation proofFloat = new CoreRepresentation(CoreKind.VECTOR, true, true, List.of("VecRep 4 FloatElemRep"), null, CoreVector.FLOATX4, null, null, null);
    private static final CoreRepresentation laneFloat = new CoreRepresentation(CoreKind.FLOAT, true, true, List.of("FloatRep"), null, null, null, null, null);
    public static final CoreRepresentation unpackedFloat = new CoreRepresentation(CoreKind.UNKNOWN, true, true, Collections.nCopies(4, "FloatRep"), Collections.nCopies(4, laneFloat), null, null, null, null);
    public static final List<String> fusedFloat = List.of("fmaddFloatX4#", "fmsubFloatX4#", "fnmaddFloatX4#", "fnmsubFloatX4#");
    public static final List<String> fusedFloat8 = List.of("fmaddFloatX8#", "fmsubFloatX8#", "fnmaddFloatX8#", "fnmsubFloatX8#");
    public static final List<String> fusedFloat16 = List.of("fmaddFloatX16#", "fmsubFloatX16#", "fnmaddFloatX16#", "fnmsubFloatX16#");
    public static final Set<String> operationsFloat = operations(new LinkedHashSet<>(List.of("packFloatX4#", "unpackFloatX4#", "broadcastFloatX4#", "plusFloatX4#", "minusFloatX4#", "timesFloatX4#")), fusedFloat);
    public static final CoreRepresentation proofDouble = new CoreRepresentation(CoreKind.VECTOR, true, true, List.of("VecRep 2 DoubleElemRep"), null, CoreVector.DOUBLEX2, null, null, null);
    private static final CoreRepresentation laneDouble = new CoreRepresentation(CoreKind.DOUBLE, true, true, List.of("DoubleRep"), null, null, null, null, null);
    public static final CoreRepresentation unpackedDouble = new CoreRepresentation(CoreKind.UNKNOWN, true, true, Collections.nCopies(2, "DoubleRep"), Collections.nCopies(2, laneDouble), null, null, null, null);
    public static final List<String> fusedDouble = List.of("fmaddDoubleX2#", "fmsubDoubleX2#", "fnmaddDoubleX2#", "fnmsubDoubleX2#");
    public static final List<String> fusedDouble4 = List.of("fmaddDoubleX4#", "fmsubDoubleX4#", "fnmaddDoubleX4#", "fnmsubDoubleX4#");
    public static final List<String> fusedDouble8 = List.of("fmaddDoubleX8#", "fmsubDoubleX8#", "fnmaddDoubleX8#", "fnmsubDoubleX8#");
    public static final Set<String> operationsDouble = operations(new LinkedHashSet<>(List.of("packDoubleX2#", "unpackDoubleX2#", "broadcastDoubleX2#", "plusDoubleX2#", "minusDoubleX2#", "timesDoubleX2#")), fusedDouble);
    public static final Set<String> operations32 = new LinkedHashSet<>(List.of("packInt32X4#", "unpackInt32X4#", "broadcastInt32X4#", "plusInt32X4#", "minusInt32X4#", "negateInt32X4#", "timesInt32X4#"));
    public static final Set<String> operations = operations(new LinkedHashSet<>(List.of("packInt64X2#", "unpackInt64X2#", "broadcastInt64X2#", "plusInt64X2#", "minusInt64X2#", "negateInt64X2#")), operations32, operations16, operations8, operationsWord8, operationsWord16, operationsWord32, operationsFloat, operationsDouble, GeneratedVectors.operations, fusedFloat8, fusedFloat16, fusedDouble4, fusedDouble8);

    @SafeVarargs private static Set<String> operations(Collection<String>... groups) {
        Set<String> result = new LinkedHashSet<>();
        for (var group : groups) result.addAll(group);
        return result;
    }
    public static void requireVariableProof(CoreRepresentation binding, CoreRepresentation occurrence) {
        if (occurrence.isVector() && !Objects.equals(binding == null ? null : binding.getVector(), occurrence.getVector()))
            throw new RuntimeFault("Vector occurrence lacks a matching lexical binder proof");
    }
    public static CoreRepresentation caseResult(List<CoreRepresentation> alternatives) {
        CoreRepresentation vector = null;
        for (var alternative : alternatives) if (alternative.isVector()) { vector = alternative; break; }
        if (vector == null) return null;
        for (var alternative : alternatives) if (!Objects.equals(alternative.getVector(), vector.getVector()))
            throw new RuntimeFault("Conflicting vector case result representations");
        boolean evaluated = true;
        for (var alternative : alternatives) if (!alternative.getEvaluated()) { evaluated = false; break; }
        return new CoreRepresentation(vector.getKind(), evaluated, vector.getPresent(), vector.getPrimReps(),
            vector.getComponents(), vector.getVector(), vector.getAlternatives(), vector.getTagSlot(), vector.getAlternativeSlots());
    }
    private static boolean exact(CoreRepresentation expected, CoreRepresentation actual) {
        if (!actual.getPresent() || expected.getKind() != actual.getKind() ||
                !Objects.equals(expected.getVector(), actual.getVector()) || !TupleShape.compatible(expected, actual)) return false;
        if (expected.getComponents() != null) {
            for (int i = 0; i < expected.getComponents().size(); i++)
                if (!exact(expected.getComponents().get(i), Objects.requireNonNull(actual.getComponents()).get(i))) return false;
        }
        return true;
    }
    public static void validate(String name, List<CoreRepresentation> arguments, CoreRepresentation result) {
        if (GeneratedVectors.operations.contains(name)) { GeneratedVectors.validate(name, arguments, result); return; }
        List<CoreRepresentation> expected;
        if ("packInt64X2#".equals(name)) expected = List.of(unpacked);
        else if ("unpackInt64X2#".equals(name) || "negateInt64X2#".equals(name)) expected = List.of(proof);
        else if ("broadcastInt64X2#".equals(name)) expected = List.of(lane);
        else if ("plusInt64X2#".equals(name) || "minusInt64X2#".equals(name)) expected = List.of(proof, proof);
        else if ("packInt32X4#".equals(name)) expected = List.of(unpacked32);
        else if ("unpackInt32X4#".equals(name) || "negateInt32X4#".equals(name)) expected = List.of(proof32);
        else if ("broadcastInt32X4#".equals(name)) expected = List.of(lane32);
        else if ("plusInt32X4#".equals(name) || "minusInt32X4#".equals(name) || "timesInt32X4#".equals(name)) expected = List.of(proof32, proof32);
        else if ("packInt16X8#".equals(name)) expected = List.of(unpacked16);
        else if ("unpackInt16X8#".equals(name) || "negateInt16X8#".equals(name)) expected = List.of(proof16);
        else if ("broadcastInt16X8#".equals(name)) expected = List.of(lane16);
        else if ("plusInt16X8#".equals(name) || "minusInt16X8#".equals(name) || "timesInt16X8#".equals(name)) expected = List.of(proof16, proof16);
        else if ("packInt8X16#".equals(name)) expected = List.of(unpacked8);
        else if ("unpackInt8X16#".equals(name) || "negateInt8X16#".equals(name)) expected = List.of(proof8);
        else if ("broadcastInt8X16#".equals(name)) expected = List.of(lane8);
        else if ("plusInt8X16#".equals(name) || "minusInt8X16#".equals(name) || "timesInt8X16#".equals(name)) expected = List.of(proof8, proof8);
        else if ("packWord8X16#".equals(name)) expected = List.of(unpackedWord8);
        else if ("unpackWord8X16#".equals(name)) expected = List.of(proofWord8);
        else if ("broadcastWord8X16#".equals(name)) expected = List.of(laneWord8);
        else if ("plusWord8X16#".equals(name) || "minusWord8X16#".equals(name) || "timesWord8X16#".equals(name)) expected = List.of(proofWord8, proofWord8);
        else if ("packWord16X8#".equals(name)) expected = List.of(unpackedWord16);
        else if ("unpackWord16X8#".equals(name)) expected = List.of(proofWord16);
        else if ("broadcastWord16X8#".equals(name)) expected = List.of(laneWord16);
        else if ("plusWord16X8#".equals(name) || "minusWord16X8#".equals(name) || "timesWord16X8#".equals(name)) expected = List.of(proofWord16, proofWord16);
        else if ("packWord32X4#".equals(name)) expected = List.of(unpackedWord32);
        else if ("unpackWord32X4#".equals(name)) expected = List.of(proofWord32);
        else if ("broadcastWord32X4#".equals(name)) expected = List.of(laneWord32);
        else if ("plusWord32X4#".equals(name) || "minusWord32X4#".equals(name) || "timesWord32X4#".equals(name)) expected = List.of(proofWord32, proofWord32);
        else if ("packDoubleX2#".equals(name)) expected = List.of(unpackedDouble);
        else if ("unpackDoubleX2#".equals(name)) expected = List.of(proofDouble);
        else if ("broadcastDoubleX2#".equals(name)) expected = List.of(laneDouble);
        else if ("plusDoubleX2#".equals(name) || "minusDoubleX2#".equals(name) || "timesDoubleX2#".equals(name)) expected = List.of(proofDouble, proofDouble);
        else if ("packFloatX4#".equals(name)) expected = List.of(unpackedFloat);
        else if ("unpackFloatX4#".equals(name)) expected = List.of(proofFloat);
        else if ("broadcastFloatX4#".equals(name)) expected = List.of(laneFloat);
        else if ("plusFloatX4#".equals(name) || "minusFloatX4#".equals(name) || "timesFloatX4#".equals(name)) expected = List.of(proofFloat, proofFloat);
        else if (fusedFloat8.contains(name)) expected = Collections.nCopies(3, GeneratedVectors.proofFloatX8);
        else if (fusedFloat16.contains(name)) expected = Collections.nCopies(3, GeneratedVectors.proofFloatX16);
        else if (fusedDouble4.contains(name)) expected = Collections.nCopies(3, GeneratedVectors.proofDoubleX4);
        else if (fusedDouble8.contains(name)) expected = Collections.nCopies(3, GeneratedVectors.proofDoubleX8);
        else if (fusedFloat.contains(name)) expected = List.of(proofFloat, proofFloat, proofFloat);
        else if (fusedDouble.contains(name)) expected = List.of(proofDouble, proofDouble, proofDouble);
        else throw new UnsupportedCore("Unsupported vector primitive " + name);
        CoreRepresentation expectedResult;
        if ("unpackInt64X2#".equals(name)) expectedResult = unpacked;
        else if ("unpackInt32X4#".equals(name)) expectedResult = unpacked32;
        else if ("unpackInt16X8#".equals(name)) expectedResult = unpacked16;
        else if (operations16.contains(name)) expectedResult = proof16;
        else if ("unpackInt8X16#".equals(name)) expectedResult = unpacked8;
        else if (operations8.contains(name)) expectedResult = proof8;
        else if ("unpackWord8X16#".equals(name)) expectedResult = unpackedWord8;
        else if (operationsWord8.contains(name)) expectedResult = proofWord8;
        else if ("unpackWord16X8#".equals(name)) expectedResult = unpackedWord16;
        else if (operationsWord16.contains(name)) expectedResult = proofWord16;
        else if ("unpackWord32X4#".equals(name)) expectedResult = unpackedWord32;
        else if (operationsWord32.contains(name)) expectedResult = proofWord32;
        else if ("unpackDoubleX2#".equals(name)) expectedResult = unpackedDouble;
        else if (operationsDouble.contains(name)) expectedResult = proofDouble;
        else if ("unpackFloatX4#".equals(name)) expectedResult = unpackedFloat;
        else if (fusedFloat8.contains(name)) expectedResult = GeneratedVectors.proofFloatX8;
        else if (fusedFloat16.contains(name)) expectedResult = GeneratedVectors.proofFloatX16;
        else if (fusedDouble4.contains(name)) expectedResult = GeneratedVectors.proofDoubleX4;
        else if (fusedDouble8.contains(name)) expectedResult = GeneratedVectors.proofDoubleX8;
        else if (operationsFloat.contains(name)) expectedResult = proofFloat;
        else if (operations32.contains(name)) expectedResult = proof32;
        else expectedResult = proof;
        validateSignature(name, arguments, result, expected, expectedResult);
    }
    public static void validateSignature(String name, List<CoreRepresentation> arguments, CoreRepresentation result,
            List<CoreRepresentation> expected, CoreRepresentation expectedResult) {
        if (arguments.size() != expected.size()) throw new RuntimeFault("Vector primitive arity mismatch: " + name);
        for (int i = 0; i < arguments.size(); i++)
            if (!exact(expected.get(i), arguments.get(i)))
                throw new RuntimeFault("Vector primitive argument representation mismatch: " + name + " argument " + i);
        if (!exact(expectedResult, result)) throw new RuntimeFault("Vector primitive result representation mismatch: " + name);
    }
    public static void validateFlags(List<?> flags) {
        for (Object flag : flags) if (!Boolean.FALSE.equals(flag)) throw new RuntimeFault("Vector primitive operands must be unlifted");
    }
    public static CoreRepresentation argumentProof(List<Object> expression) {
        if ("lit".equals(at(expression, 0)) && Arrays.asList("int8", "word8", "int16", "word16", "int32", "word32").contains(at(expression, 1)))
            return CoreRepresentations.narrowLiteralProof(expression);
        return CoreRepresentations.expression(expression);
    }
}
