// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** One image exercises the collector through its ordinary application interfaces. */
public final class SubstrateSmoke {
    private static volatile Object sink;

    static void minorCollection() {
        java.lang.management.GarbageCollectorMXBean minor = null;
        java.lang.management.GarbageCollectorMXBean major = null;
        for (var bean : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean.getName().equals("Jam minor")) minor = bean;
            if (bean.getName().equals("Jam major")) major = bean;
        }
        if (minor == null || major == null) throw new AssertionError("Jam collectors missing");
        long before = minor.getCollectionCount(), majorBefore = major.getCollectionCount();
        for (int i = 0; minor.getCollectionCount() == before && i < 65536; i++) sink = new byte[4096];
        if (minor.getCollectionCount() == before || major.getCollectionCount() != majorBefore)
            throw new AssertionError("Expected allocation-triggered minor without major collection");
    }

    private static void continuations() throws Exception {
        CountDownLatch ready = new CountDownLatch(16);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] threads = new Thread[16];
        for (int i = 0; i < threads.length; i++) {
            int index = i;
            threads[i] = Thread.ofVirtual().start(() -> {
                try {
                    byte[] data = new byte[1024 + index];
                    data[index] = (byte) (index + 1);
                    Object[] refs = {data, new String("continuation-" + index)};
                    int identity = System.identityHashCode(refs);
                    ready.countDown();
                    resume.await();
                    if (data[index] != index + 1 || refs[0] != data ||
                                    !refs[1].equals("continuation-" + index) || System.identityHashCode(refs) != identity) {
                        throw new AssertionError("parked continuation roots survive movement");
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            });
        }
        if (!ready.await(30, TimeUnit.SECONDS)) throw new AssertionError("virtual threads did not reach their suspension point", failure.get());
        for (int round = 0; round < 8; round++) {
            for (int i = 0; i < 4000; i++) sink = new byte[2048];
            System.gc();
        }
        resume.countDown();
        for (Thread thread : threads) {
            thread.join(30_000);
            if (thread.isAlive()) throw new AssertionError("virtual thread did not resume");
        }
        if (failure.get() != null) throw new AssertionError("virtual thread failed", failure.get());
        System.out.println("Jam Native Image continuations passed");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("heap | weak | jni-weak | pin | runtime | continuations | isolates | capacity | image-roots | heap-walk");
        switch (args[0]) {
            case "image-roots" -> ImageRootsSmoke.main(new String[0]);
            case "heap-walk" -> HeapWalkSmoke.main(new String[0]);
            case "heap" -> HeapSmoke.main(new String[0]);
            case "weak" -> WeakBridgeSmoke.main(new String[0]);
            case "jni-weak" -> {
                JNIWeakSmoke.run(SubstrateSmoke::minorCollection, System::gc);
                JNIWeakSmoke.oldReferent(SubstrateSmoke::minorCollection, SubstrateSmoke::minorCollection, System::gc);
            }
            case "pin" -> NativePinSmoke.main(new String[0]);
            case "runtime" -> RuntimeContractSmoke.main(new String[0]);
            case "continuations" -> continuations();
            case "isolates" -> IsolateSmoke.main(new String[0]);
            case "capacity" -> CapacitySmoke.main(new String[0]);
            default -> throw new IllegalArgumentException(args[0]);
        }
        if (Boolean.getBoolean("jam.runtime.audit")) {
            System.out.println("jam-runtime-audit-ready");
            System.out.flush();
            if (System.in.read() != '\n') throw new AssertionError("runtime audit did not resume");
        }
    }
}
