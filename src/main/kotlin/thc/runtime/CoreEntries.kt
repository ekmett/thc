// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

/** A requested call-by-value convention, distinct from an already-evaluated value proof. */
internal object CoreEntries {
    private fun parse(value: Any?, arity: Int): BooleanArray {
        if (value == null) return BooleanArray(arity)
        val marks = value as? List<*> ?: throw RuntimeFault("Invalid Core entry contract")
        if (marks.size != arity) throw RuntimeFault("Core entry contract arity mismatch")
        return BooleanArray(arity) { marks[it] as? Boolean ?: throw RuntimeFault("Invalid Core entry argument mark") }
    }

    fun lambda(expr: List<Any?>): BooleanArray {
        if (expr.firstOrNull() != "lam") return BooleanArray(0)
        val args = expr[1] as List<*>
        return parse(CoreRepresentations.metadata(expr)?.get("entryStrict"), args.size)
    }

    fun binding(binding: Map<String, Any?>): BooleanArray? {
        val rhs = binding["expr"] as? List<Any?> ?: return null
        if (rhs.firstOrNull() != "lam") return null
        val marks = lambda(rhs)
        binding["entryStrict"]?.let {
            if (!parse(it, marks.size).contentEquals(marks)) throw RuntimeFault("Binding and lambda entry contracts disagree")
        }
        return marks
    }

    fun join(definition: CoreJoinDefinition): BooleanArray {
        val marks = binding(definition.binding) ?: BooleanArray(0)
        if (definition.parameters.size > marks.size) throw RuntimeFault("Join entry contract arity mismatch")
        return marks.copyOf(definition.parameters.size)
    }
}
