// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.bytecode.ContinuationResult
import com.oracle.truffle.api.exception.AbstractTruffleException
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.MaterializedFrame
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.ControlFlowException
import thc.Language
import java.util.IdentityHashMap

/** Prompt identity is opaque and belongs to one guest context, not one carrier thread. */
internal class PromptTag(val owner: Language.State)

/** A resumption can throw at the suspended call, so captured catch frames still apply. */
internal class DelimitedResume(val value: Any?, val failure: AbstractTruffleException? = null) {
    fun get(): Any? { failure?.let { throw it }; return value }
}

internal interface DelimitedStep {
    fun resume(frame: MaterializedFrame, input: DelimitedResume, ambient: MaskingState,
               outerMask: DelimitedStep?): Any?
    fun finish(result: Any?, site: DelimitedActionSite): Any? = result
}

/** A pending application already owns the destination that its outer dispatcher
 * would otherwise consume. Capture that consumer only once. */
internal interface DelimitedPendingApplication : DelimitedStep {
    val destination: TupleDestination
}

/** Lexical control transfers belong to their saved owner, not the resumer's
 * ordinary trampoline. Otherwise they can silently discard caller suffixes. */
internal interface DelimitedTransferStep : DelimitedStep {
    fun accepts(transfer: ControlFlowException): Boolean
    fun transfer(frame: MaterializedFrame, transfer: ControlFlowException, site: DelimitedActionSite): Any?
}

internal class DelimitedRootStep(private val root: FunctionRoot) : DelimitedTransferStep {
    override fun resume(frame: MaterializedFrame, input: DelimitedResume, ambient: MaskingState,
                        outerMask: DelimitedStep?): Any? = input.get()
    override fun accepts(transfer: ControlFlowException): Boolean =
        transfer === AstSelfCall.INSTANCE || transfer is TailCall || transfer is HandoffTailCall
    override fun transfer(frame: MaterializedFrame, transfer: ControlFlowException, site: DelimitedActionSite): Any? =
        root.resumeDelimited(frame, transfer, site)
    override fun finish(result: Any?, site: DelimitedActionSite): Any? = site.finish(result, root.tupleResult)
}

internal class DelimitedFrame(val frame: MaterializedFrame, val step: DelimitedStep)

/** The exception only transports saved suffixes. It is never the continuation itself. */
internal class DelimitedCut(val tag: PromptTag, val handler: Any?, val inputShape: TupleShape,
                           val capturedMask: MaskingState, node: Node) :
    AbstractTruffleException("Internal delimited continuation capture", null, 0, node), InternalGuestControl {
    val capturedAnnotations = StackAnnotations.current(node)
    val frames = ArrayList<DelimitedFrame>()
    fun append(frame: VirtualFrame, step: DelimitedStep): DelimitedCut {
        frames.add(DelimitedFrame(frame.materialize(), step))
        return this
    }
}

/** Copy control locals, never the guest heap. In particular, MutVars and captured
 * closure environments remain shared across distinct invocations, as in GHC. */
@TruffleBoundary
internal fun copyContinuationFrame(frame: MaterializedFrame): MaterializedFrame {
    val descriptor = frame.frameDescriptor
    val copy = Truffle.getRuntime().createMaterializedFrame(frame.arguments.copyOf(), descriptor)
    frame.copyTo(0, copy, 0, descriptor.numberOfSlots)
    for (index in 0 until descriptor.numberOfAuxiliarySlots)
        copy.setAuxiliarySlot(index, frame.getAuxiliarySlot(index))
    return copy
}

internal class DelimitedBytecodeStep(private val saved: ContinuationResult,
                                   private val shape: TupleShape?) : DelimitedTransferStep {
    override fun resume(frame: MaterializedFrame, input: DelimitedResume, ambient: MaskingState,
                        outerMask: DelimitedStep?): Any? {
        val answer = ContinuationResult.create(saved.continuationRootNode, frame, saved.result).continueWith(input)
        DelimitedControl.captureBytecode(answer, shape)
        return answer
    }
    override fun finish(result: Any?, site: DelimitedActionSite): Any? = site.finish(result, shape)
    override fun accepts(transfer: ControlFlowException): Boolean = transfer is TailCall
    override fun transfer(frame: MaterializedFrame, transfer: ControlFlowException, site: DelimitedActionSite): Any? =
        site.tail(transfer as TailCall)
}

internal class DelimitedTupleStep(private val destination: TupleDestination, private val node: Node) : DelimitedStep {
    override fun resume(frame: MaterializedFrame, input: DelimitedResume, ambient: MaskingState,
                        outerMask: DelimitedStep?): Any? {
        destination.consume(frame, node, input.get())
        return null
    }
}

internal class DelimitedMaskStep(private val node: Node, private val prior: MaskingState) : DelimitedStep {
    fun recapture(ambient: MaskingState, outerMask: DelimitedStep?): DelimitedMaskStep =
        if (this === outerMask) DelimitedMaskStep(node, ambient) else this
    fun unwind(ambient: MaskingState, outerMask: DelimitedStep?) =
        SynchronousMasking.set(node, if (this === outerMask) ambient else prior)
    override fun resume(frame: MaterializedFrame, input: DelimitedResume, ambient: MaskingState,
                        outerMask: DelimitedStep?): Any? {
        unwind(ambient, outerMask)
        return input.get()
    }
}

internal class DelimitedPromptStep(val tag: PromptTag, val site: DelimitedActionSite,
                                  val shape: TupleShape) : DelimitedStep {
    override fun resume(frame: MaterializedFrame, input: DelimitedResume, ambient: MaskingState,
                        outerMask: DelimitedStep?): Any? = input.get()
    fun handle(frame: MaterializedFrame, cut: DelimitedCut): Any? =
        site.handle(frame, cut, shape)
}

internal class DelimitedCatchStep(private val site: DelimitedActionSite, private val handler: Any?,
                                 private val shape: TupleShape) : DelimitedStep {
    override fun resume(frame: MaterializedFrame, input: DelimitedResume, ambient: MaskingState,
                        outerMask: DelimitedStep?): Any? = if (input.failure == null) input.value
        else site.handleException(frame, handler, input.failure, shape)
}

/** Immutable multi-shot stack image. A fresh frame graph belongs to each invocation. */
internal class DelimitedStack(cut: DelimitedCut, private val outputShape: TupleShape) {
    private val owner = cut.tag.owner
    private val inputShape = cut.inputShape
    private val initialMask = cut.capturedMask
    private val initialAnnotations = cut.capturedAnnotations
    private val frames: List<DelimitedFrame>
    init {
        val copies = IdentityHashMap<MaterializedFrame, MaterializedFrame>()
        frames = cut.frames.map { DelimitedFrame(copies.getOrPut(it.frame) { copyContinuationFrame(it.frame) }, it.step) }
    }

    // The copied frame graph is a dynamic cold interpreter for saved suffixes.
    // Its ordinary guest calls still use their installed targets; the graph
    // itself must not be partially evaluated with nonconstant frame descriptors.
    @TruffleBoundary(transferToInterpreterOnException = false)
    fun resume(site: DelimitedActionSite, frame: MaterializedFrame, action: Any?): Any? {
        if (Language.currentState(site) !== owner) fault("Continuation belongs to another context")
        val ambient = SynchronousMasking.current(site)
        val ambientAnnotations = StackAnnotations.current(site)
        val outsideAnnotations = (frames.lastOrNull { it.step is DelimitedAnnotationStep }?.step as? DelimitedAnnotationStep)?.prior
        val annotationCopies = IdentityHashMap<StackAnnotationState, StackAnnotationState>()
        val copies = IdentityHashMap<MaterializedFrame, MaterializedFrame>()
        val active = frames.map {
            val step = if (it.step is DelimitedAnnotationStep)
                it.step.rebase(outsideAnnotations!!, ambientAnnotations, annotationCopies) else it.step
            DelimitedFrame(copies.getOrPut(it.frame) { copyContinuationFrame(it.frame) }, step)
        }
        val outerMask = active.lastOrNull { it.step is DelimitedMaskStep }?.step
        try {
            if (outerMask != null) SynchronousMasking.set(site, initialMask)
            if (outsideAnnotations != null)
                StackAnnotations.set(site, initialAnnotations.rebase(outsideAnnotations, ambientAnnotations, annotationCopies))
            val input = try { DelimitedResume(site.invoke(frame, action, arrayOf(Unit), inputShape)) }
            catch (failure: GuestException) { DelimitedResume(null, failure) }
            catch (failure: AsyncDelivery) {
                DelimitedResume(null, failure)
            }
            catch (cut: AstCapture) { DelimitedResume(null, DelimitedControl.asyncFailure(cut, site)) }
            catch (cut: DelimitedCut) { return transfer(site, cut, active, ambient, outerMask) }
            return run(site, active, input, ambient, outerMask)
        } finally {
            SynchronousMasking.set(site, ambient)
            StackAnnotations.set(site, ambientAnnotations)
        }
    }

    private fun run(site: DelimitedActionSite, active: List<DelimitedFrame>, initial: DelimitedResume,
                    ambient: MaskingState, outerMask: DelimitedStep?): Any? {
        var input = initial
        active.forEachIndexed { index, entry ->
            input = try { DelimitedResume(entry.step.finish(site.captured(entry.frame, null) {
                entry.step.resume(entry.frame, input, ambient, outerMask)
            }, site)) }
            catch (failure: GuestException) { DelimitedResume(null, failure) }
            catch (failure: AsyncDelivery) {
                DelimitedResume(null, failure)
            }
            catch (cut: AstCapture) { DelimitedResume(null, DelimitedControl.asyncFailure(cut, site)) }
            catch (cut: DelimitedCut) { return transfer(site, cut, active.drop(index + 1), ambient, outerMask) }
            catch (flow: ControlFlowException) {
                return transferControl(site, flow, active.drop(index), ambient, outerMask)
            }
        }
        return input.get()
    }

    private fun transferControl(site: DelimitedActionSite, flow: ControlFlowException,
                                remaining: List<DelimitedFrame>, ambient: MaskingState,
                                outerMask: DelimitedStep?): Any? {
        val owner = remaining.indexOfFirst { it.step is DelimitedTransferStep && it.step.accepts(flow) }
        if (owner < 0) throw flow
        val entry = remaining[owner]
        val step = entry.step as DelimitedTransferStep
        remaining.take(owner).forEach { if (it.step is DelimitedAnnotationStep) it.step.unwind() }
        val after = remaining.drop(owner + 1)
        // Completion belongs after a suspended transfer too. In particular, a
        // root's tuple loan must be detached before any outer saved step runs.
        val input = try { DelimitedResume(step.finish(site.captured(entry.frame, null) {
            step.transfer(entry.frame, flow, site)
        }, site)) }
        catch (failure: GuestException) { DelimitedResume(null, failure) }
        catch (failure: AsyncDelivery) {
            DelimitedResume(null, failure)
        }
        catch (cut: AstCapture) { DelimitedResume(null, DelimitedControl.asyncFailure(cut, site)) }
        catch (cut: DelimitedCut) { return transfer(site, cut, after, ambient, outerMask) }
        catch (next: ControlFlowException) { return transferControl(site, next, after, ambient, outerMask) }
        return run(site, after, input, ambient, outerMask)
    }

    private fun transfer(site: DelimitedActionSite, cut: DelimitedCut, remaining: List<DelimitedFrame>,
                         ambient: MaskingState, outerMask: DelimitedStep?): Any? {
        remaining.forEachIndexed { index, entry ->
            val step = entry.step
            if (step is DelimitedPromptStep && step.tag === cut.tag) {
                val input = try { DelimitedResume(step.handle(entry.frame, cut)) }
                catch (failure: GuestException) { DelimitedResume(null, failure) }
                catch (failure: AsyncDelivery) {
                    DelimitedResume(null, failure)
                }
                catch (captured: AstCapture) { DelimitedResume(null, DelimitedControl.asyncFailure(captured, site)) }
                catch (next: DelimitedCut) { return transfer(site, next, remaining.drop(index + 1), ambient, outerMask) }
                return run(site, remaining.drop(index + 1), input, ambient, outerMask)
            }
            if (step is DelimitedMaskStep) {
                // Its outer return was rebased for this invocation. A new
                // capture may put another mask return outside it, so freeze
                // that rebased prior rather than the original image's prior.
                cut.frames.add(DelimitedFrame(entry.frame, step.recapture(ambient, outerMask)))
                step.unwind(ambient, outerMask)
            } else {
                cut.frames.add(entry)
                if (step is DelimitedAnnotationStep) step.unwind()
            }
        }
        throw cut
    }

    @TruffleBoundary fun closure(language: Language, metrics: Metrics): Closure =
        Closure(null, arity = 2, target = DelimitedContinuationRoot(language, this, outputShape, metrics).callTarget)
}

private class DelimitedContinuationRoot(language: Language, private val stack: DelimitedStack,
                                       shape: TupleShape, metrics: Metrics) :
    GuestRoot(language, FrameDescriptor.newBuilder().build()) {
    @Child private var site = DelimitedActionSite(language, metrics)
    init { configureEntry(booleanArrayOf(false, false), false); configureTupleResult(shape) }
    override fun bloom(frame: VirtualFrame): Long = frame.arguments[0] as Long or mask
    override fun execute(frame: VirtualFrame): Any? {
        requireVoidCarrier(frame.arguments[2])
        return stack.resume(site, frame.materialize(), frame.arguments[1])
    }
}

/** Shared real IO call boundary for prompts and restored catch/mask frames. */
internal class DelimitedActionSite(private val language: Language, private val metrics: Metrics) : Node() {
    @Child private var one = Dispatch.create(1, false, metrics)
    @Child private var two = Dispatch.create(2, false, metrics)
    @Child private var force = Force(metrics)
    @Child private var trampoline = TailCallLoop(metrics)
    fun tail(transfer: TailCall): Any? = trampoline.execute(transfer)
    /** Every call creates a fresh one-shot owner. It is consumed here, never
     * appended to a DelimitedCut or installed in the reusable frame graph. */
    fun finish(result: Any?, shape: TupleShape?, expectedTarget: RootCallTarget? = null): Any? {
        DelimitedControl.captureBytecode(result, shape)
        val saved = when (result) {
            is TailYield -> SavedGuestContinuationKt.savedGuestContinuation(result.continuation)
            is AstTailYield -> result.continuation
            else -> SavedGuestContinuationKt.savedGuestContinuation(result)
        }
        val answer = if (saved == null) result else {
            val target = when (result) {
                is TailYield -> result.target
                is AstTailYield -> result.target
                else -> expectedTarget
            }
            if (target != null) {
                val root = saved.sourceRoot as? GuestRoot ?: fault("Non-guest delimited invocation cut")
                if (!root.isSelf(target)) fault("Delimited invocation returned an unrelated continuation")
                if (shape != null && !root.hasTupleResult(shape))
                    fault("Delimited invocation changed its tuple result shape")
            }
            saved.asyncRequest()?.let { throw AsyncDelivery(it, this) }
            if (!AsyncContinuations.isYieldMarker(saved.yielded)) fault("Unsupported delimited invocation cut")
            val drained = force.drainStack(saved, shape, true)
            DelimitedControl.asyncResult(drained, this)
            drained
        }
        return if (shape == null) answer else ownedTupleResult(answer, shape)
    }

    /** A cut in a saved AST step belongs to that invocation's active frame,
     * not to the action site's outer activation or the immutable image. */
    inline fun captured(frame: VirtualFrame, shape: TupleShape?, body: () -> Any?): Any? =
        try { body() }
        catch (cut: AstCapture) {
            cut.asyncRequest()?.let { throw AsyncDelivery(it, this) }
            finish(cut.freeze(rootNode as? GuestRoot ?: fault("Missing delimited invocation root"), frame.materialize()), shape)
        }

    private fun forceAction(frame: VirtualFrame, action: Any?): Any? = try { force.execute(frame, action) }
        catch (cut: ThunkSuspended) {
            cut.asyncRequest?.let { throw AsyncDelivery(it, this) }
            force.drainDelimitedBoundary(cut.thunk)
        } catch (cut: CallSegmentSuspended) {
            cut.asyncRequest?.let { throw AsyncDelivery(it, this) }
            force.drainDelimitedBoundary(cut.segment)
        }

    fun invoke(frame: VirtualFrame, action: Any?, arguments: Array<Any?>, shape: TupleShape): Any? {
        try {
            val closure = requireClosure(forceAction(frame, action))
            return captured(frame, shape) {
                finish((if (arguments.size == 1) one else two).execute(frame, closure, arguments), shape, closure.target)
            }
        } catch (cut: ThunkSuspended) {
            cut.asyncRequest?.let { throw AsyncDelivery(it, this) }
            throw UnsupportedCore("Delimited action encountered a non-delivery thunk scheduling cut")
        } catch (cut: CallSegmentSuspended) {
            cut.asyncRequest?.let { throw AsyncDelivery(it, this) }
            throw UnsupportedCore("Delimited action encountered a non-delivery call scheduling cut")
        } catch (blocked: AsyncBlocked) {
            throw AsyncDelivery(blocked.request, this)
        }
    }
    fun handle(frame: VirtualFrame, cut: DelimitedCut, shape: TupleShape): Any? {
        val continuation = snapshot(cut, shape)
        return invoke(frame, cut.handler, arrayOf(continuation, Unit), shape)
    }
    @TruffleBoundary
    private fun snapshot(cut: DelimitedCut, shape: TupleShape): Closure =
        DelimitedStack(cut, shape).closure(language, metrics)
    fun prompt(frame: VirtualFrame, tag: Any?, action: Any?, state: Any?, shape: TupleShape): Any? {
        requireVoidCarrier(state)
        val identity = DelimitedControl.tag(this, tag)
        return try { invoke(frame, action, arrayOf(Unit), shape) }
        catch (cut: DelimitedCut) {
            if (cut.tag === identity) handle(frame, cut, shape)
            else throw cut.append(frame, DelimitedPromptStep(identity, this, shape))
        }
    }
    fun handleException(frame: VirtualFrame, handler: Any?, failure: AbstractTruffleException, shape: TupleShape): Any? {
        val payload = when (failure) {
            is GuestException -> failure.payload
            is AsyncDelivery -> {
                check(failure.request.target === Thread.currentThread() &&
                    failure.request.targetId == GuestThreads.current(this).currentId() &&
                    failure.request.state == AsyncRequestState.CLAIMED) {
                    "Delimited catch delivery left its target or was already consumed"
                }
                failure.request.acknowledge()
                failure.request.payload
            }
            else -> throw failure
        }
        val prior = SynchronousMasking.current(this)
        if (prior == MaskingState.UNMASKED) SynchronousMasking.set(this, MaskingState.MASKED_INTERRUPTIBLE)
        return try { invoke(frame, handler, arrayOf(payload, Unit), shape) }
        catch (cut: DelimitedCut) { throw cut.append(frame, DelimitedMaskStep(this, prior)) }
        finally { SynchronousMasking.set(this, prior) }
    }
    fun caught(frame: VirtualFrame, action: Any?, handler: Any?, state: Any?, shape: TupleShape): Any? {
        requireVoidCarrier(state)
        return try { invoke(frame, action, arrayOf(Unit), shape) }
        catch (failure: GuestException) { handleException(frame, handler, failure, shape) }
        catch (delivered: AsyncDelivery) { handleException(frame, handler, delivered, shape) }
        catch (cut: DelimitedCut) { throw cut.append(frame, DelimitedCatchStep(this, handler, shape)) }
    }
    fun masked(frame: VirtualFrame, action: Any?, state: Any?, shape: TupleShape, target: MaskingState): Any? {
        requireVoidCarrier(state)
        val prior = SynchronousMasking.current(this)
        SynchronousMasking.set(this, target)
        return try { invoke(frame, action, arrayOf(Unit), shape) }
        catch (cut: DelimitedCut) { throw cut.append(frame, DelimitedMaskStep(this, prior)) }
        finally { SynchronousMasking.set(this, prior) }
    }
    fun annotated(frame: VirtualFrame, annotation: Any?, action: Any?, state: Any?, shape: TupleShape): Any? {
        requireVoidCarrier(state)
        val prior = StackAnnotations.enter(this, annotation)
        return try { invoke(frame, action, arrayOf(Unit), shape) }
        catch (cut: DelimitedCut) { throw cut.append(frame, DelimitedAnnotationStep(this, prior)) }
        finally { StackAnnotations.set(this, prior) }
    }
}

internal object DelimitedControl {
    /** The parked child is one-shot; only its original request unwinds this
     * invocation's fresh suffix. Neither is stored in the reusable image. */
    fun asyncFailure(cut: AstCapture, node: Node): AsyncDelivery =
        cut.asyncRequest()?.let { AsyncDelivery(it, node) }
            ?: throw UnsupportedCore("Delimited continuation encountered a non-delivery asynchronous scheduling cut")

    fun asyncResult(result: Any?, node: Node) {
        val saved = when (result) {
            is TailYield -> SavedGuestContinuationKt.savedGuestContinuation(result.continuation)
            is AstTailYield -> result.continuation
            else -> SavedGuestContinuationKt.savedGuestContinuation(result)
        } ?: return
        saved.asyncRequest()?.let { throw AsyncDelivery(it, node) }
        throw UnsupportedCore("Delimited continuation encountered a non-delivery asynchronous scheduling cut")
    }
    /** Inspect the dynamic captured-step graph outside guest partial evaluation.
     * Otherwise the temporarily sole loaded PendingApplication implementation
     * creates a CHA dependency that a later generic-call compilation invalidates. */
    @TruffleBoundary
    fun tupleCut(cut: DelimitedCut, frame: MaterializedFrame, destination: TupleDestination, node: Node): DelimitedCut {
        val pending = cut.frames.lastOrNull()
        if (destination is AstTupleDestination && !(pending?.frame === frame &&
                (pending.step as? DelimitedPendingApplication)?.destination === destination))
            cut.append(frame, DelimitedTupleStep(destination, node))
        return cut
    }
    fun enabled(node: Node): Boolean = (node.rootNode as? GuestRoot)?.delimitedControlEnabled == true

    /** Fixed calling-convention metadata, computed before the root can execute. */
    @TruffleBoundary
    fun rootEnabled(root: GuestRoot): Boolean = when (root) {
        is FunctionRoot -> root.enableDelimited
        is BytecodeRoot -> root.isDelimitedEnabled
        is DelimitedContinuationRoot -> true
        else -> false
    }
    fun contains(value: Any?): Boolean = when (value) {
        is Map<*, *> -> value.values.any(::contains)
        is List<*> -> value.take(2) in listOf(listOf("prim", "prompt#"), listOf("prim", "control0#")) || value.any(::contains)
        else -> false
    }
    fun validate(name: String, arguments: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        fun state(proof: CoreRepresentation) = proof.kind == CoreKind.VOID && !proof.isAggregate && !proof.isVector
        val fields = result.components
        val arity = if (name == "newPromptTag#") 1 else 3
        if (arguments.size != arity || flags != (if (arity == 1) listOf(false) else listOf(false, true, false)) ||
            !state(arguments.last()) || !result.isTuple || fields?.size != 2 || !state(fields[0]) ||
            (arity == 3 && (arguments[0].kind != CoreKind.OBJECT || arguments[1].kind != CoreKind.CLOSURE)) ||
            (name == "newPromptTag#" && fields[1].kind != CoreKind.OBJECT))
            throw RuntimeFault("$name: expected prompt identity, action, State# and tuple carriers")
        TupleShape.validate(result)
    }
    @JvmStatic fun tag(node: Node, value: Any?): PromptTag {
        val tag = value as? PromptTag ?: fault("Expected PromptTag# carrier")
        val owner = Language.currentState(node)
        if (owner !== tag.owner) fault("Prompt tag belongs to another context")
        if (owner.stm.hasTransaction()) fault("STM transaction frames do not support explicit delimited capture")
        return tag
    }
    @JvmStatic fun captureBytecode(result: Any?, shape: TupleShape?) {
        val saved = when (result) { is TailYield -> result.continuation; is ContinuationResult -> result; else -> return }
        val cut = saved.result as? DelimitedCut ?: return
        cut.append(saved.frame, DelimitedBytecodeStep(saved, shape))
        throw cut
    }
}

internal class DelimitedPrimitive(private val name: String, private val shape: TupleShape,
                                 @field:Children private var operands: Array<Expr>,
                                 language: Language, metrics: Metrics) : Expr() {
    @Child private var site = DelimitedActionSite(language, metrics)
    init { representation = shape.proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing {
        CompilerDirectives.transferToInterpreterAndInvalidate()
        fault("$name requires a tuple destination")
    }
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        if (name == "newPromptTag#") {
            requireVoidCarrier(operands[0].execute(frame))
            FrameAccess.write(frame, slots[offset], PromptTag(Language.currentState(this)))
            return null
        }
        val tag = operands[0].execute(frame)
        val action = operands[1].execute(frame)
        val state = operands[2].execute(frame)
        if (name == "prompt#") {
            val result = try { site.prompt(frame, tag, action, state, shape) }
            catch (cut: DelimitedCut) {
                throw cut.append(frame, DelimitedTupleStep(AstTupleDestination(shape, slots, offset), this))
            }
            shape.consume(frame, result, slots, offset)
            return null
        }
        requireVoidCarrier(state)
        throw DelimitedCut(DelimitedControl.tag(this, tag), action, shape, SynchronousMasking.current(this), this)
            .append(frame, DelimitedTupleStep(AstTupleDestination(shape, slots, offset), this))
    }
}

internal class DelimitedIOBoundary(private val name: String, private val shape: TupleShape,
                                  @field:Children private var operands: Array<Expr>,
                                  language: Language, metrics: Metrics) : Expr() {
    @Child private var site = DelimitedActionSite(language, metrics)
    init { representation = shape.proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing {
        CompilerDirectives.transferToInterpreterAndInvalidate()
        fault("$name requires a tuple destination")
    }
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val action = operands[0].execute(frame)
        val handler = if (name == "catch#") operands[1].execute(frame) else null
        val state = operands.last().execute(frame)
        val result = try {
            if (name == "catch#") site.caught(frame, action, handler, state, shape)
            else site.masked(frame, action, state, shape, when (name) {
                "maskAsyncExceptions#" -> MaskingState.MASKED_INTERRUPTIBLE
                "maskUninterruptible#" -> MaskingState.MASKED_UNINTERRUPTIBLE
                else -> MaskingState.UNMASKED
            })
        } catch (cut: DelimitedCut) {
            throw cut.append(frame, DelimitedTupleStep(AstTupleDestination(shape, slots, offset), this))
        }
        shape.consume(frame, result, slots, offset)
        return null
    }
}
