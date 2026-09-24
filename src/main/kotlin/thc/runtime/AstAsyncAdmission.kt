// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

/** An async AST root is enabled only when every possible cut has an explicit resume step.
 * This intentionally small first admission excludes unknown calls and wrappers: a missing
 * parent step would otherwise drop an effectful suffix inside the same root. */
internal object AstAsyncAdmission {
    private val blockingMVars = setOf("takeMVar#", "readMVar#", "putMVar#")
    private val readingMVars = setOf("takeMVar#", "readMVar#")

    fun validate(bindings: List<Map<String, Any?>>) {
        for (binding in bindings) {
            val expression = binding["expr"] as? List<*>
                ?: throw RuntimeFault("AST async binding has no expression: ${binding["id"]}")
            if (!root(expression))
                throw RuntimeFault("AST async capture is not complete for ${binding["id"]}")
        }
    }

    private fun root(expression: List<*>): Boolean = when (expression.firstOrNull()) {
        "lam" -> CoreEntries.lambda(expression).none { it } &&
            (expression.getOrNull(2) as? List<*>)?.let(::root) == true
        "lit", "void" -> true
        "app" -> {
            val head = expression.getOrNull(1) as? List<*>
            val arguments = expression.getOrNull(2) as? List<*>
            head?.firstOrNull() == "prim" && head.getOrNull(1) in blockingMVars &&
                arguments != null && arguments.all { atom(it as? List<*>) }
        }
        "case" -> {
            val scrutinee = expression.getOrNull(1) as? List<*>
            val head = scrutinee?.getOrNull(1) as? List<*>
            val alternative = (expression.getOrNull(3) as? List<*>)?.singleOrNull() as? List<*>
            val body = alternative?.getOrNull(3) as? List<*>
            // The literal suffix cannot suspend. Other tuple alternatives wait
            // until their own effects and typed result paths have capture steps.
            scrutinee?.firstOrNull() == "app" && head?.firstOrNull() == "prim" &&
                head.getOrNull(1) in readingMVars && root(scrutinee) &&
                CoreRepresentations.expression(scrutinee as List<Any?>).isTuple &&
                alternative?.firstOrNull() == "data" && body?.firstOrNull() == "lit" &&
                body.getOrNull(1) == "int" && CoreRepresentations.expression(body as List<Any?>).isLong
        }
        else -> false
    }

    private fun atom(expression: List<*>?): Boolean = expression?.firstOrNull() in setOf("var", "lit", "void")
}
