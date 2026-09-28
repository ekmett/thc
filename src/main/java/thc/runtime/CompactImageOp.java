// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.List;
import static thc.runtime.RuntimeServiceStatus.fault;

public enum CompactImageOp {
    FIRST("compactGetFirstBlock#", List.of("region", "state"), List.of("state", "address", "long")),
    NEXT("compactGetNextBlock#", List.of("region", "address", "state"), List.of("state", "address", "long")),
    ALLOCATE("compactAllocateBlock#", List.of("long", "address", "state"), List.of("state", "address")),
    FIXUP("compactFixupPointers#", List.of("address", "address", "state"), List.of("state", "region", "address")),
    TO_ADDRESS("anyToAddr#", List.of("lifted", "state"), List.of("state", "address")),
    FROM_ADDRESS("addrToAny#", List.of("address"), List.of("boxed"));

    private final String primitive;
    private final List<String> roles;
    private final List<String> results;

    CompactImageOp(String primitive, List<String> roles, List<String> results) {
        this.primitive = primitive;
        this.roles = roles;
        this.results = results;
    }

    public String getPrimitive() { return primitive; }

    private static boolean matches(CoreRepresentation proof, String role) {
        return proof.getPresent() && !proof.isAggregate() && !proof.isVector() && switch (role) {
            case "long" -> proof.getKind() == CoreKind.LONG;
            case "address" -> proof.getKind() == CoreKind.ADDRESS;
            case "state" -> proof.getKind() == CoreKind.VOID;
            case "region" -> proof.getKind() == CoreKind.OBJECT && List.of("BoxedRep (Just Unlifted)").equals(proof.getPrimReps());
            case "lifted" -> boxed(proof.getKind()) && List.of("BoxedRep (Just Lifted)").equals(proof.getPrimReps());
            default -> boxed(proof.getKind()) && proof.getPrimReps() != null && proof.getPrimReps().size() == 1 &&
                ("BoxedRep (Just Lifted)".equals(proof.getPrimReps().get(0)) ||
                    "BoxedRep (Just Unlifted)".equals(proof.getPrimReps().get(0)));
        };
    }

    private static boolean boxed(CoreKind kind) {
        return kind == CoreKind.DATA || kind == CoreKind.CLOSURE || kind == CoreKind.OBJECT;
    }

    public void validate(List<CoreRepresentation> arguments, List<?> flags, CoreRepresentation result) {
        if (arguments.size() != roles.size() || flags.size() != roles.size()) throw fault("Invalid " + primitive + " arguments");
        for (int i = 0; i < roles.size(); i++)
            if (!Boolean.valueOf("lifted".equals(roles.get(i))).equals(flags.get(i))) throw fault("Invalid " + primitive + " arguments");
        for (int i = 0; i < roles.size(); i++)
            if (!matches(arguments.get(i), roles.get(i))) throw fault("Invalid " + primitive + " arguments");
        var components = result.getComponents();
        if (!result.isTuple() || components == null || components.size() != results.size()) throw fault("Invalid " + primitive + " result");
        for (int i = 0; i < results.size(); i++)
            if (!matches(components.get(i), results.get(i))) throw fault("Invalid " + primitive + " result");
        var reps = new ArrayList<String>();
        for (var component : components) reps.addAll(component.getPrimReps());
        if (!reps.equals(result.getPrimReps())) throw fault("Invalid " + primitive + " result");
    }

    public static CompactImageOp named(String name) {
        for (var operation : values()) if (operation.primitive.equals(name)) return operation;
        return null;
    }
}
