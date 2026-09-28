// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.graalvm.polyglot.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreFormatTestSupport.*;

/** Native-produced sidecar fixture: no reference encoder or fallback in this route. */
@SuppressWarnings("unchecked")
class CoreJsonLoadTest {
    @TempDir Path directory;
    private Path fixture(String name) throws Exception {
        var path = directory.resolve(name);
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/core/" + name))) { Files.copy(input, path); }
        return path;
    }
    private String request(Path json, Path index, String backend, boolean async, boolean verify) {
        return CoreFormatTestSupport.request(List.of(json.toString()), "synthetic:LazyJson.entry", backend, false, async,
            Map.of(json.toString(), index.toString()), verify);
    }
    private Map<String, Object> statistics(Value value) { return document(value.getMember("diagnostics").asString()); }
    private long count(Value value, String key) { return ((Number) statistics(value).get(key)).longValue(); }
    private String digest(Path path) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
    @Test void requestHashesCompleteFilesAcrossBufferBoundariesWithoutParsingBodies() throws Exception {
        var json = directory.resolve("source.json"); var index = directory.resolve("source.idx");
        for (int size : new int[]{0, 1, 8191, 8192, 8193, 16384, 16387}) {
            // Request construction authenticates bytes only. Deliberately not
            // JSON/index documents: actual format validation belongs to admission.
            byte[] sourceBytes = new byte[size], indexBytes = new byte[size + 3];
            for (int i = 0; i < sourceBytes.length; i++) sourceBytes[i] = (byte) (i * 31 + 7);
            for (int i = 0; i < indexBytes.length; i++) indexBytes[i] = (byte) (i * 17 + 11);
            Files.write(json, sourceBytes); Files.write(index, indexBytes);
            var input = document(request(json, index, "bytecode", false, true));
            var descriptors = (List<Map<String, Object>>) input.get("indexedModuleFiles");
            assertEquals(1, descriptors.size()); var descriptor = descriptors.getFirst();
            assertEquals(digest(json), descriptor.get("sha256"), "source size " + size);
            assertEquals(digest(index), descriptor.get("sidecarSha256"), "index size " + (size + 3));
            assertEquals(Set.of("path", "sha256", "sidecar", "sidecarSha256", "capability"), descriptor.keySet());
            var ordinary = document(request(json, index, "bytecode", false, false));
            var trustedDescriptors = (List<Map<String, Object>>) ordinary.get("indexedModuleFiles");
            assertEquals(1, trustedDescriptors.size()); var trusted = trustedDescriptors.getFirst();
            assertEquals(false, ordinary.get("verifyArtifacts"));
            assertEquals("", trusted.get("sha256"), "default request does not read/hash source content");
            assertEquals("", trusted.get("sidecarSha256"), "default request does not read/hash index content");
        }
    }
    @Test void nativeSidecarLoadsEntryThenPreparesEachUntouchedCalleeOnce() throws Exception {
        var json = fixture("lazy-json-module.json"); var index = fixture("lazy-json-module.idx");
        assertEquals("0d9dacecb7b3e8b617e01f7eca52a5017aba4a922d994c79f89416229841b1b6", digest(json));
        assertEquals("942c653564c3f767c0877d80befc725940c1d437cc4a8a24af91bd4867cbb158", digest(index));
        for (String backend : List.of("ast", "bytecode")) for (boolean async : new boolean[]{false, true})
            try (var context = Main.executionContext(false)) {
                String serialized = request(json, index, backend, async, false);
                assertFalse(serialized.contains("unused body is deliberately"), "the host request must not embed Core bodies");
                var value = context.eval("thc", serialized);
                assertEquals("reject-at-binding-admission", statistics(value).get("unsupportedPolicy"));
                assertEquals(1L, count(value, "jsonBodyMaterializations")); assertEquals(1L, count(value, "loweredRootCount"));
                assertEquals(1L, count(value, "hostEntryRootCount")); assertEquals(1L, count(value, "initializedBindingCount"));
                assertEquals(4L, count(value, "jsonBindingHeaders"), "all binding IDs are still indexed eagerly");
                assertEquals(Files.size(json), count(value, "jsonSourceBytes")); assertEquals(Files.size(index), count(value, "jsonSidecarBytes"));
                assertEquals(Files.size(json), count(value, "jsonSourceFileBytesRead"));
                assertEquals(0L, count(value, "jsonSourceHashBytesScanned")); assertEquals(0L, count(value, "jsonStructuralBytesScanned"));
                assertEquals(0L, count(value, "jsonIndexSourceBytesScanned"));
                assertTrue(count(value, "jsonLinkingExpressionViews") > 0, "do not hide strict dependency traversal");
                assertTrue(count(value, "jsonLinkingScalarDecodes") > 0);
                assertTrue(count(value, "jsonDecodedSpanCount") > 0, "header/link scalars really are decoded before entry");
                System.out.println("indexed pre-entry " + backend + "/" + async + " " + Json.stringify(statistics(value)));
                assertEquals(1L, value.execute(0L).asLong()); assertEquals(2L, count(value, "jsonBodyMaterializations"));
                assertEquals(2L, count(value, "loweredRootCount")); assertEquals(2L, count(value, "initializedBindingCount"));
                System.out.println("indexed first-callee " + backend + "/" + async + " " + Json.stringify(statistics(value)));
                long decoded = count(value, "jsonDecodedSpanCount");
                assertEquals(1L, value.execute(0L).asLong()); assertEquals(decoded, count(value, "jsonDecodedSpanCount"));
                assertEquals(7L, value.execute(5L).asLong()); assertEquals(3L, count(value, "jsonBodyMaterializations"));
                assertEquals(3L, count(value, "loweredRootCount"));
            }
    }
    @Test void sourceSnapshotSurvivesReplacementButNewAdmissionChecksBothFiles() throws Exception {
        var json = fixture("lazy-json-module.json"); var index = fixture("lazy-json-module.idx");
        var serialized = request(json, index, "bytecode", false, true);
        try (var context = Main.executionContext(false)) {
            var value = context.eval("thc", serialized); Files.writeString(json, "{}");
            assertEquals(7L, value.execute(5L).asLong(), "cold callee uses pinned bytes, not the replaced pathname");
            assertThrows(IllegalArgumentException.class, () -> visit(document(serialized), ignored -> {}));
            Files.write(index, new byte[]{0});
            var error = assertThrows(IllegalArgumentException.class, () -> visit(document(serialized), ignored -> {}));
            assertTrue(Objects.toString(error.getMessage(), "").contains("sidecar changed"));
            assertEquals(1L, value.execute(0L).asLong());
        }
    }
    @Test void aGuestCannotForgeTheHostFilesystemCapabilityOrMixProtocols() throws Exception {
        var json = fixture("lazy-json-module.json"); var index = fixture("lazy-json-module.idx");
        var input = document(request(json, index, "ast", false, false));
        var descriptors = (List<Map<String, Object>>) input.get("indexedModuleFiles");
        assertEquals(1, descriptors.size()); var descriptor = descriptors.getFirst();
        for (var altered : List.of(with(descriptor, "capability", "forged"),
                with(descriptor, "path", directory.resolve("not-authorized.json").toString()),
                with(descriptor, "sidecar", directory.resolve("not-authorized.idx").toString()))) {
            var error = assertThrows(IllegalArgumentException.class, () -> visit(with(input, "indexedModuleFiles", List.of(altered)), ignored -> {}));
            assertTrue(Objects.toString(error.getMessage(), "").contains("capability"), error.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> visit(with(input, "modules", List.of()), ignored -> {}));
        assertThrows(IllegalArgumentException.class, () -> visit(with(input, "verifyArtifacts", true), ignored -> {}));
        var verified = document(request(json, index, "ast", false, true));
        assertThrows(IllegalArgumentException.class, () -> visit(with(verified, "verifyArtifacts", false), ignored -> {}));
    }
    @Test void sharedEngineOwnsSourceWhileProgramsAndClosingContextsRemainSeparate() throws Exception {
        var json = fixture("lazy-json-module.json"); var index = fixture("lazy-json-module.idx");
        for (String backend : List.of("ast", "bytecode"))
            try (var engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").build()) {
                var shared = Source.newBuilder("thc", request(json, index, backend, false, false), "indexed-" + backend).cached(true).build();
                try (var second = Context.newBuilder("thc").engine(engine).build()) {
                    Value other;
                    try (var first = Context.newBuilder("thc").engine(engine).build()) {
                        var value = first.eval(shared); other = second.eval(shared);
                        assertEquals(1L, count(value, "loweredRootCount")); assertEquals(1L, count(other, "loweredRootCount"));
                        assertEquals(1L, value.execute(0L).asLong()); assertEquals(2L, count(value, "loweredRootCount"));
                        assertEquals(1L, count(other, "loweredRootCount"), "source reuse must not share executable roots");
                    }
                    assertEquals(7L, other.execute(5L).asLong(), "closing one Context must not close an Engine-owned source");
                    assertEquals(2L, count(other, "loweredRootCount"));
                }
            }
    }
}
