// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.MaterializedFrame;
import java.util.List;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import static thc.runtime.RuntimeServiceStatus.fault;

/** One owned activation; ordinary AST calls neither allocate this nor materialize frames. */
public final class AstContinuation implements SavedGuestContinuation {
    private final GuestRoot sourceRoot;
    private Object yielded;
    private final MaskingState logicalMask;
    private final MaterializedFrame frame;
    private final ArrayDeque<AstResumeStep> steps;
    private final StackAnnotationState annotations;
    private final boolean rootEntrySpill;
    private final ManagedSTM.Transaction transaction;
    private boolean tailSpill;
    private final AtomicBoolean claimed = new AtomicBoolean();
    public AstContinuation(GuestRoot root, Object yielded, MaskingState mask, MaterializedFrame frame,
                           List<AstResumeStep> steps, StackAnnotationState annotations) {
        this(root, yielded, mask, frame, steps, annotations, false, false);
    }
    public AstContinuation(GuestRoot root, Object yielded, MaskingState mask, MaterializedFrame frame,
                           List<AstResumeStep> steps, StackAnnotationState annotations, boolean rootEntrySpill, boolean tailSpill) {
        this.sourceRoot = root; this.yielded = yielded; this.logicalMask = mask; this.frame = frame;
        this.steps = new ArrayDeque<>(steps); this.annotations = annotations; this.rootEntrySpill = rootEntrySpill; this.tailSpill = tailSpill;
        this.transaction = thc.Language.currentState(root).stm.currentTransaction();
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
    @Override @TruffleBoundary public void discard() {
        if (sourceRoot instanceof GhcBCORoot bco) bco.requireOwner();
        if (sourceRoot instanceof FunctionRoot root) root.requireContinuationOwner(frame);
        if (!claimed.compareAndSet(false, true)) return;
        yielded = null;
        AstContinuations.discardSteps(steps);
    }
    @Override @TruffleBoundary public Object continueWith(Object input) {
        if (sourceRoot instanceof GhcBCORoot bco) bco.requireOwner();
        if (sourceRoot instanceof FunctionRoot root) root.requireContinuationOwner(frame);
        if (!claimed.compareAndSet(false, true)) throw fault("AST continuation was already resumed");
        yielded = null;
        MaskingState ambient = SynchronousMasking.current(sourceRoot);
        StackAnnotationState ambientAnnotations = StackAnnotations.current(sourceRoot);
        AstStackScope stack = AstStacks.astStackScope(sourceRoot);
        ManagedSTM stm = thc.Language.currentState(sourceRoot).stm;
        ManagedSTM.Transaction ambientTransaction = stm.currentTransaction();
        stack.setDepth(stack.getDepth() + 1);
        try {
            SynchronousMasking.set(sourceRoot, logicalMask);
            StackAnnotations.set(sourceRoot, annotations);
            // A shared child abandoned by external delivery belongs to a new attempt on demand.
            if (transaction == null || transaction.active()) stm.restore(transaction);
            try { return resumeSteps(input); }
            catch (AstCapture cut) {
                return sourceRoot instanceof FunctionRoot root ? root.finishCapture(cut, frame) : cut.freeze(sourceRoot, frame);
            } catch (DelimitedCut cut) {
                if (sourceRoot instanceof FunctionRoot root) {
                    var last = cut.getFrames().isEmpty() ? null : cut.getFrames().getLast();
                    if (last == null || last.getFrame() != frame ||
                        !(last.getStep() instanceof DelimitedRootStep step) || step.root() != root)
                        cut.append(frame, new DelimitedRootStep(root));
                }
                throw cut;
            }
        } finally {
            stack.setDepth(stack.getDepth() - 1);
            SynchronousMasking.set(sourceRoot, ambient);
            StackAnnotations.set(sourceRoot, ambientAnnotations);
            stm.restore(ambientTransaction);
        }
    }
    private Object resumeSteps(Object input) {
        try { return AstContinuations.resumeAstSteps(frame, steps, input); }
        catch (AstSelfCall self) {
            if (sourceRoot instanceof FunctionRoot root) return root.resumeSelf(frame);
            throw self;
        }
    }
}
