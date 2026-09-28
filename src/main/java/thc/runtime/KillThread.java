// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;

/** A sender owns one request across an interruptible ACK wait. Only a captured
 * AST caller may begin an external send. */
public final class KillThread extends Expr {
    @Child private Expr identity, payload, state;
    private final boolean captureWait;
    public KillThread(Expr identity, Expr payload, Expr state, boolean captureWait, CoreRepresentation proof) {
        this.identity = identity; this.payload = payload; this.state = state; this.captureWait = captureWait;
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(),
            proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    private record ResumeWait(KillThread node, AsyncRequest sent) implements AstResumeStep {
        @Override public Object resume(VirtualFrame frame, Object input) {
            if (input != kotlin.Unit.INSTANCE) throw RuntimeFault.fault("Invalid killThread# continuation");
            return node.finish(sent);
        }
    }
    private static final class ResumeCompleted implements AstResumeStep {
        @Override public Object resume(VirtualFrame frame, Object input) {
            if (input != kotlin.Unit.INSTANCE) throw RuntimeFault.fault("Invalid completed killThread# continuation");
            return kotlin.Unit.INSTANCE;
        }
    }
    public Object finish(AsyncRequest sent) {
        boolean enteredCompiled = CompilerDirectives.inCompiledCode();
        try { GuestThreadOps.finishKill(this, sent); }
        catch (AsyncBlocked blocked) {
            if (!captureWait) throw RuntimeFault.fault("Nonresumable AST sender blocked after a self-directed killThread#");
            blocked.getRequest().compiledCapture = enteredCompiled;
            throw new AstCapture(blocked.getRequest(), SynchronousMasking.current(this)).append(new ResumeWait(this, sent));
        }
        boolean polledCompiled = CompilerDirectives.inCompiledCode();
        AsyncRequest incoming = GuestThreads.pollCurrent(this, false);
        if (incoming != null) {
            if (sent.getForceSelf()) {
                if (incoming != sent) throw RuntimeFault.fault("Self-directed killThread# claimed a different request");
                incoming.compiledCapture = polledCompiled;
                if (captureWait) throw new AstCapture(incoming, SynchronousMasking.current(this)).append(new ResumeCompleted());
                throw new AsyncDelivery(incoming, this);
            }
            if (incoming == sent) throw RuntimeFault.fault("killThread# claimed its completed outbound request");
            if (!captureWait) throw RuntimeFault.fault("Nonresumable AST sender claimed an external request");
            incoming.compiledCapture = polledCompiled;
            throw new AstCapture(incoming, SynchronousMasking.current(this)).append(new ResumeCompleted());
        }
        if (sent.getForceSelf()) throw RuntimeFault.fault("Self-directed killThread# was not delivered at its guest poll");
        return kotlin.Unit.INSTANCE;
    }
    @Override public Object execute(VirtualFrame frame) {
        Object target = identity.execute(frame);
        Object exception = payload.execute(frame); // The lifted payload remains lazy.
        TupleResultsKt.requireVoidCarrier(state.execute(frame));
        if (!captureWait && target != GuestThreadOps.myThreadId(this))
            throw new UnsupportedCore("AST external killThread# requires a captured sender continuation");
        return finish(GuestThreadOps.beginKill(this, target, exception));
    }
}
