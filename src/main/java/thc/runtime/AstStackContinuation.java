// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import java.util.concurrent.atomic.AtomicBoolean;
import static thc.runtime.RuntimeServiceStatus.fault;

public final class AstStackContinuation implements SavedGuestContinuation {
    private final Object sourceRoot;
    private CallSegmentSuspended yielded;
    private final AtomicBoolean claimed = new AtomicBoolean();
    public AstStackContinuation(Object sourceRoot, CallSegmentSuspended yielded) {
        this.sourceRoot = sourceRoot; this.yielded = yielded;
    }
    @Override public Object getSourceRoot() { return sourceRoot; }
    @Override public CallSegmentSuspended getYielded() { return yielded; }
    @Override public Object getIdentity() { return this; }
    @Override @TruffleBoundary public Object continueWith(Object input) {
        if (sourceRoot instanceof GhcBCORoot bco) bco.requireOwner();
        if (!claimed.compareAndSet(false, true)) throw fault("AST stack continuation was already resumed");
        CallSegmentSuspended suspendedChild = yielded;
        yielded = null;
        if (input instanceof TailCall tail && AstTailAnchor.accepts(AstStacks.astStackScope((Node) sourceRoot).getTailAnchor(), tail)) throw tail;
        if (input instanceof AstChildSuspension suspended) {
            if (suspended.getChild() != suspendedChild.getSegment()) throw fault("AST stack continuation received an unrelated child cut");
            return new AstStackContinuation(sourceRoot, new CallSegmentSuspended(suspendedChild.getSegment(), null, suspended.getRequest(), false));
        }
        if (!(input instanceof ChildResume resumed)) throw fault("AST stack continuation requires ChildResume");
        if (resumed.getFailure() != null) throw resumed.takeFailure();
        CallSegment segment = suspendedChild.getSegment();
        if (segment.getState() != 2 || segment.getValue() != resumed.getValue())
            throw fault("AST stack continuation lost its completed segment");
        return resumed.takeValue();
    }
}
