// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.TruffleSafepoint
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.ContinuationResult
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ControlFlowException
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.RootNode
import thc.Language
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/** An unlifted ThreadId# carries the actual JVM thread ID and its owning context. */
internal class GuestThreadId(val javaId: Long, val owner: GuestThreads) {
    override fun equals(other: Any?): Boolean =
        other is GuestThreadId && owner === other.owner && javaId == other.javaId
    override fun hashCode(): Int = 31 * System.identityHashCode(owner) + javaId.hashCode()
}

/** Exact GHC 9.14.1 Core contract for the initial Java-thread primitives. */
internal object CoreGuestThreads {
    private val lifted = listOf("BoxedRep (Just Lifted)")
    private val unlifted = listOf("BoxedRep (Just Unlifted)")
    private fun state(rep: CoreRepresentation) = !rep.isAggregate && !rep.isVector &&
        rep.kind == CoreKind.VOID && rep.primReps == emptyList<String>()
    private fun thread(rep: CoreRepresentation) = !rep.isAggregate && !rep.isVector &&
        rep.kind == CoreKind.OBJECT && rep.primReps == unlifted
    private fun lifted(rep: CoreRepresentation) = !rep.isAggregate && !rep.isVector &&
        rep.kind in setOf(CoreKind.DATA, CoreKind.CLOSURE, CoreKind.OBJECT) && rep.primReps == lifted
    private fun action(rep: CoreRepresentation) = rep.kind == CoreKind.CLOSURE && lifted(rep)
    private fun threadResult(rep: CoreRepresentation): Boolean {
        val fields = rep.components
        return rep.kind == CoreKind.UNKNOWN && rep.isTuple && !rep.isSum && !rep.isVector &&
            rep.primReps == unlifted && fields?.size == 2 && state(fields[0]) && thread(fields[1])
    }

    fun validate(name: String, arguments: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        val valid = when (name) {
            "fork#" -> arguments.size == 2 && action(arguments[0]) && state(arguments[1]) &&
                flags == listOf(true, false) && threadResult(result)
            "myThreadId#" -> arguments.size == 1 && state(arguments[0]) &&
                flags == listOf(false) && threadResult(result)
            "killThread#" -> arguments.size == 3 && thread(arguments[0]) && lifted(arguments[1]) &&
                state(arguments[2]) && flags == listOf(false, true, false) && state(result)
            else -> false
        }
        if (!valid) throw RuntimeFault("$name: expected exact GHC ThreadId#, lazy payload and State# contract")
    }
}

/** Discards a fork action's lifted result and releases its tuple loan. */
private class UncaughtForkAsync(val request: AsyncRequest) : ControlFlowException()

private class ForkDestination(shape: TupleShape, private val language: Language) : TupleDestination(shape) {
    override fun consume(frame: VirtualFrame, node: Node, result: Any?) {
        val continuation = when (result) {
            is ContinuationResult -> result
            is TailYield -> result.continuation
            else -> null
        }
        if (continuation != null) {
            val request = AsyncContinuations.request(continuation)
                ?: fault("Fork action suspended without an async request")
            throw UncaughtForkAsync(request)
        }
        if (result === TupleComplete) {
            val pool = language.handoffState.get().results
            val storage = pool.completed()
            try {
                if (storage.layout !== shape.layout) fault("Fork action returned the wrong tuple layout")
            } finally { pool.releaseChecked(storage, shape.layout) }
        } else {
            val storage = result as? HandoffStorage ?: fault("Fork action returned no tuple")
            if (storage.layout !== shape.layout) fault("Fork action returned the wrong tuple layout")
        }
    }
}

/** One child entry owns its dispatch tree; no caller frame or handoff loan crosses threads. */
private class ForkActionRoot(private val language: Language, initialShape: TupleShape?) :
    RootNode(language, FrameLayout().build()) {
    @Child private var force = Force(Metrics(false), true)
    @Child private var dispatch: TupleDispatch? = initialShape?.let {
        TupleDispatch(ForkDestination(it, language), Metrics(false), 1, false)
    }
    override fun execute(frame: VirtualFrame): Any {
        frame.setLong(FrameLayout.BLOOM_FILTER, 0L)
        val input = frame.arguments[0]
        val action = try { requireClosure(force.execute(frame, input)) }
        catch (suspended: ThunkSuspended) {
            // The fork child has no enclosing continuation consumer. The
            // shared thunk retains its captured body for a later evaluator.
            throw UncaughtForkAsync(suspended.asyncRequest
                ?: fault("fork# action head suspended without an async request"))
        }
        catch (blocked: AsyncBlocked) {
            // This child was waiting for another evaluator of the same head.
            // It does not own the thunk or a continuation to resume.
            throw UncaughtForkAsync(blocked.request)
        }
        val shape = GuestThreadOps.actionResult(action)
        val callee = dispatch ?: insert(TupleDispatch(ForkDestination(shape, language), Metrics(false), 1, false))
            .also { dispatch = it }
        callee.execute(frame, action, arrayOf(Unit))
        return Unit
    }
    override fun getName() = "THC fork action"
}

internal object GuestThreadOps {
    private val lifted = listOf("BoxedRep (Just Lifted)")
    internal fun actionResult(action: Closure): TupleShape {
        val root = action.target.rootNode as? BytecodeRoot
            ?: fault("fork# requires an async-capable bytecode action")
        if (!root.isAsyncEnabled) fault("fork# action has no async continuation capture")
        val shape = root.tupleResult ?: fault("fork# action has no tuple result proof")
        val fields = shape.proof.components
        if (fields?.size != 2 || fields[0].kind != CoreKind.VOID ||
            fields[0].primReps != emptyList<String>() || fields[1].primReps != lifted)
            fault("fork# action must return State# and a lifted result")
        return shape
    }

    @JvmStatic @TruffleBoundary fun myThreadId(node: Node): GuestThreadId {
        val threads = Language.currentState(node).threads
        return GuestThreadId(threads.currentId(), threads)
    }

    /** Start a real Truffle thread and wait only until its guest registration is visible. */
    @JvmStatic @TruffleBoundary fun fork(node: Node, action: Any?): GuestThreadId {
        val state = Language.currentState(node)
        val threads = state.threads
        // A known closure retains immediate contract validation. A lazy action
        // must be forced after the child enters its own guest thread instead.
        val shape = when (action) {
            is Closure -> actionResult(action)
            is Thunk -> null
            else -> fault("fork# requires a lazy state-transformer action")
        }
        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(node)
        val root = ForkActionRoot(language, shape).callTarget
        val inheritedMask = state.maskingState.get()
        val ready = CountDownLatch(1)
        val registrationFailure = AtomicReference<Throwable?>()
        val child = state.env.newTruffleThreadBuilder(Runnable {
            var registered = false
            try {
                threads.enterCurrent(inheritedMask)
                registered = true
                ready.countDown()
                root.call(action)
            } catch (uncaught: UncaughtForkAsync) {
                // No catch# accepted the payload. The child terminates, so the
                // sender may complete without treating a control yield as a tuple.
                uncaught.request.acknowledge()
            } catch (failure: Throwable) {
                if (!registered) registrationFailure.set(failure)
                throw failure
            } finally {
                if (!registered) ready.countDown()
                if (registered) threads.leaveCurrent()
            }
        }).build()
        child.start()
        TruffleSafepoint.setBlockedThreadInterruptibleFunction(node, awaitRegistration, ready)
        registrationFailure.get()?.let { throw RuntimeFault("fork# child registration failed: ${it.javaClass.simpleName}") }
        return GuestThreadId(child.threadId(), threads)
    }

    /** Enqueue exactly once. A bytecode retry must retain this token, never call beginKill again. */
    @JvmStatic @TruffleBoundary fun beginKill(node: Node, id: Any?, payload: Any?): AsyncRequest {
        val threads = Language.currentState(node).threads
        val target = id as? GuestThreadId ?: fault("killThread# requires a ThreadId#")
        if (target.owner !== threads) fault("ThreadId# belongs to another guest context")
        return threads.send(target.javaId, payload)
    }

    /** A self-target returns for the immediately following poll; it must not await itself. */
    @JvmStatic @TruffleBoundary fun finishKill(node: Node, request: AsyncRequest) {
        if (request.target === Thread.currentThread()) return
        when (request.await(node)) {
            AsyncRequestState.ACKNOWLEDGED, AsyncRequestState.TARGET_FINISHED -> Unit
            AsyncRequestState.FAILED -> fault("killThread# delivery failed")
            AsyncRequestState.CANCELLED -> fault("killThread# was cancelled")
            AsyncRequestState.PENDING, AsyncRequestState.CLAIMED, AsyncRequestState.PAUSED ->
                error("Await returned before async completion")
        }
    }

    private val awaitRegistration = TruffleSafepoint.InterruptibleFunction<CountDownLatch, Unit> {
        it.await()
    }
}
