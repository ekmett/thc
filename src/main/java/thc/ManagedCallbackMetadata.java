// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import thc.runtime.CoreRepresentations;

/** Decode the actual GHC wrapper declaration and its emitted createAdjustor helper. */
public final class ManagedCallbackMetadata {
    private ManagedCallbackMetadata() {}
    private static void require(boolean condition, String detail) {
        if (!condition) throw new IllegalArgumentException("Invalid native wrapper declaration: " + detail);
    }
    @SuppressWarnings("unchecked")
    public static List<ManagedCallbackSignature> read(Map<?, ?> module, Map<?, ?> proof) {
        boolean mixed = PackageFinalizers.version(proof.get("schema"), 4);
        if (!mixed && !PackageFinalizers.version(proof.get("schema"), 3)) return List.of();
        require("verified".equals(proof.get("status")) && Objects.equals(module.get("unit"), proof.get("unit")) &&
            Objects.equals(module.get("module"), proof.get("module")) && PackageFinalizers.version(proof.get("wordBits"), 64), "owner/status/word width");
        require(proof.get("wrappers") instanceof List<?> items && (mixed || !items.isEmpty()), "missing wrappers");
        var result = new ArrayList<ManagedCallbackSignature>();
        var names = new HashSet<Object>();
        var binders = new HashSet<Object>();
        for (var raw : (List<?>) proof.get("wrappers")) {
            require(raw instanceof Map<?, ?>, "wrapper record");
            var item = (Map<?, ?>) raw;
            require(item.keySet().equals(Set.of("binder", "helper", "convention", "declaredType", "normalizedType", "normalizationRole",
                "arguments", "result", "effect", "typeString")), "wrapper fields");
            var binder = PackageScalarLinks.archiveIdentity(item.get("binder"));
            require(Objects.equals(module.get("unit"), binder.get("unit")) && Objects.equals(module.get("module"), binder.get("module")) &&
                "value".equals(binder.get("namespace")) && binders.add(binder), "wrapper binder");
            require(item.get("helper") instanceof String helper && helper.matches("[A-Za-z_][A-Za-z0-9_]*") && names.add(helper) &&
                "ccall".equals(item.get("convention")) && "representational".equals(item.get("normalizationRole")) &&
                item.get("typeString") instanceof String encoding && !encoding.isEmpty() && encoding.indexOf(0) < 0, "helper/convention/encoding");
            PackageScalarLinks.archiveType(item.get("declaredType")); PackageScalarLinks.archiveType(item.get("normalizedType"));
            var normalized = (Map<String, Object>) item.get("normalizedType");
            require("function".equals(normalized.get("kind")), "wrapper function");
            var callback = (Map<String, Object>) normalized.get("argument");
            var wrappedIo = named(normalized.get("result"), "GHC.Internal.Types", "IO");
            var funPtr = named(wrappedIo.getFirst(), "GHC.Internal.Ptr", "FunPtr");
            require(funPtr.getFirst().equals(callback), "wrapper result callback type");
            var arguments = new ArrayList<Map<String, Object>>();
            var remaining = callback;
            while ("function".equals(remaining.get("kind"))) {
                arguments.add((Map<String, Object>) remaining.get("argument"));
                remaining = (Map<String, Object>) remaining.get("result");
            }
            boolean io = "io".equals(item.get("effect"));
            require(io || "pure".equals(item.get("effect")), "callback effect");
            if (io) remaining = (Map<String, Object>) named(remaining, "GHC.Internal.Types", "IO").getFirst();
            require(arguments.equals(item.get("arguments")) && remaining.equals(item.get("result")), "callback ABI projections");
            var voidRep = Map.of("kind", "void", "primReps", List.of(), "evaluated", true);
            var boxedRep = Map.of("kind", "object", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", false);
            var ioResult = io ? CoreRepresentations.parse(Map.of("kind", "unknown", "primReps", List.of("BoxedRep (Just Lifted)"),
                "evaluated", true, "aggregate", "unboxed-tuple", "components", List.of(voidRep, boxedRep))) : null;
            String unit = (String) module.get("unit"), name = (String) module.get("module");
            result.add(new ManagedCallbackSignature(new ManagedExportSignature(unit, name, (String) item.get("helper"),
                unit + ":" + name + "." + binder.get("occurrence"), List.copyOf(arguments), remaining, io, 64, ioResult), (String) item.get("typeString")));
        }
        return List.copyOf(result);
    }
    private static List<?> named(Object raw, String module, String occurrence) {
        require(raw instanceof Map<?, ?>, "nominal type");
        var type = (Map<?, ?>) raw;
        require("tycon".equals(type.get("kind")) && Map.of("unit", "ghc-internal", "module", module, "occurrence", occurrence,
            "namespace", "type").equals(type.get("name")) && type.get("arguments") instanceof List<?> args && args.size() == 1, "nominal " + occurrence);
        return (List<?>) type.get("arguments");
    }
}
