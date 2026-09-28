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
    Delay(RootCallTarget target, CaptureLayout captureLayout, int[] captures) {
        this.target = target; this.captureLayout = captureLayout; this.captures = captures;
    }
    @Override public Thunk execute(VirtualFrame frame) {
        return new Thunk(target, captureLayout == null ? null : captureLayout.capture(frame, captures));
    }
}
