// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import jdk.incubator.vector.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CoreVectorCarrierTest {
    private fun <E, V : Vector<E>> checkSpecies(species: List<VectorSpecies<E>>,
        require: (Any?, VectorSpecies<E>) -> V) {
        for (expected in species) {
            val raw = expected.broadcast(-1L)
            val checked = require(raw, expected)
            assertSame(raw, checked)
            assertEquals(expected.vectorType(), checked.javaClass)
            for (other in species.filter { it != expected })
                assertThrows(RuntimeFault::class.java) { require(raw, other) }
            val wrongElement: Vector<*>
            if (raw is IntVector) wrongElement = LongVector.zero(LongVector.SPECIES_128)
            else wrongElement = IntVector.zero(IntVector.SPECIES_128)
            for (wrong in listOf(null, 1L, Any(), wrongElement))
                assertThrows(RuntimeFault::class.java) { require(wrong, expected) }
        }
    }

    @Test fun exactCarriersPreserveIdentityAndRejectWrongSpeciesAndElements() {
        checkSpecies(listOf(ByteVector.SPECIES_128, ByteVector.SPECIES_256, ByteVector.SPECIES_512), CoreVectors::requireByte)
        checkSpecies(listOf(ShortVector.SPECIES_128, ShortVector.SPECIES_256, ShortVector.SPECIES_512), CoreVectors::requireShort)
        checkSpecies(listOf(IntVector.SPECIES_128, IntVector.SPECIES_256, IntVector.SPECIES_512), CoreVectors::requireInt)
        checkSpecies(listOf(LongVector.SPECIES_128, LongVector.SPECIES_256, LongVector.SPECIES_512), CoreVectors::requireLong)
        checkSpecies(listOf(FloatVector.SPECIES_128, FloatVector.SPECIES_256, FloatVector.SPECIES_512), CoreVectors::requireFloat)
        checkSpecies(listOf(DoubleVector.SPECIES_128, DoubleVector.SPECIES_256, DoubleVector.SPECIES_512), CoreVectors::requireDouble)
    }
}
