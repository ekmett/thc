// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** GHC's MVar payload is boxed at a known levity, not an arbitrary RuntimeRep. */
internal enum class MVarOp(val primitive: String, private val arguments: List<String>,
                           private val resultRoles: List<String>) {
    NEW("newMVar#", listOf("state"), listOf("state", "mvar")),
    TAKE("takeMVar#", listOf("mvar", "state"), listOf("state", "boxed")),
    PUT("putMVar#", listOf("mvar", "boxed", "state"), listOf("state")),
    READ("readMVar#", listOf("mvar", "state"), listOf("state", "boxed")),
    TRY_TAKE("tryTakeMVar#", listOf("mvar", "state"), listOf("state", "flag", "boxed")),
    TRY_PUT("tryPutMVar#", listOf("mvar", "boxed", "state"), listOf("state", "flag")),
    TRY_READ("tryReadMVar#", listOf("mvar", "state"), listOf("state", "flag", "boxed")),
    IS_EMPTY("isEmptyMVar#", listOf("mvar", "state"), listOf("state", "flag"));

    val tuple: Boolean get() = this != PUT

    fun validateBindings(actual: List<CoreRepresentation>, stored: List<CoreRepresentation?>) {
        for (index in actual.indices) {
            val binding = stored[index] ?: continue
            if (binding.primReps == null) continue
            val occurrence = actual[index]
            val refined = if (binding.primReps == listOf("BoxedRep Nothing") &&
                occurrence.primReps?.singleOrNull() in setOf(LIFTED, UNLIFTED))
                binding.copy(primReps = occurrence.primReps) else binding
            if (refined.isAggregate || refined.isVector || refined.primReps != occurrence.primReps ||
                refined.kind != CoreKind.UNKNOWN && !matches(refined, arguments[index]))
                throw RuntimeFault("MVar argument contradicts its binding proof: $primitive")
        }
    }

    fun validate(actual: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        if (actual.size != arguments.size || flags.size != arguments.size)
            throw RuntimeFault("Primitive arity mismatch: $primitive")
        if (actual.indices.any { !matches(actual[it], arguments[it]) ||
                flags[it] != (actual[it].primReps == listOf(LIFTED)) })
            throw RuntimeFault("MVar primitive argument representation mismatch: $primitive")
        val components = result.components
        val valid = if (tuple) result.kind == CoreKind.UNKNOWN && result.isTuple &&
            !result.isSum && !result.isVector && components!!.size == resultRoles.size &&
            components.indices.all { matches(components[it], resultRoles[it]) } &&
            result.primReps == components.flatMap { it.primReps!! }
        else matches(result, "state")
        if (!valid) throw RuntimeFault("MVar primitive result representation mismatch: $primitive")
    }

    companion object {
        private const val LIFTED = "BoxedRep (Just Lifted)"
        private const val UNLIFTED = "BoxedRep (Just Unlifted)"
        fun named(name: String): MVarOp? = entries.firstOrNull { it.primitive == name }
        private fun matches(proof: CoreRepresentation, role: String): Boolean =
            !proof.isAggregate && !proof.isVector && when (role) {
                "state" -> proof.kind == CoreKind.VOID && proof.primReps == emptyList<String>()
                "mvar" -> proof.kind == CoreKind.OBJECT && proof.primReps == listOf(UNLIFTED)
                "flag" -> proof.kind == CoreKind.LONG && proof.primReps == listOf("IntRep")
                else -> proof.kind in setOf(CoreKind.DATA, CoreKind.CLOSURE, CoreKind.OBJECT) &&
                    proof.primReps?.singleOrNull() in setOf(LIFTED, UNLIFTED)
            }
    }
}

internal fun mVarExpression(operation: MVarOp, proof: CoreRepresentation, operands: Array<Expr>, async: Boolean = false): Expr =
    when (operation) {
        MVarOp.NEW -> NewMVarExpression(operands[0])
        MVarOp.TAKE, MVarOp.READ -> ReadMVarExpression(operands[0], operands[1], operation == MVarOp.TAKE, async)
        MVarOp.TRY_TAKE, MVarOp.TRY_READ -> TryReadMVarExpression(operands[0], operands[1], operation == MVarOp.TRY_TAKE)
        MVarOp.PUT -> PutMVarExpression(operands[0], operands[1], operands[2], async)
        MVarOp.TRY_PUT -> TryPutMVarExpression(operands[0], operands[1], operands[2])
        MVarOp.IS_EMPTY -> IsEmptyMVarExpression(operands[0], operands[1])
    }.proven(proof.copy(evaluated = true))

private abstract class MVarTupleExpression : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
}

private class NewMVarExpression(@field:Child private var state: Expr) : MVarTupleExpression() {
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        requireVoidCarrier(state.execute(frame))
        FrameAccess.write(frame, slots[offset], ManagedMVar())
        return null
    }
}

private class ReadMVarExpression(@field:Child private var cell: Expr,
    @field:Child private var state: Expr, private val remove: Boolean, private val async: Boolean) : MVarTupleExpression() {
    private class Resume(private val node: ReadMVarExpression, private val reference: ManagedMVar,
                         private val slots: IntArray, private val offset: Int) : AstResumeStep {
        override fun resume(frame: VirtualFrame, input: Any?): Any? {
            if (input !== Unit) fault("Invalid AST MVar read resume value")
            val value = try { if (node.remove) reference.take(node, true) else reference.read(node, true) }
            catch (blocked: AsyncBlocked) {
                throw AstCapture(blocked.request, SynchronousMasking.current(node)).append(this)
            }
            FrameAccess.write(frame, slots[offset], value)
            return null
        }
    }
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val reference = ManagedMVar.require(cell.execute(frame))
        requireVoidCarrier(state.execute(frame))
        val value = try { if (remove) reference.take(this, async) else reference.read(this, async) }
        catch (blocked: AsyncBlocked) {
            throw AstCapture(blocked.request, SynchronousMasking.current(this)).append(Resume(this, reference, slots, offset))
        }
        FrameAccess.write(frame, slots[offset], value)
        return null
    }
}

private class TryReadMVarExpression(@field:Child private var cell: Expr,
    @field:Child private var state: Expr, private val remove: Boolean) : MVarTupleExpression() {
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val reference = ManagedMVar.require(cell.execute(frame))
        requireVoidCarrier(state.execute(frame))
        val result = if (remove) reference.tryTake() else reference.tryRead()
        FrameAccess.writeLong(frame, slots[offset], if (result.present) 1L else 0L)
        // The failure payload is unspecified; overwrite the slot even on failure
        // so an earlier successful read cannot retain a stale guest reference.
        FrameAccess.write(frame, slots[offset + 1], result.value)
        return null
    }
}

private class PutMVarExpression(@field:Child private var cell: Expr,
    @field:Child private var value: Expr, @field:Child private var state: Expr, private val async: Boolean) : Expr() {
    private class Resume(private val node: PutMVarExpression, private val reference: ManagedMVar,
                         private val stored: Any?) : AstResumeStep {
        override fun resume(frame: VirtualFrame, input: Any?): Any? {
            if (input !== Unit) fault("Invalid AST MVar put resume value")
            try { reference.put(stored, node, true) }
            catch (blocked: AsyncBlocked) {
                throw AstCapture(blocked.request, SynchronousMasking.current(node)).append(this)
            }
            return Unit
        }
    }
    override fun execute(frame: VirtualFrame): Any {
        val reference = ManagedMVar.require(cell.execute(frame))
        val stored = value.execute(frame)
        requireVoidCarrier(state.execute(frame))
        try { reference.put(stored, this, async) }
        catch (blocked: AsyncBlocked) {
            throw AstCapture(blocked.request, SynchronousMasking.current(this)).append(Resume(this, reference, stored))
        }
        return Unit
    }
}

private class TryPutMVarExpression(@field:Child private var cell: Expr,
    @field:Child private var value: Expr, @field:Child private var state: Expr) : MVarTupleExpression() {
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val reference = ManagedMVar.require(cell.execute(frame))
        val stored = value.execute(frame)
        requireVoidCarrier(state.execute(frame))
        FrameAccess.writeLong(frame, slots[offset], if (reference.tryPut(stored)) 1L else 0L)
        return null
    }
}

private class IsEmptyMVarExpression(@field:Child private var cell: Expr,
    @field:Child private var state: Expr) : MVarTupleExpression() {
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val reference = ManagedMVar.require(cell.execute(frame))
        requireVoidCarrier(state.execute(frame))
        FrameAccess.writeLong(frame, slots[offset], if (reference.isEmpty()) 1L else 0L)
        return null
    }
}
