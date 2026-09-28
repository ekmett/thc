// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.frame.VirtualFrame

internal class GcForeignExpression(private val op: GcForeignOp,
    @field:Children private var operands: Array<Expr>, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    private object ResumeCompleted : AstResumeStep {
        override fun resume(frame: VirtualFrame, input: Any?): Any? {
            if (input !== Unit) fault("Invalid completed GC/clock continuation")
            return null
        }
    }
    override fun execute(frame: VirtualFrame): Nothing = fault("GC/clock call requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        if (op == GcForeignOp.STATS) operands[0].executeRequiredAddress(frame)
        if (op == GcForeignOp.HEAP_HINT) operands[0].executeLong(frame)
        TupleResultsKt.requireVoidCarrier(operands.last().execute(frame))
        val value = op.invoke()
        if (op.result != null) FrameAccess.writeLong(frame, slots[offset], value)
        if (op.safety == "safe" && AstControl.enabled(this)) {
            val compiled = CompilerDirectives.inCompiledCode()
            GuestThreads.pollCurrent(this, false)?.let { request ->
                request.compiledCapture = compiled
                throw AstCapture(request, SynchronousMasking.current(this)).append(ResumeCompleted)
            }
        }
        return null
    }
}
