// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import static thc.runtime.RuntimeServiceStatus.fault;
import static thc.runtime.TupleResults.requireVoidCarrier;
public final class RaiseException extends Expr {
    @Child private Expr exception;
    private final boolean someException;
    public RaiseException(Expr exception) { this(exception, false); }
    public RaiseException(Expr exception, boolean someException) {
        this.exception = exception; this.someException = someException;
        setRepresentation(new CoreRepresentation(CoreKind.UNKNOWN, true, false, null, null, null, null, null, null));
    }
    @Override public Object execute(VirtualFrame frame) {
        Object payload;
        try { payload = exception.execute(frame); }
        catch (AstCapture cut) { throw cut.append((saved, input) -> { throw raise(input, this, someException); }); }
        throw raise(payload, this, someException);
    }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) { return execute(frame); }
    // A guest raise is expected control flow, not a failed caller speculation.
    @CompilerDirectives.TruffleBoundary(transferToInterpreterOnException = false) private static GuestException raise(Object payload, Node location, boolean someException) {
        throw new GuestException(payload, location, someException);
    }
}
