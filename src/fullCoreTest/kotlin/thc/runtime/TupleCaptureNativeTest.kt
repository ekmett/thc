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

class TupleCaptureNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/tuple-capture")
    private fun json(file: File) = Json.parse(file.readText()) as Map<String, Any?>
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), target.toString())
    @TestFactory fun originalTupleCapturesInline(): List<DynamicTest> = native(true)
    @TestFactory fun originalTupleCapturesResidual(): List<DynamicTest> = native(false)
    @TestFactory fun originalCapturedPropertiesRemainLazyAndSurviveTheirCreator(): List<DynamicTest> =
        listOf("pre", "post").flatMap { stage -> listOf("ast", "bytecode").map { backend ->
            DynamicTest.dynamicTest("$stage/$backend/owned storage") {
                verifyEvidence()
                val source = json(File(directory, "$stage/core/TupleCaptureAudit.json"))
                val linked = CoreModules.reachable(source, listOf("escaped", "thunk", "emptyCapture")
                    .map { "main:TupleCaptureAudit.$it" }, true) + ("instrument" to true)
                Context.newBuilder("thc").build().use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                        fun retain(name: String, x: Long): Any? {
                            val data = Calls.target(program.hostEntryTarget(1), arrayOf(
                                program.entryValue("main:TupleCaptureAudit.$name"), arrayOf(x))) as DataValue
                            return data.layout.read(data, 0)
                        }
                        val closure = retain("retainedClosure", 17L) as Closure
                        val empty = retain("retainedEmpty", 0L) as Closure
                        assertNull(empty.environment, "A zero-storage tuple does not allocate capture properties")
                        assertTrue(ClosureInspection.image(empty).pointers.isEmpty())
                        val environment = requireNotNull(closure.environment)
                        assertEquals(6, environment.layout.storageSize)
                        val fields = (0 until environment.layout.storageSize).map { environment.layout.inspect(environment, it) }
                        assertEquals(3.75f, fields.filterIsInstance<Float>().single())
                        assertEquals(-5.25, fields.filterIsInstance<Double>().single())
                        assertEquals(1, fields.filterIsInstance<ManagedAddress>().size)
                        val lazy = fields.filterIsInstance<Thunk>().single()
                        assertEquals(0, lazy.state)
                        val image = ClosureInspection.image(closure)
                        assertEquals(56, image.bytes.size); assertEquals(2, image.pointers.size)
                        assertTrue(image.pointers.any { it === lazy })
                        retain("retainedClosure", -29L)
                        assertEquals(4L * 17 + 3L * 13 + 143,
                            Calls.target(program.hostEntryTarget(1), arrayOf(closure, arrayOf(13L))))
                        assertEquals(0, lazy.state, "Inspecting and invoking the capture must not force its ignored field")
                        val thunk = retain("retainedThunk", 17L) as Thunk
                        assertEquals(0, thunk.state)
                        val thunkImage = ClosureInspection.image(thunk)
                        assertEquals(2, thunkImage.pointers.size)
                        assertTrue(thunkImage.pointers.any { it === lazy })
                        val before = program.diagnostics()["thunkEvaluations"] as Long
                        val first = Calls.target(program.hostEntryTarget(0), arrayOf(thunk)) as DataValue
                        assertEquals(before + 1, program.diagnostics()["thunkEvaluations"])
                        assertEquals(214L, first.layout.read(first, 0))
                        assertSame(first, Calls.target(program.hostEntryTarget(0), arrayOf(thunk)))
                        assertEquals(before + 1, program.diagnostics()["thunkEvaluations"])
                        assertEquals(0, lazy.state)
                        assertEquals(0, language.handoffState.get().arguments.retainedReferences())
                        assertEquals(0, language.handoffState.get().results.retainedReferences())
                    } finally { context.leave() }
                }
            }
        } }
    private fun verifyEvidence() {
        val manifest = json(File(directory, "manifest.json"))
        for (group in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in manifest[group] as Map<String, String>) {
                val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                    .joinToString("") { "%02x".format(it) }
                assertEquals(expected, actual, "Stale tuple capture fixture $path")
            }
    }
    private fun native(inlining: Boolean): List<DynamicTest> {
        verifyEvidence()
        val rows = File(directory, "oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(8, rows.size); assertEquals(296, rows.values.sumOf { it.size })
        return listOf("pre", "post").flatMap { stage ->
            assertEquals(true, json(File(directory, "$stage/audit.json"))["accepted"])
            val source = json(File(directory, "$stage/core/TupleCaptureAudit.json"))
            rows.flatMap { (entry, cases) ->
                val name = "main:TupleCaptureAudit.$entry"
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
