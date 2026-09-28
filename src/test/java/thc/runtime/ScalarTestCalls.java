// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;

/** Internal scalar tests supply exact JVM carriers and still call the original
 * compiled target. Narrow formals use the typed packet convention; this is
 * not the public numeric conversion boundary and performs no guest warmup. */
public final class ScalarTestCalls {
    private ScalarTestCalls() {}
    public static Object callScalarTestTarget(RootCallTarget target, Object[] arguments) {
        var entry = target.getRootNode() instanceof GuestRoot root ? root.getTypedInput() : null;
        if (entry == null) return Calls.target(target, arguments);
        var logical = entry.getLogical();
        if (logical.getPhysicalArity() != logical.getLogicalArity()) throw new RuntimeFault("Scalar test caller cannot flatten aggregate inputs");
        for (int i = 0; i < logical.getLogicalArity(); i++) if (logical.isTyped(i))
            throw new RuntimeFault("Scalar test caller cannot flatten aggregate inputs");
        if (arguments.length != entry.getHeader() + logical.getPhysicalArity()) throw new RuntimeFault("Wrong scalar test argument count");
        var shape = entry.getPacket(); var storage = entry.state().getArguments().acquire(shape); storage.setInputMode(1);
        try {
            for (int index = 0; index < arguments.length; index++) {
                var value = arguments[index];
                if (shape.isInt(index)) {
                    if (!(value instanceof Integer integer)) throw new RuntimeFault("Expected Int test argument");
                    shape.setInt(storage, index, integer);
                } else if (shape.isLong(index)) {
                    if (!(value instanceof Long integer)) throw new RuntimeFault("Expected Long test argument");
                    shape.setLong(storage, index, integer);
                } else if (shape.isFloat(index)) {
                    if (!(value instanceof Float number)) throw new RuntimeFault("Expected Float test argument");
                    shape.setFloat(storage, index, number);
                } else if (shape.isDouble(index)) {
                    if (!(value instanceof Double number)) throw new RuntimeFault("Expected Double test argument");
                    shape.setDouble(storage, index, number);
                } else shape.setObject(storage, index, value);
            }
            return TypedInputsKt.invokeTypedInput(entry, storage, input -> Calls.target(target, input));
        } finally { entry.releaseChecked(storage); }
    }
}
