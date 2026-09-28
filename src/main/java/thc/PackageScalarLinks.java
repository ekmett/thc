// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

/** A package-owned link needs both the typed GHC import and its complete component ABI. */
public final class PackageScalarLinks {
    public static final PackageScalarLinks INSTANCE = new PackageScalarLinks();
    private PackageScalarLinks() {}
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SYMBOL = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> REPS = Set.of("Int32Rep", "Int64Rep", "FloatRep", "DoubleRep");
    private static final Set<String> NATIVE_REPS = Set.of("Int32Rep", "Int64Rep", "FloatRep", "DoubleRep", "IntRep", "WordRep", "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep", "Word32Rep", "Word64Rep", "AddrRep", "ByteArray#", "MutableByteArray#");
    private static void check(boolean value, String detail) { if (!value) throw new IllegalArgumentException("Invalid package scalar link: " + detail); }
    private static Map<?,?> record(Object value, String keys) {
        check(value instanceof Map<?,?> fields && fields.keySet().equals(Set.of(keys.split(" "))), "record fields");
        return (Map<?,?>) value;
    }
    private static String text(Object value) { check(value instanceof String s && !s.isEmpty() && s.indexOf(0) < 0, "missing text"); return (String) value; }
    private static List<?> list(Object value) { check(value instanceof List<?>, "missing list"); return (List<?>) value; }
    private static boolean version(Object value, int wanted) { return Objects.equals(value, wanted) || Objects.equals(value, (long) wanted); }
    private static boolean in(Object value, String... values) { return Arrays.asList(values).contains(value); }
    private static boolean admitted(Set<String> reps, Object value) { return value instanceof String s && reps.contains(s); }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    private static List<Object> calls(Object value) {
        var result = new ArrayList<Object>();
        if (value instanceof Map<?,?> fields) {
            if (fields.get("foreignCall") != null) result.add(fields.get("foreignCall"));
            fields.values().forEach(child -> result.addAll(calls(child)));
        } else if (value instanceof List<?> fields) fields.forEach(child -> result.addAll(calls(child)));
        return result;
    }
    public static Map<?,?> archiveIdentity(Object value) {
        var fields = record(value, "unit module occurrence namespace"); fields.values().forEach(PackageScalarLinks::text);
        check(in(fields.get("namespace"), "value", "type", "data"), "name namespace"); return fields;
    }
    public static void archiveType(Object value) { type(value, 0); }
    private static void type(Object value, int depth) {
        check(value instanceof Map<?,?>, "type record"); var raw = (Map<?,?>) value;
        switch (Objects.toString(raw.get("kind"), "")) {
            case "tycon" -> { record(raw, "kind name arguments"); archiveIdentity(raw.get("name")); list(raw.get("arguments")).forEach(item -> type(item, depth)); }
            case "application" -> { record(raw, "kind function argument"); type(raw.get("function"), depth); type(raw.get("argument"), depth); }
            case "function" -> { record(raw, "kind multiplicity argument result"); type(raw.get("multiplicity"), depth); type(raw.get("argument"), depth); type(raw.get("result"), depth); }
            case "forall" -> { record(raw, "kind binderKind body"); type(raw.get("binderKind"), depth); type(raw.get("body"), depth + 1); }
            case "bound-variable" -> { record(raw, "kind index"); var index = raw.get("index"); check((index instanceof Long || index instanceof Integer) && ((Number) index).longValue() >= 0 && ((Number) index).longValue() < depth, "free import type variable"); }
            default -> check(false, "unknown type");
        }
    }
    private static void target(String target) {
        String system = System.getProperty("os.name");
        String arch = switch (System.getProperty("os.arch")) { case "amd64" -> "x86_64"; case "arm64" -> "aarch64"; default -> System.getProperty("os.arch"); };
        String cpu = target.split("-", 2)[0]; if (cpu.equals("arm64")) cpu = "aarch64";
        check(cpu.equals(arch) && (system.equals("Linux") && target.endsWith("-linux-gnu") || system.startsWith("Mac") && (target.contains("-darwin") || target.contains("-apple-macosx"))), "target differs from runtime");
    }
    private static String pointerAbi(String rep) { return in(rep, "ByteArray#", "MutableByteArray#") ? "AddrRep" : rep; }
    private static String integerAbi(String rep) { return in(rep, "IntRep", "Int8Rep", "Int16Rep", "Int32Rep", "Int64Rep") ? "Word" + rep.substring(3) : rep; }
    public static PackageScalarAdmission read(Map<?,?> module) { return read(module, true, true); }
    public static PackageScalarAdmission read(Map<?,?> module, boolean validateArchive) { return read(module, validateArchive, true); }
    public static PackageScalarAdmission read(Map<?,?> module, boolean validateArchive, boolean completeBindings) {
        if (validateArchive) PackageNativeArchives.read(module, completeBindings);
        boolean nativeLink = module.containsKey("packageNativeLink");
        Object raw = module.get(nativeLink ? "packageNativeLink" : "packageScalarLink"); if (raw == null) return null;
        check(!nativeLink || !module.containsKey("packageScalarLink"), "two package link profiles");
        check(Objects.equals(module.get("ghc"), "9.14.1") && (version(module.get("schema"), 1) || nativeLink && version(module.get("schema"), 2)), "GHC/schema");
        check((nativeLink || !module.containsKey("foreign")) && !module.containsKey("foreignLink") && (nativeLink || !module.containsKey("staticForeignImportStubs")) && !module.containsKey("staticForeignExports") && !module.containsKey("staticForeignExportRegistration"), "mixed foreign obligations");
        boolean inputs = nativeLink && raw instanceof Map<?,?> m && m.containsKey("buildInputs");
        boolean partial = nativeLink && raw instanceof Map<?,?> m && m.containsKey("availableEntries");
        boolean callbacks = nativeLink && raw instanceof Map<?,?> m && version(m.get("schema"), 2);
        boolean companion = nativeLink && raw instanceof Map<?,?> m && m.containsKey("nativeLibrary");
        var fields = record(raw, "schema format profile unit target componentSha256 bitcodeSha256 bitcodeHex abi" + (inputs ? " buildInputs" : "") + (partial ? " availableEntries" : "") + (callbacks ? " finalizers" : "") + (companion ? " nativeLibrary" : ""));
        if (inputs) check(fields.get("buildInputs") instanceof Map<?,?>, "build inputs record");
        String format = text(fields.get("format"));
        check((version(fields.get("schema"), 1) || callbacks) && (format.equals("llvm-bitcode") || nativeLink &&
            (format.equals("llvm-embedded-elf") && System.getProperty("os.name").equals("Linux") ||
             format.equals("llvm-embedded-mach-o") && System.getProperty("os.name").startsWith("Mac"))) &&
            Objects.equals(fields.get("profile"), nativeLink ? "thc-package-c-ffi-v1" : "thc-local-scalar-ccall-v1"), "link profile");
        String unit = text(fields.get("unit")); check(unit.equals(module.get("unit")), "component owner");
        String target = text(fields.get("target")); target(target);
        String componentHash = text(fields.get("componentSha256")), bitcodeHash = text(fields.get("bitcodeSha256"));
        check(HASH.matcher(componentHash).matches() && HASH.matcher(bitcodeHash).matches(), "digest");
        String encoded = text(fields.get("bitcodeHex"));
        boolean validEncoding = encoded.length() % 2 == 0;
        if (validEncoding) for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) { validEncoding = false; break; }
        }
        check(validEncoding, "bitcode encoding");
        byte[] bytes = HexFormat.of().parseHex(encoded); check(bytes.length != 0 && digest(bytes).equals(bitcodeHash), "bitcode digest");
        byte[] nativeLibrary = new byte[0];
        if (companion) {
            check(!partial && !format.equals("llvm-bitcode"), "native dependency container");
            var dependency = record(fields.get("nativeLibrary"), "sha256 hex");
            String hex = text(dependency.get("hex")); nativeLibrary = HexFormat.of().parseHex(hex);
            check(nativeLibrary.length != 0 && HexFormat.of().formatHex(nativeLibrary).equals(hex) &&
                digest(nativeLibrary).equals(text(dependency.get("sha256"))), "native dependency digest");
        }
        var entries = list(fields.get("abi")); var abi = new ArrayList<PackageScalarSignature>();
        for (int index = 0; index < entries.size(); index++) {
            var entry = record(entries.get(index), nativeLink ? "symbol entry convention safety arguments result" : "symbol entry arguments result");
            String name = text(entry.get("symbol")); check(SYMBOL.matcher(name).matches(), "C symbol");
            check(Objects.equals(entry.get("entry"), "thc_" + (nativeLink ? "native" : "scalar") + "_" + componentHash + "_" + index), "component entry namespace");
            var arguments = list(entry.get("arguments")); var admitted = nativeLink ? NATIVE_REPS : REPS;
            boolean validArguments = true;
            for (Object item : arguments) if (!admitted(admitted, item)) { validArguments = false; break; }
            check(validArguments && (nativeLink ? in(entry.get("result"), "void") || admitted(NATIVE_REPS, entry.get("result")) && !in(entry.get("result"), "ByteArray#", "MutableByteArray#") : admitted(REPS, entry.get("result"))), "C ABI");
            String convention = nativeLink ? text(entry.get("convention")) : "ccall", safety = nativeLink ? text(entry.get("safety")) : "unsafe";
            // Temporary safe-as-unsafe policy; declared metadata stays exact.
            check(!nativeLink || in(convention, "ccall", "capi") && in(safety, "unsafe", "safe"), "unsupported C calling convention/safety");
            String entryName = (String) entry.get("entry");
            var argumentTypes = new ArrayList<String>();
            for (Object item : arguments) argumentTypes.add((String) item);
            abi.add(new PackageScalarSignature(name, entryName, Collections.unmodifiableList(argumentTypes), (String) entry.get("result"), convention, safety));
        }
        var ordered = Comparator.comparing(PackageScalarSignature::symbol).thenComparing(PackageScalarSignature::convention).thenComparing(PackageScalarSignature::safety).thenComparing(item -> String.join("\u0000", item.arguments())).thenComparing(PackageScalarSignature::result);
        boolean validAbi = !abi.isEmpty();
        if (validAbi) {
            var sorted = new ArrayList<>(abi); sorted.sort(ordered);
            validAbi = abi.equals(sorted);
            if (validAbi) {
                var signatures = new HashSet<List<?>>();
                for (var item : abi) signatures.add(List.of(item.symbol(), item.convention(), item.safety(), item.arguments(), item.result()));
                validAbi = signatures.size() == abi.size();
            }
        }
        check(validAbi, "sorted unique ABI");
        var bySymbol = new LinkedHashMap<String,List<PackageScalarSignature>>(); for (var item : abi) bySymbol.computeIfAbsent(item.symbol(), ignored -> new ArrayList<>()).add(item);
        var headerAdapted = new HashSet<String>();
        for (var group : bySymbol.entrySet()) {
            var variants = group.getValue();
            var pointerVariants = new HashSet<List<String>>();
            for (var item : variants) {
                var arguments = new ArrayList<String>();
                for (String rep : item.arguments()) arguments.add(pointerAbi(rep));
                pointerVariants.add(arguments);
            }
            if (pointerVariants.size() > 1) headerAdapted.add(group.getKey());
            check(nativeLink || variants.size() == 1, "duplicate scalar ABI symbol");
            var cVariants = new HashSet<List<?>>();
            for (var item : variants) {
                String convention = item.convention(), safety = item.safety().equals("safe") ? "unsafe" : item.safety();
                var arguments = new ArrayList<String>();
                for (String rep : item.arguments()) arguments.add(integerAbi(pointerAbi(rep)));
                cVariants.add(List.of(convention, safety, arguments, item.result()));
            }
            check(cVariants.size() == 1, "conflicting C ABI variants");
            var mutabilityVariants = new HashSet<List<?>>();
            for (var item : variants) {
                String convention = item.convention(), safety = item.safety();
                var arguments = new ArrayList<String>();
                for (String rep : item.arguments()) arguments.add(rep.equals("MutableByteArray#") ? "ByteArray#" : rep);
                mutabilityVariants.add(List.of(convention, safety, arguments, item.result()));
            }
            check(mutabilityVariants.size() == variants.size(), "ambiguous byte-array mutability variants");
        }
        var available = partial ? PackageNativeArchives.available(module) : null;
        var finalizers = PackageFinalizers.entries(fields, abi);
        check(finalizers.isEmpty() || !partial, "partial finalizer archive");
        var selectedAbi = new ArrayList<PackageScalarSignature>();
        for (var item : abi) if (available == null || available.contains(item.entry())) selectedAbi.add(item);
        var link = new PackageScalarLink(unit, target, componentHash, bitcodeHash, bytes, Collections.unmodifiableList(selectedAbi), format, finalizers, nativeLibrary);
        if (nativeLink && !module.containsKey("staticForeignImports")) {
            check(!module.containsKey("foreign") && !module.containsKey("staticForeignImportStubs"), "foreign products lack import provenance");
            return new PackageScalarAdmission(link, Set.of());
        }
        boolean addressProof = module.get("staticForeignImports") instanceof Map<?,?> m && version(m.get("schema"), 2);
        var proof = record(module.get("staticForeignImports"), "schema scope execution profile unit module status wordBits expectedForeign imports expectedCalls" + (addressProof ? " addresses" : ""));
        if (nativeLink && module.containsKey("staticForeignImportStubs")) check(Objects.equals(module.get("staticForeignImportStubs"), proof), "retained CAPI import provenance differs");
        check((version(proof.get("schema"), 1) || addressProof) && Objects.equals(proof.get("scope"), "retained-static-import-products") && Objects.equals(proof.get("execution"), "not-linked") && Objects.equals(proof.get("profile"), "ghc-9.14.1-thc-only-static-c-imports-v1") && Objects.equals(proof.get("unit"), unit) && Objects.equals(proof.get("module"), module.get("module")) && Objects.equals(proof.get("status"), "verified") && version(proof.get("wordBits"), 64), "typed import profile/owner");
        var product = record(proof.get("expectedForeign"), "schema execution stubs files");
        check(version(product.get("schema"), 1) && Objects.equals(product.get("execution"), "not-linked") && Objects.equals(product.get("files"), List.of()), "foreign product");
        if (nativeLink && module.containsKey("foreign")) check(product.equals(module.get("foreign")), "retained C stubs differ");
        if (product.get("stubs") != null) {
            var stubs = record(product.get("stubs"), "header source initializers finalizers");
            check(Objects.equals(stubs.get("header"), "") && (nativeLink ? stubs.get("source") instanceof String : Objects.equals(stubs.get("source"), "")) && Objects.equals(stubs.get("initializers"), List.of()) && Objects.equals(stubs.get("finalizers"), List.of()), "nonempty foreign products");
            check(!nativeLink || module.containsKey("foreign") || Objects.equals(stubs.get("source"), ""), "missing retained C stubs");
        }
        var imports = list(proof.get("imports")); check(nativeLink || !imports.isEmpty(), "empty import inventory");
        var binders = new HashSet<Map<?,?>>(); var proved = new HashSet<String>();
        proved.addAll(PackageFinalizers.proved(module, link));
        for (Object value : imports) {
            var item = record(value, "binder header symbol unit isFunction convention safety declaredType normalizedType normalizationRole emitted");
            if (PackageNativeArchives.excluded(module).contains(item.get("emitted"))) continue;
            var binder = archiveIdentity(item.get("binder")); check(Objects.equals(binder.get("unit"), unit) && Objects.equals(binder.get("module"), module.get("module")) && Objects.equals(binder.get("namespace"), "value") && binders.add(binder), "import binder");
            Object convention = item.get("convention"), header = item.get("header");
            check((header == null || nativeLink && header instanceof String s && !s.isEmpty() && s.indexOf(0) < 0) && (item.get("unit") == null || Objects.equals(item.get("unit"), unit)) && (Objects.equals(item.get("isFunction"), true) || nativeLink && Objects.equals(convention, "capi") && Objects.equals(item.get("isFunction"), false)) && (nativeLink ? in(convention, "ccall", "capi") : Objects.equals(convention, "ccall")) && (nativeLink ? in(item.get("safety"), "unsafe", "safe") : Objects.equals(item.get("safety"), "unsafe")) && Objects.equals(item.get("normalizationRole"), "representational"), "static supported C import");
            archiveType(item.get("declaredType")); archiveType(item.get("normalizedType")); text(item.get("symbol"));
            var emitted = record(item.get("emitted"), "symbol unit convention safety arguments result");
            String name = nativeLink ? text(emitted.get("symbol")) : text(item.get("symbol"));
            if (headerAdapted.contains(name)) {
                boolean validHeader = Objects.equals(convention, "ccall") && header instanceof String s && !s.isEmpty();
                if (validHeader) {
                    String text = (String) header;
                    for (int i = 0; i < text.length(); i++) if ("\u0000\n\r\"\\".indexOf(text.charAt(i)) >= 0) { validHeader = false; break; }
                }
                check(validHeader, "signedness variants require a retained configured C header");
            }
            var matches = new ArrayList<PackageScalarSignature>();
            for (var signature : bySymbol.getOrDefault(name, List.of()))
                if (Objects.equals(signature.convention(), convention) && Objects.equals(signature.safety(), item.get("safety")) && Objects.equals(emitted.get("arguments"), arguments(signature)) && Objects.equals(emitted.get("result"), result(signature))) matches.add(signature);
            if (matches.size() != 1) throw new IllegalArgumentException("Unlinked typed package C import variant: " + name);
            var signature = matches.getFirst();
            check(Objects.equals(emitted.get("symbol"), name) && Objects.equals(emitted.get("unit"), unit) && Objects.equals(emitted.get("convention"), signature.convention()) && Objects.equals(convention, signature.convention()) && Objects.equals(emitted.get("safety"), signature.safety()) && Objects.equals(emitted.get("arguments"), arguments(signature)) && Objects.equals(emitted.get("result"), result(signature)), "emitted ABI differs from compiled C");
            // A callable import is not authority to publish a typed CLabel finalizer.
            if (!finalizers.contains(signature.entry())) proved.add(signature.entry());
        }
        CoreCallInventory.check(proof.get("expectedCalls"), calls(module.get("bindings")), completeBindings);
        var admittedEntries = new ArrayList<String>();
        for (var signature : link.getAbi()) admittedEntries.add(signature.entry());
        proved.retainAll(admittedEntries); return new PackageScalarAdmission(link, proved);
    }
    private static List<String> arguments(PackageScalarSignature signature) { var args = new ArrayList<>(signature.arguments()); args.add("void"); return args; }
    private static List<String> result(PackageScalarSignature signature) { return signature.result().equals("void") ? List.of("void") : List.of("void", signature.result()); }
}
