// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManagedByteArrayCompareTest {
    private List<Object> storage(byte[] bytes) {
        var mutable = ManagedAllocation.mutable(bytes.length, 8);
        mutable.copyBytesIn(bytes, 0, 0, bytes.length);
        var pinned = ManagedAllocation.mutable(bytes.length, 8, true);
        pinned.copyBytesIn(bytes, 0, 0, bytes.length);
        return List.of(bytes, mutable, pinned, ManagedAllocation.immutable(bytes, 8));
    }
    private int expected(byte[] a, int from, byte[] b, int to, int count) {
        return Integer.compare(Arrays.compareUnsigned(a, from, from + count, b, to, to + count), 0);
    }
    @Test
    void heapPinnedImmutableAndRawRangesPreserveUnsignedOrderAndTails() {
        var first = new byte[139];
        for (int i = 0; i < first.length; i++) first[i] = (byte) (i * 61 + 128);
        var second = new byte[first.length];
        for (int i = 0; i < first.length; i++) second[i] = first[first.length - i - 1];
        int[] lengths = {0, 1, 7, 8, 15, 16, 17, 31, 32, 33, 63, 64, 65, 127, 128, 129};
        for (var right : List.of(first.clone(), second)) {
            var leftStorage = storage(first);
            var rightStorage = storage(right);
            for (var a : leftStorage)
                for (var b : rightStorage)
                    for (int count : lengths)
                        for (int from : new int[] {0, 1, first.length - count})
                            for (int to : new int[] {0, 3, right.length - count})
                                assertEquals(expected(first, from, right, to, count),
                                    Long.compare(ManagedByteArray.compareGuest(a, from, b, to, count), 0),
                                    a.getClass().getSimpleName() + "/" + b.getClass().getSimpleName() + "/" + from + "/"
                                        + to + "/" + count);
            for (var raw : leftStorage)
                if (raw instanceof ManagedAllocation value)
                    for (int index = 0; index < first.length; index++)
                        assertEquals((long) first[index] & 255, value.readByte(index));
        }
        for (var a : storage(new byte[] {0, -1}))
            for (var b : storage(new byte[] {0, 127})) {
                assertTrue(ManagedByteArray.compareGuest(a, 0, b, 0, 2) > 0);
                assertTrue(ManagedByteArray.compareGuest(b, 0, a, 0, 2) < 0);
            }
        for (var a : storage(new byte[0]))
            for (var b : storage(new byte[0])) assertEquals(0L, ManagedByteArray.compareGuest(a, 0, b, 0, 0));
    }
    @Test
    void aliasesReadCurrentStorageWithoutExposurePinningOrImmutableWrites() {
        var bytes = new byte[40];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 37);
        for (var value : storage(bytes))
            for (int from : new int[] {0, 1, 7, 17, 40})
                for (int to : new int[] {0, 2, 8, 19, 40}) {
                    int count = Math.min(40 - from, 40 - to);
                    assertEquals(expected(bytes, from, bytes, to, count),
                        Long.compare(ManagedByteArray.compareGuest(value, from, value, to, count), 0));
                }
        for (boolean pinned : new boolean[] {false, true}) {
            var allocation = ManagedAllocation.mutable(40, 8, pinned);
            allocation.copyBytesIn(bytes, 0, 0, 40);
            var backing = allocation.nativeSegment();
            assertEquals(0L, ManagedByteArray.compareGuest(allocation, 0, bytes, 0, 40));
            allocation.writeByte(0, 255);
            assertTrue(ManagedByteArray.compareGuest(allocation, 0, bytes, 0, 40) > 0);
            assertSame(backing, allocation.nativeSegment());
            assertEquals(pinned, allocation.isPinned());
            var address = ManagedAddress.fromByteArray(new byte[] {73});
            // Comparison has not exposed storage: subsequent pointer installation
            // is still permitted, unlike native projection or a raw array escape.
            allocation.writeAddressByteOffset(8, address);
            assertSame(address, allocation.readAddressByteOffset(8));
        }
        var owner = ManagedAllocation.mutable(40, 8);
        owner.copyBytesIn(bytes, 0, 0, 40);
        var alias = owner.rawBytesIfPointerFree();
        assertEquals(0L, ManagedByteArray.compareGuest(owner, 3, alias, 3, 37));
        alias[5] = 99;
        assertEquals(0L, ManagedByteArray.compareGuest(alias, 0, owner, 0, 40));
        var immutable = ManagedAllocation.immutable(bytes, 8);
        assertEquals(0L, ManagedByteArray.compareGuest(immutable, 0, bytes, 0, 40));
        assertThrows(RuntimeFault.class, () -> immutable.writeByte(0, 42));
        assertEquals(0L, immutable.readByte(0));
    }
    @Test
    void fullLogicalRangesAreCheckedEvenForEmptyOrEarlyUnequalInputs() {
        long[][] invalid = {{-1L, 0L, 0L}, {0L, -1L, 0L}, {18L, 0L, 0L}, {0L, 18L, 0L}, {0L, 0L, -1L}, {0L, 0L, 18L},
            {16L, 0L, 2L}, {0L, 16L, 2L}, {Long.MAX_VALUE, 0L, 0L}, {0L, Long.MAX_VALUE, 0L}, {0L, 0L, Long.MAX_VALUE},
            {Long.MIN_VALUE, 0L, 0L}, {0L, Long.MIN_VALUE, 0L}, {0L, 0L, Long.MIN_VALUE}};
        for (boolean pinned : new boolean[] {false, true}) {
            var owner = ManagedAllocation.mutable(64, 8, pinned);
            owner.fill(0, 64, 255);
            owner.shrink(17);
            for (var other : storage(new byte[17])) {
                for (var range : invalid) {
                    long from = range[0], to = range[1], count = range[2];
                    assertThrows(
                        RuntimeFault.class, () -> ManagedByteArray.compareGuest(owner, from, other, to, count));
                    assertThrows(
                        RuntimeFault.class, () -> ManagedByteArray.compareGuest(other, to, owner, from, count));
                }
                assertEquals(0L, ManagedByteArray.compareGuest(owner, 17, other, 17, 0));
                assertTrue(ManagedByteArray.compareGuest(owner, 16, other, 16, 1) > 0);
            }
            assertEquals(0L, ManagedByteArray.compareGuest(owner, 17, owner, 17, 0));
            for (var bad : Arrays.asList(null, 1L, ManagedAddress.nullAddress(), new Integer[] {1})) {
                assertThrows(RuntimeFault.class, () -> ManagedByteArray.compareGuest(owner, 0, bad, 0, 0));
                assertThrows(RuntimeFault.class, () -> ManagedByteArray.compareGuest(bad, 0, owner, 0, 0));
            }
        }
    }
    @Test
    void completePointerOverlapChecksPrecedeAnyEarlyMismatchOrAliasShortcut() {
        for (boolean pinned : new boolean[] {false, true}) {
            var owner = ManagedAllocation.mutable(40, 8, pinned);
            owner.fill(0, 40, 255);
            var address = ManagedAddress.fromByteArray(new byte[] {73});
            owner.writeAddressByteOffset(24, address);
            for (var other : storage(new byte[40])) {
                for (var range : new long[][] {{0L, 40L}, {0L, 25L}, {23L, 2L}, {24L, 1L}, {31L, 1L}, {24L, 8L}}) {
                    long from = range[0], count = range[1];
                    assertThrows(RuntimeFault.class, () -> ManagedByteArray.compareGuest(owner, from, other, 0, count));
                    assertThrows(RuntimeFault.class, () -> ManagedByteArray.compareGuest(other, 0, owner, from, count));
                    assertThrows(
                        RuntimeFault.class, () -> ManagedByteArray.compareGuest(owner, from, owner, from, count));
                    assertSame(address, owner.readAddressByteOffset(24));
                    assertFalse(Thread.holdsLock(owner));
                }
                assertTrue(ManagedByteArray.compareGuest(owner, 0, other, 0, 24) > 0);
                assertTrue(ManagedByteArray.compareGuest(owner, 32, other, 32, 8) > 0);
                assertEquals(0L, ManagedByteArray.compareGuest(owner, 24, other, 24, 0));
            }
        }
    }
    @Test
    void opposedComparisonsAndCopiesShareOwnerOrdering() throws Exception {
        var first = ManagedAllocation.mutable(513, 8);
        var second = ManagedAllocation.mutable(513, 8, true);
        first.fill(0, 513, 0xa5);
        second.fill(0, 513, 0xa5);
        var ready = new CountDownLatch(4);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(4);
        var failure = new AtomicReference<Throwable>();
        for (int index = 0; index < 4; index++) {
            int worker = index;
            Thread.ofPlatform().daemon().name("comparison-copy-lock-" + worker).start(() -> {
                ready.countDown();
                try {
                    if (!start.await(10, TimeUnit.SECONDS))
                        throw new IllegalStateException("Check failed.");
                    for (int i = 0; i < 2000; i++) switch (worker) {
                            case 0 -> assertEquals(0L, ManagedByteArray.compareGuest(first, 1, second, 1, 512));
                            case 1 -> assertEquals(0L, ManagedByteArray.compareGuest(second, 0, first, 0, 513));
                            case 2 -> first.copyFrom(second, 0, 0, 513);
                            default -> second.copyFrom(first, 0, 0, 513);
                        }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    done.countDown();
                }
            });
        }
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS), "all opposed workers ready");
        } finally {
            start.countDown();
        }
        assertTrue(done.await(20, TimeUnit.SECONDS), "opposed comparison/copy lock ordering");
        if (failure.get() != null)
            throw new AssertionError("opposed worker failed", failure.get());
        assertEquals(0L, ManagedByteArray.compareGuest(first, 0, second, 0, 513));
    }
}
