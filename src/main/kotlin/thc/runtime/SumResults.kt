// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ExplodeLoop
import java.util.concurrent.Callable

/** Binary sums retain their logical alternatives and exact GHC storage projections.
 * This first slice deliberately needs no integer-width conversion. */
internal object SumShape {
    private const val lifted = "BoxedRep (Just Lifted)"
    private const val unlifted = "BoxedRep (Just Unlifted)"
    private val order = listOf(lifted, unlifted, "WordRep", "FloatRep", "DoubleRep")
    private fun slot(rep: String): String = when (rep) {
        "IntRep", "WordRep" -> "WordRep"
        lifted, unlifted, "FloatRep", "DoubleRep" -> rep
        else -> throw UnsupportedCore("Unsupported Core aggregate representation: unboxed-sum field $rep")
    }
    fun validate(proof: CoreRepresentation) {
        val alternatives = proof.alternatives ?: fault("Missing sum alternatives")
        if (proof.kind != CoreKind.UNKNOWN || proof.components != null || proof.vector != null)
            fault("Sum proof must retain its aggregate kind")
        if (alternatives.size != 2)
            throw UnsupportedCore("Unsupported Core aggregate representation: unboxed-sum requires two alternatives")
        val fields = alternatives.map { alternative ->
            val leaves = TupleShape.flatten(alternative)
            if (leaves.any { it.isSum || it.isVector || it.kind !in setOf(CoreKind.LONG, CoreKind.FLOAT,
                    CoreKind.DOUBLE, CoreKind.DATA, CoreKind.CLOSURE, CoreKind.OBJECT) })
                throw UnsupportedCore("Unsupported Core aggregate representation: unboxed-sum payload")
            leaves.map { slot(it.primReps?.singleOrNull()
                ?: throw UnsupportedCore("Unsupported Core aggregate representation: unboxed-sum unresolved field")) }
        }
        val physical = listOf("WordRep") + order.flatMap { rep ->
            List(fields.maxOf { row -> row.count { it == rep } }) { rep }
        }
        if (proof.primReps != physical || proof.tagSlot != 0) fault("Sum physical representation or tag slot mismatch")
        val expected = fields.map { row ->
            val used = mutableSetOf<Int>()
            row.map { rep -> physical.indices.first { it > 0 && it !in used && physical[it] == rep }.also(used::add) }
        }
        if (proof.alternativeSlots != expected) fault("Sum alternative projection mismatch")
    }
    fun storage(proof: CoreRepresentation): List<CoreRepresentation> = proof.primReps!!.map { rep ->
        CoreRepresentation(when (rep) {
            "WordRep" -> CoreKind.LONG
            "FloatRep" -> CoreKind.FLOAT
            "DoubleRep" -> CoreKind.DOUBLE
            else -> CoreKind.OBJECT
        }, true, true, listOf(rep))
    }
    fun constructor(proof: CoreRepresentation, info: Map<String, Any?>?, arity: Any?): Int {
        fun exact(value: Any?, expected: Int) = (value is Long || value is Int) && (value as Number).toLong() == expected.toLong()
        if (info?.get("kind") != "unboxed-sum" || !exact(info["arity"], 1) || !exact(arity, 1) ||
            !exact(info["sumArity"], proof.alternatives!!.size))
            fault("Sum constructor family or payload arity mismatch")
        val raw = info["tag"]
        if (raw !is Long && raw !is Int) fault("Invalid sum constructor tag")
        raw as Number
        val tag = raw.toInt()
        if (raw.toDouble() != tag.toDouble() || tag !in 1..proof.alternatives.size) fault("Invalid sum constructor tag")
        return tag
    }
    fun payload(expected: CoreRepresentation, actual: CoreRepresentation, liftedFlag: Boolean? = null) {
        if (!actual.present || !TupleShape.compatible(expected, actual)) fault("Sum payload logical shape mismatch")
        if (liftedFlag != null && liftedFlag != (!expected.isAggregate && expected.primReps == listOf(lifted)))
            fault("Sum payload levity mismatch")
    }
    fun checkedTag(value: Long): Int {
        if (value != 1L && value != 2L) fault("Invalid unboxed sum tag")
        return value.toInt()
    }
}

internal class SumConstruct(private val shape: TupleShape, private val tag: Int,
    @Child private var payload: Expr) : Expr() {
    private val proof = shape.proof.alternatives!![tag - 1]
    @field:CompilationFinal(dimensions = 1) private val projection = shape.proof.alternativeSlots!![tag - 1].toIntArray()
    private class Mapping(val destination: IntArray, val offset: Int, val fields: IntArray)
    @field:CompilationFinal @Volatile private var mapping: Mapping? = null
    init { representation = shape.proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("Sum value requires a typed destination")
    @ExplodeLoop override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val selected = mapping ?: run {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate()
            atomic(Callable {
                mapping ?: Mapping(slots, offset, IntArray(projection.size) { slots[offset + projection[it]] })
                    .also { mapping = it }
            })
        }
        check(selected.destination === slots && selected.offset == offset)
        // Clear every inactive slot before reuse, including references. No result
        // loan exists while the selected payload evaluates or throws.
        for (index in shape.leaves.indices) {
            val target = slots[offset + index]
            if (shape.layout.isLong(index)) FrameAccess.writeLong(frame, target, 0L)
            else if (shape.layout.isFloat(index)) FrameAccess.writeFloat(frame, target, 0.0f)
            else if (shape.layout.isDouble(index)) FrameAccess.writeDouble(frame, target, 0.0)
            else FrameAccess.write(frame, target, null)
        }
        val fields = selected.fields
        when {
            proof.isTuple -> payload.executeTuple(frame, fields, 0)
            proof.kind == CoreKind.VOID -> requireVoidCarrier(payload.execute(frame))
            proof.isLong -> FrameAccess.writeLong(frame, fields[0], payload.executeRequiredLong(frame))
            proof.isFloat -> FrameAccess.writeFloat(frame, fields[0], payload.executeRequiredFloat(frame))
            proof.isDouble -> FrameAccess.writeDouble(frame, fields[0], payload.executeRequiredDouble(frame))
            else -> FrameAccess.write(frame, fields[0], payload.execute(frame))
        }
        FrameAccess.writeLong(frame, slots[offset], tag.toLong())
        return null
    }
}

internal class SumCase(@Child private var scrutinee: Expr,
    @field:CompilationFinal(dimensions = 1) private val slots: IntArray,
    @Children private var alternatives: Array<Expr>, private val first: Int, private val second: Int,
    proof: CoreRepresentation) : Expr() {
    init { representation = proof }
    private val tagProfile = com.oracle.truffle.api.profiles.CountingConditionProfile.create()
    private fun prepare(frame: VirtualFrame): Boolean {
        scrutinee.executeTuple(frame, slots, 0)
        return tagProfile.profile(SumShape.checkedTag(frame.getLong(slots[0])) == 1)
    }
    override fun execute(frame: VirtualFrame): Any? {
        if (prepare(frame)) {
            if (first < 0) fault("Non-exhaustive unboxed sum case")
            return alternatives[first].execute(frame)
        }
        if (second < 0) fault("Non-exhaustive unboxed sum case")
        return alternatives[second].execute(frame)
    }
    override fun executeLong(frame: VirtualFrame): Long {
        if (prepare(frame)) {
            if (first < 0) fault("Non-exhaustive unboxed sum case")
            return alternatives[first].executeLong(frame)
        }
        if (second < 0) fault("Non-exhaustive unboxed sum case")
        return alternatives[second].executeLong(frame)
    }
    override fun executeFloat(frame: VirtualFrame): Float {
        if (prepare(frame)) {
            if (first < 0) fault("Non-exhaustive unboxed sum case")
            return alternatives[first].executeFloat(frame)
        }
        if (second < 0) fault("Non-exhaustive unboxed sum case")
        return alternatives[second].executeFloat(frame)
    }
    override fun executeDouble(frame: VirtualFrame): Double {
        if (prepare(frame)) {
            if (first < 0) fault("Non-exhaustive unboxed sum case")
            return alternatives[first].executeDouble(frame)
        }
        if (second < 0) fault("Non-exhaustive unboxed sum case")
        return alternatives[second].executeDouble(frame)
    }
    override fun executeClosure(frame: VirtualFrame): Closure {
        if (prepare(frame)) {
            if (first < 0) fault("Non-exhaustive unboxed sum case")
            return alternatives[first].executeClosure(frame)
        }
        if (second < 0) fault("Non-exhaustive unboxed sum case")
        return alternatives[second].executeClosure(frame)
    }
    override fun executeDataValue(frame: VirtualFrame): DataValue {
        if (prepare(frame)) {
            if (first < 0) fault("Non-exhaustive unboxed sum case")
            return alternatives[first].executeDataValue(frame)
        }
        if (second < 0) fault("Non-exhaustive unboxed sum case")
        return alternatives[second].executeDataValue(frame)
    }
    override fun executeAddress(frame: VirtualFrame): LiteralAddress {
        if (prepare(frame)) {
            if (first < 0) fault("Non-exhaustive unboxed sum case")
            return alternatives[first].executeAddress(frame)
        }
        if (second < 0) fault("Non-exhaustive unboxed sum case")
        return alternatives[second].executeAddress(frame)
    }
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        if (prepare(frame)) {
            if (first < 0) fault("Non-exhaustive unboxed sum case")
            return alternatives[first].executeTuple(frame, slots, offset)
        }
        if (second < 0) fault("Non-exhaustive unboxed sum case")
        return alternatives[second].executeTuple(frame, slots, offset)
    }
}
