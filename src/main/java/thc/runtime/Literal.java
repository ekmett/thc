// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

final class Literal extends Expr {
    private final Object value;
    Literal(Object value) {
        this.value = value;
        CoreKind kind = switch (value) {
            case Integer ignored -> CoreKind.LONG;
            case Long ignored -> CoreKind.LONG;
            case Float ignored -> CoreKind.FLOAT;
            case Double ignored -> CoreKind.DOUBLE;
            case ManagedAddress ignored -> CoreKind.ADDRESS;
            case null, default -> value == thc.runtime.Unit.INSTANCE ? CoreKind.VOID : CoreKind.OBJECT;
        };
        setRepresentation(new CoreRepresentation(kind, true, false, null, null, null, null, null, null));
    }
    @Override public Object execute(VirtualFrame frame) { return value; }
    @Override public int executeInt(VirtualFrame frame) throws UnexpectedResultException { return RuntimeTypesGen.expectInteger(value); }
    @Override public long executeLong(VirtualFrame frame) throws UnexpectedResultException { return RuntimeTypesGen.expectLong(value); }
    @Override public float executeFloat(VirtualFrame frame) throws UnexpectedResultException { return RuntimeTypesGen.expectFloat(value); }
    @Override public double executeDouble(VirtualFrame frame) throws UnexpectedResultException { return RuntimeTypesGen.expectDouble(value); }
}
