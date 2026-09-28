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

class FloatForeignNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/float-foreign")
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

    @Test fun originalFloatingDeclarationsRejectForgedAbiAndDefinedHeads() {
        val seen = mutableSetOf<String>()
        for (call in calls(json(File(directory, "post.json")))) {
            val metadata = call.last() as Map<String, Any?>
            val descriptor = metadata["foreignCall"] as Map<String, Any?>
            val target = descriptor["target"] as Map<String, Any?>
            seen += target["symbol"] as String
            val operands = (call[2] as List<List<Any?>>).map { CoreRepresentations.metadata(it)?.get("rep") }
            fun validate(updated: Map<String, Any?>) =
                CoreFloatForeign.validate(updated, operands, call[3] as List<*>, metadata["rep"])
            val operation = requireNotNull(validate(metadata))
            CoreFloatForeign.validateHead(call[1] as List<Any?>, false)
            assertThrows(RuntimeFault::class.java) { CoreFloatForeign.validateHead(call[1] as List<Any?>, true) }
            for (bad in listOf(descriptor + ("target" to (target + ("unit" to "ghc-9.14.1-inplace"))),
                descriptor + ("target" to (target + ("isFunction" to false))), descriptor + ("convention" to "capi"),
                descriptor + ("safety" to "safe"), descriptor + ("arity" to 1L), descriptor + ("suppliedArity" to 1L)))
                assertThrows(RuntimeFault::class.java) { validate(metadata + ("foreignCall" to bad)) }
            val wrong = CoreRepresentation(if (operation.single) CoreKind.DOUBLE else CoreKind.FLOAT, true, true, listOf(if (operation.single) "DoubleRep" else "FloatRep"))
            assertThrows(RuntimeFault::class.java) { CoreFloatForeign.validateOperand(operation, 0, wrong, null) }
        }
        assertEquals(FloatForeignOp.values().map { it.symbol }.toSet(), seen)
    }

    @Test fun originalFloatClassificationsAndRoundingMatchNativeRawBitsOnFirstCompiledCalls() {
        val manifest = json(File(directory, "manifest.json"))
        for (group in listOf("inputHashes", "artifactHashes", "interfaceHashes"))
            for ((path, expected) in manifest[group] as Map<String, String>) {
                val file = File(path).let { if (it.isAbsolute) it else File(root, path) }
                val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                assertEquals(expected, actual, "Stale original floating fixture $path")
            }
        val rows = File(directory, "oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
        assertEquals(12, rows.size)
        assertEquals(384, rows.values.sumOf { it.size })
        for (stage in listOf("pre", "post")) {
            val module = json(File(directory, "$stage.json"))
            for ((entry, cases) in rows) {
                assertEquals(true, json(File(directory, "$stage-$entry.audit.json"))["accepted"])
                for (backend in listOf("ast", "bytecode")) context().use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val linked = CoreModules.reachable(CoreModules.merge(listOf(module)), entry, true)
                        val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                        val callable = context.asValue(EntryValue(program, entry, 1))
                        for (row in cases) assertEquals(row[2].toLong(), callable.execute(row[1].toLong()).asLong(), "$stage/$backend/$entry/${row[1]}")
                        assertTrue(callable.invokeMember("compile").asBoolean())
                        for (row in cases.reversed()) {
                            val before = program.diagnostics()["compiledEntries"] as Long
                            assertEquals(row[2].toLong(), callable.execute(row[1].toLong()).asLong(), "$stage/$backend/$entry/${row[1]}")
                            assertTrue((program.diagnostics()["compiledEntries"] as Long) > before)
                        }
                        assertEquals(0, language.handoffState.get().results.depth)
                    } finally { context.leave() }
                }
            }
        }
    }
}
