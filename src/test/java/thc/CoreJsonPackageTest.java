// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreFormatTestSupport.*;

@SuppressWarnings("unchecked")
class CoreJsonPackageTest {
    @TempDir Path directory;
    private final String boundary = "optimized-Core-after-Tidy-before-CorePrep";
    private byte[] resource(String name) throws Exception {
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/core/" + name))) { return input.readAllBytes(); }
    }
    private String digest(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private Map<String, Object> module() throws Exception {
        return map("name", "LazyJson", "path", "LazyJson.json", "boundary", boundary, "sha256", digest(resource("lazy-json-package.json")));
    }
    private Path manifest() throws Exception { return manifest(module(), null); }
    private Path manifest(Map<String, Object> module, List<String> order) throws Exception {
        byte[] json = resource("lazy-json-package.json");
        var unit = map("id", "synthetic", "depends", List.of(), "modules", List.of(module));
        if (order == null) {
            Files.write(directory.resolve("LazyJson.json"), json);
        } else {
            var index = Json.stringify(map("format", "thc-core-bundle", "schema", 1, "unit", "synthetic",
                "buildKey", "0".repeat(64), "exportKey", "1".repeat(64), "modules", List.of(module))).getBytes(StandardCharsets.UTF_8);
            var members = Map.of("manifest.json", index, "LazyJson.json", json);
            var out = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(out)) {
                for (String name : order) {
                    zip.putNextEntry(new ZipEntry(name)); zip.write(members.getOrDefault(name, new byte[]{1})); zip.closeEntry();
                }
            }
            var bytes = out.toByteArray(); var path = directory.resolve("bundle.zip"); Files.write(path, bytes);
            unit.put("bundle", map("path", path.toString(), "sha256", digest(bytes)));
        }
        var path = directory.resolve("packages.json");
        Files.writeString(path, Json.stringify(map("format", "thc-core-packages", "schema", 1, "ghc", "9.14.1", "units", List.of(unit))));
        return path;
    }
    private String request(Path path, String backend, boolean verify) {
        return CoreFormatTestSupport.request(List.of("@" + path), "synthetic:LazyJson.entry", backend, false, null, true, verify);
    }
    private String request(Path path, boolean verify) { return request(path, "bytecode", verify); }
    private long count(Value value, String name) { return ((Number) document(value.getMember("diagnostics").asString()).get(name)).longValue(); }
    private void visit(String request) { CoreFormatTestSupport.visit(document(request), ignored -> {}); }
    private List<List<String>> orders() {
        return Arrays.asList(null, List.of("manifest.json", "LazyJson.json"), List.of("LazyJson.json", "manifest.json"));
    }
    @Test void jsonWorksInLooseOrderedAndReorderedPackagesWithoutEagerBodyLowering() throws Exception {
        for (var order : orders()) for (String backend : List.of("ast", "bytecode")) {
            var path = manifest(module(), order); var serialized = request(path, backend, false);
            assertFalse(serialized.contains("unused body is deliberately")); assertFalse(document(serialized).containsKey("modules"));
            try (var context = Main.executionContext(false)) {
                var value = context.eval("thc", serialized);
                assertEquals(1L, count(value, "jsonBodyMaterializations")); assertEquals(1L, count(value, "loweredRootCount"));
                assertEquals(4L, count(value, "jsonBindingHeaders")); assertEquals(0L, count(value, "jsonSourceHashBytesScanned"));
                assertEquals(resource("lazy-json-package.json").length, count(value, "jsonStructuralBytesScanned")); assertEquals(2L * resource("lazy-json-package.json").length, count(value, "jsonIndexSourceBytesScanned"));
                assertEquals(1L, value.execute(0L).asLong()); assertEquals(2L, count(value, "jsonBodyMaterializations"));
                assertEquals(2L, count(value, "loweredRootCount")); assertEquals(7L, value.execute(5L).asLong());
                assertEquals(3L, count(value, "jsonBodyMaterializations"));
            }
        }
    }
    @Test void requestCreationReadsOnlyDirectoryAndReplayChecksManifestAndArtifacts() throws Exception {
        var path = manifest(); Files.delete(directory.resolve("LazyJson.json"));
        var serialized = request(path, true); // Module JSON is not opened here.
        assertThrows(NoSuchFileException.class, () -> visit(serialized));
        manifest(); Files.writeString(directory.resolve("LazyJson.json"), "{}");
        assertTrue(Objects.toString(assertThrows(IllegalArgumentException.class, () -> visit(serialized)).getMessage(), "").contains("artifact hash mismatch"));
        manifest(); Files.writeString(path, Files.readString(path) + " ");
        assertTrue(Objects.toString(assertThrows(IllegalArgumentException.class, () -> visit(serialized)).getMessage(), "").contains("manifest changed"));
    }
    @Test void normalLoadingTrustsDeclaredDigestsButVerificationRejectsMismatch() throws Exception {
        var wrong = with(module(), "sha256", "0".repeat(64));
        for (var order : orders()) {
            var path = manifest(wrong, order);
            if (order != null) {
                var doc = document(Files.readString(path)); var units = (List<Map<String, Object>>) doc.get("units");
                assertEquals(1, units.size()); var unit = units.getFirst(); var bundle = (Map<?, ?>) unit.get("bundle");
                Files.writeString(path, Json.stringify(with(doc, "units", List.of(with(unit, "bundle", with(bundle, "sha256", "0".repeat(64)))))));
            }
            for (String backend : List.of("ast", "bytecode")) try (var context = Main.executionContext(false)) {
                var serialized = request(path, backend, false); Files.writeString(path, Files.readString(path) + " ");
                var entry = context.eval("thc", serialized);
                assertEquals(7L, entry.execute(5L).asLong()); assertEquals(0L, count(entry, "jsonSourceHashBytesScanned"));
                assertEquals(resource("lazy-json-package.json").length, count(entry, "jsonStructuralBytesScanned")); assertEquals(2L * resource("lazy-json-package.json").length, count(entry, "jsonIndexSourceBytesScanned"));
            }
            assertThrows(IllegalArgumentException.class, () -> visit(request(path, true)));
        }
    }
    @Test void bothArchiveOrdersRequireExactInventoryAndSourceIdentity() throws Exception {
        var valid = List.of("manifest.json", "LazyJson.json");
        for (var order : List.of(valid, valid.reversed())) {
            var wrong = with(module(), "sha256", "0".repeat(64));
            assertThrows(IllegalArgumentException.class, () -> visit(request(manifest(wrong, order), true)));
        }
        var extra = new ArrayList<>(valid); extra.add("extra.idx");
        var reversedExtra = new ArrayList<>(valid.reversed()); reversedExtra.add("extra.idx");
        for (var order : List.of(valid.subList(0, valid.size() - 1), extra, reversedExtra))
            assertThrows(IllegalArgumentException.class, () -> visit(request(manifest(module(), order), false)));
    }
    @Test void retiredIndexReferencesAreRejectedAndModuleOwnersRemainExact() {
        for (var index : Arrays.asList(null, map("path", "../outside.idx", "sha256", "0".repeat(64)),
                map("path", "LazyJson.json", "sha256", "0".repeat(64)), map("path", "manifest.json", "sha256", "0".repeat(64)),
                map("path", "LazyJson.idx", "sha256", "0".repeat(64)))) {
            assertThrows(RuntimeException.class, () -> visit(request(manifest(with(module(), "index", index), null), false)));
        }
        assertTrue(Objects.toString(assertThrows(IllegalArgumentException.class,
            () -> visit(request(manifest(with(module(), "name", "Other"), null), false))).getMessage(), "").contains("unit/module/boundary mismatch"));
    }
}
