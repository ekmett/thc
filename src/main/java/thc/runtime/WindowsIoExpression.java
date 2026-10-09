// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeFault.fault;

/** Operands are sequenced by Program before this owner prepares one physical
 * effect. A saved AST activation resumes that same request, including after a
 * second cut; terminal discard releases delivery, not its native worker loans. */
final class WindowsIoExpression extends Expr {
    private final WindowsIoOp op;
    @Children private Expr[] operands;
    WindowsIoExpression(WindowsIoOp op, Expr[] operands, CoreRepresentation proof) {
        this.op = op; this.operands = operands;
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(),
            proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) { throw fault("Windows RTS call requires a tuple destination"); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        if (op.request) {
            long fd = operands[0].executeRequiredLong(frame);
            long socket = operands[1].executeRequiredLong(frame);
            long count = operands[2].executeRequiredLong(frame);
            var address = operands[3].executeRequiredAddress(frame);
            TupleResults.requireVoidCarrier(operands[4].execute(frame));
            var request = WindowsNativeIo.required().submit(Math.toIntExact(fd), socket != 0, Math.toIntExact(count), address, op == WindowsIoOp.WRITE);
            return await(frame, request, slots, offset);
        }
        int code = operands[0].executeRequiredInt(frame);
        var cell = op == WindowsIoOp.INSTALL ? operands[1].executeRequiredAddress(frame) : null;
        TupleResults.requireVoidCarrier(operands[operands.length - 1].execute(frame));
        var console = WindowsNativeIo.required().console();
        if (op == WindowsIoOp.INSTALL) FrameAccess.INSTANCE.writeInt(frame, slots[offset], console.install(code, cell));
        else console.done(code);
        return null;
    }
    private Object await(VirtualFrame frame, WindowsNativeIo.Request request, int[] slots, int offset) {
        WindowsNativeIo.Result result;
        try {
            result = AstControl.INSTANCE.enabled(this) ? request.awaitResumable(this, CompilerDirectives.inCompiledCode()) : request.await(this);
        } catch (AsyncBlocked cut) {
            if (!AstControl.INSTANCE.enabled(this)) { request.abandon(); throw cut; }
            throw new AstCapture(cut.getRequest(), SynchronousMasking.INSTANCE.current(this)).append(new Resume(this, request, slots, offset));
        } catch (RuntimeException | Error failure) { request.abandon(); throw failure; }
        FrameAccess.INSTANCE.writeLong(frame, slots[offset], result.length());
        FrameAccess.INSTANCE.writeLong(frame, slots[offset + 1], result.error());
        // A completion that wins the wait race still has the ordinary SAFE
        // post-call delivery edge. Its saved tuple does not repeat native work.
        if (AstControl.INSTANCE.enabled(this)) {
            boolean compiled = CompilerDirectives.inCompiledCode();
            var pending = GuestThreads.pollCurrent(this, false);
            if (pending != null) {
                pending.compiledCapture = compiled;
                throw new AstCapture(pending, SynchronousMasking.INSTANCE.current(this)).append(Completed.INSTANCE);
            }
        }
        return null;
    }
    private enum Completed implements AstResumeStep {
        INSTANCE;
        @Override public Object resume(VirtualFrame frame, Object input) {
            if (!RuntimeTypes.isUnit(input)) throw fault("Invalid completed Windows IO continuation input");
            return null;
        }
    }
    private record Resume(WindowsIoExpression operation, WindowsNativeIo.Request request, int[] slots, int offset) implements AstResumeStep {
        @Override public Object resume(VirtualFrame frame, Object input) {
            if (!RuntimeTypes.isUnit(input)) throw fault("Invalid Windows IO continuation input");
            return operation.await(frame, request, slots, offset);
        }
        @Override public void discard() { request.discardFromSavedActivation(); }
    }
}
