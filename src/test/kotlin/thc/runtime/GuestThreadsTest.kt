// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.nodes.Node
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class GuestThreadsTest {
    private val node = object : Node() {}

    @Test fun pollCellRetainsNestedEntriesButSeparatesContextsAndClearsCompletedTargets() {
        val outer = GuestThreads(ThreadLocal.withInitial { MaskingState.UNMASKED }) { }
        val inner = GuestThreads(ThreadLocal.withInitial { MaskingState.UNMASKED }) { }
        val carrier = Thread.currentThread()
        val outerCell = outer.pollState(carrier)
        val innerCell = inner.pollState(carrier)
        assertFalse(outerCell === innerCell)
        assertNull(outerCell.current)
        outer.enterCurrent()
        val original = outerCell.current!!
        try {
            assertSame(outerCell, outer.pollState(carrier))
            outer.enterCurrent()
            try { assertSame(original, outerCell.current) }
            finally { outer.leaveCurrent() }
            assertSame(original, outerCell.current)
            inner.enterCurrent()
            try {
                assertFalse(original === innerCell.current)
                assertSame(original, outerCell.current)
            } finally { inner.leaveCurrent() }
            assertNull(innerCell.current)
        } finally { outer.leaveCurrent() }
        assertNull(outerCell.current)
        outer.enterCurrent()
        try {
            assertSame(outerCell, outer.pollState(carrier))
            assertFalse(original === outerCell.current)
        } finally { outer.leaveCurrent() }
        assertNull(outerCell.current)
        outer.close()
        inner.close()
    }

    @Test fun nonresumableForkRejectsExternalSendBeforeWakeButAllowsSelfAndDeadTargets() {
        val masks = ThreadLocal.withInitial { MaskingState.UNMASKED }
        val wakes = AtomicInteger()
        val threads = GuestThreads(masks) { wakes.incrementAndGet() }
        val ready = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val identity = AtomicReference<GuestThreadId>()
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            threads.enterCurrent(MaskingState.MASKED_UNINTERRUPTIBLE, forked = true, externalAsync = false)
            try {
                val self = threads.currentIdentity()
                identity.set(self)
                threads.enterCurrent() // Default arguments cannot upgrade this lifetime.
                try {
                    ready.countDown()
                    assertTrue(finish.await(5, TimeUnit.SECONDS))
                    assertNull(threads.poll(node, true), "Rejected external sends never enter the queue")
                    val sent = threads.send(self, "self")
                    assertTrue(sent.forceSelf)
                    assertSame(sent, threads.poll(node))
                    sent.acknowledge()
                    assertEquals(AsyncRequestState.ACKNOWLEDGED, sent.state)
                } finally { threads.leaveCurrent() }
            } catch (error: Throwable) { failure.set(error); ready.countDown() }
            finally { threads.leaveCurrent() }
        }
        worker.start()
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("fork worker failed", it) }
            repeat(3) { assertThrows(UnsupportedCore::class.java) { threads.send(identity.get(), "external") } }
            assertEquals(0, wakes.get())
        } finally { finish.countDown(); worker.join(5000) }
        assertFalse(worker.isAlive)
        failure.get()?.let { throw AssertionError("fork worker failed", it) }
        assertEquals(AsyncRequestState.TARGET_FINISHED, threads.send(identity.get(), "late").state)
        assertEquals(0, wakes.get())
        threads.close()
    }

    @Test fun javaThreadIdAndMaskingGateQueuedDelivery() {
        val masks = ThreadLocal.withInitial { MaskingState.UNMASKED }
        val wakes = AtomicInteger()
        val threads = GuestThreads(masks) { wakes.incrementAndGet() }
        val ready = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val id = AtomicLong()
        val firstSeen = AtomicReference<AsyncRequest>()
        val secondSeen = AtomicReference<AsyncRequest>()
        val worker = Thread {
            id.set(threads.registerCurrent())
            masks.set(MaskingState.MASKED_UNINTERRUPTIBLE)
            ready.countDown()
            try {
                assertTrue(proceed.await(5, TimeUnit.SECONDS))
                assertNull(threads.poll(node, true))
                masks.set(MaskingState.MASKED_INTERRUPTIBLE)
                assertNull(threads.poll(node))
                val first = threads.poll(node, true)!!
                firstSeen.set(first)
                assertNull(threads.poll(node, true), "A claimed request blocks later FIFO entries")
                first.acknowledge()
                masks.set(MaskingState.UNMASKED)
                val second = threads.poll(node)!!
                secondSeen.set(second)
                second.acknowledge()
                assertNull(threads.poll(node))
            } finally {
                threads.completeCurrent()
            }
        }
        worker.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        assertEquals(worker.threadId(), threads.pollState(worker).current!!.identity.javaId)
        assertEquals(id.get(), threads.pollState(worker).current!!.identity.logicalId)
        val first = threads.send(id.get(), "first")
        val second = threads.send(id.get(), "second")
        assertEquals(2, wakes.get())
        proceed.countDown()
        worker.join(5000)
        assertFalse(worker.isAlive)
        assertSame(first, firstSeen.get())
        assertSame(second, secondSeen.get())
        assertEquals(AsyncRequestState.ACKNOWLEDGED, first.state)
        assertEquals(AsyncRequestState.ACKNOWLEDGED, second.state)
        assertEquals("first", first.payload)
        assertEquals(AsyncRequestState.TARGET_FINISHED, threads.send(id.get(), "late").state)
    }

    @Test fun cancellationAndTargetCompletionWakePendingSenders() {
        val masks = ThreadLocal.withInitial { MaskingState.UNMASKED }
        val threads = GuestThreads(masks) { }
        val ready = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val id = AtomicLong()
        val worker = Thread {
            id.set(threads.registerCurrent())
            ready.countDown()
            try { assertTrue(finish.await(5, TimeUnit.SECONDS)) }
            finally { threads.completeCurrent() }
        }
        worker.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        val cancelled = threads.send(id.get(), "cancel")
        assertTrue(cancelled.cancel())
        assertFalse(cancelled.cancel())
        val pending = threads.send(id.get(), "pending")
        finish.countDown()
        worker.join(5000)
        assertFalse(worker.isAlive)
        assertEquals(AsyncRequestState.CANCELLED, cancelled.state)
        assertEquals(AsyncRequestState.TARGET_FINISHED, pending.state)
        assertNull(pending.target?.takeIf { it.isAlive })
    }

    @Test fun nestedEntryRetainsTargetAndInheritedMaskUntilOuterExit() {
        val masks = ThreadLocal.withInitial { MaskingState.UNMASKED }
        val threads = GuestThreads(masks) { }
        val id = threads.enterCurrent(MaskingState.MASKED_UNINTERRUPTIBLE)
        assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, masks.get())
        assertEquals(id, threads.enterCurrent(MaskingState.UNMASKED))
        threads.leaveCurrent()
        val submitted = AtomicReference<AsyncRequest>()
        val sender = Thread { submitted.set(threads.send(id, "nested")) }
        sender.start()
        sender.join(5000)
        assertFalse(sender.isAlive)
        val request = submitted.get()
        assertEquals(AsyncRequestState.PENDING, request.state)
        assertNull(threads.poll(node, true))
        threads.leaveCurrent()
        assertEquals(AsyncRequestState.TARGET_FINISHED, request.state)
        assertEquals(MaskingState.UNMASKED, masks.get())
    }

    @Test fun unsafeReverseEntryIsRejectedBeforeIdentityMaskOrAccountingMutation() {
        for (crossContext in listOf(false, true)) for (alreadyEntered in listOf(false, true)) {
            val outerMasks = ThreadLocal.withInitial { MaskingState.UNMASKED }
            val innerMasks = ThreadLocal.withInitial { MaskingState.UNMASKED }
            val outer = GuestThreads(outerMasks) { }
            val inner = if (crossContext) GuestThreads(innerMasks) { } else outer
            outer.enterCurrent(MaskingState.MASKED_INTERRUPTIBLE)
            val caller = outer.currentIdentity()
            if (crossContext && alreadyEntered) inner.enterCurrent(MaskingState.MASKED_UNINTERRUPTIBLE)
            val innerIdentity = if (!crossContext || alreadyEntered) inner.currentIdentity() else null
            val before = if (innerIdentity != null) inner.snapshot().toList() else emptyList()
            val previousMask = if (crossContext) innerMasks.get() else outerMasks.get()
            try {
                val foreign = outer.enterForeign() // Default leaf authority must stay unsafe.
                try {
                    val failure = assertThrows(RuntimeFault::class.java) { inner.enterCurrent() }
                    assertTrue(failure.message!!.contains("Unsafe foreign call"))
                    assertFalse(caller.allocationSuspended)
                    assertEquals(previousMask, if (crossContext) innerMasks.get() else outerMasks.get())
                    if (innerIdentity != null) {
                        assertSame(innerIdentity, inner.currentIdentity())
                        assertEquals(before, inner.snapshot().toList())
                        assertFalse(innerIdentity.allocationSuspended)
                    } else assertThrows(RuntimeFault::class.java) { inner.currentIdentity() }
                } finally { outer.leaveForeign(foreign) }
                // The denied entry leaves no permission or allocation residue.
                val safe = outer.enterForeign(ForeignSafety.SAFE)
                try {
                    inner.enterCurrent()
                    try {
                        assertTrue(inner.isCurrentBound())
                        assertNotSame(innerIdentity, inner.currentIdentity())
                    } finally { inner.leaveCurrent() }
                } finally { outer.leaveForeign(safe) }
            } finally {
                if (crossContext && alreadyEntered) inner.leaveCurrent()
                outer.leaveCurrent(); outer.close(); if (crossContext) inner.close()
            }
        }
    }

    @Test fun innermostDeclarationAndClosedOriginControlNestedCallbacks() {
        val outer = GuestThreads(ThreadLocal.withInitial { MaskingState.UNMASKED }) { }
        val inner = GuestThreads(ThreadLocal.withInitial { MaskingState.UNMASKED }) { }
        outer.enterCurrent()
        val caller = outer.currentIdentity()
        val safe = outer.enterForeign(ForeignSafety.SAFE)
        try {
            inner.enterCurrent()
            val callback = inner.currentIdentity()
            try {
                val unsafe = inner.enterForeign(ForeignSafety.UNSAFE)
                try {
                    assertThrows(RuntimeFault::class.java) { outer.enterCurrent() }
                    assertThrows(RuntimeFault::class.java) { inner.enterCurrent() }
                    assertSame(callback, inner.currentIdentity())
                    assertSame(caller, outer.currentIdentity())
                } finally { inner.leaveForeign(unsafe) }
                val nested = inner.enterForeign(ForeignSafety.SAFE)
                try {
                    outer.enterCurrent()
                    try {
                        assertTrue(outer.isCurrentBound())
                        assertNotSame(caller, outer.currentIdentity())
                    } finally { outer.leaveCurrent() }
                } finally { inner.leaveForeign(nested) }
            } finally { inner.leaveCurrent() }
            outer.close()
            assertThrows(RuntimeFault::class.java) { outer.enterForeign() }
            assertThrows(RuntimeFault::class.java) { inner.enterCurrent() }
        } finally { outer.leaveForeign(safe); outer.leaveCurrent(); inner.close() }
        // Popped activations must not poison subsequent contexts on this carrier.
        val fresh = GuestThreads(ThreadLocal.withInitial { MaskingState.UNMASKED }) { }
        fresh.enterCurrent()
        try { assertFalse(fresh.isCurrentBound()) } finally { fresh.leaveCurrent(); fresh.close() }
    }

    @Test fun crossContextSendKeepsCallerMaskAndFifoAcrossOrdinaryAndCallbackEntries() {
        for (callback in listOf(false, true)) for (mask in MaskingState.entries) {
            val callerMasks = ThreadLocal.withInitial { MaskingState.UNMASKED }
            val callbackMasks = ThreadLocal.withInitial { MaskingState.UNMASKED }
            val wakes = AtomicInteger()
            val caller = GuestThreads(callerMasks) { wakes.incrementAndGet() }
            val other = GuestThreads(callbackMasks) { }
            caller.enterCurrent(mask)
            val callerId = caller.currentIdentity()
            lateinit var first: AsyncRequest
            lateinit var second: AsyncRequest
            try {
                val foreign = if (callback) caller.enterForeign(ForeignSafety.SAFE) else null
                try {
                    other.enterCurrent()
                    try {
                        val otherId = other.currentIdentity()
                        assertNotSame(callerId, otherId)
                        assertEquals(callerId.javaId, otherId.javaId)
                        first = caller.send(callerId, "first")
                        second = caller.send(callerId, "second")
                        assertFalse(first.forceSelf, "A suspended context's slot is not the active sender")
                        assertFalse(second.forceSelf)
                        assertEquals(2, wakes.get(), "Cross-context requests use external delivery")
                        assertNull(caller.poll(node, true), "Only the active guest may claim a request")
                        callbackMasks.set(MaskingState.MASKED_UNINTERRUPTIBLE)
                        val self = other.send(otherId, "actual self")
                        assertTrue(self.forceSelf)
                        assertSame(self, other.poll(node), "The actual sender still has self delivery")
                        self.acknowledge()
                    } finally { other.leaveCurrent() }
                } finally { if (foreign != null) caller.leaveForeign(foreign) }
                assertSame(callerId, caller.currentIdentity())
                assertEquals(mask, callerMasks.get())
                assertEquals(AsyncRequestState.PENDING, first.state)
                assertEquals(AsyncRequestState.PENDING, second.state)
                val claimed = when (mask) {
                    MaskingState.UNMASKED -> caller.poll(node)
                    MaskingState.MASKED_INTERRUPTIBLE -> {
                        assertNull(caller.poll(node))
                        caller.poll(node, true)
                    }
                    MaskingState.MASKED_UNINTERRUPTIBLE -> {
                        assertNull(caller.poll(node)); assertNull(caller.poll(node, true))
                        callerMasks.set(MaskingState.UNMASKED)
                        caller.poll(node)
                    }
                }
                assertSame(first, claimed, "Cross-context sends retain FIFO order")
                assertNull(caller.poll(node, true), "A claimed request excludes the next request")
                first.acknowledge()
                assertSame(second, caller.poll(node, true))
                second.acknowledge()
                assertNull(caller.poll(node, true))
            } finally { caller.leaveCurrent(); other.close(); caller.close() }
        }
    }

    @Test fun inactiveContextCannotClaimItsMailboxOnAnotherGuestsCarrier() {
        val caller = GuestThreads(ThreadLocal.withInitial { MaskingState.UNMASKED }) { }
        val other = GuestThreads(ThreadLocal.withInitial { MaskingState.UNMASKED }) { }
        caller.enterCurrent()
        val callerId = caller.currentIdentity()
        val submitted = AtomicReference<AsyncRequest>()
        try {
            val sender = Thread { submitted.set(caller.send(callerId, "external")) }
            sender.start(); sender.join(5000)
            assertFalse(sender.isAlive)
            val request = submitted.get()
            assertFalse(request.forceSelf)
            other.enterCurrent()
            try {
                assertNull(caller.poll(node, true), "A retained context slot cannot claim for an inactive guest")
                assertEquals(AsyncRequestState.PENDING, request.state)
                val self = other.send(other.currentIdentity(), "active self")
                assertSame(self, other.poll(node)); self.acknowledge()
            } finally { other.leaveCurrent() }
            assertSame(callerId, caller.currentIdentity())
            assertSame(request, caller.poll(node))
            request.acknowledge()
        } finally { caller.leaveCurrent(); other.close(); caller.close() }
    }

    @Test fun crossContextSendCannotBypassNonresumableTargetAdmission() {
        for (callback in listOf(false, true)) {
            val wakes = AtomicInteger()
            val caller = GuestThreads(ThreadLocal.withInitial { MaskingState.UNMASKED }) { wakes.incrementAndGet() }
            val other = GuestThreads(ThreadLocal.withInitial { MaskingState.UNMASKED }) { }
            caller.enterCurrent(MaskingState.MASKED_UNINTERRUPTIBLE, forked = true, externalAsync = false)
            val callerId = caller.currentIdentity()
            try {
                val foreign = if (callback) caller.enterForeign(ForeignSafety.SAFE) else null
                try {
                    other.enterCurrent()
                    try {
                        assertThrows(UnsupportedCore::class.java) { caller.send(callerId, "external") }
                        assertEquals(0, wakes.get())
                    } finally { other.leaveCurrent() }
                } finally { if (foreign != null) caller.leaveForeign(foreign) }
                assertNull(caller.poll(node, true), "Rejected sends never entered the caller mailbox")
                val self = caller.send(callerId, "actual self")
                assertTrue(self.forceSelf)
                assertSame(self, caller.poll(node)); self.acknowledge()
            } finally { caller.leaveCurrent(); other.close(); caller.close() }
        }
    }

    @Test fun callbackIdentityMailboxAndMaskAreIsolatedOnTheSameCarrier() {
        for (mask in MaskingState.entries) {
            val masks = ThreadLocal.withInitial { MaskingState.UNMASKED }
            val threads = GuestThreads(masks) { }
            threads.enterCurrent(mask)
            val caller = threads.currentIdentity()
            val original = Thread.currentThread()
            val callerPoll = threads.pollState(original)
            val callerStack = callerPoll.astStack
            try {
                val submitted = AtomicReference<AsyncRequest>()
                val sender = Thread { submitted.set(threads.send(caller, "external")) }
                sender.start(); sender.join(5000)
                assertFalse(sender.isAlive)
                val external = submitted.get()
                val prior = threads.enterForeign(ForeignSafety.SAFE)
                lateinit var callback: GuestThreadId
                lateinit var callerFromCallback: AsyncRequest
                try {
                    assertNull(threads.poll(node, true), "Foreign code cannot claim the caller's request")
                    threads.enterCurrent()
                    callback = threads.currentIdentity()
                    try {
                        assertNotEquals(caller, callback)
                        assertEquals(caller.javaId, callback.javaId)
                        assertSame(original, callback.carrier.get())
                        assertTrue(threads.isCurrentBound())
                        assertEquals(MaskingState.UNMASKED, masks.get())
                        assertNotSame(callerStack, callerPoll.astStack)
                        assertNull(threads.poll(node, true), "The callback has a separate mailbox")
                        assertEquals(GuestThreadStatus.FOREIGN, threads.status(caller))
                        callerFromCallback = threads.send(caller, "caller from callback")
                        assertFalse(callerFromCallback.forceSelf, "Same carrier does not mean self throwTo")
                        assertEquals(callback.logicalId, threads.enterCurrent(), "Ordinary nesting retains identity")
                        try { assertSame(callback, threads.currentIdentity()) } finally { threads.leaveCurrent() }
                        val self = threads.send(callback, "callback self")
                        masks.set(MaskingState.MASKED_UNINTERRUPTIBLE)
                        assertSame(self, threads.poll(node), "Self throwTo still bypasses this guest's mask")
                        self.acknowledge()
                        val nestedForeign = threads.enterForeign(ForeignSafety.SAFE)
                        try {
                            threads.enterCurrent()
                            val nested = threads.currentIdentity()
                            try {
                                assertNotEquals(callback, nested); assertNotEquals(caller, nested)
                                assertEquals(original.threadId(), nested.javaId)
                                assertTrue(threads.isCurrentBound())
                                assertEquals(MaskingState.UNMASKED, masks.get())
                                assertNull(threads.poll(node, true))
                            } finally { threads.leaveCurrent() }
                            assertEquals(GuestThreadStatus.FINISHED, threads.status(nested))
                            assertSame(callback, threads.currentIdentity())
                            assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, masks.get())
                        } finally { threads.leaveForeign(nestedForeign) }
                    } finally { threads.leaveCurrent() }
                    assertSame(caller, threads.currentIdentity())
                    assertSame(callerStack, callerPoll.astStack)
                    assertEquals(mask, masks.get())
                    assertEquals(GuestThreadStatus.FINISHED, threads.status(callback))
                    assertNull(threads.liveJavaId(callback))
                    assertEquals(AsyncRequestState.TARGET_FINISHED, threads.send(callback, "retired").state)
                    assertEquals(AsyncRequestState.PENDING, external.state)
                    assertNull(threads.poll(node, true))
                } finally { threads.leaveForeign(prior) }
                assertFalse(threads.isCurrentBound())
                if (mask != MaskingState.UNMASKED) assertNull(threads.poll(node))
                masks.set(MaskingState.UNMASKED)
                assertSame(external, threads.poll(node)); external.acknowledge()
                assertSame(callerFromCallback, threads.poll(node)); callerFromCallback.acknowledge()
                assertNull(threads.poll(node))
            } finally { threads.leaveCurrent(); threads.close() }
        }
    }

    @Test fun callbackAllocationsAreChargedOnlyToTheirLogicalIdentity() {
        for (crossContext in listOf(false, true)) {
            val outer = GuestThreads(ThreadLocal.withInitial { MaskingState.UNMASKED }) { }
            val callbacks = if (crossContext) GuestThreads(ThreadLocal.withInitial { MaskingState.UNMASKED }) { } else outer
            val retained = ArrayList<ByteArray>()
            outer.enterCurrent()
            val caller = outer.currentIdentity()
            try {
                outer.setAllocationCounter(10_000_000L)
                val foreign = outer.enterForeign(ForeignSafety.SAFE)
                try {
                    callbacks.enterCurrent()
                    val first = callbacks.currentIdentity()
                    try {
                        assertTrue(caller.allocationSuspended)
                        assertEquals(-1L, caller.allocationBaseline)
                        callbacks.setAllocationCounter(10_000_000L)
                        retained.add(ByteArray(2_000_000))
                        assertTrue(callbacks.allocationCounter() < 8_000_000L)
                        val nestedForeign = callbacks.enterForeign(ForeignSafety.SAFE)
                        try {
                            callbacks.enterCurrent()
                            try {
                                assertTrue(first.allocationSuspended)
                                callbacks.setAllocationCounter(9_000_000L, first)
                                assertEquals(-1L, first.allocationBaseline, "Resetting a suspended counter must not restart charging")
                                callbacks.setAllocationCounter(10_000_000L)
                                retained.add(ByteArray(2_000_000))
                                assertTrue(callbacks.allocationCounter() < 8_000_000L)
                                assertEquals(9_000_000L, first.allocationRemaining)
                                outer.setAllocationCounter(11_000_000L, caller)
                                assertEquals(-1L, caller.allocationBaseline)
                            } finally { callbacks.leaveCurrent() }
                        } finally { callbacks.leaveForeign(nestedForeign) }
                        assertFalse(first.allocationSuspended)
                        assertTrue(callbacks.allocationCounter() in 8_900_000L..9_000_000L,
                            "The nested callback's two megabytes belong only to that callback")
                        retained.add(ByteArray(2_000_000))
                        assertEquals(11_000_000L, caller.allocationRemaining)
                    } finally { callbacks.leaveCurrent() }
                } finally { outer.leaveForeign(foreign) }
                assertFalse(caller.allocationSuspended)
                assertTrue(outer.allocationCounter() in 10_900_000L..11_000_000L,
                    "Neither nested callback is charged to the suspended caller")
                assertEquals(6_000_000, retained.sumOf { it.size })
                // Missing accounting evidence stays missing across a callback.
                caller.allocationUnavailable = true
                val again = outer.enterForeign(ForeignSafety.SAFE)
                try { callbacks.enterCurrent(); callbacks.leaveCurrent() }
                finally { outer.leaveForeign(again) }
                assertThrows(RuntimeFault::class.java) { outer.allocationCounter() }
            } finally { outer.leaveCurrent(); outer.close(); if (crossContext) callbacks.close() }
        }
    }

    @Test fun foreignScopeBeforeRegistrationAndExceptionalCallbackExitRestorePermission() {
        val threads = GuestThreads(ThreadLocal.withInitial { MaskingState.UNMASKED }) { }
        val prior = threads.enterForeign(ForeignSafety.SAFE)
        try {
            assertNull(threads.poll(node))
            assertThrows(RuntimeFault::class.java) { threads.currentId() }
            val id = threads.enterCurrent()
            val request = threads.send(id, Any())
            try {
                val failure = assertThrows(ForeignCallbackAsyncFailure::class.java) {
                    try { AsyncContinuations.uncaught(threads.poll(node)!!, node) }
                    finally { threads.leaveCurrent() }
                }
                assertSame(request.payload, failure.payload)
                assertSame(request.payload, (failure.cause as GuestException).payload)
                assertEquals(AsyncRequestState.ACKNOWLEDGED, request.state)
                assertNull(threads.poll(node))
            } finally {
                // The callback's exception is a foreign-visible failure, not a saved Java frame.
                assertEquals(AsyncRequestState.TARGET_FINISHED, threads.send(id, Unit).state)
            }
        } finally { threads.leaveForeign(prior) }
        val id = threads.enterCurrent()
        try {
            val request = threads.send(id, "new guest entry")
            assertSame(request, threads.poll(node))
            request.acknowledge()
        } finally { threads.leaveCurrent() }
    }

    @Test fun foreignOriginCrossesContextsWithoutSharingTheirMailboxesOrMasks() {
        val outerMask = ThreadLocal.withInitial { MaskingState.UNMASKED }
        val innerMask = ThreadLocal.withInitial { MaskingState.UNMASKED }
        val outer = GuestThreads(outerMask) { }
        val inner = GuestThreads(innerMask) { }
        outer.enterCurrent(MaskingState.MASKED_UNINTERRUPTIBLE)
        try {
            val previous = outer.enterForeign(ForeignSafety.SAFE)
            try {
                val id = inner.enterCurrent()
                try {
                    assertEquals(Thread.currentThread().threadId(), inner.currentIdentity().javaId)
                    assertTrue(inner.isCurrentBound())
                    assertEquals(MaskingState.UNMASKED, innerMask.get())
                    assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, outerMask.get())
                    val payload = Any()
                    val request = inner.send(id, payload)
                    val failure = assertThrows(ForeignCallbackAsyncFailure::class.java) {
                        AsyncContinuations.uncaught(inner.poll(node)!!, node)
                    }
                    assertSame(payload, failure.payload)
                    assertEquals(AsyncRequestState.ACKNOWLEDGED, request.state)
                    assertNull(outer.poll(node), "The callback cannot claim another context's mailbox")
                } finally { inner.leaveCurrent() }
            } finally { outer.leaveForeign(previous) }
        } finally { outer.leaveCurrent() }
        // A later top-level entry has no foreign ancestor and retains its ordinary API.
        val id = inner.enterCurrent()
        try {
            val request = inner.send(id, Any())
            assertThrows(GuestException::class.java) { AsyncContinuations.uncaught(inner.poll(node)!!, node) }
            assertEquals(AsyncRequestState.ACKNOWLEDGED, request.state)
        } finally { inner.leaveCurrent() }
    }

    @Test fun exactUnliftedThreadIdAndLazyKillPayloadContract() {
        val state = CoreRepresentation(CoreKind.VOID, true, true, emptyList())
        val thread = CoreRepresentation(CoreKind.OBJECT, true, true, listOf("BoxedRep (Just Unlifted)"))
        val lifted = CoreRepresentation(CoreKind.DATA, false, true, listOf("BoxedRep (Just Lifted)"))
        val action = CoreRepresentation(CoreKind.CLOSURE, true, true, listOf("BoxedRep (Just Lifted)"))
        val result = CoreRepresentation(CoreKind.UNKNOWN, false, true, thread.primReps, listOf(state, thread))
        CoreGuestThreads.validate("fork#", listOf(action, state), listOf(true, false), result)
        CoreGuestThreads.validate("myThreadId#", listOf(state), listOf(false), result)
        CoreGuestThreads.validate("killThread#", listOf(thread, lifted, state),
            listOf(false, true, false), state)
        val wrongThread = thread.withPrimReps(lifted.primReps)
        assertThrows(RuntimeFault::class.java) {
            CoreGuestThreads.validate("killThread#", listOf(wrongThread, lifted, state),
                listOf(false, true, false), state)
        }
        assertThrows(RuntimeFault::class.java) {
            CoreGuestThreads.validate("killThread#", listOf(thread, lifted, state),
                listOf(false, false, false), state)
        }
        assertThrows(RuntimeFault::class.java) {
            CoreGuestThreads.validate("fork#", listOf(action, state), listOf(true, false),
                result.let { originalProof -> originalProof.copy(originalProof.kind, originalProof.evaluated, originalProof.present, originalProof.primReps, listOf(state, lifted), originalProof.vector, originalProof.alternatives, originalProof.tagSlot, originalProof.alternativeSlots) })
        }
    }

    @Test fun selfThrowClaimsImmediatelyEvenUnderUninterruptibleMask() {
        val masks = ThreadLocal.withInitial { MaskingState.UNMASKED }
        val threads = GuestThreads(masks) { error("Self throw needs no cross-thread wake") }
        val id = threads.enterCurrent()
        try {
            for (mask in MaskingState.entries) {
                masks.set(mask)
                val request = threads.send(id, mask)
                assertSame(request, threads.poll(node), "Native GHC delivers self throwTo under $mask")
                request.acknowledge()
            }
        } finally { threads.leaveCurrent() }
    }

    @Test fun uncaughtPublicBoundaryRetainsExactPayloadAndAcknowledgesOnlyThisTarget() {
        val masks = ThreadLocal.withInitial { MaskingState.UNMASKED }
        val threads = GuestThreads(masks) { error("Self throw needs no cross-thread wake") }
        val id = threads.enterCurrent()
        try {
            val payload = Any()
            val request = threads.send(id, payload)
            assertSame(request, threads.poll(node))
            val failure = assertThrows(GuestException::class.java) {
                AsyncContinuations.uncaught(request, node)
            }
            assertSame(payload, failure.payload)
            assertEquals(AsyncRequestState.ACKNOWLEDGED, request.state)
            assertThrows(IllegalStateException::class.java) {
                AsyncContinuations.uncaught(request, node)
            }
        } finally { threads.leaveCurrent() }
    }

    @Test fun interruptedSenderPausesOnlyUnclaimedOutboundAndResumesSameToken() {
        val masks = ThreadLocal.withInitial { MaskingState.UNMASKED }
        val wakes = AtomicInteger()
        val threads = GuestThreads(masks) { wakes.incrementAndGet() }
        val id = threads.enterCurrent()
        try {
            val submitted = AtomicReference<AsyncRequest>()
            val sender = Thread { submitted.set(threads.send(id, "outbound")) }
            sender.start()
            sender.join(5000)
            assertFalse(sender.isAlive)
            val request = submitted.get()
            assertTrue(threads.pause(request))
            assertEquals(AsyncRequestState.PAUSED, request.state)
            assertNull(threads.poll(node), "Native throwTo removes an uncommitted send during sender unwind")
            threads.resume(request)
            assertSame(request, threads.poll(node))
            assertFalse(threads.pause(request), "A claimed send cannot be revoked")
            request.acknowledge()
            assertEquals(AsyncRequestState.ACKNOWLEDGED, request.state)
            assertEquals(2, wakes.get())
        } finally { threads.leaveCurrent() }
    }

    @Test fun targetCompletionBetweenEnqueueAndWakeIsSuccessfulNoop() {
        val masks = ThreadLocal.withInitial { MaskingState.UNMASKED }
        val exit = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val threads = GuestThreads(masks) { target ->
            exit.countDown()
            target.join(5000)
            assertFalse(target.isAlive)
            throw IllegalStateException("Wake rejected a completed Java thread")
        }
        val id = AtomicLong()
        val target = Thread {
            id.set(threads.enterCurrent())
            ready.countDown()
            try { assertTrue(exit.await(5, TimeUnit.SECONDS)) }
            finally { threads.leaveCurrent() }
        }
        target.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        val request = threads.send(id.get(), "no-op")
        assertEquals(AsyncRequestState.TARGET_FINISHED, request.state)
    }

    @Test fun wakeFailureAfterClaimCannotRevokeSendOrResumedSend() {
        for (resumed in listOf(false, true)) {
            val masks = ThreadLocal.withInitial { MaskingState.UNMASKED }
            val pollAllowed = CountDownLatch(1)
            val claimed = CountDownLatch(1)
            val acknowledge = CountDownLatch(1)
            val ready = CountDownLatch(1)
            val seen = AtomicReference<AsyncRequest>()
            val wakeCount = AtomicInteger()
            val threads = GuestThreads(masks) {
                if (!resumed || wakeCount.incrementAndGet() == 2) {
                    pollAllowed.countDown()
                    assertTrue(claimed.await(5, TimeUnit.SECONDS))
                    throw IllegalStateException("Wake failed after target claim")
                }
            }
            val id = AtomicLong()
            val target = Thread {
                id.set(threads.enterCurrent())
                ready.countDown()
                try {
                    assertTrue(pollAllowed.await(5, TimeUnit.SECONDS))
                    seen.set(threads.poll(node))
                    claimed.countDown()
                    assertTrue(acknowledge.await(5, TimeUnit.SECONDS))
                    seen.get().acknowledge()
                } finally { threads.leaveCurrent() }
            }
            target.start()
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                val request = if (resumed) {
                    threads.send(id.get(), "resumed").also {
                        assertTrue(threads.pause(it))
                        threads.resume(it)
                    }
                } else threads.send(id.get(), "sent")
                assertSame(request, seen.get())
                assertEquals(AsyncRequestState.CLAIMED, request.state)
                acknowledge.countDown()
                target.join(5000)
                assertFalse(target.isAlive)
                assertEquals(AsyncRequestState.ACKNOWLEDGED, request.state)
            } finally {
                acknowledge.countDown()
                target.join(5000)
            }
        }
    }
}
