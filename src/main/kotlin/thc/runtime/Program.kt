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
    abstract fun execute(frame: VirtualFrame): Any?
    @Throws(UnexpectedResultException::class)
    open fun executeLong(frame: VirtualFrame): Long {
        return RuntimeTypesGen.expectLong(execute(frame))
    }

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
        is Long -> CoreKind.LONG; is LiteralAddress -> CoreKind.ADDRESS; Unit -> CoreKind.VOID; else -> CoreKind.OBJECT
    }, evaluated = true) }
    override fun execute(frame: VirtualFrame) = value
    override fun executeLong(frame: VirtualFrame) = RuntimeTypesGen.expectLong(value)
}
internal class LocalRead(private val slot: Int, private val cell: Boolean = true) : Expr() {
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
    @Child private var force = Force(metrics)
    private fun forceResult(frame: VirtualFrame, original: Any?): Any? {
        val result = force.execute(frame, original)
        // WHNF reads need no write. A failed force never reaches this point.
        if (original is Thunk && value is LocalRead) (value as LocalRead).writeForced(frame, original, result)
        return result
    }
    override fun execute(frame: VirtualFrame): Any? = when {
        value.representation.isLong -> value.executeRequiredLong(frame)
        value.representation.evaluated -> value.execute(frame)
        else -> forceResult(frame, value.execute(frame))
    }
    @CompilationFinal private var genericLong = false
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
        representation = CoreRepresentation(kind, proofs.all { it.evaluated }, proofs.isNotEmpty() && proofs.all { it.present })
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
            else layout.initialize(value, i, fields[i].execute(frame))
        }
        return value
    }
    override fun executeDataValue(frame: VirtualFrame): DataValue = execute(frame)
}
private class Primitive(private val name: String, @field:Children private var arguments: Array<Expr>) : Expr() {
    init {
        representation = CoreRepresentation(CoreKind.LONG, evaluated = true)
        val arity = when (name) {
            "negateInt#", "not#", "notI#", "int2Word#", "word2Int#", "ord#", "chr#",
            "narrow8Int#", "narrow16Int#", "narrow32Int#" -> 1
            "+#", "plusWord#", "-#", "minusWord#", "*#", "timesWord#", "quotInt#", "remInt#",
            "==#", "eqWord#", "eqChar#", "/=#", "neWord#", "neChar#", "<#", "ltChar#", "<=#", "leChar#",
            ">#", "gtChar#", ">=#", "geChar#", "and#", "andI#", "or#", "orI#", "xor#", "xorI#",
            "uncheckedIShiftL#", "uncheckedShiftL#", "uncheckedIShiftRA#", "uncheckedIShiftRL#", "uncheckedShiftRL#" -> 2
            else -> throw UnsupportedCore("Unsupported primitive $name")
        }
        if (arguments.size != arity) throw RuntimeFault("Primitive arity mismatch: $name")
    }
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long {
        val x = arguments[0].executeRequiredLong(frame)
        val y = if (arguments.size == 2) arguments[1].executeRequiredLong(frame) else 0L
        fun b(value: Boolean) = if (value) 1L else 0L
        return when (name) {
            "+#", "plusWord#" -> x + y
            "-#", "minusWord#" -> x - y
            "*#", "timesWord#" -> x * y
            "negateInt#" -> -x
            "quotInt#" -> x / y
            "remInt#" -> x % y
            "==#", "eqWord#", "eqChar#" -> b(x == y)
            "/=#", "neWord#", "neChar#" -> b(x != y)
            "<#", "ltChar#" -> b(x < y)
            "<=#", "leChar#" -> b(x <= y)
            ">#", "gtChar#" -> b(x > y)
            ">=#", "geChar#" -> b(x >= y)
            "and#", "andI#" -> x and y
            "or#", "orI#" -> x or y
            "xor#", "xorI#" -> x xor y
            "not#", "notI#" -> x.inv()
            "uncheckedIShiftL#", "uncheckedShiftL#" -> x shl y.toInt()
            "uncheckedIShiftRA#" -> x shr y.toInt()
            "uncheckedIShiftRL#", "uncheckedShiftRL#" -> x ushr y.toInt()
            "narrow8Int#" -> x.toByte().toLong()
            "narrow16Int#" -> x.toShort().toLong()
            "narrow32Int#" -> x.toInt().toLong()
            "int2Word#", "word2Int#", "ord#", "chr#" -> x
            else -> fault("Unsupported primitive")
        }
    }
}
private class FunctionBody(expression: Expr, metrics: Metrics, result: CoreRepresentation) : Node() {
    @Child private var value = Evaluate(expression, metrics)
    private val resultKind = if (result.kind == CoreKind.UNKNOWN) expression.representation.kind else result.kind
    private val exactLong = resultKind == CoreKind.LONG
    @CompilationFinal private var genericResult = result.present && !exactLong

    /** Keep a primitive body until the mandatory Object-returning root/call boundary. */
    fun execute(frame: VirtualFrame): Any? {
        // Kotlin's enum when uses a mutable synthetic int[] mapping. Graal
        // cannot fold that lookup, even when this node's resultKind is constant.
        if (resultKind == CoreKind.LONG) return value.executeRequiredLong(frame)
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
}
private class SelfRepeater(@field:Child private var body: FunctionBody, private val metrics: Metrics) : Node(), RepeatingNode {
    fun once(frame: VirtualFrame): Any? = body.execute(frame)
    override fun executeRepeating(frame: VirtualFrame): Boolean = error("value loop")
    override fun executeRepeatingWithValue(frame: VirtualFrame): Any? = try { once(frame) }
    catch (_: AstSelfCall) {
        if (metrics.enabled) metrics.selfTailReentries++
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
                            entryStrict: BooleanArray = booleanArrayOf()) : GuestRoot(language, descriptor) {
    init { configureEntry(entryStrict, captureLayout != null) }
    @field:CompilationFinal(dimensions = 1)
    private val argumentReferences = argumentProofs.map { it.referenceCarrier() }.toTypedArray()
    @field:CompilationFinal private var hasSelfTail = false
    private val tailCallProfile = BranchProfile.create()
    @Child private var loop: LoopNode = Truffle.getRuntime().createLoopNode(SelfRepeater(FunctionBody(body, metrics, resultProof), metrics))
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
            else FrameAccess.write(frame, argumentSlots[i], value)
        }
        if (captureLayout != null) {
            val environment = arguments[1] as? CapturedFrame ?: fault("Invalid captured frame")
            for (i in environmentSlots.indices) captureLayout.restore(environment, i, frame, environmentSlots[i])
        }
    }
    override fun execute(frame: VirtualFrame): Any? {
        if (metrics.enabled && CompilerDirectives.inCompiledCode()) metrics.compiledEntries++
        frame.setLong(FrameLayout.BLOOM_FILTER, (frame.arguments[0] as? Long ?: fault("Invalid bloom argument")) or mask)
        buildFrame(frame.arguments, frame)
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
                         val entry: BooleanArray? = null)
private class Scope(val layout: FrameLayout, val locals: MutableMap<String, Local> = linkedMapOf(),
                    val joins: MutableMap<String, LocalJoinTarget> = linkedMapOf(),
                    var self: AstSelfLayout? = null) {
    fun child() = Scope(layout.scope(), LinkedHashMap(locals), LinkedHashMap(joins), self)
    fun bind(id: String, primitive: Boolean, proof: CoreRepresentation = CoreRepresentation.UNKNOWN, cell: Boolean = false,
             entry: BooleanArray? = null): Local =
        Local(layout.bind(id), if (proof.present) proof.isLong else primitive, proof, cell, entry).also { locals[id] = it; joins.remove(id) }
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
        if (entryStrict.size != args.size) throw RuntimeFault("Function entry contract arity mismatch")
        val scope = Scope(FrameLayout())
        val free = freeVariables(expression)
        val argumentIds = args.map { it["id"] as String }.toSet()
        val captured = (free - argumentIds).filter { it in outer.locals }
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
            if (arg["id"] in free) {
                val proof = CoreRepresentations.binder(arg).let { if (lifted) it.copy(evaluated = entryStrict[index]) else it }
                argumentIndices += index; argumentProofs += proof
                argumentSlots += scope.bind(arg["id"] as String, !lifted && arg["coercion"] != true, proof).slot
            }
        }
        val captures = if (captured.isEmpty()) null else CaptureLayout(requireNotNull(language), captureKinds,
            captured.map { outer.locals.getValue(it).let { local -> !local.cell && local.proof.isLong && local.proof.evaluated } }.toBooleanArray(),
            captured.map { outer.locals.getValue(it).let { local -> if (local.cell) null else local.proof.referenceCarrier() } }.toTypedArray())
        val allArgumentSlots = IntArray(args.size) { -1 }
        val allArgumentProofs = Array(args.size) { CoreRepresentation.UNKNOWN }
        argumentIndices.forEachIndexed { index, argument ->
            allArgumentSlots[argument] = argumentSlots[index]
            allArgumentProofs[argument] = argumentProofs[index]
        }
        scope.self = AstSelfLayout(captures, environmentSlots, allArgumentSlots, allArgumentProofs, entryStrict.copyOf())
        val body = compile(expression, scope, true)
        val root = FunctionRoot(language, scope.layout.build(), label, captures, environmentSlots,
            argumentSlots.toIntArray(), argumentIndices.toIntArray(), body, metrics, argumentProofs.toTypedArray(), resultProof,
            rootSource(body), entryStrict)
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
        if (!lifted) return Evaluate(compile(expr, scope, false), metrics)
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
        "int", "char" -> value.toLong()
        "word" -> value.toULong().toLong()
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
            scope.joins[id]?.let { joinJump(it, emptyList(), emptyList<Boolean>(), scope) }
                ?: scope.locals[id]?.let { LocalRead(it.slot, it.cell).proven(it.proof) }
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
            if (fn[0] == "var" && fn[1] in scope.joins) {
                joinJump(scope.joins.getValue(fn[1] as String), args, flags, scope, callStrict)
            } else {
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
                fn[0] == "prim" -> primitive(fn[1] as String, nodes)
                constructorStrictFields != null -> Construct(dataLayout(fn[1] as String), nodes)
                else -> {
                    val function = compile(fn, scope, false)
                    val self = scope.self
                    if (tail && self != null && self.arity > 0 && nodes.size <= self.arity) {
                        val temporaries = IntArray(self.arity) { scope.layout.bind("<self argument $it>") }
                        AstTailApplication(function, nodes, self, temporaries, metrics)
                    } else Application(function, nodes, tail, metrics)
                }
            }
            }
        }
        "let" -> {
            val recursive = expr[1] as Boolean; val group = expr[2] as List<Map<String, Any?>>
            val definitions = CoreJoins.definitions(group)
            if (definitions != null) compileJoins(expr, scope, tail, definitions) else {
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
            val binder = local.bind(expr[2] as String, !binderProof.present || binderProof.isLong, binderProof).slot
            val alternatives = (expr[3] as List<List<Any?>>).map { alt ->
                val child = local.child(); val kind = alt[0] as String
                val value = when (kind) {
                    "lit" -> (alt[1] as List<String>).let { literal(it[0], it[1]) }
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
            when (caseCategory(binderProof, alternatives.map { it.kind },
                alternatives.all { it.kind != LITERAL_ALTERNATIVE || it.value is Long })) {
                CaseCategory.DATA -> DataCase(scrutinee, binder, alternatives, metrics, binderProof)
                CaseCategory.LONG -> LongCase(scrutinee, binder, alternatives, metrics, binderProof)
                CaseCategory.DEFAULT_ONLY -> DefaultCase(scrutinee, binder, alternatives, metrics, binderProof)
                CaseCategory.GENERIC -> Case(scrutinee, binder, alternatives, metrics)
            }
        }
        "con" -> {
            val id = expr[1] as String; val arity = (expr[2] as Number).toInt()
            if (arity == 0) construct(id, emptyArray()) else {
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
        CoreJoins.validate(expr[2] as List<Map<String, Any?>>, expr[3] as List<Any?>, recursive)
        val identity = Any()
        val local = outer.child()
        val entryContracts = definitions.map(CoreEntries::join)
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
                parameters.map { it.proof }.toTypedArray(), entryContracts[index])
        }
        definitions.forEachIndexed { index, definition -> local.bindJoin(definition.id, targets[index]) }
        if (recursive) bodyScopes.forEachIndexed { index, scope ->
            val parameters = definitions[index].parameters.map { it["id"] as String }.toSet()
            definitions.forEachIndexed { targetIndex, definition ->
                if (definition.id !in parameters) scope.bindJoin(definition.id, targets[targetIndex])
            }
        }
        val entry = compile(expr[3] as List<Any?>, local, tail)
        val bodies = definitions.mapIndexed { index, definition ->
            withSource(sources.binding(definition.binding, currentSource)) {
                compile(definition.body, bodyScopes[index], tail).also { node ->
                    node.representation = node.representation.refine(definition.result.copy(evaluated = false))
                }
            }
        }
        val result = CoreRepresentations.expression(expr).let { proof ->
            val inferred = entry.representation.refine(proof.copy(evaluated = false))
            inferred.copy(evaluated = entry.representation.evaluated && bodies.all { it.representation.evaluated })
        }
        return LocalJoinRegion(identity, local.layout.bind("<join selector>"), local.layout.bind("<join result>"),
            (listOf(entry) + bodies).toTypedArray(), result)
    }
    private fun dataLayout(id: String): DataLayout = dataLayouts.getOrPut(id) {
        val info = constructors[id] ?: throw RuntimeFault("Missing constructor metadata $id")
        val fields = CoreFields(info)
        DataLayout(language ?: throw RuntimeFault("Constructor layout requires a guest language"), id, info["name"] as String, fields.storage, fields.referenceTypes)
    }
    private fun primitive(name: String, args: Array<Expr>): Expr = when (name) {
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
