// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.io.IOAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.ByteArrayOutputStream
import java.io.File

/** The FCall is GHC's installed c_isatty declaration, not a synthetic alias. */
class OriginalHandleReadinessNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/original-handle-readiness")
    @Suppress("UNCHECKED_CAST")
    private fun json(path: String) = Json.parse(File(root, path).readText()) as Map<String, Any?>
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target))

    @Test fun originalIsattyAndErrnoMatchNativeBeforeAndAfterExplicitCompilation() {
        val manifest = json("build/original-handle-readiness/manifest.json")
        assertEquals(1L, manifest["schema"])
        assertEquals(3L, manifest["nativeRows"])
        val inputs = manifest["inputHashes"] as Map<String, String>
        OriginalStdioChecks.hashes(root, inputs, setOf(
            "compiler/test-fixtures/OriginalHandleReadinessAudit.hs",
            "compiler/test-fixtures/OriginalHandleReadinessNative.hs",
            "test/haskell-fixtures/OriginalHandleReadinessFixtures.hs", "scripts/core_original_foreign.py"))
        val artifacts = manifest["artifactHashes"] as Map<String, String>
        OriginalStdioChecks.hashes(root, artifacts, setOf(
            "build/original-handle-readiness/oracle.json",
            "build/original-handle-readiness/pre/core/OriginalHandleReadinessAudit.json",
            "build/original-handle-readiness/post/core/OriginalHandleReadinessAudit.json"),
            "build/original-handle-readiness/")
        val rows = Json.parse(File(directory, "oracle.json").readText()) as List<Map<String, Number>>
        assertEquals(listOf(-1, 1, 2), rows.map { it.getValue("fd").toInt() })
        val abi = StdioHostAbi.load()
        for (row in rows) {
            assertEquals(0, row.getValue("result").toInt())
            assertEquals(if (row.getValue("fd").toInt() < 0) abi.error(4) else abi.notTerminal(),
                row.getValue("errno").toLong())
        }
        for (stage in listOf("pre", "post")) {
            val prefix = "build/original-handle-readiness/$stage"
            val module = CoreModules.merge(listOf("OriginalHandleReadinessAudit", "THC.InterfaceClosure")
                .map { json("$prefix/core/$it.json") })
            for (name in listOf("originalIsTerminal", "originalIsTerminalErrno")) {
                val audit = json("$prefix/$name.audit.json")
                assertEquals(true, audit["accepted"]); assertEquals(emptyList<Any?>(), audit["issues"])
                assertEquals(emptyList<Any?>(), audit["missingGlobals"])
            }
            for (backend in listOf("ast", "bytecode")) {
                val output = ByteArrayOutputStream(); val errors = ByteArrayOutputStream()
                Context.newBuilder("thc").allowIO(IOAccess.NONE).out(output).err(errors)
                    .allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
                    .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
                    .build().use { context ->
                        context.initialize("thc"); context.enter()
                        try {
                            val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                            val programs = listOf("originalIsTerminal", "originalIsTerminalErrno").associateWith { name ->
                                val linked = CoreModules.reachable(module, name) + ("instrument" to true)
                                if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                            }
                            val targets = programs.mapValues { (name, program) -> program.entryTarget(name) }
                            fun exercise(compiled: Boolean) {
                                for (row in rows) for (name in targets.keys) {
                                    val program = programs.getValue(name); val target = targets.getValue(name)
                                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                    val actual = Calls.target(target, arrayOf(0L, row.getValue("fd").toLong()))
                                    val expected = if (name == "originalIsTerminal") row.getValue("result") else row.getValue("errno")
                                    assertEquals(expected.toLong(), actual, "$stage/$backend/$name/$row")
                                    if (compiled) {
                                        assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                                        valid(target)
                                    }
                                }
                                assertEquals(0, output.size()); assertEquals(0, errors.size())
                                val handoff = language.handoffState.get()
                                assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
                                assertEquals(0, handoff.results.retainedReferences())
                            }
                            exercise(false)
                            for (target in targets.values) {
                                target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                                valid(target)
                            }
                            exercise(true)
                        } finally { context.leave() }
                    }
            }
        }
    }
}
