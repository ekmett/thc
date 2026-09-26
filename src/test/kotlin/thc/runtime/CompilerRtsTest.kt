// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CompilerRtsTest {
    private fun context() = Context.newBuilder("thc").build().also { it.initialize("thc") }
    private val proof = CoreRepresentation(CoreKind.ADDRESS, evaluated = true, present = true, primReps = listOf("AddrRep"))

    @Test fun uniqueCellsRetainAliasesAndRejectForeignOrDisposedContexts() {
        val first = context()
        val second = context()
        lateinit var counter: ManagedAddress
        lateinit var alias: ManagedAddress
        first.enter()
        try {
            counter = CoreDataLabels.fromCore("ghc_unique_counter64", proof)
            alias = counter.plus(8).plus(-8)
            val step = CoreDataLabels.fromCore("ghc_unique_inc", proof)
            assertEquals(0L, AtomicAddressOp.READ.numeric(counter))
            assertEquals(1L, AtomicAddressOp.READ.numeric(step))
            assertTrue(counter.sameLocation(alias))
            assertFalse(counter.sameLocation(step))
            AtomicAddressOp.WRITE.numeric(counter, -2)
            assertEquals(-2L, AtomicAddressOp.ADD.numeric(alias, 1))
            assertEquals(-1L, AtomicAddressOp.ADD.numeric(counter, 1))
            assertEquals(0L, AtomicAddressOp.CAS.numeric(alias, 0, 73))
            assertEquals(73L, AtomicAddressOp.READ.numeric(counter))
            assertThrows(RuntimeFault::class.java) { AtomicAddressOp.READ.numeric(counter.plus(1)) }
            assertThrows(RuntimeFault::class.java) { CoreDataLabels.fromCore("ghc_unique_counter64", proof.copy(evaluated = false)) }
        } finally { first.leave() }
        second.enter()
        try {
            assertEquals(0L, AtomicAddressOp.READ.numeric(CoreDataLabels.fromCore("ghc_unique_counter64", proof)))
            assertThrows(RuntimeFault::class.java) { AtomicAddressOp.READ.numeric(counter) }
            assertThrows(RuntimeFault::class.java) { alias.readWord8(0) }
            assertThrows(RuntimeFault::class.java) { counter.sameLocation(counter) }
        } finally { second.leave(); second.close(); first.close() }
        assertThrows(RuntimeFault::class.java) { AtomicAddressOp.READ.numeric(alias) }
    }

    @Test fun originalUniqueCellFetchAddIsAtomicAcrossGuestCarriers() {
        context().use { context ->
            context.enter()
            val address = try { CoreDataLabels.fromCore("ghc_unique_counter64", proof) } finally { context.leave() }
            val pool = Executors.newFixedThreadPool(4)
            try {
                val tasks = List(4) { Callable {
                    context.enter()
                    try { List(1000) { AtomicAddressOp.ADD.numeric(address, 1) } }
                    finally { context.leave() }
                } }
                val values = pool.invokeAll(tasks).flatMap { it.get(20, TimeUnit.SECONDS) }
                assertEquals((0L until 4000L).toSet(), values.toSet())
                context.enter()
                try { assertEquals(4000L, AtomicAddressOp.READ.numeric(address)) } finally { context.leave() }
            } finally { pool.shutdownNow() }
        }
    }

    @Test fun fastStringSlotKeepsWinnerAliveAndSeparateFromOtherRtsSlots() {
        val registry = StablePointers()
        val foreign = StablePointers()
        val value = Any()
        val winner = registry.make(value)
        val loser = registry.make(Any())
        val none = ManagedAddress.nullAddress()
        val slot = SharedCAFStore.FAST_STRING
        assertSame(none, registry.getOrSetSharedCAF(slot, none))
        assertSame(winner, registry.getOrSetSharedCAF(slot, winner))
        assertTrue(registry.equal(winner, registry.getOrSetSharedCAF(slot, loser)))
        registry.free(loser)
        assertSame(value, registry.dereference(registry.getOrSetSharedCAF(slot, none)))
        assertSame(none, registry.getOrSetSharedCAF(SharedCAFStore.EVENT_MANAGER, none))
        assertThrows(RuntimeFault::class.java) { registry.free(winner) }
        assertThrows(RuntimeFault::class.java) { registry.getOrSetSharedCAF(slot, foreign.make(Any())) }
        registry.close()
        assertThrows(RuntimeFault::class.java) { registry.dereference(winner) }
        foreign.close()
    }
}
