// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import java.nio.ByteOrder;
import jdk.incubator.vector.*;

/** Local Addr# vector reads retain exact public carriers and erased State. */
public final class VectorAddressExpression extends Expr {
    private final VectorMemoryOp operation;
    @Children private Expr[] arguments;
    private final int vectorBytes;
    private final int stride;
    private final VectorShape shape;

    public VectorAddressExpression(VectorMemoryOp operation, Expr[] arguments) {
        this.operation = operation;
        this.arguments = arguments;
        vectorBytes = operation.getVectorBytes();
        int width = vectorBytes / operation.getVectorProof().getVector().getLanes();
        stride = operation.getScalarOffset() ? width : vectorBytes;
        shape = VectorShape.forBitSize(vectorBytes * 8);
        setRepresentation(operation.isWrite() ? CoreVectorMemory.stateProof : operation.getVectorProof());
    }

    @Override public Object execute(VirtualFrame frame) {
        var address = arguments[0].executeRequiredAddress(frame);
        long index = arguments[1].executeRequiredLong(frame);
        var family = operation.getFamily();
        if (operation.isWrite()) {
            ByteVector bytes;
            if (family == VectorMemoryFamily.INT8 || family == VectorMemoryFamily.WORD8) {
                var vector = CoreVectors.requireByte(arguments[2].execute(frame), ByteVector.SPECIES_128.withShape(shape));
                var bits = vector;

                bytes = bits.reinterpretAsBytes();
            }
            else if (family == VectorMemoryFamily.INT16 || family == VectorMemoryFamily.WORD16) {
                var vector = CoreVectors.requireShort(arguments[2].execute(frame), ShortVector.SPECIES_128.withShape(shape));
                var bits = vector;
                if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
                bytes = bits.reinterpretAsBytes();
            }
            else if (family == VectorMemoryFamily.INT64 || family == VectorMemoryFamily.WORD64) {
                var vector = CoreVectors.requireLong(arguments[2].execute(frame), LongVector.SPECIES_128.withShape(shape));
                var bits = vector;
                if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
                bytes = bits.reinterpretAsBytes();
            }
            else if (family == VectorMemoryFamily.INT32) {
                var vector = CoreVectors.requireInt(arguments[2].execute(frame), IntVector.SPECIES_128.withShape(shape));
                var bits = vector;
                if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
                bytes = bits.reinterpretAsBytes();
            }
            else if (family == VectorMemoryFamily.WORD32) {
                var vector = CoreVectors.requireInt(arguments[2].execute(frame), IntVector.SPECIES_128.withShape(shape));
                var bits = vector;
                if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
                bytes = bits.reinterpretAsBytes();
            }
            else if (family == VectorMemoryFamily.FLOAT32) {
                var vector = CoreVectors.requireFloat(arguments[2].execute(frame), FloatVector.SPECIES_128.withShape(shape));
                var bits = vector.reinterpretAsInts();
                if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
                bytes = bits.reinterpretAsBytes();
            }
            else if (family == VectorMemoryFamily.DOUBLE64) {
                var vector = CoreVectors.requireDouble(arguments[2].execute(frame), DoubleVector.SPECIES_128.withShape(shape));
                var bits = vector.reinterpretAsLongs();
                if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
                bytes = bits.reinterpretAsBytes();
            }
            else throw RuntimeFault.fault("Unsupported local vector memory family");
            ManagedByteArray.requireState(arguments[3].execute(frame));
            address.writeVectorBytes(index, stride, bytes, vectorBytes);
            return kotlin.Unit.INSTANCE;
        }
        if (operation.isRead()) ManagedByteArray.requireState(arguments[2].execute(frame));
        var bytes = address.readVectorBytes(index, stride, vectorBytes);
        if (family == VectorMemoryFamily.INT8 || family == VectorMemoryFamily.WORD8) {
            var bits = bytes;

            return bits;
        }
        if (family == VectorMemoryFamily.INT16 || family == VectorMemoryFamily.WORD16) {
            var bits = bytes.reinterpretAsShorts();
            if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
            return bits;
        }
        if (family == VectorMemoryFamily.INT64 || family == VectorMemoryFamily.WORD64) {
            var bits = bytes.reinterpretAsLongs();
            if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
            return bits;
        }
        if (family == VectorMemoryFamily.INT32) {
            var bits = bytes.reinterpretAsInts();
            if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
            return bits;
        }
        if (family == VectorMemoryFamily.WORD32) {
            var bits = bytes.reinterpretAsInts();
            if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
            return bits;
        }
        if (family == VectorMemoryFamily.FLOAT32) {
            var bits = bytes.reinterpretAsInts();
            if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
            return bits.reinterpretAsFloats();
        }
        if (family == VectorMemoryFamily.DOUBLE64) {
            var bits = bytes.reinterpretAsLongs();
            if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
            return bits.reinterpretAsDoubles();
        }
        throw RuntimeFault.fault("Unsupported local vector memory family");
    }
}
