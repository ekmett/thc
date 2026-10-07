// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.charset.StandardCharsets;
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
    private static boolean canonicalHex(String encoded) {
        if (encoded.length() % 2 != 0) return false;
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) return false;
        }
        return true;
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
        check(cpu.equals(arch) && (system.equals("Linux") && target.endsWith("-linux-gnu") ||
            system.startsWith("Mac") && (target.contains("-darwin") || target.contains("-apple-macosx")) ||
            system.startsWith("Windows") && cpu.equals("x86_64") && target.startsWith("x86_64-pc-windows-msvc")),
            "target differs from runtime");
    }
    private static String pointerAbi(String rep) { return in(rep, "ByteArray#", "MutableByteArray#") ? "AddrRep" : rep; }
    private static String integerAbi(String rep) { return in(rep, "IntRep", "Int8Rep", "Int16Rep", "Int32Rep", "Int64Rep") ? "Word" + rep.substring(3) : rep; }
    private static Set<String> nativeExports(Object raw) {
        var result = new LinkedHashSet<String>();
        for (Object value : list(raw)) {
            String name = text(value);
            check(SYMBOL.matcher(name).matches() && result.add(name), "unique C provider export");
        }
        return result;
    }
    private static Map<?,?> nativeLibrary(Object raw) {
        boolean bundled = raw instanceof Map<?,?> library && library.containsKey("bundledLibraries");
        return record(raw, "sha256 hex" + (bundled ? " bundledLibraries" : ""));
    }
    private static List<PackageNativeComponent.BundledLibrary> bundledLibraries(Map<?,?> library) {
        if (!library.containsKey("bundledLibraries")) return List.of();
        check(System.getProperty("os.name").equals("Linux"), "bundled native libraries require Linux");
        var names = new HashSet<String>(); var result = new ArrayList<PackageNativeComponent.BundledLibrary>();
        for (Object raw : list(library.get("bundledLibraries"))) {
            var provider = record(raw, "name sha256 hex");
            String name = text(provider.get("name")), hash = text(provider.get("sha256")), hex = text(provider.get("hex"));
            check(name.matches("[A-Za-z0-9_][A-Za-z0-9_.+\\-]*") && names.add(name), "unique bundled native library basename");
            check(HASH.matcher(hash).matches() && canonicalHex(hex), "bundled native library encoding");
            byte[] bytes = HexFormat.of().parseHex(hex);
            check(bytes.length != 0 && digest(bytes).equals(hash), "bundled native library digest");
            result.add(new PackageNativeComponent.BundledLibrary(name, hash, bytes));
        }
        return List.copyOf(result);
    }
    private static List<PackageNativeComponent> nativeDependencies(Object raw, String target, Set<String> path,
            Map<String,PackageNativeComponent> components, Map<String,String> namespaces) {
        var result = new ArrayList<PackageNativeComponent>(); var owners = new HashSet<String>();
        for (Object value : list(raw)) {
            boolean companion = value instanceof Map<?,?> m && m.containsKey("nativeLibrary");
            var fields = record(value, "schema profile unit target componentSha256 bitcodeSha256 bitcodeHex format exports dependencies" +
                (companion ? " nativeLibrary" : ""));
            check(version(fields.get("schema"), 1) && Objects.equals(fields.get("profile"), "thc-package-native-component-v1"), "native component profile");
            String unit = text(fields.get("unit"));
            check(owners.add(unit) && path.add(unit), "duplicate or cyclic native dependency");
            check(target.equals(fields.get("target")), "native dependency target");
            String componentHash = text(fields.get("componentSha256")), bitcodeHash = text(fields.get("bitcodeSha256"));
            check(HASH.matcher(componentHash).matches() && HASH.matcher(bitcodeHash).matches(), "native dependency digest");
            String namespace = namespaces.putIfAbsent(componentHash, unit);
            check(namespace == null || namespace.equals(unit), "native dependency namespace owner");
            String format = text(fields.get("format"));
            check(format.equals("llvm-bitcode") || format.equals("llvm-embedded-elf") && System.getProperty("os.name").equals("Linux") ||
                format.equals("llvm-embedded-mach-o") && System.getProperty("os.name").startsWith("Mac"), "native dependency format");
            String encoded = text(fields.get("bitcodeHex")); byte[] bytes = HexFormat.of().parseHex(encoded);
            check(bytes.length != 0 && canonicalHex(encoded) && digest(bytes).equals(bitcodeHash), "native dependency bitcode");
            byte[] nativeLibrary = new byte[0];
            List<PackageNativeComponent.BundledLibrary> bundledLibraries = List.of();
            if (companion) {
                var library = nativeLibrary(fields.get("nativeLibrary"));
                bundledLibraries = bundledLibraries(library);
                String hex = text(library.get("hex")); nativeLibrary = HexFormat.of().parseHex(hex);
                check(nativeLibrary.length != 0 && canonicalHex(hex) &&
                    digest(nativeLibrary).equals(text(library.get("sha256"))), "native dependency companion");
            }
            var component = new PackageNativeComponent(unit, target, componentHash, bitcodeHash, format, bytes, nativeLibrary,
                nativeExports(fields.get("exports")), nativeDependencies(fields.get("dependencies"), target, path, components, namespaces), bundledLibraries);
            var prior = components.putIfAbsent(unit, component);
            check(prior == null || prior.same(component), "conflicting native dependency identity");
            result.add(prior == null ? component : prior); path.remove(unit);
        }
        return result;
    }
    public static PackageScalarAdmission read(Map<?,?> module) { return read(module, true, true); }
    public static PackageScalarAdmission read(Map<?,?> module, boolean validateArchive) { return read(module, validateArchive, true); }
    public static PackageScalarAdmission read(Map<?,?> module, boolean validateArchive, boolean completeBindings) {
        if (validateArchive) PackageNativeArchives.read(module, completeBindings);
        boolean nativeLink = module.containsKey("packageNativeLink");
        Object raw = module.get(nativeLink ? "packageNativeLink" : "packageScalarLink"); if (raw == null) return null;
        check(!nativeLink || !module.containsKey("packageScalarLink"), "two package link profiles");
        check(Objects.equals(module.get("ghc"), "9.14.1") && (version(module.get("schema"), 1) || nativeLink && version(module.get("schema"), 2)), "GHC/schema");
        check((nativeLink || !module.containsKey("foreign")) && !module.containsKey("foreignLink") && (nativeLink || !module.containsKey("staticForeignImportStubs")) &&
            (nativeLink || !module.containsKey("staticForeignExports") && !module.containsKey("staticForeignExportRegistration")), "mixed foreign obligations");
        if (nativeLink && (module.containsKey("staticForeignExports") || module.containsKey("staticForeignExportRegistration")))
            ManagedExportAdmission.declarations(module, completeBindings);
        boolean inputs = nativeLink && raw instanceof Map<?,?> m && m.containsKey("buildInputs");
        boolean demand = nativeLink && raw instanceof Map<?,?> m && version(m.get("schema"), 3);
        boolean callbacks = nativeLink && raw instanceof Map<?,?> m && version(m.get("schema"), 2);
        boolean companion = nativeLink && raw instanceof Map<?,?> m && m.containsKey("nativeLibrary");
        boolean data = nativeLink && raw instanceof Map<?,?> m && m.containsKey("dataSymbols");
        boolean components = nativeLink && raw instanceof Map<?,?> m && m.containsKey("dependencies");
        var fields = record(raw, "schema format profile unit target componentSha256 bitcodeSha256 bitcodeHex abi" + (inputs ? " buildInputs" : "") + (callbacks ? " finalizers" : "") + (companion ? " nativeLibrary" : "") + (data ? " dataSymbols" : "") + (components ? " exports dependencies" : "") + (demand ? " callSeeds" : ""));
        if (inputs) check(fields.get("buildInputs") instanceof Map<?,?>, "build inputs record");
        String format = text(fields.get("format"));
        check((version(fields.get("schema"), 1) || callbacks || demand) && (format.equals("llvm-bitcode") || nativeLink &&
            (format.equals("llvm-embedded-elf") && System.getProperty("os.name").equals("Linux") ||
             format.equals("llvm-embedded-mach-o") && System.getProperty("os.name").startsWith("Mac"))) &&
            Objects.equals(fields.get("profile"), demand ? "thc-package-c-ffi-demand-v1" : nativeLink ? "thc-package-c-ffi-v1" : "thc-local-scalar-ccall-v1"), "link profile");
        String unit = text(fields.get("unit")); check(unit.equals(module.get("unit")), "component owner");
        String target = text(fields.get("target")); target(target);
        String componentHash = text(fields.get("componentSha256")), bitcodeHash = text(fields.get("bitcodeSha256"));
        check(HASH.matcher(componentHash).matches() && HASH.matcher(bitcodeHash).matches(), "digest");
        check(fields.get("bitcodeHex") instanceof String, "bitcode encoding");
        String encoded = (String) fields.get("bitcodeHex");
        check(canonicalHex(encoded), "bitcode encoding");
        byte[] bytes = HexFormat.of().parseHex(encoded); check((demand || bytes.length != 0) && digest(bytes).equals(bitcodeHash), "bitcode digest");
        byte[] nativeLibrary = new byte[0];
        List<PackageNativeComponent.BundledLibrary> bundledLibraries = List.of();
        if (companion) {
            var dependency = nativeLibrary(fields.get("nativeLibrary"));
            bundledLibraries = bundledLibraries(dependency);
            String hex = text(dependency.get("hex")); nativeLibrary = HexFormat.of().parseHex(hex);
            check(nativeLibrary.length != 0 && canonicalHex(hex) &&
                digest(nativeLibrary).equals(text(dependency.get("sha256"))), "native dependency digest");
        }
        var dataSymbols = new HashSet<String>();
        if (data) for (Object value : list(fields.get("dataSymbols")))
            check(dataSymbols.add(text(value)), "duplicate address symbol");
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
            // Declared safety selects runtime foreign-call entry and readmission.
            check(!nativeLink || in(convention, "ccall", "capi") && in(safety, "unsafe", "safe"), "unsupported C calling convention/safety");
            String entryName = (String) entry.get("entry");
            var argumentTypes = new ArrayList<String>();
            for (Object item : arguments) argumentTypes.add((String) item);
            abi.add(new PackageScalarSignature(name, entryName, Collections.unmodifiableList(argumentTypes), (String) entry.get("result"), convention, safety));
        }
        var ordered = Comparator.comparing(PackageScalarSignature::symbol).thenComparing(PackageScalarSignature::convention).thenComparing(PackageScalarSignature::safety).thenComparing(item -> String.join("\u0000", item.arguments())).thenComparing(PackageScalarSignature::result).thenComparing(item -> dataSymbols.contains(item.entry()));
        boolean validAbi = !abi.isEmpty();
        if (validAbi) {
            var sorted = new ArrayList<>(abi); sorted.sort(ordered);
            validAbi = abi.equals(sorted);
            if (validAbi) {
                var signatures = new HashSet<List<?>>();
                for (var item : abi) signatures.add(List.of(item.symbol(), item.convention(), item.safety(), item.arguments(), item.result(), dataSymbols.contains(item.entry())));
                validAbi = signatures.size() == abi.size();
            }
        }
        check(validAbi, "sorted unique ABI");
        var bySymbol = new LinkedHashMap<String,List<PackageScalarSignature>>();
        for (var item : abi) if (!dataSymbols.contains(item.entry()))
            bySymbol.computeIfAbsent(item.symbol(), ignored -> new ArrayList<>()).add(item);
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
            // Exact typed calls distinguish array mutability. Erased ambiguous
            // calls remain rejected by CorePackageScalarForeign selection.
        }
        var finalizers = PackageFinalizers.entries(fields, abi);
        for (String entry : dataSymbols) {
            check(!finalizers.contains(entry), "callable address symbol");
            check(abi.stream().anyMatch(signature -> signature.entry().equals(entry) && signature.arguments().isEmpty() &&
                signature.result().equals("AddrRep") && signature.convention().equals("ccall") && signature.safety().equals("unsafe")), "symbol address ABI");
        }
        var selectedAbi = abi;
        var exports = components ? nativeExports(fields.get("exports")) : Set.<String>of();
        var dependencies = components ? nativeDependencies(fields.get("dependencies"), target, new HashSet<>(Set.of(unit)),
            new HashMap<>(), new HashMap<>(Map.of(componentHash, unit))) : List.<PackageNativeComponent>of();
        var seeds = new LinkedHashMap<String, PackageScalarLink.CallSeed>();
        if (demand) {
            check(format.equals("llvm-bitcode") && components && !data && finalizers.isEmpty() &&
                abi.stream().allMatch(signature -> signature.convention().equals("ccall")), "ordinary demand component profile");
            var providers = new HashMap<String, PackageNativeComponent>();
            if (bytes.length != 0) providers.put(unit, new PackageNativeComponent(unit, target, componentHash, bitcodeHash,
                format, bytes, nativeLibrary, exports, dependencies, bundledLibraries));
            for (var dependency : dependencies) collectProviders(dependency, providers);
            for (Object item : list(fields.get("callSeeds"))) {
                var seed = record(item, "entry bitcodeHex bitcodeSha256 providerUnit providerComponentSha256 providerSymbol");
                String entry = text(seed.get("entry")), seedHash = text(seed.get("bitcodeSha256")), seedHex = text(seed.get("bitcodeHex"));
                byte[] seedBytes = HexFormat.of().parseHex(seedHex);
                check(HASH.matcher(seedHash).matches() && seedBytes.length != 0 &&
                    canonicalHex(seedHex) && digest(seedBytes).equals(seedHash), "call seed digest");
                check(abi.stream().anyMatch(signature -> signature.entry().equals(entry) && signature.convention().equals("ccall")) &&
                    !dataSymbols.contains(entry) && !finalizers.contains(entry), "ordinary call seed ABI");
                String providerUnit = null, providerHash = null, providerSymbol = null;
                if (seed.get("providerUnit") != null) {
                    providerUnit = text(seed.get("providerUnit")); providerHash = text(seed.get("providerComponentSha256"));
                    providerSymbol = text(seed.get("providerSymbol"));
                    var provider = providers.get(providerUnit);
                    check(provider != null && provider.componentSha256().equals(providerHash) &&
                        providerSymbol.startsWith("thc_provider_" + providerHash + "_") && SYMBOL.matcher(providerSymbol).matches() &&
                        provider.exports().contains(providerSymbol), "exact call seed provider owner");
                } else check(seed.get("providerComponentSha256") == null && seed.get("providerSymbol") == null, "absent call provider");
                check(seeds.putIfAbsent(entry, new PackageScalarLink.CallSeed(entry, seedHash, seedHex,
                    providerUnit, providerHash, providerSymbol)) == null, "duplicate call seed");
            }
            check(!seeds.isEmpty(), "missing call seeds");
            if (bytes.length == 0) check(nativeLibrary.length == 0 && exports.isEmpty() && dependencies.isEmpty() &&
                dataSymbols.isEmpty() && finalizers.isEmpty() && seeds.size() == abi.size(), "absent component obligations");
        }
        var link = new PackageScalarLink(unit, target, componentHash, bitcodeHash, bytes, Collections.unmodifiableList(selectedAbi), format, finalizers, nativeLibrary, dataSymbols, exports, dependencies, seeds, bundledLibraries);
        if (nativeLink && !module.containsKey("staticForeignImports")) {
            check(!module.containsKey("staticForeignImportStubs"), "unproved retained import obligations");
            if (module.containsKey("foreign")) {
                var product = record(module.get("foreign"), "schema execution stubs files");
                check(version(product.get("schema"), 1) && Objects.equals(product.get("execution"), "not-linked") &&
                    Objects.equals(product.get("files"), List.of()), "foreign product");
                if (product.get("stubs") != null) {
                    var stubs = record(product.get("stubs"), "header source initializers finalizers");
                    check(Objects.equals(stubs.get("header"), "") && stubs.get("source") instanceof String &&
                        Objects.equals(stubs.get("initializers"), List.of()) && Objects.equals(stubs.get("finalizers"), List.of()),
                        "foreign registration requires its managed protocol");
                }
            }
            // The compiled component is the callable ABI contract. Installed
            // FCallIds need no recreated source-import annotations; each reached
            // call is still checked against this ABI by CorePackageScalarForeign.
            var admitted = new HashSet<String>();
            // The shared component may include finalizers declared by another
            // module. Only that module's typed CLabels can prove those entries.
            for (var signature : selectedAbi)
                if (!finalizers.contains(signature.entry())) admitted.add(signature.entry());
            return new PackageScalarAdmission(link, admitted);
        }
        boolean mixedProof = module.get("staticForeignImports") instanceof Map<?,?> m && version(m.get("schema"), 4);
        boolean wrapperProof = mixedProof || module.get("staticForeignImports") instanceof Map<?,?> m && version(m.get("schema"), 3);
        boolean addressProof = wrapperProof || module.get("staticForeignImports") instanceof Map<?,?> m && version(m.get("schema"), 2);
        var proof = record(module.get("staticForeignImports"), "schema scope execution profile unit module status wordBits expectedForeign imports expectedCalls" + (addressProof ? " addresses" : "") + (wrapperProof ? " wrappers" : "") + (mixedProof ? " importForeign" : ""));
        if (nativeLink && module.containsKey("staticForeignImportStubs")) check(Objects.equals(module.get("staticForeignImportStubs"), proof), "retained CAPI import provenance differs");
        check((version(proof.get("schema"), 1) || addressProof) && Objects.equals(proof.get("scope"), "retained-static-import-products") && Objects.equals(proof.get("execution"), "not-linked") && PackageNativeArchives.importProfile(proof.get("profile")) && Objects.equals(proof.get("unit"), unit) && Objects.equals(proof.get("module"), module.get("module")) && Objects.equals(proof.get("status"), "verified") && version(proof.get("wordBits"), 64), "typed import profile/owner");
        var wrappers = ManagedCallbackMetadata.read(module, proof);
        var product = ManagedImportAdmission.importProduct(module, proof, completeBindings);
        check(version(product.get("schema"), 1) && Objects.equals(product.get("execution"), "not-linked") && Objects.equals(product.get("files"), List.of()), "foreign product");
        if (nativeLink && module.containsKey("foreign")) check(Objects.equals(proof.get("expectedForeign"), module.get("foreign")), "retained C stubs differ");
        if (product.get("stubs") != null) {
            var stubs = record(product.get("stubs"), "header source initializers finalizers");
            check((Objects.equals(stubs.get("header"), "") || nativeLink && !wrappers.isEmpty() && stubs.get("header") instanceof String) && (nativeLink ? stubs.get("source") instanceof String : Objects.equals(stubs.get("source"), "")) && Objects.equals(stubs.get("initializers"), List.of()) && Objects.equals(stubs.get("finalizers"), List.of()), "nonempty foreign products");
            check(!nativeLink || module.containsKey("foreign") || Objects.equals(stubs.get("source"), ""), "missing retained C stubs");
        }
        var imports = list(proof.get("imports")); check(nativeLink || !imports.isEmpty(), "empty import inventory");
        // Explicit JavaScript descriptors select Truffle, not a native adapter.
        // Their declarations and complete call inventory are still validated.
        var javascript = new HashSet<String>();
        for (Object value : list(proof.get("expectedCalls"))) {
            if (value instanceof Map<?,?> call && Objects.equals(call.get("intrinsic"), "javascript-v1") &&
                version(call.get("schema"), 1) && Objects.equals(call.get("convention"), "ccall") && in(call.get("safety"), "safe", "unsafe") &&
                call.get("javascriptSource") instanceof String source && !source.isEmpty() && call.get("target") instanceof Map<?,?> callTarget &&
                Objects.equals(callTarget.get("unit"), unit) && Objects.equals(callTarget.get("kind"), "static") && Boolean.TRUE.equals(callTarget.get("isFunction"))) {
                String symbol = "thc_javascript_v1_" + HexFormat.of().formatHex(source.getBytes(StandardCharsets.UTF_8));
                if (Objects.equals(callTarget.get("symbol"), symbol)) javascript.add(symbol);
            }
        }
        var binders = new HashSet<Map<?,?>>(); var proved = new HashSet<String>();
        proved.addAll(dataSymbols);
        proved.addAll(PackageFinalizers.proved(module, link));
        for (Object value : imports) {
            var item = record(value, "binder header symbol unit isFunction convention safety declaredType normalizedType normalizationRole emitted");
            if (PackageNativeArchives.excluded(module).contains(item.get("emitted"))) continue;
            var binder = archiveIdentity(item.get("binder")); check(Objects.equals(binder.get("unit"), unit) && Objects.equals(binder.get("module"), module.get("module")) && Objects.equals(binder.get("namespace"), "value") && binders.add(binder), "import binder");
            Object convention = item.get("convention"), header = item.get("header");
            check((header == null || nativeLink && header instanceof String s && !s.isEmpty() && s.indexOf(0) < 0) && (item.get("unit") == null || Objects.equals(item.get("unit"), unit)) && (Objects.equals(item.get("isFunction"), true) || nativeLink && Objects.equals(convention, "capi") && Objects.equals(item.get("isFunction"), false)) && (nativeLink ? in(convention, "ccall", "capi") : Objects.equals(convention, "ccall")) && (nativeLink ? in(item.get("safety"), "unsafe", "safe", "interruptible") : Objects.equals(item.get("safety"), "unsafe")) && Objects.equals(item.get("normalizationRole"), "representational"), "static supported C import");
            archiveType(item.get("declaredType")); archiveType(item.get("normalizedType")); text(item.get("symbol"));
            var emitted = record(item.get("emitted"), "symbol unit convention safety arguments result");
            if (nativeLink && javascript.contains(emitted.get("symbol"))) {
                var arguments = list(emitted.get("arguments")); var result = list(emitted.get("result"));
                check(Objects.equals(item.get("symbol"), emitted.get("symbol")) && Objects.equals(emitted.get("unit"), unit) &&
                    Objects.equals(emitted.get("convention"), convention) && Objects.equals(convention, "ccall") &&
                    Objects.equals(emitted.get("safety"), item.get("safety")) && in(item.get("safety"), "safe", "unsafe") &&
                    !arguments.isEmpty() && Objects.equals(arguments.getLast(), "void") && arguments.subList(0, arguments.size() - 1).stream().allMatch(rep -> admitted(NATIVE_REPS, rep)) &&
                    (result.equals(List.of("void")) || result.size() == 2 && Objects.equals(result.getFirst(), "void") && admitted(NATIVE_REPS, result.get(1)) &&
                     !in(result.get(1), "ByteArray#", "MutableByteArray#")), "JavaScript declaration differs from emitted ABI");
                continue;
            }
            if (nativeLink && thc.runtime.CoreForeignOverride.nativeImport(item, list(proof.get("expectedCalls")))) continue;
            if (nativeLink && Objects.equals(item.get("safety"), "interruptible")) {
                // Retain the original obligation, not an executable adapter.
                // A reached call still needs its actual scheduling boundary.
                check(Objects.equals(emitted.get("unit"), unit) && Objects.equals(emitted.get("convention"), convention) &&
                    Objects.equals(emitted.get("safety"), item.get("safety")), "unlinked declaration identity");
                continue;
            }
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
        CoreCallInventory.check(proof.get("expectedCalls"), CoreCallInventory.calls(module.get("bindings")), completeBindings);
        var admittedEntries = new ArrayList<String>();
        for (var signature : link.getAbi()) admittedEntries.add(signature.entry());
        proved.retainAll(admittedEntries); return new PackageScalarAdmission(link, proved);
    }
    private static void collectProviders(PackageNativeComponent component, Map<String, PackageNativeComponent> providers) {
        var old = providers.putIfAbsent(component.unit(), component);
        check(old == null || old.same(component), "conflicting call provider");
        if (old == null) for (var dependency : component.dependencies()) collectProviders(dependency, providers);
    }
    private static List<String> arguments(PackageScalarSignature signature) { var args = new ArrayList<>(signature.arguments()); args.add("void"); return args; }
    private static List<String> result(PackageScalarSignature signature) { return signature.result().equals("void") ? List.of("void") : List.of("void", signature.result()); }
}
