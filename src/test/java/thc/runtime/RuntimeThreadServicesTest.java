// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import kotlin.Unit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Timeout(30)
class RuntimeThreadServicesTest {
    private GuestThreads threads() { return threads(new CpuAffinity(null, 4)); }
    private GuestThreads threads(CpuAffinity affinity) {
        return new GuestThreads(ThreadLocal.withInitial(() -> MaskingState.UNMASKED), affinity, ignored -> Unit.INSTANCE);
    }
    private long query(GuestThreads threads, int selector) { return query(threads, selector, 0, 0, true); }
    private long query(GuestThreads threads, int selector, long index) { return query(threads, selector, index, 0, true); }
    private long query(GuestThreads threads, int selector, long index, long detail, boolean nativeAccess) {
        return RuntimeThreadServices.query(threads, nativeAccess, selector, index, detail);
    }
    private ThreadMXBean bean(Function<String, Object> answer) { return bean(true, answer); }
    private ThreadMXBean bean(boolean extended, Function<String, Object> answer) {
        return (ThreadMXBean) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{extended ? com.sun.management.ThreadMXBean.class : ThreadMXBean.class}, (proxy, method, args) -> {
                assertFalse(method.getName().startsWith("set"), "Observation must not enable JVM accounting");
                return answer.apply(method.getName());
            });
    }
    @Test void supportedAccountingReadsExactValuesWithoutMutatingOrEnumeratingThreads() {
        var calls = new ArrayList<String>();
        var management = bean(name -> {
            calls.add(name);
            return switch (name) {
                case "isCurrentThreadCpuTimeSupported", "isThreadCpuTimeEnabled", "isThreadAllocatedMemorySupported", "isThreadAllocatedMemoryEnabled" -> true;
                case "getCurrentThreadCpuTime" -> 17L;
                case "getCurrentThreadUserTime" -> 11L;
                case "getCurrentThreadAllocatedBytes" -> 4096L;
                default -> throw new IllegalStateException("Unexpected bean operation: " + name);
            };
        });
        assertEquals(17L, RuntimeThreadServices.accounting(105, false, () -> management));
        assertEquals(11L, RuntimeThreadServices.accounting(106, false, () -> management));
        assertEquals(4096L, RuntimeThreadServices.accounting(107, false, () -> management));
        assertEquals(List.of("isCurrentThreadCpuTimeSupported", "isThreadCpuTimeEnabled", "getCurrentThreadCpuTime",
            "isCurrentThreadCpuTimeSupported", "isThreadCpuTimeEnabled", "getCurrentThreadUserTime",
            "isThreadAllocatedMemorySupported", "isThreadAllocatedMemoryEnabled", "getCurrentThreadAllocatedBytes"), calls);
    }
    @Test void disabledUnsupportedDeniedAndUnavailableAreDistinct() {
        for (int selector = 105; selector <= 107; selector++) {
            var disabled = bean(name -> {
                if (name.endsWith("Supported")) return true;
                if (name.endsWith("Enabled")) return false;
                throw new IllegalStateException("Disabled measurement must not be read: " + name);
            });
            assertEquals(RuntimeServiceStatus.DISABLED, RuntimeThreadServices.accounting(selector, false, () -> disabled));
            var unsupported = bean(name -> {
                if (name.endsWith("Supported")) return false;
                throw new IllegalStateException("Unsupported measurement queried: " + name);
            });
            assertEquals(RuntimeServiceStatus.UNSUPPORTED, RuntimeThreadServices.accounting(selector, false, () -> unsupported));
            var unavailable = bean(name -> name.startsWith("is") ? true : -1L);
            assertEquals(RuntimeServiceStatus.UNAVAILABLE, RuntimeThreadServices.accounting(selector, false, () -> unavailable));
            assertEquals(RuntimeServiceStatus.DENIED, RuntimeThreadServices.accounting(selector, false, () -> { throw new SecurityException("management denied"); }));
            assertEquals(RuntimeServiceStatus.UNSUPPORTED, RuntimeThreadServices.accounting(selector, false, () -> { throw new UnsupportedOperationException("no provider"); }));
            var deniedRead = bean(name -> {
                if (name.startsWith("is")) return true;
                throw new SecurityException("read denied");
            });
            assertEquals(RuntimeServiceStatus.DENIED, RuntimeThreadServices.accounting(selector, false, () -> deniedRead));
        }
        var standard = bean(false, name -> { throw new IllegalStateException("Allocation requires the optional extension"); });
        assertEquals(RuntimeServiceStatus.UNSUPPORTED, RuntimeThreadServices.accounting(107, false, () -> standard));
    }
    @Test void optionalAccountingDoesNotHideProgrammingOrVmFailures() {
        assertThrows(IllegalStateException.class, () -> RuntimeThreadServices.accounting(105, false,
            () -> { throw new IllegalStateException("unexpected provider failure"); }));
        assertThrows(AssertionError.class, () -> RuntimeThreadServices.accounting(105, false,
            () -> { throw new AssertionError("VM control"); }));
        assertThrows(RuntimeFault.class, () -> RuntimeThreadServices.accounting(100));
    }
    @Test void realPlatformThreadAccountingPreservesInstrumentationSettings() {
        var management = ManagementFactory.getThreadMXBean();
        var allocation = management instanceof com.sun.management.ThreadMXBean bean ? bean : null;
        Boolean cpuEnabled = management.isCurrentThreadCpuTimeSupported() ? management.isThreadCpuTimeEnabled() : null;
        Boolean allocationEnabled = allocation != null && allocation.isThreadAllocatedMemorySupported() ? allocation.isThreadAllocatedMemoryEnabled() : null;
        assertEquals(1L, query(threads(), 100));
        for (int selector = 105; selector <= 107; selector++) {
            var enabled = selector == 107 ? allocationEnabled : cpuEnabled;
            long before = RuntimeThreadServices.accounting(selector);
            long after = RuntimeThreadServices.accounting(selector);
            if (enabled == null) assertEquals(RuntimeServiceStatus.UNSUPPORTED, before);
            else if (!enabled) assertEquals(RuntimeServiceStatus.DISABLED, before);
            else {
                assertTrue(before >= 0, "Pinned JVM supports this platform-thread measurement");
                assertTrue(after >= before);
            }
        }
        if (cpuEnabled != null) assertEquals(cpuEnabled, management.isThreadCpuTimeEnabled());
        if (allocationEnabled != null) assertEquals(allocationEnabled, allocation.isThreadAllocatedMemoryEnabled());
    }
    @Test void actualVirtualThreadNeverReportsCarrierAccountingOrAffinity() throws Exception {
        var task = new FutureTask<Void>(() -> {
            var current = threads();
            assertEquals(2L, query(current, 100));
            assertEquals(RuntimeServiceStatus.UNSUPPORTED, query(current, 103));
            for (int selector = 105; selector <= 107; selector++) {
                assertEquals(RuntimeServiceStatus.UNSUPPORTED, query(current, selector));
                assertEquals(RuntimeServiceStatus.UNSUPPORTED, RuntimeThreadServices.accounting(selector,
                    () -> { throw new IllegalStateException("Virtual thread must not ask a bean for carrier accounting"); }));
            }
            return null;
        });
        Thread.ofVirtual().start(task);
        task.get(10, TimeUnit.SECONDS);
    }
    @Test void logicalLockAndRecordedAcceptanceAreIndependentOfNativeSupport() {
        var current = threads();
        current.enterCurrent(null, false, true, -1L);
        try {
            assertEquals(3L, query(current, 101));
            assertEquals(1L, query(current, 102));
            assertEquals(0L, query(current, 104));
            assertEquals(RuntimeServiceStatus.DENIED, query(current, 103, 0, 0, false));
            current.currentIdentity().setAffinityApplied(true);
            assertEquals(1L, query(current, 104), "Reports accepted-at-fork state, not a fresh OS guarantee");
            var separate = threads();
            separate.enterCurrent(null, false, true, null);
            try {
                assertEquals(0L, query(separate, 102));
                assertEquals(0L, query(separate, 104), "Context identities do not share acceptance state");
            } finally { separate.leaveCurrent(GuestThreadStatus.FINISHED); }
        } finally { current.leaveCurrent(GuestThreadStatus.FINISHED); }
        assertThrows(RuntimeFault.class, () -> query(current, 101));
    }
    @Test void eligibilityUsesQuotaBoundedDenseMappingAndNeverChangesAffinity() {
        var coordinates = List.of(new CpuCoordinate(4, 1), new CpuCoordinate(4, 17), new CpuCoordinate(4, 63));
        var affinity = new CpuAffinity(new NativeCpuAffinity() {
            @Override public int getCount() { return coordinates.size(); }
            @Override public CpuAffinityMode getMode() { return CpuAffinityMode.ADVISORY; }
            @Override public CpuCoordinate coordinate(int index) { return index >= 0 && index < coordinates.size() ? coordinates.get(index) : null; }
            @Override public AutoCloseable bindCurrent(int index) { throw new IllegalStateException("Read-only query tried to bind"); }
            @Override public AutoCloseable resetCurrent() { throw new IllegalStateException("Read-only query tried to reset"); }
        }, 2);
        var current = threads(affinity);
        assertEquals(1L, query(current, 103));
        assertEquals(2L, query(current, 108), "JVM quota bounds the capability mapping");
        assertEquals(4L, query(current, 109, 1));
        assertEquals(17L, query(current, 110, 1));
        assertNull(affinity.coordinate(2)); assertNull(affinity.coordinate(-1));
        assertThrows(RuntimeFault.class, () -> query(current, 110, 2));
        for (int selector : new int[]{103, 108, 109, 110})
            assertEquals(RuntimeServiceStatus.DENIED, query(current, selector, 0, 0, false));
    }
    @Test void realLinuxCoordinatesMatchOriginalSparseMaskWithoutPinning() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"));
        var provider = LinuxCpuAffinity.Companion.discover();
        assertNotNull(provider, "Native access is enabled in the test JVM");
        var original = provider.currentMask$org_intelligence_thc();
        assertNotNull(original);
        var cpus = new ArrayList<Integer>();
        for (int i = 0; i < original.length * 8; i++) if ((original[i / 8] & (1 << (i % 8))) != 0) cpus.add(i);
        var affinity = new CpuAffinity(provider, Runtime.getRuntime().availableProcessors());
        var current = threads(affinity);
        assertEquals(2L, query(current, 103));
        assertEquals((long) affinity.getCount(), query(current, 108));
        for (int index = 0; index < affinity.getCount(); index++) {
            assertEquals(0L, query(current, 109, index));
            assertEquals(cpus.get(index).longValue(), query(current, 110, index));
        }
        assertArrayEquals(original, provider.currentMask$org_intelligence_thc());
    }
    @Test void malformedQueryIndicesAndUnknownSelectorsFailExplicitly() {
        var current = threads();
        for (int value = 100; value <= 110; value++) {
            int selector = value;
            assertThrows(RuntimeFault.class, () -> query(current, selector, 0, 1, true));
            assertThrows(RuntimeFault.class, () -> query(current, selector, -1));
            if (selector < 109) assertThrows(RuntimeFault.class, () -> query(current, selector, 1));
        }
        for (int selector : new int[]{99, 111, 199}) assertThrows(RuntimeFault.class, () -> query(current, selector));
        assertThrows(RuntimeFault.class, () -> query(current, 110, Long.MAX_VALUE));
    }
}
