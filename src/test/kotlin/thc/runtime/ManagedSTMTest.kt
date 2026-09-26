// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.ControlFlowException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Timeout(30)
class ManagedSTMTest {
    private val location = object : Node() {}
    private fun <T> atomic(stm: ManagedSTM, action: () -> T): T =
        stm.atomically(null, { throw GuestException("nested", location) }, action = action)
    private fun await(gate: CountDownLatch) = assertTrue(gate.await(5, TimeUnit.SECONDS), "gate timed out")
    private fun queued(stm: ManagedSTM) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (stm.pendingWaiters() != 1 && System.nanoTime() < deadline) Thread.yield()
        assertEquals(1, stm.pendingWaiters(), "retry did not register")
    }

    @Test fun bufferedWritesRollbackAndPayloadsStayLazy() {
        val stm = ManagedSTM()
        val old = object { override fun equals(other: Any?): Boolean = error("must not compare payloads") }
        val replacement = Any()
        val cell = stm.newTVar(old)
        atomic(stm) {
            stm.write(cell, replacement)
            assertSame(replacement, stm.read(cell))
            assertSame(old, stm.readIO(cell))
        }
        assertSame(replacement, stm.readIO(cell))
        val payload = Any()
        val failure = assertThrows(GuestException::class.java) {
            atomic(stm) { stm.write(cell, old); throw GuestException(payload, location) }
        }
        assertSame(payload, failure.payload)
        assertSame(replacement, stm.readIO(cell))
        assertFalse(stm.hasTransaction())
        assertThrows(RuntimeFault::class.java) { stm.read(cell) }
        assertThrows(RuntimeFault::class.java) { stm.write(cell, null) }
        assertThrows(RuntimeFault::class.java) { stm.retry() }
    }

    @Test fun catchAndAlternativeHaveRealNestedWriteRollback() {
        val stm = ManagedSTM()
        val cell = stm.newTVar(1L)
        val payload = Any()
        atomic(stm) {
            stm.write(cell, 2L)
            val result = stm.catchSTM({
                stm.write(cell, 999L)
                throw GuestException(payload, location)
            }, { received ->
                assertSame(payload, received)
                assertEquals(2L, stm.read(cell))
                stm.write(cell, 3L)
                17L
            })
            assertEquals(17L, result)
            assertEquals(3L, stm.orElse({ stm.write(cell, 666L); stm.retry() }, { stm.read(cell) }))
            assertEquals(23L, stm.orElse({ 23L }, { error("right branch must stay lazy") }))
        }
        assertEquals(3L, stm.readIO(cell))
        assertThrows(IllegalStateException::class.java) {
            atomic(stm) { stm.catchSTM({ error("host fault") }, { error("not a Haskell exception") }) }
        }
        val second = Any()
        val failure = assertThrows(GuestException::class.java) {
            atomic(stm) {
                stm.catchSTM({ throw GuestException(payload, location) }, { throw GuestException(second, location) })
            }
        }
        assertSame(second, failure.payload)
        assertFalse(stm.hasTransaction())
    }

    @Test fun concurrentCommitConflictRestartsWithoutLosingAnUpdate() {
        val stm = ManagedSTM()
        val cell = stm.newTVar(0L)
        val read = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Long> {
                atomic(stm) {
                    val old = stm.read(cell) as Long
                    stm.write(cell, old + 1)
                    if (attempts.incrementAndGet() == 1) { read.countDown(); await(release) }
                    old + 1
                }.also { assertFalse(stm.hasTransaction()) }
            }
            await(read)
            assertEquals(0L, stm.readIO(cell), "buffered update was published before commit")
            atomic(stm) { stm.write(cell, 100L) }
            release.countDown()
            assertEquals(101L, result.get(5, TimeUnit.SECONDS))
            assertEquals(2, attempts.get())
            assertEquals(101L, stm.readIO(cell))
        } finally { release.countDown(); stm.close(); worker.shutdownNow() }
    }

    @Test fun abandoningNestedAttemptDoesNotCatchOrTransferPrivateWrites() {
        val stm = ManagedSTM()
        val cell = stm.newTVar(1L)
        val abandoned = object : ControlFlowException() {}
        val failure = assertThrows(ControlFlowException::class.java) {
            atomic(stm) {
                stm.write(cell, 2L)
                stm.orElse({
                    stm.catchSTM({
                        stm.write(cell, 3L)
                        throw abandoned
                    }, { error("A control unwind is not catchSTM's synchronous exception") })
                }, { error("A control unwind is not retry") })
            }
        }
        assertSame(abandoned, failure)
        assertFalse(stm.hasTransaction())
        assertEquals(1L, stm.readIO(cell))
        val carrier = Executors.newSingleThreadExecutor()
        try {
            assertEquals(4L, carrier.submit<Long> {
                assertFalse(stm.hasTransaction())
                atomic(stm) {
                    assertEquals(1L, stm.read(cell))
                    stm.write(cell, 4L)
                    4L
                }.also { assertFalse(stm.hasTransaction()) }
            }.get(5, TimeUnit.SECONDS))
            assertEquals(4L, stm.readIO(cell))
        } finally { carrier.shutdownNow(); stm.close() }
    }

    @Test fun validationPreventsMixedSnapshotsAndStaleExceptionEscape() {
        for (raise in listOf(false, true)) {
            val stm = ManagedSTM()
            val a = stm.newTVar(0L)
            val b = stm.newTVar(0L)
            val read = CountDownLatch(1)
            val release = CountDownLatch(1)
            val attempts = AtomicInteger()
            val worker = Executors.newSingleThreadExecutor()
            try {
                val result = worker.submit<Long> {
                    atomic(stm) {
                        val first = stm.read(a) as Long
                        if (attempts.incrementAndGet() == 1) {
                            read.countDown(); await(release)
                            if (raise) throw GuestException("stale snapshot", location)
                        }
                        val second = stm.read(b) as Long
                        assertEquals(first, second, "inconsistent snapshot reached guest computation")
                        first + second
                    }
                }
                await(read)
                atomic(stm) { stm.write(a, 7L); stm.write(b, 7L) }
                release.countDown()
                assertEquals(14L, result.get(5, TimeUnit.SECONDS))
                assertEquals(2, attempts.get())
            } finally { release.countDown(); stm.close(); worker.shutdownNow() }
        }
    }

    @Test fun retryWaitsOnBothAbortedAlternativeReadSets() {
        for (changeLeft in listOf(true, false)) {
            val stm = ManagedSTM()
            val a = stm.newTVar(0L)
            val b = stm.newTVar(0L)
            val sentinel = stm.newTVar(123L)
            val worker = Executors.newSingleThreadExecutor()
            try {
                val result = worker.submit<Long> {
                    atomic(stm) {
                        stm.orElse({
                            val x = stm.read(a) as Long
                            if (x == 0L) { stm.write(sentinel, 999L); stm.retry() }
                            x * 17
                        }, {
                            val y = stm.read(b) as Long
                            if (y == 0L) stm.retry()
                            y
                        })
                    }
                }
                queued(stm)
                assertEquals(123L, stm.readIO(sentinel))
                atomic(stm) { stm.write(if (changeLeft) a else b, 7L) }
                assertEquals(if (changeLeft) 119L else 7L, result.get(5, TimeUnit.SECONDS))
                assertEquals(123L, stm.readIO(sentinel))
                assertEquals(0, stm.pendingWaiters())
            } finally { stm.close(); worker.shutdownNow() }
        }
    }

    @Test fun failedCatchRetainsReadsAndHandlerRetryIsNotCaught() {
        val stm = ManagedSTM()
        val cell = stm.newTVar(0L)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Long> {
                atomic(stm) {
                    stm.catchSTM({
                        val x = stm.read(cell) as Long
                        if (x == 0L) throw GuestException(null, location)
                        x
                    }, { stm.retry() })
                }
            }
            queued(stm)
            atomic(stm) { stm.write(cell, 31L) }
            assertEquals(31L, result.get(5, TimeUnit.SECONDS))
            assertEquals(0, stm.pendingWaiters())
        } finally { stm.close(); worker.shutdownNow() }
    }

    @Test fun emptyRetryHasNoInventedSuccessAndDisposalReleasesWaiters() {
        val stm = ManagedSTM()
        val cell = stm.newTVar(Any())
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> {
                assertThrows(RuntimeFault::class.java) { atomic(stm) { stm.retry() } }
                !stm.hasTransaction()
            }
            queued(stm)
            stm.close()
            assertTrue(result.get(5, TimeUnit.SECONDS))
            assertEquals(0, stm.pendingWaiters())
            assertNull(cell.value, "context disposal retained a TVar payload")
            assertThrows(RuntimeFault::class.java) { stm.readIO(cell) }
            assertThrows(RuntimeFault::class.java) { stm.newTVar(null) }
        } finally { stm.close(); worker.shutdownNow() }
    }

    @Test fun contextsAndNestedAtomicExceptionsRemainDistinct() {
        val stm = ManagedSTM()
        val other = ManagedSTM()
        val cell = stm.newTVar(1L)
        assertThrows(RuntimeFault::class.java) { other.readIO(cell) }
        assertThrows(RuntimeFault::class.java) { atomic(other) { other.write(cell, 9L) } }
        assertEquals(1L, stm.readIO(cell))
        atomic(stm) {
            assertEquals("nested", stm.catchSTM({ atomic(stm) { error("cannot enter nested action") } }, { it }))
            assertTrue(stm.hasTransaction())
        }
        assertFalse(stm.hasTransaction())
        assertFalse(other.hasTransaction())
    }

    @Test fun manyConcurrentTransactionsPreserveTwoCellInvariant() {
        val stm = ManagedSTM()
        val a = stm.newTVar(0L)
        val b = stm.newTVar(0L)
        val workers = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val jobs = (0..1).map {
                workers.submit {
                    await(start)
                    repeat(128) {
                        atomic(stm) {
                            val x = stm.read(a) as Long
                            val y = stm.read(b) as Long
                            assertEquals(x, y)
                            stm.write(a, x + 1); stm.write(b, y + 1)
                        }
                    }
                }
            }
            start.countDown()
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(256L, stm.readIO(a)); assertEquals(256L, stm.readIO(b))
        } finally { stm.close(); workers.shutdownNow() }
    }

    @Test fun updateAfterRetryValidationBeforeRegistrationCannotBeLost() {
        val stm = ManagedSTM()
        val cell = stm.newTVar(0L)
        val validated = CountDownLatch(1)
        val updated = CountDownLatch(1)
        val attempts = AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Long> {
                atomic(stm) {
                    attempts.incrementAndGet()
                    val x = stm.read(cell) as Long
                    if (x == 0L) {
                        // Deliberate protocol seam: retry has validated, but the
                        // top atomically frame has not yet installed its waiter.
                        try { stm.retry() }
                        catch (retry: ControlFlowException) {
                            validated.countDown(); await(updated); throw retry
                        }
                    }
                    x
                }
            }
            await(validated)
            atomic(stm) { stm.write(cell, 47L) }
            updated.countDown()
            assertEquals(47L, result.get(5, TimeUnit.SECONDS))
            assertEquals(2, attempts.get())
            assertEquals(0, stm.pendingWaiters())
        } finally { updated.countDown(); stm.close(); worker.shutdownNow() }
    }

    @Test fun hostWaitInterruptionCancelsRegistrationWithoutPublishingWrites() {
        val stm = ManagedSTM()
        val dependency = stm.newTVar(0L)
        val sentinel = stm.newTVar(123L)
        val carrier = AtomicReference<Thread>()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> {
                carrier.set(Thread.currentThread())
                assertThrows(InterruptedException::class.java) {
                    atomic(stm) { stm.read(dependency); stm.write(sentinel, 999L); stm.retry() }
                }
                !stm.hasTransaction()
            }
            queued(stm)
            carrier.get().interrupt() // Direct host protocol, not guest throwTo support.
            assertTrue(result.get(5, TimeUnit.SECONDS))
            assertEquals(0, stm.pendingWaiters())
            assertEquals(123L, stm.readIO(sentinel))
        } finally { stm.close(); worker.shutdownNow() }
    }
}
