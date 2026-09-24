// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame
import java.lang.ref.Reference

/** GHC 9.14.1: a_levpoly -> State# s -> State# s, not a State tuple. */
internal object CoreTouch {
    private val keys = setOf("kind", "primReps", "evaluated")
    private val lifted = listOf("BoxedRep (Just Lifted)")
    private val unlifted = listOf("BoxedRep (Just Unlifted)")

    fun validateRaw(arguments: List<*>, flags: List<*>, result: Any?) {
        fun scalar(value: Any?): Map<*, *>? = (value as? Map<*, *>)?.takeIf {
            it.keys == keys && it["evaluated"] is Boolean
        }
        fun state(value: Any?): Boolean = scalar(value)?.let {
            it["kind"] == "void" && it["primReps"] == emptyList<String>()
        } == true
        val kept = scalar(arguments.getOrNull(0))
        if (arguments.size != 2 || kept == null || kept["kind"] !in setOf("object", "data", "closure") ||
            kept["primReps"] !in listOf(lifted, unlifted) || !state(arguments[1]) || !state(result) ||
            flags != listOf(kept["primReps"] == lifted, false))
            throw RuntimeFault("touch#: exact reference, State input and bare State result required")
    }

    /** Recheck after lexical refinement; occurrence metadata cannot disguise a stored scalar. */
    fun validate(arguments: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        fun raw(value: CoreRepresentation): Any? = if (!value.present || value.isAggregate || value.isVector) null
            else mapOf("kind" to value.kind.name.lowercase(), "primReps" to value.primReps, "evaluated" to value.evaluated)
        validateRaw(arguments.map(::raw), flags, raw(result))
    }
}

internal object Touch {
    @JvmStatic fun preserve(kept: Any?, state: Any?): Any {
        requireVoidCarrier(state)
        Reference.reachabilityFence(kept)
        return Unit
    }
}

internal class TouchExpression(@field:Child private var kept: Expr,
    @field:Child private var state: Expr, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Any {
        // Merely obtain the lazy carrier: touching bottom must not enter it.
        val value = kept.execute(frame)
        return Touch.preserve(value, state.execute(frame))
    }
}
