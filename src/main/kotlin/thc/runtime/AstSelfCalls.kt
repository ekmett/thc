package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ControlFlowException
import com.oracle.truffle.api.nodes.ExplodeLoop
import com.oracle.truffle.api.nodes.Node

/** A self transfer has already installed its next frame; it needs no packet or target. */
internal object AstSelfCall : ControlFlowException()

/** The carrier is fixed during lowering; Class.cast alone would also accept null. */
internal fun requireReferenceCarrier(value: Any?, carrier: Class<*>): Any? {
    if (!carrier.isInstance(value)) fault("Expected proven reference value")
    return carrier.cast(value)
}

/** Immutable frame destinations shared by a root's lexical scopes and cloned nodes. */
internal class AstSelfLayout(
    private val captureLayout: CaptureLayout?,
    @field:CompilationFinal(dimensions = 1) private val environmentSlots: IntArray,
    @field:CompilationFinal(dimensions = 1) private val argumentSlots: IntArray,
    @field:CompilationFinal(dimensions = 1) private val argumentProofs: Array<CoreRepresentation>,
    @field:CompilationFinal(dimensions = 1) val entryStrict: BooleanArray
) {
    val arity: Int get() = argumentSlots.size
    @field:CompilationFinal(dimensions = 1)
    private val argumentReferences = argumentProofs.map { it.referenceCarrier() }.toTypedArray()

    @ExplodeLoop fun transfer(frame: VirtualFrame, function: Closure, temporaries: IntArray): Nothing {
        // Temporaries never alias lexical slots, so capture restoration cannot
        // change a pending operand, even when the callee has a new environment.
        if (captureLayout != null) {
            val environment = function.environment ?: fault("Invalid captured frame")
            for (i in environmentSlots.indices) captureLayout.restore(environment, i, frame, environmentSlots[i])
        }
        for (i in argumentSlots.indices) {
            val destination = argumentSlots[i]
            if (destination < 0) continue
            val source = temporaries[i]
            val reference = argumentReferences[i]
            if (reference != null) FrameAccess.write(frame, destination,
                requireReferenceCarrier(FrameAccess.read(frame, source), reference))
            else if (frame.isLong(source)) FrameAccess.writeLong(frame, destination, frame.getLong(source))
            else if (argumentProofs[i].isLong) {
                val value = FrameAccess.read(frame, source) as? Long ?: fault("Expected primitive Long argument")
                FrameAccess.writeLong(frame, destination, value)
            } else FrameAccess.write(frame, destination, FrameAccess.read(frame, source))
        }
        // A different tail site may run next, so even unused operands must not
        // retain their previous values for the lifetime of this activation.
        for (source in temporaries) frame.clear(source)
        throw AstSelfCall
    }
}

/** Clones preserve GuestRoot.bodyIdentity, including cached positive/negative answers. */
private class AstSelfTarget : Node() {
    private class CachedTarget(val target: RootCallTarget, val matches: Boolean)
    @field:CompilationFinal(dimensions = 1) private var cached = emptyArray<CachedTarget>()

    @ExplodeLoop fun matches(target: RootCallTarget): Boolean {
        for (entry in cached) if (entry.target === target) return entry.matches
        val result = (rootNode as GuestRoot).isSelf(target)
        if (cached.size < 3) {
            CompilerDirectives.transferToInterpreterAndInvalidate()
            // Clones may share the old immutable array. Never mutate their cache.
            cached = cached + CachedTarget(target, result)
        }
        return result
    }
}

/** Exact self entry uses primitive frame moves; all other calls retain normal Dispatch. */
internal class AstTailApplication(function: Expr, arguments: Array<Expr>,
    private val layout: AstSelfLayout,
    @field:CompilationFinal(dimensions = 1) private val temporaries: IntArray,
    metrics: Metrics
) : Expr() {
    init { representation = CoreRepresentation(CoreKind.UNKNOWN, evaluated = true) }
    private val suppliedCount = layout.arity - arguments.size
    @Child private var function = Evaluate(function, metrics)
    @Children private var operands = Array(arguments.size) { index ->
        LocalBinding(temporaries[index + suppliedCount], arguments[index], preferLong = true)
    }
    @Child private var selfTarget = AstSelfTarget()
    @Child private var dispatch = Dispatch.create(arguments.size, true, metrics,
        arguments.map { it.representation.evaluated }.toBooleanArray())
    @field:CompilationFinal(dimensions = 1)
    private val strictPositions = layout.entryStrict.indices.filter { index ->
        layout.entryStrict[index] && (index < suppliedCount || !arguments[index - suppliedCount].representation.evaluated)
    }.toIntArray()
    @Children private var forces = Array(strictPositions.size) { Force(metrics) }

    @ExplodeLoop override fun execute(frame: VirtualFrame): Any? {
        val fn = function.executeRequiredClosure(frame)
        if (fn.arity == operands.size && fn.supplied.size == suppliedCount && selfTarget.matches(fn.target)) {
            // Save all operands before enforcing CBV or replacing any lexical slot.
            for (operand in operands) operand.write(frame)
            for (index in 0 until suppliedCount) FrameAccess.write(frame, temporaries[index], fn.supplied[index])
            // Unused formals and PAP prefixes still participate in the contract.
            for (index in strictPositions.indices) {
                val slot = temporaries[strictPositions[index]]
                FrameAccess.write(frame, slot, forces[index].execute(frame, FrameAccess.read(frame, slot)))
            }
            layout.transfer(frame, fn, temporaries)
        }
        val values = arrayOfNulls<Any>(operands.size)
        for (index in operands.indices) values[index] = operands[index].evaluate(frame)
        return dispatch.execute(frame, fn, values)
    }
}
