// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import thc.Json;

/** Allocate through the guest, then retain its opaque context-owned public handle. */
final class HostByteArrayTestValues {
    private HostByteArrayTestValues() {}
    static Value allocate(Context context, String backend) {
        var integer = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
        var state = Map.of("kind", "void", "primReps", List.of(), "evaluated", true);
        var bytes = Map.of("kind", "object", "primReps", List.of("BoxedRep (Just Unlifted)"), "evaluated", true);
        var closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        var result = Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "components", List.of(state, bytes),
            "primReps", List.of("BoxedRep (Just Unlifted)"), "evaluated", true);
        var call = List.of("app", List.of("prim", "newByteArray#"),
            List.of(List.of("lit", "int", "64", Map.of("rep", integer)), List.of("var", "s", Map.of("rep", state))),
            List.of(false, false), true, true, Map.of("rep", result));
        var lambda = List.of("lam", List.of(Map.of("id", "s", "name", "s", "rep", state, "lifted", false)),
            call, Map.of("rep", closure, "resultRep", result));
        var binding = Map.of("id", "host-array:HostArray.allocate", "name", "allocate", "arity", 1,
            "lifted", true, "rep", closure, "expr", lambda);
        var module = Map.of("schema", 1, "ghc", "9.14.1", "unit", "host-array", "module", "HostArray",
            "bindings", List.of(binding), "constructors", List.of());
        return context.eval("thc", Json.stringify(Map.of("backend", backend, "entry", "allocate",
            "modules", List.of(module)))).execute((Object) null).getArrayElement(1);
    }
}
