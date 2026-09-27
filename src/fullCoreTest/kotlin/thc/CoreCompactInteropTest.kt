// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.nio.file.Files
import java.nio.file.Path
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** These files must be produced by the real converter and native GHC, not the
 * Kotlin model writer. The dedicated Gradle tasks require both inputs. */
class CoreCompactInteropTest {
    private val manifest = Path.of(System.getProperty("thc.compactInteropManifest"))
    private data class Row(val name: String, val input: Long, val expected: Long)
    private fun rows(): List<Row> = Files.readAllLines(Path.of(System.getProperty("thc.compactInteropOracle"))).map { line ->
        val fields = line.split('\t')
        require(fields.size == 3) { "Invalid native compact interop row" }
        Row(fields[0], fields[1].toLong(), fields[2].toLong())
    }.also { rows ->
        assertEquals(setOf("unicode", "tabbed", "missing"), rows.map { it.name }.toSet())
        assertEquals(15, rows.size)
    }

    @Test fun nativeResultsAndImmediateCompiledEntriesUseTheActualCompactRequest() = checkCompiled("ast")
    @Test fun bytecodeNativeResultsAndImmediateCompiledEntriesUseTheActualCompactRequest() = checkCompiled("bytecode")

    private fun checkCompiled(backend: String) {
        val oracle = rows().groupBy { it.name }
        for (verify in listOf(false, true))
            for ((name, values) in oracle) Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw")
                .option("engine.SingleTierCompilationThreshold", "10000000").build().use { context ->
                    val id = "main:SourceNotes.$name"
                    val entry = context.eval("thc", CoreModules.request(listOf("@$manifest"), id,
                        backend = backend, sourceNotesEnabled = false, asyncExceptions = false, verifyArtifacts = verify))
                    context.enter()
                    val program = try { Language.currentState().coreUnitPrograms.single() } finally { context.leave() }
                    fun count(field: String) = (program.diagnostics().getValue(field) as Number).toLong()
                    assertEquals(1L, count("coreCompactModuleOpens"))
                    assertEquals(if (verify) 8L else 1L, count("coreCompactDecodedBindings"))
                    assertEquals(0L, count("coreCompactDebugBytesRead"))
                    if (verify) assertTrue(count("coreCompactHashBytesScanned") > 0)
                    else assertEquals(0L, count("coreCompactHashBytesScanned"))

                    val target = program.entryTarget(id)
                    assertTrue(entry.invokeMember("compile").asBoolean())
                    fun installed() = target.javaClass.getMethod("isValidLastTier").invoke(target)
                    assertEquals(true, installed(), "$backend/$verify/$name before first call")
                    val before = count("compiledEntries")
                    // No interpreted or settling call precedes this invocation.
                    assertEquals(values.first().expected, entry.execute(values.first().input).asLong())
                    assertTrue(count("compiledEntries") > before,
                        "$backend/$verify/$name first compiled entry; valid=${installed()}, diagnostics=${program.diagnostics()}")
                    assertSame(target, program.entryTarget(id))
                    assertEquals(true, installed(), "$backend/$verify/$name after first call")
                    for (row in values.drop(1)) assertEquals(row.expected, entry.execute(row.input).asLong())
                    assertEquals(0L, count("coreCompactDebugBytesRead"))
                }
    }

    @Test fun originalJsonPairHasTheSameStrictPublicFirstCompiledControl() = checkJsonCompiled("ast")
    @Test fun originalJsonBytecodePairHasTheSameStrictPublicFirstCompiledControl() = checkJsonCompiled("bytecode")

    private fun checkJsonCompiled(backend: String) {
        val reference = System.getProperty("thc.compactInteropReference")
        Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .option("engine.SingleTierCompilationThreshold", "10000000").build().use { context ->
                val id = "main:SourceNotes.unicode"
                val entry = context.eval("thc", CoreModules.request(listOf("@$reference"), id,
                    backend = backend, sourceNotesEnabled = false, asyncExceptions = false))
                context.enter()
                val program = try { Language.currentState().coreUnitPrograms.single() } finally { context.leave() }
                val target = program.entryTarget(id)
                val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                assertTrue(entry.invokeMember("compile").asBoolean())
                assertEquals(1L, entry.execute(0).asLong())
                val valid = target.javaClass.getMethod("isValidLastTier").invoke(target)
                assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before,
                    "$backend original JSON first compiled entry; valid=$valid, diagnostics=${program.diagnostics()}")
            }
    }

    @Test fun genuineCompactAndJsonPublicExecutionMatchEveryNativeRow() {
        val oracle = rows().groupBy { it.name }
        for (path in listOf(manifest.toString(), System.getProperty("thc.compactInteropReference")))
            for (backend in listOf("ast", "bytecode")) for (verify in listOf(false, true))
                for ((name, values) in oracle) Context.newBuilder("thc").allowExperimentalOptions(true)
                    .option("engine.Compilation", "false").build().use { context ->
                        val entry = context.eval("thc", CoreModules.request(listOf("@$path"), "main:SourceNotes.$name",
                            backend = backend, sourceNotesEnabled = false, asyncExceptions = false, verifyArtifacts = verify))
                        for (row in values) assertEquals(row.expected, entry.execute(row.input).asLong(),
                            "$backend/$verify/$path/$row")
                        val diagnostics = Json.parse(entry.getMember("diagnostics").asString()) as Map<*, *>
                        assertEquals(0L, (diagnostics["unsupportedTraps"] as Number).toLong())
                        assertEquals(0L, (diagnostics["coreCompactDebugBytesRead"] as Number).toLong())
                    }
    }
}
