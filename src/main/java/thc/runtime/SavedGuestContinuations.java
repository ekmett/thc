// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.bytecode.ContinuationResult;

public final class SavedGuestContinuations {
    private SavedGuestContinuations() {}
    public static SavedGuestContinuation savedGuestContinuation(Object value) {
        if (value instanceof SavedGuestContinuation saved) return saved;
        if (value instanceof ContinuationResult saved) return BytecodeContinuations.view(saved);
        return null;
    }
    public static AsyncRequest asyncRequest(SavedGuestContinuation saved) { return saved.asyncRequest(); }
}
