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
    @Test void embedsCompleteValidatedModulesWithoutChangingTheirMeaning() throws Exception {
        var documents = List.of(
            " {\"schema\":1,\"bindings\":[{\"id\":\"cold\",\"expr\":[\"unsupported\",\"kept\"]}],\"sourceCore\":\"λ\\n\\uD83D\\uDE00\",\"extra\":null} ",
            "{\"nested\":{\"escaped\":\"\\u0078\",\"values\":[true,false,null,-9223372036854775808,9223372036854775807,1.25,1e100]},\"constructors\":[]}");
        var paths = new ArrayList<String>();
        for (int i = 0; i < documents.size(); i++) {
            var path = directory.resolve("module" + i + ".json");
            Files.writeString(path, documents.get(i)); paths.add(path.toString());
        }
        var request = CoreModules.request(paths, "entry\"\\\nλ", false, true, "bytecode", false, false, null, null, null, false);
        assertEquals(map("modules", documents.stream().map(Json::parse).toList(), "entry", "entry\"\\\nλ",
            "instrument", false, "diagnosticUnsupported", true, "backend", "bytecode", "sourceNotesEnabled", false, "verifyArtifacts", false), Json.parse(request));
        // Every field survives, including unreachable definitions and decoded text.
        assertTrue(request.chars().allMatch(it -> it < 128));
    }
    @Test void rejectsMalformedDocumentsBeforeEmbeddingAndCannotInjectAnotherModule() throws Exception {
        var malformed = List.of("", "[]", "null", "1", "true", "\"object\"", "{} {}", "{},{}",
            "{}],\"entry\":\"injected\",\"modules\":[{}", "{\"nested\":{\"x\":1,\"\\u0078\":2}}", "{\"x\":null,\"x\":1}",
            "{\"x\":\"\\uQQQQ\"}", "{\"x\":\"\\u12\"}", "{\"x\":\"\\q\"}", "{\"x\":\"raw\nline\"}",
            "{\"x\":9223372036854775808}", "{\"x\":1e999}", "{\"x\":01}", "{\"x\":1.}", "{\"x\":1e+}", "{\"x\":[1,]}", "{\"x\":1,}");
        var path = directory.resolve("invalid.json");
        for (String document : malformed) {
            Files.writeString(path, document);
            assertThrows(RuntimeException.class, () -> request(List.of(path.toString()), "entry",
                Main.defaultBackend(), true, null, null, false), document);
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
