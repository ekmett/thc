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
    val bindings = CoreModules.reachable(module, name, true)["bindings"] as List<Map<String, Any?>>
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
        val state = immediateStateLambda(outer.getOrNull(2))
        val lambdas = guestLambdas(outer)
        require(lambdas.size == 2 && lambdas[0] === outer && lambdas[1] === state) {
            "$name: unexpected additional guest lambda"
        }
        return state
    }

    /** The exact source redex, also used by multiargument and helper fixtures. */
    fun immediateStateLambda(expression: Any?): List<Any?> {
        val call = expression as? List<*>
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
        return state
    }

    /** Keep helpers and returned/argument lambdas; exclude only checked direct
     * State# applications. This reads Core, never runtime target observations. */
    fun loweredGuestLambdas(expr: Any?): List<List<Any?>> {
        val inFrame = nodes(expr).filter { it.firstOrNull() == "app" &&
            (it.getOrNull(1) as? List<*>)?.firstOrNull() == "lam" }.map(::immediateStateLambda)
        return guestLambdas(expr).filter { lambda -> inFrame.none { it === lambda } }
    }

    /** Bounded two-function fixture path with one source State# redex. Keep its
     * exported lambda inventory distinct from executable roots; never infer an
     * expected count from observed targets or call the production rewriter. */
    fun loweredStateFunctionPath(callee: String, stateOwner: String = root["name"] as String,
        statePath: List<Int> = listOf(2)): List<String> {
        val functions = listOf(root, bindings.single { it["name"] == callee })
        val ids = functions.map { it["id"] as String }
        require(ids.distinct().size == 2) { "$name: expected two distinct global functions" }
        val owner = functions.single { it["name"] == stateOwner }
        val application = statePath.fold(owner["expr"]) { node, index -> (node as List<*>)[index] }
        val state = immediateStateLambda(application)
        val exported = bindings.flatMap { guestLambdas(it["expr"]) }
        require(exported.size == 3 && exported.any { it === state } &&
            functions.all { function -> exported.any { it === function["expr"] } }) {
            "$name: expected two global lambdas and the exact local State# lambda"
        }
        val retained = bindings.flatMap { loweredGuestLambdas(it["expr"]) }
        require(retained.size == 2 && functions.all { function -> retained.any { it === function["expr"] } }) {
            "$name: unexpected retained callback, helper or missing global root"
        }
        require(globalReferences(root["expr"]).filter { it in ids } == listOf(ids[1]) &&
            globalReferences(functions[1]["expr"]).none { it in ids }) {
            "$name: two-function call path or multiplicity changed"
        }
        return ids
    }

    fun immediateStateCalls(): Int {
        require(bindings.size == 1 && globalReferences(root["expr"]).isEmpty()) { "$name: global closure changed" }
        stateLambda(root["expr"])
        return guestLambdas(root["expr"]).size
    }

    /** Exclude only the independently checked runRW State# beta-redex.
     * Keep the exported lambda inventory above for structural evidence. */
    fun loweredStateLambdas(expr: Any?): List<List<Any?>> {
        val state = stateLambda(expr)
        return guestLambdas(expr).filter { it !== state }
    }

    fun loweredImmediateStateCalls(): Int {
        immediateStateCalls() // Preserve the closed-global and exact-source checks.
        return loweredStateLambdas(root["expr"]).size
    }
}
