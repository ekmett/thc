// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/** A typed don't-care, materialized only when its consumer requires a carrier. */
final class Rubbish extends Expr {
    private final RubbishLiterals literals;
    private final int programSlot;
    @Child private Expr aggregate;
    @CompilationFinal private volatile Object reference;

    Rubbish(CoreRepresentation proof, RubbishLiterals literals, Expr aggregate, int programSlot) {
        setRepresentation(proof.withEvaluated(true));
        this.literals = literals;
        this.programSlot = programSlot;
        this.aggregate = aggregate;
    }
    @Override public void prepareTuple(int[] slots, int offset) {
        if (aggregate != null) aggregate.prepareTuple(slots, offset);
    }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        return aggregate == null ? super.executeTuple(frame, slots, offset) : aggregate.executeTuple(frame, slots, offset);
    }
    @Override public Object execute(VirtualFrame frame) {
        CoreRepresentation proof = getRepresentation();
        if (aggregate != null) throw new RuntimeFault("Rubbish aggregate requires a typed destination");
        return switch (proof.getKind()) {
            case LONG -> { if (proof.isInt()) yield Integer.valueOf(0); yield Long.valueOf(0); }
            case FLOAT -> 0.0f;
            case DOUBLE -> 0.0;
            case VOID -> Unit.INSTANCE;
            case ADDRESS -> ManagedAddress.nullAddress();
            case VECTOR -> getTypedVectorLayout().getSpecies().zero();
            case OBJECT, DATA, CLOSURE -> {
                if (programSlot >= 0) yield Program.instance(frame, programSlot).rubbishValue(proof);
                if (reference == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    atomic(() -> { if (reference == null) reference = literals.decode(proof); });
                }
                yield reference;
            }
            default -> throw new UnsupportedCore("Unsupported rubbish representation");
        };
    }
    @Override public int executeInt(VirtualFrame frame) throws UnexpectedResultException {
        return getRepresentation().isInt() ? 0 : super.executeInt(frame);
    }
    @Override public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        return getRepresentation().isLong() ? 0L : super.executeLong(frame);
    }
    @Override public float executeFloat(VirtualFrame frame) throws UnexpectedResultException {
        return getRepresentation().isFloat() ? 0.0f : super.executeFloat(frame);
    }
    @Override public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        return getRepresentation().isDouble() ? 0.0 : super.executeDouble(frame);
    }
}
