// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Typed stock CLabel evidence is independent of the ordinary foreign-call inventory. */
public final class PackageFinalizers {
    private PackageFinalizers() {}

    private static void require(boolean valid, String detail) {
        if (!valid) throw new IllegalArgumentException("Invalid package finalizer: " + detail);
    }

    private static Map<?, ?> record(Object value, String fields) {
        require(value instanceof Map<?, ?>, "record");
        Map<?, ?> result = (Map<?, ?>) value;
        require(result.keySet().equals(Set.of(fields.split(" "))), "record fields");
        return result;
    }

    private static String text(Object value) {
        require(value instanceof String && !((String) value).isEmpty() && ((String) value).indexOf(0) < 0, "text");
        return (String) value;
    }

    public static boolean version(Object value, int expected) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() == expected;
    }

    public static boolean hasDeclarations(Map<?, ?> module) {
        if (module.containsKey("packageNativeLink")) return true;
        if (!(module.get("staticForeignImports") instanceof Map<?, ?> proof)) return false;
        return proof.get("imports") instanceof List<?> calls && !calls.isEmpty() ||
            proof.get("addresses") instanceof List<?> addresses && !addresses.isEmpty();
    }

    private static List<?> named(Object value, String module, String occurrence, int arity) {
        if (!(value instanceof Map<?, ?> type) || !"tycon".equals(type.get("kind")) ||
                !Map.of("unit", "ghc-internal", "module", module, "occurrence", occurrence,
                    "namespace", "type").equals(type.get("name")) ||
                !(type.get("arguments") instanceof List<?> arguments) || arguments.size() != arity) return null;
        return arguments;
    }

    /** Recheck normalized nominal types independently of the producer's ABI tag. */
    private static boolean finalizerType(Object value) {
        while (value instanceof Map<?, ?> type && "forall".equals(type.get("kind"))) value = type.get("body");
        List<?> arguments = named(value, "GHC.Internal.Ptr", "FunPtr", 1);
        if (arguments == null || !(arguments.getFirst() instanceof Map<?, ?> function) ||
                !"function".equals(function.get("kind")) ||
                named(function.get("argument"), "GHC.Internal.Ptr", "Ptr", 1) == null) return false;
        List<?> result = named(function.get("result"), "GHC.Internal.Types", "IO", 1);
        return result != null && named(result.getFirst(), "GHC.Internal.Tuple", "Unit", 0) != null;
    }

    public static Set<String> declarations(Map<?, ?> module) {
        if (!(module.get("staticForeignImports") instanceof Map<?, ?> proof)) return Set.of();
        return declarations(module, proof);
    }

    static Set<String> declarations(Map<?, ?> module, Map<?, ?> proof) {
        boolean wrappers = version(proof.get("schema"), 3);
        if (!version(proof.get("schema"), 2) && !wrappers) return Set.of();
        require("verified".equals(proof.get("status")), "verified address inventory");
        require(proof.get("addresses") instanceof List<?>, "address inventory");
        List<?> addresses = (List<?>) proof.get("addresses");
        require(wrappers || !addresses.isEmpty(), "empty address inventory");
        Set<Object> binders = new HashSet<>();
        Set<String> eligible = new LinkedHashSet<>();
        for (Object raw : addresses) {
            Map<?, ?> entry = record(raw, "binder header symbol isFunction convention declaredType normalizedType normalizationRole callback");
            Map<?, ?> binder = PackageScalarLinks.archiveIdentity(entry.get("binder"));
            require(module.get("unit").equals(binder.get("unit")) && module.get("module").equals(binder.get("module")) &&
                "value".equals(binder.get("namespace")) && binders.add(binder), "address owner/identity");
            String symbol = text(entry.get("symbol"));
            require(symbol.matches("[A-Za-z_][A-Za-z0-9_]*"), "C symbol");
            Object header = entry.get("header");
            if (header != null) {
                String name = text(header);
                for (int i = 0; i < name.length(); i++)
                    require("\n\r\"\\".indexOf(name.charAt(i)) < 0, "header");
            }
            require(entry.get("isFunction") instanceof Boolean && Set.of("ccall", "capi").contains(entry.get("convention")) &&
                "representational".equals(entry.get("normalizationRole")), "address declaration");
            PackageScalarLinks.archiveType(entry.get("declaredType"));
            PackageScalarLinks.archiveType(entry.get("normalizedType"));
            if (entry.get("callback") != null) {
                Map<?, ?> callback = record(entry.get("callback"), "arguments result");
                require(Boolean.TRUE.equals(entry.get("isFunction")) && List.of("AddrRep").equals(callback.get("arguments")) &&
                    "void".equals(callback.get("result")) && finalizerType(entry.get("normalizedType")),
                    "one-pointer IO-unit callback differs from normalized type");
                eligible.add(symbol);
            }
        }
        return Set.copyOf(eligible);
    }

    /** These entry names belong to the component's already checked, namespaced ABI. */
    public static Set<String> entries(Map<?, ?> fields, List<PackageScalarSignature> abi) {
        if (!version(fields.get("schema"), 2)) return Set.of();
        require(fields.get("finalizers") instanceof List<?>, "finalizer entries");
        List<?> raw = (List<?>) fields.get("finalizers");
        Set<String> result = new LinkedHashSet<>();
        for (Object value : raw) {
            String entry = text(value);
            require(result.add(entry), "duplicate finalizer entry");
            PackageScalarSignature signature = null;
            for (PackageScalarSignature candidate : abi) if (entry.equals(candidate.getEntry())) {
                require(signature == null, "duplicate finalizer component ABI");
                signature = candidate;
            }
            require(signature != null, "finalizer absent from component ABI");
            require(List.of("AddrRep").equals(signature.getArguments()) && "void".equals(signature.getResult()) &&
                "ccall".equals(signature.getConvention()) && "unsafe".equals(signature.getSafety()), "finalizer ABI");
            require(!Set.of("free", "libdwPoolRelease", "backtraceFree").contains(signature.getSymbol()), "reserved runtime finalizer");
        }
        require(!result.isEmpty(), "empty finalizer entries");
        return Set.copyOf(result);
    }

    public static Set<String> proved(Map<?, ?> module, PackageScalarLink link) {
        Set<String> symbols = declarations(module);
        Set<String> result = new HashSet<>();
        for (PackageScalarSignature signature : link.getAbi())
            if (link.getFinalizers().contains(signature.getEntry()) && symbols.contains(signature.getSymbol()))
                result.add(signature.getEntry());
        return Set.copyOf(result);
    }
}
