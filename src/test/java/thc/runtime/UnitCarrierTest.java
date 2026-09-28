// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.nodes.UnexpectedResultException;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UnitCarrierTest {
    @Test void theStateCarrierIsOneFinalSingleton() {
        assertTrue(Modifier.isFinal(Unit.class.getModifiers()));
        assertEquals(1, Unit.class.getDeclaredConstructors().length);
        assertTrue(Modifier.isPrivate(Unit.class.getDeclaredConstructors()[0].getModifiers()));
        assertSame(Unit.INSTANCE, RuntimeTypes.asUnit(Unit.INSTANCE));
        assertEquals("Unit", Unit.INSTANCE.toString());
    }

    @Test void typedAndVoidChecksRejectOtherCarriers() throws UnexpectedResultException {
        assertSame(Unit.INSTANCE, RuntimeTypesGen.expectUnit(Unit.INSTANCE));
        TupleResults.requireVoidCarrier(Unit.INSTANCE);
        for (Object invalid : new Object[]{null, new Object(), 0, 0L, false}) {
            assertFalse(RuntimeTypes.isUnit(invalid));
            assertThrows(UnexpectedResultException.class, () -> RuntimeTypesGen.expectUnit(invalid));
            assertThrows(RuntimeFault.class, () -> TupleResults.requireVoidCarrier(invalid));
        }
    }
}
