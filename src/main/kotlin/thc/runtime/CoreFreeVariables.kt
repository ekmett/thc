// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

/** Lexical capture analysis of one already-demanded expression. No global lookup
 * or body materialization occurs here. Preserve first-occurrence ordering without
 * constructing and merging an intermediate collection for every Core node. */
internal fun coreFreeVariables(expression: List<Any?>): Set<String> = when (expression[0]) {
    "var" -> setOf(expression[1] as String)
    "lam", "app", "let", "case" -> FreeVariables().collect(expression)
    else -> emptySet()
}

private class FreeVariables {
    private val free = LinkedHashSet<String>()
    private val bound = HashSet<String>()
    private val scope = ArrayList<String>()

    fun collect(expression: List<Any?>): Set<String> {
        visit(expression)
        return free
    }

    private fun bind(id: String) {
        // A shadow of an existing identity must not remove the outer binding
        // when this inner scope ends.
        if (bound.add(id)) scope.add(id)
    }

    private fun restore(mark: Int) {
        while (scope.size > mark) bound.remove(scope.removeAt(scope.lastIndex))
    }

    private fun visit(expression: List<Any?>) {
        when (expression[0]) {
            "var" -> {
                val id = expression[1] as String
                if (id !in bound) free.add(id)
            }
            "lam" -> {
                val mark = scope.size
                for (parameter in expression[1] as List<Map<String, Any?>>) bind(parameter["id"] as String)
                visit(expression[2] as List<Any?>)
                restore(mark)
            }
            "app" -> {
                visit(expression[1] as List<Any?>)
                for (argument in expression[2] as List<List<Any?>>) visit(argument)
            }
            "let" -> {
                val mark = scope.size
                val group = expression[2] as List<Map<String, Any?>>
                val recursive = expression[1] == true
                if (recursive) for (binding in group) bind(binding["id"] as String)
                for (binding in group) visit(binding["expr"] as List<Any?>)
                if (!recursive) for (binding in group) bind(binding["id"] as String)
                visit(expression[3] as List<Any?>)
                restore(mark)
            }
            "case" -> {
                visit(expression[1] as List<Any?>)
                val mark = scope.size
                bind(expression[2] as String)
                for (alternative in expression[3] as List<List<Any?>>) {
                    val alternativeMark = scope.size
                    for (id in alternative[2] as List<String>) bind(id)
                    visit(alternative[3] as List<Any?>)
                    restore(alternativeMark)
                }
                restore(mark)
            }
        }
    }
}
