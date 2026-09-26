// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File

class SumJoinResultTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private fun module(stage: String = "pre") = Json.parse(File(root,
        "build/sum-join/$stage/core/SumJoinAudit.json").readText()) as Map<String, Any?>
    private fun context(inlining: Boolean) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun compile(target: RootCallTarget) {
        val optimized = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        optimized.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, optimized.getMethod("isValidLastTier").invoke(target))
    }
    private fun released(language: Language) {
        assertEquals(0, language.handoffState.get().results.depth)
        assertEquals(0, language.handoffState.get().results.retainedReferences())
    }
    private fun bindings(module: Map<String, Any?>) = module["bindings"] as List<Map<String, Any?>>
    private fun walk(value: Any?): List<MutableMap<String, Any?>> = when (value) {
        is Map<*, *> -> listOf(value as MutableMap<String, Any?>) + value.values.flatMap(::walk)
        is List<*> -> value.flatMap(::walk)
        else -> emptyList()
    }

    @Test fun originalSumJoinsMatchNativeWithoutInlining() = checkNative(false)
    @Test fun originalSumJoinsMatchNativeWithInlining() = checkNative(true)
    private fun checkNative(inlining: Boolean) {
        val rows = File(root, "build/sum-join/oracle.tsv").readLines().map { it.split('\t') }
        assertEquals(18, rows.size)
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val module = module(stage)
                val ids = rows.map { it[0] }.distinct().flatMap {
                    bindings(CoreModules.reachable(module, it)) }.map { it["id"] }.toSet()
                val selected = bindings(module).filter { it["id"] in ids }
                val program = program(language, module + ("bindings" to selected), backend)
                fun checkRows() {
                    for ((name, input, expected) in rows) {
                        val x = input.toLong()
                        val model = when (name) {
                            "forwardCase" -> if (x <= 0) -11L else (x + 18L) * 3L
                            "recursiveCase" -> if (x == 0L) -13L else (if (x < 0) -x else 2L * x) + 19L
                            "nestedCase" -> if (x <= 0) -23L else (x + 7L) * 5L
                            else -> error(name)
                        }
                        assertEquals(model, expected.toLong(), "Independent native model: $name/$x")
                        assertEquals(model, Calls.target(program.hostEntryTarget(1),
                            arrayOf(program.entryValue(name), arrayOf(x))), "$stage/$backend/$name/$x")
                        released(language)
                    }
                }
                checkRows()
                selected.filter { (it["expr"] as List<*>)[0] == "lam" }.forEach {
                    compile(program.entryTarget(it["id"] as String)) }
                checkRows()
                assertTrue((program.diagnostics().getValue("localJoinTransfers") as Number).toLong() > 40_000)
                assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                assertEquals(0L, (program.diagnostics().getValue("blackholes") as Number).toLong())
            } finally { context.leave() }
        }
    }

    @Test fun zeroAritySumJoinRetainsLazyIdentityAndClearsInactiveReferences() {
        for (backend in listOf("ast", "bytecode")) context(false).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val module = module()
                val source = bindings(module).single { it["name"] == "forward" }
                val lambda = source["expr"] as MutableList<Any?>
                val proof = (lambda[3] as Map<String, Any?>)["resultRep"] as Map<String, Any?>
                val zero = mapOf("id" to "zero-sum", "name" to "zeroSum", "lifted" to false, "rep" to proof,
                    "joinValueArity" to 0, "joinResultRep" to proof, "info" to mapOf("joinArity" to 0), "expr" to lambda[2])
                lambda[2] = listOf("let", false, listOf(zero), listOf("var", "zero-sum", mapOf("rep" to proof)), mapOf("rep" to proof))
                val program = program(language, CoreModules.reachable(module, "forwardCase"), backend)
                val target = program.entryTarget(source["id"] as String)
                val shape = TupleShape(CoreRepresentations.parse(proof), language)
                val layout = FrameLayout()
                val slots = IntArray(shape.width) { layout.bind("sum result $it") }
                val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), layout.build())
                fun call(x: Long): Any? {
                    shape.consume(frame, Calls.target(target, arrayOf(0L, x)), slots, 0)
                    assertEquals(if (x <= 0) 1L else 2L, frame.getLong(slots[0]))
                    val ref = frame.getObject(slots[1])
                    if (x <= 0) assertTrue(ref is Thunk) else {
                        assertNull(ref); assertEquals(x + 18L, frame.getLong(slots[2])) }
                    released(language)
                    return ref
                }
                val lazy = call(-1L)
                call(1L); compile(target)
                for (x in listOf(-41L, 41L, -1L, 0L, 9L)) {
                    val value = call(x)
                    if (x <= 0) assertSame(lazy, value)
                }
                assertEquals(0L, (program.diagnostics().getValue("blackholes") as Number).toLong())
            } finally { context.leave() }
        }
    }

    @Test fun mismatchedJoinProjectionAndLambdaResultFailAtLoad() {
        for (backend in listOf("ast", "bytecode")) for (lambdaProof in listOf(false, true)) context(false).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val module = module()
                val source = bindings(module).single { it["name"] == "forward" }
                val join = walk(source).first { "joinValueArity" in it }
                val proof = if (lambdaProof) ((join["expr"] as List<*>)[3] as MutableMap<String, Any?>)["resultRep"]
                    else join["joinResultRep"]
                (proof as MutableMap<String, Any?>)["alternativeSlots"] = listOf(listOf(2L), listOf(1L))
                assertThrows(RuntimeFault::class.java) {
                    program(language, CoreModules.reachable(module, "forwardCase"), backend) }
            } finally { context.leave() }
        }
    }
}
