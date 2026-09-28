// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeFault.fault;

final class Primitive extends Expr {
    private final String operation;
    private final long wordMask;
    private final int bitShift;
    private final long bitMask;
    @Children private Expr[] arguments;

    Primitive(String name, Expr[] arguments) {
        this.arguments = arguments;
        operation = Scalar64Primitives.scalar64PrimitiveOperation(name);
        wordMask = switch (name) {
            case "narrow8Word#" -> 0xffL;
            case "narrow16Word#" -> 0xffffL;
            case "narrow32Word#" -> 0xffff_ffffL;
            default -> 0L;
        };
        bitShift = BitPrimitives.scalarBitPrimitiveShift(name);
        bitMask = -1L >>> bitShift;
        setRepresentation(new CoreRepresentation(CoreKind.LONG, true, false, null, null, null, null, null, null));
        int arity = arity(name);
        if (arity < 0) throw new UnsupportedCore("Unsupported primitive " + name);
        if (arguments.length != arity) throw new RuntimeFault("Primitive arity mismatch: " + name);
        if (operation.equals("mulIntMayOflo#")) {
            for (Expr argument : arguments) {
                if (!argument.getRepresentation().isLong()) throw new RuntimeFault("Primitive requires Long operands: " + name);
            }
        }
    }
    static int arity(String name) {
        return switch (Scalar64Primitives.scalar64PrimitiveOperation(name)) {
            case "popCnt8#", "popCnt16#", "popCnt32#", "popCnt64#",
                 "clz8#", "clz16#", "clz32#", "clz64#",
                 "ctz8#", "ctz16#", "ctz32#", "ctz64#",
                 "byteSwap16#", "byteSwap32#", "byteSwap64#", "byteSwap#",
                 "bitReverse8#", "bitReverse16#", "bitReverse32#", "bitReverse64#", "bitReverse#",
                 "negateInt#", "not#", "notI#", "clz#", "ctz#", "popCnt#", "int2Word#", "word2Int#", "ord#", "chr#",
                 "narrow8Int#", "narrow16Int#", "narrow32Int#", "intToInt64#", "int64ToInt#",
                 "narrow8Word#", "narrow16Word#", "narrow32Word#" -> 1;
            case "pdep8#", "pdep16#", "pdep32#", "pdep64#", "pdep#",
                 "pext8#", "pext16#", "pext32#", "pext64#", "pext#",
                 "mulIntMayOflo#", "quotWord#", "remWord#", "gtWord#", "geWord#",
                 "+#", "plusWord#", "-#", "minusWord#", "*#", "timesWord#", "quotInt#", "remInt#",
                 "==#", "eqWord#", "eqChar#", "/=#", "neWord#", "neChar#", "<#", "ltWord#", "ltChar#", "<=#", "leWord#", "leChar#",
                 ">#", "gtChar#", ">=#", "geChar#", "and#", "andI#", "or#", "orI#", "xor#", "xorI#",
                 "uncheckedIShiftL#", "uncheckedShiftL#", "uncheckedIShiftRA#", "uncheckedIShiftRL#", "uncheckedShiftRL#" -> 2;
            default -> -1;
        };
    }
    @Override public Object execute(VirtualFrame frame) { return executeLong(frame); }
    @Override public long executeLong(VirtualFrame frame) {
        long x = arguments[0].executeRequiredLong(frame);
        long y = arguments.length == 2 ? arguments[1].executeRequiredLong(frame) : 0L;
        return switch (operation) {
            case "popCnt8#", "popCnt16#", "popCnt32#", "popCnt64#" -> Long.bitCount(x & bitMask);
            case "clz8#", "clz16#", "clz32#", "clz64#" -> Long.numberOfLeadingZeros(x & bitMask) - bitShift;
            case "ctz8#", "ctz16#", "ctz32#", "ctz64#" -> Math.min(Long.numberOfTrailingZeros(x & bitMask), 64 - bitShift);
            case "byteSwap16#", "byteSwap32#", "byteSwap64#", "byteSwap#" -> Long.reverseBytes(x) >>> bitShift;
            case "bitReverse8#", "bitReverse16#", "bitReverse32#", "bitReverse64#", "bitReverse#" -> Long.reverse(x) >>> bitShift;
            case "pdep8#", "pdep16#", "pdep32#", "pdep64#", "pdep#" -> Long.expand(x & bitMask, y & bitMask) & bitMask;
            case "pext8#", "pext16#", "pext32#", "pext64#", "pext#" -> Long.compress(x & bitMask, y & bitMask) & bitMask;
            case "mulIntMayOflo#" -> Math.multiplyHigh(x, y) != ((x * y) >> 63) ? 1L : 0L;
            case "quotWord#" -> Long.divideUnsigned(x, y);
            case "remWord#" -> Long.remainderUnsigned(x, y);
            case "gtWord#" -> Long.compareUnsigned(x, y) > 0 ? 1L : 0L;
            case "geWord#" -> Long.compareUnsigned(x, y) >= 0 ? 1L : 0L;
            case "+#", "plusWord#" -> x + y;
            case "-#", "minusWord#" -> x - y;
            case "*#", "timesWord#" -> x * y;
            case "negateInt#" -> -x;
            case "quotInt#" -> x / y;
            case "remInt#" -> x % y;
            case "==#", "eqWord#", "eqChar#" -> x == y ? 1L : 0L;
            case "/=#", "neWord#", "neChar#" -> x != y ? 1L : 0L;
            case "<#", "ltChar#" -> x < y ? 1L : 0L;
            case "ltWord#" -> Long.compareUnsigned(x, y) < 0 ? 1L : 0L;
            case "<=#", "leChar#" -> x <= y ? 1L : 0L;
            case "leWord#" -> Long.compareUnsigned(x, y) <= 0 ? 1L : 0L;
            case ">#", "gtChar#" -> x > y ? 1L : 0L;
            case ">=#", "geChar#" -> x >= y ? 1L : 0L;
            case "and#", "andI#" -> x & y;
            case "or#", "orI#" -> x | y;
            case "xor#", "xorI#" -> x ^ y;
            case "not#", "notI#" -> ~x;
            case "clz#" -> Long.numberOfLeadingZeros(x);
            case "ctz#" -> Long.numberOfTrailingZeros(x);
            case "popCnt#" -> Long.bitCount(x);
            case "uncheckedIShiftL#", "uncheckedShiftL#" -> x << (int) y;
            case "uncheckedIShiftRA#" -> x >> (int) y;
            case "uncheckedIShiftRL#", "uncheckedShiftRL#" -> x >>> (int) y;
            // Machine Int# narrowing retains its Long carrier.
            case "narrow8Int#" -> (byte) x;
            case "narrow16Int#" -> (short) x;
            case "narrow32Int#" -> (int) x;
            case "narrow8Word#", "narrow16Word#", "narrow32Word#" -> x & wordMask;
            case "int2Word#", "word2Int#", "ord#", "chr#", "intToInt64#", "int64ToInt#" -> x;
            default -> throw fault("Unsupported primitive");
        };
    }
}
