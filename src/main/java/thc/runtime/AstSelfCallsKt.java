// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import static thc.runtime.RuntimeServiceStatus.fault;

public final class AstSelfCallsKt {
    private AstSelfCallsKt() {}
    /** Class.cast alone would also accept null; the carrier proof does not. */
    public static Object requireReferenceCarrier(Object value, Class<?> carrier) {
        if (!carrier.isInstance(value)) throw fault("Expected proven reference value");
        return carrier.cast(value);
    }
}
