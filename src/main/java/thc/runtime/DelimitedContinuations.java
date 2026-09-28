// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
public final class DelimitedContinuations {
    private DelimitedContinuations() {}
    @TruffleBoundary public static MaterializedFrame copyContinuationFrame(MaterializedFrame frame) {
        var descriptor = frame.getFrameDescriptor();
        MaterializedFrame copy = Truffle.getRuntime().createMaterializedFrame(frame.getArguments().clone(), descriptor);
        frame.copyTo(0, copy, 0, descriptor.getNumberOfSlots());
        for (int index = 0; index < descriptor.getNumberOfAuxiliarySlots(); index++) copy.setAuxiliarySlot(index, frame.getAuxiliarySlot(index));
        return copy;
    }
}
