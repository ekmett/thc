// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame

/** Carrier and tuple checks only; pinned primop types are checked by the exporter audit. */
internal object CoreThreadScheduling {
    const val FALSE = "ghc-internal:GHC.Internal.Types.False"
    fun named(name: String) = name in setOf("par#", "spark#", "numSparks#", "getSpark#", "delay#",
        "setThreadAllocationCounter#", "setOtherThreadAllocationCounter#")

    fun validate(name: String, arguments: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        fun scalar(rep: CoreRepresentation, kind: CoreKind) = !rep.isAggregate && !rep.isVector && rep.kind == kind
        fun lifted(rep: CoreRepresentation) = !rep.isAggregate && !rep.isVector &&
            rep.kind in setOf(CoreKind.OBJECT, CoreKind.DATA, CoreKind.CLOSURE) &&
            rep.primReps == listOf("BoxedRep (Just Lifted)")
        fun role(rep: CoreRepresentation, kind: CoreKind?) = if (kind == null) lifted(rep) else scalar(rep, kind)
        val inputs: List<CoreKind?> = when (name) {
            "par#" -> listOf(null)
            "spark#" -> listOf(null, CoreKind.VOID)
            "numSparks#", "getSpark#" -> listOf(CoreKind.VOID)
            "delay#", "setThreadAllocationCounter#" -> listOf(CoreKind.LONG, CoreKind.VOID)
            "setOtherThreadAllocationCounter#" -> listOf(CoreKind.LONG, CoreKind.OBJECT, CoreKind.VOID)
            else -> fault("Unknown thread scheduling operation $name")
        }
        val outputs: List<CoreKind?>? = when (name) {
            "spark#" -> listOf(CoreKind.VOID, null)
            "numSparks#" -> listOf(CoreKind.VOID, CoreKind.LONG)
            "getSpark#" -> listOf(CoreKind.VOID, CoreKind.LONG, null)
            else -> null
        }
        val fields = result.components
        val validResult = if (outputs == null) scalar(result, if (name == "par#") CoreKind.LONG else CoreKind.VOID)
            else result.isTuple && !result.isSum && !result.isVector && fields?.size == outputs.size &&
                outputs.indices.all { role(fields[it], outputs[it]) }
        if (arguments.size != inputs.size || flags != inputs.map { it == null } ||
            !inputs.indices.all { role(arguments[it], inputs[it]) } || !validResult)
            fault("$name requires its scalar/lazy operand and ordered state-result carriers")
    }
}

/** THC discards speculative hints; it never evaluates or queues their payloads. */
internal class SparkResult(private val name: String, @field:Child private var state: Expr,
    @field:Child private var payload: Expr?, private val empty: DataValue?, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("Spark result requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val value = payload?.execute(frame) // Retain a thunk, never enter it.
        requireVoidCarrier(state.execute(frame))
        if (name == "spark#") FrameAccess.write(frame, slots[offset], value)
        else {
            FrameAccess.writeLong(frame, slots[offset], 0L)
            if (name == "getSpark#") FrameAccess.write(frame, slots[offset + 1], empty)
        }
        return null
    }
}

internal class SetThreadAllocationCounter(@field:Child private var value: Expr,
    @field:Child private var target: Expr?, @field:Child private var state: Expr,
    proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Any {
        val counter = value.executeRequiredLong(frame)
        val threads = GuestThreads.current(this)
        val identity = if (target == null) threads.currentIdentity()
            else target!!.execute(frame) as? GuestThreadId ?: fault("Allocation counter requires ThreadId#")
        requireVoidCarrier(state.execute(frame))
        threads.setAllocationCounter(counter, identity)
        return Unit
    }
}
