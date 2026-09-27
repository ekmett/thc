// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ExplodeLoop
import java.util.concurrent.Callable

/** Logical alternatives stay distinct from GHC's shared physical slots.
 * Native WordSlot/Word64Slot storage stays Long. Narrow Int payloads convert
 * explicitly at projection boundaries; the original GHC proof is unchanged. */
internal object SumShape {
    private const val lifted = "BoxedRep (Just Lifted)"
    private const val unlifted = "BoxedRep (Just Unlifted)"
    private val order = listOf(lifted, unlifted, "WordRep", "Word64Rep", "FloatRep", "DoubleRep")
    // The deriving Ord order in GHC.Core.TyCon.PrimElemRep, not lexical order.
    private val elements = listOf("Int8ElemRep", "Int16ElemRep", "Int32ElemRep", "Int64ElemRep",
        "Word8ElemRep", "Word16ElemRep", "Word32ElemRep", "Word64ElemRep", "FloatElemRep", "DoubleElemRep")
    private val slotOrder = Comparator<String> { left, right ->
        val l = order.indexOf(left).let { if (it < 0) order.size else it }
        val r = order.indexOf(right).let { if (it < 0) order.size else it }
        if (l != r) l.compareTo(r) else if (l < order.size) 0 else {
            val a = left.split(' '); val b = right.split(' ')
            val count = a[1].toInt().compareTo(b[1].toInt())
            if (count != 0) count else elements.indexOf(a[2]).compareTo(elements.indexOf(b[2]))
        }
    }
    private fun slot(rep: String): String = when (rep) {
        "IntRep", "WordRep", "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep", "Int32Rep", "Word32Rep", "AddrRep" -> "WordRep"
        "Int64Rep", "Word64Rep" -> "Word64Rep"
        lifted, unlifted, "FloatRep", "DoubleRep" -> rep
        else -> if (rep.startsWith("VecRep ")) rep
            else throw UnsupportedCore("Unsupported Core aggregate representation: unboxed-sum field $rep")
    }
    private fun fits(left: String, right: String): String? = when {
        left == right -> left
        left in setOf("WordRep", "Word64Rep") && right in setOf("WordRep", "Word64Rep") -> "Word64Rep"
        else -> null
    }
    // GHC.Types.RepType.ubxSumRepType merges each sorted alternative in order.
    // Counting each slot class independently is wrong for mixed Word/Word64.
    private fun merge(existing: List<String>, needed: List<String>): List<String> {
        val result = ArrayList<String>()
        var left = 0
        var right = 0
        while (left < existing.size && right < needed.size) {
            val common = fits(existing[left], needed[right])
            when {
                common != null -> { result += common; left++; right++ }
                slotOrder.compare(needed[right], existing[left]) < 0 -> result += needed[right++]
                else -> result += existing[left++]
            }
        }
        result.addAll(existing.drop(left))
        result.addAll(needed.drop(right))
        return result
    }
    fun validate(proof: CoreRepresentation) {
        val alternatives = proof.alternatives ?: fault("Missing sum alternatives")
        if (proof.kind != CoreKind.UNKNOWN || proof.components != null || proof.vector != null)
            fault("Sum proof must retain its aggregate kind")
        if (alternatives.size < 2)
            throw UnsupportedCore("Unsupported Core aggregate representation: unboxed-sum requires at least two alternatives")
        val fields = alternatives.map { nativePayload(it).map(::slot) }
        val physical = listOf("WordRep") + fields.fold(emptyList<String>()) { slots, row ->
            merge(slots, row.sortedWith(slotOrder))
        }
        if (proof.primReps != physical || proof.tagSlot != 0) fault("Sum physical representation or tag slot mismatch")
        val expected = fields.map { row ->
            val used = mutableSetOf<Int>()
            row.map { rep -> physical.indices.first {
                it > 0 && it !in used && fits(rep, physical[it]) == physical[it]
            }.also(used::add) }
        }
        if (proof.alternativeSlots != expected) fault("Sum alternative projection mismatch")
    }
    /** GHC's original physical proof is never rewritten to describe JVM storage. */
    private fun nativePayload(proof: CoreRepresentation): List<String> {
        when {
            proof.isSum -> validate(proof)
            proof.isTuple -> {
                if (proof.kind != CoreKind.UNKNOWN || proof.primReps != proof.components!!.flatMap(::nativePayload))
                    fault("Sum tuple payload components disagree with physical representations")
            }
            proof.isVector -> VectorLayout.validate(proof)
            proof.kind == CoreKind.VOID -> if (proof.primReps != emptyList<String>()) fault("Sum void payload has registers")
            proof.kind == CoreKind.ADDRESS -> if (proof.primReps != listOf("AddrRep") || !proof.evaluated)
                fault("Sum address payload requires an evaluated managed carrier")
            proof.kind !in setOf(CoreKind.LONG, CoreKind.FLOAT, CoreKind.DOUBLE, CoreKind.DATA, CoreKind.CLOSURE, CoreKind.OBJECT) ->
                throw UnsupportedCore("Unsupported Core aggregate representation: unboxed-sum payload")
        }
        return proof.primReps ?: throw UnsupportedCore("Unsupported Core aggregate representation: unboxed-sum unresolved field")
    }

    internal class Transport(val fields: List<CoreRepresentation>, val nativeSlots: List<Int>,
        val projections: List<List<Int>> = emptyList())
    private data class Register(val native: Int, val address: Boolean)

    /** One native WordSlot may hold either integral bits or Addr#. The latter is
     * a traced managed reference here, so only that collision needs a second
     * field. Nested projections compose through the unchanged native slot map. */
    fun transport(proof: CoreRepresentation): Transport {
        validate(proof)
        val registers = linkedMapOf(Register(0, false) to scalarStorage("WordRep"))
        val alternatives = proof.alternatives!!.mapIndexed { index, alternative ->
            val payload = payloadTransport(alternative)
            payload.fields.mapIndexed { field, leaf ->
                val native = proof.alternativeSlots!![index][payload.nativeSlots[field]]
                val key = Register(native, leaf.kind == CoreKind.ADDRESS)
                registers.putIfAbsent(key, if (key.address) leaf else scalarStorage(proof.primReps!![native]))
                key
            }
        }
        val keys = registers.keys.sortedWith(compareBy<Register> { it.native }.thenBy { it.address })
        return Transport(keys.map { registers.getValue(it) }, keys.map { it.native },
            alternatives.map { row -> row.map(keys::indexOf) })
    }
    private fun payloadTransport(proof: CoreRepresentation): Transport = when {
        proof.isSum -> transport(proof)
        proof.isTuple -> {
            val fields = ArrayList<CoreRepresentation>(); val native = ArrayList<Int>()
            var offset = 0
            for (component in proof.components!!) {
                val child = payloadTransport(component)
                fields.addAll(child.fields); native.addAll(child.nativeSlots.map { it + offset })
                offset += component.primReps!!.size
            }
            Transport(fields, native)
        }
        proof.kind == CoreKind.VOID -> Transport(emptyList(), emptyList())
        else -> Transport(listOf(proof), listOf(0))
    }
    private fun scalarStorage(rep: String): CoreRepresentation {
        val vector = if (rep.startsWith("VecRep ")) rep.split(' ').let { CoreVector(it[1].toInt(), it[2]) } else null
        return CoreRepresentation(when {
            vector != null -> CoreKind.VECTOR
            rep == "WordRep" || rep == "Word64Rep" -> CoreKind.LONG
            rep == "FloatRep" -> CoreKind.FLOAT
            rep == "DoubleRep" -> CoreKind.DOUBLE
            else -> CoreKind.OBJECT
        }, true, true, listOf(rep), vector = vector)
    }
    fun storage(proof: CoreRepresentation): List<CoreRepresentation> = transport(proof).fields
    fun projection(proof: CoreRepresentation, alternative: Int): List<Int> = transport(proof).projections[alternative]
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
    fun checkedTag(value: Long, arity: Int): Int {
        if (arity < 2 || value < 1L || value > arity.toLong()) fault("Invalid unboxed sum tag")
        return value.toInt()
    }
}

internal class SumConstruct(private val shape: TupleShape, private val tag: Int,
    @Child private var payload: Expr,
    @field:CompilationFinal(dimensions = 1) private val intSlots: IntArray = IntArray(0)) : Expr() {
    private val proof = shape.proof.alternatives!![tag - 1]
    @field:CompilationFinal(dimensions = 1) private val integers = TupleShape.flatten(proof).map { it.narrowInteger }.toTypedArray()
    @field:CompilationFinal(dimensions = 1) private val projection = SumShape.projection(shape.proof, tag - 1).toIntArray()
    @field:CompilationFinal(dimensions = 1) private val emptyReferences = shape.leaves.map { field -> when {
        field.kind == CoreKind.ADDRESS -> ManagedAddress.nullAddress()
        field.isVector -> VectorLayout(field).species.zero()
        else -> null
    } }.toTypedArray()
    private class Mapping(val destination: IntArray, val offset: Int,
        @field:CompilationFinal(dimensions = 1) val fields: IntArray)
    @field:CompilationFinal @Volatile private var mapping: Mapping? = null
    init {
        if (proof.isTypedTransport && integers.any { it != null } &&
            (intSlots.size != integers.size || integers.indices.any { integers[it] != null && intSlots[it] < 0 }))
            fault("Missing narrow sum payload scratch slots")
        representation = shape.proof.copy(evaluated = true)
    }
    override fun execute(frame: VirtualFrame): Nothing = fault("Sum value requires a typed destination")
    @ExplodeLoop override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val selected = mapping ?: run {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate()
            atomic(Callable {
                mapping ?: Mapping(slots, offset, IntArray(projection.size) {
                    if (proof.isTypedTransport && integers[it] != null) intSlots[it] else slots[offset + projection[it]]
                })
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
            else FrameAccess.write(frame, target, emptyReferences[index])
        }
        val fields = selected.fields
        when {
            proof.isTypedTransport -> payload.executeTuple(frame, fields, 0)
            proof.kind == CoreKind.VOID -> requireVoidCarrier(payload.execute(frame))
            proof.isInt -> FrameAccess.writeLong(frame, fields[0], proof.narrowInteger!!.widen(payload.executeRequiredInt(frame)))
            proof.isLong -> FrameAccess.writeLong(frame, fields[0], payload.executeRequiredLong(frame))
            proof.isFloat -> FrameAccess.writeFloat(frame, fields[0], payload.executeRequiredFloat(frame))
            proof.isDouble -> FrameAccess.writeDouble(frame, fields[0], payload.executeRequiredDouble(frame))
            else -> FrameAccess.write(frame, fields[0], payload.execute(frame))
        }
        if (proof.isTypedTransport) for (index in integers.indices) {
            val integer = integers[index] ?: continue
            FrameAccess.writeLong(frame, slots[offset + projection[index]], integer.widen(frame.getInt(fields[index])))
        }
        FrameAccess.writeLong(frame, slots[offset], tag.toLong())
        return null
    }
}

internal class SumCase(@Child private var scrutinee: Expr,
    @field:CompilationFinal(dimensions = 1) private val slots: IntArray,
    @Children private var alternatives: Array<Expr>,
    @field:CompilationFinal(dimensions = 1) private val tagToArm: IntArray,
    proof: CoreRepresentation) : Expr() {
    init { representation = proof }
    // Probability injection belongs on the actual control edges below, not
    // on tag-to-arm array indexing (which Graal lowers as a data selection).
    @field:CompilationFinal(dimensions = 1)
    private val armProfiles = Array(alternatives.size) { com.oracle.truffle.api.profiles.CountingConditionProfile.create() }

    private enum class Route { GENERIC, INT, LONG, FLOAT, DOUBLE, CLOSURE, DATA, ADDRESS, TUPLE }

    private class ResumeBranch(private val node: SumCase, private val route: Route,
                               private val destination: IntArray?, private val offset: Int) : AstResumeStep {
        override fun resume(frame: VirtualFrame, input: Any?): Any? {
            if (input != null) fault("Invalid AST sum-case resume value")
            return node.resumeBranch(frame, route, destination, offset)
        }
    }

    private fun selected(frame: VirtualFrame): Int {
        val tag = SumShape.checkedTag(frame.getLong(slots[0]), tagToArm.size)
        return tagToArm[tag - 1]
    }

    private fun prepare(frame: VirtualFrame, route: Route,
                        destination: IntArray? = null, offset: Int = 0): Int {
        try { scrutinee.executeTuple(frame, slots, 0) }
        catch (cut: AstCapture) { throw cut.append(ResumeBranch(this, route, destination, offset)) }
        return selected(frame)
    }

    /** The scrutinee has already populated this activation's sum slots. */
    @ExplodeLoop private fun resumeBranch(frame: VirtualFrame, route: Route, destination: IntArray?, offset: Int): Any? {
        val selected = selected(frame)
        // A resumed scrutinee observes the same arm edges as ordinary entry.
        for (index in alternatives.indices) if (armProfiles[index].profile(selected == index)) {
            val branch = alternatives[index]
            return when (route) {
                Route.GENERIC -> branch.execute(frame)
                Route.INT -> branch.executeInt(frame)
                Route.LONG -> branch.executeLong(frame)
                Route.FLOAT -> branch.executeFloat(frame)
                Route.DOUBLE -> branch.executeDouble(frame)
                Route.CLOSURE -> branch.executeClosure(frame)
                Route.DATA -> branch.executeDataValue(frame)
                Route.ADDRESS -> branch.executeAddress(frame)
                Route.TUPLE -> branch.executeTuple(frame, destination!!, offset)
            }
        }
        fault("Non-exhaustive unboxed sum case")
    }
    @ExplodeLoop override fun execute(frame: VirtualFrame): Any? {
        val selected = prepare(frame, Route.GENERIC)
        for (index in alternatives.indices) if (armProfiles[index].profile(selected == index)) return alternatives[index].execute(frame)
        fault("Non-exhaustive unboxed sum case")
    }
    @ExplodeLoop override fun executeInt(frame: VirtualFrame): Int {
        val selected = prepare(frame, Route.INT)
        for (index in alternatives.indices) if (armProfiles[index].profile(selected == index)) return alternatives[index].executeInt(frame)
        fault("Non-exhaustive unboxed sum case")
    }
    @ExplodeLoop override fun executeLong(frame: VirtualFrame): Long {
        val selected = prepare(frame, Route.LONG)
        for (index in alternatives.indices) if (armProfiles[index].profile(selected == index)) return alternatives[index].executeLong(frame)
        fault("Non-exhaustive unboxed sum case")
    }
    @ExplodeLoop override fun executeFloat(frame: VirtualFrame): Float {
        val selected = prepare(frame, Route.FLOAT)
        for (index in alternatives.indices) if (armProfiles[index].profile(selected == index)) return alternatives[index].executeFloat(frame)
        fault("Non-exhaustive unboxed sum case")
    }
    @ExplodeLoop override fun executeDouble(frame: VirtualFrame): Double {
        val selected = prepare(frame, Route.DOUBLE)
        for (index in alternatives.indices) if (armProfiles[index].profile(selected == index)) return alternatives[index].executeDouble(frame)
        fault("Non-exhaustive unboxed sum case")
    }
    @ExplodeLoop override fun executeClosure(frame: VirtualFrame): Closure {
        val selected = prepare(frame, Route.CLOSURE)
        for (index in alternatives.indices) if (armProfiles[index].profile(selected == index)) return alternatives[index].executeClosure(frame)
        fault("Non-exhaustive unboxed sum case")
    }
    @ExplodeLoop override fun executeDataValue(frame: VirtualFrame): DataValue {
        val selected = prepare(frame, Route.DATA)
        for (index in alternatives.indices) if (armProfiles[index].profile(selected == index)) return alternatives[index].executeDataValue(frame)
        fault("Non-exhaustive unboxed sum case")
    }
    @ExplodeLoop override fun executeAddress(frame: VirtualFrame): ManagedAddress {
        val selected = prepare(frame, Route.ADDRESS)
        for (index in alternatives.indices) if (armProfiles[index].profile(selected == index)) return alternatives[index].executeAddress(frame)
        fault("Non-exhaustive unboxed sum case")
    }
    @ExplodeLoop override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val selected = prepare(frame, Route.TUPLE, slots, offset)
        for (index in alternatives.indices) if (armProfiles[index].profile(selected == index)) return alternatives[index].executeTuple(frame, slots, offset)
        fault("Non-exhaustive unboxed sum case")
    }
}
