// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.frame.VirtualFrame;

/** Exact vector metadata and one replay-local slot; no activation or payload is retained here. */
public final class BytecodeVectorSlots {
    private final VectorLayout layout;
    @CompilationFinal(dimensions = 1) private final LocalAccessor[] slots;

    public BytecodeVectorSlots(CoreRepresentation proof, LocalAccessor[] slots) {
        layout = new VectorLayout(proof);
        if (slots.length != TupleShape.Companion.flatten(proof).size())
            throw new IllegalArgumentException("Failed requirement.");
        this.slots = slots;
    }

    public void write(VirtualFrame frame, BytecodeRoot root, Object value) {
        layout.write(root.getBytecodeNode(), frame, slots, 0, value);
    }

    public Object read(VirtualFrame frame, BytecodeRoot root) {
        return layout.read(root.getBytecodeNode(), frame, slots, 0);
    }
}
