// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import jdk.incubator.vector.*;

/** Local reads publish a dense vector, never a State/vector tuple. */
public final class VectorByteArrayExpression extends Expr {
    private final VectorMemoryOp operation;
    @Children private Expr[] arguments;

    public VectorByteArrayExpression(VectorMemoryOp operation, Expr[] arguments) {
        this.operation = operation;
        this.arguments = arguments;
        setRepresentation(operation.isWrite() ? CoreVectorMemory.stateProof : operation.getVectorProof());
    }

    @Override public Object execute(VirtualFrame frame) {
        Object array = arguments[0].execute(frame);
        long index = arguments[1].executeRequiredLong(frame);
        var family = operation.getFamily();
        if (operation.isWrite()) {
            if (family == VectorMemoryFamily.INT8 || family == VectorMemoryFamily.WORD8) {
                var value = CoreVectors.requireByte(arguments[2].execute(frame), ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(operation.getVectorBytes() * 8)));
                ManagedByteArray.requireState(arguments[3].execute(frame));
                ManagedByteArray.writeByteVectorGuest(array, index, value, operation.getScalarOffset(), operation.getVectorBytes());
            }
            else if (family == VectorMemoryFamily.INT16 || family == VectorMemoryFamily.WORD16) {
                var value = CoreVectors.requireShort(arguments[2].execute(frame), ShortVector.SPECIES_128.withShape(VectorShape.forBitSize(operation.getVectorBytes() * 8)));
                ManagedByteArray.requireState(arguments[3].execute(frame));
                ManagedByteArray.writeShortVectorGuest(array, index, value, operation.getScalarOffset(), operation.getVectorBytes());
            }
            else if (family == VectorMemoryFamily.INT64 || family == VectorMemoryFamily.WORD64) {
                var value = CoreVectors.requireLong(arguments[2].execute(frame), LongVector.SPECIES_128.withShape(VectorShape.forBitSize(operation.getVectorBytes() * 8)));
                ManagedByteArray.requireState(arguments[3].execute(frame));
                ManagedByteArray.writeLongVectorGuest(array, index, value, operation.getScalarOffset(), operation.getVectorBytes());
            }
            else if (family == VectorMemoryFamily.INT32) {
                var value = CoreVectors.requireInt(arguments[2].execute(frame), IntVector.SPECIES_128.withShape(VectorShape.forBitSize(operation.getVectorBytes() * 8)));
                ManagedByteArray.requireState(arguments[3].execute(frame));
                ManagedByteArray.writeInt32VectorGuest(array, index, value, operation.getScalarOffset(), operation.getVectorBytes());
            }
            else if (family == VectorMemoryFamily.WORD32) {
                var value = CoreVectors.requireInt(arguments[2].execute(frame), IntVector.SPECIES_128.withShape(VectorShape.forBitSize(operation.getVectorBytes() * 8)));
                ManagedByteArray.requireState(arguments[3].execute(frame));
                ManagedByteArray.writeWord32VectorGuest(array, index, value, operation.getScalarOffset(), operation.getVectorBytes());
            }
            else if (family == VectorMemoryFamily.FLOAT32) {
                var value = CoreVectors.requireFloat(arguments[2].execute(frame), FloatVector.SPECIES_128.withShape(VectorShape.forBitSize(operation.getVectorBytes() * 8)));
                ManagedByteArray.requireState(arguments[3].execute(frame));
                ManagedByteArray.writeFloatVectorGuest(array, index, value, operation.getScalarOffset(), operation.getVectorBytes());
            }
            else if (family == VectorMemoryFamily.DOUBLE64) {
                var value = CoreVectors.requireDouble(arguments[2].execute(frame), DoubleVector.SPECIES_128.withShape(VectorShape.forBitSize(operation.getVectorBytes() * 8)));
                ManagedByteArray.requireState(arguments[3].execute(frame));
                ManagedByteArray.writeDoubleVectorGuest(array, index, value, operation.getScalarOffset(), operation.getVectorBytes());
            }
            else throw RuntimeFault.fault("Unsupported local vector memory family");
            return thc.runtime.Unit.INSTANCE;
        }
        if (operation.isRead()) ManagedByteArray.requireState(arguments[2].execute(frame));
        if (family == VectorMemoryFamily.INT8 || family == VectorMemoryFamily.WORD8) return ManagedByteArray.readByteVectorGuest(array, index, operation.getScalarOffset(), operation.getVectorBytes());
        if (family == VectorMemoryFamily.INT16 || family == VectorMemoryFamily.WORD16) return ManagedByteArray.readShortVectorGuest(array, index, operation.getScalarOffset(), operation.getVectorBytes());
        if (family == VectorMemoryFamily.INT64 || family == VectorMemoryFamily.WORD64) return ManagedByteArray.readLongVectorGuest(array, index, operation.getScalarOffset(), operation.getVectorBytes());
        if (family == VectorMemoryFamily.INT32) return ManagedByteArray.readInt32VectorGuest(array, index, operation.getScalarOffset(), operation.getVectorBytes());
        if (family == VectorMemoryFamily.WORD32) return ManagedByteArray.readWord32VectorGuest(array, index, operation.getScalarOffset(), operation.getVectorBytes());
        if (family == VectorMemoryFamily.FLOAT32) return ManagedByteArray.readFloatVectorGuest(array, index, operation.getScalarOffset(), operation.getVectorBytes());
        if (family == VectorMemoryFamily.DOUBLE64) return ManagedByteArray.readDoubleVectorGuest(array, index, operation.getScalarOffset(), operation.getVectorBytes());
        throw RuntimeFault.fault("Unsupported local vector memory family");
    }
}
