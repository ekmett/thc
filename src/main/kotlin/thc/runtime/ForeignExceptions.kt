// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.exception.AbstractTruffleException
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.interop.ExceptionType
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.nodes.Node
import thc.Language
import java.lang.ref.WeakReference

/** Private control transfers must never become ordinary Haskell exceptions. */
internal interface InternalGuestControl

/** One program retains the exact genuine GHC boxer/projector selected when linked. */
internal class ForeignExceptionBridge(val unit: String, private val boxId: String,
    private val projectId: String, private val binding: (String) -> Any?,
    private val layout: (String) -> DataLayout) {
    @Volatile private var resolvedProjector: ForeignExceptionRegistry.Projector? = null
    @Synchronized fun projector(closure: Closure): ForeignExceptionRegistry.Projector =
        resolvedProjector ?: ForeignExceptionRegistry.Projector(this, closure, layout(CoreExceptionPayload.TYPE)).also { resolvedProjector = it }
    fun box(): Any? = binding(boxId)
    fun project(): Any? = binding(projectId)

    companion object {
        fun bind(data: Map<String, Any?>, binding: (String) -> Any?,
            layout: (String) -> DataLayout): ForeignExceptionBridge? {
            val proof = data["selectedForeignExceptionBridge"] as? Map<*, *> ?: return null
            return ForeignExceptionBridge(proof["unit"] as String, proof["box"] as String,
                proof["project"] as String, binding, layout)
        }
    }
}

/** Projectors remain paired with program/unit identity after caught exceptions escape. */
internal class ForeignExceptionRegistry {
    class Projector(val bridge: ForeignExceptionBridge, val closure: Closure, val exceptionLayout: DataLayout)
    private val projectors = ArrayList<WeakReference<Projector>>()
    @Synchronized @TruffleBoundary fun register(projector: Projector) {
        projectors.removeAll { it.get() == null }
        if (projectors.none { it.get() === projector }) projectors.add(WeakReference(projector))
    }
    @Synchronized @TruffleBoundary fun snapshot(): List<Projector> {
        projectors.removeAll { it.get() == null }
        return projectors.mapNotNull { it.get() }
    }
}

/** No foreign code runs when Haskell displays this opaque value. Metadata is
 * queried only by the explicit IO operations and then cached as inert text. */
internal class ForeignFailure(val owner: Language.State, val original: AbstractTruffleException,
    val projector: ForeignExceptionRegistry.Projector) {
    class Text(val value: String?)
    val text = java.util.concurrent.atomic.AtomicReferenceArray<Text?>(2)
}

/** Framework classification is separate from arbitrary foreign metadata access. */
internal object ForeignExceptionPolicy {
    fun kind(type: ExceptionType): Boolean =
        type == ExceptionType.RUNTIME_ERROR || type == ExceptionType.PARSE_ERROR
    fun host(cause: Throwable): Boolean = cause !is VirtualMachineError && cause !is ThreadDeath &&
        cause !is LinkageError && cause !is InterruptedException && cause !is java.util.concurrent.CancellationException &&
        cause !is RuntimeFault && cause !is InternalGuestControl && cause !is GuestException
}

/** Cold exception translation. Normal foreign calls allocate no bridge object. */
internal class ForeignExceptionAccess : Node() {
    @Child private var force = Force(Metrics(false))
    @Child private var apply = Dispatch.create(1, false, Metrics(false))
    @Child private var interop = InteropLibrary.getFactory().createDispatched(3)
    private val descriptor = FrameDescriptor.newBuilder().build()

    private fun invoke(frame: VirtualFrame, function: Closure, value: Any?): Any? =
        force.execute(frame, apply.execute(frame, function, arrayOf(value)))

    /** Classification uses only Truffle's exception protocol. It must not call
     * user display/message/cause/stack accessors or recursively translate failure. */
    internal fun eligible(error: AbstractTruffleException): Boolean {
        if (error is GuestException || error is InternalGuestControl) return false
        val owner = Language.currentState(this)
        return try {
            if (owner.env.isHostException(error)) {
                val host = owner.env.asHostException(error)
                if (!ForeignExceptionPolicy.host(host)) return false
            }
            interop.isException(error) && ForeignExceptionPolicy.kind(interop.getExceptionType(error))
        } catch (failure: Exception) {
            // Broken ordinary protocol implementations preserve the original
            // failure. Cancellation and private transfers must never be hidden.
            if (!ForeignExceptionPolicy.host(failure)) throw failure
            if (failure is AbstractTruffleException &&
                (!owner.env.isHostException(failure) ||
                    !ForeignExceptionPolicy.host(owner.env.asHostException(failure)))) throw failure
            false
        }
    }

    /** Called after carrier permission and pointer borrows have been restored,
     * before an opaque Throwable could pass through Force's failure memoization. */
    @TruffleBoundary
    fun raise(error: AbstractTruffleException): Nothing {
        val owner = Language.currentState(this)
        val bridge = (rootNode as? GuestRoot)?.foreignExceptionBridge
        if (bridge == null || owner.foreignExceptionNormalization.get() || !eligible(error)) throw error
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray<Any?>(), descriptor)
        val payload = withoutLifting {
            val box = ApplicationKt.requireClosure(force.execute(frame, bridge.box()))
            val project = ApplicationKt.requireClosure(force.execute(frame, bridge.project()))
            val retained = bridge.projector(project)
            owner.foreignExceptionRegistry.register(retained)
            invoke(frame, box, ForeignFailure(owner, error, retained))
        }
        throw GuestException(payload, this, true)
    }

    /** Only a compatible public exit asks the real Haskell dictionary whether a
     * lazy SomeException contains our type. Projection failure remains guest failure. */
    @TruffleBoundary
    fun escaping(failure: GuestException): Nothing {
        if (!failure.someException) throw failure
        val owner = Language.currentState(this)
        val projectors = owner.foreignExceptionRegistry.snapshot()
        if (projectors.isNotEmpty()) {
            val frame = Truffle.getRuntime().createVirtualFrame(emptyArray<Any?>(), descriptor)
            withoutLifting {
                // Nominally equal types still have separate authenticated storage
                // domains in separate programs. Normalize only the proven exception,
                // then route to its exact domain before asking the genuine dictionary.
                val payload = force.execute(frame, failure.payload)
                val project = projectors.singleOrNull { it.exceptionLayout.matches(payload) }
                if (project != null) {
                    val origin = invoke(frame, project.closure, payload)
                    if (origin is ForeignFailure && origin.owner === owner) throw origin.original
                }
            }
        }
        throw failure
    }

    private inline fun <T> withoutLifting(action: () -> T): T {
        val flag = Language.currentState(this).foreignExceptionNormalization
        val active = flag.get()
        flag.set(true)
        try { return action() } finally { flag.set(active) }
    }

    @TruffleBoundary
    fun text(handle: ManagedAddress, selector: Long, index: Long, state: Any?): Long {
        TupleResultsKt.requireVoidCarrier(state)
        val owner = Language.currentState(this)
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray<Any?>(), descriptor)
        val value = force.execute(frame, owner.stablePointers.dereference(handle)) as? ForeignFailure
            ?: fault("Expected a genuine ForeignException origin")
        if (value.owner !== owner || selector !in 0L..1L) fault("Foreign exception metadata owner or selector")
        val field = selector.toInt()
        if (value.text.get(field) == null) {
            // Metadata may execute foreign code and may fail. Do not mark the
            // field inspected until that operation successfully returns. Concurrent
            // first readers may query independently; first successful publication
            // wins, and no monitor is held while foreign code executes.
            val previous = owner.threads.enterForeign(ForeignSafety.SAFE)
            val result = try {
                try {
                    when (field) {
                        0 -> if (interop.hasMetaObject(value.original)) {
                            val meta = interop.getMetaObject(value.original)
                            interop.asString(interop.getMetaQualifiedName(meta))
                        } else null
                        else -> if (interop.hasExceptionMessage(value.original))
                            interop.asString(interop.getExceptionMessage(value.original)) else null
                    }
                } finally { owner.threads.leaveForeign(previous) }
            } catch (error: AbstractTruffleException) { raise(error) }
            value.text.compareAndSet(field, null, ForeignFailure.Text(result))
        }
        val text = value.text.get(field)!!.value ?: return -1L
        val count = text.codePointCount(0, text.length)
        if (index == -1L) return count.toLong()
        if (index < 0 || index >= count) fault("Foreign exception text index")
        return text.codePointAt(text.offsetByCodePoints(0, index.toInt())).toLong()
    }
}
