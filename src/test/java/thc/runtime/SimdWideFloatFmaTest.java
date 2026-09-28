// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.DoubleVector;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SimdWideFloatFmaTest {
    private record Case(List<String> names, CoreRepresentation proof, List<CoreRepresentation> wrongShapes) {}
    @Test void exactThree512BitCarriersAndResultAreRequired() {
        for (var shape : List.of(
                new Case(CoreVectors.INSTANCE.getFusedFloat16(), GeneratedVectors.proofFloatX16,
                    List.of(GeneratedVectors.proofFloatX8, GeneratedVectors.proofInt32X16)),
                new Case(CoreVectors.INSTANCE.getFusedDouble8(), GeneratedVectors.proofDoubleX8,
                    List.of(GeneratedVectors.proofDoubleX4, GeneratedVectors.proofWord64X8)))) {
            var proof = shape.proof;
            for (var name : shape.names) {
                CoreVectors.INSTANCE.validate(name, Collections.nCopies(3, proof), proof);
                for (var wrong : shape.wrongShapes) {
                    for (int index = 0; index <= 2; index++) {
                        int lane = index;
                        assertThrows(RuntimeFault.class, () -> {
                            var arguments = new ArrayList<>(Collections.nCopies(3, proof)); arguments.set(lane, wrong);
                            CoreVectors.INSTANCE.validate(name, arguments, proof);
                        });
                    }
                    assertThrows(RuntimeFault.class, () -> CoreVectors.INSTANCE.validate(name, Collections.nCopies(3, proof), wrong));
                }
                for (int count : List.of(2, 4)) assertThrows(RuntimeFault.class,
                    () -> CoreVectors.INSTANCE.validate(name, Collections.nCopies(count, proof), proof));
                assertThrows(RuntimeFault.class, () -> CoreVectors.INSTANCE.validateFlags(List.of(false, true, false)));
            }
        }
    }

    @Test void wideLanesKeepFusedCancellationAndSignBeforeRounding() {
        float x = Float.intBitsToFloat(0x3f800001), y = Float.intBitsToFloat(0x3f7ffffe);
        assertEquals(0f, x * y - 1f);
        var result = BytecodeRoot.VectorFloat16Fused.apply(0, FloatVector.broadcast(FloatVector.SPECIES_512, x), FloatVector.broadcast(FloatVector.SPECIES_512, y), FloatVector.broadcast(FloatVector.SPECIES_512, -1f));
        var zero = BytecodeRoot.VectorFloat16Fused.apply(2, FloatVector.broadcast(FloatVector.SPECIES_512, 0f), FloatVector.broadcast(FloatVector.SPECIES_512, 0f), FloatVector.broadcast(FloatVector.SPECIES_512, 0f));
        for (int lane = 0; lane < 16; lane++) assertEquals(Float.floatToRawIntBits(-Math.scalb(1f, -46)), Float.floatToRawIntBits(result.lane(lane)));
        for (int lane = 0; lane < 16; lane++) assertEquals(0, Float.floatToRawIntBits(zero.lane(lane)), "Negating the rounded result would give -0");
        double a = Double.longBitsToDouble(0x3ff0000000000001L), b = Double.longBitsToDouble(0x3feffffffffffffeL);
        assertEquals(0.0, a * b - 1.0);
        var doubleResult = BytecodeRoot.VectorDouble8Fused.apply(0, DoubleVector.broadcast(DoubleVector.SPECIES_512, a), DoubleVector.broadcast(DoubleVector.SPECIES_512, b), DoubleVector.broadcast(DoubleVector.SPECIES_512, -1.0));
        var doubleZero = BytecodeRoot.VectorDouble8Fused.apply(2, DoubleVector.broadcast(DoubleVector.SPECIES_512, 0.0), DoubleVector.broadcast(DoubleVector.SPECIES_512, 0.0), DoubleVector.broadcast(DoubleVector.SPECIES_512, 0.0));
        for (int lane = 0; lane < 8; lane++) assertEquals(Double.doubleToRawLongBits(-Math.scalb(1.0, -104)), Double.doubleToRawLongBits(doubleResult.lane(lane)));
        for (int lane = 0; lane < 8; lane++) assertEquals(0L, Double.doubleToRawLongBits(doubleZero.lane(lane)), "Negating the rounded result would give -0");
    }

    @Test void genuine512BitCoreMatchesNativeScalarLanesInBothCompiledBackends() throws Exception {
        var proof = new SimdFloatFmaTest();
        proof.checkGenuineCoreAndNativeLaneBits(false, 16);
        proof.checkGenuineCoreAndNativeLaneBits(true, 8);
    }
}
