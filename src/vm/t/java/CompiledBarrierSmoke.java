// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

import java.lang.ref.Reference;
import java.util.concurrent.atomic.AtomicReference;

/** Collect across compiled atomics and initialization of oversized old arrays. */
public final class CompiledBarrierSmoke {
    private record Node(int id, Object child) {}
    private static volatile Object escaped;

    static boolean compare(AtomicReference<Object> owner, Object expected, Object value) {
        return owner.compareAndSet(expected, value);
    }

    static Object exchange(AtomicReference<Object> owner, Object value) {
        return owner.getAndSet(value);
    }

    static Object[] initialize(int size, Object value) {
        Object[] result = new Object[size];
        result[size / 2] = value;
        result[size - 1] = value;
        return result;
    }

    static Object[] duplicate(Object[] source) { return source.clone(); }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static boolean young(Object value) {
        // Jam's shift-three narrow oops use bit 31 to select the young arena.
        Object[] holder = { value };
        check(GenerationSmoke.U.arrayIndexScale(Object[].class) == 4, "compressed references");
        int reference = GenerationSmoke.U.getInt(holder, GenerationSmoke.U.arrayBaseOffset(Object[].class));
        Reference.reachabilityFence(holder);
        return reference < 0;
    }

    private static void warm() {
        AtomicReference<Object> owner = new AtomicReference<>();
        for (int i = 0; i < 20_000; ++i) {
            Object[] array = initialize(8, owner);
            escaped = duplicate(array);
            check(compare(owner, null, array), "warm CAS");
            check(exchange(owner, null) == array, "warm exchange");
        }
        escaped = null;
    }

    private static void install(AtomicReference<Object> cas, AtomicReference<Object> swap) {
        check(compare(cas, null, new Node(101, new Node(102, null))), "CAS succeeds");
        check(exchange(swap, new Node(103, new Node(104, null))) == null, "exchange succeeds");
        check(!compare(cas, null, new Node(-1, null)), "failed CAS preserves value");
    }

    private static Object[][] oldArrays() {
        // Run with an 8 MiB nursery: each array is too large to allocate there.
        int size = (8 * 1024 * 1024 / 4) + 16;
        Node value = new Node(201, new Node(202, null));
        check(young(value), "initialized value starts in young arena");
        Object[] initialized = initialize(size, value);
        check(!young(initialized), "oversized initialized array allocated directly in old arena");
        Node other = new Node(301, new Node(302, null));
        Object[] source = initialize(size, other);
        Object[] cloned = duplicate(source);
        check(!young(cloned), "oversized clone allocated directly in old arena");
        source[size / 2] = source[size - 1] = null;
        return new Object[][] { initialized, cloned };
    }

    private static void node(Object value, int id) {
        check(value instanceof Node, "reference remains a Node");
        Node node = (Node) value;
        check(node.id() == id && ((Node) node.child()).id() == id + 1, "graph survives movement");
    }

    public static void main(String[] args) {
        warm();
        AtomicReference<Object> cas = new AtomicReference<>(), swap = new AtomicReference<>();
        THCWeak.minor(true);
        check(!young(cas) && !young(swap), "atomic owners promoted");
        install(cas, swap);
        Object[][] arrays = oldArrays();
        long promotions = THCWeak.collections(1), majors = THCWeak.collections(2);
        long minors = THCWeak.collections(0);
        for (int i = 0; i < 4; ++i) {
            THCWeak.minor(false);
            node(cas.get(), 101);
            node(swap.get(), 103);
            for (int a = 0; a < arrays.length; ++a) {
                Object[] array = arrays[a];
                node(array[array.length / 2], 201 + 100 * a);
                check(array[array.length / 2] == array[array.length - 1], "array aliasing");
                check(young(array[array.length - 1]), "value remains in young arena");
            }
        }
        check(THCWeak.collections(0) == minors + 4, "four real minor collections");
        check(THCWeak.collections(1) == promotions && THCWeak.collections(2) == majors,
              "neither promotion nor full tracing hides missing barriers");
        Reference.reachabilityFence(cas);
        Reference.reachabilityFence(swap);
        Reference.reachabilityFence(arrays);
        System.out.println("CompiledBarrierSmoke passed: atomics, direct-old initialization and clone");
    }
}
