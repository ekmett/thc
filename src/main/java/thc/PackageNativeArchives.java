// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;
import thc.runtime.CoreBoxedForeignDeclarations;
import thc.runtime.CorePrimForeignDeclarations;

public final class PackageNativeArchives {
    public static final PackageNativeArchives INSTANCE = new PackageNativeArchives();
    private PackageNativeArchives() {}
    private static final Set<String> SCALAR = Set.of("IntRep", "WordRep", "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep", "Int32Rep", "Word32Rep", "Int64Rep", "Word64Rep", "FloatRep", "DoubleRep", "AddrRep");
    private static void check(boolean value, String reason) { if (!value) throw new IllegalArgumentException("Invalid package native archive: " + reason); }
    private static boolean version(Object value, int n) { return Objects.equals(value, n) || Objects.equals(value, (long) n); }
    private static boolean in(Object value, String... choices) { return Arrays.asList(choices).contains(value); }
    private static Map<?,?> record(Object value, String fields) {
        check(value instanceof Map<?,?> map && map.keySet().equals(Set.of(fields.split(" "))), "record fields"); return (Map<?,?>) value;
    }
    private static String text(Object value) { check(value instanceof String s && !s.isEmpty() && s.indexOf(0) < 0, "text"); return (String) value; }
    private static List<?> list(Object value) { check(value instanceof List<?>, "list"); return (List<?>) value; }
    private static boolean scalar(Object value) { return value instanceof String s && SCALAR.contains(s); }
    private static boolean boxed(Object value) { return in(value, "BoxedRep (Just Unlifted)", "BoxedRep (Just Lifted)"); }
    static boolean importProfile(Object value) { return in(value, "ghc-9.14.1-thc-only-static-c-imports-v1", "ghc-9.14.1-thc-stock-static-foreign-imports-v2"); }
    private static boolean prim(Map<?,?> emitted) { return Objects.equals(emitted.get("convention"), "prim"); }
    private static boolean gcBoxed(Map<?,?> emitted) {
        return list(emitted.get("arguments")).stream().anyMatch(PackageNativeArchives::boxed) ||
            list(emitted.get("result")).stream().anyMatch(PackageNativeArchives::boxed);
    }
    private static Map<?,?> emitted(Object value, String unit) {
        var emitted = record(value, "symbol unit convention safety arguments result");
        check(text(emitted.get("symbol")).matches("[A-Za-z_][A-Za-z0-9_]*") && Objects.equals(emitted.get("unit"), unit) && in(emitted.get("convention"), "ccall", "capi", "prim") && in(emitted.get("safety"), "unsafe", "safe", "interruptible"), "emitted identity");
        var args = list(emitted.get("arguments")); var result = list(emitted.get("result"));
        if (prim(emitted)) {
            check(Objects.equals(emitted.get("safety"), "safe") && args.stream().allMatch(PackageNativeArchives::primCarrier) &&
                result.stream().allMatch(PackageNativeArchives::primCarrier), "prim emitted carriers");
            return emitted;
        }
        boolean valid = !args.isEmpty() && Objects.equals(args.getLast(), "void");
        if (valid) for (Object arg : args.subList(0, args.size() - 1)) if (!scalar(arg) && !boxed(arg) && !in(arg, "ByteArray#", "MutableByteArray#")) { valid = false; break; }
        check(valid && (result.equals(List.of("void")) || result.size() == 2 && Objects.equals(result.getFirst(), "void") && (scalar(result.get(1)) || boxed(result.get(1)))), "emitted carriers");
        return emitted;
    }
    private static boolean primCarrier(Object value) { return scalar(value) || boxed(value) || in(value, "void", "ByteArray#", "MutableByteArray#"); }
    private static List<?> cAbi(Map<?,?> emitted) {
        Object convention = emitted.get("convention"), safety = Objects.equals(emitted.get("safety"), "safe") ? "unsafe" : emitted.get("safety");
        var arguments = new ArrayList<Object>();
        for (Object rep : list(emitted.get("arguments"))) {
            if (in(rep, "ByteArray#", "MutableByteArray#")) arguments.add("AddrRep");
            else if (in(rep, "IntRep", "Int8Rep", "Int16Rep", "Int32Rep", "Int64Rep")) arguments.add("Word" + ((String) rep).substring(3));
            else arguments.add(rep);
        }
        return Arrays.asList(convention, safety, Collections.unmodifiableList(arguments), emitted.get("result"));
    }
    public static List<?> excluded(Map<?,?> module) {
        return module.get("packageNativeArchive") instanceof Map<?,?> archive && archive.get("unsupportedImports") instanceof List<?> imports ? imports : List.of();
    }
    public static PackageNativeArchive read(Map<?,?> module) { return read(module, true); }
    public static PackageNativeArchive read(Map<?,?> module, boolean completeBindings) {
        Object raw = module.get("packageNativeArchive");
        if (raw == null) {
            check(!(module.get("staticForeignImports") instanceof Map<?,?> proof &&
                Objects.equals(proof.get("profile"), "ghc-9.14.1-thc-stock-static-foreign-imports-v2") &&
                Objects.equals(proof.get("status"), "verified")), "missing prim archive obligation");
            return null;
        }
        boolean conflictField = raw instanceof Map<?,?> map && map.containsKey("conflictingImports");
        var archive = record(raw, "schema profile execution unit module unsupportedImports unclassifiedReason unresolvedSymbols artifact" + (conflictField ? " conflictingImports" : ""));
        String unit = text(module.get("unit"));
        check(version(archive.get("schema"), 1) && Objects.equals(archive.get("profile"), "thc-package-native-archive-v1") && Objects.equals(archive.get("execution"), "not-linked") && Objects.equals(archive.get("unit"), unit) && Objects.equals(archive.get("module"), module.get("module")), "profile/owner");
        check(!module.containsKey("foreignLink") && !module.containsKey("packageScalarLink"), "mixed foreign profiles");
        Object unknown = archive.get("unclassifiedReason"); check(unknown == null || Objects.equals(unknown, "non-static-c-import-declaration"), "unclassified reason");
        Object proof = module.get("staticForeignImports"); var imports = new ArrayList<Map<?,?>>();
        if (unknown != null) {
            var p = record(proof, "schema scope execution profile unit module status reason"); proofIdentity(p, module);
            check(Objects.equals(p.get("status"), "unclassified") && Objects.equals(p.get("reason"), unknown), "unclassified provenance");
        } else if (proof == null) check(!module.containsKey("foreign") && !module.containsKey("staticForeignImportStubs"), "missing import provenance");
        else {
            boolean mixed = proof instanceof Map<?,?> m && version(m.get("schema"), 4);
            boolean wrappers = mixed || proof instanceof Map<?,?> m && version(m.get("schema"), 3);
            boolean addressProof = wrappers || proof instanceof Map<?,?> m && version(m.get("schema"), 2);
            var p = record(proof, "schema scope execution profile unit module status wordBits expectedForeign imports expectedCalls" + (addressProof ? " addresses" : "") + (wrappers ? " wrappers" : "") + (mixed ? " importForeign" : "")); proofIdentity(p, module);
            PackageFinalizers.declarations(module);
            check(Objects.equals(p.get("status"), "verified") && version(p.get("wordBits"), 64), "verified import profile");
            var callbacks = ManagedCallbackMetadata.read(module, p);
            var product = ManagedImportAdmission.importProduct(module, p, completeBindings);
            check(version(product.get("schema"), 1) && Objects.equals(product.get("execution"), "not-linked") && Objects.equals(product.get("files"), List.of()), "foreign product");
            if (product.get("stubs") != null) {
                var stubs = record(product.get("stubs"), "header source initializers finalizers");
                check((Objects.equals(stubs.get("header"), "") || !callbacks.isEmpty() && stubs.get("header") instanceof String) && stubs.get("source") instanceof String && Objects.equals(stubs.get("initializers"), List.of()) && Objects.equals(stubs.get("finalizers"), List.of()), "foreign stub obligations");
                check(module.containsKey("foreign") || Objects.equals(stubs.get("source"), ""), "missing retained stubs");
            }
            if (module.containsKey("foreign")) check(Objects.equals(module.get("foreign"), p.get("expectedForeign")), "retained product differs");
            CoreCallInventory.check(p.get("expectedCalls"), CoreCallInventory.mapCalls(module.get("bindings")), completeBindings);
            var binders = new HashSet<Object>();
            for (Object item : list(p.get("imports"))) {
                var entry = record(item, "binder header symbol unit isFunction convention safety declaredType normalizedType normalizationRole emitted");
                var binder = PackageScalarLinks.archiveIdentity(entry.get("binder"));
                check(Objects.equals(binder.get("unit"), unit) && Objects.equals(binder.get("module"), module.get("module")) && Objects.equals(binder.get("namespace"), "value") && binders.add(binder), "import binder");
                text(entry.get("symbol"));
                Object rawHeader = entry.get("header");
                boolean validHeader = rawHeader == null || rawHeader instanceof String;
                if (validHeader && rawHeader != null) {
                    String header = text(rawHeader);
                    for (int i = 0; i < header.length(); i++) if ("\n\r\"\\".indexOf(header.charAt(i)) >= 0) { validHeader = false; break; }
                }
                check(validHeader, "import header");
                check((entry.get("unit") == null || Objects.equals(entry.get("unit"), unit)) && in(entry.get("convention"), "ccall", "capi", "prim") && (Objects.equals(entry.get("isFunction"), true) || Objects.equals(entry.get("convention"), "capi") && Objects.equals(entry.get("isFunction"), false)) && in(entry.get("safety"), "unsafe", "safe", "interruptible") && Objects.equals(entry.get("normalizationRole"), "representational"), "import metadata");
                PackageScalarLinks.archiveType(entry.get("declaredType")); PackageScalarLinks.archiveType(entry.get("normalizedType"));
                var emitted = emitted(entry.get("emitted"), unit);
                if (prim(emitted)) check(Objects.equals(p.get("profile"), "ghc-9.14.1-thc-stock-static-foreign-imports-v2") &&
                    entry.get("header") == null && Objects.equals(entry.get("unit"), unit) &&
                    Objects.equals(entry.get("symbol"), emitted.get("symbol")), "stock prim declaration identity");
                CoreBoxedForeignDeclarations.validate(entry);
                check(Objects.equals(emitted.get("convention"), entry.get("convention")) && Objects.equals(emitted.get("safety"), entry.get("safety")), "emitted declaration"); imports.add(emitted);
            }
            check(Objects.equals(p.get("profile"), "ghc-9.14.1-thc-stock-static-foreign-imports-v2") == imports.stream().anyMatch(PackageNativeArchives::prim), "stock prim profile inventory");
            if (imports.stream().anyMatch(PackageNativeArchives::prim)) CorePrimForeignDeclarations.validate(module, p);
        }
        if (module.containsKey("staticForeignImportStubs")) check(Objects.equals(module.get("staticForeignImportStubs"), proof), "retained stub provenance differs");
        var conflicts = new ArrayList<Map<?,?>>(); if (conflictField) for (Object value : list(archive.get("conflictingImports"))) {
            var witness = emitted(value, unit); check(!prim(witness) && !gcBoxed(witness), "non-native conflict witness"); conflicts.add(witness);
        }
        if (conflictField) check(!conflicts.isEmpty() && new ArrayList<>(new LinkedHashSet<>(conflicts)).equals(conflicts) && unknown == null, "conflicting import inventory");
        var conflictGroups = new LinkedHashMap<Object,List<Map<?,?>>>(); for (var entry : conflicts) conflictGroups.computeIfAbsent(entry.get("symbol"), ignored -> new ArrayList<>()).add(entry);
        for (var group : conflictGroups.entrySet()) {
            var variants = group.getValue();
            boolean validVariants = true;
            for (var entry : variants) if (!in(entry.get("safety"), "unsafe", "safe")) { validVariants = false; break; }
            if (validVariants) {
                var abis = new HashSet<List<?>>();
                for (var entry : variants) abis.add(cAbi(entry));
                validVariants = abis.size() > 1;
            }
            check(validVariants, "imports do not have conflicting C ABIs");
            var local = new ArrayList<Map<?,?>>();
            for (var entry : imports) if (Objects.equals(entry.get("symbol"), group.getKey()) && !prim(entry) && !Objects.equals(entry.get("safety"), "interruptible") && !gcBoxed(entry)) local.add(entry);
            check(!local.isEmpty() && variants.containsAll(local), "conflict witnesses differ from local imports");
        }
        var conflictSymbols = conflictGroups.keySet();
        var selectedImports = new ArrayList<Map<?,?>>();
        for (var entry : imports) if (prim(entry) || Objects.equals(entry.get("safety"), "interruptible") || gcBoxed(entry) || conflictSymbols.contains(entry.get("symbol"))) selectedImports.add(entry);
        List<Map<?,?>> expected = Collections.unmodifiableList(selectedImports);
        check(list(archive.get("unsupportedImports")).equals(expected), "unsupported import inventory differs");
        var unresolved = new ArrayList<String>();
        for (Object value : list(archive.get("unresolvedSymbols"))) unresolved.add(text(value));
        check(new ArrayList<>(new LinkedHashSet<>(unresolved)).equals(unresolved), "duplicate unresolved symbols");
        Object artifact = archive.get("artifact"); check((artifact == null) == unresolved.isEmpty(), "unresolved artifact pair");
        check(unknown != null || !expected.isEmpty() || !unresolved.isEmpty(), "empty archive obligation");
        if (artifact != null) {
            check(!module.containsKey("packageNativeLink") && unknown == null, "archive is also executable");
            var retained = new LinkedHashMap<Object,Object>(module); retained.put("packageNativeLink", artifact);
            PackageScalarLinks.read(retained, false, completeBindings);
        }
        return new PackageNativeArchive(unit + ":" + module.get("module") + " has archive-only native obligations" + " (unsupported=" + expected.size() + ", conflicting=" + conflictSymbols + ", unclassified=" + unknown + ", unresolved=" + unresolved + ")", unknown != null || !unresolved.isEmpty(), unit, expected);
    }
    private static void proofIdentity(Map<?,?> proof, Map<?,?> module) {
        check((version(proof.get("schema"), 1) || version(proof.get("schema"), 2) || version(proof.get("schema"), 3) || version(proof.get("schema"), 4)) && Objects.equals(proof.get("scope"), "retained-static-import-products") && Objects.equals(proof.get("execution"), "not-linked") && importProfile(proof.get("profile")) && Objects.equals(proof.get("unit"), module.get("unit")) && Objects.equals(proof.get("module"), module.get("module")), "typed provenance identity");
    }
}
