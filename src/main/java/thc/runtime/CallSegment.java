// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** A cold, one-shot continuation whose answer may itself remain lazy. */
public final class CallSegment {
    private MaskingState logicalMask;
    private MaskingState callerMask;
    private boolean initialEntry;
    private final TupleShape tupleShape;
    private final boolean caughtIOAction;
    private final boolean tailSpill;
    private volatile int state = 5;
    private Object value;
    private Thread owner;
    private final Object monitor = new Object();

    public CallSegment(Object continuation) { this(continuation, MaskingState.UNMASKED); }
    public CallSegment(Object continuation, MaskingState logicalMask) { this(continuation, logicalMask, MaskingState.UNMASKED); }
    public CallSegment(Object continuation, MaskingState logicalMask, MaskingState callerMask) {
        this(continuation, logicalMask, callerMask, null);
    }
    public CallSegment(Object continuation, MaskingState logicalMask, MaskingState callerMask, TupleShape tupleShape) {
        this(continuation, logicalMask, callerMask, tupleShape, false);
    }
    public CallSegment(Object continuation, MaskingState logicalMask, MaskingState callerMask,
                       TupleShape tupleShape, boolean caughtIOAction) {
        this(continuation, logicalMask, callerMask, tupleShape, caughtIOAction, false);
    }
    public CallSegment(Object continuation, MaskingState logicalMask, MaskingState callerMask,
                       TupleShape tupleShape, boolean caughtIOAction, boolean tailSpill) {
        if (SavedGuestContinuations.savedGuestContinuation(continuation) == null)
            throw new IllegalStateException("Call segment needs a saved continuation");
        value = continuation;
        this.logicalMask = logicalMask;
        this.callerMask = callerMask;
        this.tupleShape = tupleShape;
        this.caughtIOAction = caughtIOAction;
        this.tailSpill = tailSpill;
    }
    /** An unstarted call inherits the extent of its actual first evaluator. */
    static CallSegment initial(SavedGuestContinuation entry, TupleShape shape) {
        var segment = new CallSegment(entry, MaskingState.UNMASKED, MaskingState.UNMASKED, shape);
        segment.initialEntry = true;
        return segment;
    }
    // Caller holds monitor and has won ownership of the initial continuation.
    void enterInitial(MaskingState mask) {
        if (initialEntry) { logicalMask = callerMask = mask; initialEntry = false; }
    }
    public MaskingState getLogicalMask() { return logicalMask; }
    public void setLogicalMask(MaskingState value) { logicalMask = value; }
    public MaskingState getCallerMask() { return callerMask; }
    public TupleShape getTupleShape() { return tupleShape; }
    public boolean getCaughtIOAction() { return caughtIOAction; }
    public boolean getTailSpill() { return tailSpill; }
    public int getState() { return state; }
    public void setState(int state) { this.state = state; }
    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }
    public Thread getOwner() { return owner; }
    public void setOwner(Thread owner) { this.owner = owner; }
    public Object getMonitor() { return monitor; }
}
