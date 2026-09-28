// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;

public enum ClosureInspectOp {
    UNPACK("unpackClosure#", 1), SIZE("closureSize#", 1), AP_STACK("getApStackVal#", 2), CCS("getCCSOf#", 2), WHERE("whereFrom#", 3);
    private final String primitive;
    private final int arity;
    ClosureInspectOp(String primitive, int arity) { this.primitive = primitive; this.arity = arity; }
    public String getPrimitive() { return primitive; }
    public int getArity() { return arity; }
    private static boolean role(CoreRepresentation proof, CoreKind kind) { return !proof.isAggregate() && !proof.isVector() && proof.getKind() == kind; }
    private static boolean reference(CoreRepresentation proof) {
        return !proof.isAggregate() && !proof.isVector() && (proof.getKind() == CoreKind.OBJECT || proof.getKind() == CoreKind.DATA || proof.getKind() == CoreKind.CLOSURE);
    }
    public void validate(List<CoreRepresentation> arguments, List<?> flags, CoreRepresentation result) {
        boolean validFlags = flags.size() == arity;
        if (validFlags) for (int i = 0; i < arity; i++) if (!Boolean.valueOf(i == 0).equals(flags.get(i))) { validFlags = false; break; }
        if (arguments.size() != arity || !validFlags || !reference(arguments.getFirst()) ||
            (this == AP_STACK && !role(arguments.get(1), CoreKind.LONG)) ||
            (this == WHERE && !role(arguments.get(1), CoreKind.ADDRESS)) ||
            ((this == CCS || this == WHERE) && !role(arguments.getLast(), CoreKind.VOID)))
            throw RuntimeFault.fault(primitive + ": invalid closure inspection operands");
        List<CoreRepresentation> fields = result.getComponents() == null ? List.of() : result.getComponents();
        boolean valid = switch (this) {
            case SIZE -> role(result, CoreKind.LONG);
            case UNPACK -> result.isTuple() && fields.size() == 3 && role(fields.get(0), CoreKind.ADDRESS) && role(fields.get(1), CoreKind.OBJECT) && role(fields.get(2), CoreKind.OBJECT);
            case AP_STACK -> result.isTuple() && fields.size() == 2 && role(fields.get(0), CoreKind.LONG) && reference(fields.get(1));
            case CCS, WHERE -> result.isTuple() && fields.size() == 2 && role(fields.get(0), CoreKind.VOID) && role(fields.get(1), this == CCS ? CoreKind.ADDRESS : CoreKind.LONG);
        };
        if (!valid) throw RuntimeFault.fault(primitive + ": invalid closure inspection result");
    }
    public static ClosureInspectOp named(String name) {
        for (ClosureInspectOp operation : values()) if (operation.primitive.equals(name)) return operation;
        return null;
    }
}
