// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CoreCompactDirectoryTest {
    @TempDir lateinit var directory: Path
    private val hash = "a".repeat(64)
    private fun module(name: String) = mapOf("name" to name,
        "boundary" to "optimized-Core-after-Tidy-before-CorePrep", "sha256" to hash,
        "compact" to mapOf("path" to directory.resolve("$name.thc").toString(), "sha256" to hash,
            "format" to CoreCompactFormat.NAME),
        "containsDelimitedControl" to false, "registrationObligations" to false,
        "mainAlias" to false, "packageScalarDeclarations" to false)
    private fun unit(modules: List<Map<String, Any>>) = mapOf("id" to "unit", "depends" to emptyList<String>(), "modules" to modules)
    private fun manifest(units: List<Map<String, Any>>) = mapOf("format" to "thc-core-packages", "schema" to 1L,
        "ghc" to "9.14.1", "units" to units)

    @Test fun directoryConstructionNamesColdModulesWithoutOpeningAnyArtifact() {
        val document = manifest(listOf(unit(listOf(module("A"), module("B"), module("C")))))
        val parsed = CoreUnitDirectory.read(document)!!
        assertEquals(3, parsed.modules.size)
        assertEquals("A", parsed.owner("unit:A.entry")!!.name)
        assertEquals("B", parsed.owner("unit:B.cold")!!.name)
        assertNull(parsed.owner("absent:D.f"))
        assertTrue(parsed.modules.all { it.storage is CoreUnitDirectory.CompactStorage })
        assertTrue(parsed.units.all { it.json == null && it.symbols == null })
        Files.list(directory).use { assertEquals(0L, it.count()) }
    }

    @Test fun modulelessCompatibilityIdentitySurvivesAlongsideCompactModules() {
        val empty = mapOf("id" to "rts", "depends" to emptyList<String>(), "modules" to emptyList<Any>(),
            "bundle" to mapOf("path" to directory.resolve("unopened.zip").toString(), "sha256" to hash))
        val parsed = CoreUnitDirectory.read(manifest(listOf(empty, unit(listOf(module("A"))) +
            ("depends" to listOf("rts")))))!!
        assertEquals(listOf("rts", "unit"), parsed.units.map { it.id })
        assertEquals(listOf("rts"), parsed.units.last().depends)
        Files.list(directory).use { assertEquals(0L, it.count()) }
    }

    @Test fun ambiguousProtocolsBadFormatsAndDuplicateArtifactPathsRejectWithoutOpening() {
        val original = module("A")
        val artifact = original["compact"] as Map<*, *>
        val variants = listOf(original + ("compact" to (artifact + ("format" to "unknown"))),
            original + ("compact" to (artifact - "format")), original + ("start" to 0L),
            original - "registrationObligations")
        for (variant in variants) assertThrows(RuntimeException::class.java) {
            CoreUnitDirectory.read(manifest(listOf(unit(listOf(variant)))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoreUnitDirectory.read(manifest(listOf(unit(listOf(original, module("B") + ("compact" to artifact))))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoreUnitDirectory.read(manifest(listOf(unit(listOf(original)) + ("json" to artifact))))
        }
        Files.list(directory).use { assertEquals(0L, it.count()) }
    }
}
