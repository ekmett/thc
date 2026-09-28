// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;

final class GlobalRead extends Expr {
    private final GlobalBinding binding;
    private final int bindingIndex;
    private final int programSlot;
    GlobalRead(GlobalBinding binding) { this.binding = binding; bindingIndex = -1; programSlot = -1; }
    GlobalRead(int bindingIndex, int programSlot) { binding = null; this.bindingIndex = bindingIndex; this.programSlot = programSlot; }
    @Override public Object execute(VirtualFrame frame) {
        return binding != null ? binding.read() : Program.instance(frame, programSlot).readGlobal(bindingIndex);
    }
}
