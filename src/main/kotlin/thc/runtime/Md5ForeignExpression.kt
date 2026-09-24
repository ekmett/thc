// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** The C calls return the logical singleton State tuple, with no physical fields. */
internal class Md5ForeignExpression(private val operation: Md5ForeignOp,
    @field:Children private var operands: Array<Expr>, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("MD5 foreign call requires a State tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val first = operands[0].executeRequiredAddress(frame)
        when (operation) {
            Md5ForeignOp.INIT -> {
                ManagedByteArray.requireState(operands[1].execute(frame))
                ManagedMd5.init(first)
            }
            Md5ForeignOp.UPDATE -> {
                val input = operands[1].executeRequiredAddress(frame)
                val length = operands[2].executeRequiredLong(frame)
                ManagedByteArray.requireState(operands[3].execute(frame))
                ManagedMd5.update(first, input, length)
            }
            Md5ForeignOp.FINAL -> {
                val context = operands[1].executeRequiredAddress(frame)
                ManagedByteArray.requireState(operands[2].execute(frame))
                ManagedMd5.finish(first, context)
            }
        }
        return null
    }
}
