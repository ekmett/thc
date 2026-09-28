// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.Unit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class GuestThreadStatusTest {
    private GuestThreads registry() {
        return new GuestThreads(ThreadLocal.withInitial(() -> MaskingState.UNMASKED), new CpuAffinity(null, 2), ignored -> Unit.INSTANCE);
    }
    @Test void hostReentryKeepsJavaIdentityCapabilityAndForeignStatus() throws Exception {
        var threads = registry();
        threads.enterCurrent(null, false, true, null);
        var id = threads.currentIdentity();
        try {
            assertEquals(Thread.currentThread().threadId(), id.getJavaId());
            assertEquals(0L, id.getCapability());
            assertEquals(GuestThreadStatus.RUNNING, threads.status(id));
            var foreign = threads.enterForeign(ForeignSafety.SAFE);
            try {
                assertEquals(GuestThreadStatus.FOREIGN, threads.status(id));
                threads.enterCurrent(null, false, true, null);
                var callback = threads.currentIdentity();
                try {
                    assertNotSame(id, callback);
                    assertEquals(id.getJavaId(), callback.getJavaId());
                    assertEquals(GuestThreadStatus.RUNNING, threads.status(callback));
                    assertEquals(GuestThreadStatus.FOREIGN, threads.status(id));
                    try (var extent = GuestThreads.Companion.blocking$org_intelligence_thc(GuestThreadStatus.BLACK_HOLE)) {
                        assertEquals(GuestThreadStatus.BLACK_HOLE, threads.status(callback));
                        assertEquals(GuestThreadStatus.FOREIGN, threads.status(id));
                    }
                    assertEquals(GuestThreadStatus.RUNNING, threads.status(callback));
                } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
                assertEquals(GuestThreadStatus.FINISHED, threads.status(callback));
                assertSame(id, threads.currentIdentity());
                assertEquals(GuestThreadStatus.FOREIGN, threads.status(id));
            } finally { threads.leaveForeign(foreign); }
        } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
        assertEquals(GuestThreadStatus.FOREIGN, threads.status(id), "Returning to the live host does not kill its Java thread");
        assertEquals(AsyncRequestState.TARGET_FINISHED, threads.send(id, Unit.INSTANCE).getState(),
            "Existing throwTo mailboxes are scoped to an active guest invocation");
        threads.enterCurrent(null, false, true, null);
        try {
            assertSame(id, threads.currentIdentity());
            assertEquals(GuestThreadStatus.RUNNING, threads.status(id));
            assertEquals(2L, threads.capabilityCount$org_intelligence_thc());
            var other = registry(); other.enterCurrent(null, false, true, null);
            try {
                assertEquals(id.getJavaId(), other.currentIdentity().getJavaId());
                assertNotEquals(id, other.currentIdentity());
                assertThrows(RuntimeFault.class, () -> other.status(id));
            } finally { other.leaveCurrent(GuestThreadStatus.FINISHED); }
            assertEquals(GuestThreadStatus.RUNNING, threads.status(id));
        } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
    }
    @Test void actualMVarWaitsExposeTheirReasonsAndShareBoundedCpuCapabilities() throws Exception {
        var threads = registry(); threads.enterCurrent(null, false, true, null);
        var parent = threads.currentIdentity();
        try {
            var retained = new ArrayList<GuestThreadId>();
            for (boolean read : new boolean[]{false, true}) {
                var cell = new ManagedMVar();
                var request = read ? cell.beginRead$org_intelligence_thc() : cell.beginTake$org_intelligence_thc();
                var identity = new AtomicReference<GuestThreadId>();
                var failure = new AtomicReference<Throwable>();
                var ready = new CountDownLatch(1);
                var worker = new Thread(() -> {
                    threads.enterCurrent(null, true, true, null);
                    try {
                        identity.set(threads.currentIdentity()); ready.countDown();
                        assertEquals(42L, request.await$org_intelligence_thc());
                        assertEquals(GuestThreadStatus.RUNNING, threads.status(identity.get()));
                    } catch (Throwable error) { failure.set(error); }
                    finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
                });
                worker.start();
                try {
                    assertTrue(ready.await(5, TimeUnit.SECONDS));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!request.hasWaitingThread$org_intelligence_thc() && System.nanoTime() < deadline) Thread.yield();
                    assertTrue(request.hasWaitingThread$org_intelligence_thc());
                    var id = identity.get();
                    assertEquals(worker.threadId(), id.getJavaId());
                    assertEquals((retained.size() + 1L) % 2L, id.getCapability());
                    assertEquals(read ? GuestThreadStatus.MVAR_READ : GuestThreadStatus.MVAR, threads.status(id));
                    retained.add(id);
                } finally { cell.tryPut(42L); worker.join(5000); }
                assertFalse(worker.isAlive());
                if (failure.get() != null) throw new AssertionError("worker failed", failure.get());
                assertEquals(GuestThreadStatus.FINISHED, threads.status(identity.get()));
            }
            assertEquals(1L, retained.getFirst().getCapability());
            assertEquals(parent.getCapability(), retained.get(1).getCapability(), "More threads do not invent more CPUs");
            assertEquals(2L, threads.capabilityCount$org_intelligence_thc());
        } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
    }
    @Test void forkedFailureAndHostCarrierTerminationAreDistinct() throws Exception {
        var threads = registry();
        for (boolean forked : new boolean[]{true, false}) {
            var outcome = forked ? GuestThreadStatus.DIED : GuestThreadStatus.FINISHED;
            var id = new AtomicReference<GuestThreadId>();
            var worker = new Thread(() -> {
                threads.enterCurrent(null, forked, true, null);
                id.set(threads.currentIdentity()); threads.leaveCurrent(outcome);
            });
            worker.start(); worker.join(5000); assertFalse(worker.isAlive());
            assertEquals(outcome, threads.status(id.get()));
        }
        threads.enterCurrent(null, false, true, null);
        var id = threads.currentIdentity(); threads.close(); threads.leaveCurrent(GuestThreadStatus.FINISHED);
        assertThrows(RuntimeFault.class, () -> threads.status(id));
        // Closing an active context must unwind its process-wide observation extent.
        try (var extent = GuestThreads.Companion.blocking$org_intelligence_thc(GuestThreadStatus.MVAR)) {}
    }
    @Test void exactThreadStatusTupleRejectsWrongLanesFlagsAndRepresentations() {
        var state = new CoreRepresentation(CoreKind.VOID, false, false, List.of(), null, null, null, null, null);
        var thread = new CoreRepresentation(CoreKind.OBJECT, false, false, List.of("BoxedRep (Just Unlifted)"), null, null, null, null, null);
        var integer = new CoreRepresentation(CoreKind.LONG, false, false, List.of("IntRep"), null, null, null, null, null);
        var reps = List.of("IntRep", "IntRep", "IntRep");
        var fields = List.of(state, integer, integer, integer);
        var result = new CoreRepresentation(CoreKind.UNKNOWN, false, false, reps, fields, null, null, null, null);
        CoreGuestThreads.INSTANCE.validate("threadStatus#", List.of(thread, state), List.of(false, false), result);
        assertThrows(RuntimeFault.class, () -> CoreGuestThreads.INSTANCE.validate("threadStatus#", List.of(thread, state), List.of(true, false), result));
        assertThrows(RuntimeFault.class, () -> CoreGuestThreads.INSTANCE.validate("threadStatus#", List.of(integer, state), List.of(false, false), result));
        for (var bad : List.of(
            new CoreRepresentation(CoreKind.UNKNOWN, false, false, reps, List.of(state, integer, thread, integer), null, null, null, null),
            new CoreRepresentation(CoreKind.UNKNOWN, false, false, List.of("IntRep"), fields, null, null, null, null),
            new CoreRepresentation(CoreKind.UNKNOWN, false, false, reps, List.of(state, integer), null, null, null, null)))
            assertThrows(RuntimeFault.class, () -> CoreGuestThreads.INSTANCE.validate("threadStatus#", List.of(thread, state), List.of(false, false), bad));
    }
}
