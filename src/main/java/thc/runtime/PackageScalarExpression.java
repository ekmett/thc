// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import static thc.runtime.RuntimeFault.fault;

public final class PackageScalarExpression extends Expr {
    private final PackageScalarCall call;
    @Children private Expr[] operands;
    @Child private PackageScalarAccess access;
    public PackageScalarExpression(PackageScalarCall call, Expr[] operands, CoreRepresentation proof) {
        this.call = call; this.operands = operands; access = new PackageScalarAccess(call);
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(),
            proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
    }
    @Override public Object execute(VirtualFrame frame) { throw fault("Package C call requires its State/result tuple"); }
    @ExplodeLoop @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        Object[] values = new Object[call.getArguments().length];
        for (int i = 0; i < values.length; i++) values[i] = switch (call.getArguments()[i]) {
            case "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep", "Int32Rep", "Word32Rep" -> PackageScalarAccess.packageCInteger(call.getArguments()[i], operands[i].executeRequiredInt(frame));
            case "IntRep", "WordRep", "Int64Rep", "Word64Rep" -> operands[i].executeRequiredLong(frame);
            case "FloatRep" -> operands[i].executeRequiredFloat(frame);
            case "DoubleRep" -> operands[i].executeRequiredDouble(frame);
            case "AddrRep", "ByteArray#", "MutableByteArray#" -> operands[i].execute(frame);
            default -> throw fault("Invalid package C operand");
        };
        Object state = operands[operands.length - 1].execute(frame);
        switch (call.getResult()) {
            case "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep", "Int32Rep", "Word32Rep" -> FrameAccess.writeInt(frame, slots[offset], access.executeInt(values, state));
            case "IntRep", "WordRep", "Int64Rep", "Word64Rep" -> FrameAccess.writeLong(frame, slots[offset], access.executeLong(values, state));
            case "FloatRep" -> FrameAccess.writeFloat(frame, slots[offset], access.executeFloat(values, state));
            case "DoubleRep" -> FrameAccess.writeDouble(frame, slots[offset], access.executeDouble(values, state));
            case "AddrRep" -> FrameAccess.write(frame, slots[offset], access.executeAddress(values, state));
            case "void" -> access.executeVoid(values, state);
            default -> throw fault("Invalid package C result");
        }
        if (call.getSafety() == ForeignSafety.SAFE) AstForeignCompleted.INSTANCE.poll(this);
        return null;
    }
}
