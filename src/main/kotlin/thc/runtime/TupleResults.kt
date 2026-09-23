package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.IndirectCallNode
import com.oracle.truffle.api.nodes.ExplodeLoop
import com.oracle.truffle.api.nodes.Node
import thc.Language

/** Logical tuple boundaries remain distinct even when their physical widths agree. */
internal class TupleShape(val proof: CoreRepresentation, val language: Language) {
    @field:CompilationFinal(dimensions = 1) val components = (proof.components ?: fault("Missing tuple components")).toTypedArray()
    @field:CompilationFinal(dimensions = 1) val leaves = flatten(proof).toTypedArray()
    val layout = language.handoffLayouts.intern(leaves.map { it.primReps!!.single() })
    @field:CompilationFinal(dimensions = 1) val offsets = IntArray(components.size).also { offsets ->
        var next = 0
        components.forEachIndexed { index, component -> offsets[index] = next; next += flatten(component).size }
    }
    val width: Int get() = leaves.size
    private val signature = signature(proof)
    fun matches(other: TupleShape): Boolean = signature == other.signature
    @ExplodeLoop fun copyFrom(frame: VirtualFrame, storage: HandoffStorage, slots: IntArray, offset: Int) {
        check(storage.layout === layout)
        for (index in leaves.indices) {
            if (layout.isLong(index)) FrameAccess.writeLong(frame, slots[offset + index], layout.getLong(storage, index))
            else FrameAccess.write(frame, slots[offset + index], layout.getObject(storage, index))
        }
    }
    @ExplodeLoop private fun write(frame: VirtualFrame, slots: IntArray, storage: HandoffStorage) {
        for (index in leaves.indices) {
            if (layout.isLong(index)) layout.setLong(storage, index, frame.getLong(slots[index]))
            else layout.setObject(storage, index, frame.getObject(slots[index]))
        }
    }
    fun finish(frame: VirtualFrame, slots: IntArray): Any {
        if (inlineResult()) {
            val virtual = layout.create()
            write(frame, slots, virtual)
            CompilerDirectives.ensureVirtualized(virtual)
            return virtual
        }
        val pool = language.handoffState.get().results
        val output = pool.acquire(layout)
        try {
            write(frame, slots, output)
            return pool.complete(output)
        } catch (failure: Throwable) { pool.release(output, layout); throw failure }
    }
    fun consume(frame: VirtualFrame, result: Any?, slots: IntArray, offset: Int) {
        if (result === TupleComplete) {
            val pool = language.handoffState.get().results
            val output = pool.completed()
            try { copyFrom(frame, output, slots, offset) } finally { pool.releaseChecked(output, layout) }
        } else {
            // A deoptimization can materialize this otherwise virtual storage. It
            // owns no pool loan, and remains a valid private carrier in the interpreter.
            copyFrom(frame, result as? HandoffStorage ?: fault("Invalid tuple result carrier"), slots, offset)
        }
    }
    fun inlineResult(): Boolean = CompilerDirectives.inCompiledCode() && !CompilerDirectives.inCompilationRoot()
    companion object {
        fun flatten(proof: CoreRepresentation): List<CoreRepresentation> = proof.components?.flatMap(::flatten)
            ?: if (proof.kind == CoreKind.VOID) emptyList() else listOf(proof)
        fun compatible(left: CoreRepresentation, right: CoreRepresentation): Boolean = signature(left) == signature(right)
        fun requireCompatible(expected: CoreRepresentation, actual: CoreRepresentation, component: Boolean = false) {
            if (actual.present && (component || expected.isTuple || actual.isTuple) && !compatible(expected, actual))
                throw RuntimeFault("Conflicting logical tuple representation proofs")
        }
        private fun signature(proof: CoreRepresentation): Any = if (proof.components != null)
            listOf("tuple", proof.components.map(::signature)) else listOf("scalar", proof.primReps ?: listOf("?"))
        fun validate(proof: CoreRepresentation) {
            if (proof.kind != CoreKind.UNKNOWN) throw RuntimeFault("Tuple proof must retain its aggregate kind")
            if (proof.primReps == null)
                throw UnsupportedCore("Unsupported Core aggregate representation: unboxed-tuple has unresolved fields")
            val fields = flatten(proof)
            if (fields.any { (!it.isLong && it.kind !in setOf(CoreKind.DATA, CoreKind.CLOSURE, CoreKind.OBJECT)) ||
                    it.primReps?.singleOrNull()?.let(HandoffLayout::supports) != true })
                throw UnsupportedCore("Unsupported Core aggregate representation: unboxed-tuple has unsupported fields")
            val reps = fields.map { it.primReps!!.single() }
            if (proof.primReps != reps) throw RuntimeFault("Tuple components disagree with primitive representations")
        }
    }
}

/** Completion is a private control result. It never carries a tuple or a payload array. */
internal object TupleComplete

/** A result register is acquired only after all guest evaluation is finished.
 * Its immediate consumer copies and releases it before running any guest continuation. */
internal class TupleResultPool {
    private val pool = HandoffPool()
    private var current: HandoffStorage? = null
    val depth: Int get() = pool.depth
    val allocations: Long get() = pool.allocations
    fun acquire(layout: HandoffLayout): HandoffStorage {
        check(current == null)
        return pool.acquire(layout).also { current = it }
    }
    fun complete(storage: HandoffStorage): Any {
        check(current === storage && storage.live)
        storage.completedGeneration = storage.generation
        return TupleComplete
    }
    fun completed(): HandoffStorage {
        val storage = current ?: fault("Missing completed tuple result")
        check(storage.live && storage.completedGeneration == storage.generation)
        return storage
    }
    fun release(storage: HandoffStorage, layout: HandoffLayout) {
        check(current === storage)
        pool.release(storage, layout)
        current = null
    }
    fun releaseChecked(storage: HandoffStorage, expected: HandoffLayout) {
        if (storage.layout === expected) release(storage, expected) else releaseMismatched(storage)
    }
    @CompilerDirectives.TruffleBoundary private fun releaseMismatched(storage: HandoffStorage) = release(storage, storage.layout)
    fun retainedReferences(): Int = pool.retainedReferences()
}

/** A destination holds compile-time slot metadata only, never a frame or payload. */
internal abstract class TupleDestination(val shape: TupleShape) {
    abstract fun consume(frame: VirtualFrame, node: Node, result: Any?)
}
private class AstTupleDestination(shape: TupleShape,
    @field:CompilationFinal(dimensions = 1) private val slots: IntArray, private val offset: Int) : TupleDestination(shape) {
    override fun consume(frame: VirtualFrame, node: Node, result: Any?) = shape.consume(frame, result, slots, offset)
}

/** Cache the complete call shape before making its packet. Each arm consumes its
 * result before joining control flow, keeping virtual carriers out of PIC phis. */
internal class TupleDispatch(private val destination: TupleDestination, private val metrics: Metrics,
    private val argsSize: Int, private val tail: Boolean) : Node() {
    @Children private var direct = emptyArray<DirectTupleCaller>()
    @Child private var generic = GenericTupleCaller(destination, metrics, tail)
    @CompilationFinal private var megamorphic = false

    @ExplodeLoop fun execute(frame: VirtualFrame, function: Closure, arguments: Array<Any?>) {
        for (caller in direct) if (caller.matches(function)) {
            caller.execute(frame, function, arguments)
            return
        }
        if (!megamorphic) {
            CompilerDirectives.transferToInterpreterAndInvalidate()
            if (direct.size < 3) {
                val caller = insert(DirectTupleCaller(destination, metrics, argsSize, tail, function))
                direct = direct + caller
                caller.execute(frame, function, arguments)
                return
            }
            megamorphic = true
        }
        generic.execute(frame, function, arguments)
    }
}

private class DirectTupleCaller(private val destination: TupleDestination, metrics: Metrics,
    private val argsSize: Int, private val tail: Boolean, function: Closure) : Node() {
    private val target = function.target
    private val arity = function.arity
    private val prefixSize = function.supplied.size
    private val hasEnvironment = function.environment != null
    @Child private var entry = EntryArguments(target, metrics, prefixSize = prefixSize)
    @Child private var call = DirectCallNode.create(target)
    @Child private var tailCheck = TailCheck(metrics)
    @Child private var bounce = TupleBounce(destination, metrics)
    @Child private var scalar: DirectCallerNode? = if (arity < argsSize) DirectCallerNode(target, metrics, prefixSize = prefixSize) else null
    @Child private var rest: TupleDispatch? = if (arity < argsSize) TupleDispatch(destination, metrics, argsSize - arity, tail) else null
    @Child private var force = Force(metrics)
    init {
        if (metrics.enabled) metrics.directCacheMisses++
        val root = target.rootNode as? GuestRoot ?: fault("Invalid tuple call target")
        if (arity > argsSize) fault("Tuple result application is under-saturated")
        if (arity == argsSize && root.tupleResult?.matches(destination.shape) != true) fault("Tuple call target result shape mismatch")
        if (arity < argsSize && root.tupleResult != null) fault("Cannot overapply an unboxed tuple")
    }
    fun matches(function: Closure): Boolean = function.target === target && function.arity == arity &&
        function.supplied.size == prefixSize && (function.environment != null) == hasEnvironment
    fun execute(frame: VirtualFrame, function: Closure, arguments: Array<Any?>) {
        val skip = if (hasEnvironment) 2 else 1
        val packet = arrayOfNulls<Any>(skip + prefixSize + arity)
        if (hasEnvironment) packet[1] = function.environment
        System.arraycopy(function.supplied, 0, packet, skip, prefixSize)
        System.arraycopy(arguments, 0, packet, skip + prefixSize, arity)
        if (arity < argsSize) {
            val closure = requireClosure(force.execute(frame, scalar!!.call(frame, packet, false)))
            rest!!.execute(frame, closure, arguments.copyOfRange(arity, argsSize))
            return
        }
        entry.execute(frame, packet)
        if (tail) {
            tailCheck.check(frame, target, packet)
            destination.consume(frame, this, Calls.direct(call, packet))
        } else {
            packet[0] = 0L
            try { destination.consume(frame, this, Calls.direct(call, packet)) }
            catch (transfer: TailCall) { bounce.execute(frame, transfer) }
        }
    }
}

/** Residual calls return only the pooled completion token. The loop consumes it
 * directly in the caller frame, without storing a frame or carrier in a node. */
private class TupleBounce(private val destination: TupleDestination, private val metrics: Metrics) : Node() {
    @Child private var call = IndirectCallNode.create()
    fun execute(frame: VirtualFrame, initial: TailCall) {
        var next = initial
        while (true) {
            val root = next.target.rootNode as? GuestRoot ?: fault("Invalid tuple tail target")
            if (root.tupleResult?.matches(destination.shape) != true) fault("Tuple tail target result shape mismatch")
            next.args[0] = 0L
            try {
                if (metrics.enabled) metrics.trampolineIterations++
                destination.consume(frame, this, Calls.indirect(call, next.target, next.args))
                return
            } catch (transfer: TailCall) { next = transfer }
        }
    }
}
private class GenericTupleCaller(private val destination: TupleDestination, private val metrics: Metrics,
    private val tail: Boolean) : Node() {
    @Child private var call = IndirectCallNode.create()
    @Child private var entry = IndirectEntryArguments(metrics)
    @Child private var scalar = IndirectCallerNode(metrics)
    @Child private var force = Force(metrics)
    @Child private var tailCheck = TailCheck(metrics)
    @Child private var bounce = TupleBounce(destination, metrics)
    fun execute(frame: VirtualFrame, initial: Closure, arguments: Array<Any?>) {
        var function = initial
        var offset = 0
        while (true) {
            val remaining = arguments.size - offset
            if (function.arity > remaining) fault("Tuple result application is under-saturated")
            val count = function.arity
            val exact = count == remaining
            val root = function.target.rootNode as? GuestRoot ?: fault("Invalid tuple call target")
            if (exact && root.tupleResult?.matches(destination.shape) != true) fault("Tuple call target result shape mismatch")
            if (!exact && root.tupleResult != null) fault("Cannot overapply an unboxed tuple")
            val skip = if (function.environment == null) 1 else 2
            val packet = arrayOfNulls<Any>(skip + function.supplied.size + count)
            if (function.environment != null) packet[1] = function.environment
            System.arraycopy(function.supplied, 0, packet, skip, function.supplied.size)
            System.arraycopy(arguments, offset, packet, skip + function.supplied.size, count)
            if (!exact) {
                function = requireClosure(force.execute(frame, scalar.call(frame, function.target, packet, false)))
                offset += count
                continue
            }
            entry.execute(frame, function.target, packet)
            if (metrics.enabled) metrics.indirectCalls++
            if (tail) {
                tailCheck.check(frame, function.target, packet)
                destination.consume(frame, this, Calls.indirect(call, function.target, packet))
            } else {
                packet[0] = 0L
                try { destination.consume(frame, this, Calls.indirect(call, function.target, packet)) }
                catch (transfer: TailCall) { bounce.execute(frame, transfer) }
            }
            return
        }
    }
}

/** Tuple reads copy typed current-frame locals; no activation frame is retained. */
internal class TupleLocalRead(private val shape: TupleShape,
    @field:CompilationFinal(dimensions = 1) private val sources: IntArray) : Expr() {
    init { representation = shape.proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple value requires a destination")
    @ExplodeLoop override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        for (index in sources.indices) {
            if (shape.layout.isLong(index)) FrameAccess.writeLong(frame, slots[offset + index], frame.getLong(sources[index]))
            else FrameAccess.write(frame, slots[offset + index], frame.getObject(sources[index]))
        }
        return null
    }
}
internal class TupleConstruct(private val shape: TupleShape, @field:Children private var fields: Array<Expr>) : Expr() {
    init { representation = shape.proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple value requires a destination")
    @ExplodeLoop override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        for (index in fields.indices) {
            val component = shape.components[index]
            val target = offset + shape.offsets[index]
            if (component.isTuple) fields[index].executeTuple(frame, slots, target)
            else if (component.isLong) FrameAccess.writeLong(frame, slots[target], fields[index].executeRequiredLong(frame))
            else if (component.kind == CoreKind.VOID) fields[index].execute(frame)
            else FrameAccess.write(frame, slots[target], fields[index].execute(frame))
        }
        return null
    }
}
internal class TupleApplication(private val language: Language, private val shape: TupleShape,
    function: Expr, @field:Children private var arguments: Array<Expr>, private val tail: Boolean, private val metrics: Metrics) : Expr() {
    @Child private var function = Evaluate(function, metrics)
    @Child private var dispatch: TupleDispatch? = null
    @field:CompilationFinal(dimensions = 1) private var destinationSlots: IntArray? = null
    @CompilationFinal private var destinationOffset = -1
    init { representation = shape.proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("Tuple value requires a destination")
    @ExplodeLoop override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val function = this.function.executeRequiredClosure(frame)
        val values = arrayOfNulls<Any>(arguments.size)
        for (index in arguments.indices) values[index] = arguments[index].execute(frame)
        if (dispatch == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate()
            destinationSlots = slots
            destinationOffset = offset
            dispatch = insert(TupleDispatch(AstTupleDestination(shape, slots, offset), metrics, arguments.size, tail))
        }
        check(destinationSlots === slots && destinationOffset == offset)
        dispatch!!.execute(frame, function, values)
        return null
    }
}
internal class TupleCase(@field:Child private var scrutinee: Expr,
    @field:CompilationFinal(dimensions = 1) private val slots: IntArray,
    @field:Child private var body: Expr) : Expr() {
    init { representation = body.representation }
    private fun prepare(frame: VirtualFrame) { scrutinee.executeTuple(frame, slots, 0) }
    override fun execute(frame: VirtualFrame): Any? { prepare(frame); return body.execute(frame) }
    override fun executeLong(frame: VirtualFrame): Long { prepare(frame); return body.executeLong(frame) }
    override fun executeClosure(frame: VirtualFrame): Closure { prepare(frame); return body.executeClosure(frame) }
    override fun executeDataValue(frame: VirtualFrame): DataValue { prepare(frame); return body.executeDataValue(frame) }
    override fun executeAddress(frame: VirtualFrame): LiteralAddress { prepare(frame); return body.executeAddress(frame) }
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? { prepare(frame); return body.executeTuple(frame, slots, offset) }
}

/** Replay-specific BytecodeLocal accessors, never a frame or guest payload. */
internal class BytecodeTupleSlots(shape: TupleShape,
    @field:CompilationFinal(dimensions = 1) private val slots: Array<com.oracle.truffle.api.bytecode.LocalAccessor>) : TupleDestination(shape) {
    @ExplodeLoop private fun write(frame: VirtualFrame, node: com.oracle.truffle.api.bytecode.BytecodeNode, output: HandoffStorage) {
        for (index in slots.indices) {
            if (shape.layout.isLong(index)) shape.layout.setLong(output, index, slots[index].getLong(node, frame))
            else shape.layout.setObject(output, index, slots[index].getObject(node, frame))
        }
    }
    fun finish(frame: VirtualFrame, node: com.oracle.truffle.api.bytecode.BytecodeNode): Any {
        if (shape.inlineResult()) {
            val virtual = shape.layout.create()
            write(frame, node, virtual)
            CompilerDirectives.ensureVirtualized(virtual)
            return virtual
        }
        val pool = shape.language.handoffState.get().results
        val output = pool.acquire(shape.layout)
        try { write(frame, node, output); return pool.complete(output) }
        catch (failure: Throwable) { pool.release(output, shape.layout); throw failure }
    }
    @ExplodeLoop fun copyFrom(frame: VirtualFrame, node: com.oracle.truffle.api.bytecode.BytecodeNode, receiver: HandoffStorage) {
        for (index in slots.indices) {
            if (shape.layout.isLong(index)) slots[index].setLong(node, frame, shape.layout.getLong(receiver, index))
            else slots[index].setObject(node, frame, shape.layout.getObject(receiver, index))
        }
    }
    override fun consume(frame: VirtualFrame, node: Node, result: Any?) {
        val root = node.rootNode as BytecodeRoot
        if (result === TupleComplete) {
            val pool = shape.language.handoffState.get().results
            val output = pool.completed()
            try {
                check(output.layout === shape.layout)
                copyFrom(frame, root.bytecodeNode, output)
            } finally { pool.releaseChecked(output, shape.layout) }
        } else {
            val carrier = result as? HandoffStorage ?: fault("Invalid tuple result carrier")
            check(carrier.layout === shape.layout)
            copyFrom(frame, root.bytecodeNode, carrier)
        }
    }
}
