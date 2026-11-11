// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import org.graalvm.nativeimage.PinnedObject;
import org.graalvm.nativeimage.c.type.CCharPointer;

/** Closed pins reclaim normally while another pin remains open. */
public final class CapacitySmoke {
    private static final int CHUNK = 4 << 20;
    private static final int ATTEMPTS = 32;

    private static final class Pressure implements Runnable {
        volatile byte[] last;
        volatile Throwable failure;

        @Override public void run() {
            try {
                exhaust();
            } catch (Throwable problem) {
                failure = problem;
            }
        }

        private void exhaust() {
            for (int i = 0; i < ATTEMPTS; i++) {
                byte[] candidate = new byte[CHUNK];
                candidate[0] = 73;
                candidate[candidate.length - 1] = -91;
                try (PinnedObject pin = PinnedObject.create(candidate)) {
                    CCharPointer address = pin.addressOfArrayElement(0);
                    check(address.read(0) == 73 && address.read(candidate.length - 1) == -91,
                                    "New alias sees both ends of the array");
                }
                last = candidate;
                System.gc();
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static GarbageCollectorMXBean collector(String name) {
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean.getName().equals(name)) return bean;
        }
        throw new AssertionError("Missing collector: " + name);
    }

    private static void configuration() {
        check(Runtime.getRuntime().maxMemory() == 128L << 20, "Run with -Xmx128m");
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getName().equals("Jam young generation")) {
                check(pool.getUsage().getMax() == 32L << 20, "Run with -Xmn32m");
                return;
            }
        }
        throw new AssertionError("Missing Jam young generation");
    }

    public static void main(String[] args) throws Exception {
        configuration();
        GarbageCollectorMXBean major = collector("Jam major");
        Pressure pressure = new Pressure();
        Thread worker = new Thread(pressure, "jam-pin-capacity");
        worker.setDaemon(true);
        byte[] payload = new byte[1024];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 17 + 3);

        try (PinnedObject anchor = PinnedObject.create(payload)) {
            CCharPointer nativeAddress = anchor.addressOfArrayElement(0);
            long address = nativeAddress.rawValue();
            long beforeMajor = major.getCollectionCount();
            worker.start();
            // Keep the pin open throughout the join: waiting for this pin cannot make progress.
            worker.join(30_000);
            check(!worker.isAlive(), "Pin churn finishes while another pin is open");
            if (pressure.failure != null) throw new AssertionError("Capacity worker failed", pressure.failure);
            check(pressure.last != null, "Pin churn completed");
            check(major.getCollectionCount() > beforeMajor, "Majors run with the anchor pin open");
            check(anchor.addressOfArrayElement(0).rawValue() == address, "Pinned address remains stable");
            for (int i = 0; i < payload.length; i++) {
                byte expected = (byte) (i * 17 + 3);
                check(nativeAddress.read(i) == expected && payload[i] == expected, "Pinned payload remains intact");
            }
        }

        // The producer stack has exited, so old chunks have no accidental stack roots.
        long beforeMajor = major.getCollectionCount();
        System.gc();
        check(major.getCollectionCount() > beforeMajor, "Collection still works after closing the pin");
        byte[] retained = pressure.last;
        try (PinnedObject recovered = PinnedObject.create(retained)) {
            CCharPointer address = recovered.addressOfArrayElement(0);
            check(address.read(0) == 73 && address.read(retained.length - 1) == -91,
                    "Retained object can be pinned again");
        }
        // Larger than the nursery: recovery must make old capacity usable again.
        byte[] recovered = new byte[64 << 20];
        recovered[0] = 41;
        recovered[recovered.length - 1] = 67;
        System.gc();
        check(recovered[0] == 41 && recovered[recovered.length - 1] == 67,
                "Allocation and collection recover after unpinning");
        System.out.println("Jam Native Image pin capacity passed");
    }
}
