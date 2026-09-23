package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language

class DiagnosticTupleTest {
    private val long = mapOf("kind" to "long", "evaluated" to true, "primReps" to listOf("IntRep"))
    private val void = mapOf("kind" to "void", "evaluated" to true, "primReps" to emptyList<String>())
    private val reference = mapOf("kind" to "data", "evaluated" to false,
        "primReps" to listOf("BoxedRep (Just Lifted)"))
    private val closure = mapOf("kind" to "closure", "evaluated" to true,
        "primReps" to listOf("BoxedRep (Just Lifted)"))
    private fun binder(id: String, rep: Map<String, Any>, lifted: Boolean = false) =
        mapOf("id" to id, "name" to id, "lifted" to lifted, "rep" to rep)
    private fun integer(n: Long) = listOf("lit", "int", n.toString(), mapOf("rep" to long))
    private fun module(empty: Boolean, diagnostic: Boolean): Map<String, Any?> {
        val components = if (empty) emptyList() else listOf(void, long, reference)
        val tuple = mapOf("kind" to "unknown", "evaluated" to true, "aggregate" to "unboxed-tuple",
            "primReps" to if (empty) emptyList() else listOf("IntRep", "BoxedRep (Just Lifted)"), "components" to components)
        fun apply(fn: List<Any?>, operands: List<List<Any?>>, rep: Map<String, Any>) =
            listOf("app", fn, operands, List(operands.size) { operands.size == 3 && it == 2 }, false, false, mapOf("rep" to rep))
        val good = apply(listOf("con", "Tuple", components.size),
            if (empty) emptyList() else listOf(listOf("void", mapOf("rep" to void)), integer(42),
                listOf("con", "Box", 0, mapOf("rep" to reference))), tuple)
        val cold = listOf("case", listOf("unsupported", "cold tuple"), "unavailable",
            listOf(listOf("default", null, emptyList<String>(), good)),
            mapOf("rep" to tuple, "binder" to binder("unavailable", tuple)))
        val branch = listOf("case", listOf("var", "n", mapOf("rep" to long)), "nCase", listOf(
            listOf("lit", listOf("int", "0"), emptyList<String>(), good),
            listOf("default", null, emptyList<String>(), cold)),
            mapOf("rep" to tuple, "binder" to binder("nCase", long)))
        val consume = listOf("case", apply(listOf("var", "producer", mapOf("rep" to closure)),
            listOf(listOf("var", "n", mapOf("rep" to long))), tuple), "result",
            listOf(listOf("default", null, emptyList<String>(), integer(9))),
            mapOf("rep" to long, "binder" to binder("result", tuple)))
        fun binding(id: String, body: List<Any?>, result: Map<String, Any>) = binder(id, closure, true) +
            ("expr" to listOf("lam", listOf(binder("n", long)), body,
                mapOf("rep" to closure, "resultRep" to result)))
        return mapOf("instrument" to true, "diagnosticUnsupported" to diagnostic,
            "constructors" to listOf(mapOf("id" to "Tuple", "name" to "Tuple", "kind" to "unboxed-tuple",
                "arity" to components.size), mapOf("id" to "Box", "name" to "Box", "kind" to "boxed", "arity" to 0,
                "fieldReps" to emptyList<Any>(), "strictFields" to emptyList<Boolean>(), "fieldLifted" to emptyList<Boolean>())),
            "bindings" to listOf(binding("producer", branch, tuple), binding("entry", consume, long)))
    }
    private fun program(language: Language, input: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, input) else BytecodeProgram(language, input)
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target))

    @Test fun coldUnsupportedTupleLoadsOnlyInDiagnosticModeAndTrapsWhenDemanded() {
        for (backend in listOf("ast", "bytecode")) for (empty in listOf(false, true)) for (inlining in listOf(false, true)) {
            Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", inlining.toString()).build().use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        assertThrows(UnsupportedCore::class.java) { program(language, module(empty, false), backend) }
                        val p = program(language, module(empty, true), backend)
                        fun call(n: Long) = Calls.target(p.hostEntryTarget(1), arrayOf(p.entryValue("entry"), arrayOf(n)))
                        fun traps() = (p.diagnostics().getValue("unsupportedTraps") as Number).toLong()
                        repeat(10) { assertEquals(9L, call(0)) }
                        assertEquals(0L, traps())
                        val target = p.entryTarget("entry")
                        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                        valid(target)
                        val before = (p.diagnostics().getValue("compiledEntries") as Number).toLong()
                        assertEquals(9L, call(0)); valid(target)
                        assertTrue((p.diagnostics().getValue("compiledEntries") as Number).toLong() > before)
                        val results = language.handoffState.get().results
                        if (!inlining) assertTrue(results.allocations > 0)
                        val failure = assertThrows(RuntimeFault::class.java) { call(1) }
                        assertEquals("Diagnostic unsupported path reached: Unsupported Core node unsupported", failure.message)
                        assertEquals(1L, traps())
                        assertEquals(0, results.depth); assertEquals(0, results.retainedReferences())
                        assertEquals(9L, call(0))
                        assertEquals(0, results.depth); assertEquals(0, results.retainedReferences())
                    } finally { context.leave() }
                }
        }
    }
}
