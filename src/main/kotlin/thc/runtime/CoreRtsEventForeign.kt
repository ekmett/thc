// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.frame.VirtualFrame

internal class RtsEventForeignExpression(private val op: RtsEventForeignOp,
    @field:Children private var operands: Array<Expr>, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    private object ResumeCompleted : AstResumeStep {
        override fun resume(frame: VirtualFrame, input: Any?): Any? {
            if (input !== Unit) fault("Invalid completed RTS event continuation")
            return null
        }
    }
    override fun execute(frame: VirtualFrame): Nothing = fault("RTS event call requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val count = if (op == RtsEventForeignOp.CAPABILITIES) Integer.toUnsignedLong(operands[0].executeRequiredInt(frame)) else 0L
        requireVoidCarrier(operands.last().execute(frame))
        val value = op.invoke(this, count)
        if (op == RtsEventForeignOp.PROCESSORS) FrameAccess.writeInt(frame, slots[offset], value.toInt())
        else if (op.result != null) FrameAccess.writeLong(frame, slots[offset], value)
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
