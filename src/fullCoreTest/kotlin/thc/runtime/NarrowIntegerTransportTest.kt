// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import thc.CoreModules
import thc.EntryValue
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest

class NarrowIntegerTransportTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/narrow-integer-transport")
    private fun json(file: File) = Json.parse(file.readText()) as Map<String, Any?>
    private fun evidence(): Map<String, Any?> {
        val manifest = json(File(directory, "manifest.json"))
        assertEquals(true, manifest["strictAccepted"])
        for (group in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in manifest[group] as Map<String, String>) {
                val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                    .joinToString("") { "%02x".format(it) }
                assertEquals(expected, actual, "Stale narrow-integer input $path")
            }
        return manifest
    }
    private fun modules(stage: String) = File(directory, "$stage/core").listFiles()!!
        .filter { it.extension == "json" }.map(::json)
    private fun entered(inline: Boolean = true, action: (Context, Language) -> Unit) = Context.newBuilder("thc")
        .allowExperimentalOptions(true).option("compiler.Inlining", inline.toString())
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build().use { context ->
            context.initialize("thc"); context.enter()
            try { action(context, TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
            finally { context.leave() }
        }
    private fun program(language: Language, stage: String, backend: String, entry: String): ExecutableProgram {
        val linked = CoreModules.reachable(CoreModules.merge(modules(stage)), "main:NarrowIntegerTransport.$entry", true)
        return if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
    }
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), target.toString())
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target)
    }
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.results.depth); assertEquals(0, state.arguments.depth)
        assertEquals(0, state.results.retainedReferences()); assertEquals(0, state.arguments.retainedReferences())
    }
    private fun result(name: String): CoreRepresentation {
        val module = json(File(directory, "pre/core/NarrowIntegerTransport.json"))
        val binding = (module["bindings"] as List<Map<String, Any?>>).single { it["name"] == name }
        return CoreRepresentations.lambdaResult(binding["expr"] as List<Any?>)
    }
    private fun walk(value: Any?): Sequence<List<Any?>> = sequence {
        if (value is List<*>) { yield(value as List<Any?>); for (child in value) yieldAll(walk(child)) }
        if (value is Map<*, *>) for (child in value.values) yieldAll(walk(child))
    }

    @TestFactory fun genuineActiveValuesMatchNativeOnFirstInstalledCall(): List<DynamicTest> {
        val manifest = evidence()
        val rows = File(directory, "oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(manifest["entries"], rows.keys.toList()); assertEquals(200, rows.values.sumOf { it.size })
        return listOf("pre", "post").flatMap { stage ->
            assertEquals(true, json(File(directory, "$stage/audit.json"))["accepted"])
            listOf("ast", "bytecode").flatMap { backend -> rows.flatMap { (entry, cases) ->
                listOf(true, false).map { inline -> DynamicTest.dynamicTest("$stage/$backend/$entry/inline=$inline") {
                    entered(inline) { context, language ->
                        val program = program(language, stage, backend, entry)
                        val name = "main:NarrowIntegerTransport.$entry"
                        val callable = context.asValue(EntryValue(program, name, 1))
                        fun observe(row: List<String>) {
                            assertEquals(row[2].toLong(), callable.execute(row[1].toLong()).asLong(), row.toString())
                            assertEquals(0L, program.diagnostics()["blackholes"])
                            released(language)
                        }
                        cases.forEach(::observe)
                        assertTrue(callable.invokeMember("compile").asBoolean())
                        for (row in cases.reversed()) {
                            val before = program.diagnostics()["compiledEntries"] as Long
                            observe(row)
                            assertTrue((program.diagnostics()["compiledEntries"] as Long) > before)
                            valid(program.entryTarget(name)); valid(program.hostEntryTarget(1))
                        }
                    }
                } }
            } }
        }
    }

}
