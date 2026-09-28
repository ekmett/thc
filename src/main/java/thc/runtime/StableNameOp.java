// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;

public enum StableNameOp {
    MAKE("makeStableName#"), HASH("stableNameToInt#");
    private final String primitive;
    StableNameOp(String primitive) { this.primitive = primitive; }
    public String getPrimitive() { return primitive; }
    private static boolean scalar(CoreRepresentation rep) { return !rep.isAggregate() && !rep.isVector(); }
    private static boolean state(CoreRepresentation rep) { return scalar(rep) && rep.getKind() == CoreKind.VOID; }
    private static boolean name(CoreRepresentation rep) {
        return scalar(rep) && rep.getKind() == CoreKind.OBJECT && List.of("BoxedRep (Just Unlifted)").equals(rep.getPrimReps());
    }
    private static boolean boxed(CoreRepresentation rep) {
        return scalar(rep) && (rep.getKind() == CoreKind.OBJECT || rep.getKind() == CoreKind.DATA || rep.getKind() == CoreKind.CLOSURE) &&
            (List.of("BoxedRep (Just Lifted)").equals(rep.getPrimReps()) || List.of("BoxedRep (Just Unlifted)").equals(rep.getPrimReps()));
    }
    public void validate(List<CoreRepresentation> arguments, List<?> flags, CoreRepresentation result) {
        var fields = result.getComponents();
        boolean valid = switch (this) {
            case MAKE -> arguments.size() == 2 && boxed(arguments.getFirst()) && state(arguments.get(1)) &&
                flags.equals(List.of(List.of("BoxedRep (Just Lifted)").equals(arguments.getFirst().getPrimReps()), false)) &&
                result.isTuple() && fields != null && fields.size() == 2 && state(fields.getFirst()) && name(fields.get(1));
            case HASH -> arguments.size() == 1 && name(arguments.getFirst()) && flags.equals(List.of(false)) &&
                scalar(result) && result.getKind() == CoreKind.LONG;
        };
        if (!valid) throw new RuntimeFault("StableName# primitive carrier or shape mismatch: " + primitive);
    }
    public static StableNameOp named(String name) {
        return switch (name) { case "makeStableName#" -> MAKE; case "stableNameToInt#" -> HASH; default -> null; };
    }
}
