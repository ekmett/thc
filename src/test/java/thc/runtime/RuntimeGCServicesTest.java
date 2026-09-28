// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import javax.management.ObjectName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeGCServicesTest {
    private record Collector(String label, long count, long millis, boolean valid) implements GarbageCollectorMXBean {
        Collector(String label) { this(label, 17, 23, true); }
        Collector(String label, long count, long millis) { this(label, count, millis, true); }
        @Override public String getName() { return label; }
        @Override public long getCollectionCount() { return count; }
        @Override public long getCollectionTime() { return millis; }
        @Override public boolean isValid() { return valid; }
        @Override public String[] getMemoryPoolNames() { return new String[0]; }
        @Override public ObjectName getObjectName() {
            try { return new ObjectName("thc.test:type=GarbageCollector"); }
            catch (javax.management.MalformedObjectNameException e) { throw new AssertionError(e); }
        }
    }
    @Test void namesAreUnicodeCodepointsAndCountersPreserveUnits() {
        var beans = List.of(new Collector("young"), new Collector("old \uD83D\uDE80", 29, 31));
        assertEquals(2L, RuntimeGCServices.queryJvm(300, 0, 0, () -> beans));
        assertEquals(5L, RuntimeGCServices.queryJvm(301, 1, -1, () -> beans));
        assertEquals((long) 'o', RuntimeGCServices.queryJvm(301, 1, 0, () -> beans));
        assertEquals(0x1f680L, RuntimeGCServices.queryJvm(301, 1, 4, () -> beans));
        assertEquals(17L, RuntimeGCServices.queryJvm(302, 0, 0, () -> beans));
        assertEquals(23L, RuntimeGCServices.queryJvm(303, 0, 0, () -> beans));
        assertEquals(29L, RuntimeGCServices.queryJvm(302, 1, 0, () -> beans));
        assertEquals(31L, RuntimeGCServices.queryJvm(303, 1, 0, () -> beans));
        assertThrows(RuntimeFault.class, () -> RuntimeGCServices.queryJvm(301, 1, 5, () -> beans));
    }
    @Test void missingCountersAndInvalidatedCollectorsAreUnavailable() {
        var missing = List.of(new Collector("uncounted", -1, -1), new Collector("invalid", 17, 23, false));
        for (int selector : new int[]{302, 303}) assertEquals(RuntimeServiceStatus.UNAVAILABLE,
            RuntimeGCServices.queryJvm(selector, 0, 0, () -> missing));
        for (int selector = 301; selector <= 303; selector++) assertEquals(RuntimeServiceStatus.UNAVAILABLE,
            RuntimeGCServices.queryJvm(selector, 1, selector == 301 ? -1 : 0, () -> missing));
        assertEquals(0L, RuntimeGCServices.queryJvm(302, 0, 0, () -> List.of(new Collector("zero", 0, 0))));
        assertEquals(0L, RuntimeGCServices.queryJvm(303, 0, 0, () -> List.of(new Collector("zero", 0, 0))));
    }
    @Test void emptyCollectorInventoryIsAnActualZeroNotUnknown() {
        assertEquals(0L, RuntimeGCServices.queryJvm(300, 0, 0, List::of));
        assertThrows(RuntimeFault.class, () -> RuntimeGCServices.queryJvm(301, 0, -1, List::of));
    }
    @Test void deniedAndUnsupportedQueriesDoNotSwallowUnexpectedFailures() {
        assertEquals(RuntimeServiceStatus.DENIED, RuntimeGCServices.queryJvm(300, 0, 0, () -> { throw new SecurityException(); }));
        assertEquals(RuntimeServiceStatus.UNSUPPORTED, RuntimeGCServices.queryJvm(300, 0, 0, () -> { throw new UnsupportedOperationException(); }));
        var fatal = new AssertionError("fatal");
        assertSame(fatal, assertThrows(AssertionError.class, () -> RuntimeGCServices.queryJvm(300, 0, 0, () -> { throw fatal; })));
        var unexpected = new IllegalStateException("unexpected");
        assertSame(unexpected, assertThrows(IllegalStateException.class, () -> RuntimeGCServices.queryJvm(300, 0, 0, () -> { throw unexpected; })));
    }
    @Test void malformedIndicesSelectorsAndOffsetsRemainDiagnostics() {
        for (var row : new long[][]{{299,0,0},{304,0,0},{300,1,0},{300,0,1},{302,-1,0},
            {303,Long.MAX_VALUE,0},{302,0,-1},{301,0,-2}}) {
            assertThrows(RuntimeFault.class, () -> RuntimeGCServices.queryJvm((int) row[0], row[1], row[2],
                () -> fail("Provider must not be called")));
        }
        assertThrows(RuntimeFault.class, () -> RuntimeGCServices.queryJvm(302, 1, 0, () -> List.of(new Collector("only"))));
    }
    @Test void realCollectorsMatchNamesAndOfferNonnegativeOrUnavailableCounters() {
        var beans = ManagementFactory.getGarbageCollectorMXBeans();
        assertEquals((long) beans.size(), RuntimeGCServices.queryJvm(300, 0, 0));
        for (int index = 0; index < beans.size(); index++) {
            var name = beans.get(index).getName().codePoints().toArray();
            assertEquals((long) name.length, RuntimeGCServices.queryJvm(301, index, -1));
            for (int offset = 0; offset < name.length; offset++)
                assertEquals((long) name[offset], RuntimeGCServices.queryJvm(301, index, offset));
            for (int selector = 302; selector <= 303; selector++) {
                long value = RuntimeGCServices.queryJvm(selector, index, 0);
                assertTrue(value >= 0 || value == RuntimeServiceStatus.UNAVAILABLE, selector + ": " + value);
            }
        }
    }
}
