// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import java.util.List;
import static thc.runtime.RuntimeServiceStatus.fault;
public enum AddressArrayCopyOp {
    TO_ARRAY("copyAddrToByteArray#", true), FROM_ARRAY("copyByteArrayToAddr#", false), FROM_MUTABLE_ARRAY("copyMutableByteArrayToAddr#", false);
    private final String primitive;
    private final boolean toArray;
    AddressArrayCopyOp(String primitive, boolean toArray) { this.primitive = primitive; this.toArray = toArray; }
    public String getPrimitive() { return primitive; }
    public boolean getToArray() { return toArray; }
    public void validate(List<CoreRepresentation> arguments, List<?> flags, CoreRepresentation result) {
        var kinds = toArray ? List.of(CoreKind.ADDRESS, CoreKind.OBJECT, CoreKind.LONG, CoreKind.LONG, CoreKind.VOID) :
            List.of(CoreKind.OBJECT, CoreKind.LONG, CoreKind.ADDRESS, CoreKind.LONG, CoreKind.VOID);
        if (arguments.size() != kinds.size() || !flags.equals(List.of(false, false, false, false, false)))
            throw fault("Array/address copy operand carrier or arity mismatch: " + primitive);
        for (int i = 0; i < kinds.size(); i++) if (arguments.get(i).getKind() != kinds.get(i) || arguments.get(i).isAggregate() || arguments.get(i).isVector())
            throw fault("Array/address copy operand carrier or arity mismatch: " + primitive);
        if (result.getKind() != CoreKind.VOID || result.isAggregate() || result.isVector())
            throw fault("Array/address copy requires scalar State#: " + primitive);
    }
    public static AddressArrayCopyOp named(String name) { for (var operation : values()) if (operation.primitive.equals(name)) return operation; return null; }
}
