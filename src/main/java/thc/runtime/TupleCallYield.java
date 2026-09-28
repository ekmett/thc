// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.exception.AbstractTruffleException;
public final class TupleCallYield extends AbstractTruffleException implements InternalGuestControl {
    private final ContinuationResult continuation;
    private final boolean tail;
    private final RootCallTarget tailTarget;
    public TupleCallYield(ContinuationResult continuation) { this(continuation, false, null); }
    public TupleCallYield(ContinuationResult continuation, boolean tail) { this(continuation, tail, null); }
    public TupleCallYield(ContinuationResult continuation, boolean tail, RootCallTarget tailTarget) {
        super("Internal tuple call suspension", null, 0, null); this.continuation = continuation; this.tail = tail; this.tailTarget = tailTarget;
    }
    public ContinuationResult getContinuation() { return continuation; }
    public boolean getTail() { return tail; }
    public RootCallTarget getTailTarget() { return tailTarget; }
}
