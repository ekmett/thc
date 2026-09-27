// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** The explicit test input lists original JSON, independently converted container pairs. */
class CoreCompactHeaderInteropTest {
    @Test fun originalForeignProvenanceSurvivesTypedConversionWithoutReadingAnyBodyOrDebugTable() {
        val paths = requireNotNull(System.getProperty("thc.compactInteropHeaders")) {
            "Supply original JSON/container path pairs in thc.compactInteropHeaders"
        }.split(',').map(Path::of)
        require(paths.size >= 4 && paths.size % 2 == 0)
        val covered = mutableSetOf<String>()
        for ((originalPath, containerPath) in paths.chunked(2)) {
            val original = Json.parse(Files.readString(originalPath)) as Map<*, *>
            val identity = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(containerPath))
                .joinToString("") { "%02x".format(it) }
            CoreCompactFile(containerPath, identity).use { file ->
                val facts = CoreCompactRecords(file, identity).header()
                for (key in listOf("schema", "ghc", "unit", "module", "boundary", "providedModules", "targetLayout",
                    "foreign", "foreignExceptionBridge", "foreignExceptionBridgeUnit", "foreignLink",
                    "staticForeignImportStubs", "staticForeignImports", "staticForeignExports",
                    "staticForeignExportRegistration", "packageScalarLink", "packageNativeLink", "packageNativeArchive")) {
                    assertEquals(original.containsKey(key), facts.containsKey(key), "$originalPath/$key presence")
                    assertEquals(original[key], facts[key], "$originalPath/$key")
                    if (original[key] != null) covered += key
                }
                assertEquals(0L, file.counters.statistics().dataBytesRead)
                assertEquals(0L, file.counters.statistics().lookupBytesRead)
                assertEquals(0L, file.counters.statistics().debugBytesRead)
                assertEquals(0L, file.counters.statistics().hashBytesRead)
            }
        }
        assertTrue(covered.containsAll(listOf("staticForeignImportStubs", "staticForeignImports", "packageNativeLink")),
            "Genuine import and native-product controls must be supplied: $covered")
    }
}
