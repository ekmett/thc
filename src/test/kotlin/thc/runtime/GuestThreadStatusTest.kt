// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Timeout(20)
class GuestThreadStatusTest {
    private fun registry() = GuestThreads(ThreadLocal.withInitial { MaskingState.UNMASKED }, CpuAffinity(null, 2)) { }

    @Test fun hostReentryKeepsJavaIdentityCapabilityAndForeignStatus() {
        val threads = registry()
        threads.enterCurrent()
        val id = threads.currentIdentity()
        try {
            assertEquals(Thread.currentThread().threadId(), id.javaId)
            assertEquals(0L, id.capability)
            assertEquals(GuestThreadStatus.RUNNING, threads.status(id))
            val foreign = threads.enterForeign()
            try {
                assertEquals(GuestThreadStatus.FOREIGN, threads.status(id))
                threads.enterCurrent()
                try {
                    assertSame(id, threads.currentIdentity())
                    assertEquals(GuestThreadStatus.RUNNING, threads.status(id))
                    GuestThreads.blocking(GuestThreadStatus.BLACK_HOLE).use {
                        assertEquals(GuestThreadStatus.BLACK_HOLE, threads.status(id))
                    }
                    assertEquals(GuestThreadStatus.RUNNING, threads.status(id))
                } finally { threads.leaveCurrent() }
                assertEquals(GuestThreadStatus.FOREIGN, threads.status(id))
            } finally { threads.leaveForeign(foreign) }
        } finally { threads.leaveCurrent() }
        assertEquals(GuestThreadStatus.FOREIGN, threads.status(id), "Returning to the live host does not kill its Java thread")
        assertEquals(AsyncRequestState.TARGET_FINISHED, threads.send(id, Unit).state,
            "Existing throwTo mailboxes are scoped to an active guest invocation")
        threads.enterCurrent()
        try {
            assertSame(id, threads.currentIdentity())
            assertEquals(GuestThreadStatus.RUNNING, threads.status(id))
            assertEquals(2L, threads.capabilityCount())
            val other = registry()
            other.enterCurrent()
            try {
                assertEquals(id.javaId, other.currentIdentity().javaId)
                assertNotEquals(id, other.currentIdentity())
                assertThrows(RuntimeFault::class.java) { other.status(id) }
            } finally { other.leaveCurrent() }
            assertEquals(GuestThreadStatus.RUNNING, threads.status(id))
        } finally { threads.leaveCurrent() }
    }

    @Test fun actualMVarWaitsExposeTheirReasonsAndShareBoundedCpuCapabilities() {
        val threads = registry()
        threads.enterCurrent()
        val parent = threads.currentIdentity()
        try {
            val retained = mutableListOf<GuestThreadId>()
            for (read in listOf(false, true)) {
                val cell = ManagedMVar()
                val request = if (read) cell.beginRead() else cell.beginTake()
                val identity = AtomicReference<GuestThreadId>()
                val failure = AtomicReference<Throwable>()
                val ready = CountDownLatch(1)
                val worker = Thread {
                    threads.enterCurrent(forked = true)
                    try {
                        identity.set(threads.currentIdentity())
                        ready.countDown()
                        assertEquals(42L, request.await())
                        assertEquals(GuestThreadStatus.RUNNING, threads.status(identity.get()))
                    } catch (error: Throwable) { failure.set(error) }
                    finally { threads.leaveCurrent() }
                }
                worker.start()
                try {
                    assertTrue(ready.await(5, TimeUnit.SECONDS))
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (!request.hasWaitingThread() && System.nanoTime() < deadline) Thread.yield()
                    assertTrue(request.hasWaitingThread())
                    val id = identity.get()
                    assertEquals(worker.threadId(), id.javaId)
                    assertEquals((retained.size + 1L) % 2L, id.capability)
                    assertEquals(if (read) GuestThreadStatus.MVAR_READ else GuestThreadStatus.MVAR, threads.status(id))
                    retained += id
                } finally {
                    cell.tryPut(42L)
                    worker.join(5000)
                }
                assertFalse(worker.isAlive)
                failure.get()?.let { throw AssertionError("worker failed", it) }
                assertEquals(GuestThreadStatus.FINISHED, threads.status(identity.get()))
            }
            assertEquals(1L, retained[0].capability)
            assertEquals(parent.capability, retained[1].capability, "More threads do not invent more CPUs")
            assertEquals(2L, threads.capabilityCount())
        } finally { threads.leaveCurrent() }
    }

    @Test fun forkedFailureAndHostCarrierTerminationAreDistinct() {
        val threads = registry()
        for ((forked, outcome) in listOf(true to GuestThreadStatus.DIED, false to GuestThreadStatus.FINISHED)) {
            val id = AtomicReference<GuestThreadId>()
            val worker = Thread {
                threads.enterCurrent(forked = forked)
                id.set(threads.currentIdentity())
                threads.leaveCurrent(outcome)
            }
            worker.start(); worker.join(5000)
            assertFalse(worker.isAlive)
            assertEquals(outcome, threads.status(id.get()))
        }
        threads.enterCurrent()
        val id = threads.currentIdentity()
        threads.close()
        threads.leaveCurrent()
        assertThrows(RuntimeFault::class.java) { threads.status(id) }
        // Closing an active context must unwind its process-wide observation extent.
        GuestThreads.blocking(GuestThreadStatus.MVAR).use { }
    }

    @Test fun exactThreadStatusTupleRejectsWrongLanesFlagsAndRepresentations() {
        val state = CoreRepresentation(CoreKind.VOID, primReps = emptyList())
        val thread = CoreRepresentation(CoreKind.OBJECT, primReps = listOf("BoxedRep (Just Unlifted)"))
        val integer = CoreRepresentation(CoreKind.LONG, primReps = listOf("IntRep"))
        val result = CoreRepresentation(CoreKind.UNKNOWN, primReps = List(3) { "IntRep" },
            components = listOf(state, integer, integer, integer))
        CoreGuestThreads.validate("threadStatus#", listOf(thread, state), listOf(false, false), result)
        assertThrows(RuntimeFault::class.java) {
            CoreGuestThreads.validate("threadStatus#", listOf(thread, state), listOf(true, false), result)
        }
        assertThrows(RuntimeFault::class.java) {
            CoreGuestThreads.validate("threadStatus#", listOf(integer, state), listOf(false, false), result)
        }
        for (bad in listOf(result.copy(components = listOf(state, integer, thread, integer)),
                result.copy(primReps = listOf("IntRep")), result.copy(components = listOf(state, integer))))
            assertThrows(RuntimeFault::class.java) {
                CoreGuestThreads.validate("threadStatus#", listOf(thread, state), listOf(false, false), bad)
            }
    }
}
