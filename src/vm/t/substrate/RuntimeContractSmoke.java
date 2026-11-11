// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.nativeimage.PinnedObject;

/** Concurrent Java use of a Jam native executable with a 128 MiB managed heap. */
public final class RuntimeContractSmoke {
    private static final long CAPACITY = 128L << 20;
    private static final int WORKERS = 3;
    private static final int ROUNDS = 4;
    private static final int ITERATIONS = 384;
    private static volatile Object sink;

    private static final class Node {
        final int value;
        final byte[] payload = new byte[257];
        final Object image = RuntimeContractSmoke.class;
        Node next;

        Node(int value) {
            this.value = value;
            payload[256] = (byte) value;
        }
    }

    private static final class Owner {
        Node field;
        final Node[] array = new Node[8192];
        final AtomicReference<Node> cas = new AtomicReference<>();
        final AtomicReference<Node> exchange = new AtomicReference<>();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static GarbageCollectorMXBean collector(String name) {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .filter(bean -> bean.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing collector: " + name));
    }

    private static MemoryPoolMXBean pool(String name) {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(bean -> bean.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing pool: " + name));
    }

    private static void usage(MemoryUsage usage, String name) {
        check(usage != null, name + " usage is available");
        check(usage.getUsed() >= 0 && usage.getUsed() <= usage.getCommitted(), name + " used bounds");
        check(usage.getCommitted() <= usage.getMax(), name + " committed bounds");
    }

    private static void accounting() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryPoolMXBean young = pool("Jam young generation");
        MemoryPoolMXBean old = pool("Jam old generation");
        usage(heap, "heap");
        usage(young.getUsage(), "young");
        usage(old.getUsage(), "old");
        usage(young.getPeakUsage(), "young peak");
        usage(old.getPeakUsage(), "old peak");
        usage(young.getCollectionUsage(), "young collection");
        usage(old.getCollectionUsage(), "old collection");
        check(heap.getMax() == CAPACITY && heap.getCommitted() == CAPACITY, "logical heap capacity is 128 MiB");
        check(young.getUsage().getMax() + old.getUsage().getMax() == CAPACITY, "pool capacities sum to heap capacity");
        Runtime runtime = Runtime.getRuntime();
        check(runtime.maxMemory() == CAPACITY && runtime.totalMemory() == CAPACITY, "Runtime reports managed capacity");
        long free = runtime.freeMemory();
        check(free >= 0 && free <= CAPACITY, "Runtime free memory bounds");
    }

    private static Node node(int value) {
        Node first = new Node(value);
        Node second = new Node(~value);
        first.next = second;
        second.next = first;
        return first;
    }

    private static void install(Owner owner, int value) {
        owner.field = node(value);
        owner.array[4096] = node(value + 1);
        Node[] source = {node(value + 2), node(value + 3), node(value + 4)};
        System.arraycopy(source, 0, owner.array, owner.array.length - source.length, source.length);
        check(owner.cas.compareAndSet(owner.cas.get(), node(value + 5)), "uncontended CAS succeeds");
        owner.exchange.getAndSet(node(value + 6));
    }

    private static void verifyNode(Node node, int value) {
        check(node != null && node.value == value && node.payload[256] == (byte) value, "stored node survives");
        check(node.image == RuntimeContractSmoke.class, "image reference survives");
        check(node.next != null && node.next.value == ~value && node.next.next == node, "stored cycle survives");
    }

    private static void verify(Owner owner, int value) {
        verifyNode(owner.field, value);
        verifyNode(owner.array[4096], value + 1);
        for (int i = 0; i < 3; i++) verifyNode(owner.array[8189 + i], value + 2 + i);
        verifyNode(owner.cas.get(), value + 5);
        verifyNode(owner.exchange.get(), value + 6);
    }

    private static int value(int round, int worker, int iteration) {
        return round * 100_000 + worker * 10_000 + iteration * 8;
    }

    private static void mutate(Owner owner, int round, int worker) {
        for (int i = 0; i < ITERATIONS; i++) {
            int value = value(round, worker, i);
            install(owner, value);
            sink = new byte[32 * 1024 + worker];
            verify(owner, value);
        }
    }

    private static long concurrentRound(Owner[] owners, int round, boolean pressure) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(WORKERS);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] threads = new Thread[WORKERS];
        for (int i = 0; i < WORKERS; i++) {
            int worker = i;
            threads[i] = new Thread(() -> {
                try {
                    ready.countDown();
                    start.await();
                    if (pressure) mutate(owners[worker], round, worker);
                    else install(owners[worker], value(round, worker, ITERATIONS - 1));
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                }
            }, "jam-mutator-" + i);
            threads[i].setDaemon(true);
            threads[i].start();
        }
        ready.await();
        // Drain thread-startup allocation before the measured stores.
        if (!pressure) System.gc();
        long before = collector("Jam minor").getCollectionCount();
        start.countDown();
        for (Thread thread : threads) {
            thread.join(30_000);
            check(!thread.isAlive(), "mutator finishes");
        }
        if (failure.get() != null) throw new AssertionError("Concurrent mutator failed", failure.get());
        // Producer stacks have exited: the new nodes are now reachable only through old owners.
        return before;
    }

    private static Reference<?>[] orphan(ReferenceQueue<Object> queue) {
        Object value = new byte[4096];
        return new Reference<?>[]{new WeakReference<>(value, queue), new PhantomReference<>(value, queue)};
    }

    public static void main(String[] args) throws Exception {
        GarbageCollectorMXBean minor = collector("Jam minor");
        GarbageCollectorMXBean major = collector("Jam major");
        Owner[] owners = new Owner[WORKERS];
        for (int i = 0; i < WORKERS; i++) owners[i] = new Owner();
        long minorStart = minor.getCollectionCount();
        long minorTime = minor.getCollectionTime();
        long majorTime = major.getCollectionTime();
        check(minorStart >= 0 && minorTime >= 0 && majorTime >= 0, "collection accounting is supported");

        // Keep a native alias open while verifying old-to-young barriers and major movement.
        try (PinnedObject pin = PinnedObject.create(owners)) {
            check(pin.getObject() == owners, "pin retains the owner graph");
            for (int round = 0; round < ROUNDS; round++) {
                long beforePressure = minor.getCollectionCount();
                concurrentRound(owners, round, true);
                check(minor.getCollectionCount() > beforePressure, "concurrent allocation triggers a minor");
                System.gc();
                // Refill from an empty nursery, then discard every producer stack before collection.
                long before = concurrentRound(owners, round, false);
                check(minor.getCollectionCount() == before, "final young stores have not already been promoted");
                long majorStart = major.getCollectionCount();
                for (int i = 0; minor.getCollectionCount() == before && i < 65536; i++) sink = new byte[4096];
                check(minor.getCollectionCount() > before, "allocation triggers a minor with a live pin");
                check(major.getCollectionCount() == majorStart, "barrier checks are not masked by a major");
                for (int worker = 0; worker < WORKERS; worker++) verify(owners[worker], value(round, worker, ITERATIONS - 1));
                accounting();
            }
        }

        ReferenceQueue<Object> queue = new ReferenceQueue<>();
        Reference<?>[] references = orphan(queue);
        long beforeMajor = major.getCollectionCount();
        System.gc();
        check(major.getCollectionCount() > beforeMajor, "explicit collection performs an actual major");
        check(references[0].get() == null, "unreachable weak referent clears");
        int seen = 0;
        for (int i = 0; i < references.length; i++) {
            Reference<?> queued = queue.remove(10_000);
            int bit = queued == references[0] ? 1 : queued == references[1] ? 2 : 0;
            check(bit != 0 && (seen & bit) == 0, "weak and phantom references each enqueue once");
            seen |= bit;
        }
        check(seen == 3, "both reference kinds reach their queue");
        for (int worker = 0; worker < WORKERS; worker++) verify(owners[worker], value(ROUNDS - 1, worker, ITERATIONS - 1));
        check(minor.getCollectionCount() >= minorStart + ROUNDS, "actual minors were counted");
        check(minor.getCollectionTime() >= minorTime && major.getCollectionTime() >= majorTime, "collection times are monotone");
        accounting();
        Reference.reachabilityFence(owners);
        Reference.reachabilityFence(references);
        System.out.println("Jam Native Image runtime contracts passed");
    }
}
