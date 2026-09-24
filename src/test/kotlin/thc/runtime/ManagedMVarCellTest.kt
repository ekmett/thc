// Copyright (c) 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import thc.runtime.ManagedMVar.RequestState.*

@Timeout(20)
class ManagedMVarCellTest {
    private fun pending(cell: ManagedMVar, takes: Int = 0, reads: Int = 0, puts: Int = 0) {
        assertEquals(ManagedMVar.PendingCounts(takes, reads, puts), cell.pendingCounts())
    }

    private fun result(result: MVarReadResult, present: Boolean, value: Any? = null) {
        assertEquals(present, result.present)
        assertSame(value, result.value)
    }

    @Test fun emptyAndNullAreDistinctAndFailuresNeverRetainOldPayloads() {
        val cell = ManagedMVar()
        assertSame(cell, ManagedMVar.require(cell))
        for (bad in listOf(null, Unit, Any())) assertThrows(RuntimeFault::class.java) { ManagedMVar.require(bad) }
        assertTrue(cell.isEmpty())
        result(cell.tryRead(), false)
        result(cell.tryTake(), false)
        assertTrue(cell.tryPut(null))
        assertFalse(cell.isEmpty())
        assertFalse(cell.tryPut(Any()))
        result(cell.tryRead(), true, null)
        result(cell.tryTake(), true, null)
        assertTrue(cell.isEmpty())
        val value = Any()
        assertTrue(cell.tryPut(value))
        result(cell.tryTake(), true, value)
        result(cell.tryRead(), false)
        result(cell.tryTake(), false)
        pending(cell)
    }

    @Test fun opaqueLazyPayloadIdentityIsNeverInspectedOrForced() {
        val value = object {
            override fun equals(other: Any?): Boolean = error("payload equality")
            override fun hashCode(): Int = error("payload hash")
            override fun toString(): String = error("payload rendering")
        }
        val bottom: () -> Nothing = { error("lazy payload forced") }
        val cell = ManagedMVar()
        assertTrue(cell.tryPut(value))
        result(cell.tryRead(), true, value)
        val putter = cell.beginPut(bottom)
        result(cell.tryTake(), true, value)
        assertEquals(COMMITTED, putter.state)
        assertNull(putter.pendingPutValue())
        assertNull(putter.await())
        result(cell.tryRead(), true, bottom)
        result(cell.tryTake(), true, bottom)
        pending(cell)
    }

    @Test fun everyQueuedReaderObservesTheValueBeforeTheOldestTakerConsumesIt() {
        val cell = ManagedMVar()
        val firstTaker = cell.beginTake()
        val firstReader = cell.beginRead()
        val secondTaker = cell.beginTake()
        val secondReader = cell.beginRead()
        pending(cell, takes = 2, reads = 2)
        val value = Any()
        assertTrue(cell.tryPut(value))
        assertEquals(COMMITTED, firstTaker.state)
        assertEquals(PENDING, secondTaker.state)
        assertSame(value, firstReader.await())
        assertSame(value, secondReader.await())
        assertSame(value, firstTaker.await())
        assertTrue(cell.isEmpty())
        pending(cell, takes = 1)
        assertTrue(cell.tryPut(null))
        assertNull(secondTaker.await())
        pending(cell)
    }

    @Test fun readBroadcastWithoutTakersLeavesTheCellFull() {
        val cell = ManagedMVar()
        val readers = List(4) { cell.beginRead() }
        val put = cell.beginPut(null)
        assertEquals(COMMITTED, put.state)
        for (reader in readers) {
            assertEquals(COMMITTED, reader.state)
            assertNull(reader.await())
        }
        result(cell.tryRead(), true, null)
        assertNull(cell.beginRead().await())
        assertFalse(cell.isEmpty())
        pending(cell)
    }

    @Test fun queuedPuttersReplaceTheFullCellInFifoOrderBeforeWakeup() {
        val cell = ManagedMVar()
        val original = Any()
        val first = Any()
        val second = Any()
        assertTrue(cell.tryPut(original))
        val p1 = cell.beginPut(first)
        val p2 = cell.beginPut(second)
        pending(cell, puts = 2)
        result(cell.tryTake(), true, original)
        assertEquals(COMMITTED, p1.state)
        assertEquals(PENDING, p2.state)
        assertNull(p1.pendingPutValue())
        assertFalse(cell.tryPut(Any()))
        result(cell.tryRead(), true, first)
        assertSame(first, cell.beginTake().await())
        assertEquals(COMMITTED, p2.state)
        result(cell.tryTake(), true, second)
        assertNull(p1.await())
        assertNull(p2.await())
        result(cell.tryTake(), false)
        pending(cell)
    }

    @Test fun handoffBeforeAwaitCannotBeStolenAndAwaitDoesNotReplay() {
        val cell = ManagedMVar()
        val taker = cell.beginTake()
        val first = Any()
        val later = Any()
        assertTrue(cell.tryPut(first))
        result(cell.tryTake(), false)
        assertTrue(cell.tryPut(later))
        assertSame(first, taker.await())
        assertSame(first, taker.await())
        result(cell.tryTake(), true, later)
        pending(cell)
    }

    @Test fun cancellingAnyQueuedTakerOrReaderUnlinksExactlyThatRequest() {
        for (cancelledIndex in 0..2) {
            val cell = ManagedMVar()
            val takes = List(3) { cell.beginTake() }
            val reads = List(3) { cell.beginRead() }
            for (request in listOf(takes[cancelledIndex], reads[cancelledIndex])) {
                assertTrue(request.cancel())
                assertFalse(request.cancel())
                assertEquals(CANCELLED, request.state)
                assertFalse(request.isQueued)
                assertThrows(CancellationException::class.java) { request.await() }
            }
            pending(cell, takes = 2, reads = 2)
            val first = Any()
            val second = Any()
            assertTrue(cell.tryPut(first))
            for (reader in reads.filterIndexed { i, _ -> i != cancelledIndex }) assertSame(first, reader.await())
            val surviving = takes.filterIndexed { i, _ -> i != cancelledIndex }
            assertSame(first, surviving[0].await())
            assertEquals(PENDING, surviving[1].state)
            assertTrue(cell.tryPut(second))
            assertSame(second, surviving[1].await())
            pending(cell)
        }
    }

    @Test fun cancellingAnyQueuedPutterReleasesItsPayloadAndPreservesFifo() {
        for (cancelledIndex in 0..2) {
            val cell = ManagedMVar()
            val initial = Any()
            assertTrue(cell.tryPut(initial))
            val values = List(3) { Any() }
            val puts = values.map(cell::beginPut)
            assertSame(values[cancelledIndex], puts[cancelledIndex].pendingPutValue())
            assertTrue(puts[cancelledIndex].cancel())
            assertNull(puts[cancelledIndex].pendingPutValue())
            assertFalse(puts[cancelledIndex].isQueued)
            pending(cell, puts = 2)
            result(cell.tryTake(), true, initial)
            for (i in values.indices.filter { it != cancelledIndex }) {
                assertEquals(COMMITTED, puts[i].state)
                assertNull(puts[i].pendingPutValue())
                result(cell.tryTake(), true, values[i])
            }
            assertTrue(cell.isEmpty())
            pending(cell)
        }
    }

    @Test fun cancellationBeforeCommitDoesNotConsumeOrPublish() {
        val cell = ManagedMVar()
        val take = cell.beginTake()
        val read = cell.beginRead()
        assertTrue(take.cancel())
        assertTrue(read.cancel())
        val value = Any()
        assertTrue(cell.tryPut(value))
        val rejected = cell.beginPut(Any())
        assertTrue(rejected.cancel())
        result(cell.tryTake(), true, value)
        assertTrue(cell.isEmpty())
        pending(cell)
    }

    @Test fun cancellationAfterCommitNeverRollsBackTakeReadOrPut() {
        val cell = ManagedMVar()
        val take = cell.beginTake()
        val read = cell.beginRead()
        val value = Any()
        assertTrue(cell.tryPut(value))
        assertFalse(take.cancel())
        assertFalse(read.cancel())
        assertSame(value, take.await())
        assertSame(value, read.await())
        assertTrue(cell.isEmpty())
        assertTrue(cell.tryPut(value))
        val replacement = Any()
        val put = cell.beginPut(replacement)
        result(cell.tryTake(), true, value)
        assertFalse(put.cancel())
        assertNull(put.await())
        result(cell.tryRead(), true, replacement)
        pending(cell)
    }

    @Test fun repeatedInterruptedRetriesKeepTheSameQueuePositionAndCommittedResult() {
        val cell = ManagedMVar()
        val first = cell.beginTake()
        val second = cell.beginTake()
        repeat(3) {
            Thread.currentThread().interrupt()
            try {
                assertThrows(InterruptedException::class.java) { first.await() }
            } finally {
                Thread.interrupted()
            }
            assertEquals(PENDING, first.state)
            pending(cell, takes = 2)
        }
        val value = Any()
        assertTrue(cell.tryPut(value))
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) { first.await() }
        } finally {
            Thread.interrupted()
        }
        assertSame(value, first.await())
        assertEquals(PENDING, second.state)
        assertTrue(cell.tryPut(null))
        assertNull(second.await())
        pending(cell)
    }

    @Test fun interruptionBeforeRegistrationAndCancellationOfAnUnsubmittedTokenAreSafe() {
        val cell = ManagedMVar()
        val request = cell.Request(ManagedMVar.Operation.TAKE)
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) { request.await() }
        } finally {
            Thread.interrupted()
        }
        assertEquals(PENDING, request.state)
        assertFalse(request.isQueued)
        pending(cell)
        val value = Any()
        assertTrue(cell.tryPut(value))
        assertSame(value, request.await())
        assertSame(value, request.await())
        assertTrue(cell.isEmpty())
        val abandoned = cell.Request(ManagedMVar.Operation.PUT, Any())
        assertTrue(abandoned.cancel())
        assertNull(abandoned.pendingPutValue())
        assertThrows(CancellationException::class.java) { abandoned.await() }
        assertTrue(cell.isEmpty())
        pending(cell)
    }

    @Test fun interruptedPutAndReadRetriesDoNotRegisterTwice() {
        for (operation in listOf(ManagedMVar.Operation.PUT, ManagedMVar.Operation.READ)) {
            val cell = ManagedMVar()
            val old = Any()
            val replacement = Any()
            if (operation == ManagedMVar.Operation.PUT) assertTrue(cell.tryPut(old))
            val request = if (operation == ManagedMVar.Operation.PUT) cell.beginPut(replacement) else cell.beginRead()
            repeat(3) {
                Thread.currentThread().interrupt()
                try {
                    assertThrows(InterruptedException::class.java) { request.await() }
                } finally {
                    Thread.interrupted()
                }
                if (operation == ManagedMVar.Operation.PUT) pending(cell, puts = 1) else pending(cell, reads = 1)
            }
            if (operation == ManagedMVar.Operation.PUT) {
                result(cell.tryTake(), true, old)
                assertNull(request.await())
                assertNull(request.await())
            } else {
                assertTrue(cell.tryPut(replacement))
                assertSame(replacement, request.await())
                assertSame(replacement, request.await())
            }
            result(cell.tryTake(), true, replacement)
            assertTrue(cell.isEmpty())
            pending(cell)
        }
    }

    // This observer waits for a concrete Condition queue state, not an assumed timing
    // delay. The deadline is only a failure bound; it cannot make a test pass.
    private fun awaitBlocked(request: ManagedMVar.Request) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!request.hasWaitingThread()) {
            if (System.nanoTime() >= deadline) fail<Unit>("Request did not reach its condition wait")
            Thread.yield()
        }
    }

    @Test fun conditionWaitInterruptedThenRetriedUsesOneRequestAndOneTransfer() {
        val cell = ManagedMVar()
        val first = cell.beginTake()
        val second = cell.beginTake()
        val thread = AtomicReference<Thread>()
        val interrupted = CountDownLatch(1)
        val retry = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<Any?> {
                thread.set(Thread.currentThread())
                try {
                    first.await()
                    error("Expected interruption of the actual condition wait")
                } catch (_: InterruptedException) {
                    interrupted.countDown()
                    check(retry.await(5, TimeUnit.SECONDS))
                    first.await()
                }
            }
            awaitBlocked(first)
            thread.get().interrupt()
            assertTrue(interrupted.await(5, TimeUnit.SECONDS))
            pending(cell, takes = 2)
            val value = Any()
            assertTrue(cell.tryPut(value))
            assertEquals(PENDING, second.state)
            retry.countDown()
            assertSame(value, future.get(5, TimeUnit.SECONDS))
            assertTrue(second.cancel())
            pending(cell)
        } finally {
            first.cancel()
            second.cancel()
            retry.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun terminalCancellationSignalsAnActualConditionWaitAndDropsPendingPayload() {
        val cell = ManagedMVar()
        assertTrue(cell.tryPut(Any()))
        val put = cell.beginPut(Any())
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<Boolean> {
                assertThrows(CancellationException::class.java) { put.await() }
                true
            }
            awaitBlocked(put)
            assertTrue(put.cancel())
            assertTrue(future.get(5, TimeUnit.SECONDS))
            assertNull(put.pendingPutValue())
            assertFalse(put.isQueued)
            pending(cell)
        } finally {
            put.cancel()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
