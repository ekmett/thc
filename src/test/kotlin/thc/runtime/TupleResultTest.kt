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

class TupleResultTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private fun compile(target: RootCallTarget, label: String) {
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target), label)
    }
    private fun valid(target: RootCallTarget, label: String) {
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target), label)
    }
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.results.depth)
        assertEquals(0, state.results.retainedReferences())
        assertEquals(0, state.arguments.depth)
        assertEquals(0, state.arguments.retainedReferences())
    }
    private fun context(inlining: Boolean) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()
    @Test fun nativeProducersForwardersAndOutstandingResultsRunWithoutInlining() = checkNative(false)
    @Test fun inlinedNativeResultsRemainVirtual() = checkNative(true)
    @Test fun resultOnlyNativeBoundariesWithoutInlining() = checkReturnAudit(false)
    @Test fun resultOnlyNativeBoundariesWithInlining() = checkReturnAudit(true)
    private fun checkNative(inlining: Boolean) {
        val rows = File(root, "build/aggregate-native/oracle.tsv").readLines().map { it.split('\t') }
        for (stage in listOf("aggregate-core", "aggregate-post-core")) {
            val module = Json.parse(File(root, "build/$stage/AggregateFrontier.json").readText()) as Map<String, Any?>
            for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    for (entry in listOf("tupleOutstanding", "tupleZeroLazy", "coldTuple")) {
                        val linked = CoreModules.reachable(module, entry)
                        val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                        fun checkRows() {
                            for (row in rows.filter { it[0] == entry }) {
                                val result = Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue(entry), arrayOf(row[1].toLong())))
                                assertEquals(row[2].toLong(), result, "$stage/$backend/$entry/${row[1]}")
                                released(language)
                            }
                        }
                        checkRows()
                        if (entry == "coldTuple") assertEquals(540820L, Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue(entry), arrayOf(31337L))))
                        val functions = (linked["bindings"] as List<Map<String, Any?>>).filter { (it["expr"] as List<*>)[0] == "lam" }
                        functions.forEach { compile(program.entryTarget(it["id"] as String), "$stage/$backend/$entry/${it["id"]}") }
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        checkRows()
                        assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before)
                        valid(program.entryTarget((linked["bindings"] as List<Map<String, Any?>>).single { it["name"] == entry }["id"] as String), "$stage/$backend/$entry after compiled execution")
                        val allocations = language.handoffState.get().results.allocations
                        checkRows()
                        assertEquals(allocations, language.handoffState.get().results.allocations)
                    }
                } finally { context.leave() }
            }
        }
    }
    private fun checkReturnAudit(inlining: Boolean) {
        val rows = File(root, "build/tuple-return/oracle.tsv").readLines().map { it.split('\t') }
        for (stage in listOf("pre-core", "post-core")) {
            val module = Json.parse(File(root, "build/tuple-return/$stage/TupleReturnAudit.json").readText()) as Map<String, Any?>
            for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val reached = rows.map { it[0] }.distinct().flatMap { name ->
                        CoreModules.reachable(module, name)["bindings"] as List<Map<String, Any?>>
                    }.map { it["id"] }.toSet()
                    val bindings = (module["bindings"] as List<Map<String, Any?>>).filter { it["id"] in reached }
                    val linked = module + ("bindings" to bindings)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                    fun checkRows() {
                        for (row in rows) {
                            val result = Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue(row[0]), arrayOf(row[1].toLong())))
                            assertEquals(row[2].toLong(), result, "$stage/$backend/${row[0]}/${row[1]}")
                            released(language)
                        }
                    }
                    checkRows()
                    // The shared host PIC becomes generic across these thirteen entries.
                    // Warm every root through that settled ABI before compiling profiles.
                    checkRows()
                    bindings.filter { (it["expr"] as List<*>)[0] == "lam" }.forEach {
                        compile(program.entryTarget(it["id"] as String), "$stage/$backend/${it["name"]}")
                    }
                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    checkRows()
                    assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before)
                    for (name in rows.map { it[0] }.distinct()) {
                        valid(program.entryTarget(bindings.single { it["name"] == name }["id"] as String), "$stage/$backend/$name after compiled execution")
                    }
                    val allocations = language.handoffState.get().results.allocations
                    checkRows()
                    assertEquals(allocations, language.handoffState.get().results.allocations)
                } finally { context.leave() }
            }
        }
    }

}
