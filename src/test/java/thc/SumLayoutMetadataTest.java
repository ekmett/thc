// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreExecutionTestSupport.*;

/** Exact metadata admits retained host signatures and rejects unresolved representations. */
class SumLayoutMetadataTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private String hash(Path path) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
    @Test void genuineSumLayoutsEnforceResultCapabilityBoundariesOnBothBackends() throws Exception {
        var manifest = object(Json.parse(Files.readString(root.resolve("build/sum-layout/provenance.json"))));
        var items = new ArrayList<>(objects(manifest.get("sources"))); items.addAll(objects(manifest.get("artifacts")));
        for (var item : items) {
            Path path = root.resolve((String) item.get("path"));
            assertEquals(item.get("sha256"), hash(path), "Stale sum metadata evidence: " + path);
        }
        var checks = object(Json.parse(Files.readString(root.resolve("build/sum-layout/checks.json"))));
        var stages = objects(checks.get("coverage"));
        assertEquals(list("pre", "post"), stages.stream().map(stage -> stage.get("stage")).toList());
        assertEquals(169, ((Number) checks.get("nativeRows")).intValue());
        assertEquals(27, ((Number) checks.get("supportedSumEntries")).intValue());
        var manifestProof = object(checks.get("provenance"));
        assertEquals("build/sum-layout/provenance.json", manifestProof.get("path"));
        assertEquals(manifestProof.get("sha256"), hash(root.resolve((String) manifestProof.get("path"))));
        for (var stage : stages) {
            String name = (String) stage.get("stage");
            assertEquals(19, ((Number) stage.get("exactResultShapes")).intValue());
            assertEquals(32, ((List<?>) stage.get("audits")).size());
            var module = Json.parse(Files.readString(root.resolve("build/sum-layout/" + name + "-core/SumLayoutAudit.json")));
            for (String backend : list("ast", "bytecode")) try (var context = Main.executionContext(false)) {
                for (var entry : objects(stage.get("audits"))) {
                    String request = Json.stringify(map("modules", list(module), "entry", entry.get("entry"), "backend", backend, "diagnosticUnsupported", false));
                    if (Boolean.TRUE.equals(entry.get("accepted"))) {
                        var target = context.eval("thc", request);
                        for (String row : Files.readAllLines(root.resolve("build/sum-layout/oracle.tsv"))) {
                            var columns = row.split("\t", -1);
                            if (columns[0].equals(entry.get("entry"))) assertEquals(Long.parseLong(columns[2]),
                                target.execute(Long.parseLong(columns[1])).asLong(), name + "/" + backend + "/" + row);
                        }
                    } else {
                        var error = assertThrows(PolyglotException.class, () -> context.eval("thc", request));
                        assertTrue(Objects.toString(error.getMessage(), "").contains("Unsupported Core aggregate representation:"),
                            name + "/" + backend + "/" + entry.get("entry") + ": " + error.getMessage());
                    }
                }
            }
        }
    }
}
