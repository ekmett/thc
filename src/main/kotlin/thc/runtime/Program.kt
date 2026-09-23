@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.dsl.TypeSystemReference
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.*
import com.oracle.truffle.api.profiles.BranchProfile
import com.oracle.truffle.api.profiles.CountingConditionProfile
import com.oracle.truffle.api.source.SourceSection

/* Indexed frames, selective captures, rooted application and self-tail frame
 * restoration follow Cadenza. See NOTICE.md and LICENSE.txt. Haskell thunks
 * supply the additional lazy update/blackhole protocol. One guest thread;
 * arbitrary non-tail recursion still uses the host stack. */
open class RuntimeFault(message: String) : RuntimeException(message)
/** A known implementation gap, distinct from malformed Core or runtime errors. */
internal class UnsupportedCore(message: String) : RuntimeFault(message)
internal fun fault(message: String): Nothing {
    CompilerDirectives.transferToInterpreterAndInvalidate()
    throw RuntimeFault(message)
}
/** Narrow unsigned carriers are zero-extended Longs, unlike signed Int8/16/32 carriers. */
internal fun narrowWordPrimitiveMask(name: String): Long = when (name) {
    "wordToWord8#", "word8ToWord#", "plusWord8#", "subWord8#", "timesWord8#", "ltWord8#", "leWord8#",
    "quotWord8#", "remWord8#", "eqWord8#", "neWord8#", "gtWord8#", "geWord8#", "andWord8#", "orWord8#", "xorWord8#", "notWord8#", "uncheckedShiftLWord8#", "uncheckedShiftRLWord8#" -> 0xffL
    "wordToWord16#", "word16ToWord#", "plusWord16#", "subWord16#", "timesWord16#", "ltWord16#", "leWord16#",
    "quotWord16#", "remWord16#", "eqWord16#", "neWord16#", "gtWord16#", "geWord16#", "andWord16#", "orWord16#", "xorWord16#", "notWord16#", "uncheckedShiftLWord16#", "uncheckedShiftRLWord16#" -> 0xffffL
    "wordToWord32#", "word32ToWord#", "plusWord32#", "subWord32#", "timesWord32#", "ltWord32#", "leWord32#",
    "quotWord32#", "remWord32#", "eqWord32#", "neWord32#", "gtWord32#", "geWord32#", "andWord32#", "orWord32#", "xorWord32#", "notWord32#", "uncheckedShiftLWord32#", "uncheckedShiftRLWord32#" -> 0xffff_ffffL
    else -> 0L
}
/** Fixed-width signed arithmetic retains canonical sign-extended Long carriers. */
internal fun narrowIntPrimitiveShift(name: String): Int = when (name) {
    "negateInt8#", "plusInt8#", "subInt8#", "timesInt8#", "quotInt8#", "remInt8#", "eqInt8#", "neInt8#", "ltInt8#", "leInt8#", "gtInt8#", "geInt8#" -> 56
    "negateInt16#", "plusInt16#", "subInt16#", "timesInt16#", "quotInt16#", "remInt16#", "eqInt16#", "neInt16#", "ltInt16#", "leInt16#", "gtInt16#", "geInt16#" -> 48
    "negateInt32#", "plusInt32#", "subInt32#", "timesInt32#", "quotInt32#", "remInt32#", "eqInt32#", "neInt32#", "ltInt32#", "leInt32#", "gtInt32#", "geInt32#" -> 32
    else -> 0
}
private fun signedNarrow(value: Long, shift: Int): Long = (value shl shift) shr shift
internal fun narrowWordLiteral(kind: String, value: String): Long {
    val maximum = when (kind) {
        "word8" -> 0xffL; "word16" -> 0xffffL; "word32" -> 0xffff_ffffL
        else -> throw RuntimeFault("Invalid narrow word literal kind: $kind")
    }
    val number = value.toLongOrNull()
    if (number == null || number !in 0L..maximum || number.toString() != value)
        throw RuntimeFault("Invalid $kind literal: $value")
    return number
}
/** Int64 literals are canonical decimal signed 64-bit carriers, including both endpoints. */
internal fun int64Literal(value: String): Long {
    val number = value.toLongOrNull()
    if (number == null || number.toString() != value) throw RuntimeFault("Invalid int64 literal: $value")
    return number
}
/** Cadenza's recursive indirection: captured by identity, initialized once. */
internal class RecCell {
    var initialized = false
    var value: Any? = null
}
/** Program linkage is fixed before guest execution; CAF contents remain lazy. */
internal class GlobalBinding(val name: String) {
    @CompilationFinal private var initialized = false
    @CompilationFinal private var value: Any? = null
    fun initialize(value: Any?) { check(!initialized); this.value = value; initialized = true }
    fun read(): Any? {
        if (!initialized) fault("Uninitialized global binding")
        return value
    }
}
internal class Thunk(target: RootCallTarget, var environment: CapturedFrame?) {
    // Updated thunks retain only their answer (or memoized guest failure).
    var target: RootCallTarget? = target
    var state = 0
    var value: Any? = null
}
internal class Metrics(val enabled: Boolean) {
    val thunkEvaluationsByLabel = linkedMapOf<String, Long>()
    @CompilerDirectives.TruffleBoundary fun recordThunk(label: String) {
        thunkEvaluationsByLabel[label] = (thunkEvaluationsByLabel[label] ?: 0L) + 1L
    }
    var compiledEntries = 0L
    var leadingCaseReturns = 0L
    var thunkEvaluations = 0L
    var thunkHits = 0L
    var blackholes = 0L
    var directCacheMisses = 0L
    var indirectCalls = 0L
    var tailBounces = 0L
    var selfTailReentries = 0L
    var localJoinTransfers = 0L
    var trampolineIterations = 0L
    var papAllocations = 0L
    var unsupportedTraps = 0L
}
@TypeSystemReference(RuntimeTypes::class)
internal abstract class Expr : Node() {
    // Assigned during lowering, before adoption. Evaluatedness describes our stored value.
    @CompilationFinal var representation: CoreRepresentation = CoreRepresentation.UNKNOWN
    @CompilationFinal var coreSourceLocation: CoreSourceLocation? = null
    fun located(location: CoreSourceLocation?): Expr { coreSourceLocation = location; return this }
    override fun getSourceSection(): SourceSection? = coreSourceLocation?.section ?: parent?.encapsulatingSourceSection
    fun proven(proof: CoreRepresentation): Expr { representation = proof; return this }
    open fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int = 0): Any? { fault("Expression does not produce a tuple") }
    abstract fun execute(frame: VirtualFrame): Any?
    @Throws(UnexpectedResultException::class)
    open fun executeLong(frame: VirtualFrame): Long {
        return RuntimeTypesGen.expectLong(execute(frame))
    }

    @Throws(UnexpectedResultException::class)
    open fun executeFloat(frame: VirtualFrame): Float = RuntimeTypesGen.expectFloat(execute(frame))
    @Throws(UnexpectedResultException::class)
    open fun executeDouble(frame: VirtualFrame): Double = RuntimeTypesGen.expectDouble(execute(frame))
    fun executeRequiredFloat(frame: VirtualFrame): Float = try { executeFloat(frame) }
    catch (_: UnexpectedResultException) { fault("Expected primitive Float") }
    fun executeRequiredDouble(frame: VirtualFrame): Double = try { executeDouble(frame) }
    catch (_: UnexpectedResultException) { fault("Expected primitive Double") }

    @Throws(UnexpectedResultException::class)
    open fun executeClosure(frame: VirtualFrame): Closure = RuntimeTypesGen.expectClosure(execute(frame))

    @Throws(UnexpectedResultException::class)
    open fun executeDataValue(frame: VirtualFrame): DataValue = RuntimeTypesGen.expectDataValue(execute(frame))

    @Throws(UnexpectedResultException::class)
    open fun executeAddress(frame: VirtualFrame): LiteralAddress = RuntimeTypesGen.expectLiteralAddress(execute(frame))

    /** Primitive consumers reject an unexpected value; forwarding nodes preserve it. */
    fun executeRequiredLong(frame: VirtualFrame): Long = try { executeLong(frame) }
    catch (_: UnexpectedResultException) { fault("Expected primitive Long") }

    fun executeRequiredClosure(frame: VirtualFrame): Closure = try { executeClosure(frame) }
    catch (_: UnexpectedResultException) { fault("Application of a non-function") }

    fun executeRequiredDataValue(frame: VirtualFrame): DataValue = try { executeDataValue(frame) }
    catch (_: UnexpectedResultException) { fault("Expected constructor value") }

    fun executeRequiredAddress(frame: VirtualFrame): LiteralAddress = try { executeAddress(frame) }
    catch (_: UnexpectedResultException) { fault("Expected a managed literal Addr#") }
}
private class Literal(private val value: Any?) : Expr() {
    init { representation = CoreRepresentation(when (value) {
        is Long -> CoreKind.LONG; is Float -> CoreKind.FLOAT; is Double -> CoreKind.DOUBLE
        is LiteralAddress -> CoreKind.ADDRESS; Unit -> CoreKind.VOID; else -> CoreKind.OBJECT
    }, evaluated = true) }
    override fun execute(frame: VirtualFrame) = value
    override fun executeLong(frame: VirtualFrame) = RuntimeTypesGen.expectLong(value)
    override fun executeFloat(frame: VirtualFrame) = RuntimeTypesGen.expectFloat(value)
    override fun executeDouble(frame: VirtualFrame) = RuntimeTypesGen.expectDouble(value)
}
internal class LocalRead(private val slot: Int, private val cell: Boolean = true) : Expr() {
    override fun executeFloat(frame: VirtualFrame): Float =
        if ((!cell && representation.isFloat) || frame.isFloat(slot)) frame.getFloat(slot) else super.executeFloat(frame)
    override fun executeDouble(frame: VirtualFrame): Double =
        if ((!cell && representation.isDouble) || frame.isDouble(slot)) frame.getDouble(slot) else super.executeDouble(frame)
    override fun executeLong(frame: VirtualFrame): Long =
        if ((!cell && representation.isLong) || frame.isLong(slot)) frame.getLong(slot) else super.executeLong(frame)

    override fun execute(frame: VirtualFrame): Any? {
        val value = if (!cell && representation.isEvaluatedReference) frame.getObject(slot) else FrameAccess.read(frame, slot)
        if (!cell || value !is RecCell) return value ?: fault("Uninitialized local binding")
        if (!value.initialized) fault("Recursive binding read before initialization")
        return value.value
    }

    override fun executeDataValue(frame: VirtualFrame): DataValue =
        if (!cell && representation.evaluated && representation.kind == CoreKind.DATA)
            RuntimeTypesGen.expectDataValue(frame.getObject(slot)) else super.executeDataValue(frame)

    override fun executeClosure(frame: VirtualFrame): Closure =
        if (!cell && representation.evaluated && representation.kind == CoreKind.CLOSURE)
            RuntimeTypesGen.expectClosure(frame.getObject(slot)) else super.executeClosure(frame)

    override fun executeAddress(frame: VirtualFrame): LiteralAddress =
        if (!cell && representation.evaluated && representation.kind == CoreKind.ADDRESS)
            RuntimeTypesGen.expectLiteralAddress(frame.getObject(slot)) else super.executeAddress(frame)

    /** Recursive captures retain their cell identity until the whole group is published. */
    fun writeForced(frame: VirtualFrame, original: Thunk, result: Any?) {
        val binding = FrameAccess.read(frame, slot)
        if (binding === original) FrameAccess.write(frame, slot, result)
        else if (cell && binding is RecCell) updateForcedCell(binding, original, result)
    }
}
/** Replace only the successfully forced link; aliases may already have updated this cell. */
internal fun updateForcedCell(cell: RecCell, original: Thunk, result: Any?) {
    if (cell.initialized && cell.value === original) cell.value = result
}

private class GlobalRead(private val binding: GlobalBinding) : Expr() {
    override fun execute(frame: VirtualFrame) = binding.read()
}
private class MakeClosure(private val target: RootCallTarget, private val arity: Int,
                          private val captureLayout: CaptureLayout?,
                          @field:CompilationFinal(dimensions = 1) private val captures: IntArray) : Expr() {
    init { representation = CoreRepresentation(CoreKind.CLOSURE, evaluated = true) }
    // Cadenza's closed-lambda optimization: immutable code needs no allocation.
    private val constantClosure = if (captureLayout == null) Closure(environment = null, arity = arity, target = target) else null
    override fun execute(frame: VirtualFrame): Closure = constantClosure ?: Closure(
        environment = captureLayout!!.capture(frame, captures), arity = arity, target = target)
    override fun executeClosure(frame: VirtualFrame): Closure = execute(frame)
}
private class Delay(private val target: RootCallTarget, private val captureLayout: CaptureLayout?,
                    @field:CompilationFinal(dimensions = 1) private val captures: IntArray) : Expr() {
    override fun execute(frame: VirtualFrame): Thunk = Thunk(target, captureLayout?.capture(frame, captures))
}
internal class Force(private val metrics: Metrics) : Node() {
    @Child private var calls = ThunkTargetCache(metrics)
    @Child private var trampoline = TailCallLoop(metrics)
    private val tailCallProfile = BranchProfile.create()
    @CompilationFinal private var seenThunk = false
    fun execute(frame: VirtualFrame, original: Any?): Any? {
        if (!seenThunk) {
            if (original !is Thunk) return original
            CompilerDirectives.transferToInterpreterAndInvalidate()
            seenThunk = true
        }
        if (original !is Thunk) return original
        // Every successful update below verifies WHNF before publishing state 2.
        // Re-entering the result through a generic forcing loop loses that fact
        // and merges the suspension with its answer in the compiled graph.
        return when (original.state) {
            2 -> { if (metrics.enabled) metrics.thunkHits++; original.value }
            3 -> {
                // Failed thunks rethrow on the cold interpreter path. A Kotlin
                // non-null cast here otherwise pulls NPE stack-trace machinery
                // into every compiled forcing site, including successful ones.
                CompilerDirectives.transferToInterpreterAndInvalidate()
                throw (original.value as? Throwable ?: fault("Invalid failed thunk"))
            }
            1 -> { if (metrics.enabled) metrics.blackholes++; fault("Blackhole: cyclic thunk entered while evaluating") }
            else -> {
                val thunk = original
                val target = thunk.target ?: fault("Unevaluated thunk has no body")
                thunk.state = 1
                try {
                    if (metrics.enabled) { metrics.thunkEvaluations++; metrics.recordThunk(target.rootNode.name) }
                    val environment = thunk.environment
                    val result = try { calls.call(target, environment) }
                    catch (tail: TailCall) { tailCallProfile.enter(); trampoline.execute(tail) }
                    if (result is Thunk) fault("Thunk target violated WHNF convention")
                    thunk.value = result
                    thunk.target = null
                    thunk.environment = null
                    thunk.state = 2
                    result
                } catch (e: GuestException) {
                    thunk.value = e; thunk.target = null; thunk.environment = null; thunk.state = 3; throw e
                } catch (e: RuntimeFault) {
                    thunk.value = e; thunk.target = null; thunk.environment = null; thunk.state = 3; throw e
                } catch (e: Throwable) {
                    thunk.value = null; thunk.state = 0; throw e
                }
            }
        }
    }
}
/** Typed execution widens per result kind; each fallback consumes the already evaluated value. */
internal class Evaluate(@field:Child private var value: Expr, metrics: Metrics) : Expr() {
    init { representation = value.representation.copy(evaluated = true); coreSourceLocation = value.coreSourceLocation }
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int) = value.executeTuple(frame, slots, offset)
    @Child private var force = Force(metrics)
    private fun forceResult(frame: VirtualFrame, original: Any?): Any? {
        val result = force.execute(frame, original)
        // WHNF reads need no write. A failed force never reaches this point.
        if (original is Thunk && value is LocalRead) (value as LocalRead).writeForced(frame, original, result)
        return result
    }
    override fun execute(frame: VirtualFrame): Any? = when {
        value.representation.isLong -> value.executeRequiredLong(frame)
        value.representation.isFloat -> value.executeRequiredFloat(frame)
        value.representation.isDouble -> value.executeRequiredDouble(frame)
        value.representation.evaluated -> value.execute(frame)
        else -> forceResult(frame, value.execute(frame))
    }
    @CompilationFinal private var genericLong = false
    override fun executeFloat(frame: VirtualFrame): Float =
        if (value.representation.evaluated || value.representation.isFloat) value.executeFloat(frame)
        else RuntimeTypesGen.expectFloat(forceResult(frame, value.execute(frame)))
    override fun executeDouble(frame: VirtualFrame): Double =
        if (value.representation.evaluated || value.representation.isDouble) value.executeDouble(frame)
        else RuntimeTypesGen.expectDouble(forceResult(frame, value.execute(frame)))
    override fun executeLong(frame: VirtualFrame): Long {
        if (value.representation.evaluated || value.representation.isLong) return value.executeLong(frame)
        if (genericLong) return RuntimeTypesGen.expectLong(forceResult(frame, value.execute(frame)))
        return try { value.executeLong(frame) }
        catch (unexpected: UnexpectedResultException) {
            // The exception already invalidated compiled code. Widen once, and
            // force its saved result without evaluating the child a second time.
            genericLong = true
            RuntimeTypesGen.expectLong(forceResult(frame, unexpected.result))
        }
    }
    @CompilationFinal private var genericClosure = false
    override fun executeClosure(frame: VirtualFrame): Closure {
        if (value.representation.evaluated || value.representation.isLong) return value.executeClosure(frame)
        if (value.representation.kind == CoreKind.CLOSURE || genericClosure) return RuntimeTypesGen.expectClosure(forceResult(frame, value.execute(frame)))
        return try { value.executeClosure(frame) }
        catch (unexpected: UnexpectedResultException) {
            // The exception already invalidated compiled code. Widen once, and
            // force its saved result without evaluating the child a second time.
            genericClosure = true
            RuntimeTypesGen.expectClosure(forceResult(frame, unexpected.result))
        }
    }
    @CompilationFinal private var genericDataValue = false
    override fun executeDataValue(frame: VirtualFrame): DataValue {
        if (value.representation.evaluated || value.representation.isLong) return value.executeDataValue(frame)
        if (value.representation.kind == CoreKind.DATA || genericDataValue) return RuntimeTypesGen.expectDataValue(forceResult(frame, value.execute(frame)))
        return try { value.executeDataValue(frame) }
        catch (unexpected: UnexpectedResultException) {
            // The exception already invalidated compiled code. Widen once, and
            // force its saved result without evaluating the child a second time.
            genericDataValue = true
            RuntimeTypesGen.expectDataValue(forceResult(frame, unexpected.result))
        }
    }
    @CompilationFinal private var genericAddress = false
    override fun executeAddress(frame: VirtualFrame): LiteralAddress {
        if (value.representation.evaluated || value.representation.isLong) return value.executeAddress(frame)
        if (value.representation.kind == CoreKind.ADDRESS || genericAddress) return RuntimeTypesGen.expectLiteralAddress(forceResult(frame, value.execute(frame)))
        return try { value.executeAddress(frame) }
        catch (unexpected: UnexpectedResultException) {
            // The exception already invalidated compiled code. Widen once, and
            // force its saved result without evaluating the child a second time.
            genericAddress = true
            RuntimeTypesGen.expectLiteralAddress(forceResult(frame, unexpected.result))
        }
    }
}
private class Application(function: Expr,
                          @field:Children private var arguments: Array<Expr>, tail: Boolean, metrics: Metrics) : Expr() {
    init { representation = CoreRepresentation(CoreKind.UNKNOWN, evaluated = true) }
    @Child private var function = Evaluate(function, metrics)
    @Child private var dispatch = Dispatch.create(arguments.size, tail, metrics,
        arguments.map { it.representation.evaluated }.toBooleanArray())
    @ExplodeLoop override fun execute(frame: VirtualFrame): Any? {
        val fn = function.executeRequiredClosure(frame)
        val values = arrayOfNulls<Any>(arguments.size)
        for (i in arguments.indices) values[i] = arguments[i].execute(frame)
        return dispatch.execute(frame, fn, values)
    }
}
/** Each cloned root owns its widening state; recursive RHSs stay raw until publication. */
internal class LocalBinding(private val slot: Int, @field:Child private var value: Expr,
                           preferLong: Boolean) : Node() {
    private val exactLong = value.representation.isLong
    private val referenceKind = if (value.representation.evaluated) value.representation.kind else CoreKind.UNKNOWN
    @CompilationFinal private var generic = !preferLong ||
        (value.representation.present && !exactLong)
    fun evaluate(frame: VirtualFrame): Any? {
        if (referenceKind == CoreKind.DATA) return value.executeRequiredDataValue(frame)
        if (referenceKind == CoreKind.CLOSURE) return value.executeRequiredClosure(frame)
        if (referenceKind == CoreKind.ADDRESS) return value.executeRequiredAddress(frame)
        return value.execute(frame)
    }
    fun write(frame: VirtualFrame) {
        if (exactLong) { FrameAccess.writeLong(frame, slot, value.executeRequiredLong(frame)); return }
        if (value.representation.isFloat) { FrameAccess.writeFloat(frame, slot, value.executeRequiredFloat(frame)); return }
        if (value.representation.isDouble) { FrameAccess.writeDouble(frame, slot, value.executeRequiredDouble(frame)); return }
        if (referenceKind == CoreKind.DATA) { FrameAccess.write(frame, slot, value.executeRequiredDataValue(frame)); return }
        if (referenceKind == CoreKind.CLOSURE) { FrameAccess.write(frame, slot, value.executeRequiredClosure(frame)); return }
        if (referenceKind == CoreKind.ADDRESS) { FrameAccess.write(frame, slot, value.executeRequiredAddress(frame)); return }
        if (generic) { FrameAccess.write(frame, slot, value.execute(frame)); return }
        try { FrameAccess.writeLong(frame, slot, value.executeLong(frame)) }
        catch (unexpected: UnexpectedResultException) {
            generic = true
            FrameAccess.write(frame, slot, unexpected.result)
        }
    }
}
private class Let(@field:CompilationFinal(dimensions = 1) private val slots: IntArray,
                  rhs: Array<Expr>, primitiveEligible: BooleanArray,
                  @field:Child private var body: Expr, private val recursive: Boolean) : Expr() {
    init { representation = body.representation }
    @Children private var bindings = Array(rhs.size) { index ->
        LocalBinding(slots[index], rhs[index], !recursive && primitiveEligible[index])
    }
    override fun execute(frame: VirtualFrame): Any? {
        initialize(frame)
        return body.execute(frame)
    }
    override fun executeLong(frame: VirtualFrame): Long {
        initialize(frame)
        return body.executeLong(frame)
    }
    override fun executeFloat(frame: VirtualFrame): Float { initialize(frame); return body.executeFloat(frame) }
    override fun executeDouble(frame: VirtualFrame): Double { initialize(frame); return body.executeDouble(frame) }
    override fun executeClosure(frame: VirtualFrame): Closure {
        initialize(frame)
        return body.executeClosure(frame)
    }
    override fun executeDataValue(frame: VirtualFrame): DataValue {
        initialize(frame)
        return body.executeDataValue(frame)
    }
    override fun executeAddress(frame: VirtualFrame): LiteralAddress {
        initialize(frame)
        return body.executeAddress(frame)
    }
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        initialize(frame); return body.executeTuple(frame, slots, offset)
    }
    @ExplodeLoop private fun initialize(frame: VirtualFrame) {
        if (recursive) {
            // A closure captures these cells, never a mutable activation frame.
            for (slot in slots) FrameAccess.write(frame, slot, RecCell())
            for (i in slots.indices) {
                val cell = FrameAccess.read(frame, slots[i]) as? RecCell ?: fault("Invalid recursive cell")
                cell.value = bindings[i].evaluate(frame)
                cell.initialized = true
            }
            // Existing recursive captures retain the cells; the let body and
            // closures built after publication read the values directly.
            // Publish only after every RHS has captured the whole group.
            for (slot in slots) {
                val cell = FrameAccess.read(frame, slot) as? RecCell ?: fault("Invalid recursive cell")
                FrameAccess.write(frame, slot, cell.value)
            }
        } else for (binding in bindings) binding.write(frame)
    }
}
private const val DEFAULT_ALTERNATIVE = 0
private const val DATA_ALTERNATIVE = 1
private const val LITERAL_ALTERNATIVE = 2

private class Alternative(val kind: Int, val value: Any?,
                          @field:CompilationFinal(dimensions = 1) val fields: IntArray,
                          @field:Child var body: Expr) : Node() {
    private val matchProfile = CountingConditionProfile.create()
    fun matchesData(value: DataValue): Boolean = matchProfile.profile((this.value as DataLayout).matches(value))
    fun matchesLong(value: Long): Boolean = matchProfile.profile(value == this.value as Long)
    fun matches(frame: VirtualFrame, slot: Int): Boolean = matchProfile.profile(when (kind) {
        DATA_ALTERNATIVE -> if (frame.isObject(slot)) {
            val scrutinee = frame.getObject(slot)
            (value as DataLayout).matches(scrutinee)
        } else false
        LITERAL_ALTERNATIVE -> if (value is Long && frame.isLong(slot)) frame.getLong(slot) == value
            else FrameAccess.read(frame, slot) == value
        else -> false
    })
}
private open class Case(scrutinee: Expr, protected val binderSlot: Int,
                   @field:Children protected var alternatives: Array<Alternative>, metrics: Metrics,
                   binderProof: CoreRepresentation? = null) : Expr() {
    init {
        val proofs = alternatives.map { it.body.representation }
        val kinds = proofs.map { it.kind }.toSet()
        val kind = when {
            kinds.size == 1 -> kinds.single()
            kinds.isNotEmpty() && kinds.all { it in setOf(CoreKind.DATA, CoreKind.CLOSURE, CoreKind.OBJECT) } -> CoreKind.OBJECT
            else -> CoreKind.UNKNOWN
        }
        representation = CoreVectors.caseResult(proofs)
            ?: CoreRepresentation(kind, proofs.all { it.evaluated }, proofs.isNotEmpty() && proofs.all { it.present })
    }
    // Preserve primitive scrutinees through their frame write and literal comparisons.
    @Child private var scrutinee = LocalBinding(binderSlot, Evaluate(scrutinee, metrics).apply {
        if (binderProof != null) representation = representation.refine(binderProof.copy(evaluated = false))
    }, true)
    protected fun prepare(frame: VirtualFrame) { scrutinee.write(frame) }
    protected open fun matches(frame: VirtualFrame, alternative: Alternative): Boolean = alternative.matches(frame, binderSlot)

    @ExplodeLoop override fun execute(frame: VirtualFrame): Any? {
        prepare(frame)
        var fallback: Alternative? = null
        for (alt in alternatives) {
            if (alt.kind == DEFAULT_ALTERNATIVE) { fallback = alt; continue }
            if (matches(frame, alt)) {
                restoreFields(frame, alt)
                return alt.body.execute(frame)
            }
        }
        return (fallback ?: fault("Non-exhaustive Core case")).body.execute(frame)
    }

    @ExplodeLoop override fun executeLong(frame: VirtualFrame): Long {
        prepare(frame)
        var fallback: Alternative? = null
        for (alt in alternatives) {
            if (alt.kind == DEFAULT_ALTERNATIVE) { fallback = alt; continue }
            if (matches(frame, alt)) {
                restoreFields(frame, alt)
                return alt.body.executeLong(frame)
            }
        }
        return (fallback ?: fault("Non-exhaustive Core case")).body.executeLong(frame)
    }


    @ExplodeLoop override fun executeFloat(frame: VirtualFrame): Float {
        prepare(frame)
        var fallback: Alternative? = null
        for (alt in alternatives) {
            if (alt.kind == DEFAULT_ALTERNATIVE) { fallback = alt; continue }
            if (matches(frame, alt)) {
                restoreFields(frame, alt)
                return alt.body.executeFloat(frame)
            }
        }
        return (fallback ?: fault("Non-exhaustive Core case")).body.executeFloat(frame)
    }

    @ExplodeLoop override fun executeDouble(frame: VirtualFrame): Double {
        prepare(frame)
        var fallback: Alternative? = null
        for (alt in alternatives) {
            if (alt.kind == DEFAULT_ALTERNATIVE) { fallback = alt; continue }
            if (matches(frame, alt)) {
                restoreFields(frame, alt)
                return alt.body.executeDouble(frame)
            }
        }
        return (fallback ?: fault("Non-exhaustive Core case")).body.executeDouble(frame)
    }

    @ExplodeLoop override fun executeClosure(frame: VirtualFrame): Closure {
        prepare(frame)
        var fallback: Alternative? = null
        for (alt in alternatives) {
            if (alt.kind == DEFAULT_ALTERNATIVE) { fallback = alt; continue }
            if (matches(frame, alt)) {
                restoreFields(frame, alt)
                return alt.body.executeClosure(frame)
            }
        }
        return (fallback ?: fault("Non-exhaustive Core case")).body.executeClosure(frame)
    }

    @ExplodeLoop override fun executeDataValue(frame: VirtualFrame): DataValue {
        prepare(frame)
        var fallback: Alternative? = null
        for (alt in alternatives) {
            if (alt.kind == DEFAULT_ALTERNATIVE) { fallback = alt; continue }
            if (matches(frame, alt)) {
                restoreFields(frame, alt)
                return alt.body.executeDataValue(frame)
            }
        }
        return (fallback ?: fault("Non-exhaustive Core case")).body.executeDataValue(frame)
    }

    @ExplodeLoop override fun executeAddress(frame: VirtualFrame): LiteralAddress {
        prepare(frame)
        var fallback: Alternative? = null
        for (alt in alternatives) {
            if (alt.kind == DEFAULT_ALTERNATIVE) { fallback = alt; continue }
            if (matches(frame, alt)) {
                restoreFields(frame, alt)
                return alt.body.executeAddress(frame)
            }
        }
        return (fallback ?: fault("Non-exhaustive Core case")).body.executeAddress(frame)
    }

    @ExplodeLoop override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        prepare(frame)
        var fallback: Alternative? = null
        for (alt in alternatives) {
            if (alt.kind == DEFAULT_ALTERNATIVE) { fallback = alt; continue }
            if (matches(frame, alt)) {
                restoreFields(frame, alt)
                return alt.body.executeTuple(frame, slots, offset)
            }
        }
        return (fallback ?: fault("Non-exhaustive Core case")).body.executeTuple(frame, slots, offset)
    }
    @ExplodeLoop private fun restoreFields(frame: VirtualFrame, alt: Alternative) {
        if (alt.kind == DATA_ALTERNATIVE) {
            val data = frame.getObject(binderSlot) as? DataValue ?: fault("Invalid constructor case")
            val layout = alt.value as? DataLayout ?: fault("Invalid constructor alternative")
            for (i in alt.fields.indices) layout.restore(data, i, frame, alt.fields[i])
        }
    }
}
/** Category selection happens during lowering, never in the guest loop. */
private class DataCase(scrutinee: Expr, binder: Int, alternatives: Array<Alternative>, metrics: Metrics,
                       proof: CoreRepresentation) : Case(scrutinee, binder, alternatives, metrics, proof) {
    override fun matches(frame: VirtualFrame, alternative: Alternative): Boolean =
        alternative.matchesData(frame.getObject(binderSlot) as DataValue)
}
private class LongCase(scrutinee: Expr, binder: Int, alternatives: Array<Alternative>, metrics: Metrics,
                       proof: CoreRepresentation) : Case(scrutinee, binder, alternatives, metrics, proof) {
    override fun matches(frame: VirtualFrame, alternative: Alternative): Boolean =
        alternative.matchesLong(frame.getLong(binderSlot))
}
private class DefaultCase(scrutinee: Expr, binder: Int, alternatives: Array<Alternative>, metrics: Metrics,
                          proof: CoreRepresentation) : Case(scrutinee, binder, alternatives, metrics, proof) {
    override fun execute(frame: VirtualFrame): Any? { prepare(frame); return alternatives.last().body.execute(frame) }
    override fun executeLong(frame: VirtualFrame): Long { prepare(frame); return alternatives.last().body.executeLong(frame) }
    override fun executeFloat(frame: VirtualFrame): Float { prepare(frame); return alternatives.last().body.executeFloat(frame) }
    override fun executeDouble(frame: VirtualFrame): Double { prepare(frame); return alternatives.last().body.executeDouble(frame) }
    override fun executeClosure(frame: VirtualFrame): Closure { prepare(frame); return alternatives.last().body.executeClosure(frame) }
    override fun executeDataValue(frame: VirtualFrame): DataValue { prepare(frame); return alternatives.last().body.executeDataValue(frame) }
    override fun executeAddress(frame: VirtualFrame): LiteralAddress { prepare(frame); return alternatives.last().body.executeAddress(frame) }
}
private class Construct(private val layout: DataLayout,
                        @field:Children private var fields: Array<Expr>) : Expr() {
    init { representation = CoreRepresentation(CoreKind.DATA, evaluated = true) }
    @ExplodeLoop override fun execute(frame: VirtualFrame): DataValue {
        if (layout.hasBoxedValueCache) return layout.createLong(fields[0].executeRequiredLong(frame))
        val value = layout.allocate()
        for (i in fields.indices) {
            if (layout.isLong(i)) layout.initializeLong(value, i, fields[i].executeRequiredLong(frame))
            else if (layout.isFloat(i)) layout.initializeFloat(value, i, fields[i].executeRequiredFloat(frame))
            else if (layout.isDouble(i)) layout.initializeDouble(value, i, fields[i].executeRequiredDouble(frame))
            else layout.initialize(value, i, fields[i].execute(frame))
        }
        return value
    }
    override fun executeDataValue(frame: VirtualFrame): DataValue = execute(frame)
}
/** Compare the operand references themselves, including untouched or updated thunks. */
private class PointerEquality(@field:Child private var left: Expr, @field:Child private var right: Expr) : Expr() {
    init { representation = CoreRepresentation(CoreKind.LONG, evaluated = true) }
    override fun execute(frame: VirtualFrame): Long = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long = if (left.execute(frame) === right.execute(frame)) 1L else 0L
}
private class Primitive(private val name: String, @field:Children private var arguments: Array<Expr>) : Expr() {
    private val operation = scalar64PrimitiveOperation(name)
    private val wordMask = narrowWordPrimitiveMask(name)
    private val intShift = narrowIntPrimitiveShift(name)
    private val bitShift = scalarBitPrimitiveShift(name)
    private val bitMask = -1L ushr bitShift
    init {
        representation = CoreRepresentation(CoreKind.LONG, evaluated = true)
        val arity = when (operation) {
            "popCnt8#", "popCnt16#", "popCnt32#", "popCnt64#" -> 1
            "clz8#", "clz16#", "clz32#", "clz64#" -> 1
            "ctz8#", "ctz16#", "ctz32#", "ctz64#" -> 1
            "byteSwap16#", "byteSwap32#", "byteSwap64#", "byteSwap#" -> 1
            "bitReverse8#", "bitReverse16#", "bitReverse32#", "bitReverse64#", "bitReverse#" -> 1

            "negateInt8#", "negateInt16#", "negateInt32#" -> 1
            "plusInt8#", "plusInt16#", "plusInt32#" -> 2
            "subInt8#", "subInt16#", "subInt32#" -> 2
            "timesInt8#", "timesInt16#", "timesInt32#" -> 2
            "quotInt8#", "quotInt16#", "quotInt32#" -> 2
            "remInt8#", "remInt16#", "remInt32#" -> 2
            "eqInt8#", "eqInt16#", "eqInt32#" -> 2
            "neInt8#", "neInt16#", "neInt32#" -> 2
            "ltInt8#", "ltInt16#", "ltInt32#" -> 2
            "leInt8#", "leInt16#", "leInt32#" -> 2
            "gtInt8#", "gtInt16#", "gtInt32#" -> 2
            "geInt8#", "geInt16#", "geInt32#" -> 2

            "quotWord#" -> 2
            "remWord#" -> 2
            "gtWord#" -> 2
            "geWord#" -> 2
            "quotWord8#", "quotWord16#", "quotWord32#" -> 2
            "remWord8#", "remWord16#", "remWord32#" -> 2
            "eqWord8#", "eqWord16#", "eqWord32#" -> 2
            "neWord8#", "neWord16#", "neWord32#" -> 2
            "gtWord8#", "gtWord16#", "gtWord32#" -> 2
            "geWord8#", "geWord16#", "geWord32#" -> 2
            "andWord8#", "andWord16#", "andWord32#" -> 2
            "orWord8#", "orWord16#", "orWord32#" -> 2
            "xorWord8#", "xorWord16#", "xorWord32#" -> 2
            "notWord8#", "notWord16#", "notWord32#" -> 1
            "uncheckedShiftLWord8#", "uncheckedShiftLWord16#", "uncheckedShiftLWord32#" -> 2
            "uncheckedShiftRLWord8#", "uncheckedShiftRLWord16#", "uncheckedShiftRLWord32#" -> 2
            "negateInt#", "not#", "notI#", "clz#", "ctz#", "popCnt#", "int2Word#", "word2Int#", "ord#", "chr#",
            "narrow8Int#", "narrow16Int#", "narrow32Int#", "intToInt64#", "int64ToInt#",
            "intToInt8#", "int8ToInt#", "intToInt16#", "int16ToInt#", "intToInt32#", "int32ToInt#",
            "wordToWord8#", "word8ToWord#", "wordToWord16#", "word16ToWord#", "wordToWord32#", "word32ToWord#" -> 1
            "+#", "plusWord#", "-#", "minusWord#", "*#", "timesWord#", "quotInt#", "remInt#",
            "==#", "eqWord#", "eqChar#", "/=#", "neWord#", "neChar#", "<#", "ltWord#", "ltChar#", "<=#", "leWord#", "leChar#",
            ">#", "gtChar#", ">=#", "geChar#", "and#", "andI#", "or#", "orI#", "xor#", "xorI#",
            "uncheckedIShiftL#", "uncheckedShiftL#", "uncheckedIShiftRA#", "uncheckedIShiftRL#", "uncheckedShiftRL#",
            "plusWord8#", "subWord8#", "timesWord8#", "ltWord8#", "leWord8#",
            "plusWord16#", "subWord16#", "timesWord16#", "ltWord16#", "leWord16#",
            "plusWord32#", "subWord32#", "timesWord32#", "ltWord32#", "leWord32#" -> 2
            else -> throw UnsupportedCore("Unsupported primitive $name")
        }
        if (arguments.size != arity) throw RuntimeFault("Primitive arity mismatch: $name")
    }
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long {
        val x = arguments[0].executeRequiredLong(frame)
        val y = if (arguments.size == 2) arguments[1].executeRequiredLong(frame) else 0L
        fun b(value: Boolean) = if (value) 1L else 0L
        return when (operation) {
            "popCnt8#", "popCnt16#", "popCnt32#", "popCnt64#" -> java.lang.Long.bitCount(x and bitMask).toLong()
            "clz8#", "clz16#", "clz32#", "clz64#" -> (java.lang.Long.numberOfLeadingZeros(x and bitMask) - bitShift).toLong()
            "ctz8#", "ctz16#", "ctz32#", "ctz64#" -> minOf(java.lang.Long.numberOfTrailingZeros(x and bitMask), 64 - bitShift).toLong()
            "byteSwap16#", "byteSwap32#", "byteSwap64#", "byteSwap#" -> java.lang.Long.reverseBytes(x) ushr bitShift
            "bitReverse8#", "bitReverse16#", "bitReverse32#", "bitReverse64#", "bitReverse#" -> java.lang.Long.reverse(x) ushr bitShift
            "negateInt8#", "negateInt16#", "negateInt32#" -> signedNarrow(-x, intShift)
            "plusInt8#", "plusInt16#", "plusInt32#" -> signedNarrow(x + y, intShift)
            "subInt8#", "subInt16#", "subInt32#" -> signedNarrow(x - y, intShift)
            "timesInt8#", "timesInt16#", "timesInt32#" -> signedNarrow(x * y, intShift)
            "quotInt8#", "quotInt16#", "quotInt32#" -> signedNarrow(signedNarrow(x, intShift) / signedNarrow(y, intShift), intShift)
            "remInt8#", "remInt16#", "remInt32#" -> signedNarrow(signedNarrow(x, intShift) % signedNarrow(y, intShift), intShift)
            "eqInt8#", "eqInt16#", "eqInt32#" -> b(signedNarrow(x, intShift) == signedNarrow(y, intShift))
            "neInt8#", "neInt16#", "neInt32#" -> b(signedNarrow(x, intShift) != signedNarrow(y, intShift))
            "ltInt8#", "ltInt16#", "ltInt32#" -> b(signedNarrow(x, intShift) < signedNarrow(y, intShift))
            "leInt8#", "leInt16#", "leInt32#" -> b(signedNarrow(x, intShift) <= signedNarrow(y, intShift))
            "gtInt8#", "gtInt16#", "gtInt32#" -> b(signedNarrow(x, intShift) > signedNarrow(y, intShift))
            "geInt8#", "geInt16#", "geInt32#" -> b(signedNarrow(x, intShift) >= signedNarrow(y, intShift))

            "quotWord#" -> java.lang.Long.divideUnsigned(x, y)
            "remWord#" -> java.lang.Long.remainderUnsigned(x, y)
            "gtWord#" -> b(java.lang.Long.compareUnsigned(x, y) > 0)
            "geWord#" -> b(java.lang.Long.compareUnsigned(x, y) >= 0)
            "quotWord8#", "quotWord16#", "quotWord32#" -> (x and wordMask) / (y and wordMask)
            "remWord8#", "remWord16#", "remWord32#" -> (x and wordMask) % (y and wordMask)
            "eqWord8#", "eqWord16#", "eqWord32#" -> b((x and wordMask) == (y and wordMask))
            "neWord8#", "neWord16#", "neWord32#" -> b((x and wordMask) != (y and wordMask))
            "gtWord8#", "gtWord16#", "gtWord32#" -> b((x and wordMask) > (y and wordMask))
            "geWord8#", "geWord16#", "geWord32#" -> b((x and wordMask) >= (y and wordMask))
            "andWord8#", "andWord16#", "andWord32#" -> (x and y) and wordMask
            "orWord8#", "orWord16#", "orWord32#" -> (x or y) and wordMask
            "xorWord8#", "xorWord16#", "xorWord32#" -> (x xor y) and wordMask
            "notWord8#", "notWord16#", "notWord32#" -> x.inv() and wordMask
            "uncheckedShiftLWord8#", "uncheckedShiftLWord16#", "uncheckedShiftLWord32#" -> (x shl y.toInt()) and wordMask
            "uncheckedShiftRLWord8#", "uncheckedShiftRLWord16#", "uncheckedShiftRLWord32#" -> (x and wordMask) ushr y.toInt()
            "+#", "plusWord#" -> x + y
            "-#", "minusWord#" -> x - y
            "*#", "timesWord#" -> x * y
            "plusWord8#", "plusWord16#", "plusWord32#" -> (x + y) and wordMask
            "subWord8#", "subWord16#", "subWord32#" -> (x - y) and wordMask
            "timesWord8#", "timesWord16#", "timesWord32#" -> (x * y) and wordMask
            "negateInt#" -> -x
            "quotInt#" -> x / y
            "remInt#" -> x % y
            "==#", "eqWord#", "eqChar#" -> b(x == y)
            "/=#", "neWord#", "neChar#" -> b(x != y)
            "<#", "ltChar#" -> b(x < y)
            "ltWord#" -> b(java.lang.Long.compareUnsigned(x, y) < 0)
            "<=#", "leChar#" -> b(x <= y)
            "leWord#" -> b(java.lang.Long.compareUnsigned(x, y) <= 0)
            // These masks fit below Long's sign bit, so signed order is unsigned order.
            "ltWord8#", "ltWord16#", "ltWord32#" -> b((x and wordMask) < (y and wordMask))
            "leWord8#", "leWord16#", "leWord32#" -> b((x and wordMask) <= (y and wordMask))
            ">#", "gtChar#" -> b(x > y)
            ">=#", "geChar#" -> b(x >= y)
            "and#", "andI#" -> x and y
            "or#", "orI#" -> x or y
            "xor#", "xorI#" -> x xor y
            "not#", "notI#" -> x.inv()
            "clz#" -> java.lang.Long.numberOfLeadingZeros(x).toLong()
            "ctz#" -> java.lang.Long.numberOfTrailingZeros(x).toLong()
            "popCnt#" -> java.lang.Long.bitCount(x).toLong()
            "uncheckedIShiftL#", "uncheckedShiftL#" -> x shl y.toInt()
            "uncheckedIShiftRA#" -> x shr y.toInt()
            "uncheckedIShiftRL#", "uncheckedShiftRL#" -> x ushr y.toInt()
            // Narrow signed values use sign-normalized Long carriers. Truncation
            // and signed widening therefore share the width-specific conversion.
            "narrow8Int#", "intToInt8#", "int8ToInt#" -> x.toByte().toLong()
            "narrow16Int#", "intToInt16#", "int16ToInt#" -> x.toShort().toLong()
            "narrow32Int#", "intToInt32#", "int32ToInt#" -> x.toInt().toLong()
            "wordToWord8#", "word8ToWord#", "wordToWord16#", "word16ToWord#", "wordToWord32#", "word32ToWord#" -> x and wordMask
            "int2Word#", "word2Int#", "ord#", "chr#", "intToInt64#", "int64ToInt#" -> x
            else -> fault("Unsupported primitive")
        }
    }
}
private class FunctionBody(expression: Expr, metrics: Metrics, result: CoreRepresentation,
    private val tuple: TupleShape?, @field:CompilationFinal(dimensions = 1) private val tupleSlots: IntArray) : Node() {
    @Child private var value = Evaluate(expression, metrics)
    private val resultKind = if (result.kind == CoreKind.UNKNOWN) expression.representation.kind else result.kind
    private val exactLong = resultKind == CoreKind.LONG
    @CompilationFinal private var genericResult = result.present && !exactLong

    /** Keep a primitive body until the mandatory Object-returning root/call boundary. */
    fun execute(frame: VirtualFrame): Any? {
        if (tuple != null) {
            value.executeTuple(frame, tupleSlots, 0)
            return tuple.finish(frame, tupleSlots)
        }
        // Kotlin's enum when uses a mutable synthetic int[] mapping. Graal
        // cannot fold that lookup, even when this node's resultKind is constant.
        if (resultKind == CoreKind.LONG) return value.executeRequiredLong(frame)
        if (resultKind == CoreKind.FLOAT) return value.executeRequiredFloat(frame)
        if (resultKind == CoreKind.DOUBLE) return value.executeRequiredDouble(frame)
        if (resultKind == CoreKind.DATA) return value.executeRequiredDataValue(frame)
        if (resultKind == CoreKind.CLOSURE) return value.executeRequiredClosure(frame)
        if (resultKind == CoreKind.ADDRESS) return value.executeRequiredAddress(frame)
        if (genericResult) return value.execute(frame)
        return try { value.executeLong(frame) }
        catch (unexpected: UnexpectedResultException) {
            genericResult = true
            return unexpected.result
        }
    }
    fun executeLong(frame: VirtualFrame): Long = value.executeRequiredLong(frame)
}
private class SelfRepeater(@field:Child private var body: FunctionBody, private val metrics: Metrics) : Node(), RepeatingNode {
    fun once(frame: VirtualFrame): Any? {
        val entry = (rootNode as FunctionRoot).handoff
        return if (entry != null && entry.resultLong && entry.destination(frame) >= 0) entry.finishLong(frame, body.executeLong(frame))
        else body.execute(frame)
    }
    override fun executeRepeating(frame: VirtualFrame): Boolean = error("value loop")
    override fun executeRepeatingWithValue(frame: VirtualFrame): Any? = try { once(frame) }
    catch (_: AstSelfCall) {
        if (metrics.enabled) metrics.selfTailReentries++
        RepeatingNode.CONTINUE_LOOP_STATUS
    }
    catch (tail: HandoffTailCall) {
        val root = rootNode as FunctionRoot
        if (!root.isSelf(tail.target)) throw tail
        if (metrics.enabled) metrics.selfTailReentries++
        root.restoreHandoff(frame, tail.arguments, false)
        RepeatingNode.CONTINUE_LOOP_STATUS
    }
    catch (tail: TailCall) {
        val root = rootNode as FunctionRoot
        if (!root.isSelf(tail.target)) throw tail
        if (metrics.enabled) metrics.selfTailReentries++
        root.buildFrame(tail.args, frame)
        RepeatingNode.CONTINUE_LOOP_STATUS
    }
}
internal class FunctionRoot(language: TruffleLanguage<*>?, descriptor: FrameDescriptor, private val label: String,
                            private val captureLayout: CaptureLayout?,
                            @field:CompilationFinal(dimensions = 1) private val environmentSlots: IntArray,
                            @field:CompilationFinal(dimensions = 1) private val argumentSlots: IntArray,
                            @field:CompilationFinal(dimensions = 1) private val argumentIndices: IntArray,
                            body: Expr, private val metrics: Metrics,
                            @field:CompilationFinal(dimensions = 1) private val argumentProofs: Array<CoreRepresentation> = emptyArray(),
                            resultProof: CoreRepresentation = body.representation,
                            private val coreSourceLocation: CoreSourceLocation? = body.coreSourceLocation,
                            entryStrict: BooleanArray = booleanArrayOf(),
                            internal val handoff: HandoffEntry? = null,
                            tuple: TupleShape? = null,
                            tupleSlots: IntArray = intArrayOf()) : GuestRoot(language, descriptor) {
    init { configureEntry(entryStrict, captureLayout != null); configureTupleResult(tuple) }
    @field:CompilationFinal(dimensions = 1)
    private val argumentReferences = argumentProofs.map { it.referenceCarrier() }.toTypedArray()
    @field:CompilationFinal private var hasSelfTail = false
    private val tailCallProfile = BranchProfile.create()
    @Child private var loop: LoopNode = Truffle.getRuntime().createLoopNode(SelfRepeater(FunctionBody(body, metrics, resultProof, tuple, tupleSlots), metrics))
    override fun bloom(frame: VirtualFrame): Long = frame.getLong(FrameLayout.BLOOM_FILTER)
    @ExplodeLoop fun buildFrame(arguments: Array<Any?>, frame: VirtualFrame) {
        val offset = if (captureLayout == null) 1 else 2
        for (i in argumentSlots.indices) {
            val value = arguments[argumentIndices[i] + offset]
            val reference = argumentReferences.getOrNull(i)
            if (reference != null)
                FrameAccess.write(frame, argumentSlots[i], requireReferenceCarrier(value, reference))
            else if (i < argumentProofs.size && argumentProofs[i].isLong)
                FrameAccess.writeLong(frame, argumentSlots[i], value as? Long ?: fault("Expected primitive Long argument"))
            else if (i < argumentProofs.size && argumentProofs[i].isFloat)
                FrameAccess.writeFloat(frame, argumentSlots[i], value as? Float ?: fault("Expected primitive Float argument"))
            else if (i < argumentProofs.size && argumentProofs[i].isDouble)
                FrameAccess.writeDouble(frame, argumentSlots[i], value as? Double ?: fault("Expected primitive Double argument"))
            else FrameAccess.write(frame, argumentSlots[i], value)
        }
        if (captureLayout != null) {
            val environment = arguments[1] as? CapturedFrame ?: fault("Invalid captured frame")
            for (i in environmentSlots.indices) captureLayout.restore(environment, i, frame, environmentSlots[i])
        }
    }
    fun handoffDestination(frame: VirtualFrame): Int = handoff?.destination(frame) ?: -1

    @ExplodeLoop internal fun restoreHandoff(frame: VirtualFrame, input: HandoffStorage, initial: Boolean) {
        val entry = handoff ?: fault("Target does not support the handoff ABI")
        check(input.layout === entry.arguments && input.live)
        if (!initial) check(entry.destination(frame) >= 0)
        try {
            if (initial) {
                entry.snapshot(frame, input)
                frame.setLong(entry.destinationSlot, 0L)
                frame.setLong(FrameLayout.BLOOM_FILTER, entry.arguments.getLong(input, 0) or mask)
            }
            val offset = if (captureLayout == null) 1 else 2
            for (i in argumentSlots.indices) {
                val position = argumentIndices[i] + offset
                if (entry.arguments.isLong(position)) FrameAccess.writeLong(frame, argumentSlots[i], entry.arguments.getLong(input, position))
                else {
                    val value = entry.arguments.getObject(input, position)
                    val reference = argumentReferences.getOrNull(i)
                    FrameAccess.write(frame, argumentSlots[i], if (reference != null) requireReferenceCarrier(value, reference) else value)
                }
            }
            if (captureLayout != null) {
                val environment = entry.arguments.getObject(input, 1) as? CapturedFrame ?: fault("Invalid captured frame")
                for (i in environmentSlots.indices) captureLayout.restore(environment, i, frame, environmentSlots[i])
            }
        } finally { entry.state().arguments.release(input, entry.arguments) }
    }

    override fun execute(frame: VirtualFrame): Any? {
        if (metrics.enabled && CompilerDirectives.inCompiledCode()) metrics.compiledEntries++
        val entry = handoff
        if (entry != null && frame.arguments.isEmpty()) {
            val state = entry.state()
            val input = state.pending ?: fault("Missing typed argument loan")
            state.pending = null
            restoreHandoff(frame, input, true)
        } else {
            entry?.initializeOrdinary(frame)
            frame.setLong(FrameLayout.BLOOM_FILTER, (frame.arguments[0] as? Long ?: fault("Invalid bloom argument")) or mask)
            buildFrame(frame.arguments, frame)
        }
        return executeBody(frame)
    }

    private fun executeBody(frame: VirtualFrame): Any? {
        // Non-looping roots retain entry argument facts. Once self recursion
        // is observed, PE selects only the loop body instead of duplicating it.
        if (hasSelfTail) return loop.execute(frame)
        return try { (loop.repeatingNode as SelfRepeater).once(frame) }
        catch (_: AstSelfCall) {
            tailCallProfile.enter()
            if (metrics.enabled) metrics.selfTailReentries++
            CompilerDirectives.transferToInterpreterAndInvalidate()
            hasSelfTail = true
            loop.execute(frame)
        }
        catch (tail: HandoffTailCall) {
            tailCallProfile.enter()
            if (!isSelf(tail.target)) throw tail
            if (metrics.enabled) metrics.selfTailReentries++
            CompilerDirectives.transferToInterpreterAndInvalidate()
            hasSelfTail = true
            restoreHandoff(frame, tail.arguments, false)
            loop.execute(frame)
        }
        catch (tail: TailCall) {
            tailCallProfile.enter()
            if (!isSelf(tail.target)) throw tail
            if (metrics.enabled) metrics.selfTailReentries++
            CompilerDirectives.transferToInterpreterAndInvalidate()
            hasSelfTail = true
            // Keep this frame's bloom ancestry and restore the new captures.
            buildFrame(tail.args, frame)
            loop.execute(frame)
        }
    }
    override fun getSourceSection(): SourceSection? = coreSourceLocation?.section
    fun getCoreSourceNotes(): List<CoreSourceNote> = coreSourceLocation?.notes.orEmpty()
    override fun getName() = label
    override fun toString() = label
    override fun isCloningAllowed() = true
}
internal class EntryRoot(language: TruffleLanguage<*>?, private val arity: Int, metrics: Metrics) : RootNode(language, FrameLayout().build()) {
    @Child private var dispatch = Dispatch.create(arity, false, metrics)
    @Child private var force = Force(metrics)
    override fun execute(frame: VirtualFrame): Any? {
        frame.setLong(FrameLayout.BLOOM_FILTER, 0L)
        val value = force.execute(frame, frame.arguments[0])
        if (arity == 0) return value
        val fn = value as? Closure ?: fault("Application of a non-function")
        val args = frame.arguments[1] as? Array<Any?> ?: fault("Invalid host arguments")
        return force.execute(frame, dispatch.execute(frame, fn, args))
    }
    override fun getName() = "THC host entry/$arity"
}
private data class Local(val slot: Int, val primitive: Boolean, val proof: CoreRepresentation, val cell: Boolean,
                         val entry: BooleanArray? = null, val tupleSlots: IntArray? = null)
private class Scope(val layout: FrameLayout, val locals: MutableMap<String, Local> = linkedMapOf(),
                    val joins: MutableMap<String, LocalJoinTarget> = linkedMapOf(),
                    var self: AstSelfLayout? = null) {
    fun child() = Scope(layout.scope(), LinkedHashMap(locals), LinkedHashMap(joins), self)
    fun bind(id: String, primitive: Boolean, proof: CoreRepresentation = CoreRepresentation.UNKNOWN, cell: Boolean = false,
             entry: BooleanArray? = null): Local =
        Local(layout.bind(id), if (proof.present) proof.isLong else primitive, proof, cell, entry).also { locals[id] = it; joins.remove(id) }
    fun bindTuple(id: String, proof: CoreRepresentation, slots: IntArray): Local =
        Local(-1, false, proof, false, tupleSlots = slots).also { locals[id] = it; joins.remove(id) }
    fun bindVoid(id: String, proof: CoreRepresentation): Local =
        Local(-1, false, proof.copy(evaluated = true), false).also { locals[id] = it; joins.remove(id) }
    fun refine(id: String, proof: CoreRepresentation) { locals[id]?.let { locals[id] = it.copy(proof = proof, primitive = if (proof.present) proof.isLong else it.primitive) } }
    fun publish(id: String, proof: CoreRepresentation) {
        refine(id, proof)
        locals[id]?.let { locals[id] = it.copy(cell = false) }
    }
    fun bindJoin(id: String, target: LocalJoinTarget) { joins[id] = target; locals.remove(id) }
}
private data class FunctionSpec(val target: RootCallTarget, val captureLayout: CaptureLayout?, val captures: IntArray)

/** Exported GHC Core lowers lexical bindings to indexed frame slots, as Cadenza does. */
class Program(private val language: TruffleLanguage<*>?, moduleData: Map<String, Any?>) : ExecutableProgram {
    private val callDemandsEnabled = java.lang.Boolean.getBoolean(CALL_DEMANDS_PROPERTY)
    private val metrics = Metrics(moduleData["instrument"] != false)
    private val sources = CoreSources(moduleData)
    private var currentSource: CoreSourceLocation? = null
    private var attachedRootCount = 0
    private fun <T> withSource(location: CoreSourceLocation?, action: () -> T): T {
        val previous = currentSource
        currentSource = location
        return try { action() } finally { currentSource = previous }
    }
    private fun rootSource(body: Expr): CoreSourceLocation? = (body.coreSourceLocation ?: currentSource).also {
        if (it != null) attachedRootCount++
    }
    private val diagnosticUnsupported = moduleData["diagnosticUnsupported"] == true
    private val deferredUnsupported = linkedSetOf<String>()
    private val bindings = moduleData["bindings"] as? List<Map<String, Any?>> ?: throw RuntimeFault("Missing bindings")
    private val constructors = (moduleData["constructors"] as? List<Map<String, Any?>> ?: emptyList()).associateBy { it["id"] as String }
    private val dataLayouts = mutableMapOf<String, DataLayout>()
    private val globals = bindings.associate { it["id"] as String to GlobalBinding(it["name"] as String) }
    private val globalProofs = bindings.associate { binding ->
        val expression = binding["expr"] as List<Any?>
        val delayed = representation(binding) && expression[0] !in listOf("lam", "lit", "con", "void")
        (binding["id"] as String) to if (diagnosticUnsupported) CoreRepresentation.UNKNOWN
            else CoreRepresentations.binder(binding).copy(evaluated = !delayed && expression[0] in listOf("lam", "lit", "con", "void"))
    }
    private val indices = bindings.withIndex().associate { it.value["id"] as String to it.index }
    private val names = bindings.withIndex().groupBy({ it.value["name"] as String }, { it.index })
    private val hostEntries = mutableMapOf<Int, RootCallTarget>()
    private val globalEntries = bindings.associate { it["id"] as String to CoreEntries.binding(it) }
    init {
        if (!diagnosticUnsupported) CoreRepresentations.validateAggregates(bindings)
        val scope = Scope(FrameLayout())
        val initializers = bindings.map { binding ->
            withSource(sources.binding(binding)) {
                val expr = binding["expr"] as List<Any?>
                if (representation(binding) && expr[0] !in listOf("lam", "lit", "con", "void")) delay(expr, scope, binding["name"] as String)
                else argument(expr, scope, representation(binding), binding["name"] as String)
            }
        }
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), scope.layout.build())
        bindings.forEachIndexed { index, binding -> globals.getValue(binding["id"] as String).initialize(initializers[index].execute(frame)) }
    }
    private fun bindingIndex(name: String): Int = indices[name] ?: names[name]?.singleOrNull()
        ?: names.entries.singleOrNull { it.key.substringAfterLast('.') == name }?.value?.singleOrNull()
        ?: throw RuntimeFault("Unknown or ambiguous entry $name")
    override fun hostEntryTarget(arity: Int): RootCallTarget = hostEntries.getOrPut(arity) { EntryRoot(language, arity, metrics).callTarget }
    override fun entryValue(name: String): Any? = globals.getValue(bindings[bindingIndex(name)]["id"] as String).read()
    override fun entryTarget(name: String): RootCallTarget {
        var value = entryValue(name)
        while (value is Thunk && value.state == 2) value = value.value
        return when (value) { is Closure -> value.target; is Thunk -> value.target ?: hostEntryTarget(0); else -> hostEntryTarget(0) }
    }
    override fun diagnostics(): Map<String, Any> = linkedMapOf(
        "backend" to "ast", "sourceNotesEnabled" to sources.enabled, "sourceSpanCount" to sources.spanCount,
        "sourceRootCount" to attachedRootCount, "instrumented" to metrics.enabled, "thunkEvaluationsByLabel" to metrics.thunkEvaluationsByLabel.toMap(),
        "compiledEntries" to metrics.compiledEntries, "leadingCaseReturns" to metrics.leadingCaseReturns, "thunkEvaluations" to metrics.thunkEvaluations,
        "thunkHits" to metrics.thunkHits, "blackholes" to metrics.blackholes, "directCacheMisses" to metrics.directCacheMisses,
        "indirectCalls" to metrics.indirectCalls, "tailBounces" to metrics.tailBounces, "papAllocations" to metrics.papAllocations,
        "localJoinTransfers" to metrics.localJoinTransfers,
        "selfTailReentries" to metrics.selfTailReentries, "trampolineIterations" to metrics.trampolineIterations,
        "unsupportedPolicy" to (if (diagnosticUnsupported) "diagnostic-traps" else "reject-at-load"),
        "deferredUnsupported" to deferredUnsupported.toList(), "unsupportedTraps" to metrics.unsupportedTraps,
        "frames" to "indexed primitive slots; selective StaticShape captures",
        "stackPolicy" to "tail-safe; non-tail calls and nested thunk forcing use host stack", "threadPolicy" to "single guest thread")
    private fun representation(binding: Map<String, Any?>): Boolean = binding["lifted"] as? Boolean
        ?: throw UnsupportedCore("Unknown levity for ${binding["id"]}")
    private fun freeVariables(expr: List<Any?>): Set<String> = when (expr[0]) {
        "var" -> setOf(expr[1] as String)
        "lam" -> freeVariables(expr[2] as List<Any?>) - (expr[1] as List<Map<String, Any?>>).map { it["id"] as String }.toSet()
        "app" -> freeVariables(expr[1] as List<Any?>) + (expr[2] as List<List<Any?>>).flatMap { freeVariables(it) }
        "let" -> {
            val group = expr[2] as List<Map<String, Any?>>
            val ids = group.map { it["id"] as String }.toSet()
            val rhs = group.flatMap { freeVariables(it["expr"] as List<Any?>) }.toSet()
            (if (expr[1] == true) rhs - ids else rhs) + (freeVariables(expr[3] as List<Any?>) - ids)
        }
        "case" -> freeVariables(expr[1] as List<Any?>) + (expr[3] as List<List<Any?>>).flatMap {
            freeVariables(it[3] as List<Any?>) - (it[2] as List<String>).toSet() - (expr[2] as String)
        }
        else -> emptySet()
    }
    private fun function(label: String, args: List<Map<String, Any?>>, expression: List<Any?>, outer: Scope,
                         resultProof: CoreRepresentation = CoreRepresentations.expression(expression),
                         entryStrict: BooleanArray = BooleanArray(args.size)): FunctionSpec {
        CoreRepresentations.requireNoVector(resultProof, "function result")
        if (entryStrict.size != args.size) throw RuntimeFault("Function entry contract arity mismatch")
        val scope = Scope(FrameLayout())
        val free = freeVariables(expression)
        val argumentIds = args.map { it["id"] as String }.toSet()
        args.forEach { CoreRepresentations.requireScalar(CoreRepresentations.binder(it), "formal argument") }
        val freeLocals = (free - argumentIds).filter { it in outer.locals }
        freeLocals.filter { outer.locals.getValue(it).let { local -> local.slot < 0 && local.proof.kind == CoreKind.VOID } }
            .forEach { scope.bindVoid(it, outer.locals.getValue(it).proof) }
        val captured = freeLocals.filter { outer.locals.getValue(it).let { local -> local.slot >= 0 || local.proof.kind != CoreKind.VOID } }
        captured.forEach { CoreRepresentations.requireScalar(outer.locals.getValue(it).proof, "capture") }
        val captureSources = captured.map { outer.locals.getValue(it).slot }.toIntArray()
        val captureKinds = captured.map { outer.locals.getValue(it).primitive }.toBooleanArray()
        val environmentSlots = captured.map { id ->
            val local = outer.locals.getValue(id)
            scope.bind(id, local.primitive, local.proof, local.cell, local.entry).slot
        }.toIntArray()
        val argumentSlots = arrayListOf<Int>(); val argumentIndices = arrayListOf<Int>()
        val argumentProofs = arrayListOf<CoreRepresentation>()
        for ((index, arg) in args.withIndex()) {
            val lifted = representation(arg)
            val proof = CoreRepresentations.binder(arg).let { if (lifted) it.copy(evaluated = entryStrict[index]) else it }
            if (arg["id"] in free) {
                argumentIndices += index; argumentProofs += proof
                argumentSlots += scope.bind(arg["id"] as String, !lifted && arg["coercion"] != true, proof).slot
            }
        }
        val captures = if (captured.isEmpty()) null else CaptureLayout(requireNotNull(language), captureKinds,
            captured.map { outer.locals.getValue(it).let { local -> !local.cell && local.proof.isLong && local.proof.evaluated } }.toBooleanArray(),
            captured.map { outer.locals.getValue(it).let { local -> if (local.cell) null else local.proof.referenceCarrier() } }.toTypedArray(),
            captured.map { outer.locals.getValue(it).let { local -> !local.cell && local.proof.isFloat && local.proof.evaluated } }.toBooleanArray(),
            captured.map { outer.locals.getValue(it).let { local -> !local.cell && local.proof.isDouble && local.proof.evaluated } }.toBooleanArray())
        val allArgumentSlots = IntArray(args.size) { -1 }
        val allArgumentProofs = Array(args.size) { CoreRepresentation.UNKNOWN }
        argumentIndices.forEachIndexed { index, argument ->
            allArgumentSlots[argument] = argumentSlots[index]
            allArgumentProofs[argument] = argumentProofs[index]
        }
        scope.self = AstSelfLayout(captures, environmentSlots, allArgumentSlots, allArgumentProofs, entryStrict.copyOf())
        val body = compile(expression, scope, true)
        val handoff = HandoffEntry.create(language, scope.layout, args.map(CoreRepresentations::binder), resultProof, captures != null)
        val effectiveResult = body.representation.refine(resultProof)
        CoreRepresentations.requireNoVector(effectiveResult, "function result")
        val tuple = if (effectiveResult.isTuple) TupleShape(effectiveResult, language as thc.Language) else null
        val tupleSlots = IntArray(tuple?.width ?: 0) { scope.layout.bind("<tuple return $it>") }
        val root = FunctionRoot(language, scope.layout.build(), label, captures, environmentSlots,
            argumentSlots.toIntArray(), argumentIndices.toIntArray(), body, metrics, argumentProofs.toTypedArray(), resultProof,
            rootSource(body), entryStrict, handoff, tuple, tupleSlots)
        if (body is Case) root.configureLeadingCaseReturn(LeadingCaseReturn.discover(args, expression,
            resultProof, root.entryArgumentOffset, free.intersect(argumentIds), captures != null,
            ::dataLayout, sources, body.coreSourceLocation))
        return FunctionSpec(root.callTarget, captures, captureSources)
    }
    private fun delay(expr: List<Any?>, scope: Scope, label: String): Expr {
        val fn = function(label, emptyList(), expr, scope)
        return Delay(fn.target, fn.captureLayout, fn.captures).proven(CoreRepresentations.expression(expr).copy(evaluated = false))
            .located(sources.expression(expr, currentSource))
    }
    private fun argument(expr: List<Any?>, scope: Scope, lifted: Boolean, label: String = "argument thunk"): Expr {
        CoreRepresentations.requireScalar(CoreRepresentations.expression(expr), "argument")
        if (expr[0] == "var") scope.locals[expr[1]]?.let { CoreRepresentations.requireScalar(it.proof, "argument") }
        if (!lifted) return Evaluate(compile(expr, scope, false).also {
            CoreRepresentations.requireNoVector(it.representation, "argument")
        }, metrics)
        // GHC's context-aware exprOkForSpecEval certificate also covers total
        // primitive operands in constructors, without strictifying recursive
        // dictionary knots. Allocate these values directly instead of creating
        // an update thunk and captures. A false certificate overrides HNF.
        // Older exports fall back to exprIsHNF; missing proofs stay lazy.
        if (expr[0] == "app" && ((expr.getOrNull(5) as? Boolean) ?: (expr.getOrNull(4) == true)))
            return compile(expr, scope, false)
        return when (expr[0]) { "var", "lit", "lam", "con", "prim", "void" -> compile(expr, scope, false); else -> delay(expr, scope, label) }
    }
    private fun literal(kind: String, value: String): Any = when (kind) {
        "int64" -> int64Literal(value)
        "word64" -> word64Literal(value)
        "int", "char" -> value.toLong()
        "word" -> value.toULong().toLong()
        "float" -> value.toFloat()
        "double" -> value.toDouble()
        "word8", "word16", "word32" -> narrowWordLiteral(kind, value)
        "string-bytes" -> LiteralAddress.fromHex(value)
        else -> throw UnsupportedCore("Unsupported literal kind $kind")
    }
    private fun compile(expr: List<Any?>, scope: Scope, tail: Boolean): Expr =
        withSource(sources.expression(expr, currentSource)) { compileLocated(expr, scope, tail).located(currentSource) }
    private fun compileLocated(expr: List<Any?>, scope: Scope, tail: Boolean): Expr = try {
        val lowered = compileSupported(expr, scope, tail)
        val metadata = if (diagnosticUnsupported && lowered is GlobalRead) CoreRepresentation.UNKNOWN
            else CoreRepresentations.expression(expr)
        // GHC HNF can become a thunk in our lazy storage. Only retain evaluatedness
        // established by the lowered producer or its lexical storage contract.
        lowered.proven(lowered.representation.refine(metadata.copy(evaluated = false)))
    } catch (gap: UnsupportedCore) {
        if (!diagnosticUnsupported) throw gap
        val message = gap.message ?: "Unsupported Core"
        deferredUnsupported += message
        // An unavailable value stays lazy until demanded. This applies uniformly
        // to unknown globals/operations, without special-casing library names or
        // removing branches. The default mode still rejects the same Core.
        val body = UnsupportedExpression(message, metrics).located(currentSource)
        val target = FunctionRoot(language, FrameLayout().build(), "unsupported: $message", null,
            intArrayOf(), intArrayOf(), intArrayOf(), body, metrics, coreSourceLocation = rootSource(body)).callTarget
        Delay(target, null, intArrayOf())
    }
    private fun compileSupported(expr: List<Any?>, scope: Scope, tail: Boolean): Expr = when (expr[0]) {
        "var" -> {
            val id = expr[1] as String
            CoreVectors.requireVariableProof(scope.locals[id]?.proof ?: globalProofs[id], CoreRepresentations.expression(expr))
            scope.joins[id]?.let { joinJump(it, emptyList(), emptyList<Boolean>(), scope) }
                ?: scope.locals[id]?.let {
                    if (it.tupleSlots != null) TupleLocalRead(TupleShape(it.proof, language as thc.Language), it.tupleSlots)
                    else if (it.slot < 0 && it.proof.kind == CoreKind.VOID) Literal(Unit).proven(it.proof)
                    else LocalRead(it.slot, it.cell).proven(it.proof)
                }
                ?: globals[id]?.let { GlobalRead(it).proven(globalProofs.getValue(id)) }
                ?: throw UnsupportedCore("Unresolved external binding $id")
        }
        "lit" -> Literal(literal(expr[1] as String, expr[2] as String))
        "void" -> Literal(Unit)
        "lam" -> {
            val args = expr[1] as List<Map<String, Any?>>
            val fn = function("lambda ${args.joinToString { it["name"].toString() }}", args, expr[2] as List<Any?>, scope,
                CoreRepresentations.lambdaResult(expr), CoreEntries.lambda(expr))
            MakeClosure(fn.target, args.size, fn.captureLayout, fn.captures)
        }
        "app" -> {
            val fn = expr[1] as List<Any?>; val args = expr[2] as List<List<Any?>>
            val flags = expr.getOrNull(3) as? List<*> ?: throw RuntimeFault("Application lacks representation flags")
            if (flags.size != args.size) throw RuntimeFault("Application representation flag count mismatch")
            val callStrict = CoreCallDemands.lowerApplication(expr, callDemandsEnabled)
            val tupleProof = CoreRepresentations.expression(expr)
            val tupleOperation = if (fn[0] == "prim") TupleArithmeticOp.named(fn[1] as String) else null
            if (fn[0] == "prim" && fn[1] in CoreVectors.operations) {
                val name = fn[1] as String
                CoreVectors.validate(name, args.map(CoreRepresentations::expression), tupleProof)
                val operands = args.map { compile(it, scope, false) }.toTypedArray()
                when (name) {
                    "packInt64X2#" -> VectorPack(operands[0], IntArray(2) { scope.layout.bind("<vector lane $it>") })
                    "unpackInt64X2#" -> VectorUnpack(operands[0])
                    "packInt32X4#" -> Vector32Pack(operands[0], IntArray(4) { scope.layout.bind("<vector lane $it>") })
                    "unpackInt32X4#" -> Vector32Unpack(operands[0])
                    in CoreVectors.operations32 -> Vector32Operation(name, operands)
                    else -> VectorOperation(name, operands)
                }
            } else if (tupleOperation != null) {
                tupleOperation.validate(args.map(CoreRepresentations::expression), flags, tupleProof)
                TupleArithmeticExpression(tupleOperation, tupleProof,
                    argument(args[0], scope, false), argument(args[1], scope, false))
            } else if (tupleProof.isTuple && fn[0] == "con" && constructors[fn[1]]?.get("kind") == "unboxed-tuple") {
                val shape = TupleShape(tupleProof, language as thc.Language)
                if (shape.components.size != args.size || (fn[2] as Number).toInt() != args.size ||
                    (constructors[fn[1]]?.get("arity") as? Number)?.toInt() != args.size)
                    throw RuntimeFault("Tuple constructor arity mismatch")
                TupleConstruct(shape, args.mapIndexed { index, arg ->
                    TupleShape.requireCompatible(shape.components[index], CoreRepresentations.expression(arg), component = true)
                    if (shape.components[index].isTuple) compile(arg, scope, false)
                    else argument(arg, scope, flags[index] as? Boolean ?: throw UnsupportedCore("Unknown tuple field levity"))
                }.toTypedArray())
            } else if (fn[0] == "var" && fn[1] in scope.joins) {
                joinJump(scope.joins.getValue(fn[1] as String), args, flags, scope, callStrict)
            } else {
            CoreRepresentations.requireNoVector(tupleProof, "call result")
            val constructorStrictFields = if (fn[0] == "con" && (fn[2] as Number).toInt() == args.size)
                strictConstructorFields(fn[1] as String, args.size) else null
            val entryStrict = when (fn[0]) {
                "lam" -> CoreEntries.lambda(fn)
                "var" -> (fn[1] as String).let { id -> if (id in scope.locals) scope.locals.getValue(id).entry else globalEntries[id] }
                else -> null
            }?.takeIf { args.size >= it.size }
            val nodes = args.mapIndexed { i, arg ->
                val lifted = flags[i] as? Boolean ?: throw UnsupportedCore("Unknown argument levity")
                // A saturated constructor's strict operand is already a CBV
                // context. Compile it directly, without an allocate/force thunk.
                // Partial constructors deliberately take the ordinary lazy path.
                argument(arg, scope, lifted && !callStrict[i] && constructorStrictFields?.get(i) != true && entryStrict?.getOrNull(i) != true)
            }.toTypedArray()
            when {
                fn[0] == "prim" -> {
                    ScalarPrimitiveSignatures.validate(fn[1] as String, nodes.map { it.representation }, tupleProof)
                    primitive(fn[1] as String, nodes)
                }
                constructorStrictFields != null -> Construct(dataLayout(fn[1] as String), nodes)
                else -> {
                    val function = compile(fn, scope, false)
                    if (tupleProof.isTuple) TupleApplication(language as thc.Language, TupleShape(tupleProof, language), function, nodes, tail, metrics)
                    else {
                    val self = scope.self
                    if (tail && self != null && self.arity > 0 && nodes.size <= self.arity) {
                        val temporaries = IntArray(self.arity) { scope.layout.bind("<self argument $it>") }
                        AstTailApplication(function, nodes, self, temporaries, metrics)
                    } else Application(function, nodes, tail, metrics)
                    }
                }
            }
            }
        }
        "let" -> {
            val recursive = expr[1] as Boolean; val group = expr[2] as List<Map<String, Any?>>
            val definitions = CoreJoins.definitions(group)
            if (definitions != null) compileJoins(expr, scope, tail, definitions) else {
                group.forEach { CoreRepresentations.requireScalar(CoreRepresentations.binder(it), "let binding") }
                val local = scope.child()
                val slots = group.map { local.bind(it["id"] as String, !representation(it),
                    CoreRepresentations.binder(it).copy(evaluated = false), cell = recursive, entry = CoreEntries.binding(it)).slot }.toIntArray()
                val rhs = group.map { binding -> withSource(sources.binding(binding, currentSource)) {
                    val it = binding
                    val rhsExpr = it["expr"] as List<Any?>; val lifted = representation(it)
                    if (recursive && !lifted) throw UnsupportedCore("Recursive unlifted binding unsupported")
                    val node = if (recursive && lifted && rhsExpr[0] !in listOf("lam", "lit", "con", "void")) delay(rhsExpr, local, it["name"].toString())
                    else argument(rhsExpr, if (recursive) local else scope, lifted, it["name"].toString())
                    node.proven(node.representation.refine(CoreRepresentations.binder(it).copy(evaluated = false)))
                } }.toTypedArray()
                // RHS closures retain their original cell-bearing Local records.
                // Only the body sees the values published after the entire group.
                group.forEachIndexed { index, binding -> local.publish(binding["id"] as String, rhs[index].representation) }
                Let(slots, rhs, group.map { !representation(it) }.toBooleanArray(),
                    compile(expr[3] as List<Any?>, local, tail), recursive)
            }
        }
        "case" -> {
            val scrutineeExpr = expr[1] as List<Any?>
            val scrutinee = compile(scrutineeExpr, scope, false)
            val local = scope.child()
            if (scrutineeExpr[0] == "var") {
                val id = scrutineeExpr[1] as String
                local.locals[id]?.let { local.refine(id, it.proof.copy(evaluated = true)) }
            }
            val binderProof = scrutinee.representation.refine(CoreRepresentations.caseBinder(expr).copy(evaluated = false)).copy(evaluated = true)
            if (binderProof.isTuple) compileTupleCase(expr, scrutinee, binderProof, local, tail) else {
            val binder = local.bind(expr[2] as String, !binderProof.present || binderProof.isLong, binderProof).slot
            val alternatives = (expr[3] as List<List<Any?>>).map { alt ->
                val child = local.child(); val kind = alt[0] as String
                val value = when (kind) {
                    "lit" -> (alt[1] as List<String>).let {
                        if (it[0] in setOf("float", "double")) throw UnsupportedCore("Floating literal alternatives are invalid GHC Core")
                        literal(it[0], it[1])
                    }
                    "data" -> dataLayout(alt[1] as String)
                    else -> alt[1]
                }
                val ids = alt[2] as List<String>
                val layout = value as? DataLayout
                if (layout != null && layout.arity != ids.size) throw RuntimeFault("Constructor field/binder mismatch")
                val metadata = CoreRepresentations.alternativeBinders(alt)
                val strict = if (kind == "data") strictConstructorFields(alt[1] as String, ids.size) else null
                val slots = ids.mapIndexed { index, id ->
                    val raw = metadata.getOrNull(index)?.let(CoreRepresentations::binder) ?: CoreRepresentation.UNKNOWN
                    val proof = if (layout?.isLong(index) == true) raw.refine(CoreRepresentation(CoreKind.LONG, true))
                        else raw.copy(evaluated = strict?.get(index) == true ||
                            ((constructors[alt[1] as? String]?.get("fieldLifted") as? List<*>)?.getOrNull(index) == false))
                    child.bind(id, layout?.isLong(index) == true, proof).slot
                }.toIntArray()
                val tag = when (kind) {
                    "default" -> DEFAULT_ALTERNATIVE
                    "data" -> DATA_ALTERNATIVE
                    "lit" -> LITERAL_ALTERNATIVE
                    else -> throw RuntimeFault("Invalid Core alternative kind $kind")
                }
                Alternative(tag, value, slots, compile(alt[3] as List<Any?>, child, tail))
            }.toTypedArray()
            CoreRepresentations.validateFloatingCaseResult(CoreRepresentations.expression(expr),
                alternatives.map { it.body.representation })
            when (caseCategory(binderProof, alternatives.map { it.kind },
                alternatives.all { it.kind != LITERAL_ALTERNATIVE || it.value is Long })) {
                CaseCategory.DATA -> DataCase(scrutinee, binder, alternatives, metrics, binderProof)
                CaseCategory.LONG -> LongCase(scrutinee, binder, alternatives, metrics, binderProof)
                CaseCategory.DEFAULT_ONLY -> DefaultCase(scrutinee, binder, alternatives, metrics, binderProof)
                CaseCategory.GENERIC -> Case(scrutinee, binder, alternatives, metrics)
            }
            }
        }
        "con" -> {
            val id = expr[1] as String; val arity = (expr[2] as Number).toInt()
            if (constructors[id]?.get("kind") == "unboxed-tuple" && arity == 0 && CoreRepresentations.expression(expr).isTuple) {
                val proof = CoreRepresentations.expression(expr)
                if (proof.components?.size != 0) throw RuntimeFault("Empty tuple constructor has nonempty logical components")
                TupleConstruct(TupleShape(proof, language as thc.Language), emptyArray())
            } else if (arity == 0) construct(id, emptyArray()) else {
                val layout = FrameLayout(); val slots = IntArray(arity) { layout.bind("field$it") }
                val body = construct(id, Array(arity) { LocalRead(slots[it], cell = false) })
                val target = FunctionRoot(language, layout.build(), "constructor $id", null, intArrayOf(), slots, IntArray(arity) { it }, body, metrics,
                    coreSourceLocation = rootSource(body)).callTarget
                MakeClosure(target, arity, null, intArrayOf())
            }
        }
        "prim" -> throw UnsupportedCore("Unsaturated primitive ${expr[1]}")
        else -> throw UnsupportedCore("Unsupported Core node ${expr[0]}")
    }
    private fun compileTupleCase(expr: List<Any?>, scrutinee: Expr, proof: CoreRepresentation, local: Scope, tail: Boolean): Expr {
        val shape = TupleShape(proof, language as thc.Language)
        val slots = IntArray(shape.width) { local.layout.bind("<tuple case $it>") }
        local.bindTuple(expr[2] as String, proof, slots)
        val alternatives = expr[3] as List<List<Any?>>
        if (alternatives.size != 1) throw RuntimeFault("Tuple case requires one alternative")
        val alt = alternatives.single()
        val ids = alt[2] as List<String>
        if (alt[0] == "data") {
            if (constructors[alt[1]]?.get("kind") != "unboxed-tuple" || ids.size != shape.components.size ||
                (constructors[alt[1]]?.get("arity") as? Number)?.toInt() != ids.size)
                throw RuntimeFault("Tuple alternative shape mismatch")
            val metadata = CoreRepresentations.alternativeBinders(alt)
            ids.forEachIndexed { index, id ->
                val component = shape.components[index]
                val raw = metadata.getOrNull(index)?.let(CoreRepresentations::binder) ?: component
                TupleShape.requireCompatible(component, raw, component = true)
                val field = component.refine(raw)
                val width = TupleShape.flatten(component).size
                val offset = shape.offsets[index]
                if (component.isTuple) local.bindTuple(id, component.copy(evaluated = true), slots.copyOfRange(offset, offset + width))
                else if (component.kind == CoreKind.VOID) local.bindVoid(id, field)
                else local.locals[id] = Local(slots[offset], component.isLong, field.copy(evaluated = component.isLong || component.evaluated), false)
            }
        } else if (alt[0] != "default" || ids.isNotEmpty()) throw RuntimeFault("Invalid tuple alternative")
        return TupleCase(scrutinee, slots, compile(alt[3] as List<Any?>, local, tail))
    }
    private fun joinJump(target: LocalJoinTarget, args: List<List<Any?>>, flags: List<*>, scope: Scope,
                         callStrict: BooleanArray = BooleanArray(args.size)): Expr {
        if (args.size != target.slots.size) throw RuntimeFault("Local join arity mismatch")
        val nodes = args.mapIndexed { index, arg ->
            val lifted = flags.getOrNull(index) as? Boolean ?: throw RuntimeFault("Missing join argument levity")
            argument(arg, scope, lifted && !callStrict[index] && !target.entryStrict[index])
        }.toTypedArray()
        val temps = IntArray(nodes.size) { scope.layout.bind("<join argument $it>") }
        return LocalJoinCall(target, nodes, temps, metrics)
    }
    private fun compileJoins(expr: List<Any?>, outer: Scope, tail: Boolean,
                             definitions: List<CoreJoinDefinition>): Expr {
        val recursive = expr[1] == true
        val shadowed = if (recursive) definitions.map { it.id }.toSet() else emptySet()
        definitions.forEach { definition ->
            definition.parameters.forEach { CoreRepresentations.requireScalar(CoreRepresentations.binder(it), "join argument") }
            val formals = definition.parameters.map { it["id"] as String }.toSet()
            (freeVariables(definition.body) - formals - shadowed).forEach { id ->
                outer.locals[id]?.let { CoreRepresentations.requireScalar(it.proof, "join capture") }
            }
        }
        CoreJoins.validate(expr[2] as List<Map<String, Any?>>, expr[3] as List<Any?>, recursive)
        val identity = Any()
        val local = outer.child()
        val entryContracts = definitions.map(CoreEntries::join)
        // Snapshot the outer join scope before publishing this group. Besides
        // lexical correctness, this lets nonrecursive regions dispatch once.
        val bodyScopes = definitions.map { definition ->
            val scope = local.child()
            val entryStrict = CoreEntries.join(definition)
            definition.parameters.forEachIndexed { index, parameter ->
                val lifted = representation(parameter)
                val proof = CoreRepresentations.binder(parameter).let { if (lifted) it.copy(evaluated = entryStrict[index]) else it }
                scope.bind(parameter["id"] as String, !lifted && parameter["coercion"] != true, proof)
            }
            scope
        }
        val targets = definitions.mapIndexed { index, definition ->
            val parameters = definition.parameters.map { bodyScopes[index].locals.getValue(it["id"] as String) }
            LocalJoinTarget(identity, index + 1, parameters.map { it.slot }.toIntArray(),
                parameters.map { it.proof }.toTypedArray(), entryContracts[index], definition.result)
        }
        definitions.forEachIndexed { index, definition -> local.bindJoin(definition.id, targets[index]) }
        if (recursive) bodyScopes.forEachIndexed { index, scope ->
            val parameters = definitions[index].parameters.map { it["id"] as String }.toSet()
            definitions.forEachIndexed { targetIndex, definition ->
                if (definition.id !in parameters) scope.bindJoin(definition.id, targets[targetIndex])
            }
        }
        val entry = compile(expr[3] as List<Any?>, local, tail)
        CoreRepresentations.requireNoVector(entry.representation, "join result")
        val bodies = definitions.mapIndexed { index, definition ->
            withSource(sources.binding(definition.binding, currentSource)) {
                compile(definition.body, bodyScopes[index], tail).also { node ->
                    CoreRepresentations.requireNoVector(node.representation, "join result")
                    node.representation = node.representation.refine(definition.result.copy(evaluated = false))
                }
            }
        }
        val result = CoreRepresentations.expression(expr).let { proof ->
            val inferred = entry.representation.refine(proof.copy(evaluated = false))
            bodies.forEach { TupleShape.requireCompatible(inferred, it.representation) }
            inferred.copy(evaluated = entry.representation.evaluated && bodies.all { it.representation.evaluated })
        }
        val tuple = if (result.isTuple) TupleShape(result, language as thc.Language) else null
        val tupleSlots = IntArray(tuple?.width ?: 0) { local.layout.bind("<join tuple result $it>") }
        return LocalJoinRegion(identity, local.layout.bind("<join selector>"), local.layout.bind("<join result>"),
            (listOf(entry) + bodies).toTypedArray(), result, recursive, tuple, tupleSlots)
    }
    private fun dataLayout(id: String): DataLayout = dataLayouts.getOrPut(id) {
        val info = constructors[id] ?: throw RuntimeFault("Missing constructor metadata $id")
        val fields = CoreFields(info)
        DataLayout(language ?: throw RuntimeFault("Constructor layout requires a guest language"), id, info["name"] as String, fields.storage, fields.referenceTypes)
    }
    private fun primitive(name: String, args: Array<Expr>): Expr = floatingPrimitive(name, args) ?: when (name) {
        "reallyUnsafePtrEquality#" -> {
            if (args.size != 2) throw RuntimeFault("Primitive arity mismatch: $name")
            PointerEquality(args[0], args[1])
        }
        "raise#" -> {
            if (args.size != 1) throw RuntimeFault("Primitive arity mismatch: $name")
            RaiseException(args[0])
        }
        "plusAddr#", "indexCharOffAddr#" -> {
            if (args.size != 2) throw RuntimeFault("Primitive arity mismatch: $name")
            if (name == "plusAddr#") PlusLiteralAddress(args[0], args[1]) else IndexLiteralChar(args[0], args[1])
        }
        else -> Primitive(name, args)
    }
    private fun strictConstructorFields(id: String, arity: Int): BooleanArray {
        val info = constructors[id] ?: throw RuntimeFault("Missing constructor metadata $id")
        if ((info["kind"] ?: "boxed") != "boxed") throw UnsupportedCore("Unsupported constructor representation ${info["kind"]}: $id")
        if ((info["arity"] as Number).toInt() != arity) throw RuntimeFault("Constructor arity mismatch: $id")
        val strict = info["strictFields"] as? List<*> ?: throw RuntimeFault("Missing constructor strictness metadata: $id")
        val lifted = info["fieldLifted"] as? List<*> ?: throw RuntimeFault("Missing constructor representation metadata: $id")
        if (strict.size != arity || lifted.size != arity) throw RuntimeFault("Constructor metadata length mismatch: $id")
        return BooleanArray(arity) { i ->
            val strictField = strict[i] as? Boolean ?: throw RuntimeFault("Unknown constructor field strictness: $id")
            strictField && (lifted[i] as? Boolean
                ?: throw UnsupportedCore("Unknown strict constructor field levity: $id field $i"))
        }
    }
    private fun construct(id: String, args: Array<Expr>): Expr {
        val strict = strictConstructorFields(id, args.size)
        val fields = Array(args.size) { i ->
            // Constructor workers carry CBV obligations independently of argument
            // levity (CorePrep, Note [Pin evaluatedness on floats]). This body runs
            // only at saturation, including entry through a constructor closure/PAP.
            if (strict[i]) Evaluate(args[i], metrics) else args[i]
        }
        return Construct(dataLayout(id), fields)
    }
}

/** Explicit development mode only; execution never fabricates a guest result. */
private class UnsupportedExpression(private val message: String, private val metrics: Metrics) : Expr() {
    init { representation = CoreRepresentation(CoreKind.UNKNOWN, evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing {
        CompilerDirectives.transferToInterpreterAndInvalidate()
        metrics.unsupportedTraps++
        throw RuntimeFault("Diagnostic unsupported path reached: $message")
    }
}
