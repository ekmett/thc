// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ArrayCoreEvidenceTest {
    private val intRep = mapOf("primReps" to listOf("IntRep"), "kind" to "long", "evaluated" to false)
    private val voidRep = mapOf("primReps" to emptyList<String>(), "kind" to "void", "evaluated" to true)
    private val leaf = listOf("lit", "int", "0")
    private fun formal(id: String) = mapOf("id" to id, "rep" to intRep)
    private fun binding(id: String, expr: Any?) = mapOf("id" to id, "name" to id, "expr" to expr)
    private fun module(vararg bindings: Map<String, Any?>) = mapOf("bindings" to bindings.toList())
    private fun root(body: Any? = leaf): Map<String, Any?> {
        val state = mapOf("id" to "s", "type" to "State# RealWorld", "rep" to voidRep,
            "lifted" to false, "coercion" to false)
        return binding("root", listOf("lam", listOf(formal("x")), listOf("app",
            listOf("lam", listOf(state), body), listOf(listOf("void", mapOf("rep" to voidRep))),
            listOf(false), false, false)))
    }
    private fun changedCall(change: (MutableList<Any?>) -> Unit): Map<String, Any?> {
        val root = root().toMutableMap()
        val expr = (root["expr"] as List<Any?>).toMutableList()
        val call = (expr[2] as List<Any?>).toMutableList()
        change(call); expr[2] = call; root["expr"] = expr
        return root
    }

    @Test fun actualClosureAndPrimitiveOccurrencesComeFromCore() {
        val helper = binding("helper", listOf("lam", listOf(formal("h")),
            listOf("app", listOf("prim", "+#"), listOf(listOf("var", "h"), leaf))))
        val root = binding("root", listOf("lam", listOf(formal("x")),
            listOf("app", listOf("var", "helper"), listOf(listOf("var", "x")))))
        val ignored = binding("unused", listOf("prim", "indexIntArray#"))
        val proof = ArrayCoreEvidence(module(root, helper, ignored), "root")
        assertEquals(setOf("root", "helper"), proof.bindings.map { it["id"] }.toSet())
        assertEquals(mapOf("+#" to 1), proof.primitiveCounts)
        assertEquals(listOf("helper"), proof.globalReferences(root["expr"]))
        assertThrows(IllegalArgumentException::class.java) { ArrayCoreEvidence(module(root), "root") }
        assertThrows(IllegalArgumentException::class.java) { ArrayCoreEvidence(module(root, root, helper), "root") }
        assertThrows(IllegalArgumentException::class.java) { ArrayCoreEvidence(module(root, helper), "missing") }
    }

    @Test fun immediateStateProofRejectsChangedFormalsArgumentsFlagsAndControlFlow() {
        assertEquals(2, ArrayCoreEvidence(module(root()), "root").immediateStateCalls())
        val badCalls = mutableListOf<Map<String, Any?>>()
        for ((key, value) in listOf("type" to "Proxy# RealWorld", "rep" to intRep,
            "lifted" to true, "coercion" to true)) {
            badCalls += changedCall { call ->
                val state = (call[1] as List<Any?>).toMutableList()
                val formal = (state[1] as List<Map<String, Any?>>).single().toMutableMap()
                formal[key] = value; state[1] = listOf(formal); call[1] = state
            }
        }
        badCalls += changedCall { it[2] = emptyList<Any?>() }
        badCalls += changedCall { it[2] = listOf(leaf) }
        badCalls += changedCall { it[2] = listOf(listOf("void", mapOf("rep" to intRep))) }
        badCalls += changedCall { call ->
            val state = (call[1] as List<Any?>).toMutableList()
            state[1] = (state[1] as List<Any?>) + formal("extra")
            call[1] = state
        }
        badCalls += changedCall { it[3] = listOf(true) }
        badCalls += changedCall { it[4] = true }
        badCalls += changedCall { it[5] = true }
        badCalls += root(listOf("lam", listOf(formal("hidden")), leaf))
        badCalls += root(listOf("var", "root"))
        val outer = root()["expr"] as List<Any?>
        badCalls += binding("root", listOf("lam", outer[1],
            listOf("case", leaf, "c", listOf(listOf("default", null, emptyList<String>(), outer[2])))))
        for ((index, bad) in badCalls.withIndex())
            assertThrows(IllegalArgumentException::class.java, { ArrayCoreEvidence(module(bad), "root").immediateStateCalls() }, "mutation$index")
    }

    @Test fun onlyCompleteProvenJoinPrefixesAreExcludedAndTheirBodiesRemainVisible() {
        fun join() = mutableMapOf<String, Any?>("id" to "j", "joinValueArity" to 1L,
            "joinResultRep" to intRep, "info" to mapOf("joinArity" to 1L),
            "expr" to listOf("lam", listOf(formal("a")), leaf, mapOf("resultRep" to intRep)))
        fun proof(join: Map<String, Any?>) = ArrayCoreEvidence(module(root(listOf("let", false,
            listOf(join), listOf("app", listOf("var", "j"), listOf(leaf))))), "root")
        assertEquals(2, proof(join()).immediateStateCalls())
        val mutations: List<(MutableMap<String, Any?>) -> Unit> = listOf(
            { it.remove("joinValueArity") }, { it["joinValueArity"] = 0L },
            { it["joinValueArity"] = true }, { it["joinValueArity"] = 1.0 },
            { it["joinValueArity"] = 2L },
            { it.remove("joinResultRep") },
            { it["joinResultRep"] = emptyMap<String, Any?>()
              it["expr"] = listOf("lam", listOf(formal("a")), leaf, mapOf("resultRep" to emptyMap<String, Any?>())) },
            { it["joinResultRep"] = intRep + ("primReps" to listOf("WordRep")) },
            { it["expr"] = listOf("lam", listOf(formal("a"), formal("b")), leaf, mapOf("resultRep" to intRep)) },
            { it["expr"] = listOf("lam", listOf(formal("a")),
                listOf("lam", listOf(formal("hidden")), leaf), mapOf("resultRep" to intRep)) })
        for ((index, change) in mutations.withIndex())
            assertThrows(IllegalArgumentException::class.java, { proof(join().apply(change)).immediateStateCalls() }, "join mutation$index")
    }

    @Test fun traversalIncludesDictionaryHeldExpressionsWithoutCountingUnusedGlobals() {
        val local = mapOf("id" to "a", "expr" to listOf("app", listOf("prim", "+#"), listOf(leaf, leaf)))
        val proof = ArrayCoreEvidence(module(root(listOf("let", false, listOf(local), listOf("var", "a")))), "root")
        assertEquals(mapOf("+#" to 1), proof.primitiveCounts)
        assertEquals(2, proof.immediateStateCalls())
        val hidden = local + ("expr" to listOf("lam", listOf(formal("h")), leaf))
        assertThrows(IllegalArgumentException::class.java) {
            ArrayCoreEvidence(module(root(listOf("let", false, listOf(hidden), leaf))), "root").immediateStateCalls()
        }
    }
}
