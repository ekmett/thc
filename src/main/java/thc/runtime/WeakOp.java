// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import kotlin.Pair;
import static thc.runtime.RuntimeServiceStatus.fault;

public enum WeakOp {
    MAKE("mkWeak#", List.of("boxed", "boxed", "action", "state"), List.of("state", "weak")),
    MAKE_PLAIN("mkWeakNoFinalizer#", List.of("boxed", "boxed", "state"), List.of("state", "weak")),
    ADD_C_FINALIZER("addCFinalizerToWeak#", List.of("address", "address", "flag", "address", "weak", "state"), List.of("state", "flag")),
    DEREFERENCE("deRefWeak#", List.of("weak", "state"), List.of("state", "flag", "boxed")),
    FINALIZE("finalizeWeak#", List.of("weak", "state"), List.of("state", "flag", "action"));

    private static final String LIFTED = "BoxedRep (Just Lifted)", UNLIFTED = "BoxedRep (Just Unlifted)";
    private final String primitive;
    private final List<String> arguments, resultRoles;
    WeakOp(String primitive, List<String> arguments, List<String> resultRoles) {
        this.primitive = primitive; this.arguments = arguments; this.resultRoles = resultRoles;
    }
    public String getPrimitive() { return primitive; }
    public void validate(List<CoreRepresentation> actual, List<?> flags, CoreRepresentation result) {
        if (actual.size() != arguments.size() || flags.size() != arguments.size())
            throw fault("Weak primitive argument representation mismatch: " + primitive);
        for (int index = 0; index < actual.size(); index++)
            if (!matches(actual.get(index), arguments.get(index)) ||
                !Objects.equals(flags.get(index), List.of(LIFTED).equals(actual.get(index).getPrimReps())))
                throw fault("Weak primitive argument representation mismatch: " + primitive);
        var fields = result.getComponents();
        boolean valid = result.getKind() == CoreKind.UNKNOWN && result.isTuple() && !result.isSum() && !result.isVector() &&
            fields.size() == resultRoles.size();
        if (valid) {
            var flattened = new ArrayList<String>();
            for (int index = 0; index < fields.size(); index++) {
                var field = fields.get(index);
                if (!matches(field, resultRoles.get(index))) { valid = false; break; }
                flattened.addAll(Objects.requireNonNull(field.getPrimReps()));
            }
            valid = valid && Objects.equals(result.getPrimReps(), flattened);
        }
        if (!valid) throw fault("Weak primitive result representation mismatch: " + primitive);
    }
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
                throw fault("Weak primitive argument contradicts its binding proof: " + primitive);
        }
    }
    public void validateAction(Pair<? extends List<CoreRepresentation>, CoreRepresentation> signature) {
        if (this != MAKE || signature == null) return;
        var inputs = signature.getFirst();
        var result = signature.getSecond();
        var fields = result.getComponents();
        if (inputs.size() != 1 || !matches(inputs.getFirst(), "state") || result.getKind() != CoreKind.UNKNOWN ||
            !result.isTuple() || result.isSum() || result.isVector() || fields.size() != 2 ||
            !matches(fields.get(0), "state") || !matches(fields.get(1), "boxed") ||
            !List.of(LIFTED).equals(fields.get(1).getPrimReps()) || !Objects.equals(result.getPrimReps(), fields.get(1).getPrimReps()))
            throw fault("mkWeak# finalizer requires State# -> (# State#, lifted value #)");
    }
    public static WeakOp named(String name) {
        for (var operation : values()) if (operation.primitive.equals(name)) return operation;
        return null;
    }
    private static boolean boxed(List<String> reps) { return List.of(LIFTED).equals(reps) || List.of(UNLIFTED).equals(reps); }
    private static boolean matches(CoreRepresentation proof, String role) {
        if (proof.isAggregate() || proof.isVector()) return false;
        return switch (role) {
            case "state" -> proof.getKind() == CoreKind.VOID && List.of().equals(proof.getPrimReps());
            case "weak" -> proof.getKind() == CoreKind.OBJECT && List.of(UNLIFTED).equals(proof.getPrimReps());
            case "flag" -> proof.getKind() == CoreKind.LONG && List.of("IntRep").equals(proof.getPrimReps());
            case "address" -> proof.getKind() == CoreKind.ADDRESS && List.of("AddrRep").equals(proof.getPrimReps());
            case "action" -> (proof.getKind() == CoreKind.CLOSURE || proof.getKind() == CoreKind.OBJECT) && List.of(LIFTED).equals(proof.getPrimReps());
            default -> (proof.getKind() == CoreKind.DATA || proof.getKind() == CoreKind.CLOSURE || proof.getKind() == CoreKind.OBJECT) && boxed(proof.getPrimReps());
        };
    }
}
