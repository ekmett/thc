// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import thc.Main.executionContext

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.security.MessageDigest

class LargeLiteralCaseNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/core-continuation")

    private fun original(): Map<String, Any?> {
        val hashes = Json.parse(File(directory, "literal-manifest.json").readText()) as Map<String, String>
        hashes.forEach { (path, expected) ->
            val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Stale original literal-case fixture: $path")
        }
        val module = Json.parse(File(directory, "core/LargeLiteralCaseAudit.json").readText()) as Map<String, Any?>
        return CoreModules.reachable(module, listOf("largeInt", "largeWordCheck", "largeLazy", "boxInt"), true) +
            ("instrument" to true)
    }

    private fun each(module: Map<String, Any?>, action: (String, ExecutableProgram) -> Unit) {
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (backend in listOf("ast", "bytecode")) {
                    val program = if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
                    action(backend, program)
                }
            } finally { context.leave() }
        }
    }

    private fun call(program: ExecutableProgram, entry: String, input: Any): Any? =
        Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue(entry), arrayOf(input)))

    private fun compile(target: RootCallTarget) {
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target))
        val runtime = Truffle.getRuntime()
        runtime.javaClass.getMethod("bypassedInstalledCode", type).invoke(runtime, target)
    }

    @Test fun originalSignedWordAndTupleCasesMatchNativeBeforeAndOnFirstCompiledEntry() = each(original()) { backend, program ->
        val rows = File(directory, "literal-native-output.txt").readLines().map { it.split('\t') }
        assertEquals(52, rows.size)
        for ((kind, input, expected) in rows) {
            val entry = if (kind == "int") "largeInt" else "largeWordCheck"
            assertEquals(expected.toLong(), call(program, entry, input.toLong()), "$backend/$kind/$input")
        }
        if (backend == "bytecode") assertTrue((program as BytecodeProgram).bytecodeDump().contains("LiteralBelow"))
        for ((kind, entry) in listOf("int" to "largeInt", "word" to "largeWordCheck")) {
            compile(program.entryTarget(entry))
            val before = program.diagnostics().getValue("compiledEntries") as Long
            val first = rows.last { it[0] == kind }
            assertEquals(first[2].toLong(), call(program, entry, first[1].toLong()), "$backend/$entry first compiled call")
            assertTrue((program.diagnostics().getValue("compiledEntries") as Long) > before)
            for ((_, input, expected) in rows.filter { it[0] == kind })
                assertEquals(expected.toLong(), call(program, entry, input.toLong()), "$backend/$entry/$input")
        }
    }

    @Test fun originalCaseForcesSharedInputExactlyOnceAndMemoizesFailure() = each(original()) { backend, program ->
        val boxed = call(program, "boxInt", 42L)!!
        assertEquals(115L, call(program, "largeLazy", boxed))
        var effects = 0
        val value = Thunk(object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Any { effects++; return boxed }
        }.callTarget, null)
        // Demand the new thunk through the original boxed wrapper, then enter
        // the compiled numeric case whose branch lowering this test exercises.
        compile(program.entryTarget("largeInt"))
        val before = program.diagnostics().getValue("compiledEntries") as Long
        assertEquals(115L, call(program, "largeLazy", value), "$backend first compiled demand")
        assertTrue((program.diagnostics().getValue("compiledEntries") as Long) > before, "$backend first compiled demand")
        assertEquals(115L, call(program, "largeLazy", value))
        assertEquals(1, effects)
        val failed = Thunk(object : RootNode(null) {
            override fun execute(frame: VirtualFrame): Nothing { effects++; throw RuntimeFault("literal scrutinee failed") }
        }.callTarget, null)
        repeat(2) {
            assertEquals("literal scrutinee failed", assertThrows(RuntimeFault::class.java) {
                call(program, "largeLazy", failed)
            }.message)
        }
        assertEquals(2, effects)
    }

    @Test fun missingDefaultStillFailsAndDuplicateLabelsKeepTheirOriginalOrder() {
        val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
        val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
        val parameter = mapOf("id" to "x", "name" to "x", "lifted" to false, "coercion" to false, "rep" to long)
        fun number(n: Long) = listOf("lit", "int", n.toString())
        fun arm(n: Long, result: Long): List<Any?> = listOf("lit", listOf("int", n.toString()), emptyList<String>(), number(result))
        fun binding(id: String, arms: List<List<Any?>>): Map<String, Any?> = mapOf("id" to id, "name" to id,
            "lifted" to true, "expr" to listOf("lam", listOf(parameter),
                listOf("case", listOf("var", "x"), "selected", arms,
                    mapOf("binder" to (parameter + ("id" to "selected")), "rep" to long)),
                mapOf("rep" to closure, "resultRep" to long)))
        // Deliberately unordered labels. Reordering distinct labels is safe;
        // malformed duplicates must retain the pre-existing first-match rule.
        val arms = (0L..20L).reversed().map { arm(it * 3, it + 100) }
        each(mapOf("instrument" to true, "bindings" to listOf(binding("partial", arms),
            binding("duplicate", arms + listOf(arm(0, 999)))))) { backend, program ->
            for (n in 0L..20L) assertEquals(n + 100, call(program, "partial", n * 3), "$backend/$n")
            assertEquals(100L, call(program, "duplicate", 0L))
            assertTrue(assertThrows(RuntimeFault::class.java) { call(program, "partial", -1L) }
                .message.orEmpty().contains("Non-exhaustive Core case"))
        }
    }
}
