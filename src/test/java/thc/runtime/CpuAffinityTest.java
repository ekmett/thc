// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import thc.runtime.Unit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Timeout(30)
class CpuAffinityTest {
    @Test void unavailableNativeAccessStillReportsCpuCapacityAndForkSelection() throws Exception {
        var affinity = CpuAffinity.discover(false);
        assertEquals(CpuAffinityMode.UNAVAILABLE, affinity.getMode());
        assertEquals(Runtime.getRuntime().availableProcessors(), affinity.getCount());
        assertNull(affinity.bindCurrent(Long.MIN_VALUE));
        assertNull(affinity.resetCurrent());
        var threads = new GuestThreads(ThreadLocal.withInitial(() -> MaskingState.UNMASKED), new CpuAffinity(null, 3), ignored -> {});
        for (long requested : new long[]{Long.MIN_VALUE, -1, 0, 4, Long.MAX_VALUE}) inThread(() -> {
            threads.enterCurrent(null, true, true, requested);
            try {
                assertEquals(Math.floorMod(requested, 3L), threads.currentIdentity().getCapability());
                assertTrue(threads.currentIdentity().getCapabilityLocked());
                assertFalse(threads.currentIdentity().getAffinityApplied());
                assertEquals(3L, threads.capabilityCount());
            } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
        });
    }
    @Test void logicalCountRespectsQuotaAndRejectedRequestIsOptional() {
        var requests = new ArrayList<Integer>();
        var provider = new NativeCpuAffinity() {
            @Override public int getCount() { return 8; }
            @Override public CpuAffinityMode getMode() { return CpuAffinityMode.PINNED; }
            @Override public AutoCloseable bindCurrent(int index) { requests.add(index); return null; }
            @Override public AutoCloseable resetCurrent() { return null; }
        };
        var affinity = new CpuAffinity(provider, 3);
        assertEquals(3, affinity.getCount());
        assertNull(affinity.bindCurrent(-1));
        assertEquals(List.of(2), requests);
        assertEquals(8, new CpuAffinity(provider, 32).getCount());
        assertEquals(1, new CpuAffinity(null, 0).getCount());
    }
    @Test void linuxPinAndNestedResetRestoreMasksEvenOnExceptions() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"));
        var provider = LinuxCpuAffinity.discover();
        assertNotNull(provider, "Linux test JVM enables native access");
        var original = provider.currentMask();
        assertNotNull(original);
        int expectedCount = Math.min(provider.getCount(), Runtime.getRuntime().availableProcessors());
        var affinity = new CpuAffinity(provider, expectedCount);
        inThread(() -> {
            var pin = provider.bindCurrent(0);
            assertNotNull(pin, "At least the current eligible CPU must be selectable");
            assertThrows(IllegalStateException.class, () -> {
                try (pin) {
                    var singleton = provider.currentMask();
                    assertNotNull(singleton);
                    assertEquals(1, bits(singleton));
                    assertEquals(expectedCount, affinity.getCount(), "Snapshot does not query a pinned carrier");
                    inThread(() -> {
                        assertArrayEquals(singleton, provider.currentMask(), "Linux pthread inheritance");
                        try (var reset = provider.resetCurrent()) {
                            assertArrayEquals(original, provider.currentMask());
                            System.gc();
                            for (int i = 0; i < 128; i++) assertEquals(8192, new byte[8192].length);
                        }
                        assertArrayEquals(singleton, provider.currentMask());
                    });
                    assertArrayEquals(singleton, provider.currentMask());
                    // Production start(): broaden the creator, create a child, then restore its pin.
                    try (var reset = provider.resetCurrent()) {
                        inThread(() -> assertArrayEquals(original, provider.currentMask()));
                    }
                    assertArrayEquals(singleton, provider.currentMask());
                    throw new IllegalStateException("guest unwind");
                }
            });
            assertArrayEquals(original, provider.currentMask());
            assertNull(provider.bindCurrent(-1));
            assertNull(provider.bindCurrent(provider.getCount()));
        });
        assertArrayEquals(original, provider.currentMask(), "Never changes the unrelated host carrier");
        var virtualFailure = new AtomicReference<Throwable>();
        var virtual = Thread.ofVirtual().start(() -> {
            try { assertNull(provider.bindCurrent(0)); assertNull(provider.resetCurrent()); }
            catch (Throwable failure) { virtualFailure.set(failure); }
        });
        virtual.join();
        if (virtualFailure.get() != null) throw new AssertionError("virtual guard", virtualFailure.get());
    }
    private int bits(byte[] mask) {
        int count = 0;
        for (byte value : mask) count += Integer.bitCount(value & 255);
        return count;
    }
    private void inThread(Executable action) throws Exception {
        var failure = new AtomicReference<Throwable>();
        var thread = new Thread(() -> { try { action.execute(); } catch (Throwable error) { failure.set(error); } });
        thread.start(); thread.join(10000);
        assertFalse(thread.isAlive());
        if (failure.get() != null) throw new AssertionError("platform child", failure.get());
    }
}
