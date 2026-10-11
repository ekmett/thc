// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

import java.lang.management.ManagementFactory;
import java.util.Map;

/** The same JDK must expose distinct Jam and original Epsilon collectors. */
public final class CollectorIdentitySmoke {
    static void checkJVMCI() throws ReflectiveOperationException {
        Class<?> runtimeClass = Class.forName("jdk.vm.ci.hotspot.HotSpotJVMCIRuntime");
        Object runtime = runtimeClass.getMethod("runtime").invoke(null);
        Object store = runtimeClass.getMethod("getConfigStore").invoke(runtime);
        Map<?, ?> constants = (Map<?, ?>) store.getClass().getMethod("getConstants").invoke(store);
        if (!constants.containsKey("CollectedHeap::Jam"))
            throw new AssertionError("JVMCI is missing the Jam collector enum");
        Map<?, ?> flags = (Map<?, ?>) store.getClass().getMethod("getFlags").invoke(store);
        Object flag = flags.get("UseJamGC");
        if (flag == null || !Boolean.TRUE.equals(flag.getClass().getField("value").get(flag)))
            throw new AssertionError("JVMCI is missing the selected Jam flag");
        System.out.println("JVMCI runtime initialized with Jam identity");
    }
    static void checkAccounting(Runnable collect) {
        var bean = (com.sun.management.GarbageCollectorMXBean)
            ManagementFactory.getGarbageCollectorMXBeans().getFirst();
        long beforeCount = bean.getCollectionCount();
        long beforeTime = bean.getCollectionTime();
        long beforeNative = JamWeak.collections(0) + JamWeak.collections(2);
        collect.run();
        long nativeDelta = JamWeak.collections(0) + JamWeak.collections(2) - beforeNative;
        long count = bean.getCollectionCount();
        long elapsed = bean.getCollectionTime();
        if (nativeDelta <= 0 || count - beforeCount != nativeDelta)
            throw new AssertionError("GC bean count did not track completed collections: "
                + beforeCount + " -> " + count + ", native delta=" + nativeDelta);
        // Millisecond rounding permits a short collection to add zero time.
        if (beforeTime < 0 || elapsed < beforeTime)
            throw new AssertionError("GC bean time is unavailable or decreased");
        var info = bean.getLastGcInfo();
        if (info == null || info.getId() != count || info.getEndTime() < info.getStartTime()
                || !info.getMemoryUsageBeforeGc().containsKey("Jam Heap")
                || !info.getMemoryUsageAfterGc().containsKey("Jam Heap"))
            throw new AssertionError("GC bean is missing its completed collection record");
        System.out.println("Jam GC bean: count=" + count + ", time=" + elapsed + "ms");
    }

    public static void main(String[] args) throws ReflectiveOperationException {
        boolean jam = args.length >= 1 && args[0].equals("Jam");
        String expected = jam ? "Jam Heap" : "Epsilon Heap";
        var collectors = ManagementFactory.getGarbageCollectorMXBeans();
        if (collectors.size() != 1 || !collectors.getFirst().getName().equals(expected))
            throw new AssertionError("wrong collector identity: " + collectors.stream().map(c -> c.getName()).toList());
        long pools = ManagementFactory.getMemoryPoolMXBeans().stream()
            .filter(p -> p.getName().equals(expected)).count();
        if (pools != 1) throw new AssertionError("missing heap pool: " + expected);
        if (jam) {
            if (args.length > 1 && args[1].equals("JVMCI")) checkJVMCI();
            checkAccounting(System::gc);
            checkAccounting(() -> JamWeak.minor(false));
            checkAccounting(() -> JamWeak.minor(true));
        } else {
            try {
                JamWeak.create(new Object(), new Object(), null);
                throw new AssertionError("Epsilon accepted the Jam guest bridge");
            } catch (UnsupportedOperationException expectedFailure) {
                if (!expectedFailure.getMessage().contains("Jam collector")) throw expectedFailure;
            }
            System.gc(); // Ordinary Epsilon accepts the request without collecting.
        }
        System.out.println("CollectorIdentitySmoke passed: " + expected);
    }
}
