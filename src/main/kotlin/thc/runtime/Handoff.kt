// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.TruffleSafepoint
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ControlFlowException
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.ExplodeLoop
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.staticobject.DefaultStaticProperty
import com.oracle.truffle.api.staticobject.StaticShape
import thc.Language

internal const val HANDOFF_PROPERTY = "thc.handoffSlabs"
private val EMPTY_HANDOFF_ARGUMENTS: Array<Any?> = emptyArray()
private object HandoffComplete

/** Mutable generated fields; neither payload arrays nor root references live in a loan. */
open class HandoffStorage(val layout: HandoffLayout) {
    internal var generation = 0L
    internal var live = false
    internal var completedGeneration = -1L
    // Input ownership only: 0 = not an incoming carrier (including durable PAP
    // prefixes), 1 = reusable argument-pool loan, 2 = fresh direct ingress,
    // 3 = fresh carrier materialized by a tail/generic path (also never pool-owned).
    internal var inputMode = 0
}
interface HandoffFactory { fun create(layout: HandoffLayout): HandoffStorage }

/** The key describes physical fields; signedness and liftedness stay in Core proofs. */
class HandoffLayout(language: Language, val id: Int, val reps: List<String>) {
    @field:CompilationFinal(dimensions = 1)
    private val kinds = reps.map { when (it) {
        "long" -> 0; "float" -> 1; "double" -> 2; "reference" -> 3
        "int8-lane" -> 4; "word8-lane" -> 5
        "int16-lane" -> 6; "word16-lane" -> 7
        "int32-lane" -> 8; "word32-lane" -> 9
        else -> fault("Invalid physical handoff field: $it")
    } }.toIntArray()
    @field:CompilationFinal(dimensions = 1)
    private val fields = Array(reps.size) { DefaultStaticProperty("handoff_$it") }
    private val shape = StaticShape.newBuilder(language).also { builder ->
        fields.indices.forEach { i -> builder.property(fields[i], when (kinds[i]) {
            0 -> Long::class.javaPrimitiveType!!
            1 -> Float::class.javaPrimitiveType!!
            2 -> Double::class.javaPrimitiveType!!
            4, 5 -> Byte::class.javaPrimitiveType!!
            6, 7 -> Short::class.javaPrimitiveType!!
            8, 9 -> Int::class.javaPrimitiveType!!
            else -> Any::class.java
        }, false) }
    }.build(HandoffStorage::class.java, HandoffFactory::class.java)
    fun create(): HandoffStorage = shape.factory.create(this)
    fun isLong(index: Int): Boolean = kinds[index] == 0 || kinds[index] >= 4
    fun isFloat(index: Int): Boolean = kinds[index] == 1
    fun isDouble(index: Int): Boolean = kinds[index] == 2
    fun isObject(index: Int): Boolean = kinds[index] == 3
    fun getLong(storage: HandoffStorage, index: Int): Long = when (kinds[index]) {
        0 -> fields[index].getLong(storage)
        4 -> fields[index].getByte(storage).toLong()
        5 -> fields[index].getByte(storage).toLong() and 255L
        6 -> fields[index].getShort(storage).toLong()
        7 -> fields[index].getShort(storage).toLong() and 65535L
        8 -> fields[index].getInt(storage).toLong()
        9 -> fields[index].getInt(storage).toLong() and 4294967295L
        else -> fault("Expected integral handoff field")
    }
    fun getFloat(storage: HandoffStorage, index: Int): Float = fields[index].getFloat(storage)
    fun getDouble(storage: HandoffStorage, index: Int): Double = fields[index].getDouble(storage)
    fun getObject(storage: HandoffStorage, index: Int): Any? = fields[index].getObject(storage)
    fun setObject(storage: HandoffStorage, index: Int, value: Any?) = fields[index].setObject(storage, value)
    fun setLong(storage: HandoffStorage, index: Int, value: Long) = when (kinds[index]) {
        0 -> fields[index].setLong(storage, value)
        4, 5 -> fields[index].setByte(storage, value.toByte())
        6, 7 -> fields[index].setShort(storage, value.toShort())
        8, 9 -> fields[index].setInt(storage, value.toInt())
        else -> fault("Expected integral handoff field")
    }
    fun setFloat(storage: HandoffStorage, index: Int, value: Float) = fields[index].setFloat(storage, value)
    fun setDouble(storage: HandoffStorage, index: Int, value: Double) = fields[index].setDouble(storage, value)
    @ExplodeLoop fun copyIn(storage: HandoffStorage, values: Array<Any?>) {
        check(values.size == fields.size)
        for (i in fields.indices) {
            when (kinds[i]) {
                0, 4, 5, 6, 7, 8, 9 -> setLong(storage, i, values[i] as? Long ?: fault("Invalid primitive handoff field"))
                1 -> fields[i].setFloat(storage, values[i] as? Float ?: fault("Invalid Float handoff field"))
                2 -> fields[i].setDouble(storage, values[i] as? Double ?: fault("Invalid Double handoff field"))
                else -> fields[i].setObject(storage, values[i])
            }
        }
    }
    @ExplodeLoop fun clearReferences(storage: HandoffStorage) {
        for (i in fields.indices) if (isObject(i)) fields[i].setObject(storage, null)
    }
    companion object {
        private val LONG_REPS = setOf("IntRep", "WordRep", "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep", "Int32Rep", "Word32Rep", "Int64Rep", "Word64Rep")
        // Scalar argument handoff still uses only Long/reference snapshots.
        internal fun supports(rep: String): Boolean = rep in LONG_REPS || rep.startsWith("BoxedRep ")
        internal fun supportsResult(rep: String): Boolean = supports(rep) || rep in setOf("FloatRep", "DoubleRep", "AddrRep")
        internal fun fieldKind(rep: String): String = when {
            rep == "VectorLane Int8Rep" -> "int8-lane"
            rep == "VectorLane Word8Rep" -> "word8-lane"
            rep == "VectorLane Int16Rep" -> "int16-lane"
            rep == "VectorLane Word16Rep" -> "word16-lane"
            rep == "VectorLane Int32Rep" -> "int32-lane"
            rep == "VectorLane Word32Rep" -> "word32-lane"
            rep in LONG_REPS -> "long"
            rep == "FloatRep" -> "float"
            rep == "DoubleRep" -> "double"
            // Addr# is an owned managed reference here, never a raw native word.
            rep == "AddrRep" -> "reference"
            rep.startsWith("BoxedRep ") -> "reference"
            else -> fault("Unsupported handoff representation: $rep")
        }
    }
}

/** Owned by Language.State so separate contexts never share mutable layout interning. */
internal class HandoffLayouts(private val language: Language) {
    val enabled = java.lang.Boolean.getBoolean(HANDOFF_PROPERTY)
    private val layouts = HashMap<List<String>, HandoffLayout>()
    @Synchronized fun intern(reps: List<String>): HandoffLayout {
        val fields = reps.map(HandoffLayout::fieldKind)
        return layouts.getOrPut(fields) { HandoffLayout(language, layouts.size, fields) }
    }
}

/** One incoming loan at a time: operands are evaluated before acquisition and entry
 * copies the loan into durable locals before any guest code can run. */
internal class HandoffPool {
    // Indexed metadata holds carrier identities; generated fields hold all payloads.
    private var slots: Array<HandoffStorage?> = arrayOfNulls(4)
    private var active: HandoffStorage? = null
    val depth: Int get() = if (active == null) 0 else 1
    var allocations = 0L
        private set
    fun acquire(layout: HandoffLayout): HandoffStorage {
        check(active == null)
        val alternatives = if (layout.id < slots.size) slots else grow(layout.id)
        val storage = alternatives[layout.id] ?: allocate(layout, alternatives)
        check(!storage.live)
        storage.live = true
        storage.generation++
        active = storage
        return storage
    }
    @CompilerDirectives.TruffleBoundary private fun grow(layoutId: Int): Array<HandoffStorage?> =
        slots.copyOf(maxOf(slots.size * 2, layoutId + 1)).also { slots = it }
    @CompilerDirectives.TruffleBoundary private fun allocate(layout: HandoffLayout, alternatives: Array<HandoffStorage?>): HandoffStorage =
        layout.create().also { alternatives[layout.id] = it; allocations++ }
    fun release(storage: HandoffStorage, layout: HandoffLayout = storage.layout) {
        check(active === storage && storage.live)
        layout.clearReferences(storage)
        storage.live = false
        active = null
    }
    internal fun retainedReferences(): Int = slots.sumOf { storage ->
        if (storage == null) 0 else storage.layout.reps.indices.count { storage.layout.isObject(it) && storage.layout.getObject(storage, it) != null }
    }
}

internal class HandoffState {
    val arguments = HandoffPool()
    val results = TupleResultPool()
    var pending: HandoffStorage? = null
    // Synchronous Long-only return register. Consume immediately after the completion token.
    var returnLong = 0L
    var calls = 0L
    var tailTransfers = 0L
}

/** The transfer owns its argument loan; it carries no escaping or outstanding scalar result. */
internal class HandoffTailCall(val target: RootCallTarget, val arguments: HandoffStorage) : ControlFlowException()

internal class HandoffEntry(
    private val language: Language,
    val arguments: HandoffLayout,
    val resultLong: Boolean,
    @field:CompilationFinal(dimensions = 1) val snapshotSlots: IntArray,
    val destinationSlot: Int
) {
    fun state(): HandoffState = language.handoffState.get()
    fun destination(frame: VirtualFrame): Int = frame.getLong(destinationSlot).toInt()
    fun initializeOrdinary(frame: VirtualFrame) { frame.setLong(destinationSlot, -1L) }
    @ExplodeLoop fun snapshot(frame: VirtualFrame, input: HandoffStorage) {
        check(input.layout === arguments && input.live)
        for (i in snapshotSlots.indices) {
            if (arguments.isLong(i)) FrameAccess.writeLong(frame, snapshotSlots[i], arguments.getLong(input, i))
            else FrameAccess.write(frame, snapshotSlots[i], arguments.getObject(input, i))
        }
    }
    fun finishLong(frame: VirtualFrame, value: Long): Any {
        check(destination(frame) >= 0)
        state().returnLong = value
        return HandoffComplete
    }
    companion object {
        fun create(language: TruffleLanguage<*>?, layout: FrameLayout, argumentReps: List<CoreRepresentation>,
                   resultRep: CoreRepresentation, hasEnvironment: Boolean): HandoffEntry? {
            val thc = language as? Language ?: return null
            if (argumentReps.any { it.isTuple && !it.isEmptyTuple }) return null
            if (!thc.handoffLayouts.enabled || resultRep.isAggregate) return null
            val resultReference = resultRep.primReps?.singleOrNull()?.startsWith("BoxedRep ") == true
            if (!resultRep.isLong && !resultReference) return null
            val reps = argumentReps.filterNot { it.isEmptyTuple }.map { it.primReps?.singleOrNull() ?: return null }
            if (!reps.all(HandoffLayout::supports)) return null
            val packetReps = listOf("WordRep") + (if (hasEnvironment) listOf("BoxedRep (Just Unlifted)") else emptyList()) + reps
            return HandoffEntry(thc, thc.handoffLayouts.intern(packetReps), resultRep.isLong,
                IntArray(packetReps.size) { layout.bind("<handoff entry $it>") }, layout.bind("<handoff result destination>"))
        }
    }
}

/** Opt-in dense argument transport. The actual Truffle boundary still receives an empty Object[]. */
internal class HandoffCaller(private val target: RootCallTarget, private val entry: HandoffEntry, private val metrics: Metrics) : Node() {
    @Child private var trampolineDispatch = TargetCache(metrics)

    fun call(frame: VirtualFrame, packet: Array<Any?>, callNode: DirectCallNode, tail: Boolean): Any? {
        val state = entry.state()
        val inherited = if (tail) (rootNode as? FunctionRoot)?.handoffDestination(frame) ?: -1 else -1
        // A tail chain entered through the old ABI has no typed receiving continuation yet.
        check(!tail || inherited >= 0)
        val input = state.arguments.acquire(entry.arguments)
        val generation = input.generation
        var transferred = false
        try {
            if (tail) packet[0] = 0L
            entry.arguments.copyIn(input, packet)
            if (tail) {
                val source = rootNode as GuestRoot
                val mask = source.bloom(frame)
                val destination = target.rootNode as GuestRoot
                if (mask and destination.mask == destination.mask) {
                    transferred = true
                    if (metrics.enabled) { state.tailTransfers++; metrics.incrementTailBounces() }
                    throw HandoffTailCall(target, input)
                }
                entry.arguments.setLong(input, 0, mask)
            }
            return invoke(state, input) { Calls.direct(callNode, EMPTY_HANDOFF_ARGUMENTS) }
        } catch (transfer: HandoffTailCall) {
            if (tail) throw transfer
            return trampoline(state, transfer)
        } finally {
            // Entry consumed/released this loan; a later tail may reuse its storage.
            if (!transferred && input.live && input.generation == generation) state.arguments.release(input, entry.arguments)
        }
    }

    private inline fun invoke(state: HandoffState, input: HandoffStorage, action: () -> Any?): Any? {
        val oldPending = state.pending
        check(oldPending == null)
        state.pending = input
        if (metrics.enabled) state.calls++
        try {
            val result = action()
            // Decode only our private token: an ordinary boxed result retains identity.
            return if (result === HandoffComplete) state.returnLong else result
        } finally { state.pending = oldPending }
    }

    @CompilerDirectives.TruffleBoundary private fun releaseUnknown(state: HandoffState, input: HandoffStorage) { state.arguments.release(input) }

    private fun trampoline(state: HandoffState, initial: HandoffTailCall): Any? {
        var transfer = initial
        while (true) {
            if (metrics.enabled) metrics.incrementTrampolineIterations()
            val next = transfer
            val generation = next.arguments.generation
            try {
                // A pending loan must remain inside the generation-checked
                // cleanup when an asynchronous action arrives at this poll.
                TruffleSafepoint.poll(this)
                return invoke(state, next.arguments) { trampolineDispatch.call(next.target, EMPTY_HANDOFF_ARGUMENTS) }
            } catch (tail: HandoffTailCall) { transfer = tail }
            finally {
                // A failed entry still owns its loan. Successfully consumed entries release it.
                if (next.arguments.live && next.arguments.generation == generation) releaseUnknown(state, next.arguments)
            }
        }
    }
}
