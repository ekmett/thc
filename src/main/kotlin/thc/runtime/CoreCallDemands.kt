// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

internal const val CALL_DEMANDS_PROPERTY = "thc.callDemands"

/** Caller-side demand proofs, distinct from a callee entry contract or a stored WHNF fact. */
internal object CoreCallDemands {
    /** Validate evidence even while the caller-side optimization is disabled. */
    fun lowerApplication(expr: List<Any?>, enabled: Boolean): BooleanArray =
        application(expr).also { if (!enabled) it.fill(false) }

    fun application(expr: List<Any?>): BooleanArray {
        val args = expr.getOrNull(2) as? List<*> ?: throw RuntimeFault("Application lacks arguments")
        val raw = CoreRepresentations.metadata(expr)?.get("callDemand") ?: return BooleanArray(args.size)
        val demand = raw as? Map<*, *> ?: throw RuntimeFault("Invalid Core call demand")
        val number = demand["arity"] as? Number ?: throw RuntimeFault("Invalid Core demand arity")
        val arity = number.toInt()
        if (arity < 0 || number.toDouble() != arity.toDouble()) throw RuntimeFault("Invalid Core demand arity")
        val rawMarks = demand["strictArgs"] as? List<*> ?: throw RuntimeFault("Invalid Core call demand arguments")
        if (rawMarks.size != args.size) throw RuntimeFault("Core call demand argument count mismatch")
        val marks = BooleanArray(args.size) { index ->
            val mark = rawMarks[index] as? Boolean ?: throw RuntimeFault("Invalid Core call demand argument mark")
            if (mark && index >= arity) throw RuntimeFault("Core call demand exceeds its signature arity")
            mark
        }
        // The demand signature's saturation threshold is independent of the
        // callee's runtime arity. A shorter call must retain ordinary laziness.
        if (args.size < arity) marks.fill(false)
        return marks
    }
}
