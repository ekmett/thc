// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import java.util.Collections;
import java.util.List;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Narrow CAS uses Int carriers; machine and 64-bit operations use Long. */
public enum AtomicIntArrayOp {
    READ("atomicReadIntArray#", 8, 0), WRITE("atomicWriteIntArray#"),
    ADD("fetchAddIntArray#"), SUB("fetchSubIntArray#"), AND("fetchAndIntArray#"), NAND("fetchNandIntArray#"),
    OR("fetchOrIntArray#"), XOR("fetchXorIntArray#"), CAS("casIntArray#", 8, 2),
    CAS8("casInt8Array#", 1, 2), CAS16("casInt16Array#", 2, 2), CAS32("casInt32Array#", 4, 2), CAS64("casInt64Array#", 8, 2);
    private final String primitive;
    private final int width, operands;
    AtomicIntArrayOp(String primitive) { this(primitive, 8, 1); }
    AtomicIntArrayOp(String primitive, int width, int operands) { this.primitive = primitive; this.width = width; this.operands = operands; }
    public String getPrimitive() { return primitive; }
    public int getWidth() { return width; }
    public int getOperands() { return operands; }
    public boolean getTuple() { return this != WRITE; }
    private static boolean scalar(CoreRepresentation proof, CoreKind kind) {
        return !proof.isAggregate() && !proof.isVector() && proof.getKind() == kind;
    }
    public void validate(List<CoreRepresentation> arguments, List<?> flags, CoreRepresentation result) {
        boolean valid = arguments.size() == operands + 3 && Collections.nCopies(arguments.size(), false).equals(flags)
            && scalar(arguments.get(0), CoreKind.OBJECT) && scalar(arguments.get(arguments.size() - 1), CoreKind.VOID)
            && arguments.get(1).isLong();
        for (int i = 2; valid && i < arguments.size() - 1; i++) {
            var proof = arguments.get(i);
            valid = !proof.isTypedTransport() && (width < 8 ? proof.isInt() : proof.isLong());
        }
        if (!valid) throw fault("Atomic byte-array primitive argument carrier mismatch: " + primitive);
        var components = result.getComponents();
        valid = getTuple() ? result.isTuple() && result.getKind() == CoreKind.UNKNOWN && components.size() == 2 &&
            result.getPrimReps() != null && result.getPrimReps().size() == 1 && scalar(components.get(0), CoreKind.VOID) &&
            !components.get(1).isTypedTransport() && (width < 8 ? components.get(1).isInt() : components.get(1).isLong())
            : scalar(result, CoreKind.VOID);
        if (!valid) throw fault("Atomic byte-array primitive result carrier mismatch: " + primitive);
    }
    public int executeInt(Object value, long index, int operand, int replacement) {
        if (!(value instanceof ManagedAllocation allocation)) {
            CompilerDirectives.transferToInterpreter();
            throw fault(primitive + " requires an owned MutableByteArray#");
        }
        return allocation.atomicNarrowInt(index, operand, replacement, this);
    }
    public long execute(Object value, long index, long operand, long replacement) {
        if (!(value instanceof ManagedAllocation allocation)) {
            CompilerDirectives.transferToInterpreter();
            throw fault(primitive + " requires an owned MutableByteArray#");
        }
        return allocation.atomicInt(index, operand, replacement, this);
    }
    public static AtomicIntArrayOp named(String name) {
        for (var operation : values()) if (operation.primitive.equals(name)) return operation;
        return null;
    }
}
