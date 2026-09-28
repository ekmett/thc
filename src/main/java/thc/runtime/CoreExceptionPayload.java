// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;
import java.util.Map;
import java.util.Set;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Exact producer type provenance, never inferred from runtime values. Legacy
 * captures without this marker keep primitive exception payloads opaque/lazy. */
public final class CoreExceptionPayload {
    private CoreExceptionPayload() {}
    public static final String TYPE = "ghc-internal:GHC.Internal.Exception.Type.SomeException";

    public static boolean validate(List<?> expression) {
        var metadata = CoreRepresentations.metadata(expression);
        var proof = metadata == null ? null : metadata.get("exceptionPayload");
        if (proof == null) return false;
        if (!(proof instanceof Map<?, ?> record)) throw fault("Invalid exception payload provenance");
        var head = expression.size() > 1 && expression.get(1) instanceof List<?> list ? list : null;
        if (expression.isEmpty() || !"app".equals(expression.get(0)) || head == null || head.isEmpty() ||
            !"prim".equals(head.get(0)) || head.size() <= 1 ||
            !("raise#".equals(head.get(1)) || "raiseIO#".equals(head.get(1))) ||
            !record.keySet().equals(Set.of("schema", "type")) ||
            !(Integer.valueOf(1).equals(record.get("schema")) || Long.valueOf(1).equals(record.get("schema"))) ||
            !TYPE.equals(record.get("type"))) throw fault("Invalid SomeException raise provenance");
        return true;
    }
}
