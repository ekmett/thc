// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.frame.MaterializedFrame
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node
import thc.Language
import java.util.IdentityHashMap

/** Persistent lazy annotation payloads. No executable frame or thread is retained. */
internal class StackAnnotationState private constructor(val value: Any?, val prior: StackAnnotationState?) {
    fun push(value: Any?) = StackAnnotationState(value, this)

    @TruffleBoundary fun values(): List<Any?> {
        val result = ArrayList<Any?>()
        var cursor = this
        while (cursor.prior != null) { result += cursor.value; cursor = cursor.prior!! }
        return java.util.Collections.unmodifiableList(result)
    }

    /** Only frames inside the matching prompt travel with a delimited stack. */
    @TruffleBoundary fun rebase(outside: StackAnnotationState, ambient: StackAnnotationState,
                               copies: IdentityHashMap<StackAnnotationState, StackAnnotationState>): StackAnnotationState {
        copies[outside] = ambient
        val prefix = ArrayList<StackAnnotationState>()
        var cursor = this
        while (!copies.containsKey(cursor)) {
            prefix += cursor
            cursor = cursor.prior ?: fault("Annotation continuation lost its lexical boundary")
        }
        var result = copies.getValue(cursor)
        for (index in prefix.indices.reversed()) {
            val original = prefix[index]
            result = result.push(original.value)
            copies[original] = result
        }
        return result
    }

    companion object { @JvmField val EMPTY = StackAnnotationState(null, null) }
}

/** Ordinary AST calls retain the established typed tuple dispatch and consumer. */
internal class AnnotatedTuple(@field:Child private var annotation: Expr,
    @field:Child private var state: Expr, @field:Child private var body: Expr) : Expr() {
    init { representation = body.representation }
    override fun execute(frame: VirtualFrame): Nothing = fault("annotateStack# requires a tuple destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val value = annotation.execute(frame)
        requireVoidCarrier(state.execute(frame))
        val prior = StackAnnotations.enter(this, value)
        return try { body.executeTuple(frame, slots, offset) }
        catch (cut: DelimitedCut) { throw cut.append(frame, DelimitedAnnotationStep(this, prior)) }
        finally { StackAnnotations.set(this, prior) }
    }
}

internal object StackAnnotations {
    fun validate(arguments: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        val annotation = arguments.firstOrNull()
        if (arguments.size != 3 || flags != listOf(true, true, false) || annotation == null ||
            annotation.isAggregate || annotation.isVector || annotation.kind !in setOf(CoreKind.OBJECT, CoreKind.CLOSURE, CoreKind.DATA))
            fault("annotateStack#: expected a lazy lifted annotation and State action")
        CoreProfileAction.validate(arguments.drop(1), flags.drop(1), result)
    }
    @JvmStatic @TruffleBoundary fun current(node: Node?): StackAnnotationState =
        Language.currentState(node).stackAnnotations.get()
    @JvmStatic @TruffleBoundary fun set(node: Node?, state: StackAnnotationState) {
        Language.currentState(node).stackAnnotations.set(state)
    }
    @JvmStatic fun enter(node: Node, annotation: Any?): StackAnnotationState {
        val prior = current(node)
        set(node, prior.push(annotation))
        return prior
    }
}

/** The annotation return is a scope: an exception from an earlier resumed step
 * must restore it just as a successful action does. */
private class AstAnnotationScope(private val node: Node, private val prior: StackAnnotationState,
                                  private val steps: List<AstResumeStep>) : AstResumeStep {
    override fun resume(frame: VirtualFrame, input: Any?): Any? =
        withAstAnnotationRestore(node, prior) { resumeAstSteps(frame, steps, input) }
}

private inline fun <T> withAstAnnotationRestore(node: Node, prior: StackAnnotationState, body: () -> T): T {
    return try { body() }
    catch (cut: AstCapture) { throw cut.enclose { AstAnnotationScope(node, prior, it) } }
    finally { StackAnnotations.set(node, prior) }
}

/** The lexical return must remain present even when the action is in tail position. */
internal class AnnotatedAction(private val shape: TupleShape,
    @field:Child private var annotation: Expr, @field:Child private var action: Expr,
    @field:Child private var state: Expr, metrics: Metrics, async: Boolean) : Expr() {
    @Child private var force = Force(metrics, async)
    @Child private var dispatch = Dispatch.create(1, false, metrics)
    init { representation = shape.proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("annotateStack# requires a tuple destination")

    private fun invoke(frame: VirtualFrame, action: Any?): Any? {
        val closure = try { requireClosure(AstControl.force(frame, this, force, action)) }
        catch (cut: AstCapture) {
            throw cut.append(object : AstResumeStep {
                override fun resume(frame: VirtualFrame, input: Any?): Any? = call(frame, requireClosure(input))
            })
        }
        return call(frame, closure)
    }

    private fun call(frame: VirtualFrame, closure: Closure): Any? {
        val result = try {
            val returned = dispatch.execute(frame, closure, arrayOf(Unit))
            DelimitedControl.captureBytecode(returned, shape)
            AstControl.complete(this, returned, closure.target, shape)
        } catch (cut: AstCapture) {
            throw cut.append(object : AstResumeStep {
                override fun resume(frame: VirtualFrame, input: Any?): Any? = ownedTupleResult(input, shape)
            })
        }
        return ownedTupleResult(result, shape)
    }

    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val value = try { annotation.execute(frame) }
        catch (cut: AstCapture) {
            throw cut.append(object : AstResumeStep {
                override fun resume(frame: VirtualFrame, input: Any?): Any? = loadAction(frame, input, slots, offset)
            })
        }
        return loadAction(frame, value, slots, offset)
    }

    private fun loadAction(frame: VirtualFrame, value: Any?, slots: IntArray, offset: Int): Any? {
        val function = try { action.execute(frame) }
        catch (cut: AstCapture) {
            throw cut.append(object : AstResumeStep {
                override fun resume(frame: VirtualFrame, input: Any?): Any? = loadState(frame, value, input, slots, offset)
            })
        }
        return loadState(frame, value, function, slots, offset)
    }

    private fun loadState(frame: VirtualFrame, value: Any?, function: Any?, slots: IntArray, offset: Int): Any? {
        val token = try { state.execute(frame) }
        catch (cut: AstCapture) {
            throw cut.append(object : AstResumeStep {
                override fun resume(frame: VirtualFrame, input: Any?): Any? {
                    requireVoidCarrier(input)
                    return runAction(frame, value, function, slots, offset)
                }
            })
        }
        requireVoidCarrier(token)
        return runAction(frame, value, function, slots, offset)
    }

    private fun runAction(frame: VirtualFrame, value: Any?, function: Any?, slots: IntArray, offset: Int): Any? {
        val prior = StackAnnotations.enter(this, value)
        return withAstAnnotationRestore(this, prior) {
            val result = try { invoke(frame, function) }
            catch (cut: AstCapture) {
                throw cut.append(object : AstResumeStep {
                    override fun resume(frame: VirtualFrame, input: Any?): Any? {
                        shape.consume(frame, input, slots, offset)
                        return null
                    }
                })
            }
            catch (cut: DelimitedCut) {
                throw cut.append(frame, DelimitedAnnotationStep(this, prior))
                    .append(frame, DelimitedTupleStep(AstTupleDestination(shape, slots, offset), this))
            }
            shape.consume(frame, result, slots, offset)
            null
        }
    }
}

/** Captured return through annotateStack#. Rebased once for each multi-shot invocation. */
internal class DelimitedAnnotationStep(private val node: Node, val prior: StackAnnotationState) : DelimitedStep {
    fun rebase(outside: StackAnnotationState, ambient: StackAnnotationState,
               copies: IdentityHashMap<StackAnnotationState, StackAnnotationState>) =
        DelimitedAnnotationStep(node, prior.rebase(outside, ambient, copies))
    fun unwind() = StackAnnotations.set(node, prior)
    override fun resume(frame: MaterializedFrame, input: DelimitedResume, ambient: MaskingState,
                        outerMask: DelimitedStep?): Any? {
        unwind()
        return input.get()
    }
}
