// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.io.IOAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import thc.CoreModules
import thc.CorePackageManifest
import thc.EntryValue
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest

class TupleJoinNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/tuple-join-input")
    private fun json(file: File) = Json.parse(file.readText()) as Map<String, Any?>
    @TestFactory fun typedJoinMovesMatchNativeWithBottomingCases(): List<DynamicTest> {
        val manifest = json(File(directory, "manifest.json"))
        for (group in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in manifest[group] as Map<String, String>) {
                val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                    .joinToString("") { "%02x".format(it) }
                assertEquals(expected, actual, "Stale tuple join fixture $path")
            }
        val allRows = File(directory, "oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(6, allRows.size); assertEquals(222, allRows.values.sumOf { it.size })
        val entries = manifest["entries"] as List<String>
        val rows = allRows.filterKeys { it in entries }
        assertEquals(entries.toSet(), rows.keys)
        val originals = mutableListOf<Map<String, Any?>>()
        val layout = if (manifest["originalLibrary"] == true) {
            assertTrue("originalRoundTo" in entries)
            checkNotNull(CorePackageManifest.visitModules(File(root, manifest["packageManifest"] as String).path) {
                module, _ -> originals.add(module)
            }.targetLayout).also {
                val roundTo = originals.flatMap { it["bindings"] as? List<Map<String, Any?>> ?: emptyList() }
                    .single { it["id"] == "ghc-internal:GHC.Internal.Float.\$wroundTo" }
                assertTrue(roundTo.toString().contains("joinValueArity"), "Original installed roundTo retains its local join")
            }
        } else {
            assertFalse("originalRoundTo" in entries)
            null
        }
        return listOf("pre", "post").flatMap { stage ->
            assertEquals(true, json(File(directory, "$stage/audit.json"))["accepted"])
            val modules = listOf(json(File(directory, "$stage/core/TupleJoinInputAudit.json")))
            val combined = CoreModules.merge(originals + modules).let { if (layout == null) it else it + ("targetLayout" to layout) }
            rows.flatMap { (entry, cases) ->
                val name = "main:TupleJoinInputAudit.$entry"
                val linked = CoreModules.reachable(combined, name, true) + ("instrument" to true)
                listOf("ast", "bytecode").map { backend ->
                DynamicTest.dynamicTest("$stage/$backend/$entry") {
                    Context.newBuilder("thc", "llvm").allowNativeAccess(true).allowIO(IOAccess.ALL)
                        .allowCreateThread(true).allowExperimentalOptions(true)
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
                                }
                                assertEquals(0, language.handoffState.get().results.depth)
                                assertEquals(0, language.handoffState.get().arguments.depth)
                                assertEquals(0, language.handoffState.get().results.retainedReferences())
                                assertEquals(0, language.handoffState.get().arguments.retainedReferences())
                            } finally { context.leave() }
                        }
                }
            } }
        }
    }
}
