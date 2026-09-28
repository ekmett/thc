// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.Set;
import java.util.function.Function;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RuntimeMemoryServicesTest {
    @Test void selectorsPreserveBytesAndChooseHeapOrNonheap() {
        var heap = new MemoryUsage(1024, 2048, 4096, 8192);
        var nonheap = new MemoryUsage(64, 128, 256, 512);
        long[] expected = {2048, 4096, 8192, 1024, 128, 256, 512, 64};
        for (int selector = 200; selector <= 207; selector++) {
            int[] reads = {0};
            long actual = RuntimeMemoryServices.queryJvm(selector, 0, 0, isHeap -> {
                reads[0]++;
                return isHeap ? heap : nonheap;
            });
            assertEquals(expected[selector - 200], actual);
            assertEquals(1, reads[0], "Each query obtains one MemoryUsage");
        }
    }

    @Test void unknownFieldsAreNotReportedAsZeroOrOtherStatuses() {
        var unknown = new MemoryUsage(-1, 0, 0, -1);
        for (int selector : new int[]{202, 203, 206, 207}) {
            assertEquals(RuntimeServiceStatus.UNAVAILABLE,
                RuntimeMemoryServices.queryJvm(selector, 0, 0, heap -> unknown));
        }
        for (int selector : new int[]{200, 201, 204, 205}) {
            assertEquals(0L, RuntimeMemoryServices.queryJvm(selector, 0, 0, heap -> unknown));
        }
        assertEquals(RuntimeServiceStatus.UNAVAILABLE,
            RuntimeMemoryServices.queryJvm(200, 0, 0, heap -> null));
    }

    @Test void optionalProviderFailuresDoNotHideVmFailuresOrRuntimeFaults() {
        assertEquals(RuntimeServiceStatus.DENIED,
            RuntimeMemoryServices.queryJvm(200, 0, 0, heap -> { throw new SecurityException("read denied"); }));
        assertEquals(RuntimeServiceStatus.UNSUPPORTED,
            RuntimeMemoryServices.queryJvm(200, 0, 0, heap -> { throw new UnsupportedOperationException(); }));
        var fatal = new AssertionError("fatal");
        assertSame(fatal, assertThrows(AssertionError.class, () ->
            RuntimeMemoryServices.queryJvm(200, 0, 0, heap -> { throw fatal; })));
        var fault = new RuntimeFault("cancel/control");
        assertSame(fault, assertThrows(RuntimeFault.class, () ->
            RuntimeMemoryServices.queryJvm(200, 0, 0, heap -> { throw fault; })));
    }

    @Test void malformedArgumentsAreRejectedBeforeReadingTheProvider() {
        for (long[] request : new long[][]{{199, 0, 0}, {208, 0, 0},
                {200, -1, 0}, {200, Long.MAX_VALUE, 0}, {200, 0, -1}}) {
            assertThrows(RuntimeFault.class, () -> RuntimeMemoryServices.queryJvm(
                (int) request[0], request[1], request[2], heap -> fail("Provider must not be called")));
        }
    }

    @Test void realJvmProviderReportsReadOnlyStatistics() {
        var bean = ManagementFactory.getMemoryMXBean();
        boolean verbose = bean.isVerbose();
        for (int selector = 200; selector <= 207; selector++) {
            long value = RuntimeMemoryServices.queryJvm(selector, 0, 0);
            assertTrue(value >= 0 || value == RuntimeServiceStatus.UNAVAILABLE, "selector " + selector + ": " + value);
        }
        assertEquals(verbose, bean.isVerbose(), "Queries do not alter JVM-global GC verbosity");
    }

    @Test void emptyOwnedNativeStatisticsDoNotRequireNativeAccess() {
        context(false, state -> {
            assertEquals(0L, query(state, 208));
            assertEquals(0L, query(state, 209));
            assertThrows(RuntimeFault.class, () -> RuntimeMemoryServices.query(state, 210, 0, 0));
            assertThrows(RuntimeFault.class, () -> RuntimeMemoryServices.query(state, 208, 1, 0));
            assertThrows(RuntimeFault.class, () -> RuntimeMemoryServices.query(state, 209, 0, 1));
            return null;
        });
    }

    @Test void ownedBytesFollowMallocReallocFreeAndContextIsolation() {
        nativePlatform();
        context(true, first -> {
            var registry = first.getNativeAllocations$org_intelligence_thc();
            var a = registry.malloc(19);
            var b = registry.malloc(31);
            assertNotSame(ManagedAddress.Companion.nullAddress(), a);
            assertNotSame(ManagedAddress.Companion.nullAddress(), b);
            assertEquals(50L, query(first, 208));
            assertEquals(2L, query(first, 209));
            context(true, second -> {
                assertEquals(0L, query(second, 208));
                assertEquals(0L, query(second, 209));
                second.getNativeAllocations$org_intelligence_thc().malloc(7);
                assertEquals(7L, query(second, 208));
                assertEquals(1L, query(second, 209));
                return null;
            });
            assertEquals(50L, query(first, 208));
            var resized = registry.realloc(a, 67);
            assertNotSame(ManagedAddress.Companion.nullAddress(), resized);
            assertEquals(98L, query(first, 208));
            assertEquals(2L, query(first, 209));
            registry.free(b);
            assertEquals(67L, query(first, 208));
            assertEquals(1L, query(first, 209));
            assertSame(ManagedAddress.Companion.nullAddress(), registry.realloc(resized, 0));
            assertEquals(0L, query(first, 208));
            assertEquals(0L, query(first, 209));
            return null;
        });
    }

    @Test void zeroSizedAllocationsAndFailureHaveHonestOwnershipCounts() {
        nativePlatform();
        context(true, state -> {
            var registry = state.getNativeAllocations$org_intelligence_thc();
            var zero = registry.malloc(0);
            long expectedCount = zero == ManagedAddress.Companion.nullAddress() ? 0L : 1L;
            assertEquals(0L, query(state, 208));
            assertEquals(expectedCount, query(state, 209));
            assertSame(ManagedAddress.Companion.nullAddress(), registry.malloc(Long.MAX_VALUE));
            assertEquals(0L, query(state, 208));
            assertEquals(expectedCount, query(state, 209));
            registry.free(zero);
            assertEquals(0L, query(state, 209));
            return null;
        });
    }

    @Test void contextDisposalReleasesRecordedAllocations() {
        nativePlatform();
        var registry = context(true, state -> {
            state.getNativeAllocations$org_intelligence_thc().malloc(37);
            assertEquals(37L, query(state, 208));
            return state.getNativeAllocations$org_intelligence_thc();
        });
        assertEquals(0L, registry.liveBytes$org_intelligence_thc());
        assertEquals(0, registry.liveCount$org_intelligence_thc());
    }

    private static long query(Language.State state, int selector) {
        return RuntimeMemoryServices.query(state, selector, 0, 0);
    }

    private static void nativePlatform() {
        assumeTrue(System.getProperty("os.name").equals("Linux") &&
            Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")));
    }

    private static <T> T context(boolean nativeAccess, Function<Language.State, T> action) {
        try (var context = Context.newBuilder("thc").allowNativeAccess(nativeAccess).build()) {
            context.initialize("thc");
            context.enter();
            try { return action.apply(Language.currentState(null)); }
            finally { context.leave(); }
        }
    }
}
