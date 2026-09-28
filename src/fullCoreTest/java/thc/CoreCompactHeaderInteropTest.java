// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit original JSON and independently converted container pairs. */
public class CoreCompactHeaderInteropTest {
    @Test public void originalForeignProvenanceSurvivesTypedConversionWithoutReadingAnyBodyOrDebugTable() throws Exception {
        String input = System.getProperty("thc.compactInteropHeaders");
        if (input == null) throw new IllegalArgumentException("Supply original JSON/container path pairs in thc.compactInteropHeaders");
        String[] paths = input.split(",", -1);
        if (paths.length < 4 || paths.length % 2 != 0) throw new IllegalArgumentException("Failed requirement.");
        var covered = new LinkedHashSet<String>();
        for (int i = 0; i < paths.length; i += 2) {
            Path originalPath = Path.of(paths[i]), containerPath = Path.of(paths[i + 1]);
            Map<?, ?> original = (Map<?, ?>) Json.parse(Files.readString(originalPath));
            String identity = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(containerPath)));
            try (var file = new CoreCompactFile(containerPath, identity)) {
                var facts = new CoreCompactRecords(file, identity).header();
                for (String key : List.of("schema", "ghc", "unit", "module", "boundary", "providedModules", "targetLayout",
                        "foreign", "foreignExceptionBridge", "foreignExceptionBridgeUnit", "foreignLink",
                        "staticForeignImportStubs", "staticForeignImports", "staticForeignExports",
                        "staticForeignExportRegistration", "packageScalarLink", "packageNativeLink", "packageNativeArchive")) {
                    assertEquals(original.containsKey(key), facts.containsKey(key), originalPath + "/" + key + " presence");
                    assertEquals(original.get(key), facts.get(key), originalPath + "/" + key);
                    if (original.get(key) != null) covered.add(key);
                }
                assertEquals(0L, file.getCounters().statistics().dataBytesRead());
                assertEquals(0L, file.getCounters().statistics().lookupBytesRead());
                assertEquals(0L, file.getCounters().statistics().debugBytesRead());
                assertEquals(0L, file.getCounters().statistics().hashBytesRead());
            }
        }
        assertTrue(covered.containsAll(List.of("staticForeignImportStubs", "staticForeignImports", "packageNativeLink")),
            "Genuine import and native-product controls must be supplied: " + covered);
    }
}
