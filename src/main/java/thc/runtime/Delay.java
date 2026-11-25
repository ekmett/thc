// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import java.util.Set;

final class Delay extends Expr {
    private final RootCallTarget target;
    private final CaptureLayout captureLayout;
    @CompilationFinal(dimensions = 1) private final int[] captures;
    private final int programSlot;
    private final int argumentSlot;
    private final boolean referenceArgument;
    Delay(RootCallTarget target, CaptureLayout captureLayout, int[] captures) {
        this(target, captureLayout, captures, -1);
    }
    Delay(RootCallTarget target, CaptureLayout captureLayout, int[] captures, int programSlot) {
        this.target = target; this.captureLayout = captureLayout; this.captures = captures;
        this.programSlot = programSlot; this.argumentSlot = -1; this.referenceArgument = false;
    }
    Delay(RootCallTarget target, int argumentSlot, boolean referenceArgument) {
        this.referenceArgument = referenceArgument;
        this.target = target; this.captureLayout = null; this.captures = new int[0];
        this.programSlot = -1; this.argumentSlot = argumentSlot;
    }
    RootCallTarget target() { return target; }
    void collectCaptureSlots(Set<Integer> slots) {
        if (argumentSlot >= 0) slots.add(argumentSlot);
        for (int capture : captures) slots.add(capture);
    }
    @Override public Thunk execute(VirtualFrame frame) {
        if (argumentSlot >= 0) return Thunk.withArgument(target, referenceArgument ? frame.getObject(argumentSlot) : FrameAccess.read(frame, argumentSlot));
        return new Thunk(target, captureLayout == null ? null : captureLayout.capture(frame, captures,
            programSlot < 0 ? null : Program.instance(frame, programSlot)));
    }
}
