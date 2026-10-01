// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import java.util.List;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Byte-array primitive contracts and typed execution, including the logical State# slot. */
public enum ByteArrayOp {
    NEW("newByteArray#", List.of(List.of("IntRep"), List.of()), true),
    RESIZE("resizeMutableByteArray#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of()), true),
    SHRINK("shrinkMutableByteArray#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of())),
    WRITE("writeWord8Array#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of("Word8Rep"), List.of())),
    WRITE_CHAR("writeCharArray#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of("WordRep"), List.of())),
    COPY("copyByteArray#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of("BoxedRep (Just Unlifted)"),
        List.of("IntRep"), List.of("IntRep"), List.of())),
    SET("setByteArray#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of("IntRep"), List.of("IntRep"), List.of())),
    COPY_MUTABLE("copyMutableByteArray#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of("BoxedRep (Just Unlifted)"),
        List.of("IntRep"), List.of("IntRep"), List.of())),
    COPY_MUTABLE_NON_OVERLAPPING("copyMutableByteArrayNonOverlapping#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of("BoxedRep (Just Unlifted)"),
        List.of("IntRep"), List.of("IntRep"), List.of())),
    COMPARE("compareByteArrays#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of("BoxedRep (Just Unlifted)"),
        List.of("IntRep"), List.of("IntRep"))),
    FREEZE("unsafeFreezeByteArray#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of()), true),
    UNSAFE_THAW("unsafeThawByteArray#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of()), true),
    IS_PINNED("isByteArrayPinned#", List.of(List.of("BoxedRep (Just Unlifted)"))),
    IS_MUTABLE_PINNED("isMutableByteArrayPinned#", List.of(List.of("BoxedRep (Just Unlifted)"))),
    IS_WEAKLY_PINNED("isByteArrayWeaklyPinned#", List.of(List.of("BoxedRep (Just Unlifted)"))),
    IS_MUTABLE_WEAKLY_PINNED("isMutableByteArrayWeaklyPinned#", List.of(List.of("BoxedRep (Just Unlifted)"))),
    SIZE("sizeofByteArray#", List.of(List.of("BoxedRep (Just Unlifted)"))),
    SIZE_MUTABLE("sizeofMutableByteArray#", List.of(List.of("BoxedRep (Just Unlifted)"))),
    GET_SIZE_MUTABLE("getSizeofMutableByteArray#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of()), true),
    INDEX("indexWord8Array#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"))),
    INDEX_CHAR("indexCharArray#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"))),
    READ_INT("readIntArray#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of()), true),
    WRITE_INT("writeIntArray#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of("IntRep"), List.of())),
    INDEX_INT("indexIntArray#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"))),
    READ_INT64("readInt64Array#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of()), true),
    WRITE_INT64("writeInt64Array#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of("Int64Rep"), List.of())),
    INDEX_INT64("indexInt64Array#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"))),
    READ_WORD64("readWord64Array#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of()), true),
    WRITE_WORD64("writeWord64Array#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of("Word64Rep"), List.of())),
    INDEX_WORD64("indexWord64Array#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"))),
    READ_DOUBLE("readDoubleArray#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of()), true),
    WRITE_DOUBLE("writeDoubleArray#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of("DoubleRep"), List.of())),
    INDEX_DOUBLE("indexDoubleArray#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"))),
    READ_WORD8_AS_DOUBLE("readWord8ArrayAsDouble#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of()), true),
    WRITE_WORD8_AS_DOUBLE("writeWord8ArrayAsDouble#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of("DoubleRep"), List.of())),
    INDEX_WORD8_AS_DOUBLE("indexWord8ArrayAsDouble#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"))),
    READ_INT8("readInt8Array#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of()), true),
    WRITE_INT8("writeInt8Array#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of("Int8Rep"), List.of())),
    INDEX_INT8("indexInt8Array#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"))),
    READ_WORD8("readWord8Array#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of()), true),
    READ_CHAR("readCharArray#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of()), true),
    READ_INT16("readInt16Array#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of()), true),
    WRITE_INT16("writeInt16Array#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of("Int16Rep"), List.of())),
    INDEX_INT16("indexInt16Array#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"))),
    READ_WORD16("readWord16Array#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of()), true),
    WRITE_WORD16("writeWord16Array#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of("Word16Rep"), List.of())),
    INDEX_WORD16("indexWord16Array#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"))),
    READ_WORD8_AS_INT16("readWord8ArrayAsInt16#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of()), true),
    WRITE_WORD8_AS_INT16("writeWord8ArrayAsInt16#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of("Int16Rep"), List.of())),
    INDEX_WORD8_AS_INT16("indexWord8ArrayAsInt16#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"))),
    READ_WORD8_AS_WORD16("readWord8ArrayAsWord16#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of()), true),
    WRITE_WORD8_AS_WORD16("writeWord8ArrayAsWord16#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of("Word16Rep"), List.of())),
    INDEX_WORD8_AS_WORD16("indexWord8ArrayAsWord16#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"))),
    READ_INT32("readInt32Array#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of()), true),
    WRITE_INT32("writeInt32Array#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of("Int32Rep"), List.of())),
    INDEX_INT32("indexInt32Array#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"))),
    READ_WORD32("readWord32Array#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of()), true),
    WRITE_WORD32("writeWord32Array#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of("Word32Rep"), List.of())),
    INDEX_WORD32("indexWord32Array#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"))),
    READ_WIDE_CHAR("readWideCharArray#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of()), true),
    WRITE_WIDE_CHAR("writeWideCharArray#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of("WordRep"), List.of())),
    INDEX_WIDE_CHAR("indexWideCharArray#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"))),
    READ_WORD8_AS_INT32("readWord8ArrayAsInt32#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of()), true),
    WRITE_WORD8_AS_INT32("writeWord8ArrayAsInt32#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of("Int32Rep"), List.of())),
    INDEX_WORD8_AS_INT32("indexWord8ArrayAsInt32#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"))),
    READ_WORD8_AS_WORD32("readWord8ArrayAsWord32#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of()), true),
    WRITE_WORD8_AS_WORD32("writeWord8ArrayAsWord32#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of("Word32Rep"), List.of())),
    INDEX_WORD8_AS_WORD32("indexWord8ArrayAsWord32#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"))),
    READ_FLOAT("readFloatArray#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of()), true),
    WRITE_FLOAT("writeFloatArray#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of("FloatRep"), List.of())),
    INDEX_FLOAT("indexFloatArray#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"))),
    READ_WORD8_AS_FLOAT("readWord8ArrayAsFloat#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of()), true),
    WRITE_WORD8_AS_FLOAT("writeWord8ArrayAsFloat#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of("FloatRep"), List.of())),
    INDEX_WORD8_AS_FLOAT("indexWord8ArrayAsFloat#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"))),
    READ_WORD("readWordArray#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of()), true),
    WRITE_WORD("writeWordArray#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of("WordRep"), List.of())),
    INDEX_WORD("indexWordArray#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep")));

    private static final String BYTE_ARRAY_REP = "BoxedRep (Just Unlifted)";
    private final String primitive;
    private final List<List<String>> arguments;
    private final boolean tuple;
    ByteArrayOp(String primitive, List<List<String>> arguments) { this(primitive, arguments, false); }
    ByteArrayOp(String primitive, List<List<String>> arguments, boolean tuple) {
        this.primitive = primitive; this.arguments = arguments; this.tuple = tuple;
    }
    public String getPrimitive() { return primitive; }
    public boolean getTuple() { return tuple; }
    private static boolean scalar(CoreRepresentation proof, List<String> registers) {
        String register = registers.size() == 1 ? registers.get(0) : null;
        CoreKind kind = register == null ? CoreKind.VOID : switch (register) {
            case BYTE_ARRAY_REP -> CoreKind.OBJECT;
            case "DoubleRep" -> CoreKind.DOUBLE;
            case "FloatRep" -> CoreKind.FLOAT;
            default -> CoreKind.LONG;
        };
        return !proof.isAggregate() && !proof.isVector() &&
            (proof.getKind() == CoreKind.LONG ? proof.isInt() == (NarrowInteger.fromRep(register) != null) :
                registers.equals(proof.getPrimReps())) && proof.getKind() == kind;
    }
    public void validate(List<CoreRepresentation> actual, List<?> flags, CoreRepresentation result) {
        if (actual.size() != arguments.size()) throw new RuntimeFault("Primitive arity mismatch: " + primitive);
        boolean valid = flags.size() == arguments.size();
        for (Object flag : flags) valid &= Boolean.FALSE.equals(flag);
        if (valid) for (int i = 0; i < actual.size(); i++) {
            if (!scalar(actual.get(i), arguments.get(i))) { valid = false; break; }
        }
        if (!valid) throw new RuntimeFault("ByteArray primitive argument representation mismatch: " + primitive);
        List<String> payload = List.of(switch (this) {
            case READ_INT, GET_SIZE_MUTABLE -> "IntRep";
            case READ_DOUBLE, READ_WORD8_AS_DOUBLE -> "DoubleRep";
            case READ_INT64 -> "Int64Rep";
            case READ_WORD64 -> "Word64Rep";
            case READ_INT8 -> "Int8Rep";
            case READ_WORD8 -> "Word8Rep";
            case READ_INT16, READ_WORD8_AS_INT16 -> "Int16Rep";
            case READ_WORD16, READ_WORD8_AS_WORD16 -> "Word16Rep";
            case READ_INT32, READ_WORD8_AS_INT32 -> "Int32Rep";
            case READ_WORD32, READ_WORD8_AS_WORD32 -> "Word32Rep";
            case READ_FLOAT, READ_WORD8_AS_FLOAT -> "FloatRep";
            case READ_WORD, READ_CHAR, READ_WIDE_CHAR -> "WordRep";
            default -> BYTE_ARRAY_REP;
        });
        if (tuple) valid = result.isTuple() && result.getKind() == CoreKind.UNKNOWN && result.getComponents().size() == 2 &&
            scalar(result.getComponents().get(0), List.of()) && scalar(result.getComponents().get(1), payload) &&
            (result.getComponents().get(1).getKind() == CoreKind.LONG || payload.equals(result.getPrimReps()));
        else valid = scalar(result, switch (this) {
            case WRITE_INT8, WRITE_INT16, WRITE_WORD16, WRITE_WORD8_AS_INT16, WRITE_WORD8_AS_WORD16,
                WRITE, WRITE_CHAR, WRITE_WIDE_CHAR, WRITE_INT, WRITE_DOUBLE, WRITE_INT32, WRITE_WORD32,
                WRITE_WORD8_AS_INT32, WRITE_WORD8_AS_WORD32, WRITE_FLOAT, WRITE_WORD, WRITE_WORD8_AS_DOUBLE,
                WRITE_WORD8_AS_FLOAT, WRITE_INT64, WRITE_WORD64, COPY, SET, COPY_MUTABLE, COPY_MUTABLE_NON_OVERLAPPING, SHRINK -> List.of();
            case SIZE, SIZE_MUTABLE, INDEX_INT, COMPARE, IS_PINNED, IS_MUTABLE_PINNED, IS_WEAKLY_PINNED, IS_MUTABLE_WEAKLY_PINNED -> List.of("IntRep");
            case INDEX_INT8 -> List.of("Int8Rep");
            case INDEX_INT16, INDEX_WORD8_AS_INT16 -> List.of("Int16Rep");
            case INDEX_WORD16, INDEX_WORD8_AS_WORD16 -> List.of("Word16Rep");
            case INDEX_INT32, INDEX_WORD8_AS_INT32 -> List.of("Int32Rep");
            case INDEX_WORD32, INDEX_WORD8_AS_WORD32 -> List.of("Word32Rep");
            case INDEX_INT64 -> List.of("Int64Rep");
            case INDEX_WORD64 -> List.of("Word64Rep");
            case INDEX_FLOAT, INDEX_WORD8_AS_FLOAT -> List.of("FloatRep");
            case INDEX_WORD, INDEX_CHAR, INDEX_WIDE_CHAR -> List.of("WordRep");
            case INDEX_DOUBLE, INDEX_WORD8_AS_DOUBLE -> List.of("DoubleRep");
            default -> List.of("Word8Rep");
        });
        if (!valid) throw new RuntimeFault("ByteArray primitive result representation mismatch: " + primitive);
    }
    public static ByteArrayOp named(String name) {
        switch (name) {
            case "indexWord8ArrayAsInt#", "indexWord8ArrayAsWord#", "indexWord8ArrayAsInt64#", "indexWord8ArrayAsWord64#": return INDEX_INT;
            case "indexWord8ArrayAsChar#": return INDEX_CHAR;
            case "indexWord8ArrayAsWideChar#": return INDEX_WIDE_CHAR;
            case "readWord8ArrayAsInt#", "readWord8ArrayAsWord#", "readWord8ArrayAsInt64#", "readWord8ArrayAsWord64#": return READ_INT;
            case "readWord8ArrayAsChar#": return READ_CHAR;
            case "readWord8ArrayAsWideChar#": return READ_WIDE_CHAR;
            case "writeWord8ArrayAsInt#", "writeWord8ArrayAsWord#", "writeWord8ArrayAsInt64#", "writeWord8ArrayAsWord64#": return WRITE_INT;
            case "writeWord8ArrayAsChar#": return WRITE_CHAR;
            case "writeWord8ArrayAsWideChar#": return WRITE_WIDE_CHAR;
            default: for (ByteArrayOp operation : values()) if (operation.primitive.equals(name)) return operation;
                return null;
        }
    }
    public static Expr expression(ByteArrayOp operation, CoreRepresentation proof, Expr[] operands) {
        return expression(operation, proof, operands, false);
    }
    public static Expr expression(ByteArrayOp operation, CoreRepresentation proof, Expr[] operands, boolean byteOffset) {
        return new ByteArrayExpression(operation, proof, operands, byteOffset);
    }
    private static final class ByteArrayExpression extends Expr {
        private final ByteArrayOp operation;
        private final boolean byteOffset;
        @Children private Expr[] operands;
        ByteArrayExpression(ByteArrayOp operation, CoreRepresentation proof, Expr[] operands, boolean byteOffset) {
            this.operation = operation; this.operands = operands; this.byteOffset = byteOffset;
            setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(),
                proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
        }
        @Override public int executeInt(VirtualFrame frame) throws UnexpectedResultException {
            return switch (operation) {
                case INDEX, INDEX_INT8, INDEX_INT16, INDEX_WORD16, INDEX_WORD8_AS_INT16, INDEX_WORD8_AS_WORD16, INDEX_INT32, INDEX_WORD32, INDEX_WORD8_AS_INT32, INDEX_WORD8_AS_WORD32 -> indexedInt(frame);
                default -> super.executeInt(frame);
            };
        }
        @Override public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
            return switch (operation) {
                case SIZE, SIZE_MUTABLE, IS_PINNED, IS_MUTABLE_PINNED, IS_WEAKLY_PINNED, IS_MUTABLE_WEAKLY_PINNED, COMPARE, INDEX_INT, INDEX_WORD, INDEX_INT64, INDEX_WORD64, INDEX_CHAR, INDEX_WIDE_CHAR -> indexedLong(frame);
                default -> super.executeLong(frame);
            };
        }
        @Override public float executeFloat(VirtualFrame frame) throws UnexpectedResultException {
            return switch (operation) {
                case INDEX_FLOAT, INDEX_WORD8_AS_FLOAT -> indexedFloat(frame);
                default -> super.executeFloat(frame);
            };
        }
        @Override public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
            return switch (operation) {
                case INDEX_DOUBLE, INDEX_WORD8_AS_DOUBLE -> indexedDouble(frame);
                default -> super.executeDouble(frame);
            };
        }
        @Override public Object execute(VirtualFrame frame) {
            switch (operation) {
                case INDEX, INDEX_INT8, INDEX_INT16, INDEX_WORD16, INDEX_WORD8_AS_INT16, INDEX_WORD8_AS_WORD16, INDEX_INT32, INDEX_WORD32, INDEX_WORD8_AS_INT32, INDEX_WORD8_AS_WORD32: return indexedInt(frame);
                case SIZE, SIZE_MUTABLE, IS_PINNED, IS_MUTABLE_PINNED, IS_WEAKLY_PINNED, IS_MUTABLE_WEAKLY_PINNED, COMPARE, INDEX_INT, INDEX_WORD, INDEX_INT64, INDEX_WORD64, INDEX_CHAR, INDEX_WIDE_CHAR: return indexedLong(frame);
                case INDEX_FLOAT, INDEX_WORD8_AS_FLOAT: return indexedFloat(frame);
                case INDEX_DOUBLE, INDEX_WORD8_AS_DOUBLE: return indexedDouble(frame);
                case SHRINK: {
                    Object bytes = operands[0].execute(frame);
                    long count = operands[1].executeRequiredLong(frame);
                    Object state = state(frame, 2);
                    ManagedByteArray.shrinkGuest(bytes, count); return state;
                }
                case COPY, COPY_MUTABLE, COPY_MUTABLE_NON_OVERLAPPING: {
                    Object from = operands[0].execute(frame);
                    long fromOffset = operands[1].executeRequiredLong(frame);
                    Object to = operands[2].execute(frame);
                    long toOffset = operands[3].executeRequiredLong(frame), count = operands[4].executeRequiredLong(frame);
                    Object state = state(frame, 5);
                    ManagedByteArray.copyGuest(from, fromOffset, to, toOffset, count, operation != COPY, operation == COPY_MUTABLE_NON_OVERLAPPING);
                    return state;
                }
                case SET: {
                    Object bytes = operands[0].execute(frame);
                    long start = operands[1].executeRequiredLong(frame), count = operands[2].executeRequiredLong(frame), value = operands[3].executeRequiredLong(frame);
                    Object state = state(frame, 4);
                    ManagedByteArray.fillGuest(bytes, start, count, value); return state;
                }
                case WRITE, WRITE_INT8, WRITE_CHAR: {
                    Object bytes = operands[0].execute(frame);
                    long index = operands[1].executeRequiredLong(frame);
                    int value = operation == WRITE_CHAR ? (int) operands[2].executeRequiredLong(frame) : operands[2].executeRequiredInt(frame);
                    Object state = state(frame, 3);
                    ManagedByteArray.writeGuest(bytes, index, value);
                    return state;
                }
                case WRITE_INT, WRITE_WORD, WRITE_INT64, WRITE_WORD64: {
                    Object bytes = operands[0].execute(frame);
                    long index = operands[1].executeRequiredLong(frame);
                    long value = operands[2].executeRequiredLong(frame);
                    Object state = state(frame, 3);
                    ManagedByteArray.writeIntGuest(bytes, index, value, byteOffset);
                    return state;
                }
                case WRITE_WIDE_CHAR: {
                    Object bytes = operands[0].execute(frame);
                    long index = operands[1].executeRequiredLong(frame);
                    long value = operands[2].executeRequiredLong(frame);
                    Object state = state(frame, 3);
                    if (byteOffset) ManagedByteArray.writeInt32ByteOffsetGuest(bytes, index, (int) value); else ManagedByteArray.writeInt32Guest(bytes, index, (int) value);
                    return state;
                }
                case WRITE_DOUBLE, WRITE_WORD8_AS_DOUBLE: {
                    Object bytes = operands[0].execute(frame);
                    long index = operands[1].executeRequiredLong(frame);
                    double value = operands[2].executeRequiredDouble(frame);
                    Object state = state(frame, 3);
                    if (operation == WRITE_WORD8_AS_DOUBLE) ManagedByteArray.writeDoubleByteOffsetGuest(bytes, index, value); else ManagedByteArray.writeDoubleGuest(bytes, index, value);
                    return state;
                }
                case WRITE_FLOAT, WRITE_WORD8_AS_FLOAT: {
                    Object bytes = operands[0].execute(frame);
                    long index = operands[1].executeRequiredLong(frame);
                    float value = operands[2].executeRequiredFloat(frame);
                    Object state = state(frame, 3);
                    if (operation == WRITE_WORD8_AS_FLOAT) ManagedByteArray.writeFloatByteOffsetGuest(bytes, index, value); else ManagedByteArray.writeFloatGuest(bytes, index, value);
                    return state;
                }
                case WRITE_INT16, WRITE_WORD16, WRITE_WORD8_AS_INT16, WRITE_WORD8_AS_WORD16: {
                    Object bytes = operands[0].execute(frame);
                    long index = operands[1].executeRequiredLong(frame);
                    int value = operands[2].executeRequiredInt(frame);
                    Object state = state(frame, 3);
                    if (operation == WRITE_WORD8_AS_INT16 || operation == WRITE_WORD8_AS_WORD16) ManagedByteArray.writeInt16ByteOffsetGuest(bytes, index, value); else ManagedByteArray.writeInt16Guest(bytes, index, value);
                    return state;
                }
                case WRITE_INT32, WRITE_WORD32, WRITE_WORD8_AS_INT32, WRITE_WORD8_AS_WORD32: {
                    Object bytes = operands[0].execute(frame);
                    long index = operands[1].executeRequiredLong(frame);
                    int value = operands[2].executeRequiredInt(frame);
                    Object state = state(frame, 3);
                    if (operation == WRITE_WORD8_AS_INT32 || operation == WRITE_WORD8_AS_WORD32) ManagedByteArray.writeInt32ByteOffsetGuest(bytes, index, value); else ManagedByteArray.writeInt32Guest(bytes, index, value);
                    return state;
                }
                default: throw fault("Tuple primitive requires a destination");
            }
        }
        private Object state(VirtualFrame frame, int index) {
            Object state = operands[index].execute(frame);
            ManagedByteArray.requireState(state); return state;
        }
        private int indexedInt(VirtualFrame frame) {
            Object bytes = operands[0].execute(frame);
            long index = operands[1].executeRequiredLong(frame);
            return readInt(bytes, index);
        }
        private long indexedLong(VirtualFrame frame) {
            Object bytes = operands[0].execute(frame);
            switch (operation) {
                case SIZE, SIZE_MUTABLE: return ManagedByteArray.sizeGuest(bytes);
                case IS_PINNED, IS_MUTABLE_PINNED, IS_WEAKLY_PINNED, IS_MUTABLE_WEAKLY_PINNED:
                    if (bytes instanceof ManagedAllocation owner) return
                        (operation == IS_WEAKLY_PINNED || operation == IS_MUTABLE_WEAKLY_PINNED
                            ? owner.hasNativeStorage() : owner.isPinned()) ? 1L : 0L;
                    if (bytes instanceof byte[]) return 0L;
                    throw fault("Expected a managed ByteArray#");
                case COMPARE: {
                    long from = operands[1].executeRequiredLong(frame);
                    Object other = operands[2].execute(frame);
                    long to = operands[3].executeRequiredLong(frame), count = operands[4].executeRequiredLong(frame);
                    return ManagedByteArray.compareGuest(bytes, from, other, to, count);
                }
                default: return readLong(bytes, operands[1].executeRequiredLong(frame));
            }
        }
        private float indexedFloat(VirtualFrame frame) {
            Object bytes = operands[0].execute(frame);
            return readFloat(bytes, operands[1].executeRequiredLong(frame));
        }
        private double indexedDouble(VirtualFrame frame) {
            Object bytes = operands[0].execute(frame);
            return readDouble(bytes, operands[1].executeRequiredLong(frame));
        }
        private int readInt(Object bytes, long index) {
            return switch (operation) {
                case INDEX, READ_WORD8 -> ManagedByteArray.readGuest(bytes, index, true);
                case INDEX_INT8, READ_INT8 -> ManagedByteArray.readGuest(bytes, index, false);
                case INDEX_INT16, READ_INT16 -> ManagedByteArray.readInt16Guest(bytes, index, false);
                case INDEX_WORD16, READ_WORD16 -> ManagedByteArray.readInt16Guest(bytes, index, true);
                case INDEX_WORD8_AS_INT16, READ_WORD8_AS_INT16 -> ManagedByteArray.readInt16ByteOffsetGuest(bytes, index, false);
                case INDEX_WORD8_AS_WORD16, READ_WORD8_AS_WORD16 -> ManagedByteArray.readInt16ByteOffsetGuest(bytes, index, true);
                case INDEX_INT32, READ_INT32 -> ManagedByteArray.readInt32Guest(bytes, index, false);
                case INDEX_WORD32, READ_WORD32 -> ManagedByteArray.readInt32Guest(bytes, index, true);
                case INDEX_WORD8_AS_INT32, READ_WORD8_AS_INT32 -> ManagedByteArray.readInt32ByteOffsetGuest(bytes, index, false);
                case INDEX_WORD8_AS_WORD32, READ_WORD8_AS_WORD32 -> ManagedByteArray.readInt32ByteOffsetGuest(bytes, index, true);
                default -> throw new AssertionError(operation);
            };
        }
        private long readLong(Object bytes, long index) {
            if (operation == INDEX_CHAR || operation == READ_CHAR) return ManagedByteArray.readGuest(bytes, index, true);
            if (operation == INDEX_WIDE_CHAR || operation == READ_WIDE_CHAR) {
                int bits = byteOffset ? ManagedByteArray.readInt32ByteOffsetGuest(bytes, index, true) : ManagedByteArray.readInt32Guest(bytes, index, true);
                return Integer.toUnsignedLong(bits);
            }
            return ManagedByteArray.readIntGuest(bytes, index, byteOffset);
        }
        private float readFloat(Object bytes, long index) {
            return operation == INDEX_WORD8_AS_FLOAT || operation == READ_WORD8_AS_FLOAT
                ? ManagedByteArray.readFloatByteOffsetGuest(bytes, index) : ManagedByteArray.readFloatGuest(bytes, index);
        }
        private double readDouble(Object bytes, long index) {
            return operation == INDEX_WORD8_AS_DOUBLE || operation == READ_WORD8_AS_DOUBLE
                ? ManagedByteArray.readDoubleByteOffsetGuest(bytes, index) : ManagedByteArray.readDoubleGuest(bytes, index);
        }
        @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
            if (!operation.tuple) return super.executeTuple(frame, slots, offset);
            if (operation == NEW) {
                long count = operands[0].executeRequiredLong(frame);
                state(frame, 1);
                FrameAccess.INSTANCE.write(frame, slots[offset], ManagedByteArray.allocateGuest(count, thc.Language.currentState(this).getNativeByteArrays()));
                return null;
            }
            Object bytes = operands[0].execute(frame);
            switch (operation) {
                case FREEZE, UNSAFE_THAW:
                    state(frame, 1);
                    FrameAccess.INSTANCE.write(frame, slots[offset], ManagedByteArray.freezeGuest(bytes)); break;
                case GET_SIZE_MUTABLE:
                    state(frame, 1);
                    FrameAccess.INSTANCE.writeLong(frame, slots[offset], ManagedByteArray.sizeGuest(bytes)); break;
                case RESIZE: {
                    long count = operands[1].executeRequiredLong(frame);
                    state(frame, 2);
                    FrameAccess.INSTANCE.write(frame, slots[offset], ManagedByteArray.resizeGuest(bytes, count, thc.Language.currentState(this).getNativeByteArrays())); break;
                }
                default: {
                    long index = operands[1].executeRequiredLong(frame);
                    state(frame, 2);
                    switch (operation) {
                        case READ_INT8, READ_WORD8, READ_INT16, READ_WORD16, READ_WORD8_AS_INT16, READ_WORD8_AS_WORD16,
                             READ_INT32, READ_WORD32, READ_WORD8_AS_INT32, READ_WORD8_AS_WORD32:
                            FrameAccess.INSTANCE.writeInt(frame, slots[offset], readInt(bytes, index)); break;
                        case READ_FLOAT, READ_WORD8_AS_FLOAT:
                            FrameAccess.INSTANCE.writeFloat(frame, slots[offset], readFloat(bytes, index)); break;
                        case READ_DOUBLE, READ_WORD8_AS_DOUBLE:
                            FrameAccess.INSTANCE.writeDouble(frame, slots[offset], readDouble(bytes, index)); break;
                        default: FrameAccess.INSTANCE.writeLong(frame, slots[offset], readLong(bytes, index));
                    }
                }
            }
            return null;
        }
    }
}
