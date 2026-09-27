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

class GcStatsNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/gc-stats")
    private fun json(file: File) = Json.parse(file.readText()) as Map<String, Any?>
    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build()
    private fun program(language: Language, backend: String, entry: String, stage: String): ExecutableProgram {
        val module = CoreModules.merge(listOf(json(File(directory, "$entry-$stage.json"))))
        // Exercise the safe-return poll on both backends, with AST's explicit opt-in.
        return if (backend == "ast") Program(language, module, true) else BytecodeProgram(language, module)
    }
    private fun calls(value: Any?): List<List<Any?>> = when (value) {
        is Map<*, *> -> value.values.flatMap(::calls)
        is List<*> -> (if (value.firstOrNull() == "app" &&
            (value.lastOrNull() as? Map<*, *>)?.get("foreignCall") is Map<*, *>) listOf(value as List<Any?>)
            else emptyList()) + value.flatMap(::calls)
        else -> emptyList()
    }
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
    }

    @Test fun originalDeclarationsKeepTheirUnitSafetyStateAndResultAbi() {
        val entries = json(File(directory, "manifest.json"))["entries"] as List<List<String>>
        val seen = mutableSetOf<String>()
        for ((entry, _) in entries) for (stage in listOf("pre", "post")) {
            val actual = calls(json(File(directory, "$entry-$stage.json")))
            assertEquals(if (entry == "clock") 2 else 1, actual.size)
            for (call in actual) {
                val metadata = call.last() as Map<String, Any?>
                val descriptor = metadata["foreignCall"] as Map<String, Any?>
                val target = descriptor["target"] as Map<String, Any?>
                seen += target["symbol"] as String
                val operands = (call[2] as List<List<Any?>>).map { CoreRepresentations.metadata(it)?.get("rep") }
                fun validate(meta: Map<String, Any?>) =
                    CoreGcForeign.validate(meta, operands, call[3] as List<*>, meta["rep"])
                assertNotNull(validate(metadata))
                CoreGcForeign.validateHead(call[1] as List<Any?>, false)
                assertThrows(RuntimeFault::class.java) { CoreGcForeign.validateHead(call[1] as List<Any?>, true) }
                for (bad in listOf(descriptor + ("target" to (target + ("unit" to "main"))),
                    descriptor + ("target" to (target + ("isFunction" to false))),
                    descriptor + ("safety" to if (entry == "clock") "safe" else "unsafe"),
                    descriptor + ("arity" to 99L), descriptor + ("suppliedArity" to 0L),
                    descriptor + ("resultRep" to mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to false))))
                    assertThrows(RuntimeFault::class.java) { validate(metadata + ("foreignCall" to bad)) }
                assertThrows(RuntimeFault::class.java) {
                    CoreGcForeign.validate(metadata, operands.dropLast(1), call[3] as List<*>, metadata["rep"])
                }
            }
        }
        assertEquals(GcForeignOp.values().filter { it.unit == "ghc-internal" }.map { it.symbol }.toSet(), seen)
    }

    @Test fun originalGcAndClockCallsMatchNativeFromTheFirstCompiledInvocation() {
        val manifest = json(File(directory, "manifest.json"))
        for (group in listOf("inputHashes", "artifactHashes", "interfaceHashes"))
            for ((path, expected) in manifest[group] as Map<String, String>) {
                val file = File(path).let { if (it.isAbsolute) it else File(root, path) }
                assertEquals(expected, MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it) }, "Stale GC/stats fixture $path")
            }
        assertEquals(false, manifest["statsEnabled"])
        val entries = (manifest["entries"] as List<List<String>>).associate { it[0] to it[1] }
        val rows = (json(File(directory, "oracle.json"))["rows"] as List<List<Any?>>).groupBy { it[0] as String }
        assertEquals(setOf("enabled", "minor", "major", "blocking", "clock"), rows.keys)
        assertEquals(15, rows.values.sumOf { it.size })
        for (stage in listOf("pre", "post")) for ((entry, cases) in rows)
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    assertEquals(true, json(File(directory, "$entry-$stage.audit.json"))["accepted"])
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val p = program(language, backend, entry, stage)
                    val name = entries.getValue(entry)
                    val callable = context.asValue(EntryValue(p, name, 1))
                    for ((_, input, answer) in cases) {
                        assertEquals((input as Number).toLong() + if (entry == "enabled") 0L else 1L, (answer as Number).toLong())
                        assertEquals(answer.toLong(), callable.execute(input.toLong()).asLong())
                        released(language)
                    }
                    assertTrue(callable.invokeMember("compile").asBoolean())
                    val target = p.entryTarget(name)
                    for ((_, input, answer) in cases.asReversed()) {
                        val before = p.diagnostics().getValue("compiledEntries") as Long
                        assertEquals((answer as Number).toLong(), callable.execute((input as Number).toLong()).asLong(), "$stage/$backend/$entry")
                        assertEquals(before + 1, p.diagnostics().getValue("compiledEntries"), "$stage/$backend/$entry exact entry")
                        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                        released(language)
                    }
                } finally { context.leave() }
            }
    }

    @Test fun directStatsLeafIsUnavailableAndDoesNotWriteItsBuffer() {
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val p = program(language, backend, "stats", stage)
                val address = ManagedAddress.fromByteArray(byteArrayOf(11, 22, 33, 44))
                val failure = assertThrows(RuntimeFault::class.java) {
                    Calls.target(p.hostEntryTarget(2), arrayOf(p.entryValue("originalStats"), arrayOf(address, 1L)))
                }
                assertEquals("GHC RTS statistics are unavailable on the JVM; getRTSStatsEnabled is false", failure.message)
                assertEquals(listOf(11L, 22L, 33L, 44L), (0L..3L).map { address.readWord8(it) })
                released(language)
            } finally { context.leave() }
        }
    }
}
