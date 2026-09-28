// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** GHC's MVar payload is boxed at a known levity, not an arbitrary RuntimeRep. */
public enum MVarOp {
    NEW("newMVar#", List.of("state"), List.of("state", "mvar")),
    TAKE("takeMVar#", List.of("mvar", "state"), List.of("state", "boxed")),
    PUT("putMVar#", List.of("mvar", "boxed", "state"), List.of("state")),
    READ("readMVar#", List.of("mvar", "state"), List.of("state", "boxed")),
    TRY_TAKE("tryTakeMVar#", List.of("mvar", "state"), List.of("state", "flag", "boxed")),
    TRY_PUT("tryPutMVar#", List.of("mvar", "boxed", "state"), List.of("state", "flag")),
    TRY_READ("tryReadMVar#", List.of("mvar", "state"), List.of("state", "flag", "boxed")),
    IS_EMPTY("isEmptyMVar#", List.of("mvar", "state"), List.of("state", "flag"));

    private static final String LIFTED = "BoxedRep (Just Lifted)";
    private static final String UNLIFTED = "BoxedRep (Just Unlifted)";
    private final String primitive;
    private final List<String> arguments, resultRoles;

    MVarOp(String primitive, List<String> arguments, List<String> resultRoles) {
        this.primitive = primitive;
        this.arguments = arguments;
        this.resultRoles = resultRoles;
    }
    public String getPrimitive() { return primitive; }
    public boolean getTuple() { return this != PUT; }

    public void validateBindings(List<CoreRepresentation> actual, List<CoreRepresentation> stored) {
        for (int index = 0; index < actual.size(); index++) {
            var binding = stored.get(index);
            if (binding == null || binding.getPrimReps() == null) continue;
            var occurrence = actual.get(index);
            var refined = binding;
            if (List.of("BoxedRep Nothing").equals(binding.getPrimReps()) && boxed(occurrence.getPrimReps()))
                refined = new CoreRepresentation(binding.getKind(), binding.getEvaluated(), binding.getPresent(),
                    occurrence.getPrimReps(), binding.getComponents(), binding.getVector(), binding.getAlternatives(),
                    binding.getTagSlot(), binding.getAlternativeSlots());
            if (refined.isAggregate() || refined.isVector() || !Objects.equals(refined.getPrimReps(), occurrence.getPrimReps()) ||
                refined.getKind() != CoreKind.UNKNOWN && !matches(refined, arguments.get(index)))
                throw new RuntimeFault("MVar argument contradicts its binding proof: " + primitive);
        }
    }

    public void validate(List<CoreRepresentation> actual, List<?> flags, CoreRepresentation result) {
        if (actual.size() != arguments.size() || flags.size() != arguments.size())
            throw new RuntimeFault("Primitive arity mismatch: " + primitive);
        for (int index = 0; index < actual.size(); index++)
            if (!matches(actual.get(index), arguments.get(index)) ||
                !Objects.equals(flags.get(index), List.of(LIFTED).equals(actual.get(index).getPrimReps())))
                throw new RuntimeFault("MVar primitive argument representation mismatch: " + primitive);
        boolean valid;
        if (getTuple()) {
            var components = result.getComponents();
            valid = result.getKind() == CoreKind.UNKNOWN && result.isTuple() && !result.isSum() && !result.isVector() &&
                components.size() == resultRoles.size();
            if (valid) {
                var flattened = new ArrayList<String>();
                for (int index = 0; index < components.size(); index++) {
                    var component = components.get(index);
                    if (!matches(component, resultRoles.get(index))) { valid = false; break; }
                    flattened.addAll(Objects.requireNonNull(component.getPrimReps()));
                }
                valid = valid && Objects.equals(result.getPrimReps(), flattened);
            }
        } else valid = matches(result, "state");
        if (!valid) throw new RuntimeFault("MVar primitive result representation mismatch: " + primitive);
    }

    public static MVarOp named(String name) {
        for (var operation : values()) if (operation.primitive.equals(name)) return operation;
        return null;
    }
    private static boolean boxed(List<String> reps) {
        return List.of(LIFTED).equals(reps) || List.of(UNLIFTED).equals(reps);
    }
    private static boolean matches(CoreRepresentation proof, String role) {
        if (proof.isAggregate() || proof.isVector()) return false;
        return switch (role) {
            case "state" -> proof.getKind() == CoreKind.VOID && List.of().equals(proof.getPrimReps());
            case "mvar" -> proof.getKind() == CoreKind.OBJECT && List.of(UNLIFTED).equals(proof.getPrimReps());
            case "flag" -> proof.getKind() == CoreKind.LONG && List.of("IntRep").equals(proof.getPrimReps());
            default -> (proof.getKind() == CoreKind.DATA || proof.getKind() == CoreKind.CLOSURE || proof.getKind() == CoreKind.OBJECT) &&
                boxed(proof.getPrimReps());
        };
    }
}
