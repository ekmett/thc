// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** Original compiled CAPI wrapper; its State# is checked before the C call. */
internal class CapiExpression(private val call: CapiCall,
    @field:Children private var operands: Array<Expr>, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("CAPI call requires a State/result tuple")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val result = if (call.zeroArgument) {
            requireVoidCarrier(operands[0].execute(frame))
            CoreCapiForeign.zero(this, call)
        } else {
            val clock = operands[0].executeRequiredLong(frame)
            val output = operands[1].executeRequiredAddress(frame)
            requireVoidCarrier(operands[2].execute(frame))
            CoreCapiForeign.wordAddress(this, call, clock, output)
        }
        FrameAccess.writeLong(frame, slots[offset], result)
        return null
    }
}
