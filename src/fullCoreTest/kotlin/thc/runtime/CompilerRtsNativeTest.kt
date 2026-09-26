// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.EntryValue
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest

class CompilerRtsNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/compiler-rts")
    private fun json(file: File) = Json.parse(file.readText()) as Map<String, Any?>
    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build()

    private fun calls(value: Any?): List<List<Any?>> = when (value) {
        is Map<*, *> -> value.values.flatMap(::calls)
        is List<*> -> (if (value.firstOrNull() == "app" &&
            (value.lastOrNull() as? Map<*, *>)?.get("foreignCall") is Map<*, *>) listOf(value) else emptyList()) + value.flatMap(::calls)
        else -> emptyList()
    }

    @Test fun genuineDeclarationsRejectOtherUnitsSafetyAndArity() {
        val actual = calls(json(File(directory, "post.json")))
        assertTrue(actual.isNotEmpty())
        val seen = mutableSetOf<String>()
        for (call in actual) {
            val metadata = call.last() as Map<String, Any?>
            val descriptor = metadata["foreignCall"] as Map<String, Any?>
            val target = descriptor["target"] as Map<String, Any?>
            val symbol = target["symbol"] as String
            seen += symbol
            val operands = (call[2] as List<List<Any?>>).map { CoreRepresentations.metadata(it)?.get("rep") }
            fun validate(updated: Map<String, Any?>): Any? = if (symbol == "keepCAFsForGHCi")
                CoreStringRtsForeign.validate(updated, operands, call[3] as List<*>, metadata["rep"])
            else CoreSharedCAFStores.validate(updated, operands, call[3] as List<*>, metadata["rep"])
            assertNotNull(validate(metadata))
            for (bad in listOf(descriptor + ("target" to (target + ("unit" to "ghc-internal"))),
                descriptor + ("safety" to "safe"), descriptor + ("arity" to 99L),
                descriptor + ("target" to (target + ("isFunction" to false)))))
                assertThrows(RuntimeFault::class.java) { validate(metadata + ("foreignCall" to bad)) }
        }
        assertEquals(setOf("keepCAFsForGHCi", "getOrSetLibHSghcFastStringTable"), seen)
    }

    @Test fun actualCompilerDeclarationsMatchNativeBeforeAndAfterCompilation() {
        val manifest = json(File(directory, "manifest.json"))
        for (group in listOf("inputHashes", "artifactHashes", "interfaceHashes"))
            for ((path, expected) in manifest[group] as Map<String, String>) {
                val file = File(path).let { if (it.isAbsolute) it else File(root, path) }
                val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                assertEquals(expected, digest, "Stale compiler RTS fixture $path")
            }
        val rows = File(directory, "oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(setOf("originalKeep", "originalFast", "uniqueCells"), rows.keys)
        for (stage in listOf("pre", "post")) {
            val module = json(File(directory, "$stage.json"))
            for ((entry, cases) in rows) {
                val audit = json(File(directory, "$stage-$entry.audit.json"))
                assertEquals(true, audit["accepted"])
                for (backend in listOf("ast", "bytecode")) context().use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val linked = CoreModules.reachable(CoreModules.merge(listOf(module)), entry, true)
                        val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                        val callable = context.asValue(EntryValue(program, entry, 1))
                        for (row in cases) assertEquals(row[2].toLong(), callable.execute(row[1].toLong()).asLong(), "$stage/$backend/$entry")
                        assertTrue(callable.invokeMember("compile").asBoolean())
                        for (row in cases.reversed()) {
                            val before = program.diagnostics()["compiledEntries"] as Long
                            assertEquals(row[2].toLong(), callable.execute(row[1].toLong()).asLong())
                            assertTrue((program.diagnostics()["compiledEntries"] as Long) > before)
                        }
                        assertEquals(0, language.handoffState.get().results.depth)
                    } finally { context.leave() }
                }
            }
        }
    }
}
