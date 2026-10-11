// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

import java.lang.ref.Reference;

/** Run with -Xms64m -Xmx64m -XX:JamYoungSize=32m. */
public final class GenerationCapacitySmoke {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static byte[][] graph(int tag) {
        byte[][] result = new byte[20][];
        for (int i = 0; i < result.length; ++i) {
            result[i] = new byte[1024 * 1024];
            result[i][0] = (byte) (tag + i);
            result[i][result[i].length - 1] = (byte) (tag - i);
        }
        return result;
    }
    static void verify(byte[][] graph, int tag) {
        for (int i = 0; i < graph.length; ++i)
            check(graph[i][0] == (byte) (tag + i) &&
                  graph[i][graph[i].length - 1] == (byte) (tag - i), "array payload survived movement");
    }
    static byte[][] combined() {
        byte[][] old = graph(40);
        THCWeak.minor(true);
        long promotions = THCWeak.collections(1);
        check(promotions > 0, "old graph promoted");
        byte[][] young = graph(80);
        System.gc();
        long live = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        check(live > 32L * 1024 * 1024, "combined live heap exceeds old capacity");
        THCWeak.minor(true);
        check(THCWeak.collections(1) == promotions, "non-fitting promotion retained nursery");
        verify(old, 40); verify(young, 80);
        Reference.reachabilityFence(old);
        return young;
    }
    public static void main(String[] args) {
        byte[][] young = combined();
        System.gc();
        long before = THCWeak.collections(1);
        THCWeak.minor(true);
        check(THCWeak.collections(1) == before + 1, "promotion succeeds after old garbage is reclaimed");
        verify(young, 80);
        System.out.println("GenerationCapacitySmoke passed: combined live > old capacity, failed promotion recovered");
    }
}
