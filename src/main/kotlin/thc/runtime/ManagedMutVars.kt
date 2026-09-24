package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** One mutable guest reference. Reads return the stored value without entering a thunk.
 * Volatile access publishes stored values between threads without serializing
 * independent cells. The field remains mutable and is never CompilationFinal.
 */
internal class ManagedMutVar(@Volatile var value: Any?) {
    companion object {
        @JvmStatic fun require(value: Any?): ManagedMutVar = value as? ManagedMutVar
            ?: fault("Expected a managed MutVar#")
    }
}

private const val MUTVAR_REP = "BoxedRep (Just Unlifted)"
private const val LIFTED_REP = "BoxedRep (Just Lifted)"

/** GHC's a_levpoly is a boxed value of either known levity, not an arbitrary RuntimeRep. */
internal enum class MutVarOp(val primitive: String, private val arguments: List<String>, val tuple: Boolean) {
    NEW("newMutVar#", listOf("boxed", "state"), true),
    READ("readMutVar#", listOf("mutvar", "state"), true),
    WRITE("writeMutVar#", listOf("mutvar", "boxed", "state"), false);

    fun validate(actual: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        fun matches(proof: CoreRepresentation, role: String): Boolean = !proof.isTuple && !proof.isVector && when (role) {
            "state" -> proof.kind == CoreKind.VOID && proof.primReps == emptyList<String>()
            "mutvar" -> proof.kind == CoreKind.OBJECT && proof.primReps == listOf(MUTVAR_REP)
            else -> proof.kind in setOf(CoreKind.DATA, CoreKind.CLOSURE, CoreKind.OBJECT) &&
                proof.primReps?.singleOrNull() in setOf(LIFTED_REP, MUTVAR_REP)
        }
        if (actual.size != arguments.size || flags.size != arguments.size)
            throw RuntimeFault("Primitive arity mismatch: $primitive")
        if (actual.indices.any { !matches(actual[it], arguments[it]) ||
                flags[it] != (actual[it].primReps == listOf(LIFTED_REP)) })
            throw RuntimeFault("MutVar primitive argument representation mismatch: $primitive")
        val valid = if (tuple) result.isTuple && result.components!!.size == 2 &&
            matches(result.components[0], "state") && matches(result.components[1], if (this == NEW) "mutvar" else "boxed") &&
            result.primReps == result.components[1].primReps
        else matches(result, "state")
        if (!valid) throw RuntimeFault("MutVar primitive result representation mismatch: $primitive")
    }
    companion object {
        fun named(name: String): MutVarOp? = entries.firstOrNull { it.primitive == name }
    }
}

internal fun mutVarExpression(operation: MutVarOp, proof: CoreRepresentation, operands: Array<Expr>): Expr =
    when (operation) {
        MutVarOp.NEW -> NewMutVarExpression(operands[0], operands[1])
        MutVarOp.READ -> ReadMutVarExpression(operands[0], operands[1])
        MutVarOp.WRITE -> WriteMutVarExpression(operands[0], operands[1], operands[2])
    }.proven(proof.copy(evaluated = true))

private class NewMutVarExpression(@field:Child private var value: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val stored = value.execute(frame)
        requireVoidCarrier(state.execute(frame))
        FrameAccess.write(frame, slots[offset], ManagedMutVar(stored))
        return null
    }
}
private class ReadMutVarExpression(@field:Child private var cell: Expr,
    @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple primitive requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val reference = ManagedMutVar.require(cell.execute(frame))
        requireVoidCarrier(state.execute(frame))
        FrameAccess.write(frame, slots[offset], reference.value)
        return null
    }
}
private class WriteMutVarExpression(@field:Child private var cell: Expr,
    @field:Child private var value: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any {
        val reference = ManagedMutVar.require(cell.execute(frame))
        val stored = value.execute(frame)
        requireVoidCarrier(state.execute(frame))
        reference.value = stored
        return Unit
    }
}
