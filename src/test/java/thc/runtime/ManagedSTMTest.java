// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.ControlFlowException;
import thc.runtime.Unit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class ManagedSTMTest {
    private final Node location = new Node() {};
    private <T> T atomic(ManagedSTM stm, Supplier<T> action) {
        return stm.atomically(null, () -> { throw new GuestException("nested", location); }, false, action);
    }
    private void await(CountDownLatch gate) {
        try { assertTrue(gate.await(5, TimeUnit.SECONDS), "gate timed out"); }
        catch (InterruptedException failure) { ManagedSTMTest.<RuntimeException>rethrow(failure); }
    }
    // Callbacks do not declare checked exceptions; preserve the original exception identity.
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void rethrow(Throwable failure) throws E { throw (E) failure; }
    private void queued(ManagedSTM stm) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (stm.pendingWaiters() != 1 && System.nanoTime() < deadline) Thread.yield();
        assertEquals(1, stm.pendingWaiters(), "retry did not register");
    }

    @Test void restoredTransactionRemainsThreadAndContextLocal() throws Exception {
        try (var stm = new ManagedSTM(); var other = new ManagedSTM();
             var worker = Executors.newSingleThreadExecutor()) {
            assertNull(stm.currentTransaction());
            var saved = new ManagedSTM.Transaction();
            var ready = new CountDownLatch(1); var release = new CountDownLatch(1);
            var result = worker.submit(() -> {
                stm.restore(saved);
                try {
                    ready.countDown();
                    assertSame(saved, stm.currentTransaction());
                    assertNull(other.currentTransaction());
                    await(release);
                    return stm.currentTransaction();
                } finally { stm.restore(null); }
            });
            try {
                await(ready);
                assertNull(stm.currentTransaction());
                assertNull(other.currentTransaction());
            } finally { release.countDown(); }
            assertSame(saved, result.get(5, TimeUnit.SECONDS));
            assertNull(stm.currentTransaction());
        }
    }

    @Test void bufferedWritesRollbackAndPayloadsStayLazy() {
        var stm = new ManagedSTM();
        var old = new Object() { @Override public boolean equals(Object other) { throw new IllegalStateException("must not compare payloads"); } };
        var replacement = new Object();
        var cell = stm.newTVar(old);
        atomic(stm, () -> {
            stm.write(cell, replacement);
            assertSame(replacement, stm.read(cell));
            assertSame(old, stm.readIO(cell));
            return Unit.INSTANCE;
        });
        assertSame(replacement, stm.readIO(cell));
        var payload = new Object();
        var failure = assertThrows(GuestException.class, () -> atomic(stm, () -> {
            stm.write(cell, old); throw new GuestException(payload, location);
        }));
        assertSame(payload, failure.getPayload());
        assertSame(replacement, stm.readIO(cell));
        assertFalse(stm.hasTransaction());
        assertThrows(RuntimeFault.class, () -> stm.read(cell));
        assertThrows(RuntimeFault.class, () -> stm.write(cell, null));
        assertThrows(RuntimeFault.class, stm::retry);
    }

    @Test void catchAndAlternativeHaveRealNestedWriteRollback() {
        var stm = new ManagedSTM();
        var cell = stm.newTVar(1L);
        var payload = new Object();
        atomic(stm, () -> {
            stm.write(cell, 2L);
            var result = stm.catchSTM(() -> { stm.write(cell, 999L); throw new GuestException(payload, location); }, received -> {
                assertSame(payload, received);
                assertEquals(2L, stm.read(cell));
                stm.write(cell, 3L); return 17L;
            });
            assertEquals(17L, result);
            assertEquals(3L, stm.orElse(() -> { stm.write(cell, 666L); return stm.retry(); }, () -> stm.read(cell)));
            assertEquals(23L, stm.orElse(() -> 23L, () -> { throw new IllegalStateException("right branch must stay lazy"); }));
            return Unit.INSTANCE;
        });
        assertEquals(3L, stm.readIO(cell));
        assertThrows(IllegalStateException.class, () -> atomic(stm, () -> stm.catchSTM(
            () -> { throw new IllegalStateException("host fault"); }, received -> { throw new IllegalStateException("not a Haskell exception"); })));
        var second = new Object();
        var failure = assertThrows(GuestException.class, () -> atomic(stm, () -> stm.catchSTM(
            () -> { throw new GuestException(payload, location); }, received -> { throw new GuestException(second, location); })));
        assertSame(second, failure.getPayload());
        assertFalse(stm.hasTransaction());
    }

    @Test void concurrentCommitConflictRestartsWithoutLosingAnUpdate() throws Exception {
        var stm = new ManagedSTM();
        var cell = stm.newTVar(0L);
        var read = new CountDownLatch(1); var release = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        var worker = Executors.newSingleThreadExecutor();
        try {
            var result = worker.submit(() -> {
                long answer = atomic(stm, () -> {
                    long old = (Long) stm.read(cell);
                    stm.write(cell, old + 1);
                    if (attempts.incrementAndGet() == 1) { read.countDown(); await(release); }
                    return old + 1;
                });
                assertFalse(stm.hasTransaction()); return answer;
            });
            await(read);
            assertEquals(0L, stm.readIO(cell), "buffered update was published before commit");
            atomic(stm, () -> { stm.write(cell, 100L); return Unit.INSTANCE; });
            release.countDown();
            assertEquals(101L, result.get(5, TimeUnit.SECONDS));
            assertEquals(2, attempts.get());
            assertEquals(101L, stm.readIO(cell));
        } finally { release.countDown(); stm.close(); worker.shutdownNow(); }
    }

    @Test void abandoningNestedAttemptDoesNotCatchOrTransferPrivateWrites() throws Exception {
        var stm = new ManagedSTM();
        var cell = stm.newTVar(1L);
        var abandoned = new ControlFlowException() {};
        var failure = assertThrows(ControlFlowException.class, () -> atomic(stm, () -> {
            stm.write(cell, 2L);
            return stm.orElse(() -> stm.catchSTM(() -> { stm.write(cell, 3L); throw abandoned; },
                received -> { throw new IllegalStateException("A control unwind is not catchSTM's synchronous exception"); }),
                () -> { throw new IllegalStateException("A control unwind is not retry"); });
        }));
        assertSame(abandoned, failure);
        assertFalse(stm.hasTransaction());
        assertEquals(1L, stm.readIO(cell));
        var carrier = Executors.newSingleThreadExecutor();
        try {
            assertEquals(4L, carrier.submit(() -> {
                assertFalse(stm.hasTransaction());
                long answer = atomic(stm, () -> {
                    assertEquals(1L, stm.read(cell)); stm.write(cell, 4L); return 4L;
                });
                assertFalse(stm.hasTransaction()); return answer;
            }).get(5, TimeUnit.SECONDS));
            assertEquals(4L, stm.readIO(cell));
        } finally { carrier.shutdownNow(); stm.close(); }
    }

    @Test void validationPreventsMixedSnapshotsAndStaleExceptionEscape() throws Exception {
        for (boolean raise : new boolean[]{false, true}) {
            var stm = new ManagedSTM();
            var a = stm.newTVar(0L); var b = stm.newTVar(0L);
            var read = new CountDownLatch(1); var release = new CountDownLatch(1);
            var attempts = new AtomicInteger(); var worker = Executors.newSingleThreadExecutor();
            try {
                var result = worker.submit(() -> atomic(stm, () -> {
                    long first = (Long) stm.read(a);
                    if (attempts.incrementAndGet() == 1) {
                        read.countDown(); await(release);
                        if (raise) throw new GuestException("stale snapshot", location);
                    }
                    long second = (Long) stm.read(b);
                    assertEquals(first, second, "inconsistent snapshot reached guest computation");
                    return first + second;
                }));
                await(read);
                atomic(stm, () -> { stm.write(a, 7L); stm.write(b, 7L); return Unit.INSTANCE; });
                release.countDown();
                assertEquals(14L, result.get(5, TimeUnit.SECONDS));
                assertEquals(2, attempts.get());
            } finally { release.countDown(); stm.close(); worker.shutdownNow(); }
        }
    }

    @Test void retryWaitsOnBothAbortedAlternativeReadSets() throws Exception {
        for (boolean changeLeft : new boolean[]{true, false}) {
            var stm = new ManagedSTM();
            var a = stm.newTVar(0L); var b = stm.newTVar(0L); var sentinel = stm.newTVar(123L);
            var worker = Executors.newSingleThreadExecutor();
            try {
                var result = worker.submit(() -> atomic(stm, () -> stm.orElse(() -> {
                    long x = (Long) stm.read(a);
                    if (x == 0L) { stm.write(sentinel, 999L); stm.retry(); }
                    return x * 17;
                }, () -> {
                    long y = (Long) stm.read(b); if (y == 0L) stm.retry(); return y;
                })));
                queued(stm);
                assertEquals(123L, stm.readIO(sentinel));
                atomic(stm, () -> { stm.write(changeLeft ? a : b, 7L); return Unit.INSTANCE; });
                assertEquals(changeLeft ? 119L : 7L, result.get(5, TimeUnit.SECONDS));
                assertEquals(123L, stm.readIO(sentinel));
                assertEquals(0, stm.pendingWaiters());
            } finally { stm.close(); worker.shutdownNow(); }
        }
    }

    @Test void failedCatchRetainsReadsAndHandlerRetryIsNotCaught() throws Exception {
        var stm = new ManagedSTM(); var cell = stm.newTVar(0L);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var result = worker.submit(() -> atomic(stm, () -> stm.catchSTM(() -> {
                long x = (Long) stm.read(cell); if (x == 0L) throw new GuestException(null, location); return x;
            }, received -> { stm.retry(); throw new AssertionError("retry returned"); })));
            queued(stm);
            atomic(stm, () -> { stm.write(cell, 31L); return Unit.INSTANCE; });
            assertEquals(31L, result.get(5, TimeUnit.SECONDS));
            assertEquals(0, stm.pendingWaiters());
        } finally { stm.close(); worker.shutdownNow(); }
    }

    @Test void emptyRetryHasNoInventedSuccessAndDisposalReleasesWaiters() throws Exception {
        var stm = new ManagedSTM(); var cell = stm.newTVar(new Object());
        var worker = Executors.newSingleThreadExecutor();
        try {
            var result = worker.submit(() -> {
                assertThrows(RuntimeFault.class, () -> atomic(stm, stm::retry));
                return !stm.hasTransaction();
            });
            queued(stm); stm.close();
            assertTrue(result.get(5, TimeUnit.SECONDS));
            assertEquals(0, stm.pendingWaiters());
            assertNull(cell.getValue(), "context disposal retained a TVar payload");
            assertThrows(RuntimeFault.class, () -> stm.readIO(cell));
            assertThrows(RuntimeFault.class, () -> stm.newTVar(null));
        } finally { stm.close(); worker.shutdownNow(); }
    }

    @Test void contextsAndNestedAtomicExceptionsRemainDistinct() {
        var stm = new ManagedSTM(); var other = new ManagedSTM(); var cell = stm.newTVar(1L);
        assertThrows(RuntimeFault.class, () -> other.readIO(cell));
        assertThrows(RuntimeFault.class, () -> atomic(other, () -> { other.write(cell, 9L); return Unit.INSTANCE; }));
        assertEquals(1L, stm.readIO(cell));
        atomic(stm, () -> {
            assertEquals("nested", stm.catchSTM(() -> atomic(stm, () -> { throw new IllegalStateException("cannot enter nested action"); }), value -> value));
            assertTrue(stm.hasTransaction()); return Unit.INSTANCE;
        });
        assertFalse(stm.hasTransaction()); assertFalse(other.hasTransaction());
    }

    @Test void manyConcurrentTransactionsPreserveTwoCellInvariant() throws Exception {
        var stm = new ManagedSTM(); var a = stm.newTVar(0L); var b = stm.newTVar(0L);
        var workers = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var jobs = new ArrayList<Future<?>>();
            for (int i = 0; i <= 1; i++) jobs.add(workers.submit(() -> {
                await(start);
                for (int iteration = 0; iteration < 128; iteration++) atomic(stm, () -> {
                    long x = (Long) stm.read(a); long y = (Long) stm.read(b);
                    assertEquals(x, y); stm.write(a, x + 1); stm.write(b, y + 1); return Unit.INSTANCE;
                });
            }));
            start.countDown();
            for (var job : jobs) job.get(5, TimeUnit.SECONDS);
            assertEquals(256L, stm.readIO(a)); assertEquals(256L, stm.readIO(b));
        } finally { stm.close(); workers.shutdownNow(); }
    }

    @Test void updateAfterRetryValidationBeforeRegistrationCannotBeLost() throws Exception {
        var stm = new ManagedSTM(); var cell = stm.newTVar(0L);
        var validated = new CountDownLatch(1); var updated = new CountDownLatch(1);
        var attempts = new AtomicInteger(); var worker = Executors.newSingleThreadExecutor();
        try {
            var result = worker.submit(() -> atomic(stm, () -> {
                attempts.incrementAndGet(); long x = (Long) stm.read(cell);
                if (x == 0L) {
                    // Deliberate protocol seam: retry has validated, but the
                    // top atomically frame has not yet installed its waiter.
                    try { stm.retry(); }
                    catch (ControlFlowException retry) { validated.countDown(); await(updated); throw retry; }
                }
                return x;
            }));
            await(validated);
            atomic(stm, () -> { stm.write(cell, 47L); return Unit.INSTANCE; });
            updated.countDown();
            assertEquals(47L, result.get(5, TimeUnit.SECONDS));
            assertEquals(2, attempts.get()); assertEquals(0, stm.pendingWaiters());
        } finally { updated.countDown(); stm.close(); worker.shutdownNow(); }
    }

    @Test void hostWaitInterruptionCancelsRegistrationWithoutPublishingWrites() throws Exception {
        var stm = new ManagedSTM(); var dependency = stm.newTVar(0L); var sentinel = stm.newTVar(123L);
        var carrier = new AtomicReference<Thread>(); var worker = Executors.newSingleThreadExecutor();
        try {
            var result = worker.submit(() -> {
                carrier.set(Thread.currentThread());
                assertThrows(InterruptedException.class, () -> atomic(stm, () -> {
                    stm.read(dependency); stm.write(sentinel, 999L); return stm.retry();
                }));
                return !stm.hasTransaction();
            });
            queued(stm);
            carrier.get().interrupt(); // Direct host protocol, not guest throwTo support.
            assertTrue(result.get(5, TimeUnit.SECONDS));
            assertEquals(0, stm.pendingWaiters()); assertEquals(123L, stm.readIO(sentinel));
        } finally { stm.close(); worker.shutdownNow(); }
    }
}
