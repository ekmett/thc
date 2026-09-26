// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.interop.InvalidBufferOffsetException
import com.oracle.truffle.api.interop.UnsupportedMessageException
import jdk.incubator.vector.ByteVector
import jdk.incubator.vector.ShortVector
import jdk.incubator.vector.IntVector
import jdk.incubator.vector.LongVector
import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.DoubleVector
import jdk.incubator.vector.VectorShape
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import thc.Language
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.ref.Reference
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.function.LongSupplier

class NativePinnedStorageTest {
    private inline fun inside(body: () -> Unit) {
        Context.newBuilder("thc").allowNativeAccess(true).build().use { context ->
            context.initialize("thc")
            context.enter()
            try { body() } finally { context.leave() }
        }
    }

    @Test fun pinnedAllocationsStartWithStableNativeStorageAtTheRequestedAlignment() {
        for (shift in 0..12) for (size in listOf(0L, 1L, 17L, 4097L)) {
            val alignment = 1L shl shift
            val storage = PinnedMemory.allocate(size, alignment)
            val segment = storage.nativeSegment()!!
            assertTrue(storage.isPinned)
            assertTrue(segment.isNative)
            assertEquals(size, storage.size)
            assertEquals(size, segment.byteSize())
            assertNotEquals(0L, segment.address())
            assertEquals(0L, segment.address() and (alignment - 1))
            assertSame(segment, storage.nativeSegment())
            assertThrows(RuntimeFault::class.java) { storage.rawBytesIfPointerFree() }
            Reference.reachabilityFence(storage)
        }
        val firstEmpty = PinnedMemory.allocate(0, 64)
        val secondEmpty = PinnedMemory.allocate(0, 64)
        assertNotEquals(firstEmpty.nativeSegment()!!.address(), secondEmpty.nativeSegment()!!.address())
        Reference.reachabilityFence(firstEmpty)
        Reference.reachabilityFence(secondEmpty)
        for (alignment in listOf(-1L, 0L, 3L, 17L, Long.MAX_VALUE))
            assertThrows(RuntimeFault::class.java) { PinnedMemory.allocate(16, alignment) }
        for (size in listOf(-1L, Int.MAX_VALUE.toLong() + 1, Long.MAX_VALUE))
            assertThrows(RuntimeFault::class.java) { PinnedMemory.allocate(size, 8) }
    }

    @Test fun ordinaryAllocationsKeepTheirOriginalHeapAliasAndDoNotBecomePinned() {
        val storage = ManagedAllocation.mutable(16, 8)
        val bytes = storage.rawBytesIfPointerFree()
        assertFalse(storage.isPinned)
        assertNull(storage.nativeSegment())
        assertSame(bytes, storage.rawBytesIfPointerFree())
        assertTrue(storage.ownsStorage(bytes))
        bytes[3] = 91
        assertEquals(91L, ManagedByteArray.readGuest(storage, 3, true))
        ManagedByteArray.writeInt32ByteOffsetGuest(storage, 5, 0x12345678)
        assertEquals(0x12345678L, ManagedByteArray.readInt32ByteOffset(bytes, 5))
        assertSame(storage, ManagedByteArray.freezeGuest(storage))
        assertFalse(storage.isPinned)
        assertNull(storage.nativeSegment())
    }

    @Test fun movingHeapArraysRejectNativeProjectionEvenWhenImmutable() = inside {
        val mutable = ManagedAllocation.mutable(16, 8)
        val immutable = ManagedAllocation.immutable(byteArrayOf(1, 2, 3, 4), 8)
        val interop = InteropLibrary.getUncached()
        for (storage in listOf(mutable, immutable)) {
            val address = ManagedAddress.fromGuestByteArray(storage)
            assertThrows(RuntimeFault::class.java) { address.toNativeBits() }
            assertNull(storage.nativeSegment())
            assertFalse(storage.isPinned)
            val buffer = CbitsBuffer(storage.exposeSegment().asByteBuffer(), storage.isWritable)
            interop.toNative(buffer)
            assertFalse(interop.isPointer(buffer))
            assertThrows(UnsupportedMessageException::class.java) { interop.asPointer(buffer) }
        }
        assertEquals(1L, immutable.readByte(0))
    }

    @Test fun freezingAndRepeatedNativeProjectionPreserveStorageIdentityAndOffsetAliases() = inside {
        val storage = PinnedMemory.allocate(32, 64)
        val segment = storage.nativeSegment()!!
        val base = ManagedAddress.fromGuestByteArray(storage)
        val frozen = ManagedByteArray.freezeGuest(storage)
        assertSame(storage, frozen)
        assertSame(segment, storage.nativeSegment())
        val registry = NativeAddresses.current(null)
        repeat(4) {
            assertEquals(segment.address(), base.toNativeBits())
            for (offset in listOf(0L, 1L, 15L, 31L, 32L)) {
                val alias = ManagedAddress.fromGuestByteArray(frozen).plus(offset)
                assertEquals(segment.address() + offset, alias.toNativeBits())
                val recovered = registry.recover(alias.toNativeBits())
                assertSame(storage, recovered.cbitsOwner())
                assertTrue(recovered.sameLocation(alias))
                assertEquals(alias.toNativeBits(), recovered.toNativeBits())
            }
        }
        ManagedByteArray.writeGuest(storage, 7, 93)
        assertEquals(93L, ManagedByteArray.readGuest(frozen, 7, true))
        assertEquals(93L, registry.recover(segment.address() + 7).readWord8(0))
        assertThrows(RuntimeFault::class.java) { base.plus(32).readWord8(0) }
        Reference.reachabilityFence(storage)
    }

    @Test fun bufferOnlyAndPointerCapableInteropViewsShareTheSameBytesWithoutPromotionCopies() = inside {
        val storage = PinnedMemory.allocate(32, 64)
        val base = ManagedAddress.fromGuestByteArray(storage)
        val interop = InteropLibrary.getUncached()
        val buffer = CbitsBuffer(storage.exposeSegment().asByteBuffer(), true)
        val pointer = CbitsBuffer(storage.exposeSegment().asByteBuffer(), true, LongSupplier { storage.size }, 7,
            nativeAddress = LongSupplier { base.toNativeBits() })
        assertTrue(interop.hasBufferElements(buffer))
        assertFalse(interop.isPointer(buffer))
        interop.toNative(buffer)
        assertFalse(interop.isPointer(buffer))
        assertThrows(UnsupportedMessageException::class.java) { interop.asPointer(buffer) }
        assertTrue(interop.isPointer(pointer))
        assertEquals(25L, interop.getBufferSize(pointer))
        val bits = base.toNativeBits()
        repeat(4) {
            interop.toNative(pointer)
            assertEquals(bits + 7, interop.asPointer(pointer))
            assertEquals(bits, storage.nativeSegment()!!.address())
        }
        interop.writeBufferInt(pointer, ByteOrder.nativeOrder(), 1, 0x12345678)
        assertEquals(0x12345678, interop.readBufferInt(buffer, ByteOrder.nativeOrder(), 8))
        assertEquals(0x12345678L, ManagedByteArray.readInt32Guest(storage, 2, false))
        ManagedByteArray.writeGuest(storage, 7, 0xe7)
        assertEquals(0xe7.toByte(), interop.readBufferByte(pointer, 0))
        assertThrows(InvalidBufferOffsetException::class.java) { interop.readBufferByte(pointer, 25) }
        Reference.reachabilityFence(pointer)
        Reference.reachabilityFence(buffer)
    }

    @Test fun libcMutationAndInteropWritesAreImmediatelyVisibleThroughEveryView() = inside {
        val storage = PinnedMemory.allocate(64, 64)
        val segment = storage.nativeSegment()!!
        val base = ManagedAddress.fromGuestByteArray(storage)
        val interop = InteropLibrary.getUncached()
        val buffer = CbitsBuffer(storage.exposeSegment().asByteBuffer(), true)
        storage.fill(0, 64, 0x11)
        val linker = Linker.nativeLinker()
        val memset = linker.downcallHandle(linker.defaultLookup().find("memset").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG))
        val bits = base.toNativeBits()
        val result = memset.invokeWithArguments(segment.asSlice(9, 13), 0xa5, 13L) as MemorySegment
        assertEquals(bits + 9, result.address())
        for (offset in 0L until 64L) {
            val expected = if (offset in 9L until 22L) 0xa5L else 0x11L
            assertEquals(expected, ManagedByteArray.readGuest(storage, offset, true))
            assertEquals(expected.toByte(), interop.readBufferByte(buffer, offset))
            assertEquals(expected, base.readWord8(offset))
        }
        interop.writeBufferLong(buffer, ByteOrder.nativeOrder(), 3, 0x0102030405060708L)
        assertEquals(0x0102030405060708L, segment.get(ValueLayout.JAVA_LONG_UNALIGNED, 3))
        ManagedByteArray.writeInt32ByteOffsetGuest(storage, 27, 0x89abcdefL)
        assertEquals(0x89abcdef.toInt(), segment.get(ValueLayout.JAVA_INT_UNALIGNED, 27))
        assertEquals(bits, base.toNativeBits())
        Reference.reachabilityFence(storage)
    }

    @Test fun onePinnedAllocationSupportsIndependentContextViewsAndOutlivesContextDisposal() {
        val storage = PinnedMemory.allocate(16, 64)
        val address = ManagedAddress.fromGuestByteArray(storage)
        val bits = storage.nativeSegment()!!.address()
        val interop = InteropLibrary.getUncached()
        lateinit var retainedBuffer: CbitsBuffer
        inside {
            val cbits = Language.currentState().cbits()
            retainedBuffer = cbits.buffer(address, allowNativePointer = false)
            assertFalse(interop.isPointer(retainedBuffer))
            assertEquals(bits, interop.asPointer(cbits.buffer(address, allowNativePointer = true)))
            interop.writeBufferByte(retainedBuffer, 2, 71)
        }
        // Closing one context does not close storage owned by a live ForeignPtr.
        assertEquals(71L, address.readWord8(2))
        inside {
            val cbits = Language.currentState().cbits()
            assertEquals(bits, interop.asPointer(cbits.buffer(address, allowNativePointer = true)))
            assertTrue(NativeAddresses.current(null).recover(bits + 2).sameLocation(address.plus(2)))
            val second = cbits.buffer(address, allowNativePointer = false)
            interop.writeBufferByte(second, 2, 93)
            assertEquals(93.toByte(), interop.readBufferByte(retainedBuffer, 2))
        }
        assertEquals(bits, storage.nativeSegment()!!.address())
        assertEquals(93L, storage.readByte(2))
        Reference.reachabilityFence(retainedBuffer)
    }

    @Test fun originalSulongMd5ReadsAndMutatesPinnedStorageWithoutChangingAnyAddress() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Windows uses its native MD5 provider")
        inside {
            val payload = "Native pinned storage stays shared across original C calls. ".repeat(3).toByteArray(Charsets.US_ASCII)
            val contextStorage = PinnedMemory.allocate(88, 64)
            val inputStorage = PinnedMemory.allocate(payload.size.toLong() + 16, 64)
            val outputStorage = PinnedMemory.allocate(16, 64)
            inputStorage.fill(0, inputStorage.size, 0x5a)
            payload.forEachIndexed { index, byte -> inputStorage.writeByte(index.toLong() + 7, byte.toLong()) }
            val context = ManagedAddress.fromGuestByteArray(contextStorage)
            val input = ManagedAddress.fromGuestByteArray(inputStorage).plus(7)
            val output = ManagedAddress.fromGuestByteArray(outputStorage)
            val outputAlias = ManagedByteArray.freezeGuest(outputStorage)
            val addresses = listOf(context, input, output)
            val bits = addresses.map(ManagedAddress::toNativeBits)
            val cbits = Language.currentState().cbits()
            val outputBuffer = cbits.buffer(output)
            val interop = InteropLibrary.getUncached()
            assertTrue(interop.isPointer(outputBuffer))
            assertEquals(bits[2], interop.asPointer(outputBuffer))
            cbits.init(context)
            assertEquals(bits, addresses.map(ManagedAddress::toNativeBits))
            cbits.update(context, input, 13)
            assertEquals(bits, addresses.map(ManagedAddress::toNativeBits))
            cbits.update(context, input.plus(13), payload.size - 13)
            assertEquals(bits, addresses.map(ManagedAddress::toNativeBits))
            cbits.finish(output, context)
            assertEquals(bits, addresses.map(ManagedAddress::toNativeBits))
            val expected = MessageDigest.getInstance("MD5").digest(payload)
            assertArrayEquals(expected, ByteArray(16) { ManagedByteArray.readGuest(outputAlias, it.toLong(), true).toByte() })
            assertArrayEquals(expected, ByteArray(16) { interop.readBufferByte(outputBuffer, it.toLong()) })
            assertArrayEquals(expected, outputStorage.nativeSegment()!!.toArray(ValueLayout.JAVA_BYTE))
            assertEquals(0x5aL, inputStorage.readByte(6))
            assertEquals(0x5aL, inputStorage.readByte(payload.size.toLong() + 7))
            Reference.reachabilityFence(addresses)
        }
    }

    private fun checkAtomicAliases(width: Int, arrayOp: AtomicIntArrayOp, addressOp: AtomicAddressOp) {
        val storage = PinnedMemory.allocate(width * 3L, 64)
        storage.fill(0, storage.size, 0x5a)
        storage.fill(width.toLong(), width.toLong(), 0xff)
        val alias = ManagedAddress.fromGuestByteArray(storage).plus(width.toLong())
        assertEquals(-1L, storage.atomicInt(1, -1, 7, arrayOp))
        assertEquals(7L, addressOp.numeric(alias, 7, -1))
        assertEquals(-1L, storage.atomicInt(1, -1, 19, arrayOp))
        assertEquals(19L, addressOp.numeric(alias, 20, 99))
        assertEquals(19L, storage.atomicInt(1, 20, 99, arrayOp))
        for (offset in 0L until width.toLong()) {
            assertEquals(0x5aL, storage.readByte(offset))
            assertEquals(0x5aL, storage.readByte(width * 2L + offset))
        }
        val exactTail = PinnedMemory.allocate(width.toLong(), 64)
        exactTail.fill(0, width.toLong(), 0xff)
        val unsignedOld = if (width == 8) -1L else (1L shl (width * 8)) - 1
        assertEquals(unsignedOld, addressOp.numeric(ManagedAddress.fromGuestByteArray(exactTail), -1, 23))
        assertEquals(23L, exactTail.atomicInt(0, 23, 29, arrayOp))
    }

    @Test fun wordAtomicsShareNativeStorageBetweenArrayAndAddressAliases() {
        checkAtomicAliases(4, AtomicIntArrayOp.CAS32, AtomicAddressOp.CAS32)
        checkAtomicAliases(8, AtomicIntArrayOp.CAS64, AtomicAddressOp.CAS64)
        val storage = PinnedMemory.allocate(16, 64)
        val alias = ManagedAddress.fromGuestByteArray(storage).plus(8)
        storage.atomicInt(1, 42, 0, AtomicIntArrayOp.WRITE)
        assertEquals(42L, AtomicAddressOp.ADD.numeric(alias, 5))
        assertEquals(47L, storage.atomicInt(1, 0, 0, AtomicIntArrayOp.READ))
        assertEquals(47L, storage.atomicInt(1, 9, 0, AtomicIntArrayOp.SUB))
        assertEquals(38L, AtomicAddressOp.EXCHANGE.numeric(alias, 93))
        assertEquals(93L, storage.nativeSegment()!!.get(ValueLayout.JAVA_LONG, 8))
    }

    @Test fun narrowNativeAtomicsTouchOnlyTheirElementAndShareArrayAliases() {
        assumeTrue(System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") in setOf("amd64", "x86_64"),
            "The native byte/short CAS helper currently targets Linux x86_64")
        checkAtomicAliases(1, AtomicIntArrayOp.CAS8, AtomicAddressOp.CAS8)
        checkAtomicAliases(2, AtomicIntArrayOp.CAS16, AtomicAddressOp.CAS16)
    }

    @Test fun shrinkUpdatesExistingBoundedViewsWithoutMovingTheirNativeStorage() = inside {
        val storage = PinnedMemory.allocate(32, 64)
        val base = ManagedAddress.fromGuestByteArray(storage)
        val alias = base.plus(7)
        val interop = InteropLibrary.getUncached()
        val buffer = CbitsBuffer(storage.exposeSegment().asByteBuffer(), true, LongSupplier { storage.size }, 7,
            nativeAddress = LongSupplier { base.toNativeBits() })
        val bits = interop.asPointer(buffer)
        storage.shrink(16)
        assertEquals(16L, storage.size)
        assertEquals(9L, interop.getBufferSize(buffer))
        assertEquals(bits, interop.asPointer(buffer))
        assertEquals(9L, alias.availableBytes())
        for (offset in listOf(-1L, 9L, Long.MAX_VALUE))
            assertThrows(InvalidBufferOffsetException::class.java) { interop.readBufferByte(buffer, offset) }
        assertThrows(InvalidBufferOffsetException::class.java) {
            interop.writeBufferLong(buffer, ByteOrder.nativeOrder(), 2, 1)
        }
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.readIntGuest(storage, 2) }
        assertSame(storage, NativeAddresses.current(null).recover(base.toNativeBits() + 16).cbitsOwner())
        assertThrows(RuntimeFault::class.java) { NativeAddresses.current(null).recover(base.toNativeBits() + 17).readWord8(0) }
        storage.shrink(5)
        assertEquals(0L, interop.getBufferSize(buffer))
        assertEquals(bits, interop.asPointer(buffer))
        assertThrows(InvalidBufferOffsetException::class.java) { interop.readBufferByte(buffer, 0) }
        assertThrows(RuntimeFault::class.java) { alias.readWord8(0) }
    }

    @Test fun resizeShrinksPinnedStorageInPlaceButGrowthAllocatesAnUnpinnedPrefixCopy() {
        val original = PinnedMemory.allocate(24, 4096)
        for (offset in 0L until original.size) original.writeByte(offset, offset * 7)
        val originalSegment = original.nativeSegment()!!
        val originalBits = originalSegment.address()
        assertSame(original, original.resized(24))
        val shortened = original.resized(11)
        assertSame(original, shortened)
        assertTrue(shortened.isPinned)
        assertSame(originalSegment, shortened.nativeSegment())
        assertEquals(originalBits, shortened.nativeSegment()!!.address())
        assertEquals(0L, originalBits and 4095)
        assertEquals(11L, shortened.size)
        assertEquals((0L until 11L).map { it * 7 }, (0L until 11L).map(shortened::readByte))

        val grown = shortened.resized(80)
        assertNotSame(shortened, grown)
        assertFalse(grown.isPinned)
        assertNull(grown.nativeSegment())
        assertEquals(80L, grown.size)
        assertEquals((0L until 11L).map { it * 7 }, (0L until 11L).map(grown::readByte))
        val grownAgain = grown.resized(128)
        assertNotSame(grown, grownAgain)
        assertFalse(grownAgain.isPinned)
        assertNull(grownAgain.nativeSegment())
        assertEquals(128L, grownAgain.size)
        assertEquals((0L until 11L).map { it * 7 }, (0L until 11L).map(grownAgain::readByte))

        val pinned = PinnedMemory.allocate(8, 4096)
        val pinnedBits = pinned.nativeSegment()!!.address()
        val empty = pinned.resized(0)
        assertSame(pinned, empty)
        assertTrue(empty.isPinned)
        assertEquals(0L, empty.size)
        assertEquals(pinnedBits, empty.nativeSegment()!!.address())
    }

    @Test fun guestScalarsAndVectorSpeciesAccessNativeStorageAtBothKindsOfOffset() {
        val storage = PinnedMemory.allocate(256, 64)
        val segment = storage.nativeSegment()!!
        ManagedByteArray.writeIntGuest(storage, 3, Long.MIN_VALUE + 19, true)
        assertEquals(Long.MIN_VALUE + 19, ManagedByteArray.readIntGuest(storage, 3, true))
        assertEquals(Long.MIN_VALUE + 19, segment.get(ValueLayout.JAVA_LONG_UNALIGNED, 3))
        ManagedByteArray.writeInt16ByteOffsetGuest(storage, 1, 0xffff)
        assertEquals(-1L, ManagedByteArray.readInt16ByteOffsetGuest(storage, 1, false))
        assertEquals(65535L, ManagedByteArray.readInt16ByteOffsetGuest(storage, 1, true))
        ManagedByteArray.writeInt32ByteOffsetGuest(storage, 5, 0xffffffffL)
        assertEquals(-1L, ManagedByteArray.readInt32ByteOffsetGuest(storage, 5, false))
        assertEquals(0xffffffffL, ManagedByteArray.readInt32ByteOffsetGuest(storage, 5, true))
        ManagedByteArray.writeFloatByteOffsetGuest(storage, 9, -0.0f)
        assertEquals(Int.MIN_VALUE, ManagedByteArray.readFloatByteOffsetGuest(storage, 9).toRawBits())
        ManagedByteArray.writeDoubleByteOffsetGuest(storage, 15, -0.0)
        assertEquals(Long.MIN_VALUE, ManagedByteArray.readDoubleByteOffsetGuest(storage, 15).toRawBits())
        for (vectorBytes in listOf(16, 32, 64)) for (scalarOffset in listOf(false, true)) {
            val shape = VectorShape.forBitSize(vectorBytes * 8)
            val bytes = ByteVector.broadcast(ByteVector.SPECIES_128.withShape(shape), 0xa5.toByte())
            ManagedByteArray.writeByteVectorGuest(storage, 1, bytes, scalarOffset, vectorBytes)
            assertEquals(bytes, ManagedByteArray.readByteVectorGuest(storage, 1, scalarOffset, vectorBytes))
            val shorts = ShortVector.broadcast(ShortVector.SPECIES_128.withShape(shape), 0x89ab.toShort())
            ManagedByteArray.writeShortVectorGuest(storage, 1, shorts, scalarOffset, vectorBytes)
            assertEquals(shorts, ManagedByteArray.readShortVectorGuest(storage, 1, scalarOffset, vectorBytes))
            val ints = IntVector.broadcast(IntVector.SPECIES_128.withShape(shape), 0x12345678)
            ManagedByteArray.writeInt32VectorGuest(storage, 1, ints, scalarOffset, vectorBytes)
            assertEquals(ints, ManagedByteArray.readInt32VectorGuest(storage, 1, scalarOffset, vectorBytes))
            ManagedByteArray.writeWord32VectorGuest(storage, 1, ints, scalarOffset, vectorBytes)
            assertEquals(ints, ManagedByteArray.readWord32VectorGuest(storage, 1, scalarOffset, vectorBytes))
            val longs = LongVector.broadcast(LongVector.SPECIES_128.withShape(shape), 0x0102030405060708L)
            ManagedByteArray.writeLongVectorGuest(storage, 1, longs, scalarOffset, vectorBytes)
            assertEquals(longs, ManagedByteArray.readLongVectorGuest(storage, 1, scalarOffset, vectorBytes))
            val floats = FloatVector.broadcast(FloatVector.SPECIES_128.withShape(shape), -1.25f)
            ManagedByteArray.writeFloatVectorGuest(storage, 1, floats, scalarOffset, vectorBytes)
            assertEquals(floats, ManagedByteArray.readFloatVectorGuest(storage, 1, scalarOffset, vectorBytes))
            val doubles = DoubleVector.broadcast(DoubleVector.SPECIES_128.withShape(shape), 3.125)
            ManagedByteArray.writeDoubleVectorGuest(storage, 1, doubles, scalarOffset, vectorBytes)
            assertEquals(doubles, ManagedByteArray.readDoubleVectorGuest(storage, 1, scalarOffset, vectorBytes))
            val offset = if (scalarOffset) 8L else vectorBytes.toLong()
            assertEquals(3.125, segment.get(ValueLayout.JAVA_DOUBLE_UNALIGNED, offset))
        }
        for (index in listOf(-1L, 32L, Long.MAX_VALUE))
            assertThrows(RuntimeFault::class.java) { ManagedByteArray.readIntGuest(storage, index) }
        for (offset in listOf(-1L, 249L, Long.MAX_VALUE))
            assertThrows(RuntimeFault::class.java) { ManagedByteArray.writeIntGuest(storage, offset, 1, true) }
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.readLongVectorGuest(storage, Long.MAX_VALUE, true) }
        assertThrows(RuntimeFault::class.java) {
            ManagedByteArray.writeInt32VectorGuest(storage, 0, IntVector.zero(IntVector.SPECIES_256), false, 16)
        }
    }

    @Test fun derivedAddressKeepsAutomaticNativeStorageLiveAfterTheOriginalVariableIsCleared() {
        var original: ManagedAllocation? = PinnedMemory.allocate(24, 64)
        original!!.writeByte(7, 93)
        val alias = ManagedAddress.fromGuestByteArray(original).plus(7)
        original = null
        assertNull(original)
        assertEquals(93L, alias.readWord8(0))
        alias.withNativeSegment { segment ->
            assertTrue(segment.scope().isAlive)
            assertEquals(93.toByte(), segment.get(ValueLayout.JAVA_BYTE, 0))
            segment.set(ValueLayout.JAVA_BYTE, 0, 71.toByte())
        }
        assertEquals(71L, alias.readWord8(0))
        Reference.reachabilityFence(alias)
    }
}
