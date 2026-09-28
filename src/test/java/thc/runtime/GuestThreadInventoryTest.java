// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import thc.runtime.Unit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class GuestThreadInventoryTest {
    private static GuestThreads registry() {
        return new GuestThreads(ThreadLocal.withInitial(() -> MaskingState.UNMASKED),
            CpuAffinity.discover(false), ignored -> {});
    }

    @Test void logicalCountIsContextLocalAndShrinkDoesNotRewriteAffinityClaims() {
        var affinity = new CpuAffinity(null, 8);
        var first = new GuestThreads(ThreadLocal.withInitial(() -> MaskingState.UNMASKED), affinity, ignored -> {});
        var second = registry();
        long originalSecond = second.capabilityCount();
        first.enterCurrent(null, false, true, 7L);
        try {
            var id = first.currentIdentity();
            id.setAffinityApplied(true); // A prior successful native request is historical evidence.
            assertEquals(7L, id.getCapability());
            first.setCapabilityCount(3);
            assertEquals(3L, first.capabilityCount());
            assertEquals(1L, id.getCapability());
            assertTrue(id.getCapabilityLocked()); assertTrue(id.getAffinityApplied());
            assertEquals(8, affinity.getCount());
            assertEquals(originalSecond, second.capabilityCount());
            first.setCapabilityCount(0xffff_ffffL);
            assertEquals(0xffff_ffffL, first.capabilityCount());
            for (long invalid : new long[]{0L, -1L, Long.MIN_VALUE, 0x1_0000_0000L, Long.MAX_VALUE}) {
                assertThrows(RuntimeFault.class, () -> first.setCapabilityCount(invalid));
                assertEquals(0xffff_ffffL, first.capabilityCount());
            }
        } finally { first.leaveCurrent(GuestThreadStatus.FINISHED); first.close(); second.close(); }
        assertThrows(RuntimeFault.class, () -> first.setCapabilityCount(1));
        assertThrows(RuntimeFault.class, first::capabilityCount);
    }

    @Test void concurrentCountUpdatesAndNewThreadAssignmentsRemainInTheSameRegistry() throws InterruptedException {
        var threads = registry();
        var failure = new AtomicReference<Throwable>();
        var start = new CountDownLatch(1);
        var workers = new ArrayList<Thread>();
        for (long count : new long[]{1, 2, 3, 4}) workers.add(new Thread(() -> {
            try {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                for (int repeat = 0; repeat < 100; repeat++) {
                    threads.setCapabilityCount(count);
                    long current = threads.capabilityCount();
                    assertTrue(current >= 1L && current <= 4L);
                }
                threads.enterCurrent(null, false, true, -1L);
                try {
                    long current = threads.currentIdentity().getCapability();
                    assertTrue(current >= 0L && current <= 3L);
                } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
            } catch (Throwable error) { failure.compareAndSet(null, error); }
        }));
        try {
            workers.forEach(Thread::start); start.countDown();
            for (var worker : workers) { worker.join(5000); assertFalse(worker.isAlive()); }
            if (failure.get() != null) throw new AssertionError("capability worker failed", failure.get());
            threads.setCapabilityCount(2);
            var observed = new AtomicReference<GuestThreadId>();
            var child = new Thread(() -> {
                threads.enterCurrent(null, false, true, 7L);
                try { observed.set(threads.currentIdentity()); }
                finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
            });
            child.start(); child.join(5000); assertFalse(child.isAlive());
            assertEquals(1L, observed.get().getCapability());
            threads.setCapabilityCount(1);
            assertEquals(0L, observed.get().getCapability(), "Retained finished ThreadId# is normalized too");
        } finally { threads.close(); }
    }

    @Test void currentEntryIsRequiredAndContextsNeverEnumerateHostThreadsOrEachOther() {
        var first = registry();
        var second = registry();
        assertThrows(RuntimeFault.class, first::snapshot);
        assertThrows(RuntimeFault.class, first::isCurrentBound);
        first.enterCurrent(null, false, true, null);
        try {
            var self = first.currentIdentity();
            assertArrayEquals(new Object[]{self}, first.snapshot());
            assertFalse(first.isCurrentBound());
            first.enterCurrent(null, false, true, null);
            try { assertArrayEquals(new Object[]{self}, first.snapshot(), "Nested entries do not duplicate a thread"); }
            finally { first.leaveCurrent(GuestThreadStatus.FINISHED); }
            second.enterCurrent(null, false, true, null);
            try {
                var other = second.currentIdentity();
                assertEquals(self.getJavaId(), other.getJavaId());
                assertNotEquals(self, other);
                assertArrayEquals(new Object[]{other}, second.snapshot());
                assertArrayEquals(new Object[]{self}, first.snapshot());
                assertFalse(second.isCurrentBound());
            } finally { second.leaveCurrent(GuestThreadStatus.FINISHED); second.close(); }
            var foreign = first.enterForeign(ForeignSafety.SAFE);
            try {
                first.enterCurrent(null, false, true, null);
                try { assertTrue(first.isCurrentBound(), "A callback is bound for this reverse-entry lifetime"); }
                finally { first.leaveCurrent(GuestThreadStatus.FINISHED); }
            } finally { first.leaveForeign(foreign); }
        } finally { first.leaveCurrent(GuestThreadStatus.FINISHED); first.close(); }
        assertThrows(RuntimeFault.class, first::snapshot);
        assertThrows(RuntimeFault.class, first::isCurrentBound);
    }

    @Test void concurrentSnapshotsAreIndependentAndRetainFinishedIdentitiesNotCarriers() throws InterruptedException {
        var threads = registry();
        var ready = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        var ids = new ArrayList<AtomicReference<GuestThreadId>>();
        for (int index = 0; index < 4; index++) ids.add(new AtomicReference<>());
        var failure = new AtomicReference<Throwable>();
        threads.enterCurrent(null, false, true, null);
        var initial = threads.snapshot();
        var workers = new ArrayList<Thread>();
        for (int index = 0; index < ids.size(); index++) {
            var identity = ids.get(index);
            var outcome = index == 0 ? GuestThreadStatus.DIED : GuestThreadStatus.FINISHED;
            workers.add(new Thread(() -> {
                threads.enterCurrent(null, true, true, null);
                try {
                    identity.set(threads.currentIdentity());
                    assertFalse(threads.isCurrentBound());
                    ready.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (Throwable error) { failure.compareAndSet(null, error); }
                finally { threads.leaveCurrent(outcome); }
            }));
        }
        try {
            workers.forEach(Thread::start);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            var expected = new HashSet<GuestThreadId>();
            for (var identity : ids) expected.add(identity.get());
            expected.add(threads.currentIdentity());
            var snapshot = ManagedArray.require(threads.snapshot());
            assertEquals(expected, new HashSet<>(Arrays.asList(snapshot)));
            assertEquals(5, snapshot.length);
            assertEquals(1, initial.length, "An older snapshot never grows");
            var changed = threads.snapshot();
            changed[0] = null;
            assertEquals(expected, new HashSet<>(Arrays.asList(threads.snapshot())), "Guest array writes cannot mutate the registry");
            assertEquals(expected, new HashSet<>(Arrays.asList(snapshot)), "Snapshots never share their backing array");
            release.countDown();
            for (var worker : workers) { worker.join(5000); assertFalse(worker.isAlive()); }
            if (failure.get() != null) throw new AssertionError("guest worker failed", failure.get());
            for (int index = 0; index < ids.size(); index++) {
                var id = ids.get(index).get();
                assertEquals(index == 0 ? GuestThreadStatus.DIED : GuestThreadStatus.FINISHED, threads.status(id));
                id.getCarrier().clear(); // Observation must not depend on the carrier's continued lifetime.
            }
            assertEquals(expected, new HashSet<>(Arrays.asList(threads.snapshot())));
            assertEquals(expected, new HashSet<>(Arrays.asList(snapshot)));
            threads.close();
            assertEquals(expected, new HashSet<>(Arrays.asList(snapshot)), "Closing the registry cannot rewrite guest snapshots");
            assertThrows(RuntimeFault.class, threads::snapshot);
        } finally {
            release.countDown();
            for (var worker : workers) if (worker.isAlive()) worker.join(5000);
            threads.leaveCurrent(GuestThreadStatus.FINISHED);
            threads.close();
        }
    }

    private static CoreRepresentation tuple(CoreRepresentation... fields) {
        var reps = new ArrayList<String>();
        for (var field : fields) reps.addAll(field.getPrimReps());
        return new CoreRepresentation(CoreKind.UNKNOWN, false, false, reps, List.of(fields), null, null, null, null);
    }

    @Test void stateTupleAndReadOnlyUnliftedArrayContractsRetainCarrierChecks() {
        var state = new CoreRepresentation(CoreKind.VOID, false, false, List.of(), null, null, null, null, null);
        var objectRep = new CoreRepresentation(CoreKind.OBJECT, false, false,
            List.of("BoxedRep (Just Unlifted)"), null, null, null, null, null);
        var integer = new CoreRepresentation(CoreKind.LONG, false, false, List.of("IntRep"), null, null, null, null, null);
        for (var name : List.of("listThreads#", "isCurrentThreadBound#")) {
            var value = name.equals("listThreads#") ? objectRep : integer;
            var result = tuple(state, value);
            CoreThreadObservation.validate(name, List.of(state), List.of(false), result);
            assertThrows(RuntimeFault.class, () -> CoreThreadObservation.validate(name, List.of(state), List.of(true), result));
            assertThrows(RuntimeFault.class, () -> CoreThreadObservation.validate(name, List.of(integer), List.of(false), result));
            for (var bad : List.of(tuple(value, state), tuple(value), value, tuple(state, state)))
                assertThrows(RuntimeFault.class, () -> CoreThreadObservation.validate(name, List.of(state), List.of(false), bad));
        }
        ArrayOp.INDEX.validate(List.of(objectRep, integer), List.of(false, false), tuple(objectRep));
        ArrayOp.READ.validate(List.of(objectRep, integer, state), List.of(false, false, false), tuple(state, objectRep));
        assertThrows(RuntimeFault.class, () -> ArrayOp.INDEX.validate(List.of(objectRep, integer), List.of(false, false), tuple(integer)));
        ArrayOp.NEW.validate(List.of(integer, objectRep, state), List.of(false, false, false), tuple(state, objectRep));
        assertThrows(RuntimeFault.class, () -> ArrayOp.NEW.validate(List.of(integer, integer, state), List.of(false, false, false), tuple(state, objectRep)));
    }
}
