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
class GuestThreadInventoryTest {
    private fun registry() = GuestThreads(ThreadLocal.withInitial { MaskingState.UNMASKED }) { }

    @Test fun logicalCountIsContextLocalAndShrinkDoesNotRewriteAffinityClaims() {
        val affinity = CpuAffinity(null, 8)
        val first = GuestThreads(ThreadLocal.withInitial { MaskingState.UNMASKED }, affinity) { }
        val second = registry()
        val originalSecond = second.capabilityCount()
        first.enterCurrent(capability = 7)
        try {
            val id = first.currentIdentity()
            id.affinityApplied = true // A prior successful native request is historical evidence.
            assertEquals(7L, id.capability)
            first.setCapabilityCount(3)
            assertEquals(3L, first.capabilityCount())
            assertEquals(1L, id.capability)
            assertTrue(id.capabilityLocked); assertTrue(id.affinityApplied)
            assertEquals(8, affinity.count)
            assertEquals(originalSecond, second.capabilityCount())
            first.setCapabilityCount(0xffff_ffffL)
            assertEquals(0xffff_ffffL, first.capabilityCount())
            for (invalid in listOf(0L, -1L, Long.MIN_VALUE, 0x1_0000_0000L, Long.MAX_VALUE)) {
                assertThrows(RuntimeFault::class.java) { first.setCapabilityCount(invalid) }
                assertEquals(0xffff_ffffL, first.capabilityCount())
            }
        } finally { first.leaveCurrent(); first.close(); second.close() }
        assertThrows(RuntimeFault::class.java) { first.setCapabilityCount(1) }
        assertThrows(RuntimeFault::class.java) { first.capabilityCount() }
    }

    @Test fun concurrentCountUpdatesAndNewThreadAssignmentsRemainInTheSameRegistry() {
        val threads = registry()
        val failure = AtomicReference<Throwable>()
        val start = CountDownLatch(1)
        val workers = (1L..4L).map { count -> Thread {
            try {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                repeat(100) {
                    threads.setCapabilityCount(count)
                    assertTrue(threads.capabilityCount() in 1L..4L)
                }
                threads.enterCurrent(capability = -1)
                try { assertTrue(threads.currentIdentity().capability in 0L..3L) }
                finally { threads.leaveCurrent() }
            } catch (error: Throwable) { failure.compareAndSet(null, error) }
        } }
        try {
            workers.forEach(Thread::start); start.countDown()
            workers.forEach { it.join(5000); assertFalse(it.isAlive) }
            failure.get()?.let { throw AssertionError("capability worker failed", it) }
            threads.setCapabilityCount(2)
            val observed = AtomicReference<GuestThreadId>()
            val child = Thread {
                threads.enterCurrent(capability = 7)
                try { observed.set(threads.currentIdentity()) } finally { threads.leaveCurrent() }
            }
            child.start(); child.join(5000); assertFalse(child.isAlive)
            assertEquals(1L, observed.get().capability)
            threads.setCapabilityCount(1)
            assertEquals(0L, observed.get().capability, "Retained finished ThreadId# is normalized too")
        } finally { threads.close() }
    }

    @Test fun currentEntryIsRequiredAndContextsNeverEnumerateHostThreadsOrEachOther() {
        val first = registry()
        val second = registry()
        assertThrows(RuntimeFault::class.java) { first.snapshot() }
        assertThrows(RuntimeFault::class.java) { first.isCurrentBound() }
        first.enterCurrent()
        try {
            val self = first.currentIdentity()
            assertArrayEquals(arrayOf(self), first.snapshot())
            assertFalse(first.isCurrentBound())
            first.enterCurrent()
            try { assertArrayEquals(arrayOf(self), first.snapshot(), "Nested entries do not duplicate a thread") }
            finally { first.leaveCurrent() }
            second.enterCurrent()
            try {
                val other = second.currentIdentity()
                assertEquals(self.javaId, other.javaId)
                assertNotEquals(self, other)
                assertArrayEquals(arrayOf(other), second.snapshot())
                assertArrayEquals(arrayOf(self), first.snapshot())
                assertFalse(second.isCurrentBound())
            } finally { second.leaveCurrent(); second.close() }
            val foreign = first.enterForeign(ForeignSafety.SAFE)
            try {
                first.enterCurrent()
                try { assertTrue(first.isCurrentBound(), "A callback is bound for this reverse-entry lifetime") }
                finally { first.leaveCurrent() }
            } finally { first.leaveForeign(foreign) }
        } finally { first.leaveCurrent(); first.close() }
        assertThrows(RuntimeFault::class.java) { first.snapshot() }
        assertThrows(RuntimeFault::class.java) { first.isCurrentBound() }
    }

    @Test fun concurrentSnapshotsAreIndependentAndRetainFinishedIdentitiesNotCarriers() {
        val threads = registry()
        val ready = CountDownLatch(4)
        val release = CountDownLatch(1)
        val ids = Array(4) { AtomicReference<GuestThreadId>() }
        val failure = AtomicReference<Throwable>()
        threads.enterCurrent()
        val initial = threads.snapshot()
        val workers = ids.mapIndexed { index, identity -> Thread {
            threads.enterCurrent(forked = true)
            try {
                identity.set(threads.currentIdentity())
                assertFalse(threads.isCurrentBound())
                ready.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            } catch (error: Throwable) { failure.compareAndSet(null, error) }
            finally { threads.leaveCurrent(if (index == 0) GuestThreadStatus.DIED else GuestThreadStatus.FINISHED) }
        } }
        try {
            workers.forEach(Thread::start)
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            val expected = ids.map { it.get() }.toSet() + threads.currentIdentity()
            val snapshot = ManagedArray.require(threads.snapshot())
            assertEquals(expected, snapshot.toSet())
            assertEquals(5, snapshot.size)
            assertEquals(1, initial.size, "An older snapshot never grows")
            val changed = threads.snapshot()
            changed[0] = null
            assertEquals(expected, threads.snapshot().toSet(), "Guest array writes cannot mutate the registry")
            assertEquals(expected, snapshot.toSet(), "Snapshots never share their backing array")
            release.countDown()
            workers.forEach { it.join(5000); assertFalse(it.isAlive) }
            failure.get()?.let { throw AssertionError("guest worker failed", it) }
            ids.forEachIndexed { index, reference ->
                val id = reference.get()
                assertEquals(if (index == 0) GuestThreadStatus.DIED else GuestThreadStatus.FINISHED, threads.status(id))
                id.carrier.clear() // Observation must not depend on the carrier's continued lifetime.
            }
            assertEquals(expected, threads.snapshot().toSet())
            assertEquals(expected, snapshot.toSet())
            threads.close()
            assertEquals(expected, snapshot.toSet(), "Closing the registry cannot rewrite guest snapshots")
            assertThrows(RuntimeFault::class.java) { threads.snapshot() }
        } finally {
            release.countDown()
            workers.filter { it.isAlive }.forEach { it.join(5000) }
            threads.leaveCurrent()
            threads.close()
        }
    }

    @Test fun stateTupleAndReadOnlyUnliftedArrayContractsRetainCarrierChecks() {
        val state = CoreRepresentation(CoreKind.VOID, false, false, emptyList())
        val objectRep = CoreRepresentation(CoreKind.OBJECT, false, false, listOf("BoxedRep (Just Unlifted)"))
        val integer = CoreRepresentation(CoreKind.LONG, false, false, listOf("IntRep"))
        fun tuple(vararg fields: CoreRepresentation) = CoreRepresentation(CoreKind.UNKNOWN, false, false, fields.flatMap { it.primReps!! }, fields.toList())
        for ((name, value) in listOf("listThreads#" to objectRep, "isCurrentThreadBound#" to integer)) {
            val result = tuple(state, value)
            CoreThreadObservation.validate(name, listOf(state), listOf(false), result)
            assertThrows(RuntimeFault::class.java) { CoreThreadObservation.validate(name, listOf(state), listOf(true), result) }
            assertThrows(RuntimeFault::class.java) { CoreThreadObservation.validate(name, listOf(integer), listOf(false), result) }
            for (bad in listOf(tuple(value, state), tuple(value), value, tuple(state, state)))
                assertThrows(RuntimeFault::class.java) { CoreThreadObservation.validate(name, listOf(state), listOf(false), bad) }
        }
        ArrayOp.INDEX.validate(listOf(objectRep, integer), listOf(false, false), tuple(objectRep))
        ArrayOp.READ.validate(listOf(objectRep, integer, state), listOf(false, false, false), tuple(state, objectRep))
        assertThrows(RuntimeFault::class.java) {
            ArrayOp.INDEX.validate(listOf(objectRep, integer), listOf(false, false), tuple(integer))
        }
        ArrayOp.NEW.validate(listOf(integer, objectRep, state), listOf(false, false, false), tuple(state, objectRep))
        assertThrows(RuntimeFault::class.java) {
            ArrayOp.NEW.validate(listOf(integer, integer, state), listOf(false, false, false), tuple(state, objectRep))
        }
    }
}
