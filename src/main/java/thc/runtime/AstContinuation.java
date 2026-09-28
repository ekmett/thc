// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.MaterializedFrame;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static thc.runtime.RuntimeServiceStatus.fault;

/** One owned activation; ordinary AST calls neither allocate this nor materialize frames. */
public final class AstContinuation implements SavedGuestContinuation {
    private final GuestRoot sourceRoot;
    private final Object yielded;
    private final MaskingState logicalMask;
    private final MaterializedFrame frame;
    private final List<AstResumeStep> steps;
    private final StackAnnotationState annotations;
    private final boolean rootEntrySpill;
    private boolean tailSpill;
    private final AtomicBoolean claimed = new AtomicBoolean();
    public AstContinuation(GuestRoot root, Object yielded, MaskingState mask, MaterializedFrame frame,
                           List<AstResumeStep> steps, StackAnnotationState annotations) {
        this(root, yielded, mask, frame, steps, annotations, false, false);
    }
    public AstContinuation(GuestRoot root, Object yielded, MaskingState mask, MaterializedFrame frame,
                           List<AstResumeStep> steps, StackAnnotationState annotations, boolean rootEntrySpill, boolean tailSpill) {
        this.sourceRoot = root; this.yielded = yielded; this.logicalMask = mask; this.frame = frame;
        this.steps = steps; this.annotations = annotations; this.rootEntrySpill = rootEntrySpill; this.tailSpill = tailSpill;
    }
    @Override public GuestRoot getSourceRoot() { return sourceRoot; }
    @Override public Object getYielded() { return yielded; }
    @Override public Object getIdentity() { return this; }
    public boolean getRootEntrySpill() { return rootEntrySpill; }
    public boolean getTailSpill() { return tailSpill; }
    public void certifyTailEntry() {
        if (!rootEntrySpill || claimed.get()) throw new IllegalStateException("Check failed.");
        tailSpill = true;
    }
    public void rebaseTailBloom(long liveOwners) {
        if (!tailSpill || claimed.get()) throw new IllegalStateException("Check failed.");
        frame.setLong(FrameLayout.BLOOM_FILTER, ((FunctionRoot) sourceRoot).entryBloom(liveOwners));
    }
    @Override @TruffleBoundary public Object continueWith(Object input) {
        if (!claimed.compareAndSet(false, true)) throw fault("AST continuation was already resumed");
        MaskingState ambient = SynchronousMasking.current(sourceRoot);
        StackAnnotationState ambientAnnotations = StackAnnotations.current(sourceRoot);
        AstStackScope stack = AstStacks.astStackScope(sourceRoot);
        stack.setDepth(stack.getDepth() + 1);
        try {
            SynchronousMasking.set(sourceRoot, logicalMask);
            StackAnnotations.set(sourceRoot, annotations);
            try { return AstContinuations.resumeAstSteps(frame, steps, input); }
            catch (AstCapture cut) {
                return sourceRoot instanceof FunctionRoot root ? root.finishCapture(cut, frame) : cut.freeze(sourceRoot, frame);
            }
        } finally {
            stack.setDepth(stack.getDepth() - 1);
            SynchronousMasking.set(sourceRoot, ambient);
            StackAnnotations.set(sourceRoot, ambientAnnotations);
        }
    }
}
