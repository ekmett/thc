// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.MaterializedFrame;
import java.util.List;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import static thc.runtime.RuntimeServiceStatus.fault;

/** One owned activation; ordinary AST calls neither allocate this nor materialize frames. */
public final class AstContinuation implements SavedGuestContinuation {
    private final GuestRoot sourceRoot;
    private Object yielded;
    // All current AST edges from this parent to the same stack cut share this flag.
    // It holds no frame, owner or wait. Replacing any retained edge invalidates it.
    private static final class SpillPath { volatile boolean valid = true; }
    private final SpillPath spillPath;
    private static SpillPath spillPath(Object yielded) {
        if (yielded == AstStackSpill.INSTANCE) return new SpillPath();
        Object child;
        if (yielded instanceof ThunkSuspended cut && cut.getThunk().getState() == 5)
            child = cut.getThunk().getValue();
        else if (yielded instanceof CallSegmentSuspended cut && cut.getSegment().getState() == 5)
            child = cut.getSegment().getValue();
        else return null;
        return child instanceof AstContinuation saved ? saved.spillPath : null;
    }
    boolean hasWaitFreeSpillPath() { return spillPath != null && spillPath.valid; }
    static void invalidateSpillPath(Object previous) {
        if (previous instanceof AstContinuation saved && saved.spillPath != null)
            saved.spillPath.valid = false;
    }
    private final MaskingState logicalMask;
    private final MaterializedFrame frame;
    private Object pending;
    private final boolean childDemand;
    private final StackAnnotationState annotations;
    private final boolean rootEntrySpill;
    private final ManagedSTM.Transaction transaction;
    private boolean tailSpill;
    private static final VarHandle CLAIMED;
    static {
        try { CLAIMED = MethodHandles.lookup().findVarHandle(AstContinuation.class, "claimed", boolean.class); }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private volatile boolean claimed;
    public AstContinuation(GuestRoot root, Object yielded, MaskingState mask, MaterializedFrame frame,
                           List<AstResumeStep> steps, StackAnnotationState annotations) {
        this(root, yielded, mask, frame, steps, annotations, false, false);
    }
    public AstContinuation(GuestRoot root, Object yielded, MaskingState mask, MaterializedFrame frame,
                           List<AstResumeStep> steps, StackAnnotationState annotations, boolean rootEntrySpill, boolean tailSpill) {
        this(root, yielded, mask, frame, new AstCapture.Steps(steps), false, annotations, rootEntrySpill, tailSpill);
    }
    /** Accept pending work from an unpublished capture or a private copy of the supplied list. */
    AstContinuation(GuestRoot root, Object yielded, MaskingState mask, MaterializedFrame frame,
                    Object pending, boolean childDemand, StackAnnotationState annotations, boolean rootEntrySpill, boolean tailSpill) {
        this.sourceRoot = root; this.yielded = yielded; this.logicalMask = mask; this.frame = frame;
        this.spillPath = spillPath(yielded);
        if (pending instanceof AstCapture.Steps copied) {
            if (copied.size() <= 1) pending = copied.peek();
            else if (copied.size() == 2 && AstCapture.isChildDemand(yielded, copied.peek(), copied.peekLast())) {
                pending = copied.peekLast();
                childDemand = true;
            }
        }
        this.pending = pending;
        this.childDemand = childDemand;
        this.annotations = annotations; this.rootEntrySpill = rootEntrySpill; this.tailSpill = tailSpill;
        this.transaction = thc.Language.currentState(root).stm.currentTransaction();
    }
    @Override public GuestRoot getSourceRoot() { return sourceRoot; }
    @Override public Object getYielded() { return yielded; }
    @Override public Object getIdentity() { return this; }
    public boolean getRootEntrySpill() { return rootEntrySpill; }
    public boolean getTailSpill() { return tailSpill; }
    public void certifyTailEntry() {
        if (!rootEntrySpill || claimed) throw new IllegalStateException("Check failed.");
        tailSpill = true;
    }
    public void rebaseTailBloom(long liveOwners) {
        if (!tailSpill || claimed) throw new IllegalStateException("Check failed.");
        frame.setLong(FrameLayout.BLOOM_FILTER, ((FunctionRoot) sourceRoot).entryBloom(liveOwners));
    }
    @Override @TruffleBoundary public void discard() {
        if (sourceRoot instanceof GhcBCORoot bco) bco.requireOwner();
        if (sourceRoot instanceof FunctionRoot root) root.requireContinuationOwner(frame);
        if (!CLAIMED.compareAndSet(this, false, true)) return;
        if (childDemand) pending = AstCapture.expandDemand(yielded, pending);
        yielded = null;
        AstCapture.discardPending(takePending());
    }
    private boolean completedWith(Object value) {
        if (childDemand) return !(value instanceof Thunk) && AstCapture.yieldedChild(yielded).completedWith(value);
        return pending instanceof AstControl.ResumeChild child && child.completedWith(value);
    }
    @Override @TruffleBoundary public Object continueWith(Object input) {
        if (sourceRoot instanceof GhcBCORoot bco) bco.requireOwner();
        if (sourceRoot instanceof FunctionRoot root) root.requireContinuationOwner(frame);
        if (!CLAIMED.compareAndSet(this, false, true)) throw fault("AST continuation was already resumed");
        // A terminal child delivery transfers its checked update. A following
        // demand has no work when the value is already in weak head normal form.
        if (input instanceof ChildResume completed && completed.getFailure() == null && completedWith(completed.getValue())) {
            yielded = pending = null;
            return completed.takeValue();
        }
        if (childDemand) pending = AstCapture.expandDemand(yielded, pending);
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
    private Object takePending() {
        var owned = pending;
        pending = null;
        return owned;
    }
    private Object resumeSteps(Object input) {
        try {
            if (pending instanceof AstCapture.Steps)
                return AstContinuations.resumeAstSteps(frame, (AstCapture.Steps) takePending(), input);
            return pending == null ? input : ((AstResumeStep) takePending()).forInvocation().resume(frame, input);
        } catch (AstSelfCall self) {
            if (sourceRoot instanceof FunctionRoot root) return root.resumeSelf(frame);
            throw self;
        }
    }
}
