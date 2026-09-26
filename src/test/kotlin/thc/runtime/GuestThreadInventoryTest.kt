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
            val foreign = first.enterForeign()
            try {
                first.enterCurrent()
                try { assertFalse(first.isCurrentBound(), "Foreign re-entry is not forkOS") }
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
        val state = CoreRepresentation(CoreKind.VOID, primReps = emptyList())
        val objectRep = CoreRepresentation(CoreKind.OBJECT, primReps = listOf("BoxedRep (Just Unlifted)"))
        val integer = CoreRepresentation(CoreKind.LONG, primReps = listOf("IntRep"))
        fun tuple(vararg fields: CoreRepresentation) = CoreRepresentation(CoreKind.UNKNOWN,
            primReps = fields.flatMap { it.primReps!! }, components = fields.toList())
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
