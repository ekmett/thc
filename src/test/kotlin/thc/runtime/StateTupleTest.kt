@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File

class StateTupleTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private fun module(stage: String) = Json.parse(File(root, "build/state-tuple/$stage-core/StateTupleAudit.json").readText()) as Map<String, Any?>
    private fun context(inlining: Boolean = true) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target, "initial compilation")
    }
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
    }
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun call(program: ExecutableProgram, entry: String, x: Long): Any? =
        Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue(entry), arrayOf(x)))

    @Test fun nativeStateTuplesExecuteInlined() = native(true)
    @Test fun nativeStateTuplesExecuteAcrossResidualCalls() = native(false)
    private fun native(inlining: Boolean) {
        val rows = File(root, "build/state-tuple/oracle.tsv").readLines().map { it.split('\t') }
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (entry in rows.map { it[0] }.distinct()) {
                    val linked = CoreModules.reachable(module(stage), entry)
                    val bindings = linked["bindings"] as List<Map<String, Any?>>
                    val program = program(language, linked, backend)
                    val target = program.entryTarget(bindings.single { it["name"] == entry }["id"] as String)
                    fun checkRows(compiled: Boolean) {
                        for (row in rows.filter { it[0] == entry }) {
                            val label = "$stage/$backend/$entry/${row[1]}/inlining=$inlining"
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            assertEquals(row[2].toLong(), call(program, entry, row[1].toLong()), label)
                            if (compiled) {
                                assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before, label)
                                valid(target, label)
                            }
                            released(language)
                        }
                    }
                    checkRows(false)
                    bindings.filter { (it["expr"] as List<*>)[0] == "lam" }.forEach { compile(program.entryTarget(it["id"] as String)) }
                    checkRows(true)
                }
            } finally { context.leave() }
        }
    }

    @Test fun ignoredStateFieldStillExecutesAndThrowsBeforeTupleCompletion() {
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val linked = CoreModules.reachable(module(stage), "effectCase")
                val program = program(language, linked, backend)
                val bindings = linked["bindings"] as List<Map<String, Any?>>
                val target = program.entryTarget(bindings.single { it["name"] == "effectCase" }["id"] as String)
                assertEquals(7L, call(program, "effectCase", 7L))
                assertThrows(GuestException::class.java) { call(program, "effectCase", -1L) }; released(language)
                compile(target)
                assertEquals(9L, call(program, "effectCase", 9L)); valid(target, "$stage/$backend")
                assertThrows(GuestException::class.java) { call(program, "effectCase", -7L) }; released(language)
            } finally { context.leave() }
        }
    }

    @Test fun logicalStateAndNestedEmptyTupleFieldsHaveNoPhysicalSlots() = context().use { context ->
        context.initialize("thc"); context.enter()
        try {
            val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
            val bindings = module("pre")["bindings"] as List<Map<String, Any?>>
            fun shape(name: String): TupleShape = TupleShape(CoreRepresentations.parse(
                ((bindings.single { it["name"] == name }["expr"] as List<*>)[3] as Map<*, *>)["resultRep"]), language)
            val pair = shape("pair")
            assertEquals(2, pair.components.size); assertEquals(1, pair.width); assertArrayEquals(intArrayOf(0, 0), pair.offsets)
            val zero = shape("zero")
            assertEquals(3, zero.components.size); assertEquals(0, zero.width); assertEquals(emptyList<String>(), zero.layout.reps)
            assertFalse(TupleShape.compatible(zero.components[0], zero.components[1]))
            assertArrayEquals(intArrayOf(0, 0, 0), zero.offsets)
            val bytes = shape("byteShape")
            assertEquals(1, bytes.width); assertEquals(CoreKind.OBJECT, bytes.leaves.single().kind)
            assertEquals(listOf("BoxedRep (Just Unlifted)"), bytes.leaves.single().primReps)
            assertEquals(listOf("reference"), bytes.layout.reps)
            val lazy = shape("lazyPair")
            assertEquals(1, lazy.width); assertFalse(lazy.leaves.single().evaluated)
        } finally { context.leave() }
    }

    @Test fun zeroWidthStateCannotBeReinterpretedAsAnEmptyTuple() {
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val module = module("pre")
                val pair = (module["bindings"] as List<Map<String, Any?>>).single { it["name"] == "pair" }["expr"] as List<*>
                val proof = (pair[3] as Map<*, *>)["resultRep"] as MutableMap<String, Any?>
                val fields = proof["components"] as MutableList<Any?>
                fields[0] = mapOf("kind" to "unknown", "evaluated" to true, "primReps" to emptyList<String>(),
                    "aggregate" to "unboxed-tuple", "components" to emptyList<Any>())
                assertThrows(RuntimeFault::class.java) { program(language, CoreModules.reachable(module, "pairCase"), backend) }
            } finally { context.leave() }
        }
    }
}
