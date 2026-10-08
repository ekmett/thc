// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.MaterializedFrame;
import java.util.concurrent.atomic.AtomicBoolean;
import thc.runtime.Unit;

/** One retained function catcher around an already consumed prefix's saved suffix.
 * It does not create a new function invocation or restart the body to find its PC. */
final class AstTailAnchor implements SavedGuestContinuation {
    private final FunctionRoot root;
    private final MaterializedFrame frame;
    private final SavedGuestContinuation child;
    private final MaskingState mask;
    private final StackAnnotationState annotations;
    private final AtomicBoolean claimed = new AtomicBoolean();
    private AstTailAnchor previous;
    private long liveBloom;

    AstTailAnchor(FunctionRoot root, MaterializedFrame frame, SavedGuestContinuation child,
                  MaskingState mask, StackAnnotationState annotations) {
        this.root = root;
        this.frame = frame;
        this.child = child;
        this.mask = mask;
        this.annotations = annotations;
    }

    @Override public Object getIdentity() { return this; }
    @Override public Object getSourceRoot() { return root; }
    @Override public Object getYielded() { return AstStackSpill.INSTANCE; }

    static boolean accepts(AstTailAnchor anchor, TailCall transfer) {
        return anchor != null && anchor.root.isSelf(transfer.getTarget());
    }

    long liveBloom() { return liveBloom; }

    @Override @TruffleBoundary public Object continueWith(Object input) {
        if (input != Unit.INSTANCE || !claimed.compareAndSet(false, true))
            throw new IllegalStateException("Tail anchor requires one Unit resume");
        AstStackScope scope = AstStacks.astStackScope(root);
        MaskingState ambient = SynchronousMasking.INSTANCE.current(root);
        StackAnnotationState ambientAnnotations = StackAnnotations.current(root);
        previous = scope.getTailAnchor();
        // This anchor owns one tail segment. An outer anchor across a non-tail
        // call is a return boundary, not another eligible catcher in this segment.
        // Never subtract hashed bits: rebuild from the exact surviving owner.
        liveBloom = root.mask;
        frame.setLong(FrameLayout.BLOOM_FILTER, liveBloom);
        scope.setTailAnchor(this);
        scope.setDepth(scope.getDepth() + 1);
        scope.setTailAnchors(scope.getTailAnchors() + 1);
        SynchronousMasking.INSTANCE.set(root, mask);
        StackAnnotations.set(root, annotations);
        SavedGuestContinuation pending = child;
        TailCall transfer = null;
        try {
            while (true) {
                try {
                    Object result;
                    if (pending != null) {
                        result = root.drainTailChild(frame, pending);
                    } else {
                        result = root.restartTailAnchor(frame, transfer);
                    }
                    // This first tranche never hides a scheduling request behind an internal spill.
                    SavedGuestContinuation next = SavedGuestContinuations.savedGuestContinuation(result);
                    if (next != null)
                        throw new IllegalStateException("Synchronous tail anchor returned a non-internal suspension");
                    return result;
                } catch (TailCall tail) {
                    if (!root.isSelf(tail.getTarget())) throw tail;
                    pending = null;
                    transfer = tail;
                } catch (AstCapture cut) {
                    // The body was entered only after a real matching transfer. Save its new
                    // suffix; do not replay it or build an ever-growing stack of loop anchors.
                    AstPendingTail direct = cut.pendingTail();
                    pending = direct == null ? cut.freeze(root, frame, false) : direct.child;
                }
            }
        } finally {
            scope.setDepth(scope.getDepth() - 1);
            scope.setTailAnchor(previous);
            previous = null;
            SynchronousMasking.INSTANCE.set(root, ambient);
            StackAnnotations.set(root, ambientAnnotations);
        }
    }
}
