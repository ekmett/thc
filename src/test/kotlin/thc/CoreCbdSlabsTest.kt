// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CoreCbdSlabsTest {
    private fun slab(size: Long): CoreCbdSlabs.Slab = Arena.ofShared().let { arena ->
        CoreCbdSlabs.Slab(arena, arena.allocate(size).asReadOnly())
    }
    private fun key(name: String) = CoreCbdSlabs.Key(name, "data")

    @Test fun idleByteAndEntryBudgetsEvictOnlyLastReleasedEntriesNotActiveHandles() {
        CoreCbdSlabs(5, 2).use { cache ->
            val live = cache.acquire(key("live")) { slab(100) }
            val a = cache.acquire(key("a")) { slab(3) }; val old = a.bytes; a.close()
            cache.acquire(key("b")) { slab(3) }.close()
            assertThrows(IllegalStateException::class.java) { old.get(ValueLayout.JAVA_BYTE, 0) }
            assertEquals(3L, cache.statistics().idleBytes)
            assertEquals(100L, live.bytes.byteSize())
            cache.acquire(key("b")) { fail("Unexpected reinflation") }.close()
            cache.close()
            assertEquals(100L, live.bytes.byteSize())
            live.close()
            assertEquals(3L, cache.statistics().closes)
        }
        CoreCbdSlabs(100, 1).use { cache ->
            cache.acquire(key("a")) { slab(1) }.close()
            cache.acquire(key("b")) { slab(1) }.close()
            assertEquals(1, cache.statistics().idleEntries)
            assertEquals(1L, cache.statistics().closes)
        }
    }
    @Test fun zeroLengthEntriesStillRespectEntryBudgetAndFailuresReleaseReservations() {
        CoreCbdSlabs(0, 1).use { cache ->
            for (name in listOf("a", "b")) cache.acquire(key(name)) { slab(0) }.close()
            assertEquals(1, cache.statistics().idleEntries)
            assertEquals(1L, cache.statistics().closes)
            assertThrows(CancellationException::class.java) { cache.acquire(key("failure")) { throw CancellationException("control") } }
            assertEquals(0L, cache.statistics().activeLeases)
            cache.acquire(key("failure")) { slab(0) }.close()
            assertEquals(3L, cache.statistics().inflations)
            assertEquals(1L, cache.statistics().failures)
        }
    }
}
