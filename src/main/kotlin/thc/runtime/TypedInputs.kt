package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.bytecode.BytecodeNode
import com.oracle.truffle.api.bytecode.LocalAccessor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ExplodeLoop
import com.oracle.truffle.api.nodes.Node
import thc.Language
import java.util.concurrent.Callable

/** Typed physical input fields, separate from both logical shapes and result storage. */
internal class TypedInputLayout(val language: Language, val logical: ArgumentLayout, val hasEnvironment: Boolean) {
    val header = if (hasEnvironment) 2 else 1
    @field:CompilationFinal(dimensions = 1)
    val leaves = logical.physicalProofs
    private val reps = leaves.map(::fieldRep)
    val packet = language.handoffLayouts.intern(listOf("WordRep") +
        (if (hasEnvironment) listOf("BoxedRep (Just Unlifted)") else emptyList()) + reps)
    @field:CompilationFinal(dimensions = 1)
    private val prefixes = Array(logical.logicalArity + 1) { count ->
        language.handoffLayouts.intern(reps.take(logical.offset(count)))
    }
    fun state(): HandoffState = language.handoffState.get()
    fun prefix(count: Int): HandoffLayout = prefixes[count]
    fun take(arguments: Array<Any?>): HandoffStorage {
        if (arguments.size != 1) fault("Tuple input entry requires one typed carrier")
        val input = arguments[0] as? HandoffStorage ?: fault("Tuple input entry requires a typed carrier")
        validate(input)
        return input
    }
    fun validate(input: HandoffStorage) = validate(input, true)
    fun validateTail(input: HandoffStorage) {
        // A TailCall deliberately owns a materialized transfer. A mode-2
        // direct ingress cannot legally arrive through this control object.
        if (input.inputMode == 2) {
            releaseUnexpected(input)
            fault("Direct typed input cannot be restored from a tail transfer")
        }
        validate(input, false)
    }
    private fun validate(input: HandoffStorage, direct: Boolean) {
        if (input.layout !== packet || input.inputMode !in 1..3 || input.live != (input.inputMode == 1)) {
            releaseUnexpected(input)
            fault("Conflicting typed input layout or ownership")
        }
        // The caller, not the callee, owns the incoming transport choice. An
        // inlined fresh ingress must disappear; a residual edge may materialize.
        if (direct && CompilerDirectives.inCompiledCode() && !CompilerDirectives.inCompilationRoot() && input.inputMode == 2)
            CompilerDirectives.ensureVirtualizedHere(input)
    }
    fun release(input: HandoffStorage) {
        check(input.layout === packet)
        when (input.inputMode) {
            1 -> state().arguments.release(input, packet)
            2, 3 -> packet.clearReferences(input)
            else -> fault("Typed input carrier was already consumed")
        }
        input.inputMode = 0
    }
    fun releaseChecked(input: HandoffStorage) {
        if (input.inputMode == 0) return
        if (input.layout === packet) release(input) else releaseUnexpected(input)
    }
    fun releaseIfOwned(input: HandoffStorage, generation: Long) {
        if (input.generation == generation) releaseChecked(input)
    }
    private fun releaseUnexpected(input: HandoffStorage) = discardTypedInput(language, input)

    /** A scalar State#/unknown/address retains one reference field in the old ABI;
     * only fields recursively inside an exact tuple are erased or flattened. */
    companion object {
        private fun fieldRep(proof: CoreRepresentation): String = when {
            proof.isLong -> "IntRep"
            proof.isFloat -> "FloatRep"
            proof.isDouble -> "DoubleRep"
            else -> "BoxedRep (Just Lifted)"
        }
        fun create(language: Language, logical: ArgumentLayout?, hasEnvironment: Boolean): TypedInputLayout? =
            logical?.takeIf { it.requiresTyped }?.let { TypedInputLayout(language, it, hasEnvironment) }
    }
}

/** Failure cleanup may see a different layout and must not explode its dynamic fields. */
@CompilerDirectives.TruffleBoundary
internal fun discardTypedInput(language: Language, input: HandoffStorage) {
    when (input.inputMode) {
        1 -> language.handoffState.get().arguments.release(input)
        2, 3 -> input.layout.clearReferences(input)
    }
    input.inputMode = 0
}

/** Compile-time source descriptors only: no activation frame or guest payload is retained. */
internal abstract class InputSource(val layout: ArgumentLayout?) {
    @field:CompilationFinal(dimensions = 1)
    internal val physicalProofs = layout?.physicalProofs
    abstract fun long(frame: VirtualFrame, node: Node, values: Array<Any?>?, index: Int): Long
    abstract fun float(frame: VirtualFrame, node: Node, values: Array<Any?>?, index: Int): Float
    abstract fun double(frame: VirtualFrame, node: Node, values: Array<Any?>?, index: Int): Double
    abstract fun reference(frame: VirtualFrame, node: Node, values: Array<Any?>?, index: Int): Any?
    abstract fun setReference(frame: VirtualFrame, node: Node, values: Array<Any?>?, index: Int, value: Any?)
    @ExplodeLoop fun copy(frame: VirtualFrame, node: Node, values: Array<Any?>?, sourceOffset: Int,
        destination: HandoffStorage, targetOffset: Int, count: Int, shape: HandoffLayout = destination.layout) {
        for (i in 0 until count) {
            val source = sourceOffset + i
            val target = targetOffset + i
            if (shape.isLong(target)) shape.setLong(destination, target, long(frame, node, values, source))
            else if (shape.isFloat(target)) shape.setFloat(destination, target, float(frame, node, values, source))
            else if (shape.isDouble(target)) shape.setDouble(destination, target, double(frame, node, values, source))
            else shape.setObject(destination, target, reference(frame, node, values, source))
        }
    }
}

/** Used only for pre-existing scalar/empty call sites; tuple payloads never enter this array. */
internal class ScalarArrayInputSource(layout: ArgumentLayout?) : InputSource(layout) {
    init { require(layout?.requiresTyped != true) }
    override fun long(frame: VirtualFrame, node: Node, values: Array<Any?>?, index: Int) =
        values!![index] as? Long ?: fault("Expected primitive Long input")
    override fun float(frame: VirtualFrame, node: Node, values: Array<Any?>?, index: Int) =
        values!![index] as? Float ?: fault("Expected primitive Float input")
    override fun double(frame: VirtualFrame, node: Node, values: Array<Any?>?, index: Int) =
        values!![index] as? Double ?: fault("Expected primitive Double input")
    override fun reference(frame: VirtualFrame, node: Node, values: Array<Any?>?, index: Int) = values!![index]
    override fun setReference(frame: VirtualFrame, node: Node, values: Array<Any?>?, index: Int, value: Any?) { values!![index] = value }
}

// The legacy prefix has no tuple fields. Reuse its immutable descriptor so a
// compiled call never constructs or recursively analyzes source metadata.
private val scalarPrefixSource = ScalarArrayInputSource(null)

internal class AstInputSource(layout: ArgumentLayout,
    @field:CompilationFinal(dimensions = 1) val slots: IntArray) : InputSource(layout) {
    override fun long(frame: VirtualFrame, node: Node, values: Array<Any?>?, index: Int): Long =
        if (frame.isLong(slots[index])) frame.getLong(slots[index]) else FrameAccess.read(frame, slots[index]) as? Long ?: fault("Expected primitive Long input")
    override fun float(frame: VirtualFrame, node: Node, values: Array<Any?>?, index: Int): Float =
        if (frame.isFloat(slots[index])) frame.getFloat(slots[index]) else FrameAccess.read(frame, slots[index]) as? Float ?: fault("Expected primitive Float input")
    override fun double(frame: VirtualFrame, node: Node, values: Array<Any?>?, index: Int): Double =
        if (frame.isDouble(slots[index])) frame.getDouble(slots[index]) else FrameAccess.read(frame, slots[index]) as? Double ?: fault("Expected primitive Double input")
    override fun reference(frame: VirtualFrame, node: Node, values: Array<Any?>?, index: Int) = FrameAccess.read(frame, slots[index])
    override fun setReference(frame: VirtualFrame, node: Node, values: Array<Any?>?, index: Int, value: Any?) {
        writeInputReference(frame, slots[index], value)
    }
    @ExplodeLoop fun clear(frame: VirtualFrame) { for (slot in slots) frame.clear(slot) }
}

internal class BytecodeInputSource(layout: ArgumentLayout,
    @field:CompilationFinal(dimensions = 1) val slots: Array<LocalAccessor>) : InputSource(layout) {
    private fun bytecode(node: Node): BytecodeNode = (node.rootNode as BytecodeRoot).bytecodeNode
    // Exact tuple leaves retain primitive access. A legacy unknown scalar beside
    // a tuple can generalize its local to Object after another numeric target;
    // read that existing scalar carrier generically, then check the target kind.
    override fun long(frame: VirtualFrame, node: Node, values: Array<Any?>?, index: Int): Long =
        if (physicalProofs!![index].isLong) slots[index].getLong(bytecode(node), frame)
        else slots[index].getObject(bytecode(node), frame) as? Long ?: fault("Expected primitive Long input")
    override fun float(frame: VirtualFrame, node: Node, values: Array<Any?>?, index: Int): Float =
        if (physicalProofs!![index].isFloat) slots[index].getFloat(bytecode(node), frame)
        else slots[index].getObject(bytecode(node), frame) as? Float ?: fault("Expected primitive Float input")
    override fun double(frame: VirtualFrame, node: Node, values: Array<Any?>?, index: Int): Double =
        if (physicalProofs!![index].isDouble) slots[index].getDouble(bytecode(node), frame)
        else slots[index].getObject(bytecode(node), frame) as? Double ?: fault("Expected primitive Double input")
    override fun reference(frame: VirtualFrame, node: Node, values: Array<Any?>?, index: Int) = slots[index].getObject(bytecode(node), frame)
    override fun setReference(frame: VirtualFrame, node: Node, values: Array<Any?>?, index: Int, value: Any?) {
        slots[index].setObject(bytecode(node), frame, value)
    }
}

/** These slots are selected by an exact reference field in the call layout.
 * Keep this write independent of the generic scalar widening dispatch. */
internal fun writeInputReference(frame: VirtualFrame, slot: Int, value: Any?) {
    FrameAccess.writeObject(frame, slot, value)
}

/** Copies between separately owned typed storage; logical compatibility is checked before this operation. */
@ExplodeLoop
internal fun copyInputFields(source: HandoffStorage, destination: HandoffStorage, sourceOffset: Int, targetOffset: Int,
    count: Int, from: HandoffLayout = source.layout, into: HandoffLayout = destination.layout) {
    for (i in 0 until count) {
        val s = sourceOffset + i
        val d = targetOffset + i
        if (into.isLong(d)) into.setLong(destination, d, from.getLong(source, s))
        else if (into.isFloat(d)) into.setFloat(destination, d, from.getFloat(source, s))
        else if (into.isDouble(d)) into.setDouble(destination, d, from.getDouble(source, s))
        else into.setObject(destination, d, from.getObject(source, s))
    }
}

/** Prefix fields are immutable after publication and cannot be confused with a pool loan. */
internal fun typedPap(function: Closure, input: TypedInputLayout, source: InputSource,
    frame: VirtualFrame, node: Node, values: Array<Any?>?, offset: Int, count: Int,
    oldCount: Int, remainingArity: Int): Closure {
    check(count < remainingArity)
    val prefixWidth = input.logical.offset(oldCount)
    val sourceOffset = ArgumentLayout.offset(source.layout, offset)
    val sourceWidth = ArgumentLayout.offset(source.layout, offset + count) - sourceOffset
    val layout = input.prefix(oldCount + count)
    val storage = layout.create()
    function.typedSupplied?.let { copyInputFields(it, storage, 0, 0, prefixWidth, input.prefix(oldCount), layout) } ?: run {
        check(prefixWidth == function.supplied.size)
        scalarPrefixSource.copy(frame, node, function.supplied, 0, storage, 0, prefixWidth, layout)
    }
    source.copy(frame, node, values, sourceOffset, storage, prefixWidth, sourceWidth, layout)
    return Closure(function.environment, NO_PAP_ARGUMENTS, remainingArity - count, function.target, oldCount + count, storage)
}

/** The loan is consumed by entry before any guest continuation. Failed entry and
 * deoptimization still have one generation-checked owner in the caller. */
internal inline fun invokeTypedInput(target: com.oracle.truffle.api.RootCallTarget, input: HandoffStorage,
    action: (Array<Any?>) -> Any?): Any? {
    val layout = (target.rootNode as GuestRoot).typedInput ?: fault("Target has no typed input entry")
    val generation = input.generation
    try { return action(arrayOf(input)) }
    // Trampoline targets vary at runtime. Cleanup receives only storage and
    // metadata; it must not explode a dynamic layout or retain a caller frame.
    finally { releaseGenericInput(layout, input, generation) }
}

internal inline fun invokeTypedInput(layout: TypedInputLayout, input: HandoffStorage,
    action: (Array<Any?>) -> Any?): Any? {
    val generation = input.generation
    try { return action(arrayOf(input)) }
    finally { layout.releaseIfOwned(input, generation) }
}

/** All operand evaluation and strict scalar-prefix forcing happens before the
 * loan. Aggregate strictness never forces a lifted field inside the tuple. */
@ExplodeLoop
private fun prepareInput(frame: VirtualFrame, node: Node, function: Closure, input: TypedInputLayout,
    source: InputSource, values: Array<Any?>?, logicalOffset: Int, count: Int, force: Force,
    prefixCount: Int, strictPositions: IntArray): HandoffStorage {
    val prefixWidth = input.logical.offset(prefixCount)
    val strictPrefix = strictPositions.any { it < prefixCount }
    // Only strict scalar reference values appear here. No tuple field is boxed
    // or put in this temporary override array, and it never escapes the call.
    val overrides = if (strictPrefix) arrayOfNulls<Any>(prefixWidth) else null
    for (i in strictPositions) {
        val physical = input.logical.offset(i)
        if (i < prefixCount) {
            val supplied = function.typedSupplied
            val raw = if (supplied != null) input.prefix(prefixCount).getObject(supplied, physical) else function.supplied[physical]
            overrides!![physical] = force.execute(frame, raw)
        } else {
            val position = ArgumentLayout.offset(source.layout, logicalOffset + i - prefixCount)
            val actual = source.physicalProofs?.get(position)
            if (actual?.isLong == true || actual?.isFloat == true || actual?.isDouble == true) continue
            source.setReference(frame, node, values, position, force.execute(frame, source.reference(frame, node, values, position)))
        }
    }
    val loan = if (CompilerDirectives.inCompiledCode()) input.packet.create().also { it.inputMode = 2 }
        else input.state().arguments.acquire(input.packet).also { it.inputMode = 1 }
    try {
        input.packet.setLong(loan, 0, 0L)
        if (input.hasEnvironment) input.packet.setObject(loan, 1, function.environment)
        function.typedSupplied?.let { copyInputFields(it, loan, 0, input.header, prefixWidth, input.prefix(prefixCount), input.packet) } ?: run {
            check(function.supplied.size == prefixWidth)
            scalarPrefixSource.copy(frame, node, function.supplied, 0, loan, input.header, prefixWidth, input.packet)
        }
        val from = ArgumentLayout.offset(source.layout, logicalOffset)
        val width = ArgumentLayout.offset(source.layout, logicalOffset + count) - from
        source.copy(frame, node, values, from, loan, input.header + prefixWidth, width, input.packet)
        if (overrides != null) for (i in strictPositions) {
            if (i < prefixCount) {
                val physical = input.logical.offset(i)
                input.packet.setObject(loan, input.header + physical, overrides[physical])
            }
        }
        return loan
    } catch (failure: Throwable) { input.release(loan); throw failure }
}

private inline fun callTypedInput(frame: VirtualFrame, node: Node, function: Closure,
    input: TypedInputLayout, source: InputSource, values: Array<Any?>?, start: Int, count: Int,
    force: Force, tail: Boolean, metrics: Metrics, prefixCount: Int, strictPositions: IntArray,
    action: (Array<Any?>) -> Any?): Any? {
    val loan = prepareInput(frame, node, function, input, source, values, start, count, force, prefixCount, strictPositions)
    val generation = loan.generation
    var transferred = false
    try {
        if (tail) try { checkTypedTail(frame, node, function.target, loan, input.packet, metrics) }
        catch (transfer: TailCall) { transferred = transfer.input === loan; throw transfer }
        if (metrics.enabled) input.state().calls++
        return invokeTypedInput(input, loan, action)
    } finally {
        if (!transferred) input.releaseIfOwned(loan, generation)
    }
}

/** One call-site cache serves both scalar and aggregate results. Each arm consumes
 * aggregate completion before returning, so virtual results never merge in a PIC. */
internal class InputDispatch(private val source: InputSource, private val count: Int, private val tail: Boolean,
    private val metrics: Metrics, private val destination: TupleDestination? = null, private val start: Int = 0) : Node() {
    @Children @Volatile private var direct = emptyArray<InputCallArm>()
    @Child private var generic = GenericInputCall(source, count, tail, metrics, destination, start)
    @CompilationFinal private var megamorphic = false
    @ExplodeLoop fun execute(frame: VirtualFrame, function: Closure, values: Array<Any?>? = null): Any? {
        for (arm in direct) if (arm.matches(function)) return arm.execute(frame, function, values)
        if (!megamorphic) {
            CompilerDirectives.transferToInterpreterAndInvalidate()
            val arm: InputCallArm? = atomic(Callable {
                direct.firstOrNull { it.matches(function) } ?: if (megamorphic) null else if (direct.size < 3) {
                    insert(InputCallArm(source, count, tail, metrics, destination, start, function))
                        .also { direct = direct + it }
                } else {
                    megamorphic = true
                    null
                }
            })
            if (arm != null) {
                return arm.execute(frame, function, values)
            }
        }
        return generic.execute(frame, function, values)
    }
}

private class InputCallArm(private val source: InputSource, private val count: Int, private val tail: Boolean,
    private val metrics: Metrics, private val destination: TupleDestination?, private val start: Int, function: Closure) : Node() {
    private val target = function.target
    private val arity = function.arity
    private val prefixCount = function.suppliedCount
    private val hasEnvironment = function.environment != null
    private val root = target.rootNode as GuestRoot
    private val input = root.typedInput
    @field:CompilationFinal(dimensions = 1)
    private val strictPositions = input?.let { strictInputPositions(root, it) } ?: IntArray(0)
    @Child private var direct = com.oracle.truffle.api.nodes.DirectCallNode.create(target)
    @Child private var legacy = DirectCallerNode(target, metrics, prefixSize = prefixCount)
    @Child private var force = Force(metrics)
    @Child private var loop = TailCallLoop(metrics)
    @Child private var tupleBounce: TupleBounce? = destination?.let { TupleBounce(it, metrics) }
    @Child private var remainder: InputDispatch? = if (arity < count)
        InputDispatch(source, count - arity, tail, metrics, destination, start + arity) else null
    init {
        ArgumentLayout.validate(root.inputLayout, prefixCount, source.layout, start, minOf(arity, count))
        if (arity <= count) checkInputResult(root, destination, arity == count)
        if (metrics.enabled) metrics.incrementDirectCacheMisses()
    }
    fun matches(function: Closure): Boolean = function.target === target && function.arity == arity &&
        function.suppliedCount == prefixCount && (function.environment != null) == hasEnvironment
    fun execute(frame: VirtualFrame, function: Closure, values: Array<Any?>?): Any? {
        if (arity > count) {
            if (destination != null) fault("Aggregate result application is under-saturated")
            if (metrics.enabled) metrics.incrementPapAllocations()
            return if (input != null) typedPap(function, input, source, frame, this, values, start, count, prefixCount, arity)
            else legacyPap(frame, this, function, source, values, start, count)
        }
        val isTail = tail && arity == count
        val result = if (input == null) {
            legacy.call(frame, scalarPacket(frame, this, function, source, values, start, arity), isTail)
        } else {
            try { callTypedInput(frame, this, function, input, source, values, start, arity, force, isTail, metrics, prefixCount, strictPositions) { packet ->
                Calls.direct(direct, packet)
            } } catch (transfer: TailCall) {
                if (isTail) throw transfer
                if (arity == count && tupleBounce != null) { tupleBounce!!.execute(frame, transfer); return null }
                loop.execute(transfer)
            }
        }
        if (arity < count) return remainder!!.execute(frame, requireClosure(force.execute(frame, result)), values)
        if (destination != null) { destination.consume(frame, this, result); return null }
        return result
    }
}

internal class GenericInputCall(private val source: InputSource, private val count: Int, private val tail: Boolean,
    private val metrics: Metrics, private val destination: TupleDestination?, private val start: Int) : Node() {
    @Child private var indirect = com.oracle.truffle.api.nodes.IndirectCallNode.create()
    @Child private var legacy = IndirectCallerNode(metrics)
    @Child private var force = Force(metrics)
    @Child private var loop = TailCallLoop(metrics)
    @Child private var tupleBounce: TupleBounce? = destination?.let { TupleBounce(it, metrics) }
    fun execute(frame: VirtualFrame, initial: Closure, values: Array<Any?>?, initialOffset: Int = start): Any? {
        var function = initial
        var offset = initialOffset
        while (true) {
            val remaining = count + start - offset
            val target = function.target
            val root = target.rootNode as GuestRoot
            val input = root.typedInput
            val used = minOf(function.arity, remaining)
            validateGenericInput(root, function.suppliedCount, source.layout, offset, used, destination, function.arity == remaining, function.arity > remaining)
            if (function.arity > remaining) {
                if (destination != null) fault("Aggregate result application is under-saturated")
                if (metrics.enabled) metrics.incrementPapAllocations()
                return if (input != null) genericTypedPap(function, input, source, frame, this, values, count + start, offset, remaining)
                    else legacyGenericPap(frame, this, function, source, values, count + start, offset, remaining)
            }
            val exact = function.arity == remaining
            val isTail = tail && exact
            val result = if (input == null) {
                legacy.call(frame, target, scalarPacket(frame, this, function, source, values, offset, function.arity, count + start), isTail)
            } else {
                if (metrics.enabled) metrics.incrementIndirectCalls()
                try {
                    val storage = prepareGenericInput(frame, this, function, input, source, values, count + start, offset, function.arity, force)
                    val generation = storage.generation
                    var transferred = false
                    try {
                        if (isTail) try { checkTypedTail(frame, this, target, storage, input.packet, metrics) }
                        catch (transfer: TailCall) { transferred = transfer.input === storage; throw transfer }
                        if (metrics.enabled) input.state().calls++
                        Calls.indirect(indirect, target, arrayOf(storage))
                    } finally { if (!transferred) releaseGenericInput(input, storage, generation) }
                } catch (transfer: TailCall) {
                    if (isTail) throw transfer
                    if (exact && tupleBounce != null) { tupleBounce!!.execute(frame, transfer); return null }
                    loop.execute(transfer)
                }
            }
            if (exact) {
                if (destination != null) { destination.consume(frame, this, result); return null }
                return result
            }
            offset += function.arity
            function = requireClosure(force.execute(frame, result))
        }
    }
}

@CompilerDirectives.TruffleBoundary
internal fun strictInputPositions(root: GuestRoot, input: TypedInputLayout): IntArray =
    root.entryStrict.indices.filter { root.entryStrict[it] && !input.logical.isTuple(it) &&
        input.packet.isObject(input.header + input.logical.offset(it)) }.toIntArray()

internal fun checkInputResult(root: GuestRoot, destination: TupleDestination?, exact: Boolean) {
    if (exact && destination == null && root.tupleResult != null)
        fault("Aggregate call target requires a typed result destination")
    if (exact && destination != null && root.tupleResult?.matches(destination.shape) != true)
        fault("Aggregate call target result shape mismatch")
    if (!exact && root.tupleResult != null) fault("Cannot overapply an unboxed aggregate")
}
private fun checkTypedTail(frame: VirtualFrame, node: Node, target: com.oracle.truffle.api.RootCallTarget,
    loan: HandoffStorage, packet: HandoffLayout, metrics: Metrics) {
    val source = node.rootNode as? GuestRoot
    val targetRoot = target.rootNode as GuestRoot
    val mask = source?.bloom(frame) ?: 0L
    if (source == null || mask and targetRoot.mask == targetRoot.mask) {
        if (metrics.enabled) { metrics.incrementTailBounces(); targetRoot.typedInput!!.state().tailTransfers++ }
        // A tail transfer deliberately materializes this carrier in a control
        // exception. It remains unpooled, but is no longer an inline-only input.
        if (loan.inputMode == 2) loan.inputMode = 3
        throw TailCall(target, NO_PAP_ARGUMENTS, loan)
    }
    packet.setLong(loan, 0, mask)
}

/** A crossing back into the scalar ABI can contain only scalar logical arguments. */
@ExplodeLoop
private fun scalarValues(frame: VirtualFrame, node: Node, source: InputSource, values: Array<Any?>?, start: Int, count: Int): Array<Any?> {
    val result = arrayOfNulls<Any>(ArgumentLayout.offset(source.layout, start + count) - ArgumentLayout.offset(source.layout, start))
    var to = 0
    for (i in start until start + count) {
        val proof = source.layout?.proof(i)
        if (proof?.isEmptyTuple == true) continue
        if (proof?.isTuple == true) fault("Tuple input cannot enter a scalar packet")
        val from = ArgumentLayout.offset(source.layout, i)
        result[to++] = when {
            proof?.isLong == true -> source.long(frame, node, values, from)
            proof?.isFloat == true -> source.float(frame, node, values, from)
            proof?.isDouble == true -> source.double(frame, node, values, from)
            else -> source.reference(frame, node, values, from)
        }
    }
    return result
}
private fun scalarPacket(frame: VirtualFrame, node: Node, function: Closure, source: InputSource,
    values: Array<Any?>?, start: Int, count: Int, genericMaximum: Int = -1): Array<Any?> {
    check(function.typedSupplied == null)
    val args = if (genericMaximum >= 0) genericScalarValues(frame, node, source, values, genericMaximum, start, count)
        else scalarValues(frame, node, source, values, start, count)
    val skip = if (function.environment == null) 1 else 2
    return arrayOfNulls<Any>(skip + function.supplied.size + args.size).also { packet ->
        if (function.environment != null) packet[1] = function.environment
        System.arraycopy(function.supplied, 0, packet, skip, function.supplied.size)
        System.arraycopy(args, 0, packet, skip + function.supplied.size, args.size)
    }
}
private fun legacyPap(frame: VirtualFrame, node: Node, function: Closure, source: InputSource,
    values: Array<Any?>?, start: Int, count: Int): Closure {
    val args = scalarValues(frame, node, source, values, start, count)
    return function.papCompact(args, 0, args.size, count)
}

private fun legacyGenericPap(frame: VirtualFrame, node: Node, function: Closure, source: InputSource,
    values: Array<Any?>?, maximum: Int, offset: Int, count: Int): Closure {
    val args = genericScalarValues(frame, node, source, values, maximum, offset, count)
    return function.papCompact(args, 0, args.size, count)
}

/** Evaluate logical operands in order into caller-owned typed locals. Even a
 * zero-storage component runs through its writer before the next argument. */
internal class AstInputOperands(arguments: Array<Expr>, frameLayout: FrameLayout) : Node() {
    @Children private var arguments = arguments
    val layout = ArgumentLayout.fromProofs(arguments.map { it.representation })!!
    val source = AstInputSource(layout, IntArray(layout.physicalArity) { frameLayout.bind("<typed input $it>") })
    @ExplodeLoop fun evaluate(frame: VirtualFrame) {
        for (i in arguments.indices) {
            val proof = layout.proof(i)
            val offset = layout.offset(i)
            if (proof.isTuple) arguments[i].executeTuple(frame, source.slots, offset)
            else if (proof.isLong) FrameAccess.writeLong(frame, source.slots[offset], arguments[i].executeRequiredLong(frame))
            else if (proof.isFloat) FrameAccess.writeFloat(frame, source.slots[offset], arguments[i].executeRequiredFloat(frame))
            else if (proof.isDouble) FrameAccess.writeDouble(frame, source.slots[offset], arguments[i].executeRequiredDouble(frame))
            else FrameAccess.write(frame, source.slots[offset], arguments[i].execute(frame))
        }
    }
}

internal class AstTypedApplication(function: Expr, arguments: Array<Expr>, frameLayout: FrameLayout,
    private val tail: Boolean, private val metrics: Metrics, private val shape: TupleShape? = null) : Expr() {
    @Child private var function = Evaluate(function, metrics)
    @Child private var operands = AstInputOperands(arguments, frameLayout)
    @Child @Volatile private var dispatch: InputDispatch? = if (shape == null)
        InputDispatch(operands.source, arguments.size, tail, metrics) else null
    @field:CompilationFinal(dimensions = 1) private var destinationSlots: IntArray? = null
    @CompilationFinal private var destinationOffset = -1
    init { representation = shape?.proof?.copy(evaluated = true) ?: CoreRepresentation(CoreKind.UNKNOWN, evaluated = true) }
    override fun execute(frame: VirtualFrame): Any? {
        if (shape != null) fault("Aggregate value requires a typed destination")
        val closure = function.executeRequiredClosure(frame)
        try { operands.evaluate(frame); return dispatch!!.execute(frame, closure) }
        finally { operands.source.clear(frame) }
    }
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val tuple = shape ?: fault("Scalar application has no aggregate destination")
        val closure = function.executeRequiredClosure(frame)
        try {
            operands.evaluate(frame)
            val child = dispatch ?: run {
                CompilerDirectives.transferToInterpreterAndInvalidate()
                atomic(Callable {
                    dispatch ?: insert(InputDispatch(operands.source, operands.layout.logicalArity, tail, metrics,
                        AstTupleDestination(tuple, slots, offset))).also {
                        destinationSlots = slots
                        destinationOffset = offset
                        dispatch = it
                    }
                })
            }
            check(destinationSlots === slots && destinationOffset == offset)
            child.execute(frame, closure)
            return null
        } finally { operands.source.clear(frame) }
    }
}
