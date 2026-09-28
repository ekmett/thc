// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import thc.Json;
import static org.junit.jupiter.api.Assertions.*;

/** Both suites reject stale source, exporter, auditor, native and Core evidence. */
@SuppressWarnings("unchecked")
final class SumEvidence {
    static void verifySumEvidence(File root) throws Exception {
        for (var directory : List.of("sum-layout", "sum-result")) {
            var manifest = (Map<String, Object>) Json.parse(Files.readString(new File(root, "build/" + directory + "/provenance.json").toPath()));
            var sources = (List<Map<String, String>>) manifest.get("sources");
            var artifacts = (List<Map<String, String>>) manifest.get("artifacts");
            var toolchain = (Map<String, Object>) manifest.get("toolchain");
            var items = new ArrayList<>(sources); items.addAll(artifacts);
            items.add((Map<String, String>) toolchain.get("ghc")); items.add((Map<String, String>) toolchain.get("ghcPkg"));
            for (var item : items) {
                var relative = new File(java.util.Objects.requireNonNull(item.get("path")));
                var path = relative.isAbsolute() ? relative : new File(root, relative.getPath());
                var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path.toPath())));
                assertEquals(item.get("sha256"), hash, "Stale sum evidence: " + path);
            }
            var paths = new ArrayList<String>(); for (var item : sources) paths.add(item.get("path"));
            assertTrue(paths.containsAll(List.of("scripts/audit-core.py", "scripts/core_sums.py", "scripts/core-capabilities.json",
                "src/main/resources/thc/scalar-primop-signatures.json")));
        }
        for (var stage : List.of("pre", "post")) {
            var audit = (Map<String, Object>) Json.parse(Files.readString(new File(root, "build/sum-result/" + stage + "-audit.json").toPath()));
            assertEquals(true, audit.get("accepted"), "Strict sum result audit: " + stage);
        }
    }
}
