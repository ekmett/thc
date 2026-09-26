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

class SumJoinInputNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/sum-join-input")
    private fun json(file: File) = Json.parse(file.readText()) as Map<String, Any?>
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), target.toString())
    private fun assertRecursiveWrapper(source: Map<String, Any?>, name: String) {
        fun groups(value: Any?): List<List<Any?>> = when (value) {
            is List<*> -> (if (value.firstOrNull() == "let") listOf(value as List<Any?>) else emptyList()) + value.flatMap(::groups)
            is Map<*, *> -> value.values.flatMap(::groups)
            else -> emptyList()
        }
        val binding = (source["bindings"] as List<Map<String, Any?>>).single { it["id"] == name }
        val joins = groups(binding["expr"]).filter { group ->
            (group[2] as List<Map<String, Any?>>).any { "joinValueArity" in it }
        }
        assertEquals(listOf(false, true), joins.map { it[1] }, "GHC wrapper then recursive join")
        val wrapper = (joins[0][2] as List<Map<String, Any?>>).single()["expr"] as List<Any?>
        assertEquals(joins[1], wrapper[2])
        val loop = (joins[1][2] as List<Map<String, Any?>>).single()
        val transfer = joins[1][3] as List<Any?>
        assertEquals("app", transfer[0]); assertEquals(loop["id"], (transfer[1] as List<*>)[1])
        assertEquals((wrapper[1] as List<Map<String, Any?>>).map { it["id"] },
            (transfer[2] as List<List<Any?>>).map { assertEquals("var", it[0]); it[1] })
    }
    @TestFactory fun originalSumJoinInputsInline(): List<DynamicTest> = native(true)
    @TestFactory fun originalSumJoinInputsResidual(): List<DynamicTest> = native(false)
    private fun native(inlining: Boolean): List<DynamicTest> {
        val manifest = json(File(directory, "manifest.json"))
        for (group in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in manifest[group] as Map<String, String>) {
                val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                    .joinToString("") { "%02x".format(it) }
                assertEquals(expected, actual, "Stale sum join input fixture $path")
            }
        val rows = File(directory, "oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(7, rows.size); assertEquals(259, rows.values.sumOf { it.size })
        return listOf("pre", "post").flatMap { stage ->
            assertEquals(true, json(File(directory, "$stage/audit.json"))["accepted"])
            val source = json(File(directory, "$stage/core/SumJoinInputAudit.json"))
            rows.flatMap { (entry, cases) ->
                val name = "main:SumJoinInputAudit.$entry"
                if (entry in listOf("recursiveSwap", "changingTag")) assertRecursiveWrapper(source, name)
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
                                    fun check(row: List<String>) {
                                        val before = program.diagnostics()["localJoinTransfers"] as Long
                                        assertEquals(row[2].toLong(), callable.execute(row[1].toLong()).asLong(), row[1])
                                        val transfers = (program.diagnostics()["localJoinTransfers"] as Long) - before
                                        val expected = when (entry) {
                                            // GHC retains a nonrecursive NOINLINE join wrapper around
                                            // these recursive loops: entry transfers through both.
                                            "recursiveSwap", "changingTag" -> (row[1].toLong() and 31L) + 2
                                            "mutual" -> (row[1].toLong() and 15L) + 1
                                            else -> 1L
                                        }
                                        assertEquals(expected, transfers, "$entry join transfer count")
                                    }
                                    for (row in cases) check(row)
                                    assertTrue(callable.invokeMember("compile").asBoolean())
                                    for (row in cases.reversed()) {
                                        val before = program.diagnostics()["compiledEntries"] as Long
                                        check(row)
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
