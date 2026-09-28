// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;

public final class DelayThread extends Expr {
    @Child private Expr duration, state;
    private final boolean async;
    public DelayThread(Expr duration, Expr state, boolean async, CoreRepresentation proof) {
        this.duration = duration; this.state = state; this.async = async;
        setRepresentation(proof.withEvaluated(true));
    }
    private record Resume(DelayThread node, ThreadDelayToken token) implements AstResumeStep {
        @Override public Object resume(VirtualFrame frame, Object input) {
            if (input != thc.runtime.Unit.INSTANCE) throw RuntimeFault.fault("Invalid delay continuation");
            node.await(token);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    private void await(ThreadDelayToken token) {
        try { token.await(this, async, CompilerDirectives.inCompiledCode()); }
        catch (AsyncBlocked blocked) {
            throw new AstCapture(blocked.getRequest(), SynchronousMasking.current(this)).append(new Resume(this, token));
        }
    }
    @Override public Object execute(VirtualFrame frame) {
        long microseconds = duration.executeRequiredLong(frame);
        TupleResultsKt.requireVoidCarrier(state.execute(frame));
        await(new ThreadDelayToken(GuestThreads.current(this), microseconds));
        return thc.runtime.Unit.INSTANCE;
    }
}
