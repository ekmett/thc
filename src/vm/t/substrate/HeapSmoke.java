// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.nativeimage.PinnedObject;

/** Allocation, movement, barriers, identity and pinning in a native executable. */
public final class HeapSmoke {
    private static volatile Object sink;
    private static final Object imageKey = HeapSmoke.class;
    private static final AtomicReference<Object> root = new AtomicReference<>();
    private record Node(Node next, long value, Object image) { }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static WeakReference<Object> orphan(ReferenceQueue<Object> queue) {
        Object value = new byte[4096];
        return new WeakReference<>(value, queue);
    }

    public static void main(String[] args) throws Exception {
        Node list = null;
        for (int i = 0; i < 10000; i++) list = new Node(list, i, imageKey);
        int identity = System.identityHashCode(list);
        root.set(list);
        for (int round = 0; round < 8; round++) {
            for (int i = 0; i < 6000; i++) sink = new byte[1024 + (i & 127)];
            root.compareAndSet(root.get(), list);
            System.gc();
            check(System.identityHashCode(list) == identity, "identity hash survives movement");
            Node cursor = list;
            for (int i = 9999; i >= 0; i--, cursor = cursor.next()) {
                check(cursor.value() == i && cursor.image() == imageKey, "mixed image and collected references");
            }
            check(cursor == null, "list ends");
        }
        byte[] pinned = new byte[4096];
        try (PinnedObject pin = PinnedObject.create(pinned)) {
            long address = pin.addressOfArrayElement(0).rawValue();
            for (int round = 0; round < 8; round++) {
                pinned[round] = (byte) (round + 1);
                for (int i = 0; i < 6000; i++) sink = new byte[1024];
                System.gc();
                check(pin.addressOfArrayElement(0).rawValue() == address, "pin address stable across allocation and GC");
                for (int i = 0; i <= round; i++) check(pinned[i] == i + 1, "pinned payload survives");
            }
        }
        ReferenceQueue<Object> queue = new ReferenceQueue<>();
        WeakReference<Object> weak = orphan(queue);
        System.gc();
        check(weak.get() == null, "Java weak referent cleared");
        check(queue.remove(10000) == weak, "Java reference handler enqueues");
        Reference.reachabilityFence(list);
        Reference.reachabilityFence(pinned);
        System.out.println("Jam Native Image heap passed");
    }
}
