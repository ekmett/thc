// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ManagedByteArrayCompareTest {
    private fun storage(bytes: ByteArray): List<Any> = listOf(bytes,
        ManagedAllocation.mutable(bytes.size.toLong(), 8).also { it.copyBytesIn(bytes, 0, 0, bytes.size.toLong()) },
        ManagedAllocation.mutable(bytes.size.toLong(), 8, true).also { it.copyBytesIn(bytes, 0, 0, bytes.size.toLong()) },
        ManagedAllocation.immutable(bytes, 8))
    private fun expected(a: ByteArray, from: Int, b: ByteArray, to: Int, count: Int) =
        java.util.Arrays.compareUnsigned(a, from, from + count, b, to, to + count).compareTo(0)

    @Test fun heapPinnedImmutableAndRawRangesPreserveUnsignedOrderAndTails() {
        val first = ByteArray(139) { (it * 61 + 128).toByte() }
        val second = first.reversedArray()
        val lengths = listOf(0, 1, 7, 8, 15, 16, 17, 31, 32, 33, 63, 64, 65, 127, 128, 129)
        for (right in listOf(first.copyOf(), second)) {
            val leftStorage = storage(first)
            val rightStorage = storage(right)
            for (a in leftStorage) for (b in rightStorage) for (count in lengths)
                for (from in listOf(0, 1, first.size - count)) for (to in listOf(0, 3, right.size - count)) {
                    assertEquals(expected(first, from, right, to, count),
                        ManagedByteArray.compareGuest(a, from.toLong(), b, to.toLong(), count.toLong()).compareTo(0),
                        "${a.javaClass.simpleName}/${b.javaClass.simpleName}/$from/$to/$count")
                }
            for (value in leftStorage.filterIsInstance<ManagedAllocation>())
                for (index in first.indices) assertEquals(first[index].toLong() and 255, value.readByte(index.toLong()))
        }
        for (a in storage(byteArrayOf(0, -1))) for (b in storage(byteArrayOf(0, 127))) {
            assertTrue(ManagedByteArray.compareGuest(a, 0, b, 0, 2) > 0)
            assertTrue(ManagedByteArray.compareGuest(b, 0, a, 0, 2) < 0)
        }
        for (a in storage(byteArrayOf())) for (b in storage(byteArrayOf()))
            assertEquals(0L, ManagedByteArray.compareGuest(a, 0, b, 0, 0))
    }

    @Test fun aliasesReadCurrentStorageWithoutExposurePinningOrImmutableWrites() {
        val bytes = ByteArray(40) { (it * 37).toByte() }
        for (value in storage(bytes)) for (from in listOf(0, 1, 7, 17, 40))
            for (to in listOf(0, 2, 8, 19, 40)) {
                val count = minOf(40 - from, 40 - to)
                assertEquals(expected(bytes, from, bytes, to, count),
                    ManagedByteArray.compareGuest(value, from.toLong(), value, to.toLong(), count.toLong()).compareTo(0))
            }
        for (pinned in listOf(false, true)) {
            val allocation = ManagedAllocation.mutable(40, 8, pinned)
            allocation.copyBytesIn(bytes, 0, 0, 40)
            val backing = allocation.nativeSegment()
            assertEquals(0L, ManagedByteArray.compareGuest(allocation, 0, bytes, 0, 40))
            allocation.writeByte(0, 255)
            assertTrue(ManagedByteArray.compareGuest(allocation, 0, bytes, 0, 40) > 0)
            assertSame(backing, allocation.nativeSegment())
            assertEquals(pinned, allocation.isPinned)
            val address = ManagedAddress.fromByteArray(byteArrayOf(73))
            // Comparison has not exposed storage: subsequent pointer installation
            // is still permitted, unlike native projection or a raw array escape.
            allocation.writeAddressByteOffset(8, address)
            assertSame(address, allocation.readAddressByteOffset(8))
        }
        val owner = ManagedAllocation.mutable(40, 8)
        owner.copyBytesIn(bytes, 0, 0, 40)
        val alias = owner.rawBytesIfPointerFree()
        assertEquals(0L, ManagedByteArray.compareGuest(owner, 3, alias, 3, 37))
        alias[5] = 99
        assertEquals(0L, ManagedByteArray.compareGuest(alias, 0, owner, 0, 40))
        val immutable = ManagedAllocation.immutable(bytes, 8)
        assertEquals(0L, ManagedByteArray.compareGuest(immutable, 0, bytes, 0, 40))
        assertThrows(RuntimeFault::class.java) { immutable.writeByte(0, 42) }
        assertEquals(0L, immutable.readByte(0))
    }

    @Test fun fullLogicalRangesAreCheckedEvenForEmptyOrEarlyUnequalInputs() {
        val invalid = listOf(Triple(-1L, 0L, 0L), Triple(0L, -1L, 0L), Triple(18L, 0L, 0L), Triple(0L, 18L, 0L),
            Triple(0L, 0L, -1L), Triple(0L, 0L, 18L), Triple(16L, 0L, 2L), Triple(0L, 16L, 2L),
            Triple(Long.MAX_VALUE, 0L, 0L), Triple(0L, Long.MAX_VALUE, 0L), Triple(0L, 0L, Long.MAX_VALUE),
            Triple(Long.MIN_VALUE, 0L, 0L), Triple(0L, Long.MIN_VALUE, 0L), Triple(0L, 0L, Long.MIN_VALUE))
        for (pinned in listOf(false, true)) {
            val owner = ManagedAllocation.mutable(64, 8, pinned)
            owner.fill(0, 64, 255)
            owner.shrink(17)
            for (other in storage(ByteArray(17))) {
                for ((from, to, count) in invalid) {
                    assertThrows(RuntimeFault::class.java) { ManagedByteArray.compareGuest(owner, from, other, to, count) }
                    assertThrows(RuntimeFault::class.java) { ManagedByteArray.compareGuest(other, to, owner, from, count) }
                }
                assertEquals(0L, ManagedByteArray.compareGuest(owner, 17, other, 17, 0))
                assertTrue(ManagedByteArray.compareGuest(owner, 16, other, 16, 1) > 0)
            }
            assertEquals(0L, ManagedByteArray.compareGuest(owner, 17, owner, 17, 0))
            for (bad in listOf(null, 1L, ManagedAddress.nullAddress(), arrayOf(1))) {
                assertThrows(RuntimeFault::class.java) { ManagedByteArray.compareGuest(owner, 0, bad, 0, 0) }
                assertThrows(RuntimeFault::class.java) { ManagedByteArray.compareGuest(bad, 0, owner, 0, 0) }
            }
        }
    }

    @Test fun completePointerOverlapChecksPrecedeAnyEarlyMismatchOrAliasShortcut() {
        for (pinned in listOf(false, true)) {
            val owner = ManagedAllocation.mutable(40, 8, pinned)
            owner.fill(0, 40, 255)
            val address = ManagedAddress.fromByteArray(byteArrayOf(73))
            owner.writeAddressByteOffset(24, address)
            for (other in storage(ByteArray(40))) {
                for ((from, count) in listOf(0L to 40L, 0L to 25L, 23L to 2L, 24L to 1L, 31L to 1L, 24L to 8L)) {
                    assertThrows(RuntimeFault::class.java) { ManagedByteArray.compareGuest(owner, from, other, 0, count) }
                    assertThrows(RuntimeFault::class.java) { ManagedByteArray.compareGuest(other, 0, owner, from, count) }
                    assertThrows(RuntimeFault::class.java) { ManagedByteArray.compareGuest(owner, from, owner, from, count) }
                    assertSame(address, owner.readAddressByteOffset(24))
                    assertFalse(Thread.holdsLock(owner))
                }
                assertTrue(ManagedByteArray.compareGuest(owner, 0, other, 0, 24) > 0)
                assertTrue(ManagedByteArray.compareGuest(owner, 32, other, 32, 8) > 0)
                assertEquals(0L, ManagedByteArray.compareGuest(owner, 24, other, 24, 0))
            }
        }
    }

    @Test fun opposedComparisonsAndCopiesShareOwnerOrdering() {
        val first = ManagedAllocation.mutable(513, 8)
        val second = ManagedAllocation.mutable(513, 8, true)
        first.fill(0, 513, 0xa5); second.fill(0, 513, 0xa5)
        val ready = CountDownLatch(4)
        val start = CountDownLatch(1)
        val done = CountDownLatch(4)
        val failure = AtomicReference<Throwable>()
        repeat(4) { worker ->
            Thread.ofPlatform().daemon().name("comparison-copy-lock-$worker").start {
                ready.countDown()
                try {
                    check(start.await(10, TimeUnit.SECONDS))
                    repeat(2000) {
                        when (worker) {
                            0 -> assertEquals(0L, ManagedByteArray.compareGuest(first, 1, second, 1, 512))
                            1 -> assertEquals(0L, ManagedByteArray.compareGuest(second, 0, first, 0, 513))
                            2 -> first.copyFrom(second, 0, 0, 513)
                            else -> second.copyFrom(first, 0, 0, 513)
                        }
                    }
                } catch (error: Throwable) { failure.compareAndSet(null, error) }
                finally { done.countDown() }
            }
        }
        try { assertTrue(ready.await(10, TimeUnit.SECONDS), "all opposed workers ready") }
        finally { start.countDown() }
        assertTrue(done.await(20, TimeUnit.SECONDS), "opposed comparison/copy lock ordering")
        failure.get()?.let { throw AssertionError("opposed worker failed", it) }
        assertEquals(0L, ManagedByteArray.compareGuest(first, 0, second, 0, 513))
    }
}
