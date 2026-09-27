// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Lowering-time operation descriptor. Machine-width narrow8/16/32Int#/Word#
 * are deliberately absent: those consume and produce machine Long values. */
final class NarrowScalarOp {
    enum Code {
        CONVERT(""), NEGATE("negate"), ADD("plus"), SUB("sub"), MUL("times"),
        QUOT("quot"), REM("rem"), EQ("eq"), NE("ne"), LT("lt"), LE("le"), GT("gt"), GE("ge"),
        AND("and"), OR("or"), XOR("xor"), NOT("not"),
        SHL("uncheckedShiftL"), SRA("uncheckedShiftRA"), SRL("uncheckedShiftRL");

        private final String prefix;
        Code(String prefix) { this.prefix = prefix; }
    }

    private final NarrowInteger integer;
    private final Code code;
    private final boolean sourceLong;
    private final boolean resultLong;
    private final boolean unary;
    private final boolean shift;
    private final CoreRepresentation result;

    private NarrowScalarOp(NarrowInteger integer, Code code, boolean sourceLong, boolean resultLong) {
        this.integer = integer;
        this.code = code;
        this.sourceLong = sourceLong;
        this.resultLong = resultLong;
        unary = code == Code.CONVERT || code == Code.NEGATE || code == Code.NOT;
        shift = code == Code.SHL || code == Code.SRA || code == Code.SRL;
        String resultRep = !resultLong ? integer.getRep()
                : code == Code.CONVERT && integer.getUnsigned() ? "WordRep" : "IntRep";
        result = new CoreRepresentation(CoreKind.LONG, true, true, List.of(resultRep), null, null, null, null, null);
    }

    public NarrowInteger getInteger() { return integer; }
    public Code getCode() { return code; }
    public boolean getSourceLong() { return sourceLong; }
    public boolean getResultLong() { return resultLong; }
    public boolean getUnary() { return unary; }
    public boolean getShift() { return shift; }
    public CoreRepresentation getResult() { return result; }

    /** Lowering checks actual carrier/aggregate differences, not scalar names
     * that share a carrier. An unconstrained legacy operand is checked at use. */
    public void validateOperand(CoreRepresentation proof, int index) {
        boolean wide = sourceLong && index == 0 || shift && index == 1;
        if (proof.isTypedTransport() || proof.getKind() != CoreKind.UNKNOWN && (wide ? !proof.isLong() : !proof.isInt())) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("Narrow primitive operand carrier mismatch");
        }
    }

    public int intResult(int left, int right) {
        int x = integer.narrow(left), y = integer.narrow(right);
        int value = switch (code) {
            case CONVERT -> x;
            case NEGATE -> -x;
            case ADD -> x + y;
            case SUB -> x - y;
            case MUL -> x * y;
            case QUOT -> integer.getUnsigned() ? Integer.divideUnsigned(x, y) : x / y;
            case REM -> integer.getUnsigned() ? Integer.remainderUnsigned(x, y) : x % y;
            case AND -> x & y;
            case OR -> x | y;
            case XOR -> x ^ y;
            case NOT -> ~x;
            case SHL -> x << right;
            case SRA -> x >> right;
            case SRL -> (integer.getBits() == 32 ? x : x & ((1 << integer.getBits()) - 1)) >>> right;
            default -> {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new RuntimeFault("Comparison requires a machine Int# result");
            }
        };
        return integer.narrow(value);
    }

    public long longResult(int left, int right) {
        if (code == Code.CONVERT) return integer.widen(left);
        int x = integer.narrow(left), y = integer.narrow(right);
        int order = integer.getUnsigned() ? Integer.compareUnsigned(x, y) : Integer.compare(x, y);
        boolean value = switch (code) {
            case EQ -> order == 0;
            case NE -> order != 0;
            case LT -> order < 0;
            case LE -> order <= 0;
            case GT -> order > 0;
            case GE -> order >= 0;
            default -> {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new RuntimeFault("Narrow arithmetic requires an Int carrier");
            }
        };
        return value ? 1L : 0L;
    }

    private static final Map<String, NarrowScalarOp> OPERATIONS;
    static {
        Map<String, NarrowScalarOp> operations = new HashMap<>();
        for (NarrowInteger integer : NarrowInteger.values()) {
            String family = integer.getRep().substring(0, integer.getRep().length() - 3);
            String lower = Character.toLowerCase(family.charAt(0)) + family.substring(1);
            String machine = integer.getUnsigned() ? "word" : "int";
            operations.put(machine + "To" + family + "#", new NarrowScalarOp(integer, Code.CONVERT, true, false));
            operations.put(lower + "To" + (integer.getUnsigned() ? "Word" : "Int") + "#",
                    new NarrowScalarOp(integer, Code.CONVERT, false, true));
            String other = (integer.getUnsigned() ? "int" : "word") + integer.getBits();
            operations.put(other + "To" + family + "#", new NarrowScalarOp(integer, Code.CONVERT, false, false));
            for (Code code : Code.values()) {
                if (code == Code.CONVERT) continue;
                if (integer.getUnsigned() && (code == Code.NEGATE || code == Code.SRA)) continue;
                if (!integer.getUnsigned() && (code == Code.AND || code == Code.OR || code == Code.XOR || code == Code.NOT)) continue;
                boolean resultLong = switch (code) { case EQ, NE, LT, LE, GT, GE -> true; default -> false; };
                operations.put(code.prefix + family + "#", new NarrowScalarOp(integer, code, false, resultLong));
            }
        }
        OPERATIONS = Map.copyOf(operations);
    }

    public static NarrowScalarOp named(String name) { return OPERATIONS.get(name); }
}
