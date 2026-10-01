// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.interop.InteropLibrary;
import java.util.*;
import org.graalvm.polyglot.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreBackendTestSupport.*;

/** Public transport keeps nominal raw references distinct from guest storage. */
class HostInteropAbiTest {
    private static final Map<String, Object> RAW = map("kind", "object", "primReps", list("BoxedRep (Just Unlifted)"), "evaluated", true);
    private static final Map<String, Object> CLOSURE = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private static Map<String, Object> type(Object carrier) { return map("rep", RAW, "carriers", list(carrier)); }
    private static Map<String, Object> module(Object carrier) {
        return module(carrier, RAW);
    }
    private static Map<String, Object> module(Object carrier, Map<String, Object> proof) {
        var parameter = map("id", "x", "name", "x", "rep", proof, "lifted", proof.get("primReps").equals(list("BoxedRep (Just Lifted)")));
        var body = list("lam", list(parameter), list("var", "x", map("rep", proof)), map("rep", CLOSURE, "resultRep", proof));
        var binding = map("id", "host:Raw.identity", "arity", 1, "lifted", true, "rep", CLOSURE, "expr", body);
        if (carrier != null) binding.put("hostSignature", map("inputs", list(type(carrier)), "result", type(carrier)));
        return map("schema", 1, "ghc", "9.14.1", "unit", "host", "module", "Raw", "bindings", list(binding), "constructors", list());
    }

    @Test void declarationCannotChangeWorkerRepresentationOrInventCarrierLeaves() {
        for (Object invalid : list(null, map("inputs", list(), "result", type("object")),
                map("inputs", list(type("bogus")), "result", type("object")),
                map("inputs", list(map("rep", RAW, "carriers", list())), "result", type("object")),
                map("inputs", list(map("rep", with(RAW, "primReps", list("BoxedRep (Just Lifted)")), "carriers", list("object"))), "result", type("object")))) {
            var module = module("object"); var binding = objects(module.get("bindings")).getFirst();
            binding.put("hostSignature", invalid);
            assertThrows(RuntimeFault.class, () -> CoreHostSignature.select(binding, objects(module.get("bindings"))));
        }
        var old = module(null); var binding = objects(old.get("bindings")).getFirst();
        assertNull(CoreHostSignature.select(binding, objects(old.get("bindings"))).inputs().getFirst().getHostCarrier());
    }
}
