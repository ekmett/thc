// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.TruffleSafepoint
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node
import thc.Language
import java.util.IdentityHashMap
import java.util.WeakHashMap

/** Target allocation accounting, not a GHC heap image or a JVM GC exclusion zone. */
internal class ManagedCompact(val owner: ManagedCompacts, requested: Long) {
    internal val identity = Any()
    internal val objects = ArrayList<Any>()
    private var blockSize = blockSize(requested)
    private var capacity = blockSize
    private var available = blockSize - HEADER_BYTES
    private var adding = false
    internal var generation = 0L
        private set

    @Synchronized internal fun <T> snapshot(action: (List<Any>) -> T): T {
        if (adding) fault("Cannot serialize a compact region while adding to it")
        return action(objects)
    }

    @Synchronized fun size(): Long = capacity
    @Synchronized fun resize(requested: Long) {
        if (adding) fault("Concurrent compact-region mutation")
        blockSize = blockSize(requested)
        capacity = Math.addExact(capacity, blockSize)
        available = blockSize - HEADER_BYTES
        generation++
    }
    @Synchronized internal fun begin() {
        if (adding) fault("Concurrent or reentrant compactAdd# on one region")
        adding = true
    }
    @Synchronized internal fun finish(copied: List<Pair<Any, Long>>) {
        for ((value, bytes) in copied) {
            if (bytes > available) {
                val next = maxOf(blockSize, blockSize(bytes))
                capacity = Math.addExact(capacity, next)
                available = next - HEADER_BYTES
            }
            available -= bytes
            objects.add(value)
        }
        generation++
    }
    @Synchronized internal fun end() { adding = false }

    companion object {
        private const val HEADER_BYTES = 128L
        private fun blockSize(requested: Long): Long {
            if (requested < 0 || requested > Int.MAX_VALUE.toLong() - 4096)
                fault("Compact allocation size outside managed target domain")
            return maxOf(4096L, (requested + HEADER_BYTES + 4095) / 4096 * 4096)
        }
    }
}

/** Weak keys never retain compacted data. The value is only a region identity
 * token, never the region (which would make weak-key/value cycles leak).
 * Every admitted key has JVM identity equality, not guest Eq/Ord semantics. */
internal class ManagedCompacts {
    private val membership = WeakHashMap<Any, Any>()
    fun require(value: Any?): ManagedCompact {
        val region = value as? ManagedCompact ?: fault("Expected Compact#")
        if (region.owner !== this) fault("Compact# belongs to another context")
        return region
    }
    @TruffleBoundary @Synchronized fun contains(region: ManagedCompact, value: Any?): Boolean =
        membership[completedBoxedIdentity(value)] === region.identity
    @TruffleBoundary @Synchronized fun containsAny(value: Any?): Boolean =
        membership.containsKey(completedBoxedIdentity(value))
    @TruffleBoundary @Synchronized internal fun record(region: ManagedCompact, values: List<Pair<Any, Long>>) {
        values.forEach { (value, _) -> membership[value] = region.identity }
    }
}

/** Cold graph traversal with an explicit work stack, so a long list does not
 * consume the Java stack. Cyclic constructor shells remain private until all
 * their final fields have been initialized and the entire operation succeeds. */
internal class CompactCopyNode(metrics: Metrics, private val failures: Array<GlobalBinding>) : Node() {
    @Child private var force = Force(metrics)
    private class Copy(val value: Any?, val store: (Any?) -> Unit)

    fun execute(frame: VirtualFrame, region: ManagedCompact, root: Any?, sharing: Boolean): Any? {
        CompilerDirectives.transferToInterpreter()
        val registry = region.owner
        val known = IdentityHashMap<Any, Any>()
        val path = IdentityHashMap<Any, Boolean>()
        val allocated = ArrayList<Pair<Any, Long>>()
        val pending = ArrayDeque<Any>()
        var result: Any? = null
        pending.addLast(Copy(root) { result = it })
        region.begin()
        try {
            while (pending.isNotEmpty()) {
                TruffleSafepoint.poll(this)
                val task = pending.removeLast()
                if (task !is Copy) { path.remove(task); continue }
                val value = force.execute(frame, task.value)
                if (value == null) fault("Null guest value in compact region")
                if (registry.contains(region, value)) { task.store(value); continue }
                if (sharing && known.containsKey(value)) { task.store(known[value]); continue }
                if (!sharing && path.containsKey(value)) fault("Cyclic data requires compactAddWithSharing#")
                fun publish(copy: Any, bytes: Long) {
                    if (sharing) known[value] = copy else { path[value] = true; pending.addLast(value) }
                    allocated.add(copy to bytes)
                    task.store(copy)
                }
                when (value) {
                    is DataValue -> {
                        val layout = value.layout
                        // Shared nullary constructors are static values, not region allocations.
                        if (layout.arity == 0) { task.store(value); continue }
                        val copy = layout.allocate()
                        publish(copy, layout.compactBytes())
                        for (index in layout.arity - 1 downTo 0) {
                            if (layout.compactPointer(index)) pending.addLast(Copy(layout.read(value, index)) {
                                layout.initialize(copy, index, it)
                            }) else layout.copyCompactScalar(value, copy, index)
                        }
                    }
                    is ManagedAllocation -> {
                        if (value.isPinned) fail(1)
                        val copy = ManagedAllocation.immutable(value.copyBytesOut(0, value.size), value.addressWidth)
                        publish(copy, 16L + copy.size)
                    }
                    is ByteArray -> publish(value.copyOf(), 16L + value.size)
                    is Array<*> -> {
                        @Suppress("UNCHECKED_CAST") val array = value as Array<Any?>
                        if (!ManagedArray.isFrozen(array)) fail(2)
                        val copy = ManagedArray.freeze(arrayOfNulls(array.size))
                        publish(copy, 24L + 8L * copy.size)
                        for (index in array.indices.reversed()) pending.addLast(Copy(array[index]) { copy[index] = it })
                    }
                    is SmallArrayStorage -> {
                        if (!value.frozen) fail(2)
                        val copy = ManagedSmallArray.freeze(SmallArrayStorage(arrayOfNulls(value.logicalSize)))
                        publish(copy, 16L + 8L * copy.logicalSize)
                        for (index in 0 until value.logicalSize) pending.addLast(Copy(value.elements[index]) {
                            copy.elements[index] = it
                        })
                    }
                    is Closure -> fail(0)
                    else -> fail(2)
                }
            }
            region.finish(allocated)
            registry.record(region, allocated)
            return result
        } finally { region.end() }
    }
    private fun fail(index: Int): Nothing = throw GuestException(failures[index].read(), this)
}

internal enum class CompactOp(val primitive: String, private val roles: List<String>, val result: String) {
    NEW("compactNew#", listOf("long", "state"), "region"),
    RESIZE("compactResize#", listOf("region", "long", "state"), "state"),
    ADD("compactAdd#", listOf("region", "lifted", "state"), "lifted"),
    ADD_SHARING("compactAddWithSharing#", listOf("region", "lifted", "state"), "lifted"),
    CONTAINS("compactContains#", listOf("region", "lifted", "state"), "long"),
    CONTAINS_ANY("compactContainsAny#", listOf("lifted", "state"), "long"),
    SIZE("compactSize#", listOf("region", "state"), "long");

    val adds: Boolean get() = this == ADD || this == ADD_SHARING
    fun validate(arguments: List<CoreRepresentation>, flags: List<*>, proof: CoreRepresentation) {
        fun matches(value: CoreRepresentation, role: String): Boolean = value.present && !value.isAggregate && !value.isVector && when (role) {
            "state" -> value.kind == CoreKind.VOID
            "long" -> value.kind == CoreKind.LONG
            "region" -> value.kind == CoreKind.OBJECT && value.primReps == listOf("BoxedRep (Just Unlifted)")
            else -> value.kind in setOf(CoreKind.DATA, CoreKind.CLOSURE, CoreKind.OBJECT) &&
                value.primReps == listOf("BoxedRep (Just Lifted)")
        }
        if (arguments.size != roles.size || flags != roles.map { it == "lifted" } ||
            roles.indices.any { !matches(arguments[it], roles[it]) }) fault("Invalid $primitive arguments")
        val valid = if (this == RESIZE) matches(proof, "state") else proof.isTuple && proof.components?.size == 2 &&
            matches(proof.components[0], "state") && matches(proof.components[1], result) &&
            proof.primReps == proof.components.flatMap { it.primReps!! }
        if (!valid) fault("Invalid $primitive result")
    }
    companion object {
        val failures = arrayOf("cannotCompactFunction", "cannotCompactPinned", "cannotCompactMutable")
            .map { "ghc-internal:GHC.Internal.IO.Exception.$it" }.toTypedArray()
        fun named(name: String): CompactOp? = entries.firstOrNull { it.primitive == name }
    }
}

internal class CompactExpression(private val op: CompactOp, @field:Children private var operands: Array<Expr>,
    metrics: Metrics, failures: Array<GlobalBinding>) : Expr() {
    @Child private var copier = if (op.adds) CompactCopyNode(metrics, failures) else null
    override fun execute(frame: VirtualFrame): Any? {
        if (op != CompactOp.RESIZE) fault("Compact primitive requires tuple destination")
        val registry = Language.currentState(this).compactRegions
        val region = registry.require(operands[0].execute(frame))
        val size = operands[1].executeRequiredLong(frame)
        requireVoidCarrier(operands[2].execute(frame))
        region.resize(size)
        return Unit
    }
    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        val registry = Language.currentState(this).compactRegions
        when (op) {
            CompactOp.NEW -> {
                val size = operands[0].executeRequiredLong(frame)
                requireVoidCarrier(operands[1].execute(frame))
                FrameAccess.write(frame, slots[offset], ManagedCompact(registry, size))
            }
            CompactOp.SIZE -> {
                val region = registry.require(operands[0].execute(frame))
                requireVoidCarrier(operands[1].execute(frame))
                FrameAccess.writeLong(frame, slots[offset], region.size())
            }
            CompactOp.CONTAINS_ANY -> {
                val value = operands[0].execute(frame)
                requireVoidCarrier(operands[1].execute(frame))
                FrameAccess.writeLong(frame, slots[offset], if (registry.containsAny(value)) 1 else 0)
            }
            CompactOp.CONTAINS, CompactOp.ADD, CompactOp.ADD_SHARING -> {
                val region = registry.require(operands[0].execute(frame))
                val value = operands[1].execute(frame)
                requireVoidCarrier(operands[2].execute(frame))
                if (op == CompactOp.CONTAINS) FrameAccess.writeLong(frame, slots[offset], if (registry.contains(region, value)) 1 else 0)
                else FrameAccess.write(frame, slots[offset], copier!!.execute(frame, region, value, op == CompactOp.ADD_SHARING))
            }
            else -> fault("Not a tuple compact operation")
        }
        return null
    }
}
