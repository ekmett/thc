// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import jdk.incubator.vector.*;

/** Independent nonuniform payloads for transport and heap tests. */
public final class RawVectorTestValues {
    private RawVectorTestValues() {}
    private static long bits(int index) { return -1L - index * 104729L; }
    @SuppressWarnings("unchecked")
    public static Vector<?> rawVectorTestValue(CoreRepresentation proof) {
        var layout = new VectorLayout(proof); int count = layout.getLanes();
        return switch (proof.getVector().getElement()) {
            case "Int8ElemRep", "Word8ElemRep" -> {
                var values = new byte[count]; for (int i = 0; i < count; i++) values[i] = (byte) bits(i);
                yield ByteVector.fromArray((VectorSpecies<Byte>) layout.getSpecies(), values, 0);
            }
            case "Int16ElemRep", "Word16ElemRep" -> {
                var values = new short[count]; for (int i = 0; i < count; i++) values[i] = (short) bits(i);
                yield ShortVector.fromArray((VectorSpecies<Short>) layout.getSpecies(), values, 0);
            }
            case "Int32ElemRep", "Word32ElemRep" -> {
                var values = new int[count]; for (int i = 0; i < count; i++) values[i] = (int) bits(i);
                yield IntVector.fromArray((VectorSpecies<Integer>) layout.getSpecies(), values, 0);
            }
            case "Int64ElemRep", "Word64ElemRep" -> {
                var values = new long[count]; for (int i = 0; i < count; i++) values[i] = bits(i);
                yield LongVector.fromArray((VectorSpecies<Long>) layout.getSpecies(), values, 0);
            }
            case "FloatElemRep" -> {
                int[] pattern = {Integer.MIN_VALUE, 1, 0x7fc01234, 0x7f800000}; var values = new float[count];
                for (int i = 0; i < count; i++) values[i] = Float.intBitsToFloat(pattern[i % 4]);
                yield FloatVector.fromArray((VectorSpecies<Float>) layout.getSpecies(), values, 0);
            }
            case "DoubleElemRep" -> {
                long[] pattern = {Long.MIN_VALUE, 1L, 0x7ff8000000001234L, 0x7ff0000000000000L}; var values = new double[count];
                for (int i = 0; i < count; i++) values[i] = Double.longBitsToDouble(pattern[i % 4]);
                yield DoubleVector.fromArray((VectorSpecies<Double>) layout.getSpecies(), values, 0);
            }
            default -> throw new IllegalStateException("Unexpected vector proof");
        };
    }
}
