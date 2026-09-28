// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import static thc.runtime.RuntimeFault.fault;

/** Keep each operand in its exact primitive local until the interop call. */
public final class BytecodePackageScalarArguments {
    private final PackageScalarCall call;
    @CompilationFinal(dimensions = 1) private final LocalAccessor[] slots;
    private final LocalAccessor state;
    public BytecodePackageScalarArguments(PackageScalarCall call, LocalAccessor[] slots, LocalAccessor state) {
        this.call = call; this.slots = slots; this.state = state;
    }
    public PackageScalarCall getCall() { return call; }
    @ExplodeLoop public Object[] read(BytecodeNode bytecode, VirtualFrame frame) {
        Object[] values = new Object[slots.length];
        try { for (int i = 0; i < slots.length; i++) values[i] = switch (call.getArguments()[i]) {
            case "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep", "Int32Rep", "Word32Rep" -> PackageScalarAccess.packageCInteger(call.getArguments()[i], slots[i].getInt(bytecode, frame));
            case "IntRep", "WordRep", "Int64Rep", "Word64Rep" -> slots[i].getLong(bytecode, frame);
            case "FloatRep" -> slots[i].getFloat(bytecode, frame);
            case "DoubleRep" -> slots[i].getDouble(bytecode, frame);
            case "AddrRep", "ByteArray#", "MutableByteArray#" -> slots[i].getObject(bytecode, frame);
            default -> throw fault("Invalid package C argument local");
        }; } catch (com.oracle.truffle.api.nodes.UnexpectedResultException failure) { throw rethrow(failure); }
        return values;
    }
    public Object state(BytecodeNode bytecode, VirtualFrame frame) { return state.getObject(bytecode, frame); }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException rethrow(Throwable failure) throws E { throw (E) failure; }
}
