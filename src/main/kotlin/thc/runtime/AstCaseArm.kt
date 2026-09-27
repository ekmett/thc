// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode

/** An already-selected arm: its scrutinee and constructor fields are owned by
 * the caller. Only exact live captures cross this ordinarily inlinable edge.
 * There is deliberately no tail check, Bloom reset or trampoline here. */
internal class AstCaseArm(internal val target: RootCallTarget, private val captures: CaptureLayout?,
    @field:CompilationFinal(dimensions = 1) private val sourceSlots: IntArray,
    /** Original Core position, not by itself a proof that result/cleanup steps can be elided. */
    internal val tailPosition: Boolean) : Expr() {
    @Child private var call = DirectCallNode.create(target)
    private val shape = (target.rootNode as GuestRoot).tupleResult
    init {
        check((target.rootNode as FunctionRoot).role == FunctionRootRole.PASS_THROUGH)
        representation = (target.rootNode as GuestRoot).scalarResultProof
    }

    override fun execute(frame: VirtualFrame): Any? {
        val bloom = (rootNode as GuestRoot).bloom(frame)
        val result = if (captures == null) Calls.direct(call, arrayOf(bloom))
            else Calls.direct(call, arrayOf(bloom, captures.capture(frame, sourceSlots)))
        return AstControl.complete(this, result, target, shape)
    }

    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val resultShape = shape ?: fault("Case arm has no typed result")
        val answer = try { execute(frame) }
        catch (cut: AstCapture) {
            throw cut.append(object : AstResumeStep {
                override fun resume(frame: VirtualFrame, input: Any?): Any? {
                    resultShape.consume(frame, input, slots, offset)
                    return null
                }
            })
        } catch (cut: DelimitedCut) {
            if (!DelimitedControl.enabled(this)) throw cut
            CompilerDirectives.transferToInterpreter()
            throw DelimitedControl.tupleCut(cut, frame.materialize(),
                AstTupleDestination(resultShape, slots, offset), this)
        }
        resultShape.consume(frame, answer, slots, offset)
        return null
    }
}
