@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext

/** Strict fields can exclude thunks without imposing evaluation on their lazy neighbors. */
class ReferenceFieldTest {
    private val boxed = listOf("BoxedRep (Just Lifted)")
    private fun proof(kind: String, evaluated: Boolean, reps: List<String> = boxed) =
        mapOf("kind" to kind, "evaluated" to evaluated, "primReps" to reps)
    private fun variable(id: String): List<Any?> = listOf("var", id)
    private fun parameter(id: String) = mapOf("id" to id, "name" to id, "lifted" to false,
        "type" to "Int#", "coercion" to false)
    private fun lambda(id: String, body: List<Any?>): List<Any?> = listOf("lam", listOf(parameter(id)), body)
    private fun binding(id: String, body: List<Any?>) = mapOf("id" to id, "name" to id,
        "lifted" to true, "type" to "Synthetic", "arity" to if (body[0] == "lam") 1 else 0, "expr" to body)
    private fun apply(id: String, args: List<List<Any?>>, lifted: Boolean) =
        listOf("app", listOf("con", id, args.size), args, List(args.size) { lifted })
    private fun constructor(id: String, proofs: List<Map<String, Any>>, strict: List<Boolean>) = mapOf(
        "id" to id, "name" to id, "kind" to "boxed", "arity" to proofs.size, "tag" to 1,
        "fieldTypes" to proofs, "fieldReps" to proofs.map { it["primReps"] },
        "strictFields" to strict, "fieldLifted" to proofs.map { it["kind"] != "long" })
    private fun record() = constructor("Record", listOf(proof("data", true), proof("data", false),
        proof("closure", true), proof("closure", false), proof("object", true), proof("object", false)),
        listOf(true, false, true, false, true, false))
    private fun module(typed: Boolean): Map<String, Any?> {
        val box = apply("Box", listOf(variable("input")), false)
        val built = apply("Record", listOf(variable("shared"), variable("bottom"), variable("identity"),
            variable("bottom"), variable("shared"), variable("bottom")), true)
        val body = listOf("let", false, listOf(binding("shared", box)), built)
        val constructors = listOf(record(), constructor("Box", listOf(proof("long", true, listOf("IntRep"))), listOf(false)))
        return mapOf("schema" to 1, "ghc" to "9.14.1", "module" to "Synthetic.ReferenceFields", "instrument" to true,
            "constructors" to if (typed) constructors else constructors.map { it - "fieldTypes" },
            "bindings" to listOf(binding("bottom", variable("bottom")), binding("identity", lambda("x", variable("x"))),
                binding("entry", lambda("input", body))))
    }
    private fun run(program: ExecutableProgram, n: Long): DataValue =
        Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue("entry"), arrayOf<Any?>(n))) as DataValue
    private fun compile(target: RootCallTarget) {
        val type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
        type.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target))
    }
    @Test fun strictDataAndClosureFieldsArePreciseWhileLazyAndPolymorphicFieldsRemainGeneric() {
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (typed in listOf(true, false)) for (backend in listOf("ast", "bytecode")) {
                    val program = if (backend == "ast") Program(language, module(typed)) else BytecodeProgram(language, module(typed))
                    fun check(n: Long) {
                        val result = run(program, n)
                        val first = result.layout.read(result, 0) as DataValue
                        assertEquals(n, first.layout.readLong(first, 0), backend)
                        assertSame(first, result.layout.read(result, 4), "Shared strict field identity survives")
                        assertSame(program.entryValue("identity"), result.layout.read(result, 2))
                        for (i in listOf(1, 3, 5)) {
                            val lazy = result.layout.read(result, i) as Thunk
                            assertEquals(0, lazy.state, "A field's type must not force its lazy value")
                        }
                        val expected = listOf(if (typed) DataValue::class.java else Any::class.java, Any::class.java,
                            if (typed) Closure::class.java else Any::class.java, Any::class.java, Any::class.java, Any::class.java)
                        assertEquals(expected, result.javaClass.declaredFields.sortedBy { it.name }.map { it.type }, backend)
                    }
                    repeat(30) { check(it.toLong()) }
                    compile(program.entryTarget("entry"))
                    val before = program.diagnostics()["compiledEntries"] as Long
                    for (n in listOf(3_000_000_017L, Long.MIN_VALUE, Long.MAX_VALUE, 0L)) check(n)
                    assertTrue((program.diagnostics()["compiledEntries"] as Long) > before, backend)
                    assertEquals(0L, program.diagnostics()["blackholes"], backend)
                }
            } finally { context.leave() }
        }
    }
    @Test fun constructorProofsMustAlignWithBothStorageAndWorkerEvaluation() {
        val good = record()
        val proofs = good["fieldTypes"] as List<Map<String, Any>>
        assertEquals(listOf(DataValue::class.java, null, Closure::class.java, null, null, null),
            CoreFields(good).referenceTypes.toList())
        for (bad in listOf(good + ("fieldTypes" to proofs.dropLast(1)),
            good + ("fieldTypes" to (proofs.take(1) + (proofs[1] + ("evaluated" to true)) + proofs.drop(2))),
            good + ("fieldTypes" to (listOf(proof("long", true, listOf("IntRep"))) + proofs.drop(1))),
            good + ("fieldTypes" to listOf(null, *proofs.drop(1).toTypedArray())),
            good + ("fieldLifted" to listOf(false, true, true, true, true, true)),
            good + ("fieldLifted" to listOf("true", true, true, true, true, true)),
            good + ("arity" to 6.5), good + ("arity" to -1))) {
            assertThrows(RuntimeFault::class.java) { CoreFields(bad) }
        }
        assertTrue(CoreFields(good - "fieldTypes").referenceTypes.all { it == null })
    }

    @Test fun typedConstructorPapKeepsItsStrictPrefixLazyUntilSaturation() {
        val partial = listOf("app", listOf("con", "Record", 6), listOf(variable("shared")), listOf(true))
        val body = listOf("let", false, listOf(binding("shared", apply("Box", listOf(variable("input")), false))), partial)
        val original = module(true)
        val data = original + ("bindings" to ((original["bindings"] as List<Map<String, Any?>>).dropLast(1) +
            binding("entry", lambda("input", body))))
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (backend in listOf("ast", "bytecode")) {
                    val program = if (backend == "ast") Program(language, data) else BytecodeProgram(language, data)
                    val bottom = program.entryValue("bottom") as Thunk
                    val identity = program.entryValue("identity")
                    repeat(30) { index ->
                        val n = 3_000_000_017L + index
                        val pap = Calls.target(program.hostEntryTarget(1),
                            arrayOf(program.entryValue("entry"), arrayOf<Any?>(n))) as Closure
                        val prefix = pap.supplied[0] as Thunk
                        assertEquals(0, prefix.state, backend)
                        val result = Calls.target(program.hostEntryTarget(5),
                            arrayOf(pap, arrayOf(bottom, identity, bottom, identity, bottom))) as DataValue
                        assertEquals(2, prefix.state, backend)
                        assertSame(prefix.value, result.layout.read(result, 0), backend)
                        val value = prefix.value as DataValue
                        assertEquals(n, value.layout.readLong(value, 0), backend)
                        assertEquals(0, bottom.state, backend)
                    }
                    assertEquals(0L, program.diagnostics()["blackholes"], backend)
                }
            } finally { context.leave() }
        }
    }
}
