// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

/** GHC's Id arity may exceed the lambdas retained in an exported pre-Tidy RHS. */
internal object CoreApplicationCertificates {
    internal data class Arity(val declared: Int, val retained: Int, val retainedValues: Int)

    fun binding(binding: Map<String, Any?>): Arity? {
        val declared = (binding["arity"] as? Number)?.toInt() ?: return null
        val rhs = binding["expr"] as? List<*> ?: return null
        val parameters = if (rhs.firstOrNull() == "lam") rhs.getOrNull(1) as? List<*> ?: return null else emptyList<Any?>()
        val retainedValues = parameters.count { (it as? Map<*, *>)?.get("coercion") != true }
        return Arity(declared, parameters.size, retainedValues)
    }

    /** A certified partial application can still enter work when forced to WHNF.
     * Casts and ticks have already been erased by the exporter; retained coercion
     * slots remain in both the lambda prefix and application argument list. */
    fun eagerApplication(expr: List<Any?>, head: Arity?): Boolean {
        if (expr.firstOrNull() != "app") return false
        val certified = (expr.getOrNull(5) as? Boolean) ?: (expr.getOrNull(4) == true)
        if (!certified || head == null || head.declared <= head.retainedValues) return certified
        val supplied = (expr.getOrNull(2) as? List<*>)?.size ?: return false
        // Below the retained lambda prefix, ordinary PAP construction is inert.
        // Once that prefix is saturated, a case before later lambdas may run on
        // WHNF entry. Extra slots can include coercions; without their original
        // types the retained expression cannot prove a later safe saturation.
        return supplied < head.retained
    }
}
