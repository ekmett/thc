// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import thc.runtime.Unit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ManagedMVar.RequestState.*;

@Timeout(20)
class ManagedMVarCellTest {
    private void pending(ManagedMVar cell, int takes, int reads, int puts) {
        assertEquals(new ManagedMVar.PendingCounts(takes, reads, puts), cell.pendingCounts());
    }
    private void result(MVarReadResult result, boolean present, Object value) {
        assertEquals(present, result.getPresent());
        assertSame(value, result.getValue());
    }

    @Test void emptyAndNullAreDistinctAndFailuresNeverRetainOldPayloads() {
        var cell = new ManagedMVar();
        assertSame(cell, ManagedMVar.require(cell));
        for (var bad : Arrays.asList(null, Unit.INSTANCE, new Object())) assertThrows(RuntimeFault.class, () -> ManagedMVar.require(bad));
        assertTrue(cell.isEmpty());
        result(cell.tryRead(), false, null);
        result(cell.tryTake(), false, null);
        assertTrue(cell.tryPut(null));
        assertFalse(cell.isEmpty());
        assertFalse(cell.tryPut(new Object()));
        result(cell.tryRead(), true, null);
        result(cell.tryTake(), true, null);
        assertTrue(cell.isEmpty());
        var value = new Object();
        assertTrue(cell.tryPut(value));
        result(cell.tryTake(), true, value);
        result(cell.tryRead(), false, null);
        result(cell.tryTake(), false, null);
        pending(cell, 0, 0, 0);
    }

    @Test void opaqueLazyPayloadIdentityIsNeverInspectedOrForced() throws Exception {
        var value = new Object() {
            @Override public boolean equals(Object other) { throw new IllegalStateException("payload equality"); }
            @Override public int hashCode() { throw new IllegalStateException("payload hash"); }
            @Override public String toString() { throw new IllegalStateException("payload rendering"); }
        };
        Supplier<Object> bottom = () -> { throw new IllegalStateException("lazy payload forced"); };
        var cell = new ManagedMVar();
        assertTrue(cell.tryPut(value));
        result(cell.tryRead(), true, value);
        var putter = cell.beginPut(bottom);
        result(cell.tryTake(), true, value);
        assertEquals(COMMITTED, putter.getState());
        assertNull(putter.pendingPutValue());
        assertNull(putter.await());
        result(cell.tryRead(), true, bottom);
        result(cell.tryTake(), true, bottom);
        pending(cell, 0, 0, 0);
    }

    @Test void everyQueuedReaderObservesTheValueBeforeTheOldestTakerConsumesIt() throws Exception {
        var cell = new ManagedMVar();
        var firstTaker = cell.beginTake();
        var firstReader = cell.beginRead();
        var secondTaker = cell.beginTake();
        var secondReader = cell.beginRead();
        pending(cell, 2, 2, 0);
        var value = new Object();
        assertTrue(cell.tryPut(value));
        assertEquals(COMMITTED, firstTaker.getState());
        assertEquals(PENDING, secondTaker.getState());
        assertSame(value, firstReader.await());
        assertSame(value, secondReader.await());
        assertSame(value, firstTaker.await());
        assertTrue(cell.isEmpty());
        pending(cell, 1, 0, 0);
        assertTrue(cell.tryPut(null));
        assertNull(secondTaker.await());
        pending(cell, 0, 0, 0);
    }

    @Test void readBroadcastWithoutTakersLeavesTheCellFull() throws Exception {
        var cell = new ManagedMVar();
        var readers = new ArrayList<ManagedMVar.Request>();
        for (int i = 0; i < 4; i++) readers.add(cell.beginRead());
        var put = cell.beginPut(null);
        assertEquals(COMMITTED, put.getState());
        for (var reader : readers) {
            assertEquals(COMMITTED, reader.getState());
            assertNull(reader.await());
        }
        result(cell.tryRead(), true, null);
        assertNull(cell.beginRead().await());
        assertFalse(cell.isEmpty());
        pending(cell, 0, 0, 0);
    }

    @Test void queuedPuttersReplaceTheFullCellInFifoOrderBeforeWakeup() throws Exception {
        var cell = new ManagedMVar();
        var original = new Object(); var first = new Object(); var second = new Object();
        assertTrue(cell.tryPut(original));
        var p1 = cell.beginPut(first); var p2 = cell.beginPut(second);
        pending(cell, 0, 0, 2);
        result(cell.tryTake(), true, original);
        assertEquals(COMMITTED, p1.getState());
        assertEquals(PENDING, p2.getState());
        assertNull(p1.pendingPutValue());
        assertFalse(cell.tryPut(new Object()));
        result(cell.tryRead(), true, first);
        assertSame(first, cell.beginTake().await());
        assertEquals(COMMITTED, p2.getState());
        result(cell.tryTake(), true, second);
        assertNull(p1.await()); assertNull(p2.await());
        result(cell.tryTake(), false, null);
        pending(cell, 0, 0, 0);
    }

    @Test void handoffBeforeAwaitCannotBeStolenAndAwaitDoesNotReplay() throws Exception {
        var cell = new ManagedMVar();
        var taker = cell.beginTake();
        var first = new Object(); var later = new Object();
        assertTrue(cell.tryPut(first));
        result(cell.tryTake(), false, null);
        assertTrue(cell.tryPut(later));
        assertSame(first, taker.await()); assertSame(first, taker.await());
        result(cell.tryTake(), true, later);
        pending(cell, 0, 0, 0);
    }

    @Test void cancellingAnyQueuedTakerOrReaderUnlinksExactlyThatRequest() throws Exception {
        for (int cancelledIndex = 0; cancelledIndex <= 2; cancelledIndex++) {
            var cell = new ManagedMVar();
            var takes = new ArrayList<ManagedMVar.Request>();
            var reads = new ArrayList<ManagedMVar.Request>();
            for (int i = 0; i < 3; i++) takes.add(cell.beginTake());
            for (int i = 0; i < 3; i++) reads.add(cell.beginRead());
            for (var request : List.of(takes.get(cancelledIndex), reads.get(cancelledIndex))) {
                assertTrue(request.cancel()); assertFalse(request.cancel());
                assertEquals(CANCELLED, request.getState());
                assertFalse(request.isQueued());
                assertThrows(CancellationException.class, request::await);
            }
            pending(cell, 2, 2, 0);
            var first = new Object(); var second = new Object();
            assertTrue(cell.tryPut(first));
            for (int i = 0; i < reads.size(); i++) if (i != cancelledIndex) assertSame(first, reads.get(i).await());
            var surviving = new ArrayList<>(takes); surviving.remove(cancelledIndex);
            assertSame(first, surviving.get(0).await());
            assertEquals(PENDING, surviving.get(1).getState());
            assertTrue(cell.tryPut(second));
            assertSame(second, surviving.get(1).await());
            pending(cell, 0, 0, 0);
        }
    }

    @Test void cancellingAnyQueuedPutterReleasesItsPayloadAndPreservesFifo() {
        for (int cancelledIndex = 0; cancelledIndex <= 2; cancelledIndex++) {
            var cell = new ManagedMVar();
            var initial = new Object();
            assertTrue(cell.tryPut(initial));
            var values = List.of(new Object(), new Object(), new Object());
            var puts = values.stream().map(cell::beginPut).toList();
            assertSame(values.get(cancelledIndex), puts.get(cancelledIndex).pendingPutValue());
            assertTrue(puts.get(cancelledIndex).cancel());
            assertNull(puts.get(cancelledIndex).pendingPutValue());
            assertFalse(puts.get(cancelledIndex).isQueued());
            pending(cell, 0, 0, 2);
            result(cell.tryTake(), true, initial);
            for (int i = 0; i < values.size(); i++) if (i != cancelledIndex) {
                assertEquals(COMMITTED, puts.get(i).getState());
                assertNull(puts.get(i).pendingPutValue());
                result(cell.tryTake(), true, values.get(i));
            }
            assertTrue(cell.isEmpty());
            pending(cell, 0, 0, 0);
        }
    }

    @Test void cancellationBeforeCommitDoesNotConsumeOrPublish() {
        var cell = new ManagedMVar();
        var take = cell.beginTake(); var read = cell.beginRead();
        assertTrue(take.cancel()); assertTrue(read.cancel());
        var value = new Object();
        assertTrue(cell.tryPut(value));
        var rejected = cell.beginPut(new Object());
        assertTrue(rejected.cancel());
        result(cell.tryTake(), true, value);
        assertTrue(cell.isEmpty());
        pending(cell, 0, 0, 0);
    }

    @Test void cancellationAfterCommitNeverRollsBackTakeReadOrPut() throws Exception {
        var cell = new ManagedMVar();
        var take = cell.beginTake(); var read = cell.beginRead();
        var value = new Object();
        assertTrue(cell.tryPut(value));
        assertFalse(take.cancel()); assertFalse(read.cancel());
        assertSame(value, take.await()); assertSame(value, read.await());
        assertTrue(cell.isEmpty());
        assertTrue(cell.tryPut(value));
        var replacement = new Object();
        var put = cell.beginPut(replacement);
        result(cell.tryTake(), true, value);
        assertFalse(put.cancel());
        assertNull(put.await());
        result(cell.tryRead(), true, replacement);
        pending(cell, 0, 0, 0);
    }

    @Test void repeatedInterruptedRetriesKeepTheSameQueuePositionAndCommittedResult() throws Exception {
        var cell = new ManagedMVar();
        var first = cell.beginTake(); var second = cell.beginTake();
        for (int i = 0; i < 3; i++) {
            Thread.currentThread().interrupt();
            try { assertThrows(InterruptedException.class, first::await); }
            finally { Thread.interrupted(); }
            assertEquals(PENDING, first.getState());
            pending(cell, 2, 0, 0);
        }
        var value = new Object();
        assertTrue(cell.tryPut(value));
        Thread.currentThread().interrupt();
        try { assertThrows(InterruptedException.class, first::await); }
        finally { Thread.interrupted(); }
        assertSame(value, first.await());
        assertEquals(PENDING, second.getState());
        assertTrue(cell.tryPut(null));
        assertNull(second.await());
        pending(cell, 0, 0, 0);
    }

    @Test void interruptionBeforeRegistrationAndCancellationOfAnUnsubmittedTokenAreSafe() throws Exception {
        var cell = new ManagedMVar();
        var request = new ManagedMVar.Request(cell, ManagedMVar.Operation.TAKE, null, null);
        Thread.currentThread().interrupt();
        try { assertThrows(InterruptedException.class, request::await); }
        finally { Thread.interrupted(); }
        assertEquals(PENDING, request.getState());
        assertFalse(request.isQueued());
        pending(cell, 0, 0, 0);
        var value = new Object();
        assertTrue(cell.tryPut(value));
        assertSame(value, request.await()); assertSame(value, request.await());
        assertTrue(cell.isEmpty());
        var abandoned = new ManagedMVar.Request(cell, ManagedMVar.Operation.PUT, new Object(), null);
        assertTrue(abandoned.cancel());
        assertNull(abandoned.pendingPutValue());
        assertThrows(CancellationException.class, abandoned::await);
        assertTrue(cell.isEmpty());
        pending(cell, 0, 0, 0);
    }

    @Test void interruptedPutAndReadRetriesDoNotRegisterTwice() throws Exception {
        for (var operation : List.of(ManagedMVar.Operation.PUT, ManagedMVar.Operation.READ)) {
            var cell = new ManagedMVar();
            var old = new Object(); var replacement = new Object();
            if (operation == ManagedMVar.Operation.PUT) assertTrue(cell.tryPut(old));
            var request = operation == ManagedMVar.Operation.PUT ? cell.beginPut(replacement) : cell.beginRead();
            for (int i = 0; i < 3; i++) {
                Thread.currentThread().interrupt();
                try { assertThrows(InterruptedException.class, request::await); }
                finally { Thread.interrupted(); }
                if (operation == ManagedMVar.Operation.PUT) pending(cell, 0, 0, 1); else pending(cell, 0, 1, 0);
            }
            if (operation == ManagedMVar.Operation.PUT) {
                result(cell.tryTake(), true, old);
                assertNull(request.await()); assertNull(request.await());
            } else {
                assertTrue(cell.tryPut(replacement));
                assertSame(replacement, request.await()); assertSame(replacement, request.await());
            }
            result(cell.tryTake(), true, replacement);
            assertTrue(cell.isEmpty());
            pending(cell, 0, 0, 0);
        }
    }

    // This observer waits for a concrete Condition queue state, not an assumed timing
    // delay. The deadline is only a failure bound; it cannot make a test pass.
    private void awaitBlocked(ManagedMVar.Request request) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!request.hasWaitingThread()) {
            if (System.nanoTime() >= deadline) fail("Request did not reach its condition wait");
            Thread.yield();
        }
    }

    @Test void conditionWaitInterruptedThenRetriedUsesOneRequestAndOneTransfer() throws Exception {
        var cell = new ManagedMVar();
        var first = cell.beginTake(); var second = cell.beginTake();
        var thread = new AtomicReference<Thread>();
        var interrupted = new CountDownLatch(1); var retry = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> {
                thread.set(Thread.currentThread());
                try { first.await(); throw new IllegalStateException("Expected interruption of the actual condition wait"); }
                catch (InterruptedException ignored) {
                    interrupted.countDown();
                    if (!retry.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Check failed.");
                    return first.await();
                }
            });
            awaitBlocked(first); thread.get().interrupt();
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            pending(cell, 2, 0, 0);
            var value = new Object();
            assertTrue(cell.tryPut(value));
            assertEquals(PENDING, second.getState());
            retry.countDown();
            assertSame(value, future.get(5, TimeUnit.SECONDS));
            assertTrue(second.cancel());
            pending(cell, 0, 0, 0);
        } finally {
            first.cancel(); second.cancel(); retry.countDown(); executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void terminalCancellationSignalsAnActualConditionWaitAndDropsPendingPayload() throws Exception {
        var cell = new ManagedMVar();
        assertTrue(cell.tryPut(new Object()));
        var put = cell.beginPut(new Object());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> { assertThrows(CancellationException.class, put::await); return true; });
            awaitBlocked(put);
            assertTrue(put.cancel());
            assertTrue(future.get(5, TimeUnit.SECONDS));
            assertNull(put.pendingPutValue()); assertFalse(put.isQueued());
            pending(cell, 0, 0, 0);
        } finally {
            put.cancel(); executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
