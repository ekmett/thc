// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.bytecode.ContinuationResult;

public final class SavedGuestContinuations {
    // Resolve production carriers before guest compilation, so the first cold
    // suspension cannot invalidate HotSpot's class-hierarchy assumptions.
    private static final Class<?>[] CARRIER_TYPES = {
        AstContinuation.class, AstStackContinuation.class, AstTailAnchor.class, Force.DriverWait.class,
        BytecodeContinuations.BytecodeSavedContinuation.class
    };
    private SavedGuestContinuations() {}
    /** Resolve carrier classes once without constructing or resuming a continuation. */
    public static void initializeCarrierTypes() {}
    public static SavedGuestContinuation savedGuestContinuation(Object value) {
        if (value instanceof SavedGuestContinuation saved) return saved;
        if (value instanceof ContinuationResult saved) return BytecodeContinuations.view(saved);
        return null;
    }
    public static AsyncRequest asyncRequest(SavedGuestContinuation saved) { return saved.asyncRequest(); }
}
