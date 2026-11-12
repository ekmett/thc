// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.MaterializedFrame;

/** Scalar indexing owns operand order; only an interrupted prefix needs a saved recipe. */
public final class IndexManagedScalarAddress extends Expr {
    private final ManagedAddressRead operation;
    @Child private Expr address, element;
    public IndexManagedScalarAddress(ManagedAddressRead operation, Expr address, Expr element) { this.operation = operation; this.address = address; this.element = element; }
    static ManagedAddressRead operation(String name) {
        return switch (name) {
            case "indexCharOffAddr#" -> ManagedAddressRead.CHAR;
            case "indexWord16OffAddr#" -> ManagedAddressRead.WORD16;
            case "indexInt16OffAddr#" -> ManagedAddressRead.INT16;
            default -> null;
        };
    }
    @Override public Object execute(VirtualFrame frame) { if (operation.isInt()) return executeInt(frame); return executeLong(frame); }
    @Override public int executeInt(VirtualFrame frame) {
        ManagedAddress value = address(frame, true);
        return operation.readInt(value, element(frame, value, true));
    }
    @Override public long executeLong(VirtualFrame frame) {
        ManagedAddress value = address(frame, false);
        return operation.read(value, element(frame, value, false));
    }
    private ManagedAddress address(VirtualFrame frame, boolean integerResult) {
        try { return address.executeRequiredAddress(frame); }
        catch (AstCapture cut) { throw appendAddress(cut, integerResult); }
        catch (DelimitedCut cut) {
            CompilerDirectives.transferToInterpreter();
            throw appendAddress(frame.materialize(), cut, integerResult);
        }
    }
    private long element(VirtualFrame frame, ManagedAddress value, boolean integerResult) {
        try { return element.executeRequiredLong(frame); }
        catch (AstCapture cut) { throw appendElement(cut, value, integerResult); }
        catch (DelimitedCut cut) {
            CompilerDirectives.transferToInterpreter();
            throw appendElement(frame.materialize(), cut, value, integerResult);
        }
    }
    private Object afterAddress(VirtualFrame frame, Object input, boolean integerResult) {
        ManagedAddress value = requiredAddress(input);
        return read(value, element(frame, value, integerResult), integerResult);
    }
    private Object read(ManagedAddress value, long index, boolean integerResult) {
        // Preserve the entered route, including its required result carrier.
        if (integerResult) return operation.readInt(value, index);
        return operation.read(value, index);
    }
    @TruffleBoundary(transferToInterpreterOnException = false)
    private AstCapture appendAddress(AstCapture cut, boolean integerResult) {
        return cut.append((saved, input) -> afterAddress(saved, input, integerResult));
    }
    @TruffleBoundary(transferToInterpreterOnException = false)
    private AstCapture appendElement(AstCapture cut, ManagedAddress value, boolean integerResult) {
        return cut.append((saved, input) -> read(value, requiredLong(input), integerResult));
    }
    @TruffleBoundary(transferToInterpreterOnException = false)
    private DelimitedCut appendAddress(MaterializedFrame frame, DelimitedCut cut, boolean integerResult) {
        return cut.append(frame, (saved, input, ambient, outer) -> afterAddress(saved, input.get(), integerResult));
    }
    @TruffleBoundary(transferToInterpreterOnException = false)
    private DelimitedCut appendElement(MaterializedFrame frame, DelimitedCut cut, ManagedAddress value, boolean integerResult) {
        return cut.append(frame, (saved, input, ambient, outer) -> read(value, requiredLong(input.get()), integerResult));
    }
    private static ManagedAddress requiredAddress(Object value) {
        if (value instanceof ManagedAddress address) return address;
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeFault("Expected a managed literal Addr#");
    }
    private static long requiredLong(Object value) {
        if (value instanceof Long number) return number;
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeFault("Expected primitive Long");
    }
}
