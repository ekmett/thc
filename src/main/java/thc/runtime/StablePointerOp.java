// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;
import java.util.Objects;
import static thc.runtime.RuntimeServiceStatus.fault;

public enum StablePointerOp {
    MAKE("makeStablePtr#", true), DEREFERENCE("deRefStablePtr#", true), EQUAL("eqStablePtr#", false);

    private final String primitive;
    private final boolean tuple;
    StablePointerOp(String primitive, boolean tuple) { this.primitive = primitive; this.tuple = tuple; }
    public String getPrimitive() { return primitive; }
    public boolean getTuple() { return tuple; }

    private static boolean address(CoreRepresentation proof) {
        return !proof.isAggregate() && !proof.isVector() && proof.getKind() == CoreKind.ADDRESS && List.of("AddrRep").equals(proof.getPrimReps());
    }

    private static boolean state(CoreRepresentation proof) {
        return !proof.isAggregate() && !proof.isVector() && proof.getKind() == CoreKind.VOID && List.of().equals(proof.getPrimReps());
    }

    private static boolean lifted(CoreRepresentation proof) {
        return !proof.isAggregate() && !proof.isVector() &&
            (proof.getKind() == CoreKind.DATA || proof.getKind() == CoreKind.CLOSURE || proof.getKind() == CoreKind.OBJECT) &&
            List.of("BoxedRep (Just Lifted)").equals(proof.getPrimReps());
    }

    public void validate(List<CoreRepresentation> arguments, List<?> flags, CoreRepresentation result) {
        boolean inputs = switch (this) {
            case MAKE -> arguments.size() == 2 && lifted(arguments.get(0)) && state(arguments.get(1)) && List.of(true, false).equals(flags);
            case DEREFERENCE -> arguments.size() == 2 && address(arguments.get(0)) && state(arguments.get(1)) && List.of(false, false).equals(flags);
            case EQUAL -> arguments.size() == 2 && address(arguments.get(0)) && address(arguments.get(1)) && List.of(false, false).equals(flags);
        };
        if (!inputs) throw fault("StablePtr# primitive argument representation mismatch: " + primitive);
        var components = result.getComponents();
        boolean output = this == EQUAL ? !result.isAggregate() && !result.isVector() &&
            result.getKind() == CoreKind.LONG && List.of("IntRep").equals(result.getPrimReps()) :
            result.isTuple() && components != null && components.size() == 2 && state(components.get(0)) &&
            (this == MAKE ? address(components.get(1)) : lifted(components.get(1))) &&
            Objects.equals(result.getPrimReps(), components.get(1).getPrimReps());
        if (!output) throw fault("StablePtr# primitive result representation mismatch: " + primitive);
    }

    public static StablePointerOp named(String name) {
        for (var operation : values()) if (operation.primitive.equals(name)) return operation;
        return null;
    }
}
