// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreFormatTestSupport.*;

/** Synthetic Core with sidecars from the native producer, never a reference fallback. */
@SuppressWarnings("unchecked")
class CoreJsonConsumersTest {
    @TempDir Path directory;
    private byte[] resource(String name) throws Exception {
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/core/" + name))) { return input.readAllBytes(); }
    }
    private String digest(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private Path file(String name) throws Exception { var path = directory.resolve(name); Files.write(path, resource(name)); return path; }
    private Path support() throws Exception { return support(true); }
    private Path support(boolean indexed) throws Exception {
        var json = file("lazy-json-package.json"); var index = file("lazy-json-package.idx");
        var module = map("name", "LazyJson", "path", json.getFileName().toString(), "boundary", "optimized-Core-after-Tidy-before-CorePrep",
            "sha256", digest(Files.readAllBytes(json)));
        if (indexed) module.put("index", map("path", index.getFileName().toString(), "sha256", digest(Files.readAllBytes(index))));
        var path = directory.resolve("packages.json");
        Files.writeString(path, Json.stringify(map("format", "thc-core-packages", "schema", 1, "ghc", "9.14.1",
            "units", List.of(map("id", "synthetic", "depends", List.of(), "modules", List.of(module))))));
        return path;
    }
    private LinkedHashMap<String, String> consumers() throws Exception {
        var result = new LinkedHashMap<String, String>();
        result.put(file("indexed-consumer.json").toString(), file("indexed-consumer.idx").toString());
        result.put(file("indexed-interface-closure.json").toString(), file("indexed-interface-closure.idx").toString());
        return result;
    }
    private String request(List<String> paths, Map<String, String> pairs) { return request(paths, pairs, false); }
    private String request(List<String> paths, Map<String, String> pairs, boolean verify) {
        return CoreFormatTestSupport.request(paths, "main:Main.entry", Main.defaultBackend(), false, null, pairs, verify);
    }
    private List<Map<String, Object>> modules(String request) {
        var result = new ArrayList<Map<String, Object>>(); visit(document(request), result::add); return result;
    }
    private long count(Value value, String key) { return ((Number) document(value.getMember("diagnostics").asString()).get(key)).longValue(); }
    @Test void nativePairsMixWithLegacyOrIndexedSupportAndKeepInterfaceOwnershipAndOrder() throws Exception {
        var pairs = consumers();
        assertEquals("d2a141b1f35127040ee3358a3332444c4f92b866de3a03854e085b6b979e4b7a", digest(resource("indexed-consumer.json")));
        assertEquals("a5171ad8533443aa5844e8099faf399f1803ba25fba18a5a2ed0f5db6f2ffb50", digest(resource("indexed-consumer.idx")));
        assertEquals("c7fb8e4a188e3c62d505225f4769a96d7dac4375882d0d23c9e26ee6cc8dec78", digest(resource("indexed-interface-closure.json")));
        assertEquals("5ea63e376321c6532d7cf10289459fdfe13c4764859dea07beed42f02bc9afb5", digest(resource("indexed-interface-closure.idx")));
        for (boolean indexed : new boolean[]{false, true}) {
            var manifest = support(indexed); var keys = new ArrayList<>(pairs.keySet());
            for (var order : List.of(keys, keys.reversed())) {
                var paths = List.of(order.get(0), "@" + manifest, order.get(1));
                String serialized = request(paths, pairs); var input = document(serialized);
                assertFalse(input.containsKey("consumerModules")); assertFalse(input.containsKey("modules"));
                var selected = modules(serialized); var expected = new ArrayList<>(List.of("synthetic"));
                for (String path : order) expected.add(path.endsWith("indexed-consumer.json") ? "main" : "dependency-closure");
                assertEquals(expected, selected.stream().map(it -> it.get("unit")).toList());
                var closures = selected.stream().filter(it -> "dependency-closure".equals(it.get("unit"))).toList();
                assertEquals(1, closures.size()); var closure = closures.getFirst();
                assertEquals("actual-interface-unfoldings", closure.get("boundary"));
                var bindings = (List<Map<String, Object>>) closure.get("bindings");
                assertEquals(1, bindings.size()); assertEquals("dependency:Hidden.cold", bindings.getFirst().get("id"));
                assertEquals(List.of("synthetic:LazyJson"), closure.get("providedModules"));
                CoreModules.merge(selected); // exact provided owner and original binding IDs remain admissible
                var legacy = modules(CoreFormatTestSupport.request(paths, "main:Main.entry", Main.defaultBackend(), false, null, null, false));
                assertEquals(expected, legacy.stream().map(it -> it.get("unit")).toList(), "legacy relative consumer order");
                for (String backend : List.of("ast", "bytecode")) for (boolean async : new boolean[]{false, true})
                    try (var context = Main.executionContext(false)) {
                        var value = Main.loadEntry(context, paths, "main:Main.entry", true, backend, false, null, async, pairs, false);
                        assertEquals(1L, count(value, "jsonBodyMaterializations")); assertEquals(1L, count(value, "loweredRootCount"));
                        assertEquals(10L, value.execute(7L).asLong(), indexed + "/" + order + "/" + backend + "/" + async);
                        assertEquals(2L, count(value, "jsonBodyMaterializations")); assertEquals(2L, count(value, "loweredRootCount"));
                    }
            }
        }
    }
    @Test void requestRequiresExactLooseInputCoverageAndRejectsDuplicateProtocols() throws Exception {
        var pairs = consumers(); var manifest = support(); var paths = new ArrayList<>(pairs.keySet()); paths.add("@" + manifest);
        var missing = new LinkedHashMap<>(pairs); missing.remove(pairs.firstEntry().getKey());
        var unlisted = new LinkedHashMap<>(pairs); unlisted.put("unlisted.json", "unlisted.idx");
        var manifestPair = new LinkedHashMap<>(pairs); manifestPair.put("@" + manifest, pairs.firstEntry().getValue());
        for (var invalid : List.of(missing, unlisted, manifestPair)) assertThrows(IllegalArgumentException.class, () -> request(paths, invalid));
        var duplicateManifest = new ArrayList<>(paths); duplicateManifest.add("@" + manifest);
        assertThrows(IllegalArgumentException.class, () -> request(duplicateManifest, pairs));
        var duplicatePath = new ArrayList<>(paths); duplicatePath.add(paths.getFirst());
        assertThrows(IllegalArgumentException.class, () -> request(duplicatePath, pairs));
        String alias = directory.resolve(".").resolve("indexed-consumer.json").toString();
        var aliasedPaths = new ArrayList<>(paths); aliasedPaths.add(alias);
        var aliasedPairs = new LinkedHashMap<>(pairs); aliasedPairs.put(alias, pairs.firstEntry().getValue());
        assertThrows(IllegalArgumentException.class, () -> request(aliasedPaths, aliasedPairs));
        var input = document(request(paths, pairs));
        for (String field : List.of("modules", "consumerModules", "targetLayout"))
            assertThrows(IllegalArgumentException.class, () -> visit(with(input, field, List.of()), ignored -> {}));
        var files = new ArrayList<Object>((List<?>) input.get("indexedModuleFiles")); files.add(files.getFirst());
        assertThrows(IllegalArgumentException.class, () -> visit(with(input, "indexedModuleFiles", files), ignored -> {}));
    }
    @Test void mixedReplayChecksBothCapabilitiesManifestAndPairIdentityAndPinsAdmittedBytes() throws Exception {
        var pairs = consumers(); var manifest = support(); var paths = new ArrayList<>(pairs.keySet()); paths.add("@" + manifest);
        String serialized = request(paths, pairs, true); var input = document(serialized);
        for (var changed : List.of(with(input, "packageCapability", "forged"), with(input, "foreignExceptionBridgeUnit", "forged")))
            assertThrows(IllegalArgumentException.class, () -> visit(changed, ignored -> {}));
        var wrongPairs = new LinkedHashMap<>(pairs); wrongPairs.put(wrongPairs.firstEntry().getKey(), wrongPairs.lastEntry().getValue());
        assertThrows(IllegalArgumentException.class, () -> modules(request(paths, wrongPairs)));
        try (var context = Main.executionContext(false)) {
            var value = context.eval("thc", serialized); var closure = Path.of(pairs.lastEntry().getKey()); Files.writeString(closure, "{}");
            assertEquals(10L, value.execute(7L).asLong(), "cold helper uses the admitted source snapshot");
            assertThrows(IllegalArgumentException.class, () -> modules(serialized));
            consumers(); Files.write(Path.of(pairs.lastEntry().getValue()), new byte[]{0});
            assertThrows(IllegalArgumentException.class, () -> modules(serialized));
            consumers(); Files.writeString(manifest, Files.readString(manifest) + " ");
            assertThrows(IllegalArgumentException.class, () -> modules(serialized));
        }
    }
    @Test void consumersRemainSubjectToDuplicateDefinitionAndExactProvidedOwnerChecks() throws Exception {
        var pairs = consumers(); var paths = new ArrayList<>(pairs.keySet()); paths.add("@" + support());
        var selected = modules(request(paths, pairs)); var closure = selected.getLast();
        var duplicate = new ArrayList<>(selected); duplicate.add(closure);
        assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(duplicate));
        var wrongOwner = new ArrayList<>(selected.subList(0, selected.size() - 1));
        wrongOwner.add(with(closure, "providedModules", List.of("forged:LazyJson")));
        assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(wrongOwner));
        var wrongUnit = new ArrayList<>(selected.subList(0, selected.size() - 1)); wrongUnit.add(with(closure, "unit", "forged"));
        assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(wrongUnit));
    }
}
