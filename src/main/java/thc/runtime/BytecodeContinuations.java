// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.ContinuationResult;

/** Cold adaptation of a bytecode suspension; ordinary classification stays inline. */
final class BytecodeContinuations {
    private BytecodeContinuations() {}

    @TruffleBoundary
    static SavedGuestContinuation view(ContinuationResult saved) {
        return new BytecodeSavedContinuation(saved);
    }

    /** State-5 storage keeps the original result, not this transient view. */
    static final class BytecodeSavedContinuation implements SavedGuestContinuation {
        private final ContinuationResult saved;

        private BytecodeSavedContinuation(ContinuationResult saved) {
            this.saved = saved;
        }

        @Override public Object getIdentity() { return saved; }
        @Override public Object getYielded() { return saved.getResult(); }
        @Override public Object getSourceRoot() {
            return saved.getContinuationRootNode().getSourceRootNode();
        }
        @Override public Object continueWith(Object input) { return saved.continueWith(input); }
    }
}
