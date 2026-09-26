// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.IdentityHashMap
import java.util.UUID
import java.util.WeakHashMap
import java.util.zip.CRC32

/** A real managed byte image, not the GHC heap ABI. Layout handles are metadata
 * of the originating context; graph payloads and backreferences live in bytes.
 * No exported image retains its source objects. Cross-context images fail closed. */
internal class CompactImages(private val regions: ManagedCompacts, private val heap: HeapAddresses) {
    private class Export(val generation: Long, val blocks: List<ManagedAddress>)
    private class Import {
        val blocks = ArrayList<ManagedAllocation>()
        var size = 0
    }
    internal class Fixed(val region: ManagedCompact, val root: ManagedAddress)
    private class InvalidImage : RuntimeException(null, null, false, false)
    private val identity = UUID.randomUUID()
    private val exports = WeakHashMap<ManagedCompact, Export>()
    private val layoutIds = IdentityHashMap<DataLayout, Long>()
    private val layouts = HashMap<Long, DataLayout>()
    // Like the original raw allocation primop, pending blocks must be finalized
    // exactly once. The context releases abandoned imports on disposal.
    private val imports = IdentityHashMap<ManagedAllocation, Import>()
    private var closed = false
    private fun requireOpen() { if (closed) fault("Compact image context is closed") }
    private fun invalid(): Nothing = throw InvalidImage()

    @Synchronized @TruffleBoundary
    fun first(region: ManagedCompact): ManagedAddress {
        requireOpen(); regions.require(region)
        return region.snapshot { values ->
            val prior = exports[region]
            if (prior != null && prior.generation == region.generation) prior.blocks.first()
            else {
                val bytes = encode(values)
                val blocks = (bytes.indices step BLOCK_BYTES).map { offset ->
                    ManagedAddress.fromAllocation(ManagedAllocation.immutable(
                        bytes.copyOfRange(offset, minOf(bytes.size, offset + BLOCK_BYTES)), 8))
                }
                exports[region] = Export(region.generation, blocks)
                blocks.first()
            }
        }
    }

    @Synchronized @TruffleBoundary
    fun next(region: ManagedCompact, current: ManagedAddress): ManagedAddress {
        requireOpen(); regions.require(region)
        return region.snapshot {
            val image = exports[region] ?: fault("Compact block was not exported by this region")
            if (image.generation != region.generation) fault("Compact region changed during block iteration")
            val index = image.blocks.indexOfFirst { it.sameLocation(current) }
            if (index < 0) fault("Compact block belongs to another region")
            image.blocks.getOrNull(index + 1) ?: ManagedAddress.nullAddress()
        }
    }

    @Synchronized @TruffleBoundary
    fun allocate(size: Long, previous: ManagedAddress): ManagedAddress {
        requireOpen()
        if (size <= 0 || size > MAX_IMAGE_BYTES) fault("Compact import block size outside managed target domain")
        val chain = if (previous === ManagedAddress.nullAddress()) Import() else {
            val allocation = previous.cbitsOwner()
            val known = allocation?.let(imports::get) ?: fault("Previous compact import block is unknown or consumed")
            if (previous.cbitsOffset() != 0L || known.blocks.last() !== allocation)
                fault("Compact import blocks must be appended at the chain tail")
            known
        }
        if (size > MAX_IMAGE_BYTES - chain.size) fault("Compact import image exceeds managed target domain")
        val allocation = ManagedAllocation.mutable(size, 8, true)
        chain.blocks.add(allocation); chain.size += size.toInt()
        imports[allocation] = chain
        return ManagedAddress.fromAllocation(allocation)
    }

    @Synchronized @TruffleBoundary
    fun fixup(first: ManagedAddress, oldRoot: ManagedAddress): Fixed {
        requireOpen()
        val allocation = first.cbitsOwner()
        val chain = allocation?.let(imports::get) ?: fault("Compact import block is unknown or already consumed")
        if (first.cbitsOffset() != 0L || chain.blocks.first() !== allocation)
            fault("compactFixupPointers# requires the first import block")
        chain.blocks.forEach(imports::remove)
        val region = ManagedCompact(regions, BLOCK_BYTES.toLong())
        fun failed() = Fixed(region, ManagedAddress.nullAddress())
        val handle = oldRoot.heapHandle() ?: return failed()
        if (handle.owner !== heap) return failed()
        heap.require(handle)
        val bytes = ByteArray(chain.size)
        var offset = 0
        for (block in chain.blocks) {
            val copy = block.copyBytesOut(0, block.size)
            copy.copyInto(bytes, offset); offset += copy.size
        }
        return try {
            val (values, oldIds) = decode(bytes)
            val index = oldIds.indexOf(handle.id)
            val root = if (index >= 0) values[index] else handle.value.get()?.takeIf {
                it is DataValue && it.layout.arity == 0
            } ?: return failed()
            val owned = values.filterNot { it is DataValue && it.layout.arity == 0 }.map { it to bytesUsed(it) }
            region.begin()
            try { region.finish(owned); regions.record(region, owned) } finally { region.end() }
            Fixed(region, heap.address(root))
        } catch (_: InvalidImage) { failed() }
          catch (_: IOException) { failed() }
          catch (_: IllegalArgumentException) { failed() }
    }

    private fun bytesUsed(value: Any): Long = when (value) {
        is DataValue -> value.layout.compactBytes()
        is ManagedAllocation -> 16 + value.size
        is ByteArray -> 16L + value.size
        is Array<*> -> 24L + 8L * value.size
        is SmallArrayStorage -> 16L + 8L * value.logicalSize
        else -> invalid()
    }
    private fun children(value: Any): List<Any?> = when (value) {
        is DataValue -> (0 until value.layout.arity).filter(value.layout::compactPointer).map { value.layout.read(value, it) }
        is Array<*> -> value.asList()
        is SmallArrayStorage -> value.elements.asList().take(value.logicalSize)
        else -> emptyList()
    }
    private fun layoutId(layout: DataLayout): Long = layoutIds[layout] ?: (layouts.size.toLong() + 1).also {
        layoutIds[layout] = it; layouts[it] = layout
    }

    private fun encode(roots: List<Any>): ByteArray {
        val indices = IdentityHashMap<Any, Int>()
        val nodes = ArrayList<Any>()
        fun visit(raw: Any?) {
            val value = completedBoxedIdentity(raw) ?: fault("Null in compact image")
            if (value is Thunk) fault("Unevaluated value in compact image")
            if (!indices.containsKey(value)) { indices[value] = nodes.size; nodes.add(value) }
        }
        roots.forEach(::visit)
        var index = 0
        while (index < nodes.size) children(nodes[index++]).forEach(::visit)
        fun reference(value: Any?): Int = indices[completedBoxedIdentity(value)] ?: fault("Missing compact image node")
        val buffer = ByteArrayOutputStream()
        val output = DataOutputStream(buffer)
        output.writeInt(MAGIC); output.writeInt(VERSION)
        output.writeLong(identity.mostSignificantBits); output.writeLong(identity.leastSignificantBits)
        output.writeInt(nodes.size)
        for (value in nodes) {
            output.writeLong(heap.handle(value).id)
            when (value) {
                is DataValue -> {
                    output.writeByte(DATA); output.writeLong(layoutId(value.layout))
                    for (field in 0 until value.layout.arity) if (value.layout.compactPointer(field))
                        output.writeInt(reference(value.layout.read(value, field)))
                    else value.layout.writeCompactScalar(value, field, output)
                }
                is ManagedAllocation -> {
                    output.writeByte(ALLOCATION); output.writeInt(value.addressWidth)
                    output.writeInt(value.size.toInt()); output.write(value.copyBytesOut(0, value.size))
                }
                is ByteArray -> { output.writeByte(BYTES); output.writeInt(value.size); output.write(value) }
                is Array<*> -> {
                    output.writeByte(ARRAY); output.writeInt(value.size)
                    value.forEach { output.writeInt(reference(it)) }
                }
                is SmallArrayStorage -> {
                    output.writeByte(SMALL_ARRAY); output.writeInt(value.logicalSize)
                    for (field in 0 until value.logicalSize) output.writeInt(reference(value.elements[field]))
                }
                else -> fault("Unsupported compact image object")
            }
            if (buffer.size() > MAX_IMAGE_BYTES - 8) fault("Compact image exceeds managed target domain")
        }
        val payload = buffer.toByteArray()
        output.writeLong(CRC32().also { it.update(payload) }.value)
        return buffer.toByteArray()
    }

    private fun decode(bytes: ByteArray): Pair<List<Any>, LongArray> {
        if (bytes.size < 36) invalid()
        val checksum = DataInputStream(ByteArrayInputStream(bytes, bytes.size - 8, 8)).readLong()
        if (CRC32().also { it.update(bytes, 0, bytes.size - 8) }.value != checksum) invalid()
        val input = DataInputStream(ByteArrayInputStream(bytes, 0, bytes.size - 8))
        if (input.readInt() != MAGIC || input.readInt() != VERSION ||
            input.readLong() != identity.mostSignificantBits || input.readLong() != identity.leastSignificantBits) invalid()
        val count = input.readInt()
        if (count < 0 || count > input.available() / 9) invalid()
        val nodes = arrayOfNulls<Any>(count)
        val references = arrayOfNulls<IntArray>(count)
        val oldIds = LongArray(count)
        val unique = HashSet<Long>()
        fun size(width: Int): Int = input.readInt().also { if (it < 0 || it > input.available() / width) invalid() }
        fun reference(): Int = input.readInt().also { if (it !in 0 until count) invalid() }
        for (index in 0 until count) {
            oldIds[index] = input.readLong().also { if (it <= 0 || !unique.add(it)) invalid() }
            nodes[index] = when (val tag = input.readUnsignedByte()) {
                DATA -> {
                    val layout = layouts[input.readLong()] ?: invalid()
                    val value = if (layout.arity == 0) layout.create(emptyArray()) else layout.allocate()
                    val fields = IntArray(layout.arity) { -1 }
                    for (field in fields.indices) if (layout.compactPointer(field)) fields[field] = reference()
                    else layout.readCompactScalar(value, field, input)
                    references[index] = fields
                    value
                }
                ALLOCATION -> {
                    val width = input.readInt(); if (width != 4 && width != 8) invalid()
                    ManagedAllocation.immutable(input.readNBytes(size(1)), width)
                }
                BYTES -> input.readNBytes(size(1))
                ARRAY, SMALL_ARRAY -> {
                    val length = size(4)
                    references[index] = IntArray(length) { reference() }
                    if (tag == ARRAY) ManagedArray.freeze(arrayOfNulls(length))
                    else ManagedSmallArray.freeze(SmallArrayStorage(arrayOfNulls(length)))
                }
                else -> invalid()
            }
        }
        if (input.available() != 0) invalid()
        for (index in nodes.indices) references[index]?.let { fields ->
            when (val value = nodes[index]) {
                is DataValue -> for (field in fields.indices) if (fields[field] >= 0) {
                    val target = nodes[fields[field]]
                    if (!value.layout.acceptsCompactPointer(field, target)) invalid()
                    value.layout.initialize(value, field, target)
                }
                is Array<*> -> {
                    @Suppress("UNCHECKED_CAST") val array = value as Array<Any?>
                    for (field in fields.indices) array[field] = nodes[fields[field]]
                }
                is SmallArrayStorage -> for (field in fields.indices) value.elements[field] = nodes[fields[field]]
                else -> invalid()
            }
        }
        return nodes.map { it ?: invalid() } to oldIds
    }

    @Synchronized fun close() {
        closed = true; exports.clear(); imports.clear(); layoutIds.clear(); layouts.clear()
    }
    companion object {
        private const val MAGIC = 0x54484343 // THCC
        private const val VERSION = 1
        private const val BLOCK_BYTES = 64 * 1024
        private const val MAX_IMAGE_BYTES = 256 * 1024 * 1024L
        private const val DATA = 1
        private const val ALLOCATION = 2
        private const val BYTES = 3
        private const val ARRAY = 4
        private const val SMALL_ARRAY = 5
    }
}
