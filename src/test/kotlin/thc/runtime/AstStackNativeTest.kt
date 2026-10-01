// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.security.MessageDigest

class AstStackNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/deep-evaluation")
    private fun rows(): List<Pair<Long, Long>> {
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<String, Any?>
        assertEquals("9.14.1", manifest["ghc"])
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in manifest[kind] as Map<String, String>) {
                val file = File(root, path)
                assertTrue(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
                val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, hash, "$kind/$path")
            }
        return File(root, manifest["oracle"] as String).readLines().map {
            val row = it.split('\t'); row[0].toLong() to row[1].toLong()
        }.also { assertEquals(listOf(0L, 100L, 1000L, 5000L, 20000L), it.map { row -> row.first }) }
    }

    private fun source(stage: String): Map<String, Any?> {
        val audit = Json.parse(File(directory, "$stage/audit.json").readText()) as Map<String, Any?>
        assertEquals(true, audit["accepted"]); assertEquals(emptyList<Any>(), audit["issues"])
        assertEquals(emptyList<Any>(), audit["missingGlobals"])
        val modules = listOf("DeepEvaluation", "THC.InterfaceClosure").map {
            Json.parse(File(directory, "$stage/core/$it.json").readText()) as Map<String, Any?>
        }
        return CoreModules.reachable(CoreModules.merge(modules), "probe", strictLink = true) + ("instrument" to true)
    }

    @Test fun genuineDeepLazyEvaluationMatchesNativeWithoutGuestCompilation() {
        val rows = rows()
        for (stage in listOf("pre", "post")) Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.Compilation", "false").build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val program = Program(language, source(stage), true)
                    val state = Language.currentState()
                    state.threads.enterCurrent()
                    try {
                        val scope = state.threadPollState.get().astStack
                        for ((input, expected) in rows) {
                            val before = scope.spills
                            assertEquals(expected, Calls.target(program.hostEntryTarget(1),
                                arrayOf(program.entryValue("probe"), arrayOf(input))), "$stage/$input")
                            if (input >= 5000) assertTrue(scope.spills > before)
                            assertEquals(0, scope.depth); assertFalse(scope.driving)
                        }
                        assertEquals(0, language.handoffState.get().arguments.depth)
                        assertEquals(0, language.handoffState.get().results.depth)
                    } finally { state.threads.leaveCurrent() }
                } finally { context.leave() }
            }
    }

    @Test fun firstCompiledPublicEntryRetainsItsDeepResult() {
        rows()
        for (stage in listOf("pre", "post")) Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val program = Program(language, source(stage), true)
                    val function = context.asValue(EntryValue(program, "probe", 1))
                    repeat(10) { assertEquals(100L, function.execute(100L).asLong()) }
                    assertTrue(function.invokeMember("compile").asBoolean())
                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    assertEquals(5000L, function.execute(5000L).asLong(), stage)
                    assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before)
                    assertEquals(20000L, function.execute(20000L).asLong(), "$stage/larger input")
                    assertEquals(0, language.handoffState.get().arguments.depth)
                    assertEquals(0, language.handoffState.get().results.depth)
                } finally { context.leave() }
            }
    }
}
