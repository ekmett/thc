// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

internal class RuntimeQueryExpression(private val backend: Int,
    @field:Child private var selector: Expr, @field:Child private var index: Expr,
    @field:Child private var detail: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Runtime query requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val key = selector.executeRequiredInt(frame)
        val item = index.executeLong(frame)
        val field = detail.executeLong(frame)
        TupleResultsKt.requireVoidCarrier(state.execute(frame))
        FrameAccess.writeLong(frame, slots[offset], RuntimeServices.query(this, backend, key, item, field))
        return null
    }
}

internal class RuntimeControlExpression(@field:Child private var selector: Expr,
    @field:Child private var setting: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Runtime control requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val key = selector.executeRequiredInt(frame)
        val value = setting.executeLong(frame)
        TupleResultsKt.requireVoidCarrier(state.execute(frame))
        FrameAccess.writeLong(frame, slots[offset], RuntimeServices.control(this, key, value))
        return null
    }
}

internal class RuntimeTraceExpression(@field:Child private var operation: Expr,
    @field:Child private var token: Expr, @field:Child private var address: Expr,
    @field:Child private var length: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Runtime trace requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val op = operation.executeRequiredInt(frame)
        val id = token.executeLong(frame)
        val bytes = address.executeAddress(frame)
        val count = length.executeLong(frame)
        TupleResultsKt.requireVoidCarrier(state.execute(frame))
        FrameAccess.writeLong(frame, slots[offset], RuntimeServices.trace(this, op, id, bytes, count))
        return null
    }
}

internal class ExceptionTextExpression(@field:Child private var handle: Expr,
    @field:Child private var selector: Expr, @field:Child private var index: Expr,
    @field:Child private var state: Expr) : Expr() {
    @Child private var access = ForeignExceptionAccess()
    override fun execute(frame: VirtualFrame): Nothing = fault("Exception metadata requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val value = access.text(handle.executeAddress(frame), selector.executeRequiredInt(frame).toLong(), index.executeLong(frame), state.execute(frame))
        FrameAccess.writeLong(frame, slots[offset], value)
        AstForeignCompleted.poll(this)
        return null
    }
}
