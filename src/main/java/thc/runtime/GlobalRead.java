// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;

final class GlobalRead extends Expr {
    private final GlobalBinding binding;
    GlobalRead(GlobalBinding binding) { this.binding = binding; }
    @Override public Object execute(VirtualFrame frame) { return binding.read(); }
}
