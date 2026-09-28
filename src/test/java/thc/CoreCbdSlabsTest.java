// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.zip.DataFormatException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoreCbdSlabsTest {
    private CoreCbdSlabs.Slab slab(long size) {
        var arena = Arena.ofShared(); return new CoreCbdSlabs.Slab(arena, arena.allocate(size).asReadOnly());
    }
    private CoreCbdSlabs.Key key(String name) { return new CoreCbdSlabs.Key(name, "data"); }
    @Test void checkedInflationFailureKeepsExactIdentityAndLeavesNoReservation() {
        try (var cache = new CoreCbdSlabs(32, 2)) {
            var failure = new DataFormatException("original checked inflater failure");
            var actual = assertThrows(DataFormatException.class, () -> cache.acquire(key("checked"), () -> { throw failure; }));
            assertSame(failure, actual);
            assertEquals(0L, cache.statistics().activeLeases()); assertEquals(0, cache.statistics().idleEntries());
            assertEquals(1L, cache.statistics().failures());
            try (var it = cache.acquire(key("checked"), () -> slab(4))) {
                assertTrue(it.getInflated()); assertEquals(4L, it.getBytes().byteSize());
            }
            assertEquals(1L, cache.statistics().inflations());
        }
    }
    @Test void idleByteAndEntryBudgetsEvictOnlyLastReleasedEntriesNotActiveHandles() {
        try (var cache = new CoreCbdSlabs(5, 2)) {
            var live = cache.acquire(key("live"), () -> slab(100));
            var a = cache.acquire(key("a"), () -> slab(3)); var old = a.getBytes(); a.close();
            cache.acquire(key("b"), () -> slab(3)).close();
            assertThrows(IllegalStateException.class, () -> old.get(ValueLayout.JAVA_BYTE, 0));
            assertEquals(3L, cache.statistics().idleBytes()); assertEquals(100L, live.getBytes().byteSize());
            cache.acquire(key("b"), () -> fail("Unexpected reinflation")).close();
            cache.close(); assertEquals(100L, live.getBytes().byteSize()); live.close();
            assertEquals(3L, cache.statistics().closes());
        }
        try (var cache = new CoreCbdSlabs(100, 1)) {
            cache.acquire(key("a"), () -> slab(1)).close(); cache.acquire(key("b"), () -> slab(1)).close();
            assertEquals(1, cache.statistics().idleEntries()); assertEquals(1L, cache.statistics().closes());
        }
    }
    @Test void zeroLengthEntriesStillRespectEntryBudgetAndFailuresReleaseReservations() {
        try (var cache = new CoreCbdSlabs(0, 1)) {
            for (var name : List.of("a", "b")) cache.acquire(key(name), () -> slab(0)).close();
            assertEquals(1, cache.statistics().idleEntries()); assertEquals(1L, cache.statistics().closes());
            assertThrows(CancellationException.class, () -> cache.acquire(key("failure"), () -> { throw new CancellationException("control"); }));
            assertEquals(0L, cache.statistics().activeLeases()); cache.acquire(key("failure"), () -> slab(0)).close();
            assertEquals(3L, cache.statistics().inflations()); assertEquals(1L, cache.statistics().failures());
        }
    }
}
