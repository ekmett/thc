// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;

final class Delay extends Expr {
    private final RootCallTarget target;
    private final CaptureLayout captureLayout;
    @CompilationFinal(dimensions = 1) private final int[] captures;
    private final int programSlot;
    private final int argumentSlot;
    private final boolean referenceArgument;
    private final int secondArgumentSlot;
    private final boolean secondReferenceArgument;
    Delay(RootCallTarget target, CaptureLayout captureLayout, int[] captures) {
        this(target, captureLayout, captures, -1);
    }
    Delay(RootCallTarget target, CaptureLayout captureLayout, int[] captures, int programSlot) {
        this.target = target; this.captureLayout = captureLayout; this.captures = captures;
        this.programSlot = programSlot; this.argumentSlot = -1; this.referenceArgument = false;
        this.secondArgumentSlot = -1; this.secondReferenceArgument = false;
    }
    Delay(RootCallTarget target, int argumentSlot, boolean referenceArgument) {
        this.referenceArgument = referenceArgument;
        this.target = target; this.captureLayout = null; this.captures = new int[0];
        this.programSlot = -1; this.argumentSlot = argumentSlot;
        this.secondArgumentSlot = -1; this.secondReferenceArgument = false;
    }
    Delay(RootCallTarget target, int firstSlot, boolean firstReference, int secondSlot, boolean secondReference) {
        this.target = target; this.captureLayout = null; this.captures = new int[0]; this.programSlot = -1;
        this.argumentSlot = firstSlot; this.referenceArgument = firstReference;
        this.secondArgumentSlot = secondSlot; this.secondReferenceArgument = secondReference;
    }
    RootCallTarget target() { return target; }
    @Override public Thunk execute(VirtualFrame frame) {
        if (secondArgumentSlot >= 0) return Thunk.withArguments(target, new Object[]{0L,
            referenceArgument ? frame.getObject(argumentSlot) : FrameAccess.read(frame, argumentSlot),
            secondReferenceArgument ? frame.getObject(secondArgumentSlot) : FrameAccess.read(frame, secondArgumentSlot)});
        if (argumentSlot >= 0) return Thunk.withArgument(target, referenceArgument ? frame.getObject(argumentSlot) : FrameAccess.read(frame, argumentSlot));
        return new Thunk(target, captureLayout == null ? null : captureLayout.capture(frame, captures,
            programSlot < 0 ? null : Program.instance(frame, programSlot)));
    }
}
