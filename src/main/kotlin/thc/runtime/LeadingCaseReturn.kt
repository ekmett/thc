// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.source.SourceSection

internal const val LEADING_CASE_RETURN_PROPERTY = "thc.leadingCaseReturn"

/** Immutable partial-inlining recipe, installed before a successfully lowered root is published. */
internal class LeadingCaseReturn private constructor(
    val layout: DataLayout,
    val scrutineeIndex: Int,
    val resultIndex: Int,
    val source: CoreSourceLocation?
) {
    companion object {
        /** Called only for an actual lowered Case, never a diagnostic replacement for that Case. */
        fun discover(args: List<Map<String, Any?>>, expression: List<Any?>,
                     result: CoreRepresentation, argumentOffset: Int, usedFormals: Set<String>, hasCaptures: Boolean,
                     dataLayout: (String) -> DataLayout, sources: CoreSources,
                     inheritedSource: CoreSourceLocation?): LeadingCaseReturn? {
            if (!java.lang.Boolean.getBoolean(LEADING_CASE_RETURN_PROPERTY) || !result.isLong || expression[0] != "case")
                return null
            // The shortcut must not skip entry validation for other live carriers/captures.
            val ids = args.map { it["id"] as String }
            if (hasCaptures || ids.toSet().size != ids.size) return null
            val scrutinee = expression[1] as List<Any?>
            if (scrutinee[0] != "var") return null
            val scrutineeIndex = args.indexOfFirst { it["id"] == scrutinee[1] }
            if (scrutineeIndex < 0 || CoreRepresentations.binder(args[scrutineeIndex]).kind !in
                setOf(CoreKind.UNKNOWN, CoreKind.OBJECT, CoreKind.DATA)) return null
            val alternatives = expression[3] as List<List<Any?>>
            val seen = hashSetOf<String>()
            for (alt in alternatives) {
                if (alt[0] != "data") continue
                val id = alt[1] as String
                // Earlier duplicate alternatives have priority in both interpreters.
                if (!seen.add(id) || (alt[2] as List<*>).isNotEmpty()) continue
                val value = alt[3] as List<Any?>
                if (value[0] != "var" || value[1] == expression[2]) continue
                val resultIndex = args.indexOfFirst { it["id"] == value[1] }
                if (resultIndex < 0 || resultIndex == scrutineeIndex || !CoreRepresentations.binder(args[resultIndex]).isLong ||
                    usedFormals.any { it != scrutinee[1] && it != value[1] }) continue
                val layout = dataLayout(id)
                if (layout.arity != 0) continue
                val location = sources.expression(value, sources.expression(expression, inheritedSource))
                return LeadingCaseReturn(layout, scrutineeIndex + argumentOffset, resultIndex + argumentOffset, location)
            }
            return null
        }
    }
}

/**
 * Compiled-only partial inlining: interpreted calls retain their ordinary frames.
 * This child attributes the shortcut to the callee's case/arm, but deliberately
 * does not synthesize a callee FrameInstance or report a root entry that did not occur.
 */
internal class LeadingCaseReturnNode(private val recipe: LeadingCaseReturn, private val metrics: Metrics) : Node() {
    override fun getSourceSection(): SourceSection? = recipe.source?.section
    fun getCoreSourceNotes(): List<CoreSourceNote> = recipe.source?.notes.orEmpty()

    /** null is a miss; the only successful result carrier is a non-null boxed Long. */
    fun execute(arguments: Array<Any?>): Any? {
        if (!CompilerDirectives.inCompiledCode()) return null
        val scrutinee = arguments[recipe.scrutineeIndex]
        if (!recipe.layout.matches(scrutinee) || arguments[recipe.resultIndex] !is Long)
            return null
        if (metrics.enabled) metrics.leadingCaseReturns++
        // Keep the original reference ABI value; do not unbox and rebox a cached result.
        return arguments[recipe.resultIndex]
    }
}
