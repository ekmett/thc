// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CoreRepresentation {
    public enum HostCarrier { OBJECT, INTEROP_LIBRARY }
    private final CoreKind kind;
    private final boolean evaluated;
    private final boolean present;
    private final List<String> primReps;
    private final List<CoreRepresentation> components;
    private final CoreVector vector;
    private final List<CoreRepresentation> alternatives;
    private final Integer tagSlot;
    private final List<List<Integer>> alternativeSlots;
    private final NarrowInteger narrowInteger;
    private final boolean evaluatedUnliftedObject;
    private final boolean emptyTuple;
    private final int boxedLevity;
    private final HostCarrier hostCarrier;

    public static final CoreRepresentation UNKNOWN = new CoreRepresentation(CoreKind.UNKNOWN);

    public CoreRepresentation(CoreKind kind) { this(kind, false); }
    public CoreRepresentation(CoreKind kind, boolean evaluated) { this(kind, evaluated, false); }
    public CoreRepresentation(CoreKind kind, boolean evaluated, boolean present) { this(kind, evaluated, present, null); }
    public CoreRepresentation(CoreKind kind, boolean evaluated, boolean present, List<String> primReps) {
        this(kind, evaluated, present, primReps, null);
    }
    public CoreRepresentation(CoreKind kind, boolean evaluated, boolean present, List<String> primReps,
                              List<CoreRepresentation> components) {
        this(kind, evaluated, present, primReps, components, null);
    }
    public CoreRepresentation(CoreKind kind, boolean evaluated, boolean present, List<String> primReps,
                              List<CoreRepresentation> components, CoreVector vector) {
        this(kind, evaluated, present, primReps, components, vector, null, null, null);
    }
    public CoreRepresentation(CoreKind kind, boolean evaluated, boolean present, List<String> primReps,
                              List<CoreRepresentation> components, CoreVector vector,
                              List<CoreRepresentation> alternatives, Integer tagSlot, List<List<Integer>> alternativeSlots) {
        this(kind, evaluated, present, primReps, components, vector, alternatives, tagSlot, alternativeSlots, null);
    }
    private CoreRepresentation(CoreKind kind, boolean evaluated, boolean present, List<String> primReps,
                              List<CoreRepresentation> components, CoreVector vector,
                              List<CoreRepresentation> alternatives, Integer tagSlot, List<List<Integer>> alternativeSlots,
                              HostCarrier hostCarrier) {
        this.kind = Objects.requireNonNull(kind);
        this.evaluated = evaluated;
        this.present = present;
        this.primReps = primReps;
        this.components = components;
        this.vector = vector;
        this.alternatives = alternatives;
        this.tagSlot = tagSlot;
        this.alternativeSlots = alternativeSlots;
        this.hostCarrier = hostCarrier;
        if (hostCarrier != null && !(present && kind == CoreKind.OBJECT && components == null && alternatives == null && vector == null &&
                Objects.equals(primReps, List.of("BoxedRep (Just Unlifted)"))))
            throw new RuntimeFault("Nominal host carrier requires an exact unlifted boxed reference");
        boxedLevity = boxedKind(kind) && primReps != null && primReps.size() == 1 ? switch (primReps.getFirst()) {
            case "BoxedRep Nothing" -> 0;
            case "BoxedRep (Just Lifted)" -> 1;
            case "BoxedRep (Just Unlifted)" -> 2;
            default -> -1;
        } : -1;
        // Exact lowered PrimRep, not the broad exported kind, selects the carrier.
        narrowInteger = kind == CoreKind.LONG ? NarrowInteger.fromRep(
            primReps != null && primReps.size() == 1 ? primReps.getFirst() : null) : null;
        // Cache owned load-time metadata: list equality in a frame read can make
        // a VirtualFrame escape during partial evaluation.
        evaluatedUnliftedObject = evaluated && kind == CoreKind.OBJECT &&
            Objects.equals(primReps, List.of("BoxedRep (Just Unlifted)"));
        emptyTuple = present && kind == CoreKind.UNKNOWN && components != null && components.isEmpty() &&
            primReps != null && primReps.isEmpty();
    }

    public CoreKind getKind() { return kind; }
    public boolean getEvaluated() { return evaluated; }
    public boolean getPresent() { return present; }
    public List<String> getPrimReps() { return primReps; }
    public List<CoreRepresentation> getComponents() { return components; }
    public CoreVector getVector() { return vector; }
    public List<CoreRepresentation> getAlternatives() { return alternatives; }
    public Integer getTagSlot() { return tagSlot; }
    public List<List<Integer>> getAlternativeSlots() { return alternativeSlots; }
    public NarrowInteger getNarrowInteger() { return narrowInteger; }
    public boolean isInt() { return narrowInteger != null; }
    public HostCarrier getHostCarrier() { return hostCarrier; }
    public CoreRepresentation withHostCarrier(HostCarrier value) {
        return new CoreRepresentation(kind, evaluated, present, primReps, components, vector, alternatives, tagSlot, alternativeSlots, value);
    }
    public boolean isEvaluatedUnliftedObject() { return evaluatedUnliftedObject; }
    public boolean hasUnknownBoxedLevity() { return boxedLevity == 0; }
    public boolean hasBoxedPointer() { return boxedLevity >= 0; }
    public boolean isVector() { return vector != null; }
    public boolean isTuple() { return components != null; }
    public boolean isSum() { return alternatives != null; }
    public boolean isAggregate() { return isTuple() || isSum(); }
    /** A vector remains one logical value and one raw reference in typed transport. */
    public boolean isTypedTransport() { return isAggregate() || isVector(); }
    public boolean isEmptyTuple() { return emptyTuple; }
    public boolean isLong() { return kind == CoreKind.LONG && !isInt(); }
    public boolean isFloat() { return kind == CoreKind.FLOAT; }
    public boolean isDouble() { return kind == CoreKind.DOUBLE; }
    public boolean isEvaluatedReference() {
        return evaluated && (kind == CoreKind.DATA || kind == CoreKind.CLOSURE || kind == CoreKind.ADDRESS);
    }
    /** A lifted type alone cannot exclude a thunk; a stored WHNF proof can. */
    public Class<?> referenceCarrier() {
        if (!evaluated) return null;
        return switch (kind) {
            case DATA -> DataValue.class;
            case CLOSURE -> Closure.class;
            case ADDRESS -> ManagedAddress.class;
            default -> null;
        };
    }

    public CoreRepresentation copy() {
        return copy(kind, evaluated, present, primReps, components, vector, alternatives, tagSlot, alternativeSlots);
    }
    public CoreRepresentation copy(CoreKind kind, boolean evaluated, boolean present, List<String> primReps,
                                   List<CoreRepresentation> components, CoreVector vector,
                                   List<CoreRepresentation> alternatives, Integer tagSlot, List<List<Integer>> alternativeSlots) {
        return new CoreRepresentation(kind, evaluated, present, primReps, components, vector, alternatives, tagSlot, alternativeSlots, hostCarrier);
    }
    public CoreRepresentation withEvaluated(boolean value) {
        return copy(kind, value, present, primReps, components, vector, alternatives, tagSlot, alternativeSlots);
    }
    public CoreRepresentation withPrimReps(List<String> value) {
        return copy(kind, evaluated, present, value, components, vector, alternatives, tagSlot, alternativeSlots);
    }

    public CoreRepresentation refine(CoreRepresentation other) {
        if (hostCarrier != null && other.hostCarrier != null && hostCarrier != other.hostCarrier)
            throw new RuntimeFault("Conflicting nominal host carriers");
        // GHC checks scalar types; only the lowered Int/Long carrier differs here.
        if (kind == CoreKind.LONG && other.kind == CoreKind.LONG && primReps != null && other.primReps != null && isInt() != other.isInt())
            throw new RuntimeFault("Conflicting Core integral carriers");
        String boxed = exactBoxedRep(), otherBoxed = other.exactBoxedRep();
        if (boxed != null && otherBoxed != null && !boxed.equals(otherBoxed))
            throw new RuntimeFault("Conflicting Core boxed levity proofs: " + boxed + " and " + otherBoxed);
        if ((isVector() || other.isVector()) && present && other.present && !Objects.equals(vector, other.vector))
            throw new RuntimeFault("Conflicting Core vector representation proofs");
        if (isAggregate() && other.isAggregate() && !TupleShape.compatible(this, other))
            throw new RuntimeFault("Conflicting logical aggregate representation proofs");
        if (isAggregate() && !other.isAggregate() && (other.kind != CoreKind.UNKNOWN || other.primReps != null) ||
            other.isAggregate() && !isAggregate() && (kind != CoreKind.UNKNOWN || primReps != null))
            throw new RuntimeFault("Conflicting scalar and aggregate representation proofs");
        CoreKind merged;
        if (kind == CoreKind.UNKNOWN) merged = other.kind;
        else if (other.kind == CoreKind.UNKNOWN || kind == other.kind) merged = kind;
        else if (boxedKind(kind) && boxedKind(other.kind)) merged = other.kind == CoreKind.OBJECT ? kind : other.kind;
        else throw new RuntimeFault("Conflicting Core representation proofs: " + kind + " and " + other.kind);
        // Unknown levity cannot erase an existing exact proof and hide a later contradiction.
        List<String> mergedReps = boxed != null && Objects.equals(other.primReps, List.of("BoxedRep Nothing")) ? primReps :
            other.primReps != null ? other.primReps : primReps;
        List<CoreRepresentation> mergedComponents = refineChildren(components, other.components);
        if (components != null && other.components != null) {
            List<String> reps = new ArrayList<>();
            for (CoreRepresentation component : mergedComponents) {
                if (component.primReps == null) reps = null;
                else if (reps != null) reps.addAll(component.primReps);
            }
            mergedReps = reps == null ? null : List.copyOf(reps);
        }
        var result = new CoreRepresentation(merged, evaluated || other.evaluated, present || other.present, mergedReps,
            mergedComponents, other.vector != null ? other.vector : vector,
            refineChildren(alternatives, other.alternatives), other.tagSlot != null ? other.tagSlot : tagSlot,
            other.alternativeSlots != null ? other.alternativeSlots : alternativeSlots,
            hostCarrier != null ? hostCarrier : other.hostCarrier);
        return result.isSum() ? SumShape.relayout(result) : result;
    }
    private static List<CoreRepresentation> refineChildren(List<CoreRepresentation> left, List<CoreRepresentation> right) {
        if (left == null) return right;
        if (right == null) return left;
        var merged = new ArrayList<CoreRepresentation>(left.size());
        for (int i = 0; i < left.size(); i++) merged.add(left.get(i).refine(right.get(i)));
        return List.copyOf(merged);
    }
    private static boolean boxedKind(CoreKind kind) {
        return kind == CoreKind.OBJECT || kind == CoreKind.DATA || kind == CoreKind.CLOSURE;
    }
    private String exactBoxedRep() {
        if (!present || !boxedKind(kind) || primReps == null || primReps.size() != 1) return null;
        String rep = primReps.getFirst();
        return "BoxedRep (Just Lifted)".equals(rep) || "BoxedRep (Just Unlifted)".equals(rep) ? rep : null;
    }
    @Override public boolean equals(Object value) {
        return this == value || value instanceof CoreRepresentation other && kind == other.kind &&
            evaluated == other.evaluated && present == other.present && Objects.equals(primReps, other.primReps) &&
            Objects.equals(components, other.components) && Objects.equals(vector, other.vector) &&
            Objects.equals(alternatives, other.alternatives) && Objects.equals(tagSlot, other.tagSlot) &&
            Objects.equals(alternativeSlots, other.alternativeSlots) && hostCarrier == other.hostCarrier;
    }
    @Override public int hashCode() {
        int result = kind.hashCode();
        result = 31 * result + Boolean.hashCode(evaluated);
        result = 31 * result + Boolean.hashCode(present);
        result = 31 * result + Objects.hashCode(primReps);
        result = 31 * result + Objects.hashCode(components);
        result = 31 * result + Objects.hashCode(vector);
        result = 31 * result + Objects.hashCode(alternatives);
        result = 31 * result + Objects.hashCode(tagSlot);
        result = 31 * result + Objects.hashCode(alternativeSlots);
        return 31 * result + Objects.hashCode(hostCarrier);
    }
    @Override public String toString() {
        return "CoreRepresentation(kind=" + kind + ", evaluated=" + evaluated + ", present=" + present +
            ", primReps=" + primReps + ", components=" + components + ", vector=" + vector +
            ", alternatives=" + alternatives + ", tagSlot=" + tagSlot + ", alternativeSlots=" + alternativeSlots + ")";
    }
}
