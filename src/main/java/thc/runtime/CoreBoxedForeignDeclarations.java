// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Nominal stock declarations for operations on THC-owned boxed identities.
 * Validation retains an archive obligation; it grants no native ABI. */
public final class CoreBoxedForeignDeclarations {
    private final List<?> calls;
    private CoreBoxedForeignDeclarations(List<?> calls) { this.calls = (List<?>) thc.Json.immutable(calls); }

    private static boolean owned(Map<?,?> emitted) {
        return "ghc-internal".equals(emitted.get("unit")) && (ThreadIdForeignOp.named(emitted.get("symbol")) != null ||
            "rts_setMainThread".equals(emitted.get("symbol")) || "reportStackOverflow".equals(emitted.get("symbol")));
    }
    /** An in-memory token is made only from the unchanged stock proof and call inventory.
     * A serialized map cannot stand in for this admission. */
    public static CoreBoxedForeignDeclarations read(Map<?,?> module, boolean completeBindings) {
        if (!(module.get("staticForeignImports") instanceof Map<?,?> proof) ||
            !(proof.get("imports") instanceof List<?> imports)) return null;
        var declarations = new ArrayList<Map<?,?>>();
        for (Object item : imports) if (item instanceof Map<?,?> declaration &&
            declaration.get("emitted") instanceof Map<?,?> emitted && owned(emitted)) declarations.add(declaration);
        if (declarations.isEmpty()) return null;
        check("9.14.1".equals(module.get("ghc")), "GHC version");
        // The same generic provenance validator is used without an archive wrapper.
        // This temporary obligation is never published or converted to a native capability.
        var retained = new LinkedHashMap<Object,Object>(module);
        if (!retained.containsKey("packageNativeArchive")) {
            var excluded = new ArrayList<Object>();
            for (Object item : imports) {
                var emitted = (Map<?,?>) ((Map<?,?>) item).get("emitted");
                boolean boxed = ((List<?>) emitted.get("arguments")).stream().anyMatch(CoreBoxedForeignDeclarations::boxed) ||
                    ((List<?>) emitted.get("result")).stream().anyMatch(CoreBoxedForeignDeclarations::boxed);
                if (boxed || "prim".equals(emitted.get("convention")) || "interruptible".equals(emitted.get("safety"))) excluded.add(emitted);
            }
            var archive = new LinkedHashMap<String,Object>();
            archive.put("schema", 1L); archive.put("profile", "thc-package-native-archive-v1"); archive.put("execution", "not-linked");
            archive.put("unit", module.get("unit")); archive.put("module", module.get("module")); archive.put("unsupportedImports", excluded);
            archive.put("unclassifiedReason", null); archive.put("unresolvedSymbols", List.of()); archive.put("artifact", null);
            retained.put("packageNativeArchive", archive);
        }
        thc.PackageNativeArchives.read(retained, completeBindings);
        var admitted = new ArrayList<Object>();
        for (Object item : (List<?>) proof.get("expectedCalls")) {
            if (!(item instanceof Map<?,?> call) || !(call.get("target") instanceof Map<?,?> target)) continue;
            for (var declaration : declarations) {
                var emitted = (Map<?,?>) declaration.get("emitted");
                if (!Objects.equals(target.get("unit"), emitted.get("unit")) || !Objects.equals(target.get("symbol"), emitted.get("symbol"))) continue;
                check(Objects.equals(call.get("convention"), emitted.get("convention")) &&
                    Objects.equals(call.get("safety"), emitted.get("safety")) &&
                    carriers((List<?>) call.get("argumentReps")).equals(emitted.get("arguments")) &&
                    carriers((List<?>) ((Map<?,?>) call.get("resultRep")).get("components")).equals(emitted.get("result")),
                    "call inventory differs from nominal declaration");
                admitted.add(call);
            }
        }
        return new CoreBoxedForeignDeclarations(admitted);
    }
    private static boolean boxed(Object value) { return "BoxedRep (Just Unlifted)".equals(value) || "BoxedRep (Just Lifted)".equals(value); }
    private static List<Object> carriers(List<?> representations) {
        var carriers = new ArrayList<Object>();
        for (Object item : representations) {
            var reps = (List<?>) ((Map<?,?>) item).get("primReps");
            check(reps.size() <= 1, "scalar boxed declaration carrier"); carriers.add(reps.isEmpty() ? "void" : reps.getFirst());
        }
        return carriers;
    }
    public static List<CoreBoxedForeignDeclarations> admissions(Map<?,?> module) {
        if (module.get("boxedForeignDeclarations") instanceof List<?> tokens) {
            var result = new ArrayList<CoreBoxedForeignDeclarations>();
            for (Object token : tokens) {
                check(token instanceof CoreBoxedForeignDeclarations, "unforgeable admission token");
                result.add((CoreBoxedForeignDeclarations) token);
            }
            return List.copyOf(result);
        }
        var admission = read(module, true); return admission == null ? List.of() : List.of(admission);
    }
    public static void requireCall(List<CoreBoxedForeignDeclarations> admissions, Object metadata) {
        var call = ((Map<?,?>) metadata).get("foreignCall");
        for (var admission : admissions) if (admission.calls.contains(call)) return;
        throw RuntimeServiceStatus.fault("Boxed RTS call lacks its exact nominal stock-import admission");
    }
    private static Map<String,Object> named(String module, String name, String namespace, Object... arguments) {
        return Map.of("kind", "tycon", "name", Map.of("unit", "ghc-internal", "module", module,
            "occurrence", name, "namespace", namespace), "arguments", List.of(arguments));
    }
    private static Object type(String module, String name, Object... arguments) { return named(module, name, "type", arguments); }
    private static Object arrow(Object argument, Object result) {
        return Map.of("kind", "function", "multiplicity", named("GHC.Internal.Types", "Many", "data"),
            "argument", argument, "result", result);
    }
    private static void check(boolean value, String detail) {
        if (!value) throw new IllegalArgumentException("Invalid boxed runtime declaration: " + detail);
    }
    public static void validate(Map<?,?> declaration) {
        if (!(declaration.get("emitted") instanceof Map<?,?> emitted) || !"ghc-internal".equals(emitted.get("unit"))) return;
        var operation = ThreadIdForeignOp.named(emitted.get("symbol"));
        boolean main = "rts_setMainThread".equals(emitted.get("symbol"));
        boolean stack = "reportStackOverflow".equals(emitted.get("symbol"));
        if (operation == null && !main && !stack) return;
        check(Objects.equals(declaration.get("symbol"), emitted.get("symbol")) &&
            "ghc-internal".equals(declaration.get("unit")) && declaration.get("header") == null &&
            Boolean.TRUE.equals(declaration.get("isFunction")) && "ccall".equals(declaration.get("convention")) &&
            "ccall".equals(emitted.get("convention")) && "unsafe".equals(declaration.get("safety")) &&
            "unsafe".equals(emitted.get("safety")) && "representational".equals(declaration.get("normalizationRole")),
            "original static target/normalization identity");
        Object thread = type("GHC.Internal.Prim", "ThreadId#");
        Object declared, normalized;
        int count;
        List<String> results;
        if (operation != null) {
            String cType = switch (operation) { case ID -> "CULLong"; case EQUAL -> "CBool"; case ORDER -> "CInt"; };
            declared = type("GHC.Internal.Foreign.C.Types", cType);
            normalized = switch (operation) {
                case ID -> type("GHC.Internal.Word", "Word64");
                case EQUAL -> type("GHC.Internal.Word", "Word8");
                case ORDER -> type("GHC.Internal.Int", "Int32");
            };
            count = operation.getArguments(); results = List.of("void", operation.getResult());
            for (int i = 0; i < count; i++) { declared = arrow(thread, declared); normalized = arrow(thread, normalized); }
        } else {
            Object argument = main ? type("GHC.Internal.Prim", "Weak#", named("GHC.Internal.Types", "Lifted", "data"),
                type("GHC.Internal.Conc.Sync", "ThreadId")) : thread;
            declared = normalized = arrow(argument, type("GHC.Internal.Types", "IO", type("GHC.Internal.Tuple", "Unit")));
            count = 1; results = List.of("void");
        }
        check(declared.equals(declaration.get("declaredType")) && normalized.equals(declaration.get("normalizedType")),
            "exact declared and normalized nominal types");
        var arguments = new ArrayList<String>();
        for (int i = 0; i < count; i++) arguments.add("BoxedRep (Just Unlifted)");
        arguments.add("void");
        check(arguments.equals(emitted.get("arguments")) && results.equals(emitted.get("result")), "exact levity and emitted carriers");
    }
}
