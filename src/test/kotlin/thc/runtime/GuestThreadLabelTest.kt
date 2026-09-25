// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.atomic.AtomicReference

@Timeout(20)
class GuestThreadLabelTest {
    private fun registry() = GuestThreads(ThreadLocal.withInitial { MaskingState.UNMASKED }) { }

    @Test fun exactArrayIdentityLogicalSizeAndHostNameSurviveReentry() {
        val threads = registry()
        val name = Thread.currentThread().name
        threads.enterCurrent()
        val id = threads.currentIdentity()
        try {
            assertNull(threads.label(id))
            val raw = "λ\u0000x".toByteArray(Charsets.UTF_8)
            threads.label(id, raw)
            assertSame(raw, threads.label(id))
            val owned = ManagedByteArray.allocateGuest(8)
            ManagedByteArray.writeGuest(owned, 0, 65)
            ManagedByteArray.shrinkGuest(owned, 1)
            val frozen = ManagedByteArray.freezeGuest(owned)
            threads.label(id, frozen)
            assertSame(frozen, threads.label(id))
            assertEquals(1L, ManagedByteArray.sizeGuest(threads.label(id)))
            assertEquals(65L, ManagedByteArray.readGuest(threads.label(id), 0, true))
            assertThrows(RuntimeFault::class.java) { threads.label(id, "wrong carrier") }
            assertSame(frozen, threads.label(id), "Failed labels leave the old value intact")
        } finally { threads.leaveCurrent() }
        threads.enterCurrent()
        try {
            assertSame(id, threads.currentIdentity())
            assertNotNull(threads.label(id))
            assertEquals(Thread.currentThread().threadId(), id.javaId)
            assertEquals(name, Thread.currentThread().name)
            val empty = byteArrayOf()
            threads.label(id, empty)
            assertSame(empty, threads.label(id), "Empty is present, not absent")
        } finally { threads.leaveCurrent(); threads.close() }
        assertThrows(RuntimeFault::class.java) { threads.label(id) }
        assertThrows(RuntimeFault::class.java) { threads.label(id, byteArrayOf()) }
    }

    @Test fun labelsRemainOnDeadIdentitiesAndOtherContextsCannotReadOrOverwriteThem() {
        val threads = registry()
        for (outcome in listOf(GuestThreadStatus.FINISHED, GuestThreadStatus.DIED)) {
            val retained = AtomicReference<GuestThreadId>()
            val bytes = byteArrayOf(65, 0, 66)
            val child = Thread {
                threads.enterCurrent(forked = true)
                val id = threads.currentIdentity()
                retained.set(id)
                try { threads.label(id, bytes) } finally { threads.leaveCurrent(outcome) }
            }
            child.start(); child.join(5000)
            assertFalse(child.isAlive)
            val id = retained.get()
            assertEquals(outcome, threads.status(id))
            assertSame(bytes, threads.label(id))
            val other = registry()
            assertThrows(RuntimeFault::class.java) { other.label(id) }
            assertThrows(RuntimeFault::class.java) { other.label(id, byteArrayOf()) }
            assertSame(bytes, threads.label(id))
            val replacement = byteArrayOf(67)
            threads.label(id, replacement)
            assertSame(replacement, threads.label(id))
            other.close()
        }
        threads.close()
    }

    @Test fun exactProofRejectsLiftedBytesFlagsAndIncorrectGetterTuple() {
        val state = CoreRepresentation(CoreKind.VOID, primReps = emptyList())
        val opaque = CoreRepresentation(CoreKind.OBJECT, primReps = listOf("BoxedRep (Just Unlifted)"))
        val int = CoreRepresentation(CoreKind.LONG, primReps = listOf("IntRep"))
        val tuple = CoreRepresentation(CoreKind.UNKNOWN, primReps = listOf("IntRep", "BoxedRep (Just Unlifted)"),
            components = listOf(state, int, opaque))
        CoreGuestThreads.validate("labelThread#", listOf(opaque, opaque, state), List(3) { false }, state)
        CoreGuestThreads.validate("threadLabel#", listOf(opaque, state), List(2) { false }, tuple)
        assertThrows(RuntimeFault::class.java) {
            CoreGuestThreads.validate("labelThread#", listOf(opaque,
                opaque.copy(primReps = listOf("BoxedRep (Just Lifted)")), state), List(3) { false }, state)
        }
        assertThrows(RuntimeFault::class.java) {
            CoreGuestThreads.validate("labelThread#", listOf(opaque, opaque, state), listOf(false, true, false), state)
        }
        for (bad in listOf(tuple.copy(primReps = listOf("IntRep")),
                tuple.copy(components = listOf(state, opaque, int))))
            assertThrows(RuntimeFault::class.java) {
                CoreGuestThreads.validate("threadLabel#", listOf(opaque, state), List(2) { false }, bad)
            }
    }
}
