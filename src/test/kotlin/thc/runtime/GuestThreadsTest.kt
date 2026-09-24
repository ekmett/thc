// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.nodes.Node
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
        assertEquals(worker.threadId(), id.get())
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

    @Test fun exactUnliftedThreadIdAndLazyKillPayloadContract() {
        val state = CoreRepresentation(CoreKind.VOID, true, true, emptyList())
        val thread = CoreRepresentation(CoreKind.OBJECT, true, true, listOf("BoxedRep (Just Unlifted)"))
        val lifted = CoreRepresentation(CoreKind.DATA, false, true, listOf("BoxedRep (Just Lifted)"))
        val action = CoreRepresentation(CoreKind.CLOSURE, true, true, listOf("BoxedRep (Just Lifted)"))
        val result = CoreRepresentation(CoreKind.UNKNOWN, false, true, thread.primReps,
            components = listOf(state, thread))
        CoreGuestThreads.validate("fork#", listOf(action, state), listOf(true, false), result)
        CoreGuestThreads.validate("myThreadId#", listOf(state), listOf(false), result)
        CoreGuestThreads.validate("killThread#", listOf(thread, lifted, state),
            listOf(false, true, false), state)
        val wrongThread = thread.copy(primReps = lifted.primReps)
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
                result.copy(components = listOf(state, lifted)))
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
