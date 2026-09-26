// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

/** The exporter lowers runRW# to a State# lambda applied to realWorld#.
 * GHC CoreToStg.Prep beta-reduces that exact form: its continuation may jump
 * to an outer join. Keep the binder and source scope, but not a closure boundary.
 * This does not inline arbitrary lambdas or discard state-producing effects. */
internal object CoreStateApplications {
    fun inline(expr: List<Any?>): List<Any?>? {
        if (expr.firstOrNull() != "app") return null
        val function = expr.getOrNull(1) as? List<Any?> ?: return null
        if (function.firstOrNull() != "lam") return null
        val parameters = function[1] as List<Map<String, Any?>>
        val arguments = expr[2] as List<List<Any?>>
        if (parameters.size != 1 || arguments.size != 1 || expr.getOrNull(3) != listOf(false)) return null
        val parameter = parameters.single()
        val state = arguments.single()
        if (parameter["lifted"] != false || parameter["coercion"] == true || state.firstOrNull() != "void") return null
        val formal = CoreRepresentations.binder(parameter)
        val actual = CoreRepresentations.expression(state)
        if (formal.kind != CoreKind.VOID || formal.isAggregate || actual.kind != CoreKind.VOID || actual.isAggregate) return null
        val metadata = CoreRepresentations.metadata(expr).orEmpty() + ("binder" to parameter)
        return listOf("case", state, parameter["id"],
            listOf(listOf("default", null, emptyList<String>(), function[2])), metadata)
    }
}
