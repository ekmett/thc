// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;

/** An unavailable foreign implementation: no invocation, operand effects or result writes. */
public final class UnsupportedForeignCall extends Expr {
    private final String message;
    private final Metrics metrics;

    public UnsupportedForeignCall(String message, Metrics metrics) {
        this.message = message;
        this.metrics = metrics;
    }

    @Override public Object execute(VirtualFrame frame) { return trap(message, metrics != null ? metrics : ((FunctionRoot) getRootNode()).invocationMetrics(frame)); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) { return trap(message, metrics != null ? metrics : ((FunctionRoot) getRootNode()).invocationMetrics(frame)); }

    @TruffleBoundary(transferToInterpreterOnException = false)
    static Object trap(String message, Metrics metrics) {
        metrics.incrementUnsupportedTraps();
        throw new UnsupportedCore(message);
    }
}
