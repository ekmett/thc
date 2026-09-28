// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** Erase the logical State field, retaining a detached diagnostic snapshot. */
internal class CloneStackExpression(@field:Child private var state: Expr, proof: CoreRepresentation) : Expr() {
    init { representation = proof.withEvaluated(true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("Stack clone requires a State/snapshot tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        TupleResultsKt.requireVoidCarrier(state.execute(frame))
        FrameAccess.write(frame, slots[offset], ManagedStackSnapshot.capture(this))
        return null
    }
}
