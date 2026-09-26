// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

/** Ordinary Core forms have captured child edges. Primitive operands are
 * sequenced into locals by lowering; higher-order primitives retain explicit
 * scope handlers. Subsystems with opaque continuation state keep their lowering
 * barriers (STM attempts, compact traversal and foreign execution). */
internal object AstAsyncAdmission {
    fun validate(bindings: List<Map<String, Any?>>) {
        for (binding in bindings) expression(binding["expr"] as? List<*>
            ?: throw RuntimeFault("AST async binding has no expression: ${binding["id"]}"))
    }

    private fun expression(expr: List<*>) {
        when (expr.firstOrNull()) {
            "var", "lit", "void", "con", "prim" -> Unit
            "lam" -> expression(expr[2] as List<*>)
            "app" -> {
                expression(expr[1] as List<*>)
                (expr[2] as List<*>).forEach { expression(it as List<*>) }
            }
            "case" -> {
                expression(expr[1] as List<*>)
                (expr[3] as List<*>).forEach { expression((it as List<*>)[3] as List<*>) }
            }
            "let" -> {
                (expr[2] as List<*>).forEach { expression((it as Map<*, *>)["expr"] as List<*>) }
                expression(expr[3] as List<*>)
            }
            else -> throw UnsupportedCore("AST async capture is not complete for ${expr.firstOrNull()}")
        }
    }
}
