// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node
import thc.Language

/** PARTIAL weak support: retain registrations until explicit finalization/close, not guest GC. */
internal class ManagedWeaks {
    private class Handle(val owner: ManagedWeaks)
    private class Payload(val key: Any, val value: Any, val action: Any?,
        val callbacks: MutableList<() -> Unit> = ArrayList())
    private val live = HashMap<Handle, Payload>()
    private var closed = false

    private fun handle(value: Any?): Handle {
        val handle = value as? Handle ?: fault("Expected a context-owned Weak#")
        if (closed || handle.owner !== this) fault("Disposed or foreign Weak# context")
        return handle
    }

    @Synchronized @TruffleBoundary
    fun make(key: Any?, value: Any?, action: Any?): Any {
        if (closed) fault("Weak# context is disposed")
        if (key == null || value == null) fault("Weak# key and value require boxed carriers")
        // Never force a key, value, or action. Private handles have identity equality.
        return Handle(this).also { live[it] = Payload(key, value, action) }
    }

    @Synchronized @TruffleBoundary
    fun dereference(value: Any?): WeakResult = live[handle(value)]?.let { WeakResult(1L, it.value) } ?: dead

    /** The exact original two one-argument callbacks use a zero environment flag. */
    @TruffleBoundary
    fun addCFinalizer(function: ManagedAddress, address: ManagedAddress, flag: Long,
        weak: Any?, provider: SulongCbits): Long {
        val callback = function.finalizerFunction() ?: fault("Expected an original C function label")
        callback.requireOwner(provider)
        if (flag != 0L) fault("Original C finalizer requires a one-address ABI")
        // The zero-flag RTS form ignores environment; lowering checks its Addr# carrier.
        if (address !== ManagedAddress.nullAddress()) address.requireByteRegion(0)
        return addCallback(weak) { callback.invoke(address) }
    }

    @Synchronized @TruffleBoundary
    internal fun addCallback(value: Any?, callback: () -> Unit): Long {
        val payload = live[handle(value)] ?: return 0L
        payload.callbacks.add(0, callback) // RTS prepends: explicit finalize visits newest first.
        return 1L
    }

    @TruffleBoundary
    fun finalize(value: Any?): WeakResult {
        // Publish DEAD before calling C; neither Sulong nor guest code runs under this monitor.
        val payload = synchronized(this) { live.remove(handle(value)) } ?: return dead
        payload.callbacks.forEach { it() }
        return payload.action?.let { WeakResult(1L, it) } ?: dead
    }

    /** rts_setMainThread consumes the KEY (ThreadId#), not the boxed ThreadId value. */
    @Synchronized @TruffleBoundary
    fun mainThreadKey(value: Any?, threads: GuestThreads): MainThreadWeakKey {
        val weak = handle(value)
        val payload = live[weak] ?: fault("Main thread requires a live Weak#")
        threadKey(payload, threads)
        return MainThreadWeakKey(this, weak, threads)
    }

    private fun threadKey(payload: Payload, threads: GuestThreads): GuestThreadId {
        val key = payload.key as? GuestThreadId ?: fault("Main thread Weak# key is not a ThreadId#")
        if (key.owner !== threads) fault("Main thread Weak# key belongs to another context")
        return key
    }

    // Capability backing operation: no key or callback escapes. The thread service
    // checks the original canonical carrier; the returned number is still only a snapshot.
    @Synchronized @TruffleBoundary
    internal fun mainThreadJavaId(value: Any, threads: GuestThreads): Long? {
        if (closed) return null
        val payload = live[handle(value)] ?: return null
        return threads.liveJavaId(threadKey(payload, threads))
    }

    @Synchronized fun retainedCount(): Int = live.size
    @Synchronized fun close() { closed = true; live.clear() }

    companion object {
        private val dead = WeakResult(0L, null) // Invalid payload, never a fabricated no-op action.
        @JvmStatic fun current(node: Node?): ManagedWeaks = Language.currentState(node).weaks
    }
}

internal class WeakResult(val flag: Long, val value: Any?)

/** Retain this capability, not a permanent ThreadId#/Java-ID snapshot. */
internal class MainThreadWeakKey internal constructor(private val owner: ManagedWeaks,
    private val weak: Any, private val threads: GuestThreads) {
    fun liveJavaId(): Long? = owner.mainThreadJavaId(weak, threads)
}

internal enum class WeakOp(val primitive: String, private val arguments: List<String>,
                           private val resultRoles: List<String>) {
    MAKE("mkWeak#", listOf("boxed", "boxed", "action", "state"), listOf("state", "weak")),
    MAKE_PLAIN("mkWeakNoFinalizer#", listOf("boxed", "boxed", "state"), listOf("state", "weak")),
    ADD_C_FINALIZER("addCFinalizerToWeak#", listOf("address", "address", "flag", "address", "weak", "state"),
        listOf("state", "flag")),
    DEREFERENCE("deRefWeak#", listOf("weak", "state"), listOf("state", "flag", "boxed")),
    FINALIZE("finalizeWeak#", listOf("weak", "state"), listOf("state", "flag", "action"));

    fun validate(actual: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        if (actual.size != arguments.size || flags.size != arguments.size ||
            actual.indices.any { !matches(actual[it], arguments[it]) ||
                flags[it] != (actual[it].primReps == listOf(LIFTED)) })
            fault("Weak primitive argument representation mismatch: $primitive")
        val fields = result.components
        if (result.kind != CoreKind.UNKNOWN || !result.isTuple || result.isSum || result.isVector ||
            fields!!.size != resultRoles.size || fields.indices.any { !matches(fields[it], resultRoles[it]) } ||
            result.primReps != fields.flatMap { it.primReps!! })
            fault("Weak primitive result representation mismatch: $primitive")
    }

    fun validateBindings(actual: List<CoreRepresentation>, stored: List<CoreRepresentation?>) {
        for (index in actual.indices) {
            val binding = stored[index] ?: continue
            if (binding.primReps == null) continue
            val occurrence = actual[index]
            val refined = if (binding.primReps == listOf("BoxedRep Nothing") &&
                occurrence.primReps?.singleOrNull() in setOf(LIFTED, UNLIFTED))
                binding.copy(primReps = occurrence.primReps) else binding
            if (refined.isAggregate || refined.isVector || refined.primReps != occurrence.primReps ||
                refined.kind != CoreKind.UNKNOWN && !matches(refined, arguments[index]))
                fault("Weak primitive argument contradicts its binding proof: $primitive")
        }
    }

    fun validateAction(signature: Pair<List<CoreRepresentation>, CoreRepresentation>?) {
        if (this != MAKE || signature == null) return
        val (inputs, result) = signature
        val fields = result.components
        if (inputs.size != 1 || !matches(inputs[0], "state") || result.kind != CoreKind.UNKNOWN ||
            !result.isTuple || result.isSum || result.isVector || fields!!.size != 2 ||
            !matches(fields[0], "state") || !matches(fields[1], "boxed") ||
            fields[1].primReps != listOf(LIFTED) || result.primReps != fields[1].primReps)
            fault("mkWeak# finalizer requires State# -> (# State#, lifted value #)")
    }

    companion object {
        private const val LIFTED = "BoxedRep (Just Lifted)"
        private const val UNLIFTED = "BoxedRep (Just Unlifted)"
        fun named(name: String): WeakOp? = entries.firstOrNull { it.primitive == name }
        private fun matches(proof: CoreRepresentation, role: String): Boolean =
            !proof.isAggregate && !proof.isVector && when (role) {
                "state" -> proof.kind == CoreKind.VOID && proof.primReps == emptyList<String>()
                "weak" -> proof.kind == CoreKind.OBJECT && proof.primReps == listOf(UNLIFTED)
                "flag" -> proof.kind == CoreKind.LONG && proof.primReps == listOf("IntRep")
                "address" -> proof.kind == CoreKind.ADDRESS && proof.primReps == listOf("AddrRep")
                "action" -> proof.kind in setOf(CoreKind.CLOSURE, CoreKind.OBJECT) && proof.primReps == listOf(LIFTED)
                else -> proof.kind in setOf(CoreKind.DATA, CoreKind.CLOSURE, CoreKind.OBJECT) &&
                    proof.primReps?.singleOrNull() in setOf(LIFTED, UNLIFTED)
            }
    }
}

internal class WeakExpression(private val operation: WeakOp, @field:Children private val operands: Array<Expr>) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("Weak# tuple requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val first = operands[0].execute(frame)
        val registry = ManagedWeaks.current(this)
        if (operation == WeakOp.MAKE || operation == WeakOp.MAKE_PLAIN) {
            val value = operands[1].execute(frame)
            val action = if (operation == WeakOp.MAKE) operands[2].execute(frame)
                ?: fault("mkWeak# requires a finalizer carrier") else null
            requireVoidCarrier(operands.last().execute(frame))
            FrameAccess.writeObject(frame, slots[offset], registry.make(first, value, action))
        } else if (operation == WeakOp.ADD_C_FINALIZER) {
            val function = first as? ManagedAddress ?: fault("Expected a C function Addr#")
            val address = operands[1].executeRequiredAddress(frame)
            val flag = operands[2].executeLong(frame)
            operands[3].executeRequiredAddress(frame)
            val weak = operands[4].execute(frame)
            requireVoidCarrier(operands[5].execute(frame))
            val provider = Language.currentState(this).cbits()
            FrameAccess.writeLong(frame, slots[offset], registry.addCFinalizer(function, address,
                flag, weak, provider))
        } else {
            requireVoidCarrier(operands[1].execute(frame))
            val result = if (operation == WeakOp.FINALIZE) registry.finalize(first) else registry.dereference(first)
            FrameAccess.writeLong(frame, slots[offset], result.flag)
            FrameAccess.writeObject(frame, slots[offset + 1], result.value)
        }
        return null
    }
}
