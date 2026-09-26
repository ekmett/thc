// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.DoubleVector

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SimdWideFloatFmaTest {
    @Test fun exactThree512BitCarriersAndResultAreRequired() {
        for ((names, proof, wrongShapes) in listOf(
            Triple(CoreVectors.fusedFloat16, GeneratedVectors.proofFloatX16,
                listOf(GeneratedVectors.proofFloatX8, GeneratedVectors.proofInt32X16)),
            Triple(CoreVectors.fusedDouble8, GeneratedVectors.proofDoubleX8,
                listOf(GeneratedVectors.proofDoubleX4, GeneratedVectors.proofWord64X8)))) {
            for (name in names) {
                CoreVectors.validate(name, List(3) { proof }, proof)
                for (wrong in wrongShapes) {
                    for (index in 0..2) assertThrows(RuntimeFault::class.java) {
                        CoreVectors.validate(name, MutableList(3) { proof }.also { it[index] = wrong }, proof)
                    }
                    assertThrows(RuntimeFault::class.java) { CoreVectors.validate(name, List(3) { proof }, wrong) }
                }
                for (count in listOf(2, 4)) assertThrows(RuntimeFault::class.java) {
                    CoreVectors.validate(name, List(count) { proof }, proof)
                }
                assertThrows(RuntimeFault::class.java) { CoreVectors.validateFlags(listOf(false, true, false)) }
            }
        }
    }

    @Test fun wideLanesKeepFusedCancellationAndSignBeforeRounding() {
        val x = Float.fromBits(0x3f800001)
        val y = Float.fromBits(0x3f7ffffe)
        assertEquals(0f, x * y - 1f)
        val result = BytecodeRoot.VectorFloat16Fused.apply(0, FloatVector.broadcast(FloatVector.SPECIES_512, x), FloatVector.broadcast(FloatVector.SPECIES_512, y), FloatVector.broadcast(FloatVector.SPECIES_512, -1f))
        val zero = BytecodeRoot.VectorFloat16Fused.apply(2, FloatVector.broadcast(FloatVector.SPECIES_512, 0f), FloatVector.broadcast(FloatVector.SPECIES_512, 0f), FloatVector.broadcast(FloatVector.SPECIES_512, 0f))
        val floatLanes = { value: FloatVector -> listOf(value.lane(0),value.lane(1),value.lane(2),value.lane(3),
            value.lane(4),value.lane(5),value.lane(6),value.lane(7),value.lane(8),value.lane(9),value.lane(10),value.lane(11),
            value.lane(12),value.lane(13),value.lane(14),value.lane(15)) }
        floatLanes(result).forEach { assertEquals((-Math.scalb(1f,-46)).toRawBits(), it.toRawBits()) }
        floatLanes(zero).forEach { assertEquals(0, it.toRawBits(), "Negating the rounded result would give -0") }
        val a = Double.fromBits(0x3ff0000000000001L)
        val b = Double.fromBits(0x3feffffffffffffeL)
        assertEquals(0.0, a * b - 1.0)
        val doubleResult = BytecodeRoot.VectorDouble8Fused.apply(0, DoubleVector.broadcast(DoubleVector.SPECIES_512, a), DoubleVector.broadcast(DoubleVector.SPECIES_512, b), DoubleVector.broadcast(DoubleVector.SPECIES_512, -1.0))
        val doubleZero = BytecodeRoot.VectorDouble8Fused.apply(2, DoubleVector.broadcast(DoubleVector.SPECIES_512, 0.0), DoubleVector.broadcast(DoubleVector.SPECIES_512, 0.0), DoubleVector.broadcast(DoubleVector.SPECIES_512, 0.0))
        val doubleLanes = { value: DoubleVector -> listOf(value.lane(0),value.lane(1),value.lane(2),value.lane(3),
            value.lane(4),value.lane(5),value.lane(6),value.lane(7)) }
        doubleLanes(doubleResult).forEach { assertEquals((-Math.scalb(1.0,-104)).toRawBits(), it.toRawBits()) }
        doubleLanes(doubleZero).forEach { assertEquals(0L, it.toRawBits(), "Negating the rounded result would give -0") }
    }

    @Test fun genuine512BitCoreMatchesNativeScalarLanesInBothCompiledBackends() {
        val proof = SimdFloatFmaTest()
        proof.checkGenuineCoreAndNativeLaneBits(false, 16)
        proof.checkGenuineCoreAndNativeLaneBits(true, 8)
    }
}
