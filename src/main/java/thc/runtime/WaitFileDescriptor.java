// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import java.nio.channels.ClosedChannelException;
import thc.Language;

/** One logical token survives a captured async cut; a new poll request does not. */
public final class WaitFileDescriptor extends Expr {
    @Child private Expr fd;
    @Child private Expr state;
    @Child private Expr payload;
    private final boolean writing;
    private final boolean async;
    public WaitFileDescriptor(Expr fd, Expr state, GlobalBinding payload, boolean writing, boolean async, CoreRepresentation proof) {
        this(fd, state, new GlobalRead(payload), writing, async, proof);
    }
    public WaitFileDescriptor(Expr fd, Expr state, Expr payload, boolean writing, boolean async, CoreRepresentation proof) {
        this.fd = fd;
        this.state = state;
        this.payload = payload;
        this.writing = writing;
        this.async = async;
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(),
            proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    private record Resume(WaitFileDescriptor node, ManagedFiles.WaitToken token) implements AstResumeStep {
        @Override public Object resume(VirtualFrame frame, Object input) {
            if (input != thc.runtime.Unit.INSTANCE) throw RuntimeFault.fault("Invalid descriptor-wait resume value");
            node.await(frame, token);
            return thc.runtime.Unit.INSTANCE;
        }
    }
    private void await(VirtualFrame frame, ManagedFiles.WaitToken token) {
        try { token.await(this, async, CompilerDirectives.inCompiledCode()); }
        catch (AsyncBlocked blocked) {
            throw new AstCapture(blocked.getRequest(), SynchronousMasking.current(this)).append(new Resume(this, token));
        } catch (Throwable failure) {
            if (failure instanceof ClosedChannelException) {
                Object value;
                try { value = payload.execute(frame); }
                catch (AstCapture cut) { throw cut.append((saved, input) -> { throw new GuestException(input, this); }); }
                throw new GuestException(value, this);
            }
            throw FileWaitPrimitives.propagate(failure);
        }
    }
    @Override public Object execute(VirtualFrame frame) {
        long descriptor = fd.executeRequiredLong(frame);
        TupleResults.requireVoidCarrier(state.execute(frame));
        await(frame, Language.currentState(this).getFiles().waitToken(descriptor, writing));
        return thc.runtime.Unit.INSTANCE;
    }
}
