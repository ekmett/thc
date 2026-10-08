// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;

final class MakeClosure extends Expr {
    private final RootCallTarget target;
    private final int arity;
    private final CaptureLayout captureLayout;
    @CompilationFinal(dimensions = 1) private final int[] captures;
    private final Closure constantClosure;
    private final int programSlot;
    MakeClosure(RootCallTarget target, int arity, CaptureLayout captureLayout, int[] captures) {
        this(target, arity, captureLayout, captures, -1);
    }
    MakeClosure(RootCallTarget target, int arity, CaptureLayout captureLayout, int[] captures, int programSlot) {
        this.target = target; this.arity = arity; this.captureLayout = captureLayout; this.captures = captures;
        this.programSlot = programSlot;
        setRepresentation(new CoreRepresentation(CoreKind.CLOSURE, true, false, null, null, null, null, null, null));
        // Closed immutable code needs no per-entry allocation.
        constantClosure = captureLayout == null ? new Closure(null, arity, target) : null;
    }
    RootCallTarget target() { return target; }
    @Override public Closure execute(VirtualFrame frame) {
        return constantClosure != null ? constantClosure : new Closure(captureLayout.capture(frame, captures,
            programSlot < 0 ? null : Program.instance(frame, programSlot)), arity, target);
    }
    @Override public Closure executeClosure(VirtualFrame frame) { return execute(frame); }
}
