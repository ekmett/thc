// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;

public final class HashStableName extends Expr {
    @Child private Expr value;
    public HashStableName(Expr value) { this.value = value; }
    @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
    @Override public long executeLong(VirtualFrame frame) { return StableNames.current(this).hash(value.execute(frame)); }
}
