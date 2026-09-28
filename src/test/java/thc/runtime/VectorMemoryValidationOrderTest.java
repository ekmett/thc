// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import jdk.incubator.vector.FloatVector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VectorMemoryValidationOrderTest {
    @Test void floatCarrierIsCheckedBeforeTheDestinationRange() {
        byte[] bytes = new byte[1];
        var wrongShape = FloatVector.zero(FloatVector.SPECIES_64);
        var failure = assertThrows(RuntimeFault.class,
            () -> VectorMemory.writeFloatVectorArray(bytes, Long.MAX_VALUE, wrongShape, false));
        assertEquals("Unexpected vector species", failure.getMessage());
        assertArrayEquals(new byte[1], bytes);
    }

    @Test void readArgumentShapesAreCheckedBeforeAnyRepresentation() {
        var malformed = List.of("var", "array", Map.of("rep", Map.of("kind", "bogus")));
        var app = List.of("app", List.of("prim", "readFloatX4Array#"), List.of(malformed, "not-an-expression"));
        var failure = assertThrows(RuntimeFault.class, () -> CoreVectorMemory.readCase(List.of("case", app), Map.of()));
        assertEquals("Invalid local vector read argument", failure.getMessage());
    }

    @Test void readFlagsAreCheckedBeforeArgumentRepresentations() {
        var malformed = List.of("var", "array", Map.of("rep", Map.of("kind", "bogus")));
        var app = List.of("app", List.of("prim", "readFloatX4Array#"), List.of(malformed));
        var failure = assertThrows(RuntimeFault.class, () -> CoreVectorMemory.readCase(List.of("case", app), Map.of()));
        assertEquals("Missing local vector read flags", failure.getMessage());
    }

    @Test void allFlagsAreCheckedBeforeAnyArgumentProof() {
        var failure = assertThrows(RuntimeFault.class, () -> VectorMemoryOp.INDEX_FLOAT.validateArguments(
            Arrays.asList(null, CoreVectorMemory.indexProof), List.of(false, true)));
        assertEquals("Vector memory primitive argument representation mismatch: indexFloatX4Array#", failure.getMessage());
    }
}
