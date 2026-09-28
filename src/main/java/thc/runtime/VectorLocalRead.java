// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;

/** Parallel moves retain the immutable raw vector without extracting lanes. */
public final class VectorLocalRead extends Expr {
    @CompilationFinal(dimensions = 1) private final int[] sources;
    private final VectorLayout vector;
    public VectorLocalRead(TupleShape shape, int[] sources) {
        vector = new VectorLayout(shape.getProof());
        if (sources.length != vector.getWidth()) throw new IllegalArgumentException("Failed requirement.");
        this.sources = sources;
        CoreRepresentation proof = shape.getProof();
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(),
            proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) { return vector.read(frame, sources, 0); }
    @ExplodeLoop @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        vector.copy(frame, sources, 0, slots, offset);
        return null;
    }
}
