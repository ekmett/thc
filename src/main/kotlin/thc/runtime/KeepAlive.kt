// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame
import java.lang.ref.Reference

internal object CoreKeepAlive {
    fun validate(arguments: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation,
                 continuation: Pair<List<CoreRepresentation>, CoreRepresentation>?) {
        fun state(proof: CoreRepresentation) = !proof.isAggregate && !proof.isVector &&
            proof.kind == CoreKind.VOID && proof.primReps == emptyList<String>()
        fun reference(proof: CoreRepresentation) = !proof.isAggregate && !proof.isVector &&
            proof.kind in setOf(CoreKind.OBJECT, CoreKind.DATA, CoreKind.CLOSURE) &&
            proof.primReps in listOf(listOf("BoxedRep (Just Lifted)"), listOf("BoxedRep (Just Unlifted)"))
        if (arguments.size != 3 || !reference(arguments[0]) || !state(arguments[1]) ||
            arguments[2].kind !in setOf(CoreKind.CLOSURE, CoreKind.OBJECT) ||
            arguments[2].isAggregate || arguments[2].isVector ||
            arguments[2].primReps != listOf("BoxedRep (Just Lifted)") ||
            flags != listOf(arguments[0].primReps == listOf("BoxedRep (Just Lifted)"), false, true))
            throw RuntimeFault("keepAlive#: exact reference, State and continuation operands required")
        if (!result.present) throw RuntimeFault("keepAlive#: exact continuation result required")
        CoreRepresentations.requireNoVector(result, "keepAlive result")
        when {
            result.isSum -> SumShape.validate(result)
            result.isTuple -> TupleShape.validate(result)
            else -> {
                CoreRepresentations.requireScalar(result, "keepAlive result")
                if (result.primReps == null || result.kind == CoreKind.UNKNOWN)
                    throw RuntimeFault("keepAlive#: exact scalar result required")
            }
        }
        if (continuation != null) {
            if (continuation.first.isEmpty() || !state(continuation.first[0]))
                throw RuntimeFault("keepAlive#: continuation requires a logical State operand")
            if (continuation.first.size == 1) {
                result.refine(continuation.second)
                // keepAlive# preserves the continuation's exact logical result,
                // including scalar widths that share THC's Long carrier.
                TupleShape.requireCompatible(result, continuation.second, component = true)
            } else if (!reference(result) || result.primReps != listOf("BoxedRep (Just Lifted)"))
                throw RuntimeFault("keepAlive#: partially applied continuation must return a lifted function")
        }
    }
}

/** A saved reference stays live through every child step, including repeated cuts. */
private class AstKeepAliveScope(private val value: Any?, private val steps: List<AstResumeStep>) : AstResumeStep {
    override fun resume(frame: VirtualFrame, input: Any?): Any? =
        withKeptValue(value) { resumeAstSteps(frame, steps, input) }
}

private inline fun <T> withKeptValue(value: Any?, body: () -> T): T {
    return try { body() }
    catch (cut: AstCapture) { throw cut.enclose { AstKeepAliveScope(value, it) } }
    finally { Reference.reachabilityFence(value) }
}

/** Do not tail-transfer the action: the fence belongs after its actual completion. */
internal class KeepAliveExpression(@field:Child private var kept: Expr,
    @field:Child private var state: Expr, @field:Child private var action: Expr,
    proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }

    private enum class Route { GENERIC, LONG, FLOAT, DOUBLE, ADDRESS, DATA, CLOSURE, TUPLE }

    private class ResumeKept(private val node: KeepAliveExpression, private val route: Route,
                             private val slots: IntArray?, private val offset: Int) : AstResumeStep {
        override fun resume(frame: VirtualFrame, input: Any?): Any? = withKeptValue(input) {
            node.loadState(frame, route, slots, offset)
            node.resumeAction(frame, route, slots, offset)
        }
    }

    private class ResumeState(private val node: KeepAliveExpression, private val route: Route,
                              private val slots: IntArray?, private val offset: Int) : AstResumeStep {
        override fun resume(frame: VirtualFrame, input: Any?): Any? {
            ManagedByteArray.requireState(input)
            return node.resumeAction(frame, route, slots, offset)
        }
    }

    private fun loadState(frame: VirtualFrame, route: Route, slots: IntArray?, offset: Int) {
        val token = try { state.execute(frame) }
        catch (cut: AstCapture) { throw cut.append(ResumeState(this, route, slots, offset)) }
        ManagedByteArray.requireState(token)
    }

    /** Only suspended operand edges select a route dynamically; normal calls stay typed. */
    private fun resumeAction(frame: VirtualFrame, route: Route, slots: IntArray?, offset: Int): Any? = when (route) {
        Route.GENERIC -> action.execute(frame)
        Route.LONG -> action.executeLong(frame)
        Route.FLOAT -> action.executeFloat(frame)
        Route.DOUBLE -> action.executeDouble(frame)
        Route.ADDRESS -> action.executeAddress(frame)
        Route.DATA -> action.executeDataValue(frame)
        Route.CLOSURE -> action.executeClosure(frame)
        Route.TUPLE -> action.executeTuple(frame, slots!!, offset)
    }

    private inline fun <T> retaining(frame: VirtualFrame, route: Route,
                                    slots: IntArray? = null, offset: Int = 0, block: () -> T): T {
        // The lifted expression is compiled as a lazy argument, never forced here.
        val value = try { kept.execute(frame) }
        catch (cut: AstCapture) { throw cut.append(ResumeKept(this, route, slots, offset)) }
        return withKeptValue(value) {
            loadState(frame, route, slots, offset)
            block()
        }
    }
    override fun execute(frame: VirtualFrame): Any? = retaining(frame, Route.GENERIC) { action.execute(frame) }
    override fun executeLong(frame: VirtualFrame): Long = retaining(frame, Route.LONG) { action.executeLong(frame) }
    override fun executeFloat(frame: VirtualFrame): Float = retaining(frame, Route.FLOAT) { action.executeFloat(frame) }
    override fun executeDouble(frame: VirtualFrame): Double = retaining(frame, Route.DOUBLE) { action.executeDouble(frame) }
    override fun executeAddress(frame: VirtualFrame): ManagedAddress = retaining(frame, Route.ADDRESS) { action.executeAddress(frame) }
    override fun executeDataValue(frame: VirtualFrame): DataValue = retaining(frame, Route.DATA) { action.executeDataValue(frame) }
    override fun executeClosure(frame: VirtualFrame): Closure = retaining(frame, Route.CLOSURE) { action.executeClosure(frame) }
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? =
        retaining(frame, Route.TUPLE, slots, offset) { action.executeTuple(frame, slots, offset) }
}
