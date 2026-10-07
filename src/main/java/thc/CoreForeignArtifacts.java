// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Archive validation is not foreign linking, initialization or callback registration. */
public final class CoreForeignArtifacts {
    public static final CoreForeignArtifacts INSTANCE = new CoreForeignArtifacts();
    private CoreForeignArtifacts() {}
    private static boolean version(Object value, int expected) { return Objects.equals(value, expected) || Objects.equals(value, (long) expected); }
    private static boolean sha(Object value) { return value instanceof String text && text.matches("[0-9a-f]{64}"); }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    private static Map<String,Object> scalar(String primitive, boolean evaluated) {
        return Map.of("kind", primitive == null ? "void" : primitive.equals("AddrRep") ? "address" : "long",
                "primReps", primitive == null ? List.of() : List.of(primitive), "evaluated", evaluated);
    }
    private static Map<String,Object> tuple(String primitive) {
        return Map.of("kind", "unknown", "primReps", List.of(primitive), "aggregate", "unboxed-tuple",
                "components", List.of(scalar(null, true), scalar(primitive, true)), "evaluated", false);
    }
    private static Map<String,Object> expected(String unit, String symbol, boolean time, String kind) {
        boolean zero = kind.equals("clock-id") || kind.equals("time-clock-id");
        String word = time ? "Int32Rep" : "Word64Rep";
        return Map.of("schema", 1L, "target", Map.of("kind", "static", "symbol", symbol, "unit", unit, "isFunction", true),
                "convention", "capi", "safety", "unsafe", "arity", zero ? 1L : 3L, "suppliedArity", zero ? 1L : 3L,
                "argumentReps", zero ? List.of(scalar(null, false)) : List.of(scalar(word, false), scalar("AddrRep", false), scalar(null, false)),
                "resultRep", tuple(zero ? word : "Int32Rep"));
    }
    private static String callKind(Map<?,?> call, String unit, String symbol, boolean time) {
        if (time) {
            String kind = timeSymbols(unit).get(symbol);
            return kind != null && call.equals(expected(unit, symbol, true, kind)) ? kind : null;
        }
        if (call.equals(expected(unit, symbol, false, "clock-id"))) return "clock-id";
        if (call.equals(expected(unit, symbol, false, "clock-buffer"))) return "clock-buffer";
        return null;
    }
    private static Map<String,String> timeSymbols(String unit) {
        String owner = unit.replace("-", "zm").replace(".", "zi");
        var result = new LinkedHashMap<String,String>();
        String prefix = "ZC" + owner + "ZCDataziTimeziClockziInternalziCTimespecZC";
        result.put("ghczuwrapperZC0" + prefix + "HSzuCLOCKzuREALTIME", "time-clock-id");
        result.put("ghczuwrapperZC1" + prefix + "clockzugetres", "time-clock-resolution");
        result.put("ghczuwrapperZC2" + prefix + "clockzugettime", "time-clock-time");
        return result;
    }
    public static ForeignBitcode linked(Map<?,?> module) { return linked(module, true); }
    public static ForeignBitcode linked(Map<?,?> module, boolean completeBindings) {
        Object raw = module.get("foreignLink");
        if (raw == null) return null;
        var link = record(raw, "Invalid foreign bitcode link");
        boolean time = Objects.equals(link.get("module"), "Data.Time.Clock.Internal.CTimespec");
        var keys = new HashSet<>(List.of("schema", "format", "unit", "module", "target", "symbols", "abi", "sourceSha256", "bitcodeSha256", "bitcodeHex"));
        if (time) keys.add("headerHashes");
        require(link.keySet().equals(keys) && version(link.get("schema"), time ? 3 : 2) && Objects.equals(link.get("format"), "llvm-bitcode") &&
                Objects.equals(link.get("unit"), module.get("unit")) && Objects.equals(link.get("module"), module.get("module")), "Invalid foreign bitcode owner/schema");
        String unit = text(link.get("unit"), "Invalid foreign bitcode unit"), name = text(link.get("module"), "Invalid foreign bitcode module");
        require(time ? unit.matches("time-1\\.15-(?:inplace|[0-9a-f]+)") && System.getProperty("os.name").equals("Linux") :
                name.equals("System.CPUTime.Posix.ClockGetTime") && unit.startsWith("base-"), "Foreign module has no complete execution ABI: " + unit + ":" + name);
        if (time) {
            var headers = list(link.get("headerHashes"), "Missing selected time headers");
            var names = new ArrayList<Object>();
            for (Object header : headers) names.add(header instanceof Map<?,?> fields ? fields.get("name") : null);
            boolean valid = headers.size() == 3 && names.equals(List.of("HsFFI.h", "HsTime.h", "HsTimeConfig.h"));
            if (valid) for (Object header : headers) {
                if (!(header instanceof Map<?,?> fields) || !fields.keySet().equals(Set.of("name", "sha256")) || !sha(fields.get("sha256"))) { valid = false; break; }
            }
            require(valid, "Invalid selected time header provenance");
        }
        var foreign = record(module.get("foreign"), "Missing original foreign archive");
        var stubs = record(foreign.get("stubs"), "Missing original C stubs");
        require(Objects.equals(stubs.get("header"), "") && Objects.equals(stubs.get("initializers"), List.of()) &&
                Objects.equals(stubs.get("finalizers"), List.of()) && Objects.equals(foreign.get("files"), List.of()),
                "Foreign callbacks, initializers, finalizers, and additional files are not linked");
        String source = text(stubs.get("source"), "Missing original C source");
        Object sourceHash = link.get("sourceSha256");
        require(!source.isEmpty() && sha(sourceHash) && Objects.equals(digest(source.getBytes(StandardCharsets.UTF_8)), sourceHash), "Foreign C source hash mismatch");
        String encoded = text(link.get("bitcodeHex"), "Missing foreign bitcode");
        byte[] bytes;
        try { bytes = HexFormat.of().parseHex(encoded); }
        catch (IllegalArgumentException failure) { throw new IllegalArgumentException("Invalid foreign bitcode encoding"); }
        Object hash = link.get("bitcodeSha256");
        require(!encoded.isEmpty() && encoded.matches("[0-9a-f]+") && sha(hash) && Objects.equals(digest(bytes), hash), "Foreign bitcode hash mismatch");
        String target = text(link.get("target"), "Missing foreign target");
        String os = System.getProperty("os.name"), arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String system = os.startsWith("Mac") ? "darwin" : os.startsWith("Linux") ? "linux-gnu" : "unsupported";
        arch = switch (arch) { case "amd64" -> "x86_64"; case "arm64" -> "aarch64"; default -> arch; };
        boolean hostSystem = system.equals("darwin") ? target.contains("-darwin") : target.endsWith("-linux-gnu");
        require(!system.equals("unsupported") && hostSystem && (target.startsWith(arch + "-") || arch.equals("aarch64") && target.startsWith("arm64-")),
                "Foreign bitcode target differs from this runtime");
        var symbols = new ArrayList<String>();
        for (Object symbol : list(link.get("symbols"), "Missing foreign symbol inventory")) symbols.add(text(symbol, "Invalid foreign symbol"));
        boolean validSymbols = symbols.size() == 3 && new HashSet<>(symbols).size() == symbols.size();
        if (validSymbols) for (String symbol : symbols) if (symbol.isEmpty()) { validSymbols = false; break; }
        require(validSymbols, "Invalid foreign symbol inventory");
        var abiEntries = list(link.get("abi"), "Missing CAPI ABI inventory");
        var abi = new LinkedHashMap<String,String>();
        for (Object entry : abiEntries) {
            var fields = record(entry, "Invalid CAPI ABI entry");
            require(fields.keySet().equals(Set.of("symbol", "kind")), "Invalid CAPI ABI fields");
            String symbol = text(fields.get("symbol"), "Invalid CAPI ABI symbol"), kind = text(fields.get("kind"), "Invalid CAPI ABI kind");
            require(time ? Objects.equals(kind, timeSymbols(unit).get(symbol)) : kind.equals("clock-id") || kind.equals("clock-buffer"), "Invalid CAPI ABI kind");
            abi.put(symbol, kind);
        }
        boolean validAbi = abiEntries.size() == 3 && abi.keySet().equals(new HashSet<>(symbols));
        if (validAbi) {
            if (time) validAbi = abi.equals(timeSymbols(unit));
            else {
                long ids = 0;
                for (String kind : abi.values()) if ("clock-id".equals(kind)) ids++;
                validAbi = ids == 1;
                if (validAbi) {
                    long buffers = 0;
                    for (String kind : abi.values()) if ("clock-buffer".equals(kind)) buffers++;
                    validAbi = buffers == 2;
                }
            }
        }
        require(validAbi, "CAPI ABI inventory differs from original symbols");
        var result = new ForeignBitcode(unit, name, target, new LinkedHashSet<>(symbols), abi, bytes);
        validateCalls(module.get("bindings"), result, completeBindings);
        return result;
    }
    public static void validateCalls(Object bindings, ForeignBitcode link, boolean complete) {
        String unit = link.unit();
        boolean time = link.module().equals("Data.Time.Clock.Internal.CTimespec");
        var actual = new LinkedHashSet<String>();
        for (var descriptor : CoreCallInventory.mapCalls(bindings)) {
            if (time && !(descriptor.get("target") instanceof Map<?,?> fields && Objects.equals(fields.get("unit"), unit))) continue;
            var callTarget = record(descriptor.get("target"), "Invalid foreign call target");
            String symbol = text(callTarget.get("symbol"), "Invalid foreign call symbol");
            require(link.abi().containsKey(symbol) && Objects.equals(callKind(descriptor, unit, symbol, time), link.abi().get(symbol)),
                    "CAPI original call disagrees with linked symbol ABI: " + symbol);
            actual.add(symbol);
        }
        require(!complete || actual.equals(link.symbols()), "Linked CAPI symbols differ from original Core declarations");
    }
    public static void requireExecutableInput(Map<?,?> module) {
        if (module.containsKey("archiveBindings")) {
            var pending = record(module.get("archiveBindings"), "Invalid Core archive admission state");
            require(pending.isEmpty(), "Unresolved archive-only Core modules require entry reachability");
        }
        if (module.containsKey("schema") || module.containsKey("foreign")) requireExecutable(module);
    }
    public static void validateArchive(Map<?,?> module) { validateArchive(module, true); }
    public static void validateArchive(Map<?,?> module, boolean completeBindings) {
        if (version(module.get("schema"), 1)) {
            require(!module.containsKey("foreign") && !module.containsKey("staticForeignImportStubs"), "Foreign artifacts require Core schema 2");
            PackageScalarLinks.read(module, true, completeBindings);
            return;
        }
        require(version(module.get("schema"), 2), "Unsupported Core schema: " + module.get("schema"));
        var artifacts = artifactRecord(module.get("foreign"), "schema", "execution", "stubs", "files");
        require(version(artifacts.get("schema"), 1) && Objects.equals(artifacts.get("execution"), "not-linked"), "Invalid Core foreign artifact schema/link state");
        boolean nonempty = false;
        if (artifacts.get("stubs") != null) {
            var stub = artifactRecord(artifacts.get("stubs"), "header", "source", "initializers", "finalizers");
            String header = text(stub.get("header"), "Invalid Core foreign artifact text"), source = text(stub.get("source"), "Invalid Core foreign artifact text");
            var initializers = labels(stub.get("initializers"), true);
            var finalizers = labels(stub.get("finalizers"), false);
            nonempty = !header.isEmpty() || !source.isEmpty() || !initializers.isEmpty() || !finalizers.isEmpty();
        }
        for (Object entry : list(artifacts.get("files"), "Invalid Core foreign artifact list")) {
            var file = artifactRecord(entry, "language", "source", "extension");
            require(!text(file.get("language"), "Invalid Core foreign artifact text").isEmpty(), "Missing foreign source language");
            text(file.get("source"), "Invalid Core foreign artifact text"); text(file.get("extension"), "Invalid Core foreign artifact text");
            nonempty = true;
        }
        require(nonempty, "Core schema 2 requires foreign artifacts");
        if (module.containsKey("foreignLink")) linked(module, completeBindings);
        if (PackageScalarLinks.read(module, true, completeBindings) == null) ManagedImportAdmission.read(module, completeBindings);
    }
    private static Map<?,?> artifactRecord(Object value, String... keys) {
        require(value instanceof Map<?,?> map && map.keySet().equals(Set.of(keys)), "Invalid Core foreign artifact record");
        return (Map<?,?>) value;
    }
    private static List<?> labels(Object value, boolean initializer) {
        var entries = list(value, "Invalid Core foreign artifact list");
        for (Object entry : entries) {
            var label = artifactRecord(entry, "isInitializer", "unit", "module", "name");
            require(Objects.equals(label.get("isInitializer"), initializer), "Foreign initializer/finalizer kind mismatch");
            for (String key : List.of("unit", "module", "name")) require(!text(label.get(key), "Invalid Core foreign artifact text").isEmpty(), "Missing foreign label " + key);
        }
        return entries;
    }
    public static boolean hasRegistrationObligations(Map<?,?> module) {
        if (!(module.get("foreign") instanceof Map<?,?> foreign)) return false;
        if (foreign.get("files") instanceof List<?> files && !files.isEmpty()) return true;
        if (!(foreign.get("stubs") instanceof Map<?,?> stubs)) return false;
        return stubs.get("initializers") instanceof List<?> initializers && !initializers.isEmpty() ||
                stubs.get("finalizers") instanceof List<?> finalizers && !finalizers.isEmpty();
    }
    public static void requireExecutable(Map<?,?> module) {
        validateArchive(module);
        require(!module.containsKey("packageNativeArchive"), "Archive-only package native obligations require strict entry reachability");
        require(version(module.get("schema"), 1) || linked(module) != null || PackageScalarLinks.read(module) != null || ManagedImportAdmission.read(module) != null,
                "Unsupported foreign code/registration for " + module.get("unit") + ":" + module.get("module") +
                ": Core schema 2 is archive-only; native stubs, initializers, finalizers and callbacks are not linked");
    }
    private static Map<?,?> record(Object value, String message) { if (!(value instanceof Map<?,?> result)) throw new IllegalArgumentException(message); return result; }
    private static List<?> list(Object value, String message) { if (!(value instanceof List<?> result)) throw new IllegalArgumentException(message); return result; }
    private static String text(Object value, String message) { if (!(value instanceof String result)) throw new IllegalArgumentException(message); return result; }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
