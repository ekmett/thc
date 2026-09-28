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

    private static synchronized Map<String, Object> manifest() throws Exception {
        if (manifest == null) {
            var value = (Map<String, Object>) Json.parse(Files.readString(new File(root, "build/foreign-exceptions/manifest.json").toPath()));
            for (String field : List.of("inputHashes", "artifactHashes")) {
                for (var entry : ((Map<String, String>) value.get(field)).entrySet()) {
                    var bytes = Files.readAllBytes(new File(root, entry.getKey()).toPath());
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
            var text = new StringBuilder();
            var layout = CorePackageManifest.appendModules(text, new File(root, (String) manifest().get("packageManifest")).getPath());
            var modules = (List<Map<String, Object>>) Json.parse("[" + text + "]");
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
        for (var path : stages.get(stage)) modules.add((Map<String, Object>) Json.parse(Files.readString(new File(root, path).toPath())));
        return modules;
    }
    public static Map<String, Object> source(String stage) throws Exception {
        var modules = new ArrayList<>(originals());
        modules.addAll(stageModules(stage));
        var merged = new LinkedHashMap<>(CoreModules.merge(modules));
        merged.put("targetLayout", target);
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
