// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;
import java.util.regex.Pattern;

public final class PackageNativeArchives {
    public static final PackageNativeArchives INSTANCE = new PackageNativeArchives();
    private PackageNativeArchives() {}
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
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
    private static Map<?,?> emitted(Object value, String unit) {
        var emitted = record(value, "symbol unit convention safety arguments result");
        check(text(emitted.get("symbol")).matches("[A-Za-z_][A-Za-z0-9_]*") && Objects.equals(emitted.get("unit"), unit) && in(emitted.get("convention"), "ccall", "capi") && in(emitted.get("safety"), "unsafe", "safe", "interruptible"), "emitted identity");
        var args = list(emitted.get("arguments")); var result = list(emitted.get("result"));
        check(!args.isEmpty() && Objects.equals(args.getLast(), "void") && args.subList(0, args.size() - 1).stream().allMatch(arg -> scalar(arg) || in(arg, "ByteArray#", "MutableByteArray#")) && (result.equals(List.of("void")) || result.size() == 2 && Objects.equals(result.getFirst(), "void") && scalar(result.get(1))), "emitted carriers");
        return emitted;
    }
    private static List<?> cAbi(Map<?,?> emitted) {
        return Arrays.asList(emitted.get("convention"), Objects.equals(emitted.get("safety"), "safe") ? "unsafe" : emitted.get("safety"), list(emitted.get("arguments")).stream().map(rep -> {
            if (in(rep, "ByteArray#", "MutableByteArray#")) return "AddrRep";
            if (in(rep, "IntRep", "Int8Rep", "Int16Rep", "Int32Rep", "Int64Rep")) return "Word" + ((String) rep).substring(3);
            return rep;
        }).toList(), emitted.get("result"));
    }
    public static List<?> excluded(Map<?,?> module) {
        return module.get("packageNativeArchive") instanceof Map<?,?> archive && archive.get("unsupportedImports") instanceof List<?> imports ? imports : List.of();
    }
    private static Map<?,?> requiredMap(Object value, String message) { if (value instanceof Map<?,?> map) return map; throw new IllegalArgumentException(message); }
    private static Map<Object,Object> without(Map<?,?> map, String... keys) { var copy = new LinkedHashMap<Object,Object>(map); for (String key : keys) copy.remove(key); return copy; }
    private static List<String> names(Object raw) {
        var names = list(raw).stream().map(PackageNativeArchives::text).toList();
        check(names.equals(names.stream().distinct().sorted().toList()), "sorted unique dependency symbols"); return names;
    }
    /** A strict producer receipt binds every original adapter to its LLVM closure
     * and independently checked union. Old archives gain no authority. */
    public static Set<String> available(Map<?,?> module) {
        var archive = requiredMap(module.get("packageNativeArchive"), "Missing native entry archive");
        var original = requiredMap(archive.get("artifact"), "Missing original native artifact");
        var selected = requiredMap(module.get("packageNativeLink"), "Missing selected native artifact");
        var proof = record(archive.get("entryResolution"), "schema profile inputBitcodeSha256 entries outputBitcodeSha256 unresolved");
        check(version(proof.get("schema"), 1) && Objects.equals(proof.get("profile"), "llvm-globaldce-adapter-closures-v1"), "entry resolution profile");
        check(Objects.equals(original.get("format"), "llvm-bitcode") && Objects.equals(selected.get("format"), "llvm-bitcode") && Objects.equals(original.get("bitcodeSha256"), proof.get("inputBitcodeSha256")) && Objects.equals(selected.get("bitcodeSha256"), proof.get("outputBitcodeSha256")), "entry resolution content identity");
        check(without(original, "bitcodeSha256", "bitcodeHex").equals(without(selected, "bitcodeSha256", "bitcodeHex", "availableEntries")), "entry resolution changes component ABI/recipe");
        var unsupported = names(archive.get("unresolvedSymbols"));
        var externals = names(original.get("buildInputs") instanceof Map<?,?> inputs ? inputs.get("unresolved") : null);
        check(!unsupported.isEmpty() && externals.containsAll(unsupported), "original unresolved inventory");
        var entries = list(original.get("abi")).stream().map(value -> ((Map<?,?>) value).get("entry")).toList();
        record Dependency(String entry, List<String> symbols) {}
        var dependencies = new ArrayList<Dependency>();
        for (Object value : list(proof.get("entries"))) {
            var row = record(value, "entry bitcodeSha256 unresolved"); check(HASH.matcher(text(row.get("bitcodeSha256"))).matches(), "adapter closure digest");
            var symbols = names(row.get("unresolved")); check(externals.containsAll(symbols), "unrecorded adapter dependency");
            dependencies.add(new Dependency(text(row.get("entry")), symbols));
        }
        check(dependencies.stream().map(Dependency::entry).toList().equals(entries), "complete ordered adapter coverage");
        var available = dependencies.stream().filter(dep -> dep.symbols().stream().noneMatch(unsupported::contains)).map(Dependency::entry).toList();
        check(!available.isEmpty() && available.size() < entries.size() && Objects.equals(selected.get("availableEntries"), available), "exact available adapter selection");
        var union = names(proof.get("unresolved"));
        check(union.stream().noneMatch(unsupported::contains) && dependencies.stream().filter(dep -> available.contains(dep.entry())).flatMap(dep -> dep.symbols().stream()).toList().containsAll(union), "union unresolved dependencies");
        check(union.stream().allMatch(name -> in(name, "memcpy", "memmove", "memset", "memcmp", "bcmp", "__cxa_atexit", "__dso_handle") || name.startsWith("llvm.")), "selected bitcode requires an unrecorded native provider container");
        return new LinkedHashSet<>(available);
    }
    public static PackageNativeArchive read(Map<?,?> module) { return read(module, true); }
    public static PackageNativeArchive read(Map<?,?> module, boolean completeBindings) {
        Object raw = module.get("packageNativeArchive"); if (raw == null) return null;
        boolean conflictField = raw instanceof Map<?,?> map && map.containsKey("conflictingImports");
        boolean resolution = raw instanceof Map<?,?> map && map.containsKey("entryResolution");
        var archive = record(raw, "schema profile execution unit module unsupportedImports unclassifiedReason unresolvedSymbols artifact" + (conflictField ? " conflictingImports" : "") + (resolution ? " entryResolution" : ""));
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
            var p = record(proof, "schema scope execution profile unit module status wordBits expectedForeign imports expectedCalls"); proofIdentity(p, module);
            check(Objects.equals(p.get("status"), "verified") && version(p.get("wordBits"), 64), "verified import profile");
            var product = record(p.get("expectedForeign"), "schema execution stubs files");
            check(version(product.get("schema"), 1) && Objects.equals(product.get("execution"), "not-linked") && Objects.equals(product.get("files"), List.of()), "foreign product");
            if (product.get("stubs") != null) {
                var stubs = record(product.get("stubs"), "header source initializers finalizers");
                check(Objects.equals(stubs.get("header"), "") && stubs.get("source") instanceof String && Objects.equals(stubs.get("initializers"), List.of()) && Objects.equals(stubs.get("finalizers"), List.of()), "foreign stub obligations");
                check(module.containsKey("foreign") || Objects.equals(stubs.get("source"), ""), "missing retained stubs");
            }
            if (module.containsKey("foreign")) check(Objects.equals(module.get("foreign"), product), "retained product differs");
            CoreCallInventory.check(p.get("expectedCalls"), PackageNativeArchive.calls(module.get("bindings")), completeBindings);
            var binders = new HashSet<Object>();
            for (Object item : list(p.get("imports"))) {
                var entry = record(item, "binder header symbol unit isFunction convention safety declaredType normalizedType normalizationRole emitted");
                var binder = PackageScalarLinks.archiveIdentity(entry.get("binder"));
                check(Objects.equals(binder.get("unit"), unit) && Objects.equals(binder.get("module"), module.get("module")) && Objects.equals(binder.get("namespace"), "value") && binders.add(binder), "import binder");
                text(entry.get("symbol"));
                check(entry.get("header") == null || entry.get("header") instanceof String && text(entry.get("header")).chars().noneMatch(c -> "\n\r\"\\".indexOf(c) >= 0), "import header");
                check((entry.get("unit") == null || Objects.equals(entry.get("unit"), unit)) && in(entry.get("convention"), "ccall", "capi") && (Objects.equals(entry.get("isFunction"), true) || Objects.equals(entry.get("convention"), "capi") && Objects.equals(entry.get("isFunction"), false)) && in(entry.get("safety"), "unsafe", "safe", "interruptible") && Objects.equals(entry.get("normalizationRole"), "representational"), "import metadata");
                PackageScalarLinks.archiveType(entry.get("declaredType")); PackageScalarLinks.archiveType(entry.get("normalizedType"));
                var emitted = emitted(entry.get("emitted"), unit);
                check(Objects.equals(emitted.get("convention"), entry.get("convention")) && Objects.equals(emitted.get("safety"), entry.get("safety")), "emitted declaration"); imports.add(emitted);
            }
        }
        if (module.containsKey("staticForeignImportStubs")) check(Objects.equals(module.get("staticForeignImportStubs"), proof), "retained stub provenance differs");
        var conflicts = new ArrayList<Map<?,?>>(); if (conflictField) for (Object value : list(archive.get("conflictingImports"))) conflicts.add(emitted(value, unit));
        if (conflictField) check(!conflicts.isEmpty() && conflicts.stream().distinct().toList().equals(conflicts) && unknown == null, "conflicting import inventory");
        var conflictGroups = new LinkedHashMap<Object,List<Map<?,?>>>(); for (var entry : conflicts) conflictGroups.computeIfAbsent(entry.get("symbol"), ignored -> new ArrayList<>()).add(entry);
        for (var group : conflictGroups.entrySet()) {
            var variants = group.getValue(); check(variants.stream().allMatch(entry -> in(entry.get("safety"), "unsafe", "safe")) && variants.stream().map(PackageNativeArchives::cAbi).distinct().count() > 1, "imports do not have conflicting C ABIs");
            var local = imports.stream().filter(entry -> Objects.equals(entry.get("symbol"), group.getKey()) && !Objects.equals(entry.get("safety"), "interruptible")).toList();
            check(!local.isEmpty() && variants.containsAll(local), "conflict witnesses differ from local imports");
        }
        var conflictSymbols = conflictGroups.keySet();
        var expected = imports.stream().filter(entry -> Objects.equals(entry.get("safety"), "interruptible") || conflictSymbols.contains(entry.get("symbol"))).toList();
        check(list(archive.get("unsupportedImports")).equals(expected), "unsupported import inventory differs");
        var unresolved = list(archive.get("unresolvedSymbols")).stream().map(PackageNativeArchives::text).toList(); check(unresolved.stream().distinct().toList().equals(unresolved), "duplicate unresolved symbols");
        Object artifact = archive.get("artifact"); check((artifact == null) == unresolved.isEmpty(), "unresolved artifact pair");
        check(unknown != null || !expected.isEmpty() || !unresolved.isEmpty(), "empty archive obligation");
        if (artifact != null) {
            check((!module.containsKey("packageNativeLink") || resolution) && unknown == null, "archive is also executable");
            var retained = new LinkedHashMap<Object,Object>(module); retained.put("packageNativeLink", artifact);
            PackageScalarLinks.read(retained, false, completeBindings);
        }
        var available = resolution ? available(module) : null;
        if (available != null) PackageScalarLinks.read(module, false, completeBindings);
        var unavailable = new ArrayList<Map<?,?>>();
        if (available != null) for (Object value : list(((Map<?,?>) artifact).get("abi"))) { var entry = (Map<?,?>) value; if (!available.contains(entry.get("entry"))) unavailable.add(entry); }
        return new PackageNativeArchive(unit + ":" + module.get("module") + " has archive-only native obligations" + " (unsupported=" + expected.size() + ", conflicting=" + conflictSymbols + ", unclassified=" + unknown + ", unresolved=" + unresolved + ")", unknown != null || !unresolved.isEmpty() && available == null, unit, expected, unavailable);
    }
    private static void proofIdentity(Map<?,?> proof, Map<?,?> module) {
        check(version(proof.get("schema"), 1) && Objects.equals(proof.get("scope"), "retained-static-import-products") && Objects.equals(proof.get("execution"), "not-linked") && Objects.equals(proof.get("profile"), "ghc-9.14.1-thc-only-static-c-imports-v1") && Objects.equals(proof.get("unit"), module.get("unit")) && Objects.equals(proof.get("module"), module.get("module")), "typed provenance identity");
    }
}
