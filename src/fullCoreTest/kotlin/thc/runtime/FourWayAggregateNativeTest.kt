// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.RootCallTarget
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import thc.CoreModules
import thc.EntryValue
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest

internal fun verifyFourWayEvidence(root: File): Map<String, Any?> {
    val manifest = Json.parse(File(root, "build/fourway-aggregate/manifest.json").readText()) as Map<String, Any?>
    assertEquals(true, manifest["strictAccepted"], "Preparation-only rejection evidence cannot authorize runtime checks")
    assertEquals(listOf("0", "1", "4294967295", "4294967296", "9223372036854775808", "18446744073709551615"),
        manifest["payloads"], "Unsigned payload provenance must retain exact decimal strings")
    for (group in listOf("inputHashes", "artifactHashes"))
        for ((path, expected) in manifest[group] as Map<String, String>) {
            val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals(expected, actual, "Stale four-way fixture $path")
        }
    return manifest
}

class FourWayAggregateNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/fourway-aggregate")
    private fun json(file: File) = Json.parse(file.readText()) as Map<String, Any?>
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), target.toString())

    @TestFactory fun originalAndNestedAggregatesMatchNativeOnFirstCompiledEntry(): List<DynamicTest> {
        val manifest = verifyFourWayEvidence(root)
        val rows = File(directory, "oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(manifest["entries"], rows.keys.toList())
        assertEquals(16, rows.size)
        assertEquals(1536, rows.values.sumOf { it.size })
        return listOf("pre", "post").flatMap { stage ->
            assertEquals(true, json(File(directory, "$stage/audit.json"))["accepted"])
            val modules = File(directory, "$stage/core").listFiles()!!.filter { it.extension == "json" }.map(::json)
            rows.flatMap { (entry, cases) -> listOf("ast", "bytecode").flatMap { backend ->
                // Residual edges additionally exercise durable captures/PAPs without inlining.
                val inlineModes = if (entry.contains("residual", ignoreCase = true) || entry.contains("capture", ignoreCase = true)) listOf(true, false) else listOf(true)
                inlineModes.map { inline -> DynamicTest.dynamicTest("$stage/$backend/$entry/inline=$inline") {
                    Context.newBuilder("thc").allowExperimentalOptions(true)
                        .option("compiler.Inlining", inline.toString())
                        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                        .option("engine.CompilationFailureAction", "Throw").build().use { context ->
                            context.initialize("thc"); context.enter()
                            try {
                                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                                val name = "main:FourWayAggregateFields.$entry"
                                val linked = CoreModules.reachable(CoreModules.merge(modules), name, true)
                                val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                                val callable = context.asValue(EntryValue(program, name, 2))
                                fun observe(row: List<String>) {
                                    val result = callable.execute(row[2].toLong(), java.lang.Long.parseUnsignedLong(row[3])).asLong()
                                    assertEquals(row[4], java.lang.Long.toUnsignedString(result), row.joinToString("/"))
                                    val handoff = language.handoffState.get()
                                    assertEquals(0, handoff.results.depth); assertEquals(0, handoff.arguments.depth)
                                    assertEquals(0, handoff.results.retainedReferences()); assertEquals(0, handoff.arguments.retainedReferences())
                                }
                                cases.forEach(::observe)
                                // Public compile restores the shared entry prerequisite without
                                // executing a settling guest call. The next observation is checked.
                                assertTrue(callable.invokeMember("compile").asBoolean())
                                for (row in cases.reversed()) {
                                    val before = program.diagnostics()["compiledEntries"] as Long
                                    observe(row)
                                    assertTrue((program.diagnostics()["compiledEntries"] as Long) > before)
                                    valid(program.entryTarget(name)); valid(program.hostEntryTarget(2))
                                }
                            } finally { context.leave() }
                        }
                } }
            } }
        }
    }
}
