// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import org.graalvm.polyglot.Value
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Small native-oracle baseline for the two execution backends. */
class FastSmokeTest {
    private val project = File(System.getProperty("thc.projectRoot"))
    private val modules = listOf("THC.Prim", "THC.Fixtures")
        .map { File(project, "build/core/$it.json").path }

    private data class Row(val entry: String, val input: Long, val expected: Long)

    private fun nativeRows(): Map<String, List<Row>> = File(project, "build/native/oracle.tsv")
        .readLines().filter { it.isNotBlank() }.map { line ->
            val fields = line.split('\t')
            require(fields.size == 3) { "Malformed native oracle row: $line" }
            Row(fields[0], fields[1].toLong(), fields[2].toLong())
        }.filter { it.entry == "under" || it.entry == "sumLoop" }.groupBy { it.entry }

    @Suppress("UNCHECKED_CAST")
    private fun diagnostics(value: Value): Map<String, Any?> =
        Json.parse(value.getMember("diagnostics").asString()) as Map<String, Any?>

    private fun count(value: Value, key: String): Long = (diagnostics(value).getValue(key) as Number).toLong()

    @Test fun nativePartialApplicationAndTailLoopRunThroughInstalledCode() {
        val rows = nativeRows()
        assertEquals(mapOf("under" to 7, "sumLoop" to 7), rows.mapValues { it.value.size },
            "The native GHC oracle must cover both smoke entries")
        for (entry in listOf("under", "sumLoop"))
            assertEquals(setOf(-3L, 0L, 1L, 2L, 7L, 10L, 20L),
                rows.getValue(entry).map { it.input }.toSet(), "$entry native oracle inputs")
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            for (entry in listOf("under", "sumLoop")) {
                val function = loadEntry(context, modules, entry, backend = backend)
                assertEquals(backend, diagnostics(function)["backend"])
                for (row in rows.getValue(entry))
                    assertEquals(row.expected, function.execute(row.input).asLong(),
                        "$backend/$entry(${row.input}) interpreted")

                assertTrue(function.invokeMember("compile").asBoolean(), "$backend/$entry compilation")
                val before = count(function, "compiledEntries")
                for (row in rows.getValue(entry))
                    assertEquals(row.expected, function.execute(row.input).asLong(),
                        "$backend/$entry(${row.input}) compiled")
                assertTrue(count(function, "compiledEntries") > before,
                    "$backend/$entry must execute installed guest code")
                assertEquals(0L, count(function, "unsupportedTraps"), "$backend/$entry")
            }
        }
    }
}
