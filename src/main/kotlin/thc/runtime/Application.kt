package thc.runtime

import com.oracle.truffle.api.CallTarget
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.dsl.Bind
import com.oracle.truffle.api.dsl.Cached
import com.oracle.truffle.api.dsl.GenerateInline
import com.oracle.truffle.api.dsl.GenerateUncached
import com.oracle.truffle.api.dsl.ReportPolymorphism
import com.oracle.truffle.api.dsl.Specialization
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ControlFlowException
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.IndirectCallNode
import com.oracle.truffle.api.nodes.LoopNode
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.RepeatingNode
import com.oracle.truffle.api.profiles.BranchProfile
import com.oracle.truffle.api.profiles.InlinedConditionProfile

/* Adapted from Cadenza's dispatch.kt, tail_calls.kt and data/Closure.kt.
 * Keep the actual DSL specialization structure, closure/PAP convention and
 * tail trampoline; Haskell adds forcing of a delayed result before overapply.
 * The original copyright/license is retained in LICENSE.txt and NOTICE.md.
 */

/** Immutable zero-prefix shared by closed and freshly captured functions. */
internal val NO_PAP_ARGUMENTS: Array<Any?> = emptyArray()

@CompilerDirectives.ValueType
internal class Closure(
    @JvmField val environment: CapturedFrame?,
    @JvmField @CompilerDirectives.CompilationFinal(dimensions = 1) val supplied: Array<Any?> = NO_PAP_ARGUMENTS,
    /** Remaining value arguments, including any explicit zero-width slots. */
    @JvmField val arity: Int,
    @JvmField val target: RootCallTarget
) {
    init { require(arity >= 0) { "Negative closure arity" } }

    fun pap(arguments: Array<out Any?>): Closure = pap(arguments, 0, arguments.size)

    /** Escaping PAPs own their combined prefix; argument thunks stay unforced. */
    @CompilerDirectives.TruffleBoundary
    fun pap(arguments: Array<out Any?>, offset: Int, count: Int): Closure {
        require(offset >= 0 && count >= 0 && offset + count <= arguments.size && count < arity)
        val combined = arrayOfNulls<Any>(supplied.size + count)
        System.arraycopy(supplied, 0, combined, 0, supplied.size)
        System.arraycopy(arguments, offset, combined, supplied.size, count)
        return Closure(environment, combined, arity - count, target)
    }
}

/** Fully saturated call target cache: three direct nodes, then one indirect node. */
@ReportPolymorphism
@GenerateInline
@GenerateUncached
internal abstract class DispatchCallTarget : Node() {
    abstract fun execute(inliningTarget: Node, target: CallTarget, arguments: Array<Any?>, metrics: Metrics): Any?

    @Specialization(guards = ["target == cachedTarget"], limit = "3")
    fun direct(target: CallTarget, arguments: Array<Any?>, metrics: Metrics,
               @Cached("target") cachedTarget: CallTarget,
               @Cached("createDirect(cachedTarget, metrics)") call: DirectCallNode): Any? =
        Calls.direct(call, arguments)

    @Specialization(replaces = ["direct"])
    fun indirect(target: CallTarget, arguments: Array<Any?>, metrics: Metrics,
                 @Cached("create()") call: IndirectCallNode): Any? {
        if (metrics.enabled) metrics.indirectCalls++
        return Calls.indirect(call, target, arguments)
    }

    companion object {
        @JvmStatic fun createDirect(target: CallTarget, metrics: Metrics): DirectCallNode {
            if (metrics.enabled) metrics.directCacheMisses++
            return DirectCallNode.create(target)
        }
    }
}

/** The host bridge and tail loop use the same generated bounded target cache. */
internal class TargetCache(private val metrics: Metrics) : Node() {
    @Child private var dispatch: DispatchCallTarget = DispatchCallTargetNodeGen.create()
    fun call(target: RootCallTarget, arguments: Array<Any?>): Any? =
        dispatch.execute(this, target, arguments, metrics)
}

/**
 * Cache a thunk's target before building its call packet. Environment presence
 * is fixed by that target's capture layout. Merging one-slot and two-slot
 * arrays before the target cache prevents their scalar replacement even when
 * the target subsequently inlines.
 */
@ReportPolymorphism
@GenerateInline
@GenerateUncached
internal abstract class DispatchThunkTarget : Node() {
    abstract fun execute(inliningTarget: Node, target: RootCallTarget,
                         environment: CapturedFrame?, metrics: Metrics): Any?

    @Specialization(guards = ["target == cachedTarget"], limit = "3")
    fun direct(target: RootCallTarget, environment: CapturedFrame?, metrics: Metrics,
               @Cached("target") cachedTarget: RootCallTarget,
               @Cached("environment != null") hasEnvironment: Boolean,
               @Cached("createDirect(cachedTarget, metrics)") call: DirectCallNode): Any? =
        if (hasEnvironment) Calls.direct(call, arrayOf(0L, environment))
        else Calls.direct(call, arrayOf(0L))

    @Specialization(replaces = ["direct"])
    fun indirect(target: RootCallTarget, environment: CapturedFrame?, metrics: Metrics,
                 @Cached("create()") call: IndirectCallNode): Any? {
        if (metrics.enabled) metrics.indirectCalls++
        return if (environment != null) Calls.indirect(call, target, arrayOf(0L, environment))
        else Calls.indirect(call, target, arrayOf(0L))
    }

    companion object {
        @JvmStatic fun createDirect(target: RootCallTarget, metrics: Metrics): DirectCallNode {
            if (metrics.enabled) metrics.directCacheMisses++
            return DirectCallNode.create(target)
        }
    }
}

internal class ThunkTargetCache(private val metrics: Metrics) : Node() {
    @Child private var dispatch: DispatchThunkTarget = DispatchThunkTargetNodeGen.create()
    fun call(target: RootCallTarget, environment: CapturedFrame?): Any? =
        dispatch.execute(this, target, environment, metrics)
}

/** Cadenza's exact/PAP/overapplication specializations with a fixed site arity. */
@ReportPolymorphism
internal abstract class Dispatch(
    @JvmField val argsSize: Int,
    @JvmField val tailCall: Boolean,
    @JvmField val metrics: Metrics
) : Node() {
    abstract fun execute(frame: VirtualFrame, function: Closure, arguments: Array<Any?>): Any?

    @Specialization(guards = ["function.arity == argsSize", "function.target == cachedTarget"], limit = "3")
    fun direct(frame: VirtualFrame, function: Closure, arguments: Array<Any?>,
               @Cached("function.target") cachedTarget: RootCallTarget,
               @Cached("function.supplied.length") prefixSize: Int,
               @Cached("function.environment != null") hasEnvironment: Boolean,
               @Cached("createCaller(cachedTarget)") caller: DirectCallerNode): Any? {
        val packet = appendWithHeader(if (hasEnvironment) 2 else 1,
            function.supplied, prefixSize, arguments, argsSize)
        if (hasEnvironment) packet[1] = function.environment
        return caller.call(frame, packet, tailCall)
    }

    @Specialization(guards = ["function.arity < argsSize", "function.arity == arity", "function.target == cachedTarget"], limit = "3")
    fun directOverapplied(frame: VirtualFrame, function: Closure, arguments: Array<Any?>,
                          @Cached("function.arity") arity: Int,
                          @Cached("function.target") cachedTarget: RootCallTarget,
                          @Cached("function.supplied.length") prefixSize: Int,
                          @Cached("function.environment != null") hasEnvironment: Boolean,
                          @Cached("createCaller(cachedTarget)") caller: DirectCallerNode,
                          @Cached("createRemainder(arity)") rest: Dispatch,
                          @Cached("createForce()") force: Force): Any? {
        val packet = appendWithHeader(if (hasEnvironment) 2 else 1,
            function.supplied, prefixSize, arguments, arity)
        if (hasEnvironment) packet[1] = function.environment
        // There is pending application work, so this first call is not tail.
        val result = force.execute(frame, caller.call(frame, packet, false))
        val remaining = arguments.copyOfRange(arity, argsSize)
        return rest.execute(frame, requireClosure(result), remaining)
    }

    @Specialization(guards = ["function.arity > argsSize"])
    fun underapplied(function: Closure, arguments: Array<Any?>): Any? {
        if (metrics.enabled) metrics.papAllocations++
        return function.pap(arguments)
    }

    @Specialization(guards = ["function.arity == argsSize"], replaces = ["direct"])
    fun indirect(frame: VirtualFrame, function: Closure, arguments: Array<Any?>,
                 @Cached(value = "createIndirectCaller()", neverDefault = true) caller: IndirectCallerNode): Any? {
        val hasEnvironment = function.environment != null
        val packet = appendWithHeader(if (hasEnvironment) 2 else 1,
            function.supplied, function.supplied.size, arguments, argsSize)
        if (hasEnvironment) packet[1] = function.environment
        return caller.call(frame, function.target, packet, tailCall)
    }

    @Specialization(guards = ["function.arity < argsSize"], replaces = ["directOverapplied"])
    fun indirectOverapplied(frame: VirtualFrame, function: Closure, arguments: Array<Any?>,
                            @Bind node: Node,
                            @Cached(inline = true) generic: GenericDispatch): Any? =
        generic.execute(frame, node, function, arguments, tailCall, metrics)

    fun createCaller(target: RootCallTarget) = DirectCallerNode.create(target, metrics)
    fun createIndirectCaller() = IndirectCallerNode.create(metrics)
    fun createRemainder(arity: Int): Dispatch = create(argsSize - arity, tailCall, metrics)
    fun createForce(): Force = Force(metrics)

    companion object {
        fun create(argsSize: Int, tailCall: Boolean, metrics: Metrics): Dispatch =
            DispatchNodeGen.create(argsSize, tailCall, metrics)
    }
}

/** Saturated megamorphic overapplication consumes arguments in a bounded loop. */
@GenerateInline
internal abstract class GenericDispatch : Node() {
    abstract fun execute(frame: VirtualFrame, inliningTarget: Node, function: Closure,
                         arguments: Array<Any?>, tailCall: Boolean, metrics: Metrics): Any?

    companion object {
        @JvmStatic
        @Specialization
        fun apply(frame: VirtualFrame, node: Node, initial: Closure, arguments: Array<Any?>,
                  tailCall: Boolean, metrics: Metrics,
                  @Cached(value = "createCaller(metrics)", neverDefault = true) caller: IndirectCallerNode,
                  @Cached(value = "createForce(metrics)", neverDefault = true) force: Force,
                  @Cached underapplied: InlinedConditionProfile,
                  @Cached exact: InlinedConditionProfile): Any? {
            var function = initial
            var offset = 0
            while (true) {
                val remaining = arguments.size - offset
                if (underapplied.profile(node, function.arity > remaining)) {
                    if (metrics.enabled) metrics.papAllocations++
                    return function.pap(arguments, offset, remaining)
                }
                val count = function.arity
                val hasEnvironment = function.environment != null
                val skip = if (hasEnvironment) 2 else 1
                val packet = arrayOfNulls<Any>(skip + function.supplied.size + count)
                if (hasEnvironment) packet[1] = function.environment
                System.arraycopy(function.supplied, 0, packet, skip, function.supplied.size)
                System.arraycopy(arguments, offset, packet, skip + function.supplied.size, count)
                if (exact.profile(node, count == remaining)) {
                    return caller.call(frame, function.target, packet, tailCall)
                }
                val result = caller.call(frame, function.target, packet, false)
                offset += count
                function = requireClosure(force.execute(frame, result))
            }
        }

        @JvmStatic fun createCaller(metrics: Metrics) = IndirectCallerNode.create(metrics)
        @JvmStatic fun createForce(metrics: Metrics): Force = Force(metrics)
    }
}

internal fun requireClosure(value: Any?): Closure {
    if (value is Closure) return value
    CompilerDirectives.transferToInterpreterAndInvalidate()
    throw RuntimeFault("Application of a non-function")
}

private fun appendWithHeader(skip: Int, prefix: Array<Any?>, prefixSize: Int,
                             arguments: Array<Any?>, argumentCount: Int): Array<Any?> {
    val packet = arrayOfNulls<Any>(skip + prefixSize + argumentCount)
    System.arraycopy(prefix, 0, packet, skip, prefixSize)
    System.arraycopy(arguments, 0, packet, skip + prefixSize, argumentCount)
    return packet
}

internal class TailCall(val target: RootCallTarget, val args: Array<Any?>) : ControlFlowException()

internal class TailCheck(private val metrics: Metrics) : Node() {
    private val bounceProfile = BranchProfile.create()
    private val unrollProfile = BranchProfile.create()

    fun check(frame: VirtualFrame, target: RootCallTarget, arguments: Array<Any?>) {
        val sourceRoot = rootNode
        if (sourceRoot !is GuestRoot) bounce(target, arguments)
        val mask = sourceRoot.bloom(frame)
        val targetRoot = target.rootNode as? GuestRoot
            ?: throw RuntimeFault("Tail call target does not use the THC calling convention")
        if (mask and targetRoot.mask == targetRoot.mask) {
            bounce(target, arguments)
        } else {
            unrollProfile.enter()
            arguments[0] = mask
        }
    }

    private fun bounce(target: RootCallTarget, arguments: Array<Any?>): Nothing {
        bounceProfile.enter()
        if (metrics.enabled) metrics.tailBounces++
        throw TailCall(target, arguments)
    }
}

internal class DirectCallerNode(val target: RootCallTarget, private val metrics: Metrics) : Node() {
    @Child private var callNode = DirectCallNode.create(target)
    @Child private var loop = TailCallLoop(metrics)
    @Child private var tailCheck = TailCheck(metrics)
    private val normalProfile = BranchProfile.create()
    private val tailProfile = BranchProfile.create()

    init { if (metrics.enabled) metrics.directCacheMisses++ }

    fun call(frame: VirtualFrame, arguments: Array<Any?>, tailCall: Boolean): Any? {
        if (tailCall) {
            tailCheck.check(frame, target, arguments)
            return Calls.direct(callNode, arguments)
        }
        return try {
            arguments[0] = 0L
            val result = Calls.direct(callNode, arguments)
            normalProfile.enter()
            result
        } catch (tail: TailCall) {
            tailProfile.enter()
            loop.execute(tail)
        }
    }

    companion object {
        @JvmStatic fun create(target: RootCallTarget, metrics: Metrics) = DirectCallerNode(target, metrics)
    }
}

internal class IndirectCallerNode(private val metrics: Metrics) : Node() {
    @Child private var callNode = IndirectCallNode.create()
    @Child private var loop = TailCallLoop(metrics)
    @Child private var tailCheck = TailCheck(metrics)
    private val normalProfile = BranchProfile.create()
    private val tailProfile = BranchProfile.create()

    fun call(frame: VirtualFrame, target: RootCallTarget, arguments: Array<Any?>, tailCall: Boolean): Any? {
        if (metrics.enabled) metrics.indirectCalls++
        if (tailCall) {
            tailCheck.check(frame, target, arguments)
            return Calls.indirect(callNode, target, arguments)
        }
        return try {
            arguments[0] = 0L
            val result = Calls.indirect(callNode, target, arguments)
            normalProfile.enter()
            result
        } catch (tail: TailCall) {
            tailProfile.enter()
            loop.execute(tail)
        }
    }

    companion object { @JvmStatic fun create(metrics: Metrics) = IndirectCallerNode(metrics) }
}

internal class TailCallLoop(metrics: Metrics) : Node() {
    @Child private var loop: LoopNode = Truffle.getRuntime().createLoopNode(
        TailCallRepeatingNode(FrameLayout().build(), metrics))

    fun execute(tail: TailCall): Any? {
        val repeating = loop.repeatingNode as TailCallRepeatingNode
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), repeating.descriptor)
        repeating.setNext(frame, tail)
        loop.execute(frame)
        return frame.getObject(FrameLayout.TAIL_RESULT)
    }
}

internal class TailCallRepeatingNode(val descriptor: FrameDescriptor, metrics: Metrics) : Node(), RepeatingNode {
    @Child private var dispatch = TargetCache(metrics)

    fun setNext(frame: VirtualFrame, call: TailCall) {
        frame.setObject(FrameLayout.TAIL_FUNCTION, call.target)
        frame.setObject(FrameLayout.TAIL_ARGUMENTS, call.args)
    }

    override fun executeRepeating(frame: VirtualFrame): Boolean = try {
        val target = frame.getObject(FrameLayout.TAIL_FUNCTION) as RootCallTarget
        @Suppress("UNCHECKED_CAST")
        val arguments = frame.getObject(FrameLayout.TAIL_ARGUMENTS) as Array<Any?>
        frame.setObject(FrameLayout.TAIL_FUNCTION, null)
        frame.setObject(FrameLayout.TAIL_ARGUMENTS, null)
        arguments[0] = 0L
        frame.setObject(FrameLayout.TAIL_RESULT, dispatch.call(target, arguments))
        false
    } catch (tail: TailCall) {
        setNext(frame, tail)
        true
    }
}
