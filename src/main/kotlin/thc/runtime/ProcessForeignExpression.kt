// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.bytecode.BytecodeNode
import com.oracle.truffle.api.bytecode.LocalAccessor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ExplodeLoop
import thc.Language

internal class ProcessForeignExpression(private val operation: ProcessOp,
    @field:Children private var operands: Array<Expr>, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("Original process call requires its State/result tuple")
    @ExplodeLoop override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val values = arrayOfNulls<Any>(operands.size)
        for (index in values.indices) values[index] = when (operation.arguments[index]) {
            "Int32Rep" -> operands[index].executeRequiredLong(frame)
            "AddrRep" -> operands[index].executeRequiredAddress(frame)
            else -> operands[index].execute(frame)
        }
        val result = ManagedProcessForeign.current(this).invoke(operation, values, this)
        val errno = Language.currentState(this).stdio.errno()
        FrameAccess.writeLong(frame, slots[offset], result)
        if (operation == ProcessOp.WAIT) AstForeignCompleted.poll(this, errno, result == -1L && errno == 4L)
        return null
    }
}

/** Typed operand locals survive evaluation exactly once, before native effects. */
internal class BytecodeProcessArguments(val operation: ProcessOp,
    @field:CompilationFinal(dimensions = 1) private val slots: Array<LocalAccessor>) {
    @ExplodeLoop fun read(bytecode: BytecodeNode, frame: VirtualFrame): Array<Any?> =
        arrayOfNulls<Any>(slots.size).also { values ->
            for (index in values.indices) values[index] = if ("Int32Rep" == operation.arguments[index])
                slots[index].getLong(bytecode, frame) else slots[index].getObject(bytecode, frame)
        }
}
