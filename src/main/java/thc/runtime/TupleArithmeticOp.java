// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import java.util.List;

/** GHC 9.14.1 machine-width tuple primops writing exact scalar destinations. */
enum TupleArithmeticOp {
    QUOT_REM_INT("quotRemInt#"), QUOT_REM_WORD("quotRemWord#"),
    QUOT_REM_INT8("quotRemInt8#", 2, 2, 8, false),
    QUOT_REM_INT16("quotRemInt16#", 2, 2, 16, false),
    QUOT_REM_INT32("quotRemInt32#", 2, 2, 32, false),
    QUOT_REM_WORD8("quotRemWord8#", 2, 2, 8, true),
    QUOT_REM_WORD16("quotRemWord16#", 2, 2, 16, true),
    QUOT_REM_WORD32("quotRemWord32#", 2, 2, 32, true),
    QUOT_REM_WORD_2("quotRemWord2#", 2, 3, 64, false),
    ADD_INT_C("addIntC#"), SUB_INT_C("subIntC#"),
    ADD_WORD_C("addWordC#"), SUB_WORD_C("subWordC#"),
    PLUS_WORD_2("plusWord2#"), TIMES_WORD_2("timesWord2#"),
    TIMES_INT_2("timesInt2#", 3, 2, 64, false);

    private final String primitive;
    private final int resultArity;
    private final int argumentArity;
    private final int narrowBits;
    private final boolean unsigned;

    TupleArithmeticOp(String primitive) { this(primitive, 2, 2, 64, false); }
    TupleArithmeticOp(String primitive, int resultArity, int argumentArity, int narrowBits, boolean unsigned) {
        this.primitive = primitive;
        this.resultArity = resultArity;
        this.argumentArity = argumentArity;
        this.narrowBits = narrowBits;
        this.unsigned = unsigned;
    }

    public String getPrimitive() { return primitive; }
    public int getResultArity() { return resultArity; }
    public int getArgumentArity() { return argumentArity; }
    public boolean isInt() { return narrowBits < 64; }

    private int narrowInt(int value) {
        if (narrowBits == 32) return value;
        if (unsigned) return value & ((1 << narrowBits) - 1);
        return (value << (32 - narrowBits)) >> (32 - narrowBits);
    }

    public int firstInt(int left, int right) {
        if (!isInt()) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("Expected narrow tuple arithmetic");
        }
        int x = narrowInt(left), y = narrowInt(right);
        if (y == 0) {
            CompilerDirectives.transferToInterpreter();
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("Undefined input to " + primitive);
        }
        return narrowInt(unsigned ? Integer.divideUnsigned(x, y) : x / y);
    }

    public int secondInt(int left, int right) {
        if (!isInt()) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("Expected narrow tuple arithmetic");
        }
        int x = narrowInt(left), y = narrowInt(right);
        if (y == 0) {
            CompilerDirectives.transferToInterpreter();
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("Undefined input to " + primitive);
        }
        return narrowInt(unsigned ? Integer.remainderUnsigned(x, y) : x % y);
    }

    private void divisionDomain(long left, long right) {
        if (right == 0L || this == QUOT_REM_INT && left == Long.MIN_VALUE && right == -1L) {
            CompilerDirectives.transferToInterpreter();
            throw new RuntimeFault("Undefined input to " + primitive);
        }
    }

    public long first(long left, long right) {
        return switch (this) {
            case QUOT_REM_INT -> { divisionDomain(left, right); yield left / right; }
            case QUOT_REM_WORD -> { divisionDomain(left, right); yield Long.divideUnsigned(left, right); }
            case QUOT_REM_INT8, QUOT_REM_INT16, QUOT_REM_INT32, QUOT_REM_WORD8, QUOT_REM_WORD16, QUOT_REM_WORD32 -> {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new RuntimeFault("Narrow tuple arithmetic requires Int operands");
            }
            case QUOT_REM_WORD_2 -> throw new IllegalStateException("Double-word division needs three operands");
            case ADD_INT_C, ADD_WORD_C -> left + right;
            case SUB_INT_C, SUB_WORD_C -> left - right;
            case PLUS_WORD_2 -> Long.compareUnsigned(left + right, left) < 0 ? 1L : 0L;
            case TIMES_WORD_2 -> Math.unsignedMultiplyHigh(left, right);
            case TIMES_INT_2 -> Math.multiplyHigh(left, right) == ((left * right) >> 63) ? 0L : 1L;
        };
    }

    public long second(long left, long right) {
        return switch (this) {
            case QUOT_REM_INT -> { divisionDomain(left, right); yield left % right; }
            case QUOT_REM_WORD -> { divisionDomain(left, right); yield Long.remainderUnsigned(left, right); }
            case QUOT_REM_INT8, QUOT_REM_INT16, QUOT_REM_INT32, QUOT_REM_WORD8, QUOT_REM_WORD16, QUOT_REM_WORD32 -> {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new RuntimeFault("Narrow tuple arithmetic requires Int operands");
            }
            case QUOT_REM_WORD_2 -> throw new IllegalStateException("Double-word division needs three operands");
            case ADD_INT_C -> ((left ^ (left + right)) & (right ^ (left + right))) < 0 ? 1L : 0L;
            case SUB_INT_C -> ((left ^ right) & (left ^ (left - right))) < 0 ? 1L : 0L;
            case ADD_WORD_C -> Long.compareUnsigned(left + right, left) < 0 ? 1L : 0L;
            case SUB_WORD_C -> Long.compareUnsigned(left, right) < 0 ? 1L : 0L;
            case PLUS_WORD_2 -> left + right;
            case TIMES_WORD_2 -> left * right;
            case TIMES_INT_2 -> Math.multiplyHigh(left, right);
        };
    }

    public long third(long left, long right) {
        if (this != TIMES_INT_2) throw new IllegalStateException("Check failed.");
        return left * right;
    }

    private boolean scalar(CoreRepresentation rep) {
        return !rep.isTypedTransport() && (isInt() ? rep.isInt() : rep.isLong());
    }

    public void validate(List<CoreRepresentation> arguments, List<?> lifted, CoreRepresentation result) {
        if (arguments.size() != argumentArity) throw new RuntimeFault("Primitive arity mismatch: " + primitive);
        if (lifted.size() != argumentArity)
            throw new RuntimeFault("Tuple primitive argument representation mismatch: " + primitive);
        for (int i = 0; i < argumentArity; i++) {
            if (!Boolean.FALSE.equals(lifted.get(i)) || !scalar(arguments.get(i)))
                throw new RuntimeFault("Tuple primitive argument representation mismatch: " + primitive);
        }
        List<CoreRepresentation> components = result.getComponents();
        if (!result.isTuple() || components.size() != resultArity)
            throw new RuntimeFault("Tuple primitive result representation mismatch: " + primitive);
        for (CoreRepresentation component : components) {
            if (!scalar(component)) throw new RuntimeFault("Tuple primitive result representation mismatch: " + primitive);
        }
    }

    public static TupleArithmeticOp named(String name) {
        return switch (name) {
            case "quotRemInt#" -> QUOT_REM_INT;
            case "quotRemWord#" -> QUOT_REM_WORD;
            case "quotRemInt8#" -> QUOT_REM_INT8;
            case "quotRemInt16#" -> QUOT_REM_INT16;
            case "quotRemInt32#" -> QUOT_REM_INT32;
            case "quotRemWord8#" -> QUOT_REM_WORD8;
            case "quotRemWord16#" -> QUOT_REM_WORD16;
            case "quotRemWord32#" -> QUOT_REM_WORD32;
            case "quotRemWord2#" -> QUOT_REM_WORD_2;
            case "addIntC#" -> ADD_INT_C;
            case "subIntC#" -> SUB_INT_C;
            case "addWordC#" -> ADD_WORD_C;
            case "subWordC#" -> SUB_WORD_C;
            case "plusWord2#" -> PLUS_WORD_2;
            case "timesWord2#" -> TIMES_WORD_2;
            case "timesInt2#" -> TIMES_INT_2;
            default -> null;
        };
    }
}
