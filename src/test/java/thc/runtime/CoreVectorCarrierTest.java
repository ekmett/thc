// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;
import java.util.function.BiFunction;
import jdk.incubator.vector.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoreVectorCarrierTest {
    private <E, V extends Vector<E>> void checkSpecies(List<VectorSpecies<E>> species,
            BiFunction<Object, VectorSpecies<E>, V> require) {
        for (var expected : species) {
            var raw = expected.broadcast(-1L);
            var checked = require.apply(raw, expected);
            assertSame(raw, checked);
            assertEquals(expected.vectorType(), checked.getClass());
            for (var other : species) if (!other.equals(expected))
                assertThrows(RuntimeFault.class, () -> require.apply(raw, other));
            Vector<?> wrongElement = raw instanceof IntVector
                ? LongVector.zero(LongVector.SPECIES_128) : IntVector.zero(IntVector.SPECIES_128);
            for (Object wrong : new Object[]{null, 1L, new Object(), wrongElement})
                assertThrows(RuntimeFault.class, () -> require.apply(wrong, expected));
        }
    }

    @Test void exactCarriersPreserveIdentityAndRejectWrongSpeciesAndElements() {
        checkSpecies(List.of(ByteVector.SPECIES_128, ByteVector.SPECIES_256, ByteVector.SPECIES_512), RuntimeTypes::requireByte);
        checkSpecies(List.of(ShortVector.SPECIES_128, ShortVector.SPECIES_256, ShortVector.SPECIES_512), RuntimeTypes::requireShort);
        checkSpecies(List.of(IntVector.SPECIES_128, IntVector.SPECIES_256, IntVector.SPECIES_512), RuntimeTypes::requireInt);
        checkSpecies(List.of(LongVector.SPECIES_128, LongVector.SPECIES_256, LongVector.SPECIES_512), RuntimeTypes::requireLong);
        checkSpecies(List.of(FloatVector.SPECIES_128, FloatVector.SPECIES_256, FloatVector.SPECIES_512), RuntimeTypes::requireFloat);
        checkSpecies(List.of(DoubleVector.SPECIES_128, DoubleVector.SPECIES_256, DoubleVector.SPECIES_512), RuntimeTypes::requireDouble);
    }
}
