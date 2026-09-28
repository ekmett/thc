// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ExplodeLoop

/** The unavailable DWARF backend reports failure, exactly as GHC's USE_LIBDW=0.
 * It neither captures a native stack nor writes a Location. Managed IPE is a
 * separate, available facility; a missing DWARF session is never fake success.
 */
internal class OriginalLibdwExpression(private val operation: LibdwForeignOp,
    @field:Children private var operands: Array<Expr>, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Any = fault("Original libdw call requires a tuple destination")

    @ExplodeLoop
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        // Native stubs ignore pointer contents, but evaluating the arguments and
        // checking their carriers remains required, including the State token.
        val firstOperands = operands
        if (firstOperands == null) CompilerDirectives.transferToInterpreter()
        for (index in 0 until firstOperands.lastIndex) operands[index].executeRequiredAddress(frame)
        TupleResultsKt.requireVoidCarrier(operands.last().execute(frame))
        if (operation == LibdwForeignOp.LOOKUP) FrameAccess.writeInt(frame, slots[offset], 1)
        else if (operation != LibdwForeignOp.CLEAR)
            FrameAccess.writeObject(frame, slots[offset], ManagedAddress.nullAddress())
        return null
    }
}
