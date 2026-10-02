// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.HashSet;
import thc.runtime.CoreForeignExceptionBridge;
import thc.runtime.TargetLayout;

/** Immutable compiler-produced support for public foreign-program controls.
 * No exception constructors, dictionaries, or proof records are synthesized. */
@SuppressWarnings("unchecked")
public final class ForeignExceptionFixtureSupport {
    private ForeignExceptionFixtureSupport() {}
    private static final File root = new File(System.getProperty("thc.projectRoot"));
    private static Map<String, Object> manifest;
    private static List<Map<String, Object>> originals;
    private static TargetLayout target;
    private static Map<String, Object> nativeRuntime;

    private static synchronized Map<String, Object> manifest() throws Exception {
        if (manifest == null) {
            var value = (Map<String, Object>) Json.parse(Files.readString(new File(root, "build/foreign-exceptions/manifest.json").toPath()));
            for (String field : List.of("inputHashes", "artifactHashes")) {
                for (var entry : ((Map<String, String>) value.get(field)).entrySet()) {
                    var bytes = Files.readAllBytes(root.toPath().resolve(entry.getKey()));
                    var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                    if (!entry.getValue().equals(actual)) throw new IllegalStateException("Stale foreign-exception fixture: " + entry.getKey());
                }
            }
            manifest = value;
        }
        return manifest;
    }
    private static synchronized List<Map<String, Object>> originals() throws Exception {
        if (originals == null) {
            var modules = new ArrayList<Map<String, Object>>();
            var layout = CoreCbdFixtures.visitModules(root.toPath().resolve((String) manifest().get("packageManifest")).toString(),
                (module, text) -> modules.add(module)).getTargetLayout();
            if (layout == null) throw new IllegalStateException("Required value was null.");
            target = layout;
            originals = modules;
        }
        return originals;
    }
    private static List<Map<String, Object>> stageModules(String stage) throws Exception {
        var stages = (Map<String, List<String>>) manifest().get("stages");
        if (!stages.containsKey(stage)) throw new NoSuchElementException("Key " + stage + " is missing in the map.");
        var modules = new ArrayList<Map<String, Object>>();
        for (var path : stages.get(stage)) modules.add(CoreCbdFixtures.read(root.toPath().resolve(path)));
        return modules;
    }
    public static Map<String, Object> source(String stage) throws Exception {
        var modules = new ArrayList<>(originals());
        modules.addAll(stageModules(stage));
        var merged = new LinkedHashMap<>(CoreModules.merge(modules));
        merged.put("targetLayout", target);
        return merged;
    }
    private static synchronized Map<String, Object> nativeRuntime() throws Exception {
        if (nativeRuntime == null) {
            var paths = new ArrayList<String>();
            var stages = (Map<String, List<String>>) manifest().get("stages");
            for (var path : stages.get("post")) paths.add(root.toPath().resolve(path).toString());
            paths.add("@" + root.toPath().resolve((String) manifest().get("packageManifest")));
            // This original audited foreign entry selects both genuine helpers
            // through the ordinary indexed linker. Keep no complete boot corpus.
            String entry = "main:ForeignExceptionAudit.caught";
            var request = (Map<String, Object>) Json.parse(CoreModules.request(paths, entry, false, false, "ast", false,
                false, null, null, true));
            var selected = CoreModules.selectedModules(request, entry);
            nativeRuntime = new LinkedHashMap<>(CoreModules.merge((List<Map<String, Object>>) selected.get("modules")));
            nativeRuntime.put("targetLayout", selected.get("targetLayout"));
        }
        return nativeRuntime;
    }
    /** Original closure fixtures keep their bodies. Owners contribute checked C
     * declarations, and only the reachable genuine exception helpers are added.
     * Link the native owners in the entered context, as Language.instantiate does. */
    public static Map<String, Object> nativeModules(List<Map<String, Object>> modules) throws Exception {
        var directory = CoreUnitDirectory.read((Map<?, ?>) Json.parse(Files.readString(
            root.toPath().resolve((String) manifest().get("packageManifest")))));
        return nativeModules(modules, directory, nativeRuntime());
    }
    /** Use the caller's acquired runtime instead of requiring the polyglot fixture corpus. */
    public static Map<String, Object> nativeModules(List<Map<String, Object>> modules, java.nio.file.Path packageManifest) throws Exception {
        var directory = CoreUnitDirectory.read((Map<?, ?>) Json.parse(Files.readString(packageManifest)));
        var owners = directory.getModules().stream().filter(module -> module.name().equals("THC.Internal.Exception") &&
            module.unit().equals(directory.getForeignExceptionBridgeUnit())).toList();
        if (owners.size() != 1) throw new IllegalArgumentException("Missing or ambiguous acquired THC.Exception runtime owner");
        Map<String, Object> proof;
        try (var sources = directory.open(true, false)) {
            var owner = owners.getFirst();
            sources.verifyModule(owner);
            proof = new CoreModuleAdmission(sources.metadata(owner), sources::binding).getBridge();
        }
        if (proof == null) throw new IllegalArgumentException("Acquired runtime has no genuine exception bridge");
        String entry = (String) proof.get("box");
        var request = (Map<String, Object>) Json.parse(CoreModules.request(List.of("@" + packageManifest),
            entry, false, false, "ast", false, true, (String) proof.get("project"), null, true));
        var selected = CoreModules.selectedModules(request, entry);
        var runtime = new LinkedHashMap<>(CoreModules.merge((List<Map<String, Object>>) selected.get("modules")));
        runtime.put("foreignExceptionBridgeUnit", directory.getForeignExceptionBridgeUnit());
        runtime.put("targetLayout", directory.getTargetLayout().document());
        return nativeModules(modules, directory, runtime);
    }
    private static Map<String, Object> nativeModules(List<Map<String, Object>> modules,
            CoreUnitDirectory directory, Map<String, Object> runtime) throws Exception {
        var available = new HashSet<String>();
        for (var original : directory.getModules()) available.add(original.unit() + ":" + original.name());
        var merger = new CoreModules.Merger(available);
        for (var original : directory.getModules()) if (original.packageScalarDeclarations()) {
            // Verification checks the complete original artifact. Close each
            // reader before the next owner; header admission uses the same
            // incomplete-body mode as the production indexed linker.
            try (var sources = directory.open(true, false)) {
                var admission = PackageScalarLinks.read(sources.metadata(original), true, false);
                if (admission != null) merger.addPackageProvenance(admission);
            }
        }
        var proof = CoreForeignExceptionBridge.select(runtime);
        var helpers = new LinkedHashMap<>(CoreModules.reachable(runtime,
            List.of((String) proof.get("box"), (String) proof.get("project")), true));
        var supplied = new HashSet<Object>();
        for (var module : modules) for (var binding : (List<Map<String, Object>>) module.get("bindings"))
            supplied.add(binding.get("id"));
        helpers.put("bindings", ((List<Map<String, Object>>) helpers.get("bindings")).stream()
            .filter(binding -> !supplied.contains(binding.get("id"))).toList());
        merger.add(helpers);
        modules.forEach(merger::add);
        var merged = new LinkedHashMap<>(merger.finish());
        merged.put("foreignExceptionBridges", runtime.get("foreignExceptionBridges"));
        merged.put("foreignExceptionBridgeUnit", runtime.get("foreignExceptionBridgeUnit"));
        merged.put("selectedForeignExceptionBridge", proof);
        merged.put("targetLayout", TargetLayout.fromDocument(runtime.get("targetLayout")));
        for (var module : modules) if (module.containsKey("instrument")) merged.put("instrument", module.get("instrument"));
        // Direct fixture consumers already carry their checked native ABI links.
        // Core module assembly does not consume this internal program field.
        var links = new LinkedHashMap<String, PackageScalarLink>();
        for (var link : (List<PackageScalarLink>) merged.get("packageScalarLinks")) links.put(link.getUnit(), link);
        for (var module : modules) if (module.get("packageScalarLinks") instanceof List<?> suppliedLinks) {
            for (var value : suppliedLinks) {
                var link = (PackageScalarLink) value;
                for (var existing : links.values()) if (!existing.getUnit().equals(link.getUnit())
                        && existing.getComponentSha256().equals(link.getComponentSha256()))
                    throw new IllegalArgumentException("Package C entry namespace belongs to another unit: " + link.getComponentSha256());
                var previous = links.putIfAbsent(link.getUnit(), link);
                if (previous != null && !previous.same(link)) throw new IllegalArgumentException("Conflicting package C component: " + link.getUnit());
            }
        }
        merged.put("packageScalarLinks", List.copyOf(links.values()));
        for (var link : (List<PackageScalarLink>) merged.get("packageScalarLinks"))
            Language.currentState().getPackageCbits().link(link);
        return merged;
    }
    /** Supplied protocol remains synthetic; exception support is genuine validated GHC output. */
    public static Map<String, Object> link(Map<String, ?> module, String entry) throws Exception {
        var support = new ArrayList<Map<String, Object>>();
        for (var item : stageModules("post")) if (!"ForeignExceptionAudit".equals(item.get("module"))) support.add(item);
        var protocol = new LinkedHashMap<String, Object>();
        protocol.put("schema", 1L); protocol.put("ghc", "9.14.1"); protocol.put("constructors", List.of());
        protocol.putAll(module);
        var modules = new ArrayList<>(originals());
        modules.addAll(support); modules.add(protocol);
        var merged = new LinkedHashMap<>(CoreModules.merge(modules));
        merged.put("targetLayout", target);
        for (String key : List.of("foreignLinks", "packageScalarLinks")) if (module.get(key) != null) {
            var links = new ArrayList<Object>();
            if (merged.get(key) instanceof List<?> existing) links.addAll(existing);
            links.addAll((List<?>) module.get(key)); merged.put(key, links);
        }
        if (module.containsKey("instrument")) merged.put("instrument", module.get("instrument"));
        return CoreModules.reachable(merged, entry, true);
    }
}
