// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.Main.executionContext

class StateLambdaJoinTest {
    private val long = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val void = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private fun parameter(id: String, rep: Map<String, Any?>) = mapOf("id" to id, "name" to id, "lifted" to false, "rep" to rep)
    private fun variable(id: String, rep: Map<String, Any?> = long) = listOf("var", id, mapOf("rep" to rep))
    private fun literal(n: Long) = listOf("lit", "int", n.toString(), mapOf("rep" to long))
    private fun lambda(id: String, rep: Map<String, Any?>, body: List<Any?>) =
        listOf("lam", listOf(parameter(id, rep)), body, mapOf("rep" to closure, "resultRep" to long))
    private fun app(fn: List<Any?>, vararg args: List<Any?>) =
        listOf("app", fn, args.toList(), List(args.size) { false }, false, false, mapOf("rep" to long))
    private fun plus(a: List<Any?>, b: List<Any?>) = app(listOf("prim", "+#"), a, b)
    private fun jump() = app(variable("finish", closure), variable("input"))
    private fun stateApplication() = app(lambda("state", void, jump()), listOf("void", mapOf("rep" to void)))
    private fun binding(id: String, expr: List<Any?>) = mapOf("id" to id, "name" to id, "lifted" to true,
        "arity" to 1, "rep" to closure, "expr" to expr)
    private fun module(body: List<Any?>): Map<String, Any?> {
        val finish = binding("finish", lambda("value", long, plus(variable("value"), literal(17)))) +
            mapOf("joinValueArity" to 1, "joinResultRep" to long)
        val region = listOf("let", false, listOf(finish), body, mapOf("rep" to long))
        return mapOf("bindings" to listOf(binding("entry", lambda("input", long, region))), "instrument" to true)
    }

    @Test fun ordinaryClosuresAndNonTailStateCallsStillReject() {
        val ordinary = app(lambda("value", long, jump()), literal(0))
        val nonTail = plus(stateApplication(), literal(1))
        val effects = stateApplication().toMutableList().also {
            it[2] = listOf(app(listOf("prim", "noDuplicate#"), listOf("void", mapOf("rep" to void))))
        }
        for (body in listOf(ordinary, nonTail, effects)) for (backend in listOf("ast", "bytecode"))
            executionContext().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val failure = assertThrows(RuntimeFault::class.java) {
                        if (backend == "ast") Program(language, module(body)) else BytecodeProgram(language, module(body))
                    }
                    assertTrue(failure.message.orEmpty().contains("Local join"))
                } finally { context.leave() }
            }
    }

    @Test fun stateBindingRetainsTheOriginalLexicalScopeAndMetadata() {
        val original = stateApplication().toMutableList()
        val metadata = mapOf("rep" to long, "source" to "state-source", "sourceNotes" to listOf("state-source"))
        original[6] = metadata
        val reduced = CoreStateApplications.inline(original)!!
        assertEquals("case", reduced[0])
        assertEquals("state", reduced[2])
        assertEquals(metadata + ("binder" to parameter("state", void)), reduced[4])
        assertEquals(jump(), ((reduced[3] as List<*>).single() as List<*>)[3])
        assertNull(CoreStateApplications.inline(app(lambda("value", long, jump()), literal(0))))
    }
}
