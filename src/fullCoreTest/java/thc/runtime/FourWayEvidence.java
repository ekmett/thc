// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import thc.Json;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Shared provenance checks for the original four-way native/protocol/storage controls. */
final class FourWayEvidence {
    private FourWayEvidence() {}
    @SuppressWarnings("unchecked")
    static Map<String, Object> verify(File root) throws Exception {
        var manifest = (Map<String, Object>) Json.parse(Files.readString(new File(root, "build/fourway-aggregate/manifest.json").toPath(), StandardCharsets.UTF_8));
        assertEquals(true, manifest.get("strictAccepted"), "Preparation-only rejection evidence cannot authorize runtime checks");
        assertEquals(List.of("0", "1", "4294967295", "4294967296", "9223372036854775808", "18446744073709551615"),
            manifest.get("payloads"), "Unsigned payload provenance must retain exact decimal strings");
        for (String group : List.of("inputHashes", "artifactHashes")) for (var hash : ((Map<String, String>) manifest.get(group)).entrySet())
            assertEquals(hash.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(root, hash.getKey()).toPath()))), "Stale four-way fixture " + hash.getKey());
        return manifest;
    }
}
