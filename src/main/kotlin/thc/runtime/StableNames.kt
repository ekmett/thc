// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import thc.Language

/** Canonical identity tokens which do not keep their referents alive. */
internal class StableNames {
    private class Key(value: Any, queue: ReferenceQueue<Any>?) : WeakReference<Any>(value, queue) {
        private val hash = System.identityHashCode(value)
        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean = this === other ||
            other is Key && get()?.let { it === other.get() } == true
    }
    private class Name(val owner: StableNames, val hash: Long)
    private val queue = ReferenceQueue<Any>()
    private val names = HashMap<Key, Name>()
    private var nextHash = 1L
    private var closed = false

    @Synchronized @TruffleBoundary
    fun make(value: Any?): Any {
        if (closed || nextHash <= 0) fault("StableName context is closed or exhausted")
        if (value == null) fault("StableName# requires a boxed referent")
        while (true) names.remove(queue.poll() ?: break)
        // Never evaluate the referent or use its structural equality/hash code.
        val key = Key(value, queue)
        return names.getOrPut(key) { Name(this, nextHash++) }
    }

    @Synchronized @TruffleBoundary
    fun hash(value: Any?): Long {
        val name = value as? Name ?: fault("Expected a StableName#")
        if (closed || name.owner !== this) fault("Disposed or foreign StableName# context")
        return name.hash
    }

    @Synchronized fun close() {
        closed = true
        names.clear()
        while (queue.poll() != null) { /* Release queued keys too. */ }
    }

    companion object {
        @JvmStatic fun current(node: Node?): StableNames = Language.currentState(node).stableNames
    }
}

internal enum class StableNameOp(val primitive: String) {
    MAKE("makeStableName#"), HASH("stableNameToInt#");

    fun validate(arguments: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        fun scalar(rep: CoreRepresentation) = !rep.isAggregate && !rep.isVector
        fun state(rep: CoreRepresentation) = scalar(rep) && rep.kind == CoreKind.VOID
        fun name(rep: CoreRepresentation) = scalar(rep) && rep.kind == CoreKind.OBJECT &&
            rep.primReps == listOf("BoxedRep (Just Unlifted)")
        fun boxed(rep: CoreRepresentation) = scalar(rep) &&
            rep.kind in setOf(CoreKind.OBJECT, CoreKind.DATA, CoreKind.CLOSURE) &&
            rep.primReps?.singleOrNull() in setOf("BoxedRep (Just Lifted)", "BoxedRep (Just Unlifted)")
        val valid = when (this) {
            MAKE -> arguments.size == 2 && boxed(arguments[0]) && state(arguments[1]) &&
                flags == listOf(arguments[0].primReps == listOf("BoxedRep (Just Lifted)"), false) &&
                result.isTuple && result.components?.size == 2 &&
                state(result.components[0]) && name(result.components[1])
            HASH -> arguments.size == 1 && name(arguments[0]) && flags == listOf(false) &&
                scalar(result) && result.kind == CoreKind.LONG
        }
        if (!valid) fault("StableName# primitive carrier or shape mismatch: $primitive")
    }

    companion object { fun named(name: String): StableNameOp? = entries.firstOrNull { it.primitive == name } }
}

internal class MakeStableName(@field:Child private var value: Expr, @field:Child private var state: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Nothing = fault("StableName# tuple requires a destination")
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val referent = value.execute(frame)
        requireVoidCarrier(state.execute(frame))
        FrameAccess.write(frame, slots[offset], StableNames.current(this).make(referent))
        return null
    }
}

internal class HashStableName(@field:Child private var value: Expr) : Expr() {
    override fun execute(frame: VirtualFrame): Any = executeLong(frame)
    override fun executeLong(frame: VirtualFrame): Long = StableNames.current(this).hash(value.execute(frame))
}
