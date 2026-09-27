// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CoreFreeVariablesTest {
    private fun variable(id: String): List<Any?> = listOf("var", id)
    private fun apply(vararg values: List<Any?>): List<Any?> = listOf("app", values.first(), values.drop(1))
    private fun lambda(ids: List<String>, body: List<Any?>): List<Any?> = listOf("lam", ids.map { mapOf("id" to it) }, body)
    private fun binding(id: String, body: List<Any?>) = mapOf("id" to id, "expr" to body)
    private fun local(recursive: Boolean, bindings: List<Map<String, Any?>>, body: List<Any?>): List<Any?> =
        listOf("let", recursive, bindings, body)
    private fun alternative(ids: List<String>, body: List<Any?>): List<Any?> = listOf("default", null, ids, body)
    private fun case(scrutinee: List<Any?>, id: String, vararg alternatives: List<Any?>): List<Any?> =
        listOf("case", scrutinee, id, alternatives.toList())

    @Test fun lexicalScopesPreserveFirstFreeOccurrenceAndRestoreShadowedIdentities() {
        val expression = apply(variable("first"),
            lambda(listOf("x"), apply(variable("x"), lambda(listOf("x"), variable("x")), variable("inside"))),
            variable("x"), variable("first"), variable("last"))
        assertEquals(listOf("first", "inside", "x", "last"), coreFreeVariables(expression).toList())
        val selected = case(variable("caseValue"), "caseValue",
            alternative(listOf("field"), apply(variable("caseValue"), variable("field"), variable("a"))),
            alternative(emptyList(), apply(variable("field"), variable("b"))))
        assertEquals(listOf("caseValue", "a", "field", "b"), coreFreeVariables(selected).toList())
    }

    @Test fun recursiveAndNonrecursiveGroupsHaveDifferentRhsScopes() {
        val group = listOf(binding("a", apply(variable("a"), variable("outside"))),
            binding("b", apply(variable("a"), variable("b"), variable("second"))))
        val body = apply(variable("b"), variable("last"))
        assertEquals(listOf("outside", "second", "last"), coreFreeVariables(local(true, group, body)).toList())
        assertEquals(listOf("a", "outside", "b", "second", "last"), coreFreeVariables(local(false, group, body)).toList())
        assertEquals(listOf("a"), coreFreeVariables(apply(variable("a"), local(true, group, variable("a"))))
            .filter { it == "a" })
    }

    @Test fun terminalRecordsAndMetadataAreNotVisitedOrRetainedAcrossCalls() {
        for (tag in listOf("prim", "lit", "con", "void"))
            assertTrue(coreFreeVariables(listOf(tag, variable("not an expression child"))).isEmpty())
        val expression = listOf("app", variable("real"), emptyList<Any>(), mapOf("debug" to variable("not free")))
        val first = coreFreeVariables(expression)
        assertEquals(setOf("real"), first)
        assertEquals(setOf("other"), coreFreeVariables(variable("other")))
        assertEquals(setOf("real"), first)
    }

    /** Previous lowering algorithm, retained here as an independent traversal
     * oracle over generated lexical trees, not used by production. */
    private fun previous(expr: List<Any?>): Set<String> = when (expr[0]) {
        "var" -> setOf(expr[1] as String)
        "lam" -> previous(expr[2] as List<Any?>) - (expr[1] as List<Map<String, Any?>>).map { it["id"] as String }.toSet()
        "app" -> previous(expr[1] as List<Any?>) + (expr[2] as List<List<Any?>>).flatMap { previous(it) }
        "let" -> {
            val group = expr[2] as List<Map<String, Any?>>
            val ids = group.map { it["id"] as String }.toSet()
            val rhs = group.flatMap { previous(it["expr"] as List<Any?>) }.toSet()
            (if (expr[1] == true) rhs - ids else rhs) + (previous(expr[3] as List<Any?>) - ids)
        }
        "case" -> previous(expr[1] as List<Any?>) + (expr[3] as List<List<Any?>>).flatMap {
            previous(it[3] as List<Any?>) - (it[2] as List<String>).toSet() - (expr[2] as String)
        }
        else -> emptySet()
    }

    @Test fun generatedLexicalTreesRetainExactOrderedCaptureSets() {
        val random = Random(0x5eed)
        fun id() = "v${random.nextInt(8)}"
        fun expression(depth: Int): List<Any?> {
            if (depth == 0) return variable(id())
            return when (random.nextInt(5)) {
                0 -> variable(id())
                1 -> lambda(List(random.nextInt(3)) { id() }, expression(depth - 1))
                2 -> apply(expression(depth - 1), expression(depth - 1), expression(depth - 1))
                3 -> local(random.nextBoolean(), List(random.nextInt(3)) { binding(id(), expression(depth - 1)) },
                    expression(depth - 1))
                else -> case(expression(depth - 1), id(),
                    alternative(listOf(id()), expression(depth - 1)),
                    alternative(listOf(id(), id()), expression(depth - 1)))
            }
        }
        repeat(1024) {
            val tree = expression(5)
            assertEquals(previous(tree).toList(), coreFreeVariables(tree).toList())
        }
    }
}
