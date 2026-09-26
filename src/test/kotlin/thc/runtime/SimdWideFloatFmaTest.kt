// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

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
        val result = FloatX16Fused.apply(0, FloatX16.broadcast(x), FloatX16.broadcast(y), FloatX16.broadcast(-1f))
        val zero = FloatX16Fused.apply(2, FloatX16.broadcast(0f), FloatX16.broadcast(0f), FloatX16.broadcast(0f))
        val floatLanes = { value: FloatX16 -> listOf(value.lane0,value.lane1,value.lane2,value.lane3,
            value.lane4,value.lane5,value.lane6,value.lane7,value.lane8,value.lane9,value.lane10,value.lane11,
            value.lane12,value.lane13,value.lane14,value.lane15) }
        floatLanes(result).forEach { assertEquals((-Math.scalb(1f,-46)).toRawBits(), it.toRawBits()) }
        floatLanes(zero).forEach { assertEquals(0, it.toRawBits(), "Negating the rounded result would give -0") }
        val a = Double.fromBits(0x3ff0000000000001L)
        val b = Double.fromBits(0x3feffffffffffffeL)
        assertEquals(0.0, a * b - 1.0)
        val doubleResult = DoubleX8Fused.apply(0, DoubleX8.broadcast(a), DoubleX8.broadcast(b), DoubleX8.broadcast(-1.0))
        val doubleZero = DoubleX8Fused.apply(2, DoubleX8.broadcast(0.0), DoubleX8.broadcast(0.0), DoubleX8.broadcast(0.0))
        val doubleLanes = { value: DoubleX8 -> listOf(value.lane0,value.lane1,value.lane2,value.lane3,
            value.lane4,value.lane5,value.lane6,value.lane7) }
        doubleLanes(doubleResult).forEach { assertEquals((-Math.scalb(1.0,-104)).toRawBits(), it.toRawBits()) }
        doubleLanes(doubleZero).forEach { assertEquals(0L, it.toRawBits(), "Negating the rounded result would give -0") }
    }

    @Test fun genuine512BitCoreMatchesNativeScalarLanesInBothCompiledBackends() {
        val proof = SimdFloatFmaTest()
        proof.checkGenuineCoreAndNativeLaneBits(false, 16)
        proof.checkGenuineCoreAndNativeLaneBits(true, 8)
    }
}
