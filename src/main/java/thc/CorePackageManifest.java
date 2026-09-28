// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.*;
import java.util.zip.*;
import thc.runtime.TargetLayout;

/** Exact post-Tidy Core artifacts from separate Cabal-planned GHC units. */
public final class CorePackageManifest {
    public static final CorePackageManifest INSTANCE = new CorePackageManifest();
    private CorePackageManifest() {}
    private static final String BOUNDARY = "optimized-Core-after-Tidy-before-CorePrep";
    private record BundleContents(Map<String,byte[]> entries, TargetLayout targetLayout) {}
    private record VerifiedBundle(byte[] bytes, List<String> centralNames) {}
    private record OrderedVisit(TargetLayout targetLayout) {}
    public record VisitResult(TargetLayout targetLayout, String manifestSha256, String manifestPath, String foreignExceptionBridgeUnit) {
        public TargetLayout getTargetLayout() { return targetLayout; } public String getManifestSha256() { return manifestSha256; }
        public String getManifestPath() { return manifestPath; } public String getForeignExceptionBridgeUnit() { return foreignExceptionBridgeUnit; }
    }
    private static List<String> inventory(String id, List<?> modules) {
        var paths = new ArrayList<String>();
        for (Object item : modules) {
            var module = record(item, "Invalid module record in GHC unit " + id);
            String name = text(module.get("path"), "Missing module path in " + id);
            var names = new ArrayList<String>(); names.add(name);
            boolean valid = true;
            for (String path : names) if (!safeRelative(path) || Set.of("manifest.json", "inplace-manifest.json").contains(path)) { valid = false; break; }
            require(valid, "Invalid module path in " + id + ": " + names);
            paths.addAll(names);
        }
        return paths;
    }
    private static TargetLayout bundleLayout(String id, List<?> modules, List<String> centralNames, List<String> entryNames,
            byte[] indexBytes, byte[] inputBytes, boolean verifyArtifacts) {
        var index = record(Json.parse(new String(indexBytes, StandardCharsets.UTF_8)), "Invalid ZIP manifest in " + id);
        require(Objects.equals(index.get("format"), "thc-core-bundle") && Objects.equals(index.get("schema"), 1L) &&
                Objects.equals(index.get("unit"), id) && hash(index.get("buildKey")) && hash(index.get("exportKey")) && Objects.equals(index.get("modules"), modules),
                "ZIP manifest does not match package unit " + id);
        Object buildInputs = index.get("buildInputs");
        Map<?,?> inputRecord = null;
        if (index.containsKey("buildInputs")) {
            var reference = record(buildInputs, "Invalid build inputs in ZIP manifest for " + id);
            require(reference.keySet().equals(Set.of("path", "sha256")) && Objects.equals(reference.get("path"), "inplace-manifest.json") && hash(reference.get("sha256")),
                    "Invalid build inputs reference in ZIP manifest for " + id);
            if (inputBytes == null) throw error("Missing build inputs in ZIP bundle for " + id);
            Object parsed = Json.parse(new String(inputBytes, StandardCharsets.UTF_8));
            inputRecord = parsed instanceof Map<?,?> fields ? fields : null;
            require((!verifyArtifacts || Objects.equals(digest(inputBytes), reference.get("sha256"))) && inputRecord != null &&
                    Objects.equals(inputRecord.get("format"), "thc-core-build-inputs") && Objects.equals(inputRecord.get("schema"), 1L) &&
                    Objects.equals(inputRecord.get("unit"), id) && Objects.equals(inputRecord.get("buildKey"), index.get("buildKey")) &&
                    Objects.equals(inputRecord.get("exportKey"), index.get("exportKey")), "Invalid build inputs record in ZIP bundle for " + id);
        }
        var paths = inventory(id, modules);
        var expectedEntries = new LinkedHashSet<>(paths); expectedEntries.add("manifest.json");
        if (buildInputs != null) expectedEntries.add("inplace-manifest.json");
        require(new HashSet<>(paths).size() == paths.size() && new HashSet<>(entryNames).equals(expectedEntries) && entryNames.size() == expectedEntries.size(),
                "ZIP entries differ from declared modules in " + id);
        require(centralNames.equals(entryNames), "ZIP central directory differs from entries in " + id);
        require(index.get("targetLayout") == null || inputRecord != null, "Wired target layout lacks hashed build inputs in " + id);
        return inputRecord == null ? null : TargetLayout.fromReceipts(index, inputRecord);
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException failure) { return rethrow(failure); }
    }
    private static boolean safeRelative(String path) {
        if (path.isEmpty() || path.startsWith("/") || path.matches("^[A-Za-z]:.*") || path.indexOf('\\') >= 0) return false;
        for (int i = 0; i < path.length(); i++) if (path.charAt(i) < 32) return false;
        for (String part : path.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        return true;
    }
    private static boolean blank(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) return false;
        }
        return true;
    }
    private static byte[] artifact(Path root, String relative) throws IOException {
        var raw = Path.of(relative);
        boolean parent = false;
        for (Path component : raw) if (component.toString().equals("..")) parent = true;
        require(!relative.isEmpty() && !raw.isAbsolute() && !parent, "Package module path must stay inside manifest root: " + relative);
        var file = root.resolve(raw).toRealPath();
        require(file.startsWith(root), "Package module path escapes manifest root: " + relative);
        return Files.readAllBytes(file);
    }
    private static VerifiedBundle verifiedBundle(String id, Map<?,?> record, boolean verifyArtifacts) throws IOException {
        String location = text(record.get("path"), "Missing ZIP bundle path for " + id);
        String expected = text(record.get("sha256"), "Missing ZIP bundle SHA-256 for " + id);
        require(record.keySet().equals(Set.of("path", "sha256")) && hash(expected), "Invalid ZIP bundle reference for " + id);
        var path = Path.of(location);
        require(path.isAbsolute(), "ZIP bundle path must be absolute for " + id + ": " + location);
        var file = path.toRealPath();
        byte[] bytes = Files.readAllBytes(file);
        if (verifyArtifacts) require(digest(bytes).equals(expected), "Core ZIP bundle hash mismatch: " + id + " at " + location);
        List<String> centralNames;
        try (var archive = new ZipFile(file.toFile())) {
            var names = new ArrayList<String>();
            var entries = archive.entries();
            while (entries.hasMoreElements()) names.add(entries.nextElement().getName());
            centralNames = Collections.unmodifiableList(names);
        }
        catch (IOException failure) { throw new IllegalArgumentException("Invalid ZIP bundle for " + id + " at " + location, failure); }
        return new VerifiedBundle(bytes, centralNames);
    }
    private static BundleContents bundle(String id, VerifiedBundle verified, List<?> modules, boolean verifyArtifacts) {
        var entries = new LinkedHashMap<String,byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(verified.bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                require(!entry.isDirectory() && safeRelative(entry.getName()), "Invalid ZIP entry in " + id + ": " + entry.getName());
                require(!entries.containsKey(entry.getName()), "Duplicate ZIP entry in " + id + ": " + entry.getName());
                entries.put(entry.getName(), zip.readAllBytes()); zip.closeEntry();
            }
        } catch (IOException failure) { throw new IllegalArgumentException("Invalid ZIP bundle for " + id, failure); }
        byte[] manifest = entries.get("manifest.json");
        if (manifest == null) throw new NoSuchElementException("Key manifest.json is missing in the map.");
        return new BundleContents(entries, bundleLayout(id, modules, verified.centralNames, new ArrayList<>(entries.keySet()), manifest, entries.get("inplace-manifest.json"), verifyArtifacts));
    }
    @FunctionalInterface private interface BundleVisitor { void accept(Object item, byte[] bytes); }
    private static byte[] member(ZipInputStream zip, String id, String expected) throws IOException {
        var entry = zip.getNextEntry();
        if (entry == null) throw error("Missing ZIP entry in " + id + ": " + expected);
        require(!entry.isDirectory() && entry.getName().equals(expected) && safeRelative(entry.getName()), "ZIP central directory differs from entries in " + id);
        byte[] bytes = zip.readAllBytes(); zip.closeEntry();
        return bytes;
    }
    private static OrderedVisit orderedBundle(String id, VerifiedBundle verified, List<?> modules, boolean verifyArtifacts, BundleVisitor accept) {
        var paths = inventory(id, modules);
        var withoutInputs = new ArrayList<String>(); withoutInputs.add("manifest.json"); withoutInputs.addAll(paths);
        var withInputs = new ArrayList<String>(); withInputs.add("manifest.json"); withInputs.add("inplace-manifest.json"); withInputs.addAll(paths);
        boolean hasInputs;
        if (verified.centralNames.equals(withoutInputs)) hasInputs = false;
        else if (verified.centralNames.equals(withInputs)) hasInputs = true;
        else return null;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(verified.bytes))) {
            byte[] index = member(zip, id, "manifest.json");
            byte[] inputs = hasInputs ? member(zip, id, "inplace-manifest.json") : null;
            var layout = bundleLayout(id, modules, verified.centralNames, verified.centralNames, index, inputs, verifyArtifacts);
            for (Object item : modules) {
                var module = (Map<?,?>) item;
                byte[] bytes = member(zip, id, (String) module.get("path"));
                accept.accept(item, bytes);
            }
            require(zip.getNextEntry() == null, "ZIP entries differ from declared modules in " + id);
            return new OrderedVisit(layout);
        } catch (IOException failure) { throw new IllegalArgumentException("Invalid ZIP bundle for " + id, failure); }
    }
    public static VisitResult indexedRequestIdentity(String manifestPath) { return indexedRequestIdentity(manifestPath, false, false); }
    public static VisitResult indexedRequestIdentity(String manifestPath, boolean forceDescriptor, boolean verifyArtifacts) {
        try {
            var path = Path.of(manifestPath).toRealPath();
            byte[] bytes = Files.readAllBytes(path);
            var document = record(Json.parse(new String(bytes, StandardCharsets.UTF_8)), "Invalid Core package manifest: " + path);
            require(Objects.equals(document.get("format"), "thc-core-packages") && Objects.equals(document.get("schema"), 1L) &&
                    Objects.equals(document.get("ghc"), "9.14.1"), "Core package manifest requires schema 1 / GHC 9.14.1: " + path);
            var units = list(document.get("units"), "Missing package units: " + path);
            boolean indexed = false;
            unitScan: for (Object raw : units) if (raw instanceof Map<?,?> unit) {
                if (unit.containsKey("json") || unit.containsKey("symbols")) { indexed = true; break; }
                if (unit.get("modules") instanceof List<?> modules) for (Object item : modules)
                    if (item instanceof Map<?,?> module && module.containsKey("compact")) { indexed = true; break unitScan; }
            }
            if (!indexed && !forceDescriptor) return null;
            Object bridge = document.get("foreignExceptionBridgeUnit");
            require(bridge == null || bridge instanceof String text && !blank(text), "Invalid foreign exception bridge unit");
            return new VisitResult(null, verifyArtifacts ? digest(bytes) : "", path.toString(), (String) bridge);
        } catch (IOException failure) { return rethrow(failure); }
    }
    public static VisitResult visitModules(String manifestPath, BiConsumer<Map<String,Object>,String> accept) { return visitModules(manifestPath, null, true, accept); }
    public static VisitResult visitModules(String manifestPath, String expectedSha256, BiConsumer<Map<String,Object>,String> accept) { return visitModules(manifestPath, expectedSha256, true, accept); }
    public static VisitResult visitModules(String manifestPath, String expectedSha256, boolean verifyArtifacts, BiConsumer<Map<String,Object>,String> accept) {
        return visitModules(manifestPath, expectedSha256, true, true, verifyArtifacts, (source, adapter) -> {}, accept);
    }
    public static VisitResult visitRuntimeModules(String manifestPath, String expectedSha256, boolean sourceNotesEnabled, boolean verifyArtifacts,
            BiConsumer<CoreJsonIndex,CoreJsonBindings> indexed, Consumer<Map<String,Object>> accept) {
        return visitModules(manifestPath, expectedSha256, sourceNotesEnabled, false, verifyArtifacts, indexed, (module, text) -> accept.accept(module));
    }
    private static VisitResult visitModules(String manifestPath, String expectedSha256, boolean sourceNotesEnabled,
            boolean materializeText, boolean verifyArtifacts, BiConsumer<CoreJsonIndex,CoreJsonBindings> indexed,
            BiConsumer<Map<String,Object>,String> accept) {
        try {
            var manifest = Path.of(manifestPath).toRealPath();
            var root = manifest.getParent();
            byte[] manifestBytes = Files.readAllBytes(manifest);
            String manifestSha256 = verifyArtifacts ? digest(manifestBytes) : "";
            require(!verifyArtifacts || expectedSha256 == null || hash(expectedSha256) && expectedSha256.equals(manifestSha256),
                    "Core package manifest changed after request: " + manifest);
            var document = record(Json.parse(new String(manifestBytes, StandardCharsets.UTF_8)), "Invalid Core package manifest: " + manifest);
            require(Objects.equals(document.get("format"), "thc-core-packages") && Objects.equals(document.get("schema"), 1L) &&
                    Objects.equals(document.get("ghc"), "9.14.1"), "Core package manifest requires schema 1 / GHC 9.14.1: " + manifest);
            Object bridgeUnit = document.get("foreignExceptionBridgeUnit");
            require(bridgeUnit == null || bridgeUnit instanceof String bridge && !blank(bridge), "Invalid foreign exception bridge unit");
            var units = list(document.get("units"), "Missing package units: " + manifest);
            var seenUnits = new HashSet<String>();
            record ModuleIdentity(String unit, String name) {}
            var seenModules = new HashSet<ModuleIdentity>();
            int[] count = {0};
            TargetLayout targetLayout = null;
            var adapter = new CoreJsonBindings(sourceNotesEnabled);
            var opened = new ArrayList<CoreJsonIndex>();
            try {
                for (Object record : units) {
                    var unit = record(record, "Invalid package unit: " + manifest);
                    String id = text(unit.get("id"), "Missing GHC unit ID: " + manifest);
                    require(!id.isEmpty() && seenUnits.add(id), "Invalid or duplicate GHC unit ID: " + id);
                    var depends = list(unit.get("depends"), "Missing dependencies for GHC unit " + id);
                    boolean validDepends = true;
                    for (Object item : depends) if (!(item instanceof String dependency) || dependency.isEmpty()) { validDepends = false; break; }
                    require(validDepends && new HashSet<>(depends).size() == depends.size(),
                            "Invalid dependencies for GHC unit " + id);
                    var modules = list(unit.get("modules"), "Missing module list for GHC unit " + id);
                    for (Object item : modules) require(!record(item, "Invalid module record in GHC unit " + id).containsKey("index"),
                            "JSON .idx sidecars are no longer supported; regenerate unit " + id);
                    List<String> paths = unit.containsKey("bundle") ? inventory(id, modules) : List.of();
                    require(new HashSet<>(paths).size() == paths.size(), "Duplicate module path in " + id);
                    class Consumer {
                        @SuppressWarnings("unchecked") void consume(Object item, byte[] bytes, boolean bundled) {
                            var module = record(item, "Invalid module record in GHC unit " + id);
                            String name = text(module.get("name"), "Missing module name in GHC unit " + id);
                            require(!name.isEmpty() && seenModules.add(new ModuleIdentity(id, name)), "Duplicate GHC module: " + id + ":" + name);
                            require(Objects.equals(module.get("boundary"), BOUNDARY), "Package module must be post-Tidy: " + id + ":" + name);
                            String relative = text(module.get("path"), "Missing path for " + id + ":" + name);
                            if (bundled) require(safeRelative(relative), "Invalid ZIP module path: " + relative);
                            String expected = text(module.get("sha256"), "Missing SHA-256 for " + id + ":" + name);
                            require(hash(expected), "Invalid SHA-256 for " + id + ":" + name);
                            if (verifyArtifacts) require(digest(bytes).equals(expected), "Core package artifact hash mismatch: " + id + ":" + name + " at " + relative);
                            CoreJsonIndex sourceIndex = materializeText ? null : CoreJsonIndex.fromBytes(bytes);
                            if (sourceIndex != null) opened.add(sourceIndex);
                            String text = sourceIndex == null || materializeText ? new String(bytes, StandardCharsets.UTF_8) : "";
                            Map<String,Object> source = sourceIndex != null && !materializeText ? adapter.module(sourceIndex.getRoot()) :
                                    (Map<String,Object>) record(Json.parse(text), "Invalid Core package artifact: " + relative);
                            CoreForeignArtifacts.INSTANCE.validateArchive(source, true);
                            require(Objects.equals(source.get("ghc"), "9.14.1") && Objects.equals(source.get("unit"), id) &&
                                    Objects.equals(source.get("module"), name) && Objects.equals(source.get("boundary"), BOUNDARY),
                                    "Core package unit/module/boundary mismatch: " + id + ":" + name + " at " + relative);
                            String prefix = id + ":" + name + ".", alias = "main::" + name + ".main";
                            var bindings = list(source.get("bindings"), "Missing bindings in " + id + ":" + name);
                            for (Object binding : bindings) {
                                Object key = binding instanceof Map<?,?> fields ? fields.get("id") : null;
                                require(key instanceof String identifier && (identifier.startsWith(prefix) || identifier.equals(alias)),
                                        "Foreign binding owner in " + id + ":" + name + " at " + relative + ": " + key);
                            }
                            count[0]++; accept.accept(source, text);
                            if (sourceIndex != null) indexed.accept(sourceIndex, adapter);
                        }
                    }
                    var consumer = new Consumer();
                    TargetLayout unitLayout;
                    if (unit.containsKey("bundle")) {
                        var bundleRecord = record(unit.get("bundle"), "Invalid ZIP bundle for " + id);
                        var verified = verifiedBundle(id, bundleRecord, verifyArtifacts);
                        var ordered = orderedBundle(id, verified, modules, verifyArtifacts, (item, bytes) -> consumer.consume(item, bytes, true));
                        if (ordered != null) unitLayout = ordered.targetLayout;
                        else {
                            var fallback = bundle(id, verified, modules, verifyArtifacts);
                            for (Object item : modules) {
                                var module = item instanceof Map<?,?> fields ? fields : null;
                                String relative = text(module == null ? null : module.get("path"), "Missing module path in " + id);
                                byte[] bytes = fallback.entries.get(relative);
                                if (bytes == null) throw error("Missing ZIP module in " + id + ": " + relative);
                                consumer.consume(item, bytes, true);
                            }
                            unitLayout = fallback.targetLayout;
                        }
                    } else {
                        for (Object item : modules) {
                            var module = item instanceof Map<?,?> fields ? fields : null;
                            String relative = text(module == null ? null : module.get("path"), "Missing module path in " + id);
                            consumer.consume(item, artifact(root, relative), false);
                        }
                        unitLayout = null;
                    }
                    if (unitLayout != null) {
                        require(targetLayout == null || targetLayout.equals(unitLayout), "Conflicting GHC target layouts across package bundles");
                        targetLayout = unitLayout;
                    }
                }
                require(count[0] != 0, "Core package manifest has no executable modules: " + manifest);
                return new VisitResult(targetLayout, manifestSha256, manifest.toString(), (String) bridgeUnit);
            } catch (Throwable failure) { opened.forEach(CoreJsonIndex::close); return rethrow(failure); }
        } catch (IOException failure) { return rethrow(failure); }
    }
    public static TargetLayout appendModules(StringBuilder destination, String manifestPath) {
        int[] count = {0};
        var result = visitModules(manifestPath, (module, text) -> {
            if (count[0]++ != 0) destination.append(',');
            Json.appendObjectDocument(destination, text);
        });
        return result.targetLayout;
    }
    private static boolean hash(Object value) { return value instanceof String text && text.matches("[0-9a-f]{64}"); }
    private static Map<?,?> record(Object value, String message) { if (!(value instanceof Map<?,?> result)) throw error(message); return result; }
    private static List<?> list(Object value, String message) { if (!(value instanceof List<?> result)) throw error(message); return result; }
    private static String text(Object value, String message) { if (!(value instanceof String result)) throw error(message); return result; }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static IllegalStateException error(String message) { return new IllegalStateException(message); }
    @SuppressWarnings("unchecked") private static <T,E extends Throwable> T rethrow(Throwable failure) throws E { throw (E) failure; }
}
