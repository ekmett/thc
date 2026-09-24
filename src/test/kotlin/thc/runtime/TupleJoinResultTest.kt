// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File

class TupleJoinResultTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private fun module(stage: String = "pre") = Json.parse(File(root, "build/tuple-join/$stage-core/TupleJoinAudit.json").readText()) as Map<String, Any?>
    private fun context(inlining: Boolean) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()
    private fun valid(target: RootCallTarget) = assertEquals(true,
        Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("isValidLastTier").invoke(target))
    private fun compile(target: RootCallTarget) {
        Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target)
    }
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun released(language: Language) {
        assertEquals(0, language.handoffState.get().results.depth)
        assertEquals(0, language.handoffState.get().results.retainedReferences())
    }
    @Test fun genuineTupleJoinsRunWithResidualCalls() = checkNative(false)
    @Test fun genuineTupleJoinsRunWithInlining() = checkNative(true)
    private fun checkNative(inlining: Boolean) {
        val rows = File(root, "build/tuple-join/oracle.tsv").readLines().map { it.split('\t') }
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val module = module(stage)
                val reached = rows.map { it[0] }.distinct().flatMap { CoreModules.reachable(module, it)["bindings"] as List<Map<String, Any?>> }.map { it["id"] }.toSet()
                val bindings = (module["bindings"] as List<Map<String, Any?>>).filter { it["id"] in reached }
                val program = program(language, module + ("bindings" to bindings), backend)
                fun checkRows() {
                    for (row in rows) {
                        val result = Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue(row[0]), arrayOf(row[1].toLong())))
                        assertEquals(row[2].toLong(), result, "$stage/$backend/${row[0]}/${row[1]}")
                        released(language)
                    }
                }
                checkRows(); checkRows()
                bindings.filter { (it["expr"] as List<*>)[0] == "lam" }.forEach { compile(program.entryTarget(it["id"] as String)) }
                checkRows()
                rows.map { it[0] }.distinct().forEach { valid(program.entryTarget(bindings.single { b -> b["name"] == it }["id"] as String)) }
                assertTrue((program.diagnostics().getValue("localJoinTransfers") as Number).toLong() > 100_000)
                assertEquals(0L, (program.diagnostics().getValue("tailBounces") as Number).toLong())
                assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
            } finally { context.leave() }
        }
    }

    private fun walk(value: Any?): List<MutableMap<String, Any?>> = when (value) {
        is Map<*, *> -> listOf(value as MutableMap<String, Any?>) + value.values.flatMap(::walk)
        is List<*> -> value.flatMap(::walk)
        else -> emptyList()
    }
    private fun wrap(proof: MutableMap<String, Any?>): Map<String, Any?> = mapOf("kind" to "unknown", "evaluated" to true,
        "aggregate" to "unboxed-tuple", "primReps" to proof["primReps"], "components" to listOf(proof))
    @Test fun astTupleJoinScratchReferencesAreClearedAfterCopying() = context(false).use { context ->
        context.initialize("thc"); context.enter()
        try {
            val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
            val leaf = CoreRepresentation(CoreKind.OBJECT, false, true, listOf("BoxedRep (Just Lifted)"))
            val proof = CoreRepresentation(CoreKind.UNKNOWN, true, true, leaf.primReps, listOf(leaf))
            val shape = TupleShape(proof, language)
            val layout = FrameLayout(); val source = layout.bind("private tuple result"); val destination = layout.bind("caller result")
            val selector = layout.bind("selector"); val unused = layout.bind("scalar result")
            val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), layout.build())
            val marker = Any(); val slots = intArrayOf(source)
            val value = object : Expr() { override fun execute(frame: VirtualFrame): Any = marker }
            val region = LocalJoinRegion(Any(), selector, unused, arrayOf(TupleConstruct(shape, arrayOf(value))), proof, false, shape, slots)
            region.executeTuple(frame, intArrayOf(destination), 0)
            assertSame(marker, frame.getObject(destination))
            assertFalse(frame.isObject(source), "Private reference result slot must be cleared")
            region.executeTuple(frame, slots, 0)
            assertSame(marker, frame.getObject(source), "An explicit aliased destination must survive cleanup")
        } finally { context.leave() }
    }
    @Test fun tupleJoinProofsAndUnusedAggregateFormalsAreCheckedAtLoad() {
        val mutations = listOf<(MutableMap<String, Any?>) -> Unit>(
            { join -> val p = join["joinResultRep"] as MutableMap<String, Any?>; val c = p["components"] as MutableList<Any?>; c[0] = wrap(c[0] as MutableMap<String, Any?>) },
            { join -> val rhs = join["expr"] as List<*>; val p = (rhs[3] as MutableMap<String, Any?>)["resultRep"] as MutableMap<String, Any?>; val c = p["components"] as MutableList<Any?>; c[0] = wrap(c[0] as MutableMap<String, Any?>) },
            { join -> val p = ((join["expr"] as List<*>)[1] as List<MutableMap<String, Any?>>)[0]; p["rep"] = wrap(p["rep"] as MutableMap<String, Any?>) },
            { join -> (join["joinResultRep"] as MutableMap<String, Any?>)["components"] = null }
        )
        for (backend in listOf("ast", "bytecode")) context(false).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                mutations.forEachIndexed { index, mutate ->
                    val module = module()
                    val forward = (module["bindings"] as List<Map<String, Any?>>).single { it["name"] == "forward" }
                    mutate(walk(forward).first { "joinValueArity" in it })
                    assertThrows(RuntimeFault::class.java, { program(language, CoreModules.reachable(module, "forwardCase"), backend) }, "$backend mutation $index")
                }
            } finally { context.leave() }
        }
    }

    @Test fun recursiveJoinShadowsAnOuterTupleButZeroArityJoinCannotCaptureIt() {
        for (backend in listOf("ast", "bytecode")) context(false).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (capture in listOf(false, true)) {
                    val module = module()
                    val bindings = module["bindings"] as List<Map<String, Any?>>
                    val producer = bindings.single { it["name"] == "recursive" }
                    val rhs = producer["expr"] as MutableList<Any?>
                    val original = rhs[2]
                    val result = (rhs[3] as Map<String, Any?>)["resultRep"] as Map<String, Any?>
                    val join = walk(original).first { "joinValueArity" in it }
                    val tupleId = join["id"] as String
                    val x = (rhs[1] as List<Map<String, Any?>>).last()
                    val forward = bindings.single { it["name"] == "forward" }
                    val scrutinee = listOf("app", listOf("var", forward["id"], mapOf("rep" to forward["rep"])),
                        listOf(listOf("var", x["id"], mapOf("rep" to x["rep"]))), listOf(false), false, false, mapOf("rep" to result))
                    val body = if (!capture) original else {
                        val zero = mapOf("id" to "zero", "name" to "zero", "lifted" to false, "rep" to result,
                            "joinValueArity" to 0, "joinResultRep" to result, "info" to mapOf("joinArity" to 0),
                            "expr" to listOf("var", tupleId, mapOf("rep" to result)))
                        listOf("let", false, listOf(zero), listOf("var", "zero", mapOf("rep" to result)), mapOf("rep" to result))
                    }
                    rhs[2] = listOf("case", scrutinee, tupleId, listOf(listOf("default", null, emptyList<String>(), body, mapOf("binders" to emptyList<Any>()))),
                        mapOf("rep" to result, "binder" to mapOf("id" to tupleId, "lifted" to false, "rep" to (result + ("evaluated" to true)))))
                    val linked = CoreModules.reachable(module, "recursiveCase")
                    if (capture) assertThrows(UnsupportedCore::class.java) { program(language, linked, backend) }
                    else {
                        val program = program(language, linked, backend)
                        assertEquals(4123L, Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue("recursiveCase"), arrayOf(4097L))))
                        released(language)
                    }
                }
            } finally { context.leave() }
        }
    }
}
