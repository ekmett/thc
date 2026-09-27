// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;

/** Megamorphic calls enforce the same physical strict-argument contract. */
public final class IndirectEntryArguments extends Node {
    @Child private Force force;
    public IndirectEntryArguments(Metrics metrics) { force = new Force(metrics); }
    public void execute(VirtualFrame frame, RootCallTarget target, Object[] packet) {
        if (!(target.getRootNode() instanceof GuestRoot root) || EntryArguments.captures(root)) return;
        // Physical positions exclude zero-storage logical inputs.
        for (int position : root.getStrictArgumentPositions()) packet[position] = force.execute(frame, packet[position]);
    }
}
