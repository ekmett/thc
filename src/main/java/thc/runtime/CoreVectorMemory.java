// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Exact proof boundary for local State/vector reads. */
public final class CoreVectorMemory {
    private CoreVectorMemory() {}
    public static final CoreRepresentation stateProof =
        new CoreRepresentation(CoreKind.VOID, true, true, List.of(), null, null, null, null, null);
    public static final CoreRepresentation arrayProof =
        new CoreRepresentation(CoreKind.OBJECT, true, true, List.of("BoxedRep (Just Unlifted)"), null, null, null, null, null);
    public static final CoreRepresentation addressProof =
        new CoreRepresentation(CoreKind.ADDRESS, true, true, List.of("AddrRep"), null, null, null, null, null);
    public static final CoreRepresentation indexProof =
        new CoreRepresentation(CoreKind.LONG, true, true, List.of("IntRep"), null, null, null, null, null);
    public static CoreRepresentation getStateProof() { return stateProof; }
    public static boolean exact(CoreRepresentation expected, CoreRepresentation actual) {
        return actual.getPresent() && !actual.isTuple() && actual.getKind() == expected.getKind()
            && Objects.equals(actual.getPrimReps(), expected.getPrimReps())
            && Objects.equals(actual.getVector(), expected.getVector());
    }
    private static void requireProof(boolean condition, String detail) {
        if (!condition) throw new RuntimeFault("Invalid local vector read case: " + detail);
    }
    private static boolean exactInteger(Object value, long expected) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() == expected;
    }
    private static boolean vectorAnnotation(Object raw, CoreRepresentation proof) {
        return raw instanceof Map<?, ?> value && value.keySet().equals(Set.of("lanes", "element"))
            && exactInteger(value.get("lanes"), proof.getVector().getLanes())
            && proof.getVector().getElement().equals(value.get("element"));
    }
    /** Validate raw VecRep metadata before generic parsing normalizes lane counts. */
    private static void readResult(Object raw, boolean binder, CoreRepresentation proof) {
        if (!(raw instanceof Map<?, ?> value)) throw new RuntimeFault("Missing local vector read result proof");
        var components = value.get("components") instanceof List<?> list ? list : null;
        requireProof("unknown".equals(value.get("kind")) && "unboxed-tuple".equals(value.get("aggregate"))
            && Objects.equals(value.get("primReps"), proof.getPrimReps()) && vectorAnnotation(value.get("vector"), proof)
            && value.get("evaluated") instanceof Boolean && (!binder || Boolean.TRUE.equals(value.get("evaluated")))
            && components != null && components.size() == 2, "expected exact State/vector proof for " + proof.getVector());
        requireProof(components.get(1) instanceof Map<?, ?> vector && vectorAnnotation(vector.get("vector"), proof)
            && exact(stateProof, CoreRepresentations.INSTANCE.parse(components.get(0)))
            && exact(proof, CoreRepresentations.INSTANCE.parse(components.get(1))), "result components");
    }
    private static boolean termUses(Object value, String id) {
        if (value instanceof List<?> list) {
            if (!list.isEmpty() && "var".equals(list.getFirst()) && list.size() > 1 && id.equals(list.get(1))) return true;
            for (Object item : list) if (termUses(item, id)) return true;
        } else if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) if (termUses(item, id)) return true;
        }
        return false;
    }
    private static Object at(List<?> list, int index) { return index < list.size() ? list.get(index) : null; }

    @SuppressWarnings("unchecked")
    public static VectorReadCase readCase(List<?> expr, Map<String, ? extends Map<String, ?>> constructors) {
        if (!"case".equals(at(expr, 0)) || !(at(expr, 1) instanceof List<?> app)
            || !"app".equals(at(app, 0)) || !(at(app, 1) instanceof List<?> function)
            || !"prim".equals(at(function, 0)) || !(at(function, 1) instanceof String name)) return null;
        var operation = VectorMemoryOp.named(name);
        if (operation == null || !operation.isRead()) return null;
        if (!(at(app, 2) instanceof List<?> rawArguments)) throw new RuntimeFault("Missing local vector read arguments");
        var arguments = new ArrayList<List<Object>>(rawArguments.size());
        for (Object raw : rawArguments) {
            if (!(raw instanceof List<?> argument)) throw new RuntimeFault("Invalid local vector read argument");
            arguments.add((List<Object>) argument);
        }
        if (!(at(app, 3) instanceof List<?> flags)) throw new RuntimeFault("Missing local vector read flags");
        var proofs = new ArrayList<CoreRepresentation>(arguments.size());
        for (var argument : arguments) proofs.add(CoreRepresentations.INSTANCE.expression(argument));
        operation.validateArguments(proofs, flags);
        readResult(at(app, 6) instanceof Map<?, ?> metadata ? metadata.get("rep") : null, false, operation.getVectorProof());
        if (!(at(expr, 2) instanceof String whole)) throw new RuntimeFault("Missing local vector read case binder");
        if (!(at(expr, 4) instanceof Map<?, ?> metadata)) throw new RuntimeFault("Missing local vector read metadata");
        if (!(metadata.get("binder") instanceof Map<?, ?> binder)) throw new RuntimeFault("Missing local vector read binder metadata");
        requireProof(whole.equals(binder.get("id")) && Boolean.FALSE.equals(binder.get("lifted"))
            && Boolean.FALSE.equals(binder.get("coercion")) && !binder.containsKey("joinValueArity"), "whole-tuple binder identity/levity");
        readResult(binder.get("rep"), true, operation.getVectorProof());
        if (!(at(expr, 3) instanceof List<?> alternatives)) throw new RuntimeFault("Missing local vector read alternatives");
        requireProof(alternatives.size() == 1, "requires one tuple alternative");
        if (!(alternatives.getFirst() instanceof List<?> alternative)) throw new RuntimeFault("Invalid local vector read alternative");
        var constructor = at(alternative, 1) instanceof String id ? constructors.get(id) : null;
        requireProof("data".equals(at(alternative, 0)) && constructor != null
            && "unboxed-tuple".equals(constructor.get("kind")) && exactInteger(constructor.get("arity"), 2),
            "requires a registered tuple2 constructor");
        if (!(at(alternative, 2) instanceof List<?> ids)) throw new RuntimeFault("Missing local vector read pattern ids");
        requireProof(ids.size() == 2 && ids.get(0) instanceof String && ids.get(1) instanceof String
            && !ids.get(0).equals(ids.get(1)) && !ids.contains(whole), "pattern binder identities");
        var records = at(alternative, 4) instanceof Map<?, ?> m && m.get("binders") instanceof List<?> list ? list : null;
        requireProof(records != null && records.size() == 2, "missing ordered pattern metadata");
        for (int i = 0; i < 2; i++) {
            var expected = i == 0 ? stateProof : operation.getVectorProof();
            if (!(records.get(i) instanceof Map<?, ?> record)) throw new RuntimeFault("Invalid local vector read pattern metadata");
            var rep = record.get("rep") instanceof Map<?, ?> r ? r : null;
            requireProof(ids.get(i).equals(record.get("id")) && Boolean.FALSE.equals(record.get("lifted"))
                && Boolean.FALSE.equals(record.get("coercion")) && !record.containsKey("joinValueArity")
                && (!expected.isVector() || rep != null && vectorAnnotation(rep.get("vector"), expected))
                && exact(expected, CoreRepresentations.INSTANCE.parse(record.get("rep")))
                && rep != null && Boolean.TRUE.equals(rep.get("evaluated")), "pattern binder representation");
        }
        if (!(at(alternative, 3) instanceof List<?> body)) throw new RuntimeFault("Missing local vector read continuation");
        requireProof(!body.isEmpty(), "empty continuation");
        requireProof(!termUses(body, whole), "whole tuple binder escapes");
        return new VectorReadCase(operation, arguments, (String) ids.get(0), (String) ids.get(1), (List<Object>) body);
    }
}
