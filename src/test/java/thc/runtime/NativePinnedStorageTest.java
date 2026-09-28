// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.interop.*;
import jdk.incubator.vector.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import thc.Language;
import java.lang.foreign.*;
import java.lang.ref.Reference;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.LongStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class NativePinnedStorageTest {
    private void inside(Executable body) throws Throwable {
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).build()) {
            context.initialize("thc");
            context.enter();
            try {
                body.execute();
            } finally {
                context.leave();
            }
        }
    }
    @Test
    public void pinnedAllocationsStartWithStableNativeStorageAtTheRequestedAlignment() {
        for (int shift = 0; shift <= 12; shift++)
            for (long size : new long[] {0, 1, 17, 4097}) {
                long alignment = 1L << shift;
                var storage = PinnedMemory.allocate(size, alignment);
                var segment = Objects.requireNonNull(storage.nativeSegment());
                assertTrue(storage.isPinned());
                assertTrue(segment.isNative());
                assertEquals(size, storage.getSize());
                assertEquals(size, segment.byteSize());
                assertNotEquals(0L, segment.address());
                assertEquals(0L, segment.address() & (alignment - 1));
                assertSame(segment, storage.nativeSegment());
                assertThrows(RuntimeFault.class, storage::rawBytesIfPointerFree);
                Reference.reachabilityFence(storage);
            }
        var firstEmpty = PinnedMemory.allocate(0, 64);
        var secondEmpty = PinnedMemory.allocate(0, 64);
        assertNotEquals(Objects.requireNonNull(firstEmpty.nativeSegment()).address(),
            Objects.requireNonNull(secondEmpty.nativeSegment()).address());
        Reference.reachabilityFence(firstEmpty);
        Reference.reachabilityFence(secondEmpty);
        for (long alignment : new long[] {-1, 0, 3, 17, Long.MAX_VALUE})
            assertThrows(RuntimeFault.class, () -> PinnedMemory.allocate(16, alignment));
        for (long size : new long[] {-1, (long) Integer.MAX_VALUE + 1, Long.MAX_VALUE})
            assertThrows(RuntimeFault.class, () -> PinnedMemory.allocate(size, 8));
    }
    @Test
    public void ordinaryAllocationsKeepTheirOriginalHeapAliasAndDoNotBecomePinned() {
        var storage = ManagedAllocation.mutable(16, 8);
        var bytes = storage.rawBytesIfPointerFree();
        assertFalse(storage.isPinned());
        assertNull(storage.nativeSegment());
        assertSame(bytes, storage.rawBytesIfPointerFree());
        assertTrue(storage.ownsStorage(bytes));
        bytes[3] = 91;
        assertEquals(91L, (long) ManagedByteArray.readGuest(storage, 3, true));
        ManagedByteArray.writeInt32ByteOffsetGuest(storage, 5, 0x12345678);
        assertEquals(0x12345678L, (long) ManagedByteArray.readInt32ByteOffset(bytes, 5));
        assertSame(storage, ManagedByteArray.freezeGuest(storage));
        assertFalse(storage.isPinned());
        assertNull(storage.nativeSegment());
    }
    @Test
    public void movingHeapArraysRejectNativeProjectionEvenWhenImmutable() throws Throwable {
        inside(() -> {
            var mutable = ManagedAllocation.mutable(16, 8);
            var immutable = ManagedAllocation.immutable(new byte[] {1, 2, 3, 4}, 8);
            var interop = InteropLibrary.getUncached();
            for (var storage : List.of(mutable, immutable)) {
                var address = ManagedAddress.Companion.fromGuestByteArray(storage);
                assertThrows(RuntimeFault.class, address::toNativeBits);
                assertNull(storage.nativeSegment());
                assertFalse(storage.isPinned());
                var buffer = new CbitsBuffer(storage.exposeSegment().asByteBuffer(), storage.isWritable());
                interop.toNative(buffer);
                assertFalse(interop.isPointer(buffer));
                assertThrows(UnsupportedMessageException.class, () -> interop.asPointer(buffer));
            }
            assertEquals(1L, immutable.readByte(0));
        });
    }
    @Test
    public void freezingAndRepeatedNativeProjectionPreserveStorageIdentityAndOffsetAliases() throws Throwable {
        inside(() -> {
            var storage = PinnedMemory.allocate(32, 64);
            var segment = Objects.requireNonNull(storage.nativeSegment());
            var base = ManagedAddress.Companion.fromGuestByteArray(storage);
            var frozen = ManagedByteArray.freezeGuest(storage);
            assertSame(storage, frozen);
            assertSame(segment, storage.nativeSegment());
            var registry = NativeAddresses.current(null);
            for (int repeat = 0; repeat < 4; repeat++) {
                assertEquals(segment.address(), base.toNativeBits());
                for (long offset : new long[] {0, 1, 15, 31, 32}) {
                    var alias = ManagedAddress.Companion.fromGuestByteArray(frozen).plus(offset);
                    assertEquals(segment.address() + offset, alias.toNativeBits());
                    var recovered = registry.recover(alias.toNativeBits());
                    assertSame(storage, recovered.cbitsOwner$org_intelligence_thc());
                    assertTrue(recovered.sameLocation(alias));
                    assertEquals(alias.toNativeBits(), recovered.toNativeBits());
                }
            }
            ManagedByteArray.writeGuest(storage, 7, 93);
            assertEquals(93L, (long) ManagedByteArray.readGuest(frozen, 7, true));
            assertEquals(93L, registry.recover(segment.address() + 7).readWord8(0));
            assertThrows(RuntimeFault.class, () -> base.plus(32).readWord8(0));
            Reference.reachabilityFence(storage);
        });
    }
    @Test
    public void bufferOnlyAndPointerCapableInteropViewsShareTheSameBytesWithoutPromotionCopies() throws Throwable {
        inside(() -> {
            var storage = PinnedMemory.allocate(32, 64);
            var base = ManagedAddress.Companion.fromGuestByteArray(storage);
            var interop = InteropLibrary.getUncached();
            var buffer = new CbitsBuffer(storage.exposeSegment().asByteBuffer(), true);
            var pointer = new CbitsBuffer(
                storage.exposeSegment().asByteBuffer(), true, storage::getSize, 7, null, base::toNativeBits);
            assertTrue(interop.hasBufferElements(buffer));
            assertFalse(interop.isPointer(buffer));
            interop.toNative(buffer);
            assertFalse(interop.isPointer(buffer));
            assertThrows(UnsupportedMessageException.class, () -> interop.asPointer(buffer));
            assertTrue(interop.isPointer(pointer));
            assertEquals(25L, interop.getBufferSize(pointer));
            long bits = base.toNativeBits();
            for (int repeat = 0; repeat < 4; repeat++) {
                interop.toNative(pointer);
                assertEquals(bits + 7, interop.asPointer(pointer));
                assertEquals(bits, Objects.requireNonNull(storage.nativeSegment()).address());
            }
            interop.writeBufferInt(pointer, ByteOrder.nativeOrder(), 1, 0x12345678);
            assertEquals(0x12345678, interop.readBufferInt(buffer, ByteOrder.nativeOrder(), 8));
            assertEquals(0x12345678L, (long) ManagedByteArray.readInt32Guest(storage, 2, false));
            ManagedByteArray.writeGuest(storage, 7, 0xe7);
            assertEquals((byte) 0xe7, interop.readBufferByte(pointer, 0));
            assertThrows(InvalidBufferOffsetException.class, () -> interop.readBufferByte(pointer, 25));
            Reference.reachabilityFence(pointer);
            Reference.reachabilityFence(buffer);
        });
    }
    @Test
    public void libcMutationAndInteropWritesAreImmediatelyVisibleThroughEveryView() throws Throwable {
        inside(() -> {
            var storage = PinnedMemory.allocate(64, 64);
            var segment = Objects.requireNonNull(storage.nativeSegment());
            var base = ManagedAddress.Companion.fromGuestByteArray(storage);
            var interop = InteropLibrary.getUncached();
            var buffer = new CbitsBuffer(storage.exposeSegment().asByteBuffer(), true);
            storage.fill(0, 64, 0x11);
            var linker = Linker.nativeLinker();
            var memset = linker.downcallHandle(linker.defaultLookup().find("memset").orElseThrow(),
                FunctionDescriptor.of(
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
            long bits = base.toNativeBits();
            var result = (MemorySegment) memset.invokeWithArguments(segment.asSlice(9, 13), 0xa5, 13L);
            assertEquals(bits + 9, result.address());
            for (long offset = 0; offset < 64; offset++) {
                long expected = offset >= 9 && offset < 22 ? 0xa5L : 0x11L;
                assertEquals(expected, (long) ManagedByteArray.readGuest(storage, offset, true));
                assertEquals((byte) expected, interop.readBufferByte(buffer, offset));
                assertEquals(expected, base.readWord8(offset));
            }
            interop.writeBufferLong(buffer, ByteOrder.nativeOrder(), 3, 0x0102030405060708L);
            assertEquals(0x0102030405060708L, segment.get(ValueLayout.JAVA_LONG_UNALIGNED, 3));
            ManagedByteArray.writeInt32ByteOffsetGuest(storage, 27, (int) 0x89abcdefL);
            assertEquals(0x89abcdef, segment.get(ValueLayout.JAVA_INT_UNALIGNED, 27));
            assertEquals(bits, base.toNativeBits());
            Reference.reachabilityFence(storage);
        });
    }
    @Test
    public void onePinnedAllocationSupportsIndependentContextViewsAndOutlivesContextDisposal() throws Throwable {
        var storage = PinnedMemory.allocate(16, 64);
        var address = ManagedAddress.Companion.fromGuestByteArray(storage);
        long bits = Objects.requireNonNull(storage.nativeSegment()).address();
        var interop = InteropLibrary.getUncached();
        CbitsBuffer[] retainedBuffer = new CbitsBuffer[1];
        inside(() -> {
            var cbits = Language.currentState().cbits();
            retainedBuffer[0] = cbits.buffer$org_intelligence_thc(address, false);
            assertFalse(interop.isPointer(retainedBuffer[0]));
            assertEquals(bits, interop.asPointer(cbits.buffer$org_intelligence_thc(address, true)));
            interop.writeBufferByte(retainedBuffer[0], 2, (byte) 71);
        });
        // Closing one context does not close storage owned by a live ForeignPtr.
        assertEquals(71L, address.readWord8(2));
        inside(() -> {
            var cbits = Language.currentState().cbits();
            assertEquals(bits, interop.asPointer(cbits.buffer$org_intelligence_thc(address, true)));
            assertTrue(NativeAddresses.current(null).recover(bits + 2).sameLocation(address.plus(2)));
            var second = cbits.buffer$org_intelligence_thc(address, false);
            interop.writeBufferByte(second, 2, (byte) 93);
            assertEquals((byte) 93, interop.readBufferByte(retainedBuffer[0], 2));
        });
        assertEquals(bits, Objects.requireNonNull(storage.nativeSegment()).address());
        assertEquals(93L, storage.readByte(2));
        Reference.reachabilityFence(retainedBuffer[0]);
    }
    @Test
    public void originalSulongMd5ReadsAndMutatesPinnedStorageWithoutChangingAnyAddress() throws Throwable {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Windows uses its native MD5 provider");
        inside(() -> {
            byte[] payload = "Native pinned storage stays shared across original C calls. ".repeat(3).getBytes(
                StandardCharsets.US_ASCII);
            var contextStorage = PinnedMemory.allocate(88, 64);
            var inputStorage = PinnedMemory.allocate(payload.length + 16L, 64);
            var outputStorage = PinnedMemory.allocate(16, 64);
            inputStorage.fill(0, inputStorage.getSize(), 0x5a);
            for (int index = 0; index < payload.length; index++) inputStorage.writeByte(index + 7L, payload[index]);
            var context = ManagedAddress.Companion.fromGuestByteArray(contextStorage);
            var input = ManagedAddress.Companion.fromGuestByteArray(inputStorage).plus(7);
            var output = ManagedAddress.Companion.fromGuestByteArray(outputStorage);
            var outputAlias = ManagedByteArray.freezeGuest(outputStorage);
            var addresses = List.of(context, input, output);
            var bits = addresses.stream().map(ManagedAddress::toNativeBits).toList();
            var cbits = Language.currentState().cbits();
            var outputBuffer = cbits.buffer$org_intelligence_thc(output, true);
            var interop = InteropLibrary.getUncached();
            assertTrue(interop.isPointer(outputBuffer));
            assertEquals(bits.get(2).longValue(), interop.asPointer(outputBuffer));
            cbits.init(context);
            assertEquals(bits, addresses.stream().map(ManagedAddress::toNativeBits).toList());
            cbits.update(context, input, 13);
            assertEquals(bits, addresses.stream().map(ManagedAddress::toNativeBits).toList());
            cbits.update(context, input.plus(13), payload.length - 13);
            assertEquals(bits, addresses.stream().map(ManagedAddress::toNativeBits).toList());
            cbits.finish(output, context);
            assertEquals(bits, addresses.stream().map(ManagedAddress::toNativeBits).toList());
            var expected = MessageDigest.getInstance("MD5").digest(payload);
            byte[] guest = new byte[16], buffer = new byte[16];
            for (int i = 0; i < 16; i++) guest[i] = (byte) ManagedByteArray.readGuest(outputAlias, i, true);
            assertArrayEquals(expected, guest);
            for (int i = 0; i < 16; i++) buffer[i] = interop.readBufferByte(outputBuffer, i);
            assertArrayEquals(expected, buffer);
            assertArrayEquals(
                expected, Objects.requireNonNull(outputStorage.nativeSegment()).toArray(ValueLayout.JAVA_BYTE));
            assertEquals(0x5aL, inputStorage.readByte(6));
            assertEquals(0x5aL, inputStorage.readByte(payload.length + 7L));
            Reference.reachabilityFence(addresses);
        });
    }
    private void checkAtomicAliases(int width, AtomicIntArrayOp arrayOp, AtomicAddressOp addressOp) {
        var storage = PinnedMemory.allocate(width * 3L, 64);
        storage.fill(0, storage.getSize(), 0x5a);
        storage.fill(width, width, 0xff);
        var alias = ManagedAddress.Companion.fromGuestByteArray(storage).plus(width);
        assertEquals(-1L, storage.atomicInt(1, -1, 7, arrayOp));
        assertEquals(7L, addressOp.numeric(alias, 7, -1));
        assertEquals(-1L, storage.atomicInt(1, -1, 19, arrayOp));
        assertEquals(19L, addressOp.numeric(alias, 20, 99));
        assertEquals(19L, storage.atomicInt(1, 20, 99, arrayOp));
        for (long offset = 0; offset < width; offset++) {
            assertEquals(0x5aL, storage.readByte(offset));
            assertEquals(0x5aL, storage.readByte(width * 2L + offset));
        }
        var exactTail = PinnedMemory.allocate(width, 64);
        exactTail.fill(0, width, 0xff);
        long unsignedOld = width == 8 ? -1L : (1L << (width * 8)) - 1;
        assertEquals(unsignedOld, addressOp.numeric(ManagedAddress.Companion.fromGuestByteArray(exactTail), -1, 23));
        assertEquals(23L, exactTail.atomicInt(0, 23, 29, arrayOp));
    }
    @Test
    public void wordAtomicsShareNativeStorageBetweenArrayAndAddressAliases() {
        checkAtomicAliases(4, AtomicIntArrayOp.CAS32, AtomicAddressOp.CAS32);
        checkAtomicAliases(8, AtomicIntArrayOp.CAS64, AtomicAddressOp.CAS64);
        var storage = PinnedMemory.allocate(16, 64);
        var alias = ManagedAddress.Companion.fromGuestByteArray(storage).plus(8);
        storage.atomicInt(1, 42, 0, AtomicIntArrayOp.WRITE);
        assertEquals(42L, AtomicAddressOp.ADD.numeric(alias, 5, 0));
        assertEquals(47L, storage.atomicInt(1, 0, 0, AtomicIntArrayOp.READ));
        assertEquals(47L, storage.atomicInt(1, 9, 0, AtomicIntArrayOp.SUB));
        assertEquals(38L, AtomicAddressOp.EXCHANGE.numeric(alias, 93, 0));
        assertEquals(93L, Objects.requireNonNull(storage.nativeSegment()).get(ValueLayout.JAVA_LONG, 8));
    }
    @Test
    public void narrowNativeAtomicsTouchOnlyTheirElementAndShareArrayAliases() {
        assumeTrue(System.getProperty("os.name").equals("Linux")
                && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")),
            "The native byte/short CAS helper currently targets Linux x86_64");
        checkAtomicAliases(1, AtomicIntArrayOp.CAS8, AtomicAddressOp.CAS8);
        checkAtomicAliases(2, AtomicIntArrayOp.CAS16, AtomicAddressOp.CAS16);
    }
    @Test
    public void shrinkUpdatesExistingBoundedViewsWithoutMovingTheirNativeStorage() throws Throwable {
        inside(() -> {
            var storage = PinnedMemory.allocate(32, 64);
            var base = ManagedAddress.Companion.fromGuestByteArray(storage);
            var alias = base.plus(7);
            var interop = InteropLibrary.getUncached();
            var buffer = new CbitsBuffer(
                storage.exposeSegment().asByteBuffer(), true, storage::getSize, 7, null, base::toNativeBits);
            long bits = interop.asPointer(buffer);
            storage.shrink(16);
            assertEquals(16L, storage.getSize());
            assertEquals(9L, interop.getBufferSize(buffer));
            assertEquals(bits, interop.asPointer(buffer));
            assertEquals(9L, alias.availableBytes());
            for (long offset : new long[] {-1, 9, Long.MAX_VALUE})
                assertThrows(InvalidBufferOffsetException.class, () -> interop.readBufferByte(buffer, offset));
            assertThrows(InvalidBufferOffsetException.class,
                () -> interop.writeBufferLong(buffer, ByteOrder.nativeOrder(), 2, 1));
            assertThrows(RuntimeFault.class, () -> ManagedByteArray.readIntGuest(storage, 2));
            assertSame(storage,
                NativeAddresses.current(null).recover(base.toNativeBits() + 16).cbitsOwner$org_intelligence_thc());
            assertThrows(
                RuntimeFault.class, () -> NativeAddresses.current(null).recover(base.toNativeBits() + 17).readWord8(0));
            storage.shrink(5);
            assertEquals(0L, interop.getBufferSize(buffer));
            assertEquals(bits, interop.asPointer(buffer));
            assertThrows(InvalidBufferOffsetException.class, () -> interop.readBufferByte(buffer, 0));
            assertThrows(RuntimeFault.class, () -> alias.readWord8(0));
        });
    }
    @Test
    public void resizeShrinksPinnedStorageInPlaceButGrowthAllocatesAnUnpinnedPrefixCopy() {
        var original = PinnedMemory.allocate(24, 4096);
        for (long offset = 0; offset < original.getSize(); offset++) original.writeByte(offset, offset * 7);
        var originalSegment = Objects.requireNonNull(original.nativeSegment());
        long originalBits = originalSegment.address();
        assertSame(original, original.resized(24));
        var shortened = original.resized(11);
        assertSame(original, shortened);
        assertTrue(shortened.isPinned());
        assertSame(originalSegment, shortened.nativeSegment());
        assertEquals(originalBits, Objects.requireNonNull(shortened.nativeSegment()).address());
        assertEquals(0L, originalBits & 4095);
        assertEquals(11L, shortened.getSize());
        assertEquals(LongStream.range(0, 11).map(x -> x * 7).boxed().toList(),
            LongStream.range(0, 11).map(shortened::readByte).boxed().toList());
        var grown = shortened.resized(80);
        assertNotSame(shortened, grown);
        assertFalse(grown.isPinned());
        assertNull(grown.nativeSegment());
        assertEquals(80L, grown.getSize());
        assertEquals(LongStream.range(0, 11).map(x -> x * 7).boxed().toList(),
            LongStream.range(0, 11).map(grown::readByte).boxed().toList());
        var grownAgain = grown.resized(128);
        assertNotSame(grown, grownAgain);
        assertFalse(grownAgain.isPinned());
        assertNull(grownAgain.nativeSegment());
        assertEquals(128L, grownAgain.getSize());
        assertEquals(LongStream.range(0, 11).map(x -> x * 7).boxed().toList(),
            LongStream.range(0, 11).map(grownAgain::readByte).boxed().toList());
        var pinned = PinnedMemory.allocate(8, 4096);
        long pinnedBits = Objects.requireNonNull(pinned.nativeSegment()).address();
        var empty = pinned.resized(0);
        assertSame(pinned, empty);
        assertTrue(empty.isPinned());
        assertEquals(0L, empty.getSize());
        assertEquals(pinnedBits, Objects.requireNonNull(empty.nativeSegment()).address());
    }
    @Test
    public void guestScalarsAndVectorSpeciesAccessNativeStorageAtBothKindsOfOffset() {
        var storage = PinnedMemory.allocate(256, 64);
        var segment = Objects.requireNonNull(storage.nativeSegment());
        ManagedByteArray.writeIntGuest(storage, 3, Long.MIN_VALUE + 19, true);
        assertEquals(Long.MIN_VALUE + 19, ManagedByteArray.readIntGuest(storage, 3, true));
        assertEquals(Long.MIN_VALUE + 19, segment.get(ValueLayout.JAVA_LONG_UNALIGNED, 3));
        ManagedByteArray.writeInt16ByteOffsetGuest(storage, 1, 0xffff);
        assertEquals(-1L, (long) ManagedByteArray.readInt16ByteOffsetGuest(storage, 1, false));
        assertEquals(65535L, (long) ManagedByteArray.readInt16ByteOffsetGuest(storage, 1, true));
        ManagedByteArray.writeInt32ByteOffsetGuest(storage, 5, (int) 0xffffffffL);
        assertEquals(-1L, (long) ManagedByteArray.readInt32ByteOffsetGuest(storage, 5, false));
        assertEquals(0xffffffffL, Integer.toUnsignedLong(ManagedByteArray.readInt32ByteOffsetGuest(storage, 5, true)));
        ManagedByteArray.writeFloatByteOffsetGuest(storage, 9, -0.0f);
        assertEquals(Integer.MIN_VALUE, Float.floatToRawIntBits(ManagedByteArray.readFloatByteOffsetGuest(storage, 9)));
        ManagedByteArray.writeDoubleByteOffsetGuest(storage, 15, -0.0);
        assertEquals(
            Long.MIN_VALUE, Double.doubleToRawLongBits(ManagedByteArray.readDoubleByteOffsetGuest(storage, 15)));
        for (int vectorBytes : new int[] {16, 32, 64})
            for (boolean scalarOffset : new boolean[] {false, true}) {
                var shape = VectorShape.forBitSize(vectorBytes * 8);
                var bytes = ByteVector.broadcast(ByteVector.SPECIES_128.withShape(shape), (byte) 0xa5);
                ManagedByteArray.writeByteVectorGuest(storage, 1, bytes, scalarOffset, vectorBytes);
                assertEquals(bytes, ManagedByteArray.readByteVectorGuest(storage, 1, scalarOffset, vectorBytes));
                var shorts = ShortVector.broadcast(ShortVector.SPECIES_128.withShape(shape), (short) 0x89ab);
                ManagedByteArray.writeShortVectorGuest(storage, 1, shorts, scalarOffset, vectorBytes);
                assertEquals(shorts, ManagedByteArray.readShortVectorGuest(storage, 1, scalarOffset, vectorBytes));
                var ints = IntVector.broadcast(IntVector.SPECIES_128.withShape(shape), 0x12345678);
                ManagedByteArray.writeInt32VectorGuest(storage, 1, ints, scalarOffset, vectorBytes);
                assertEquals(ints, ManagedByteArray.readInt32VectorGuest(storage, 1, scalarOffset, vectorBytes));
                ManagedByteArray.writeWord32VectorGuest(storage, 1, ints, scalarOffset, vectorBytes);
                assertEquals(ints, ManagedByteArray.readWord32VectorGuest(storage, 1, scalarOffset, vectorBytes));
                var longs = LongVector.broadcast(LongVector.SPECIES_128.withShape(shape), 0x0102030405060708L);
                ManagedByteArray.writeLongVectorGuest(storage, 1, longs, scalarOffset, vectorBytes);
                assertEquals(longs, ManagedByteArray.readLongVectorGuest(storage, 1, scalarOffset, vectorBytes));
                var floats = FloatVector.broadcast(FloatVector.SPECIES_128.withShape(shape), -1.25f);
                ManagedByteArray.writeFloatVectorGuest(storage, 1, floats, scalarOffset, vectorBytes);
                assertEquals(floats, ManagedByteArray.readFloatVectorGuest(storage, 1, scalarOffset, vectorBytes));
                var doubles = DoubleVector.broadcast(DoubleVector.SPECIES_128.withShape(shape), 3.125);
                ManagedByteArray.writeDoubleVectorGuest(storage, 1, doubles, scalarOffset, vectorBytes);
                assertEquals(doubles, ManagedByteArray.readDoubleVectorGuest(storage, 1, scalarOffset, vectorBytes));
                long offset = scalarOffset ? 8L : vectorBytes;
                assertEquals(3.125, segment.get(ValueLayout.JAVA_DOUBLE_UNALIGNED, offset));
            }
        for (long index : new long[] {-1, 32, Long.MAX_VALUE})
            assertThrows(RuntimeFault.class, () -> ManagedByteArray.readIntGuest(storage, index));
        for (long offset : new long[] {-1, 249, Long.MAX_VALUE})
            assertThrows(RuntimeFault.class, () -> ManagedByteArray.writeIntGuest(storage, offset, 1, true));
        assertThrows(RuntimeFault.class, () -> ManagedByteArray.readLongVectorGuest(storage, Long.MAX_VALUE, true));
        assertThrows(RuntimeFault.class,
            () -> ManagedByteArray.writeInt32VectorGuest(storage, 0, IntVector.zero(IntVector.SPECIES_256), false, 16));
    }
    @Test
    public void derivedAddressKeepsAutomaticNativeStorageLiveAfterTheOriginalVariableIsCleared() {
        ManagedAllocation original = PinnedMemory.allocate(24, 64);
        original.writeByte(7, 93);
        var alias = ManagedAddress.Companion.fromGuestByteArray(original).plus(7);
        original = null;
        assertNull(original);
        assertEquals(93L, alias.readWord8(0));
        alias.withNativeSegment$org_intelligence_thc(segment -> {
            assertTrue(segment.scope().isAlive());
            assertEquals((byte) 93, segment.get(ValueLayout.JAVA_BYTE, 0));
            segment.set(ValueLayout.JAVA_BYTE, 0, (byte) 71);
            return kotlin.Unit.INSTANCE;
        });
        assertEquals(71L, alias.readWord8(0));
        Reference.reachabilityFence(alias);
    }
}
