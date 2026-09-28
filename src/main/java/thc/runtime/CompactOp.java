// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import static thc.runtime.RuntimeServiceStatus.fault;

public enum CompactOp {
    NEW("compactNew#", List.of("long", "state"), "region"),
    RESIZE("compactResize#", List.of("region", "long", "state"), "state"),
    ADD("compactAdd#", List.of("region", "lifted", "state"), "lifted"),
    ADD_SHARING("compactAddWithSharing#", List.of("region", "lifted", "state"), "lifted"),
    CONTAINS("compactContains#", List.of("region", "lifted", "state"), "long"),
    CONTAINS_ANY("compactContainsAny#", List.of("lifted", "state"), "long"),
    SIZE("compactSize#", List.of("region", "state"), "long");
    private static final String[] FAILURES = {"ghc-internal:GHC.Internal.IO.Exception.cannotCompactFunction",
        "ghc-internal:GHC.Internal.IO.Exception.cannotCompactPinned", "ghc-internal:GHC.Internal.IO.Exception.cannotCompactMutable"};
    private final String primitive, result;
    private final List<String> roles;
    CompactOp(String primitive, List<String> roles, String result) { this.primitive = primitive; this.roles = roles; this.result = result; }
    public String getPrimitive() { return primitive; }
    public String getResult() { return result; }
    public boolean getAdds() { return this == ADD || this == ADD_SHARING; }
    public static String[] getFailures() { return FAILURES; }
    public static CompactOp named(String name) {
        for (var operation : values()) if (operation.primitive.equals(name)) return operation;
        return null;
    }
    public void validate(List<CoreRepresentation> arguments, List<?> flags, CoreRepresentation proof) {
        boolean valid = arguments.size() == roles.size() && flags.size() == roles.size();
        if (valid) for (int index = 0; index < roles.size(); index++)
            if (!Objects.equals(flags.get(index), roles.get(index).equals("lifted")) || !matches(arguments.get(index), roles.get(index))) { valid = false; break; }
        if (!valid) throw fault("Invalid " + primitive + " arguments");
        if (this == RESIZE) valid = matches(proof, "state");
        else {
            var fields = proof.getComponents();
            valid = proof.isTuple() && fields != null && fields.size() == 2 && matches(fields.get(0), "state") && matches(fields.get(1), result);
            if (valid) {
                var reps = new ArrayList<String>();
                for (var field : fields) reps.addAll(Objects.requireNonNull(field.getPrimReps()));
                valid = Objects.equals(proof.getPrimReps(), reps);
            }
        }
        if (!valid) throw fault("Invalid " + primitive + " result");
    }
    private static boolean matches(CoreRepresentation value, String role) {
        if (!value.getPresent() || value.isAggregate() || value.isVector()) return false;
        return switch (role) {
            case "state" -> value.getKind() == CoreKind.VOID;
            case "long" -> value.getKind() == CoreKind.LONG;
            case "region" -> value.getKind() == CoreKind.OBJECT && List.of("BoxedRep (Just Unlifted)").equals(value.getPrimReps());
            default -> (value.getKind() == CoreKind.DATA || value.getKind() == CoreKind.CLOSURE || value.getKind() == CoreKind.OBJECT) && List.of("BoxedRep (Just Lifted)").equals(value.getPrimReps());
        };
    }
}
