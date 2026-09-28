// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.lang.ref.Reference;

public final class Touch {
    private Touch() {}
    public static Object preserve(Object kept, Object state) {
        TupleResults.requireVoidCarrier(state);
        Reference.reachabilityFence(kept);
        return thc.runtime.Unit.INSTANCE;
    }
}
