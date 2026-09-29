// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreFormatTestSupport.*;

class CoreRequestTest {
    @TempDir Path directory;
    @Test void unverifiedLooseRequestsDoNotOpenOrHashTheirArtifacts() throws Exception {
        var paths = List.of(directory.resolve("not-opened λ.cbd").toString());
        var request = CoreModules.request(paths, "entry\"\\\nλ", false, true, "bytecode", false, false, null, null, false);
        var document = (Map<?,?>) Json.parse(request);
        assertEquals(map("entry", "entry\"\\\nλ", "instrument", false, "diagnosticUnsupported", true,
            "backend", "bytecode", "sourceNotesEnabled", false, "verifyArtifacts", false), without(document, "moduleFiles"));
        var artifacts = (List<?>) document.get("moduleFiles"); assertEquals(1, artifacts.size());
        var artifact = (Map<?,?>) artifacts.getFirst();
        assertEquals(paths.getFirst(), artifact.get("path")); assertEquals("", artifact.get("sha256"));
        assertFalse(((String) artifact.get("request")).isEmpty());
        assertFalse(((String) artifact.get("capability")).isEmpty());
        assertFalse(Files.exists(Path.of(paths.getFirst())));
        assertFalse(request.contains("\n"), "Control characters must be JSON-escaped");
        assertNotEquals(request, CoreModules.request(paths, "entry\"\\\nλ", false, true, "bytecode", false, false, null, null, false),
            "New unverified requests must not alias cached preparation of an earlier pathname snapshot");
        assertThrows(NoSuchFileException.class, () -> CoreModules.request(paths, "entry", false, false, "ast", false, false, null, false, true));
    }
    @Test void inlineCoreAndForgedArtifactCapabilitiesRejectBeforeOpening() {
        assertThrows(IllegalArgumentException.class, () -> CoreModules.unitDirectory(map("modules", List.of())));
        var input = document(CoreModules.request(List.of(directory.resolve("not-opened.cbd").toString()), "entry", false));
        var artifact = (Map<?,?>) ((List<?>) input.get("moduleFiles")).getFirst();
        for (var forged : List.of(with(artifact, "path", directory.resolve("other.cbd").toString()),
                with(artifact, "sha256", "a".repeat(64)), with(artifact, "request", "another"), with(artifact, "capability", "forged"))) {
            var invalid = with(input, "moduleFiles", List.of(forged));
            try (var sources = CoreModules.unitDirectory(invalid).open(false)) {
                assertThrows(IllegalArgumentException.class, () -> CoreModules.visitUnitConsumers(invalid, sources, ignored -> fail("Untrusted module admitted")));
                assertTrue(sources.compactCounters().isEmpty());
            }
        }
    }
    @Test void validationMatchesTheMaterializingReaderForNestedDocuments() {
        var leaves = List.of("null", "true", "false", "-0", "-9223372036854775808", "9223372036854775807",
            "1.25e-100", "\"λ😀\\uD800\\b\\f\\n\\r\\t\\/\\\\\\\"\"", "[]", "{}");
        var malformed = List.of("[1,]", "{\"x\":1,\"x\":2}", "\"\\uZZZZ\"", "1e999", "9223372036854775808");
        var inputs = new ArrayList<>(leaves); inputs.addAll(malformed);
        for (String leaf : inputs) for (int depth = 0; depth <= 5; depth++) {
            String document = leaf;
            for (int i = 0; i < depth; i++) document = "{\"node\":[null," + document + ",{\"next\":" + document + "}]}";
            document = "{\"payload\":" + document + "}";
            Object materialized = null;
            boolean parsed = false, validated = false;
            try { materialized = Json.parse(document); parsed = true; } catch (Throwable ignored) {}
            var destination = new StringBuilder("prefix");
            try { Json.appendObjectDocument(destination, document); validated = true; } catch (Throwable ignored) {}
            assertEquals(parsed, validated, document);
            if (parsed) assertEquals(materialized, Json.parse(destination.substring(6)));
            else assertEquals("prefix", destination.toString(), "Invalid document appended a partial payload");
        }
    }
    @Test void escapesLateUnicodeInStringsWhilePreservingLegacyNumericTokens() {
        var ascii = "a".repeat(1_000_000);
        var document = "{\"sourceCore\":\"" + ascii + " λ😀\uD800\\\"quoted\\\\text\",\"λ\":\"value\",\"number\":1٢}";
        var destination = new StringBuilder();
        Json.appendObjectDocument(destination, document);
        assertEquals(Json.parse(document), Json.parse(destination.toString()));
        assertFalse(destination.toString().contains("λ")); assertFalse(destination.toString().contains("😀"));
        assertTrue(destination.toString().contains("\\ud800")); assertTrue(destination.toString().contains("\"number\":1٢"));
    }
}
