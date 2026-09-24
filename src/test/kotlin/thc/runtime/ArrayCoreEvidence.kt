// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import thc.CoreModules
import java.util.Collections
import java.util.IdentityHashMap

/** Structural evidence for the pinned array fixtures, not a Core evaluator. */
internal class ArrayCoreEvidence(module: Map<String, Any?>, private val name: String) {
    private val supplied = module["bindings"] as List<Map<String, Any?>>
    val allBindings = supplied.associateBy { it["id"] as String }.also {
        require(it.size == supplied.size) { "$name: duplicate binding" }
    }
    // Reuse the production lexical linker, including missing-global/constructor checks.
    val bindings = CoreModules.reachable(module, name, strictLink = true)["bindings"] as List<Map<String, Any?>>
    val root = allBindings[name] ?: bindings.single { it["name"] == name }
    val primitiveCounts: Map<String, Int> = bindings.flatMap { nodes(it["expr"]) }
        .filter { it.firstOrNull() == "prim" }.map { it[1] as String }.groupingBy { it }.eachCount()

    fun nodes(value: Any?): List<List<Any?>> = buildList {
        fun visit(value: Any?) {
            when (value) {
                is List<*> -> { add(value); value.forEach(::visit) }
                is Map<*, *> -> value.values.forEach(::visit)
            }
        }
        visit(value)
    }

    fun globalReferences(expr: Any?): List<String> = nodes(expr)
        .filter { it.firstOrNull() == "var" && it.getOrNull(1) in allBindings }
        .map { it[1] as String }

    fun guestLambdas(expr: Any?): List<List<Any?>> {
        val all = nodes(expr)
        val prefixes = Collections.newSetFromMap(IdentityHashMap<List<Any?>, Boolean>())
        for (node in all.filter { it.firstOrNull() == "let" }) {
            for (binding in node[2] as List<Map<String, Any?>>) {
                val arity = binding["joinValueArity"]
                val rhs = binding["expr"] as? List<Any?> ?: continue
                if ((arity is Int || arity is Long) && (arity as Number).toLong() > 0 &&
                    rhs.firstOrNull() == "lam" && arity.toLong() == (rhs[1] as List<*>).size.toLong()) {
                    val result = binding["joinResultRep"] as? Map<*, *>
                    require(result?.get("primReps") == listOf("IntRep") && result["kind"] == "long" &&
                        result == (rhs.lastOrNull() as? Map<*, *>)?.get("resultRep")) {
                        "$name: complete join prefix lost its scalar Int# result proof"
                    }
                    prefixes.add(rhs)
                }
            }
        }
        // Do not skip a join's body: a function returned or hidden inside it is a guest root.
        return all.filter { it.firstOrNull() == "lam" && it !in prefixes }
    }

    fun stateLambda(expr: Any?): List<Any?> {
        val outer = expr as? List<Any?>
        require(outer?.firstOrNull() == "lam") { "$name: entry lost its lambda" }
        val formals = outer.getOrNull(1) as? List<*>
        val input = formals?.singleOrNull() as? Map<*, *>
        require((input?.get("rep") as? Map<*, *>)?.get("primReps") == listOf("IntRep")) {
            "$name: expected unary Int# entry"
        }
        val call = outer.getOrNull(2) as? List<*>
        require(call?.firstOrNull() == "app") { "$name: State# lambda must be called immediately" }
        val state = call.getOrNull(1) as? List<Any?>
        require(state?.firstOrNull() == "lam") { "$name: missing immediate State# lambda" }
        val formal = (state.getOrNull(1) as? List<*>)?.singleOrNull() as? Map<*, *>
        val void = mapOf("primReps" to emptyList<String>(), "kind" to "void", "evaluated" to true)
        require(formal?.get("type") == "State# RealWorld" && formal["rep"] == void &&
            formal["lifted"] == false && formal["coercion"] == false) {
            "$name: expected actual zero-slot State# formal"
        }
        val argument = (call.getOrNull(2) as? List<*>)?.singleOrNull() as? List<*>
        require(argument?.firstOrNull() == "void" &&
            (argument.lastOrNull() as? Map<*, *>)?.get("rep") == void) { "$name: expected one void State# argument" }
        require(call.drop(3).take(3) == listOf(listOf(false), false, false)) { "$name: State# call flags changed" }
        val lambdas = guestLambdas(outer)
        require(lambdas.size == 2 && lambdas[0] === outer && lambdas[1] === state) {
            "$name: unexpected additional guest lambda"
        }
        return state
    }

    fun immediateStateCalls(): Int {
        require(bindings.size == 1 && globalReferences(root["expr"]).isEmpty()) { "$name: global closure changed" }
        stateLambda(root["expr"])
        return guestLambdas(root["expr"]).size
    }
}
