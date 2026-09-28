// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;

final class ByteStringUtf8Expression extends Expr {
    private final boolean safe;
    @Children private Expr[] operands;

    ByteStringUtf8Expression(boolean safe, Expr[] operands, CoreRepresentation proof) {
        this.safe = safe;
        this.operands = operands;
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(),
                proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(),
                proof.getTagSlot(), proof.getAlternativeSlots()));
    }

    private static final class ResumeCompleted implements AstResumeStep {
        private static final ResumeCompleted INSTANCE = new ResumeCompleted();

        @Override public Object resume(VirtualFrame frame, Object input) {
            if (!RuntimeTypes.isUnit(input)) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new RuntimeFault("Invalid completed UTF-8 validation continuation");
            }
            return null;
        }
    }

    @Override public Object execute(VirtualFrame frame) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeFault("UTF-8 validation requires a tuple destination");
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        ManagedAddress address = operands[0].executeRequiredAddress(frame);
        long length = operands[1].executeRequiredLong(frame);
        TupleResults.requireVoidCarrier(operands[2].execute(frame));
        // Poll only after C has returned and the result is saved; resumption must
        // never invoke the completed foreign call again.
        FrameAccess.INSTANCE.writeInt(frame, slots[offset], (int) ManagedByteStringUtf8.validate(address, length));
        if (safe && AstControl.INSTANCE.enabled(this)) {
            boolean compiled = CompilerDirectives.inCompiledCode();
            AsyncRequest request = GuestThreads.pollCurrent(this, false);
            if (request != null) {
                request.compiledCapture = compiled;
                throw new AstCapture(request, SynchronousMasking.INSTANCE.current(this)).append(ResumeCompleted.INSTANCE);
            }
        }
        return null;
    }
}
