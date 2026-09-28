// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.CoreGmpForeign.GMP_ARRAY_REP;

/** Descriptor controls only; genuine imported declarations have a separate oracle. */
class CoreGmpForeignTest {
    @Test void loadTimeUnliftedObjectPredicateRetainsExactNullSentinelRule() {
        for (var kind : CoreKind.values()) for (boolean evaluated : new boolean[]{false, true})
            for (var primitive : Arrays.asList(null, "BoxedRep (Just Lifted)", GMP_ARRAY_REP, "IntRep")) {
                var reps = primitive == null ? List.<String>of() : List.of(primitive);
                var proof = new CoreRepresentation(kind, evaluated, true, reps, null, null, null, null, null);
                assertEquals(kind == CoreKind.OBJECT && evaluated && GMP_ARRAY_REP.equals(primitive), proof.isEvaluatedUnliftedObject());
                assertFalse(new CoreRepresentation(kind, false, true, reps, null, null, null, null, null).isEvaluatedUnliftedObject());
            }
    }
    private Map<String, Object> scalar(String primitive, boolean evaluated) {
        String kind = primitive == null ? "void" : primitive.equals(GMP_ARRAY_REP) ? "object" : primitive.equals("DoubleRep") ? "double" : "long";
        return Map.of("kind", kind, "primReps", primitive == null ? List.of() : List.of(primitive), "evaluated", evaluated);
    }
    private Map<String, Object> result(GmpForeignOp operation, boolean evaluated) {
        var fields = new ArrayList<Map<String, Object>>(); fields.add(scalar(null, true));
        if (operation.getResult() != null) fields.add(scalar(operation.getResult(), true));
        return Map.of("kind", "unknown", "primReps", operation.getResult() == null ? List.of() : List.of(operation.getResult()),
            "evaluated", evaluated, "aggregate", "unboxed-tuple", "components", fields);
    }
    private Map<String, Object> descriptor(GmpForeignOp operation) {
        return Map.of("schema", 1L, "target", Map.of("kind", "static", "symbol", operation.getSymbol(), "unit", "ghc-internal", "isFunction", true),
            "convention", "ccall", "safety", "unsafe", "arity", (long) operation.getArguments().size(), "suppliedArity", (long) operation.getArguments().size(),
            "argumentReps", operation.getArguments().stream().map(rep -> scalar(rep, false)).toList(), "resultRep", result(operation, false));
    }
    private Map<String, Object> changed(Map<?, ?> original, String key, Object value) {
        var changed = new LinkedHashMap<String, Object>(); original.forEach((k, v) -> changed.put((String) k, v));
        changed.put(key, value); return changed;
    }
    @Test void allTwentyFourExactContractsIncludeOriginalStateAndLogicalTuple() {
        assertEquals(24, GmpForeignOp.values().length);
        for (var operation : GmpForeignOp.values()) {
            var proof = result(operation, true);
            assertEquals(operation, CoreGmpForeign.validate(Map.of("rep", proof, "foreignCall", descriptor(operation)),
                operation.getArguments().stream().map(rep -> scalar(rep, true)).toList(),
                operation.getArguments().stream().map(rep -> false).toList(), proof));
            assertNull(operation.getArguments().getLast());
            assertTrue(operation.getObjectIndices().size() <= 4); assertTrue(operation.getLongIndices().size() <= 3);
        }
        assertNull(CoreGmpForeign.validate(Map.of(), List.of(), List.of(), null));
    }
    private void reject(Map<String, Object> call, List<?> args, List<?> flags, Object result) {
        assertThrows(RuntimeFault.class, () -> CoreGmpForeign.validate(Map.of("rep", result, "foreignCall", call), args, flags, result));
    }
    @Test void malformedDescriptorsAndOccurrenceProofsCannotChangeTheAbi() {
        for (var operation : GmpForeignOp.values()) {
            var good = descriptor(operation);
            var arguments = operation.getArguments().stream().map(rep -> scalar(rep, true)).toList();
            var flags = operation.getArguments().stream().map(rep -> false).toList();
            var output = result(operation, true);
            for (var change : new Object[][]{{"schema",1.0},{"convention","capi"},{"safety","safe"},
                {"arity",operation.getArguments().size()-1},{"suppliedArity",1.0},
                {"argumentReps",operation.getArguments().stream().map(rep -> scalar(rep,true)).toList()},
                {"resultRep",result(operation,true)}})
                reject(changed(good, (String) change[0], change[1]), arguments, flags, output);
            var target = (Map<?, ?>) good.get("target");
            for (var change : new Object[][]{{"unit","base"},{"kind","dynamic"},{"isFunction",false}})
                reject(changed(good, "target", changed(target, (String) change[0], change[1])), arguments, flags, output);
            reject(changed(good, "extra", true), arguments, flags, output);
            reject(changed(good, "resultRep", scalar(operation.getResult(), false)), arguments, flags, output);
            for (int index = 0; index < operation.getArguments().size(); index++) {
                var args = new ArrayList<>(arguments); args.set(index, scalar("AddrRep", true)); reject(good, args, flags, output);
                var cbv = new ArrayList<>(flags); cbv.set(index, true); reject(good, arguments, cbv, output);
            }
            reject(good, arguments, flags, changed(output, "components", List.of()));
            reject(good, arguments, flags, changed(output, "evaluated", "true"));
        }
    }
    @Test void headsAndStoredOperandsCannotForgeKnownRepresentations() {
        List<Object> head = List.of("var", "genuine-fcall-unique", Map.of("rep", Map.of("kind", "closure",
            "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true)));
        CoreGmpForeign.validateHead(head, false);
        assertThrows(RuntimeFault.class, () -> CoreGmpForeign.validateHead(head, true));
        assertThrows(RuntimeFault.class, () -> CoreGmpForeign.validateHead(List.of("var", ""), false));
        for (var operation : GmpForeignOp.values()) for (int i = 0; i < operation.getArguments().size(); i++) {
            int index = i;
            var primitive = operation.getArguments().get(index);
            var kind = primitive == null ? CoreKind.VOID : primitive.equals(GMP_ARRAY_REP) ? CoreKind.OBJECT : CoreKind.LONG;
            var proof = new CoreRepresentation(kind, true, true, primitive == null ? List.of() : List.of(primitive), null, null, null, null, null);
            CoreGmpForeign.validateOperand(operation, index, proof, proof);
            var incorrect = new CoreRepresentation(CoreKind.ADDRESS, true, true, List.of("AddrRep"), null, null, null, null, null);
            assertThrows(RuntimeFault.class, () -> CoreGmpForeign.validateOperand(operation, index, incorrect, proof));
            assertThrows(RuntimeFault.class, () -> CoreGmpForeign.validateOperand(operation, index, proof, incorrect));
        }
    }
}
