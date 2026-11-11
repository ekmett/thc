// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
package com.oracle.svm.core.jam;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.List;
import javax.management.ObjectName;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import com.oracle.svm.core.GCRelatedMXBeans;
import com.oracle.svm.core.heap.AbstractMemoryMXBean;
import com.oracle.svm.core.heap.AbstractMXBean;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.AllAccess;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.DisallowLayered;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;
import com.sun.management.GarbageCollectorMXBean;
import com.sun.management.GcInfo;
import sun.management.Util;

/** Managed capacities exclude the image, guard pages and compressed-reference address gap. */
@SingletonTraits(access = AllAccess.class, layeredCallbacks = NoLayeredCallbacks.class, other = DisallowLayered.class)
final class JamRelatedMXBeans extends GCRelatedMXBeans {
    private static final String MINOR = "Jam minor";
    private static final String MAJOR = "Jam major";
    private static final String YOUNG = "Jam young generation";
    private static final String OLD = "Jam old generation";

    @Platforms(Platform.HOSTED_ONLY.class)
    JamRelatedMXBeans() {
        beans.addSingleton(MemoryMXBean.class, new Memory());
        beans.addList(MemoryPoolMXBean.class, List.of(new Pool(true), new Pool(false)));
        beans.addList(BufferPoolMXBean.class, List.of());
        beans.addList(GarbageCollectorMXBean.class, List.of(new Collector(true), new Collector(false)));
    }

    private static final class Memory extends AbstractMemoryMXBean {
        @Override public MemoryUsage getHeapMemoryUsage() {
            JamHeap heap = JamHeap.get();
            long maximum = heap.oldCapacity().add(heap.youngCapacity()).rawValue();
            return new MemoryUsage(maximum, heap.usedBytes(false) + heap.usedBytes(true), maximum, maximum);
        }
    }

    private static final class Collector extends AbstractMXBean implements GarbageCollectorMXBean {
        private final boolean minor;
        Collector(boolean minor) { this.minor = minor; }
        @Override public String getName() { return minor ? MINOR : MAJOR; }
        @Override public boolean isValid() { return true; }
        @Override public long getCollectionCount() { return minor ? JamGC.get().minorCount() : JamGC.get().majorCount(); }
        @Override public long getCollectionTime() { return minor ? JamGC.get().minorMillis() : JamGC.get().majorMillis(); }
        @Override public String[] getMemoryPoolNames() { return new String[]{YOUNG, OLD}; }
        @Override public ObjectName getObjectName() { return Util.newObjectName(ManagementFactory.GARBAGE_COLLECTOR_MXBEAN_DOMAIN_TYPE, getName()); }
        @Override public GcInfo getLastGcInfo() { return null; }
    }

    private static final class Pool implements MemoryPoolMXBean {
        private final boolean young;
        Pool(boolean young) { this.young = young; }
        @Override public String getName() { return young ? YOUNG : OLD; }
        @Override public MemoryType getType() { return MemoryType.HEAP; }
        @Override public boolean isValid() { return true; }
        private MemoryUsage usage(long used) {
            JamHeap heap = JamHeap.get();
            long capacity = (young ? heap.youngCapacity() : heap.oldCapacity()).rawValue();
            return new MemoryUsage(capacity, used, capacity, capacity);
        }
        @Override public MemoryUsage getUsage() { return usage(JamHeap.get().usedBytes(young)); }
        @Override public MemoryUsage getPeakUsage() { return usage(JamHeap.get().peakBytes(young)); }
        @Override public MemoryUsage getCollectionUsage() { return usage(JamHeap.get().collectionBytes(young)); }
        @Override public void resetPeakUsage() { JamHeap.get().resetPeak(young); }
        @Override public String[] getMemoryManagerNames() { return new String[]{MINOR, MAJOR}; }
        @Override public ObjectName getObjectName() { return Util.newObjectName(ManagementFactory.MEMORY_POOL_MXBEAN_DOMAIN_TYPE, getName()); }
        @Override public boolean isUsageThresholdSupported() { return false; }
        @Override public boolean isCollectionUsageThresholdSupported() { return false; }
        private static UnsupportedOperationException unsupported() { return new UnsupportedOperationException("Jam memory thresholds are not supported"); }
        @Override public long getUsageThreshold() { throw unsupported(); }
        @Override public void setUsageThreshold(long value) { throw unsupported(); }
        @Override public boolean isUsageThresholdExceeded() { throw unsupported(); }
        @Override public long getUsageThresholdCount() { throw unsupported(); }
        @Override public long getCollectionUsageThreshold() { throw unsupported(); }
        @Override public void setCollectionUsageThreshold(long value) { throw unsupported(); }
        @Override public boolean isCollectionUsageThresholdExceeded() { throw unsupported(); }
        @Override public long getCollectionUsageThresholdCount() { throw unsupported(); }
    }
}
