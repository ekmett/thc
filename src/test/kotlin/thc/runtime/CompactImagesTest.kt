// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language

class CompactImagesTest {
    private fun withLanguage(action: (Language, Language.State) -> Unit) = Context.newBuilder("thc").build().use { context ->
        context.initialize("thc"); context.enter()
        try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null), Language.currentState()) }
        finally { context.leave() }
    }
    private fun newRegion(state: Language.State, vararg values: Any): ManagedCompact {
        val region = ManagedCompact(state.compactRegions, 4096)
        val owned = values.map { it to 64L }
        region.begin()
        try { region.finish(owned); state.compactRegions.record(region, owned) } finally { region.end() }
        return region
    }
    private fun snapshot(images: CompactImages, region: ManagedCompact): List<ByteArray> {
        val blocks = ArrayList<ByteArray>()
        var block = images.first(region)
        while (block !== ManagedAddress.nullAddress()) {
            val bytes = ByteArray(block.availableBytes().toInt())
            block.copyToByteArray(bytes, 0, bytes.size.toLong())
            blocks.add(bytes)
            block = images.next(region, block)
        }
        return blocks
    }
    private fun copyBlocks(images: CompactImages, bytes: List<ByteArray>): ManagedAddress {
        var first = ManagedAddress.nullAddress()
        var previous = first
        for (block in bytes) {
            val next = images.allocate(block.size.toLong(), previous)
            next.copyFromByteArray(block, 0, block.size.toLong())
            if (first === ManagedAddress.nullAddress()) first = next
            previous = next
        }
        return first
    }

    @Test fun opaqueAddressesKeepIdentityWithoutForcingRootingOrNativeProjection() = withLanguage { language, state ->
        val layout = DataLayout(language, "test:Leaf", "Leaf", arrayOf("IntRep"))
        val value = layout.create(arrayOf(19L))
        val address = state.heapAddresses.address(value)
        assertTrue(address.sameLocation(state.heapAddresses.address(value)))
        assertSame(value, state.heapAddresses.dereference(address))
        assertFalse(address.sameLocation(state.heapAddresses.address(layout.create(arrayOf(19L)))))
        assertFalse(address.sameLocation(ManagedAddress.nullAddress()))
        assertSame(address, address.plus(0))
        assertThrows(RuntimeFault::class.java) { address.plus(1) }
        assertThrows(RuntimeFault::class.java) { address.readWord8(0) }
        assertThrows(RuntimeFault::class.java) { address.toNativeBits() }
        assertThrows(RuntimeFault::class.java) { HeapAddresses().dereference(address) }
        var entered = false
        val thunk = Thunk(object : RootNode(language) {
            override fun execute(frame: VirtualFrame): Any { entered = true; return value }
        }.callTarget, null)
        assertThrows(RuntimeFault::class.java) { state.heapAddresses.address(thunk) }
        assertFalse(entered)
        thunk.value = value; thunk.state = 2
        assertTrue(address.sameLocation(state.heapAddresses.address(thunk)))
        // Deterministically exercise the weak-reference-cleared state, without
        // relying on a collector schedule or retaining the object through Addr#.
        state.heapAddresses.require(address).value.clear()
        assertThrows(RuntimeFault::class.java) { state.heapAddresses.dereference(address) }
    }

    @Test fun imageBytesRebuildSharingCyclesArraysAndExactScalarBits() = withLanguage { language, state ->
        val layout = DataLayout(language, "test:Node", "Node", arrayOf("IntRep", "FloatRep", "DoubleRep", "LiftedRep"))
        val original = layout.allocate()
        layout.initializeLong(original, 0, Long.MIN_VALUE)
        layout.initializeFloat(original, 1, Float.fromBits(0x7fc01234))
        layout.initializeDouble(original, 2, Double.fromBits(Long.MIN_VALUE))
        val storage = ManagedAllocation.immutable(byteArrayOf(-1, 0, 17), 4)
        val small = ManagedSmallArray.freeze(SmallArrayStorage(arrayOf(original, storage)))
        val array = ManagedArray.freeze(arrayOf<Any?>(original, original, small))
        layout.initialize(original, 3, array)
        val region = newRegion(state, original, array, small, storage)
        val pointer = state.heapAddresses.address(original)
        val bytes = snapshot(state.compactImages, region)
        state.heapAddresses.require(pointer).value.clear() // Import must not recover the source graph from the handle.
        val fixed = state.compactImages.fixup(copyBlocks(state.compactImages, bytes), pointer)
        val result = state.heapAddresses.dereference(fixed.root) as DataValue
        assertNotSame(original, result)
        assertEquals(Long.MIN_VALUE, layout.readLong(result, 0))
        assertEquals(0x7fc01234, layout.readFloat(result, 1).toRawBits())
        assertEquals(Long.MIN_VALUE, layout.readDouble(result, 2).toRawBits())
        val copiedArray = layout.read(result, 3) as Array<*>
        assertNotSame(array, copiedArray); assertSame(result, copiedArray[0]); assertSame(result, copiedArray[1])
        val copiedSmall = copiedArray[2] as SmallArrayStorage
        assertNotSame(small, copiedSmall); assertTrue(copiedSmall.frozen); assertSame(result, copiedSmall.elements[0])
        val copiedStorage = copiedSmall.elements[1] as ManagedAllocation
        assertNotSame(storage, copiedStorage); assertFalse(copiedStorage.isWritable)
        assertEquals(4, copiedStorage.addressWidth)
        assertArrayEquals(byteArrayOf(-1, 0, 17), copiedStorage.copyBytesOut(0, 3))
        assertTrue(state.compactRegions.contains(fixed.region, result))
        assertFalse(state.compactRegions.contains(fixed.region, original))
        assertFalse(fixed.root.sameLocation(pointer))
        assertArrayEquals(bytes[0], snapshot(state.compactImages, region)[0], "Export bytes stay unchanged")
    }

    @Test fun vectorPayloadsPreserveExactLaneBitsThroughImageBytes() = withLanguage { language, state ->
        for ((lanes, element) in listOf(4 to "FloatElemRep", 2 to "DoubleElemRep", 16 to "Word8ElemRep", 2 to "Int64ElemRep")) {
            val rep = "VecRep $lanes $element"
            val proof = mapOf("kind" to "vector", "primReps" to listOf(rep), "evaluated" to true,
                "vector" to mapOf("lanes" to lanes, "element" to element))
            val layout = DataLayout.fromFields(language, "test:$rep", "Vector", CoreFields(mapOf(
                "id" to "test:$rep", "arity" to 1, "fieldReps" to listOf(listOf(rep)),
                "fieldTypes" to listOf(proof), "strictFields" to listOf(true), "fieldLifted" to listOf(false))))
            val slots = FrameLayout()
            val slot = slots.bind("vector")
            val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), slots.build())
            val input = rawVectorTestValue(CoreRepresentations.parse(proof))
            FrameAccess.write(frame, slot, input)
            val original = layout.allocate()
            layout.initializeVector(original, 0, frame, intArrayOf(slot), 0)
            val region = newRegion(state, original)
            val fixed = state.compactImages.fixup(copyBlocks(state.compactImages, snapshot(state.compactImages, region)),
                state.heapAddresses.address(original))
            val result = state.heapAddresses.dereference(fixed.root) as DataValue
            layout.restoreVector(result, 0, frame, intArrayOf(slot), 0)
            val copied = frame.getObject(slot) as jdk.incubator.vector.Vector<*>
            assertArrayEquals(input.reinterpretAsBytes().toArray(), copied.reinterpretAsBytes().toArray(), rep)
        }
    }

    @Test fun multipleBlocksCopyRealBytesAndRejectWrongBlockChains() = withLanguage { _, state ->
        val bytes = ByteArray(200_000) { (it * 37).toByte() }
        val region = newRegion(state, bytes)
        val pointer = state.heapAddresses.address(bytes)
        val exported = snapshot(state.compactImages, region)
        assertTrue(exported.size > 1)
        val first = copyBlocks(state.compactImages, exported)
        val fixed = state.compactImages.fixup(first, pointer)
        val imported = state.heapAddresses.dereference(fixed.root) as ByteArray
        assertNotSame(bytes, imported); assertArrayEquals(bytes, imported)
        assertThrows(RuntimeFault::class.java) { state.compactImages.fixup(first, pointer) }
        val pending = state.compactImages.allocate(8, ManagedAddress.nullAddress())
        val next = state.compactImages.allocate(8, pending)
        assertThrows(RuntimeFault::class.java) { state.compactImages.allocate(8, pending) }
        assertThrows(RuntimeFault::class.java) { state.compactImages.fixup(next, pointer) }
        assertThrows(RuntimeFault::class.java) { state.compactImages.fixup(pending.plus(1), pointer) }
        for (size in listOf(-1L, 0L, Long.MAX_VALUE))
            assertThrows(RuntimeFault::class.java) { state.compactImages.allocate(size, ManagedAddress.nullAddress()) }
    }

    @Test fun corruptTruncatedAndForeignImagesFailWithoutPublishingAResult() = withLanguage { language, state ->
        val layout = DataLayout(language, "test:Leaf", "Leaf", arrayOf("IntRep"))
        val value = layout.create(arrayOf(71L))
        val region = newRegion(state, value)
        val pointer = state.heapAddresses.address(value)
        val bytes = snapshot(state.compactImages, region).single()
        fun malformed(edit: (java.nio.ByteBuffer) -> Unit): ByteArray = bytes.copyOf().also { copy ->
            val buffer = java.nio.ByteBuffer.wrap(copy)
            edit(buffer)
            buffer.putLong(copy.size - 8, java.util.zip.CRC32().also { it.update(copy, 0, copy.size - 8) }.value)
        }
        for (bad in listOf(bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }, bytes.copyOf(bytes.size - 1),
            malformed { it.putInt(24, Int.MAX_VALUE) }, malformed { it.put(36, 99.toByte()) },
            malformed { it.putLong(37, -1L) })) {
            val first = copyBlocks(state.compactImages, listOf(bad))
            val failed = state.compactImages.fixup(first, pointer)
            assertSame(ManagedAddress.nullAddress(), failed.root)
            assertTrue(failed.region.objects.isEmpty())
            assertThrows(RuntimeFault::class.java) { state.compactImages.fixup(first, pointer) }
        }
        val foreign = CompactImages(state.compactRegions, state.heapAddresses)
        try {
            assertSame(ManagedAddress.nullAddress(), foreign.fixup(copyBlocks(foreign, listOf(bytes)), pointer).root)
        } finally { foreign.close() }
        assertThrows(RuntimeFault::class.java) { state.compactImages.next(newRegion(state, value), state.compactImages.first(region)) }
        val first = state.compactImages.first(region)
        region.resize(8192)
        assertThrows(RuntimeFault::class.java) { state.compactImages.next(region, first) }
        val typed = DataLayout(language, "test:Typed", "Typed", arrayOf("LiftedRep"),
            arrayOf<Class<*>?>(DataValue::class.java))
        val holder = typed.create(arrayOf(value))
        val wrongCarrier = snapshot(state.compactImages, newRegion(state, holder, value, byteArrayOf(1))).single()
        // Header 28, old identity 8, node tag 1, layout id 8; the first field's
        // node index is at 45. Point it at ByteArray instead of DataValue.
        val payload = java.nio.ByteBuffer.wrap(wrongCarrier)
        payload.putInt(45, 2)
        payload.putLong(wrongCarrier.size - 8, java.util.zip.CRC32().also {
            it.update(wrongCarrier, 0, wrongCarrier.size - 8)
        }.value)
        val mismatch = state.compactImages.fixup(copyBlocks(state.compactImages, listOf(wrongCarrier)),
            state.heapAddresses.address(holder))
        assertSame(ManagedAddress.nullAddress(), mismatch.root)
    }
}
