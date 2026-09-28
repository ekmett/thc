// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** GHC 9.14.1 boxed-at-known-levity payloads, not arbitrary scalar RuntimeRep. */
public enum STMOp {
    ATOMICALLY("atomically#", List.of("action", "state"), List.of("state", "boxed")),
    RETRY("retry#", List.of("state"), List.of("state", "boxed")),
    OR_ELSE("catchRetry#", List.of("action", "action", "state"), List.of("state", "boxed")),
    CATCH("catchSTM#", List.of("action", "action", "state"), List.of("state", "boxed")),
    NEW("newTVar#", List.of("boxed", "state"), List.of("state", "tvar")),
    READ("readTVar#", List.of("tvar", "state"), List.of("state", "boxed")),
    READ_IO("readTVarIO#", List.of("tvar", "state"), List.of("state", "boxed")),
    WRITE("writeTVar#", List.of("tvar", "boxed", "state"), List.of("state"));

    public static final String NESTED = "ghc-internal:GHC.Internal.Control.Exception.Base.nestedAtomically";
    private static final String LIFTED = "BoxedRep (Just Lifted)";
    private static final String UNLIFTED = "BoxedRep (Just Unlifted)";
    private final String primitive;
    private final List<String> arguments;
    private final List<String> results;
    STMOp(String primitive, List<String> arguments, List<String> results) {
        this.primitive = primitive; this.arguments = arguments; this.results = results;
    }
    public String getPrimitive() { return primitive; }
    public List<String> getArguments() { return arguments; }
    public List<String> getResults() { return results; }
    public boolean getCallback() { return this == ATOMICALLY || this == OR_ELSE || this == CATCH; }
    public static STMOp named(String name) {
        for (var operation : values()) if (operation.primitive.equals(name)) return operation;
        return null;
    }
    public void validate(List<CoreRepresentation> actual, List<?> flags, CoreRepresentation result) {
        boolean valid = actual.size() == arguments.size() && flags.size() == arguments.size();
        if (valid) for (int i = 0; i < actual.size(); i++) {
            if (!matches(actual.get(i), arguments.get(i)) ||
                !Objects.equals(flags.get(i), Objects.equals(actual.get(i).getPrimReps(), List.of(LIFTED)))) { valid = false; break; }
        }
        if (!valid) throw new RuntimeFault("STM primitive argument representation mismatch: " + primitive);
        var parts = result.getComponents();
        if (this == WRITE) valid = matches(result, "state");
        else {
            valid = result.getKind() == CoreKind.UNKNOWN && result.isTuple() && !result.isSum() && !result.isVector() &&
                parts != null && parts.size() == results.size();
            if (valid) for (int i = 0; i < parts.size(); i++) if (!matches(parts.get(i), results.get(i))) { valid = false; break; }
            if (valid) {
                var reps = new ArrayList<String>();
                for (var part : parts) reps.addAll(Objects.requireNonNull(part.getPrimReps()));
                valid = Objects.equals(result.getPrimReps(), reps);
            }
        }
        if (!valid) throw new RuntimeFault("STM primitive result representation mismatch: " + primitive);
    }
    private static boolean matches(CoreRepresentation proof, String role) {
        if (proof.isAggregate() || proof.isVector()) return false;
        return switch (role) {
            case "state" -> proof.getKind() == CoreKind.VOID && Objects.equals(proof.getPrimReps(), List.of());
            case "tvar" -> proof.getKind() == CoreKind.OBJECT && Objects.equals(proof.getPrimReps(), List.of(UNLIFTED));
            case "action" -> proof.getKind() == CoreKind.CLOSURE && Objects.equals(proof.getPrimReps(), List.of(LIFTED));
            default -> (proof.getKind() == CoreKind.DATA || proof.getKind() == CoreKind.CLOSURE || proof.getKind() == CoreKind.OBJECT) &&
                proof.getPrimReps() != null && proof.getPrimReps().size() == 1 &&
                (LIFTED.equals(proof.getPrimReps().getFirst()) || UNLIFTED.equals(proof.getPrimReps().getFirst()));
        };
    }
}
