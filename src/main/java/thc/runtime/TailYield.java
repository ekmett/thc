// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.ContinuationResult;

/** Trampoline witness: every caller suffix was discarded before this exact target. */
public final class TailYield {
    private final ContinuationResult continuation;
    private final RootCallTarget target;
    public TailYield(ContinuationResult continuation, RootCallTarget target) {
        if (!(continuation.getContinuationRootNode().getSourceRootNode() instanceof GuestRoot root) || !root.isSelf(target))
            throw new IllegalStateException("Tail continuation does not belong to the final tail target");
        this.continuation = continuation;
        this.target = target;
    }
    public ContinuationResult getContinuation() { return continuation; }
    public RootCallTarget getTarget() { return target; }
}
