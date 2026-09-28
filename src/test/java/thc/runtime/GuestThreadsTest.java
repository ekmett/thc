// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.nodes.Node;
import kotlin.Unit;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class GuestThreadsTest {
    private final Node node = new Node() {};
    private boolean await(CountDownLatch gate) {
        try { return gate.await(5, TimeUnit.SECONDS); }
        catch (InterruptedException failure) { return GuestThreadsTest.<Boolean, RuntimeException>rethrow(failure); }
    }
    private void join(Thread thread) {
        try { thread.join(5000); }
        catch (InterruptedException failure) { GuestThreadsTest.<Void, RuntimeException>rethrow(failure); }
    }
    // Preserve checked exceptions crossing Java Runnable / Kotlin callback boundaries.
    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T rethrow(Throwable failure) throws E { throw (E) failure; }

    @Test void pollCellRetainsNestedEntriesButSeparatesContextsAndClearsCompletedTargets() {
        var outer = new GuestThreads(ThreadLocal.withInitial(() -> MaskingState.UNMASKED), CpuAffinity.Companion.discover(false), target -> Unit.INSTANCE);
        var inner = new GuestThreads(ThreadLocal.withInitial(() -> MaskingState.UNMASKED), CpuAffinity.Companion.discover(false), target -> Unit.INSTANCE);
        var carrier = Thread.currentThread();
        var outerCell = outer.pollState$org_intelligence_thc(carrier); var innerCell = inner.pollState$org_intelligence_thc(carrier);
        assertFalse(outerCell == innerCell); assertNull(outerCell.getCurrent$org_intelligence_thc());
        outer.enterCurrent(null, false, true, null);
        var original = Objects.requireNonNull(outerCell.getCurrent$org_intelligence_thc());
        try {
            assertSame(outerCell, outer.pollState$org_intelligence_thc(carrier));
            outer.enterCurrent(null, false, true, null);
            try { assertSame(original, outerCell.getCurrent$org_intelligence_thc()); } finally { outer.leaveCurrent(GuestThreadStatus.FINISHED); }
            assertSame(original, outerCell.getCurrent$org_intelligence_thc());
            inner.enterCurrent(null, false, true, null);
            try { assertFalse(original == innerCell.getCurrent$org_intelligence_thc()); assertSame(original, outerCell.getCurrent$org_intelligence_thc()); }
            finally { inner.leaveCurrent(GuestThreadStatus.FINISHED); }
            assertNull(innerCell.getCurrent$org_intelligence_thc());
        } finally { outer.leaveCurrent(GuestThreadStatus.FINISHED); }
        assertNull(outerCell.getCurrent$org_intelligence_thc());
        outer.enterCurrent(null, false, true, null);
        try { assertSame(outerCell, outer.pollState$org_intelligence_thc(carrier)); assertFalse(original == outerCell.getCurrent$org_intelligence_thc()); }
        finally { outer.leaveCurrent(GuestThreadStatus.FINISHED); }
        assertNull(outerCell.getCurrent$org_intelligence_thc()); outer.close(); inner.close();
    }

    @Test void nonresumableForkRejectsExternalSendBeforeWakeButAllowsSelfAndDeadTargets() {
        var masks = ThreadLocal.withInitial(() -> MaskingState.UNMASKED); var wakes = new AtomicInteger();
        var threads = new GuestThreads(masks, CpuAffinity.Companion.discover(false), target -> { wakes.incrementAndGet(); return Unit.INSTANCE; });
        var ready = new CountDownLatch(1); var finish = new CountDownLatch(1);
        var identity = new AtomicReference<GuestThreadId>(); var failure = new AtomicReference<Throwable>();
        var worker = new Thread(() -> {
            threads.enterCurrent(MaskingState.MASKED_UNINTERRUPTIBLE, true, false, null);
            try {
                var self = threads.currentIdentity(); identity.set(self);
                threads.enterCurrent(null, false, true, null); // Default arguments cannot upgrade this lifetime.
                try {
                    ready.countDown(); assertTrue(await(finish));
                    assertNull(threads.poll(node, true), "Rejected external sends never enter the queue");
                    var sent = threads.send(self, "self"); assertTrue(sent.getForceSelf$org_intelligence_thc());
                    assertSame(sent, threads.poll(node, false)); sent.acknowledge();
                    assertEquals(AsyncRequestState.ACKNOWLEDGED, sent.getState());
                } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
            } catch (Throwable error) { failure.set(error); ready.countDown(); }
            finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
        });
        worker.start();
        try {
            assertTrue(await(ready));
            if (failure.get() != null) throw new AssertionError("fork worker failed", failure.get());
            for (int i = 0; i < 3; i++) assertThrows(UnsupportedCore.class, () -> threads.send(identity.get(), "external"));
            assertEquals(0, wakes.get());
        } finally { finish.countDown(); join(worker); }
        assertFalse(worker.isAlive());
        if (failure.get() != null) throw new AssertionError("fork worker failed", failure.get());
        assertEquals(AsyncRequestState.TARGET_FINISHED, threads.send(identity.get(), "late").getState());
        assertEquals(0, wakes.get()); threads.close();
    }

    @Test void javaThreadIdAndMaskingGateQueuedDelivery() {
        var masks = ThreadLocal.withInitial(() -> MaskingState.UNMASKED); var wakes = new AtomicInteger();
        var threads = new GuestThreads(masks, CpuAffinity.Companion.discover(false), target -> { wakes.incrementAndGet(); return Unit.INSTANCE; });
        var ready = new CountDownLatch(1); var proceed = new CountDownLatch(1); var id = new AtomicLong();
        var firstSeen = new AtomicReference<AsyncRequest>(); var secondSeen = new AtomicReference<AsyncRequest>();
        var worker = new Thread(() -> {
            id.set(threads.registerCurrent()); masks.set(MaskingState.MASKED_UNINTERRUPTIBLE); ready.countDown();
            try {
                assertTrue(await(proceed)); assertNull(threads.poll(node, true));
                masks.set(MaskingState.MASKED_INTERRUPTIBLE); assertNull(threads.poll(node, false));
                var first = Objects.requireNonNull(threads.poll(node, true)); firstSeen.set(first);
                assertNull(threads.poll(node, true), "A claimed request blocks later FIFO entries"); first.acknowledge();
                masks.set(MaskingState.UNMASKED);
                var second = Objects.requireNonNull(threads.poll(node, false)); secondSeen.set(second); second.acknowledge();
                assertNull(threads.poll(node, false));
            } finally { threads.completeCurrent(); }
        });
        worker.start(); assertTrue(await(ready));
        assertEquals(worker.threadId(), Objects.requireNonNull(threads.pollState$org_intelligence_thc(worker).getCurrent$org_intelligence_thc()).getIdentity().getJavaId());
        assertEquals(id.get(), Objects.requireNonNull(threads.pollState$org_intelligence_thc(worker).getCurrent$org_intelligence_thc()).getIdentity().getLogicalId());
        var first = threads.send(id.get(), "first"); var second = threads.send(id.get(), "second");
        assertEquals(2, wakes.get()); proceed.countDown(); join(worker); assertFalse(worker.isAlive());
        assertSame(first, firstSeen.get()); assertSame(second, secondSeen.get());
        assertEquals(AsyncRequestState.ACKNOWLEDGED, first.getState()); assertEquals(AsyncRequestState.ACKNOWLEDGED, second.getState());
        assertEquals("first", first.getPayload());
        assertEquals(AsyncRequestState.TARGET_FINISHED, threads.send(id.get(), "late").getState());
    }

    @Test void cancellationAndTargetCompletionWakePendingSenders() {
        var masks = ThreadLocal.withInitial(() -> MaskingState.UNMASKED);
        var threads = new GuestThreads(masks, CpuAffinity.Companion.discover(false), target -> Unit.INSTANCE);
        var ready = new CountDownLatch(1); var finish = new CountDownLatch(1); var id = new AtomicLong();
        var worker = new Thread(() -> {
            id.set(threads.registerCurrent()); ready.countDown();
            try { assertTrue(await(finish)); } finally { threads.completeCurrent(); }
        });
        worker.start(); assertTrue(await(ready));
        var cancelled = threads.send(id.get(), "cancel"); assertTrue(cancelled.cancel()); assertFalse(cancelled.cancel());
        var pending = threads.send(id.get(), "pending"); finish.countDown(); join(worker); assertFalse(worker.isAlive());
        assertEquals(AsyncRequestState.CANCELLED, cancelled.getState()); assertEquals(AsyncRequestState.TARGET_FINISHED, pending.getState());
        assertNull(pending.getTarget() != null && pending.getTarget().isAlive() ? pending.getTarget() : null);
    }

    @Test void nestedEntryRetainsTargetAndInheritedMaskUntilOuterExit() {
        var masks = ThreadLocal.withInitial(() -> MaskingState.UNMASKED);
        var threads = new GuestThreads(masks, CpuAffinity.Companion.discover(false), target -> Unit.INSTANCE);
        long id = threads.enterCurrent(MaskingState.MASKED_UNINTERRUPTIBLE, false, true, null);
        assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, masks.get());
        assertEquals(id, threads.enterCurrent(MaskingState.UNMASKED, false, true, null));
        threads.leaveCurrent(GuestThreadStatus.FINISHED);
        var submitted = new AtomicReference<AsyncRequest>();
        var sender = new Thread(() -> submitted.set(threads.send(id, "nested"))); sender.start(); join(sender); assertFalse(sender.isAlive());
        var request = submitted.get(); assertEquals(AsyncRequestState.PENDING, request.getState());
        assertNull(threads.poll(node, true)); threads.leaveCurrent(GuestThreadStatus.FINISHED);
        assertEquals(AsyncRequestState.TARGET_FINISHED, request.getState()); assertEquals(MaskingState.UNMASKED, masks.get());
    }

    @Test void unsafeReverseEntryIsRejectedBeforeIdentityMaskOrAccountingMutation() {
        for (boolean crossContext : new boolean[]{false, true}) for (boolean alreadyEntered : new boolean[]{false, true}) {
            var outerMasks = ThreadLocal.withInitial(() -> MaskingState.UNMASKED);
            var innerMasks = ThreadLocal.withInitial(() -> MaskingState.UNMASKED);
            var outer = new GuestThreads(outerMasks, CpuAffinity.Companion.discover(false), target -> Unit.INSTANCE);
            var inner = crossContext ? new GuestThreads(innerMasks, CpuAffinity.Companion.discover(false), target -> Unit.INSTANCE) : outer;
            outer.enterCurrent(MaskingState.MASKED_INTERRUPTIBLE, false, true, null); var caller = outer.currentIdentity();
            if (crossContext && alreadyEntered) inner.enterCurrent(MaskingState.MASKED_UNINTERRUPTIBLE, false, true, null);
            var innerIdentity = !crossContext || alreadyEntered ? inner.currentIdentity() : null;
            var before = innerIdentity != null ? Arrays.asList(inner.snapshot()) : List.of();
            var previousMask = crossContext ? innerMasks.get() : outerMasks.get();
            try {
                var foreign = outer.enterForeign(ForeignSafety.UNSAFE); // Default leaf authority must stay unsafe.
                try {
                    var failure = assertThrows(RuntimeFault.class, () -> inner.enterCurrent(null, false, true, null));
                    assertTrue(Objects.requireNonNull(failure.getMessage()).contains("Unsafe foreign call"));
                    assertFalse(caller.getAllocationSuspended$org_intelligence_thc());
                    assertEquals(previousMask, crossContext ? innerMasks.get() : outerMasks.get());
                    if (innerIdentity != null) {
                        assertSame(innerIdentity, inner.currentIdentity()); assertEquals(before, Arrays.asList(inner.snapshot()));
                        assertFalse(innerIdentity.getAllocationSuspended$org_intelligence_thc());
                    } else assertThrows(RuntimeFault.class, inner::currentIdentity);
                } finally { outer.leaveForeign(foreign); }
                // The denied entry leaves no permission or allocation residue.
                var safe = outer.enterForeign(ForeignSafety.SAFE);
                try {
                    inner.enterCurrent(null, false, true, null);
                    try { assertTrue(inner.isCurrentBound()); assertNotSame(innerIdentity, inner.currentIdentity()); }
                    finally { inner.leaveCurrent(GuestThreadStatus.FINISHED); }
                } finally { outer.leaveForeign(safe); }
            } finally {
                if (crossContext && alreadyEntered) inner.leaveCurrent(GuestThreadStatus.FINISHED);
                outer.leaveCurrent(GuestThreadStatus.FINISHED); outer.close(); if (crossContext) inner.close();
            }
        }
    }

    @Test void innermostDeclarationAndClosedOriginControlNestedCallbacks() {
        var outer = new GuestThreads(ThreadLocal.withInitial(() -> MaskingState.UNMASKED), CpuAffinity.Companion.discover(false), target -> Unit.INSTANCE);
        var inner = new GuestThreads(ThreadLocal.withInitial(() -> MaskingState.UNMASKED), CpuAffinity.Companion.discover(false), target -> Unit.INSTANCE);
        outer.enterCurrent(null, false, true, null); var caller = outer.currentIdentity(); var safe = outer.enterForeign(ForeignSafety.SAFE);
        try {
            inner.enterCurrent(null, false, true, null); var callback = inner.currentIdentity();
            try {
                var unsafe = inner.enterForeign(ForeignSafety.UNSAFE);
                try {
                    assertThrows(RuntimeFault.class, () -> outer.enterCurrent(null, false, true, null));
                    assertThrows(RuntimeFault.class, () -> inner.enterCurrent(null, false, true, null));
                    assertSame(callback, inner.currentIdentity()); assertSame(caller, outer.currentIdentity());
                } finally { inner.leaveForeign(unsafe); }
                var nested = inner.enterForeign(ForeignSafety.SAFE);
                try {
                    outer.enterCurrent(null, false, true, null);
                    try { assertTrue(outer.isCurrentBound()); assertNotSame(caller, outer.currentIdentity()); }
                    finally { outer.leaveCurrent(GuestThreadStatus.FINISHED); }
                } finally { inner.leaveForeign(nested); }
            } finally { inner.leaveCurrent(GuestThreadStatus.FINISHED); }
            outer.close();
            assertThrows(RuntimeFault.class, () -> outer.enterForeign(ForeignSafety.UNSAFE));
            assertThrows(RuntimeFault.class, () -> inner.enterCurrent(null, false, true, null));
        } finally { outer.leaveForeign(safe); outer.leaveCurrent(GuestThreadStatus.FINISHED); inner.close(); }
        // Popped activations must not poison subsequent contexts on this carrier.
        var fresh = new GuestThreads(ThreadLocal.withInitial(() -> MaskingState.UNMASKED), CpuAffinity.Companion.discover(false), target -> Unit.INSTANCE);
        fresh.enterCurrent(null, false, true, null);
        try { assertFalse(fresh.isCurrentBound()); } finally { fresh.leaveCurrent(GuestThreadStatus.FINISHED); fresh.close(); }
    }

    @Test void crossContextSendKeepsCallerMaskAndFifoAcrossOrdinaryAndCallbackEntries() {
        for (boolean callback : new boolean[]{false, true}) for (var mask : MaskingState.values()) {
            var callerMasks = ThreadLocal.withInitial(() -> MaskingState.UNMASKED); var callbackMasks = ThreadLocal.withInitial(() -> MaskingState.UNMASKED);
            var wakes = new AtomicInteger();
            var caller = new GuestThreads(callerMasks, CpuAffinity.Companion.discover(false), target -> { wakes.incrementAndGet(); return Unit.INSTANCE; });
            var other = new GuestThreads(callbackMasks, CpuAffinity.Companion.discover(false), target -> Unit.INSTANCE);
            caller.enterCurrent(mask, false, true, null); var callerId = caller.currentIdentity();
            AsyncRequest first; AsyncRequest second;
            try {
                var foreign = callback ? caller.enterForeign(ForeignSafety.SAFE) : null;
                try {
                    other.enterCurrent(null, false, true, null);
                    try {
                        var otherId = other.currentIdentity(); assertNotSame(callerId, otherId); assertEquals(callerId.getJavaId(), otherId.getJavaId());
                        first = caller.send(callerId, "first"); second = caller.send(callerId, "second");
                        assertFalse(first.getForceSelf$org_intelligence_thc(), "A suspended context's slot is not the active sender"); assertFalse(second.getForceSelf$org_intelligence_thc());
                        assertEquals(2, wakes.get(), "Cross-context requests use external delivery");
                        assertNull(caller.poll(node, true), "Only the active guest may claim a request");
                        callbackMasks.set(MaskingState.MASKED_UNINTERRUPTIBLE);
                        var self = other.send(otherId, "actual self"); assertTrue(self.getForceSelf$org_intelligence_thc());
                        assertSame(self, other.poll(node, false), "The actual sender still has self delivery"); self.acknowledge();
                    } finally { other.leaveCurrent(GuestThreadStatus.FINISHED); }
                } finally { if (foreign != null) caller.leaveForeign(foreign); }
                assertSame(callerId, caller.currentIdentity()); assertEquals(mask, callerMasks.get());
                assertEquals(AsyncRequestState.PENDING, first.getState()); assertEquals(AsyncRequestState.PENDING, second.getState());
                var claimed = switch (mask) {
                    case UNMASKED -> caller.poll(node, false);
                    case MASKED_INTERRUPTIBLE -> { assertNull(caller.poll(node, false)); yield caller.poll(node, true); }
                    case MASKED_UNINTERRUPTIBLE -> {
                        assertNull(caller.poll(node, false)); assertNull(caller.poll(node, true));
                        callerMasks.set(MaskingState.UNMASKED); yield caller.poll(node, false);
                    }
                };
                assertSame(first, claimed, "Cross-context sends retain FIFO order");
                assertNull(caller.poll(node, true), "A claimed request excludes the next request"); first.acknowledge();
                assertSame(second, caller.poll(node, true)); second.acknowledge(); assertNull(caller.poll(node, true));
            } finally { caller.leaveCurrent(GuestThreadStatus.FINISHED); other.close(); caller.close(); }
        }
    }

    @Test void inactiveContextCannotClaimItsMailboxOnAnotherGuestsCarrier() {
        var caller = new GuestThreads(ThreadLocal.withInitial(() -> MaskingState.UNMASKED), CpuAffinity.Companion.discover(false), target -> Unit.INSTANCE);
        var other = new GuestThreads(ThreadLocal.withInitial(() -> MaskingState.UNMASKED), CpuAffinity.Companion.discover(false), target -> Unit.INSTANCE);
        caller.enterCurrent(null, false, true, null); var callerId = caller.currentIdentity(); var submitted = new AtomicReference<AsyncRequest>();
        try {
            var sender = new Thread(() -> submitted.set(caller.send(callerId, "external"))); sender.start(); join(sender); assertFalse(sender.isAlive());
            var request = submitted.get(); assertFalse(request.getForceSelf$org_intelligence_thc());
            other.enterCurrent(null, false, true, null);
            try {
                assertNull(caller.poll(node, true), "A retained context slot cannot claim for an inactive guest");
                assertEquals(AsyncRequestState.PENDING, request.getState());
                var self = other.send(other.currentIdentity(), "active self"); assertSame(self, other.poll(node, false)); self.acknowledge();
            } finally { other.leaveCurrent(GuestThreadStatus.FINISHED); }
            assertSame(callerId, caller.currentIdentity()); assertSame(request, caller.poll(node, false)); request.acknowledge();
        } finally { caller.leaveCurrent(GuestThreadStatus.FINISHED); other.close(); caller.close(); }
    }

    @Test void crossContextSendCannotBypassNonresumableTargetAdmission() {
        for (boolean callback : new boolean[]{false, true}) {
            var wakes = new AtomicInteger();
            var caller = new GuestThreads(ThreadLocal.withInitial(() -> MaskingState.UNMASKED), CpuAffinity.Companion.discover(false), target -> { wakes.incrementAndGet(); return Unit.INSTANCE; });
            var other = new GuestThreads(ThreadLocal.withInitial(() -> MaskingState.UNMASKED), CpuAffinity.Companion.discover(false), target -> Unit.INSTANCE);
            caller.enterCurrent(MaskingState.MASKED_UNINTERRUPTIBLE, true, false, null); var callerId = caller.currentIdentity();
            try {
                var foreign = callback ? caller.enterForeign(ForeignSafety.SAFE) : null;
                try {
                    other.enterCurrent(null, false, true, null);
                    try { assertThrows(UnsupportedCore.class, () -> caller.send(callerId, "external")); assertEquals(0, wakes.get()); }
                    finally { other.leaveCurrent(GuestThreadStatus.FINISHED); }
                } finally { if (foreign != null) caller.leaveForeign(foreign); }
                assertNull(caller.poll(node, true), "Rejected sends never entered the caller mailbox");
                var self = caller.send(callerId, "actual self"); assertTrue(self.getForceSelf$org_intelligence_thc());
                assertSame(self, caller.poll(node, false)); self.acknowledge();
            } finally { caller.leaveCurrent(GuestThreadStatus.FINISHED); other.close(); caller.close(); }
        }
    }
    @Test void callbackIdentityMailboxAndMaskAreIsolatedOnTheSameCarrier() {
        for (var mask : MaskingState.values()) {
            var masks = ThreadLocal.withInitial(() -> MaskingState.UNMASKED);
            var threads = new GuestThreads(masks, CpuAffinity.Companion.discover(false), target -> Unit.INSTANCE);
            threads.enterCurrent(mask, false, true, null);
            var caller = threads.currentIdentity();
            var original = Thread.currentThread();
            var callerPoll = threads.pollState$org_intelligence_thc(original);
            var callerStack = callerPoll.getAstStack$org_intelligence_thc();
            try {
                var submitted = new AtomicReference<AsyncRequest>();
                var sender = new Thread(() -> submitted.set(threads.send(caller, "external")));
                sender.start(); join(sender);
                assertFalse(sender.isAlive());
                var external = submitted.get();
                var prior = threads.enterForeign(ForeignSafety.SAFE);
                GuestThreadId callback;
                AsyncRequest callerFromCallback;
                try {
                    assertNull(threads.poll(node, true), "Foreign code cannot claim the caller's request");
                    threads.enterCurrent(null, false, true, null);
                    callback = threads.currentIdentity();
                    try {
                        assertNotEquals(caller, callback);
                        assertEquals(caller.getJavaId(), callback.getJavaId());
                        assertSame(original, callback.getCarrier$org_intelligence_thc().get());
                        assertTrue(threads.isCurrentBound());
                        assertEquals(MaskingState.UNMASKED, masks.get());
                        assertNotSame(callerStack, callerPoll.getAstStack$org_intelligence_thc());
                        assertNull(threads.poll(node, true), "The callback has a separate mailbox");
                        assertEquals(GuestThreadStatus.FOREIGN, threads.status(caller));
                        callerFromCallback = threads.send(caller, "caller from callback");
                        assertFalse(callerFromCallback.getForceSelf$org_intelligence_thc(), "Same carrier does not mean self throwTo");
                        assertEquals(callback.getLogicalId(), threads.enterCurrent(null, false, true, null), "Ordinary nesting retains identity");
                        try { assertSame(callback, threads.currentIdentity()); } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
                        var self = threads.send(callback, "callback self");
                        masks.set(MaskingState.MASKED_UNINTERRUPTIBLE);
                        assertSame(self, threads.poll(node, false), "Self throwTo still bypasses this guest's mask");
                        self.acknowledge();
                        var nestedForeign = threads.enterForeign(ForeignSafety.SAFE);
                        try {
                            threads.enterCurrent(null, false, true, null);
                            var nested = threads.currentIdentity();
                            try {
                                assertNotEquals(callback, nested); assertNotEquals(caller, nested);
                                assertEquals(original.threadId(), nested.getJavaId());
                                assertTrue(threads.isCurrentBound());
                                assertEquals(MaskingState.UNMASKED, masks.get());
                                assertNull(threads.poll(node, true));
                            } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
                            assertEquals(GuestThreadStatus.FINISHED, threads.status(nested));
                            assertSame(callback, threads.currentIdentity());
                            assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, masks.get());
                        } finally { threads.leaveForeign(nestedForeign); }
                    } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
                    assertSame(caller, threads.currentIdentity());
                    assertSame(callerStack, callerPoll.getAstStack$org_intelligence_thc());
                    assertEquals(mask, masks.get());
                    assertEquals(GuestThreadStatus.FINISHED, threads.status(callback));
                    assertNull(threads.liveJavaId$org_intelligence_thc(callback));
                    assertEquals(AsyncRequestState.TARGET_FINISHED, threads.send(callback, "retired").getState());
                    assertEquals(AsyncRequestState.PENDING, external.getState());
                    assertNull(threads.poll(node, true));
                } finally { threads.leaveForeign(prior); }
                assertFalse(threads.isCurrentBound());
                if (mask != MaskingState.UNMASKED) assertNull(threads.poll(node, false));
                masks.set(MaskingState.UNMASKED);
                assertSame(external, threads.poll(node, false)); external.acknowledge();
                assertSame(callerFromCallback, threads.poll(node, false)); callerFromCallback.acknowledge();
                assertNull(threads.poll(node, false));
            } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); threads.close(); }
        }
    }

    @Test void callbackAllocationsAreChargedOnlyToTheirLogicalIdentity() {
        for (boolean crossContext : List.of(false, true)) {
            var outer = new GuestThreads(ThreadLocal.withInitial(() -> MaskingState.UNMASKED), CpuAffinity.Companion.discover(false), target -> Unit.INSTANCE);
            var callbacks = crossContext ? new GuestThreads(ThreadLocal.withInitial(() -> MaskingState.UNMASKED), CpuAffinity.Companion.discover(false), target -> Unit.INSTANCE) : outer;
            var retained = new ArrayList<byte[]>();
            outer.enterCurrent(null, false, true, null);
            var caller = outer.currentIdentity();
            try {
                outer.setAllocationCounter(10_000_000L, outer.currentIdentity());
                var foreign = outer.enterForeign(ForeignSafety.SAFE);
                try {
                    callbacks.enterCurrent(null, false, true, null);
                    var first = callbacks.currentIdentity();
                    try {
                        assertTrue(caller.getAllocationSuspended$org_intelligence_thc());
                        assertEquals(-1L, caller.getAllocationBaseline$org_intelligence_thc());
                        callbacks.setAllocationCounter(10_000_000L, callbacks.currentIdentity());
                        retained.add(new byte[2_000_000]);
                        assertTrue(callbacks.allocationCounter() < 8_000_000L);
                        var nestedForeign = callbacks.enterForeign(ForeignSafety.SAFE);
                        try {
                            callbacks.enterCurrent(null, false, true, null);
                            try {
                                assertTrue(first.getAllocationSuspended$org_intelligence_thc());
                                callbacks.setAllocationCounter(9_000_000L, first);
                                assertEquals(-1L, first.getAllocationBaseline$org_intelligence_thc(), "Resetting a suspended counter must not restart charging");
                                callbacks.setAllocationCounter(10_000_000L, callbacks.currentIdentity());
                                retained.add(new byte[2_000_000]);
                                assertTrue(callbacks.allocationCounter() < 8_000_000L);
                                assertEquals(9_000_000L, first.getAllocationRemaining$org_intelligence_thc());
                                outer.setAllocationCounter(11_000_000L, caller);
                                assertEquals(-1L, caller.getAllocationBaseline$org_intelligence_thc());
                            } finally { callbacks.leaveCurrent(GuestThreadStatus.FINISHED); }
                        } finally { callbacks.leaveForeign(nestedForeign); }
                        assertFalse(first.getAllocationSuspended$org_intelligence_thc());
                        long counter = callbacks.allocationCounter();
                        assertTrue(counter >= 8_900_000L && counter <= 9_000_000L,
                            "The nested callback's two megabytes belong only to that callback");
                        retained.add(new byte[2_000_000]);
                        assertEquals(11_000_000L, caller.getAllocationRemaining$org_intelligence_thc());
                    } finally { callbacks.leaveCurrent(GuestThreadStatus.FINISHED); }
                } finally { outer.leaveForeign(foreign); }
                assertFalse(caller.getAllocationSuspended$org_intelligence_thc());
                long counter = outer.allocationCounter();
                assertTrue(counter >= 10_900_000L && counter <= 11_000_000L,
                    "Neither nested callback is charged to the suspended caller");
                assertEquals(6_000_000, retained.stream().mapToInt(bytes -> bytes.length).sum());
                // Missing accounting evidence stays missing across a callback.
                caller.setAllocationUnavailable$org_intelligence_thc(true);
                var again = outer.enterForeign(ForeignSafety.SAFE);
                try { callbacks.enterCurrent(null, false, true, null); callbacks.leaveCurrent(GuestThreadStatus.FINISHED); }
                finally { outer.leaveForeign(again); }
                assertThrows(RuntimeFault.class, outer::allocationCounter);
            } finally { outer.leaveCurrent(GuestThreadStatus.FINISHED); outer.close(); if (crossContext) callbacks.close(); }
        }
    }

    @Test void foreignScopeBeforeRegistrationAndExceptionalCallbackExitRestorePermission() {
        var threads = new GuestThreads(ThreadLocal.withInitial(() -> MaskingState.UNMASKED), CpuAffinity.Companion.discover(false), target -> Unit.INSTANCE);
        var prior = threads.enterForeign(ForeignSafety.SAFE);
        try {
            assertNull(threads.poll(node, false));
            assertThrows(RuntimeFault.class, threads::currentId);
            long id = threads.enterCurrent(null, false, true, null);
            var request = threads.send(id, new Object());
            try {
                var failure = assertThrows(ForeignCallbackAsyncFailure.class, () -> {
                    try { AsyncContinuations.uncaught(Objects.requireNonNull(threads.poll(node, false)), node); }
                    finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
                });
                assertSame(request.getPayload(), failure.getPayload());
                assertSame(request.getPayload(), ((GuestException) failure.getCause()).getPayload());
                assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState());
                assertNull(threads.poll(node, false));
            } finally {
                // The callback's exception is a foreign-visible failure, not a saved Java frame.
                assertEquals(AsyncRequestState.TARGET_FINISHED, threads.send(id, Unit.INSTANCE).getState());
            }
        } finally { threads.leaveForeign(prior); }
        long id = threads.enterCurrent(null, false, true, null);
        try {
            var request = threads.send(id, "new guest entry");
            assertSame(request, threads.poll(node, false));
            request.acknowledge();
        } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
    }

    @Test void foreignOriginCrossesContextsWithoutSharingTheirMailboxesOrMasks() {
        var outerMask = ThreadLocal.withInitial(() -> MaskingState.UNMASKED);
        var innerMask = ThreadLocal.withInitial(() -> MaskingState.UNMASKED);
        var outer = new GuestThreads(outerMask, CpuAffinity.Companion.discover(false), target -> Unit.INSTANCE);
        var inner = new GuestThreads(innerMask, CpuAffinity.Companion.discover(false), target -> Unit.INSTANCE);
        outer.enterCurrent(MaskingState.MASKED_UNINTERRUPTIBLE, false, true, null);
        try {
            var previous = outer.enterForeign(ForeignSafety.SAFE);
            try {
                long id = inner.enterCurrent(null, false, true, null);
                try {
                    assertEquals(Thread.currentThread().threadId(), inner.currentIdentity().getJavaId());
                    assertTrue(inner.isCurrentBound());
                    assertEquals(MaskingState.UNMASKED, innerMask.get());
                    assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, outerMask.get());
                    var payload = new Object();
                    var request = inner.send(id, payload);
                    var failure = assertThrows(ForeignCallbackAsyncFailure.class, () ->
                        AsyncContinuations.uncaught(Objects.requireNonNull(inner.poll(node, false)), node));
                    assertSame(payload, failure.getPayload());
                    assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState());
                    assertNull(outer.poll(node, false), "The callback cannot claim another context's mailbox");
                } finally { inner.leaveCurrent(GuestThreadStatus.FINISHED); }
            } finally { outer.leaveForeign(previous); }
        } finally { outer.leaveCurrent(GuestThreadStatus.FINISHED); }
        // A later top-level entry has no foreign ancestor and retains its ordinary API.
        long id = inner.enterCurrent(null, false, true, null);
        try {
            var request = inner.send(id, new Object());
            assertThrows(GuestException.class, () -> AsyncContinuations.uncaught(Objects.requireNonNull(inner.poll(node, false)), node));
            assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState());
        } finally { inner.leaveCurrent(GuestThreadStatus.FINISHED); }
    }

    @Test void exactUnliftedThreadIdAndLazyKillPayloadContract() {
        var state = new CoreRepresentation(CoreKind.VOID, true, true, List.of(), null, null, null, null, null);
        var thread = new CoreRepresentation(CoreKind.OBJECT, true, true, List.of("BoxedRep (Just Unlifted)"), null, null, null, null, null);
        var lifted = new CoreRepresentation(CoreKind.DATA, false, true, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null);
        var action = new CoreRepresentation(CoreKind.CLOSURE, true, true, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null);
        var result = new CoreRepresentation(CoreKind.UNKNOWN, false, true, thread.getPrimReps(), List.of(state, thread), null, null, null, null);
        CoreGuestThreads.INSTANCE.validate("fork#", List.of(action, state), List.of(true, false), result);
        CoreGuestThreads.INSTANCE.validate("myThreadId#", List.of(state), List.of(false), result);
        CoreGuestThreads.INSTANCE.validate("killThread#", List.of(thread, lifted, state), List.of(false, true, false), state);
        var wrongThread = thread.copy(thread.getKind(), thread.getEvaluated(), thread.getPresent(), lifted.getPrimReps(),
            thread.getComponents(), thread.getVector(), thread.getAlternatives(), thread.getTagSlot(), thread.getAlternativeSlots());
        assertThrows(RuntimeFault.class, () -> CoreGuestThreads.INSTANCE.validate("killThread#", List.of(wrongThread, lifted, state), List.of(false, true, false), state));
        assertThrows(RuntimeFault.class, () -> CoreGuestThreads.INSTANCE.validate("killThread#", List.of(thread, lifted, state), List.of(false, false, false), state));
        assertThrows(RuntimeFault.class, () -> CoreGuestThreads.INSTANCE.validate("fork#", List.of(action, state), List.of(true, false),
            result.copy(result.getKind(), result.getEvaluated(), result.getPresent(), result.getPrimReps(), List.of(state, lifted),
                result.getVector(), result.getAlternatives(), result.getTagSlot(), result.getAlternativeSlots())));
    }

    @Test void selfThrowClaimsImmediatelyEvenUnderUninterruptibleMask() {
        var masks = ThreadLocal.withInitial(() -> MaskingState.UNMASKED);
        var threads = new GuestThreads(masks, CpuAffinity.Companion.discover(false), target -> { throw new IllegalStateException("Self throw needs no cross-thread wake"); });
        long id = threads.enterCurrent(null, false, true, null);
        try {
            for (var mask : MaskingState.values()) {
                masks.set(mask);
                var request = threads.send(id, mask);
                assertSame(request, threads.poll(node, false), "Native GHC delivers self throwTo under " + mask);
                request.acknowledge();
            }
        } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
    }

    @Test void uncaughtPublicBoundaryRetainsExactPayloadAndAcknowledgesOnlyThisTarget() {
        var masks = ThreadLocal.withInitial(() -> MaskingState.UNMASKED);
        var threads = new GuestThreads(masks, CpuAffinity.Companion.discover(false), target -> { throw new IllegalStateException("Self throw needs no cross-thread wake"); });
        long id = threads.enterCurrent(null, false, true, null);
        try {
            var payload = new Object();
            var request = threads.send(id, payload);
            assertSame(request, threads.poll(node, false));
            var failure = assertThrows(GuestException.class, () -> AsyncContinuations.uncaught(request, node));
            assertSame(payload, failure.getPayload());
            assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState());
            assertThrows(IllegalStateException.class, () -> AsyncContinuations.uncaught(request, node));
        } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
    }

    @Test void interruptedSenderPausesOnlyUnclaimedOutboundAndResumesSameToken() {
        var masks = ThreadLocal.withInitial(() -> MaskingState.UNMASKED); var wakes = new AtomicInteger();
        var threads = new GuestThreads(masks, CpuAffinity.Companion.discover(false), target -> { wakes.incrementAndGet(); return Unit.INSTANCE; });
        long id = threads.enterCurrent(null, false, true, null);
        try {
            var submitted = new AtomicReference<AsyncRequest>();
            var sender = new Thread(() -> submitted.set(threads.send(id, "outbound")));
            sender.start(); join(sender);
            assertFalse(sender.isAlive());
            var request = submitted.get();
            assertTrue(threads.pause$org_intelligence_thc(request));
            assertEquals(AsyncRequestState.PAUSED, request.getState());
            assertNull(threads.poll(node, false), "Native throwTo removes an uncommitted send during sender unwind");
            threads.resume$org_intelligence_thc(request);
            assertSame(request, threads.poll(node, false));
            assertFalse(threads.pause$org_intelligence_thc(request), "A claimed send cannot be revoked");
            request.acknowledge();
            assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState());
            assertEquals(2, wakes.get());
        } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
    }

    @Test void targetCompletionBetweenEnqueueAndWakeIsSuccessfulNoop() {
        var masks = ThreadLocal.withInitial(() -> MaskingState.UNMASKED);
        var exit = new CountDownLatch(1); var ready = new CountDownLatch(1);
        var threads = new GuestThreads(masks, CpuAffinity.Companion.discover(false), target -> {
            exit.countDown(); join(target); assertFalse(target.isAlive());
            throw new IllegalStateException("Wake rejected a completed Java thread");
        });
        var id = new AtomicLong();
        var target = new Thread(() -> {
            id.set(threads.enterCurrent(null, false, true, null)); ready.countDown();
            try { assertTrue(await(exit)); } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
        });
        target.start(); assertTrue(await(ready));
        var request = threads.send(id.get(), "no-op");
        assertEquals(AsyncRequestState.TARGET_FINISHED, request.getState());
    }

    @Test void wakeFailureAfterClaimCannotRevokeSendOrResumedSend() {
        for (boolean resumed : List.of(false, true)) {
            var masks = ThreadLocal.withInitial(() -> MaskingState.UNMASKED);
            var pollAllowed = new CountDownLatch(1); var claimed = new CountDownLatch(1);
            var acknowledge = new CountDownLatch(1); var ready = new CountDownLatch(1);
            var seen = new AtomicReference<AsyncRequest>(); var wakeCount = new AtomicInteger();
            var threads = new GuestThreads(masks, CpuAffinity.Companion.discover(false), target -> {
                if (!resumed || wakeCount.incrementAndGet() == 2) {
                    pollAllowed.countDown(); assertTrue(await(claimed));
                    throw new IllegalStateException("Wake failed after target claim");
                }
                return Unit.INSTANCE;
            });
            var id = new AtomicLong();
            var target = new Thread(() -> {
                id.set(threads.enterCurrent(null, false, true, null)); ready.countDown();
                try {
                    assertTrue(await(pollAllowed)); seen.set(threads.poll(node, false)); claimed.countDown();
                    assertTrue(await(acknowledge)); seen.get().acknowledge();
                } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
            });
            target.start();
            try {
                assertTrue(await(ready));
                AsyncRequest request;
                if (resumed) {
                    request = threads.send(id.get(), "resumed"); assertTrue(threads.pause$org_intelligence_thc(request)); threads.resume$org_intelligence_thc(request);
                } else request = threads.send(id.get(), "sent");
                assertSame(request, seen.get());
                assertEquals(AsyncRequestState.CLAIMED, request.getState());
                acknowledge.countDown(); join(target); assertFalse(target.isAlive());
                assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState());
            } finally { acknowledge.countDown(); join(target); }
        }
    }
}
