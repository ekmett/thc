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

class SumInputNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/sum-input")
    private fun json(file: File) = Json.parse(file.readText()) as Map<String, Any?>
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), target.toString())
    @TestFactory fun originalSumInputsInline(): List<DynamicTest> = native(true)
    @TestFactory fun originalSumInputsResidual(): List<DynamicTest> = native(false)
    private fun native(inlining: Boolean): List<DynamicTest> {
        val manifest = json(File(directory, "manifest.json"))
        for (group in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in manifest[group] as Map<String, String>) {
                val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                    .joinToString("") { "%02x".format(it) }
                assertEquals(expected, actual, "Stale sum input fixture $path")
            }
        val rows = File(directory, "oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(9, rows.size); assertEquals(333, rows.values.sumOf { it.size })
        return listOf("pre", "post").flatMap { stage ->
            assertEquals(true, json(File(directory, "$stage/audit.json"))["accepted"])
            val source = json(File(directory, "$stage/core/SumInputAudit.json"))
            rows.flatMap { (entry, cases) ->
                val name = "main:SumInputAudit.$entry"
                val linked = CoreModules.reachable(source, name, true) + ("instrument" to true)
                listOf("ast", "bytecode").map { backend ->
                    DynamicTest.dynamicTest("$stage/$backend/$entry/inlining=$inlining") {
                        Context.newBuilder("thc").allowExperimentalOptions(true)
                            .option("compiler.Inlining", inlining.toString())
                            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                            .option("engine.CompilationFailureAction", "Throw").build().use { context ->
                                context.initialize("thc"); context.enter()
                                try {
                                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                                    val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                                    val callable = context.asValue(EntryValue(program, name, 1))
                                    for (row in cases) assertEquals(row[2].toLong(), callable.execute(row[1].toLong()).asLong(), row[1])
                                    assertTrue(callable.invokeMember("compile").asBoolean())
                                    for (row in cases.reversed()) {
                                        val before = program.diagnostics()["compiledEntries"] as Long
                                        assertEquals(row[2].toLong(), callable.execute(row[1].toLong()).asLong(), row[1])
                                        assertTrue((program.diagnostics()["compiledEntries"] as Long) > before)
                                        valid(program.entryTarget(name)); valid(program.hostEntryTarget(1))
                                    }
                                    assertEquals(0, language.handoffState.get().results.depth)
                                    assertEquals(0, language.handoffState.get().arguments.depth)
                                    assertEquals(0, language.handoffState.get().results.retainedReferences())
                                    assertEquals(0, language.handoffState.get().arguments.retainedReferences())
                                } finally { context.leave() }
                            }
                    }
                }
            }
        }
    }
}
