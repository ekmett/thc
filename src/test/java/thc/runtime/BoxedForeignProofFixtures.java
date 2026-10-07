// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import thc.CoreCallInventory;

/** Explicit synthetic admission controls, not a substitute for stock producer fixtures. */
final class BoxedForeignProofFixtures {
    private BoxedForeignProofFixtures() {}
    private static Map<String,Object> named(String module, String occurrence, String namespace, Object... arguments) {
        return Map.of("kind", "tycon", "name", Map.of("unit", "ghc-internal", "module", module,
            "occurrence", occurrence, "namespace", namespace), "arguments", List.of(arguments));
    }
    private static Object type(String module, String occurrence, Object... arguments) { return named(module, occurrence, "type", arguments); }
    private static Object arrow(Object argument, Object result) {
        return Map.of("kind", "function", "multiplicity", named("GHC.Internal.Types", "Many", "data"), "argument", argument, "result", result);
    }
    static Map<String,Object> withProof(Map<String,Object> raw) {
        var module = new LinkedHashMap<>(raw); var calls = CoreCallInventory.mapCalls(raw.get("bindings"));
        var declarations = new ArrayList<Object>();
        for (var call : calls) {
            var target = (Map<?,?>) call.get("target"); var symbol = (String) target.get("symbol");
            var op = ThreadIdForeignOp.named(symbol);
            if (op == null && !symbol.equals("rts_setMainThread") && !symbol.equals("reportStackOverflow")) continue;
            Object declared, normalized, argument = type("GHC.Internal.Prim", "ThreadId#");
            int count = op == null ? 1 : op.getArguments();
            if (op != null) {
                declared = type("GHC.Internal.Foreign.C.Types", switch (op) { case ID -> "CULLong"; case EQUAL -> "CBool"; case ORDER -> "CInt"; });
                normalized = type(op == ThreadIdForeignOp.ORDER ? "GHC.Internal.Int" : "GHC.Internal.Word",
                    switch (op) { case ID -> "Word64"; case EQUAL -> "Word8"; case ORDER -> "Int32"; });
            } else {
                declared = normalized = type("GHC.Internal.Types", "IO", type("GHC.Internal.Tuple", "Unit"));
                if (symbol.equals("rts_setMainThread")) argument = type("GHC.Internal.Prim", "Weak#",
                    named("GHC.Internal.Types", "Lifted", "data"), type("GHC.Internal.Conc.Sync", "ThreadId"));
            }
            for (int i = 0; i < count; i++) { declared = arrow(argument, declared); normalized = arrow(argument, normalized); }
            var arguments = new ArrayList<String>(); for (int i = 0; i < count; i++) arguments.add("BoxedRep (Just Unlifted)"); arguments.add("void");
            var emitted = Map.of("symbol", symbol, "unit", "ghc-internal", "convention", "ccall", "safety", "unsafe",
                "arguments", arguments, "result", op == null ? List.of("void") : List.of("void", op.getResult()));
            var entry = new LinkedHashMap<String,Object>();
            entry.put("binder", Map.of("unit", "ghc-internal", "module", "SyntheticBoxedProof", "occurrence", symbol, "namespace", "value"));
            entry.put("header", null); entry.put("symbol", symbol); entry.put("unit", "ghc-internal"); entry.put("isFunction", true);
            entry.put("convention", "ccall"); entry.put("safety", "unsafe"); entry.put("declaredType", declared); entry.put("normalizedType", normalized);
            entry.put("normalizationRole", "representational"); entry.put("emitted", emitted); declarations.add(entry);
        }
        if (declarations.isEmpty()) return raw;
        var product = new LinkedHashMap<String,Object>(); product.put("schema", 1L); product.put("execution", "not-linked"); product.put("stubs", null); product.put("files", List.of());
        var proof = new LinkedHashMap<String,Object>();
        proof.put("schema", 1L); proof.put("scope", "retained-static-import-products"); proof.put("execution", "not-linked");
        proof.put("profile", "ghc-9.14.1-thc-only-static-c-imports-v1"); proof.put("unit", "ghc-internal"); proof.put("module", "SyntheticBoxedProof");
        proof.put("status", "verified"); proof.put("wordBits", 64L); proof.put("expectedForeign", product); proof.put("imports", declarations); proof.put("expectedCalls", calls);
        module.put("schema", 1L); module.put("ghc", "9.14.1"); module.put("unit", "ghc-internal"); module.put("module", "SyntheticBoxedProof"); module.put("staticForeignImports", proof);
        return module;
    }
}
