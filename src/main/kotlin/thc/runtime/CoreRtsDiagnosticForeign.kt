// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

internal class RtsDiagnosticExpression(private val operation: RtsDiagnosticOp,
    @field:Children private var operands: Array<Expr>, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("RTS diagnostic requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val first = if (operands.size > 1) operands[0].execute(frame) else null
        val second = if (operands.size > 2) operands[1].executeRequiredAddress(frame) else null
        TupleResultsKt.requireVoidCarrier(operands.last().execute(frame))
        RtsDiagnostics.report(this, operation, first, second)
        return null
    }
}
