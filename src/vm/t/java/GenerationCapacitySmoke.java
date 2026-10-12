// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

import java.lang.ref.Reference;

/** Run with -Xms64m -Xmx64m -XX:JamYoungSize=32m -XX:JamPromoteEvery=1000. */
public final class GenerationCapacitySmoke {
    private static final class Node {
        final int id;
        final Node child;
        final byte[] payload;
        Node(int id, Node child, byte[] payload) {
            this.id = id; this.child = child; this.payload = payload;
        }
    }
    static boolean young(Object[] observer, Object value) {
        // The same shift-three generation bit used by CompiledBarrierSmoke.
        observer[0] = value;
        int reference = GenerationSmoke.U.getInt(observer, GenerationSmoke.U.arrayBaseOffset(Object[].class));
        observer[0] = null;
        return reference < 0;
    }
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
    static Node fillNursery(byte[][] retained, Object[] observer) {
        Node child = new Node(73, null, null);
        check(young(observer, child), "ordinary small allocation starts young");
        long minors = THCWeak.collections(0), promotions = THCWeak.collections(1);
        long majors = THCWeak.collections(2);
        for (int i = 0; i < retained.length; ++i) {
            byte[] payload = new byte[1024];
            payload[0] = (byte) i;
            Node node = new Node(i, child, payload);
            retained[i] = payload;
            check(THCWeak.collections(2) == majors, "old fallback preceded major collection");
            if (!young(observer, node)) {
                check(THCWeak.collections(0) > minors, "exhausted nursery collected before old fallback");
                check(THCWeak.collections(1) == promotions, "fill did not promote the nursery");
                check(young(observer, child), "initializing reference target remains young");
                return node;
            }
        }
        throw new AssertionError("bounded retained allocation did not reach old fallback");
    }
    static void allocationFallback() {
        // Initialize the existing generation observer before measuring collections.
        check(GenerationSmoke.U.arrayIndexScale(Object[].class) == 4, "compressed references");
        Object[] observer = new Object[1];
        new Node(0, null, null);
        System.gc();
        byte[][] old = graph(20);
        THCWeak.minor(true);
        // Bounds the fill by heap capacity, without assuming object header sizes.
        byte[][] retained = new byte[(int) (Runtime.getRuntime().totalMemory() / 1024)][];
        byte[][] continued = new byte[3][];
        Node owner = fillNursery(retained, observer);
        long minors = THCWeak.collections(0), promotions = THCWeak.collections(1);
        long majors = THCWeak.collections(2);
        // Retain six more MiB in old, including any unused reservation in the
        // returned old TLAB. Observe generations and collection progress
        // rather than assuming a particular ergonomic TLAB size.
        for (int i = 0; i < continued.length; ++i) {
            continued[i] = new byte[2 * 1024 * 1024];
            continued[i][0] = (byte) (90 + i);
            continued[i][continued[i].length - 1] = (byte) (100 + i);
            check(!young(observer, continued[i]), "continued allocation used old capacity");
            check(THCWeak.collections(0) == minors, "old capacity avoided repeated minor collection");
            check(THCWeak.collections(1) == promotions && THCWeak.collections(2) == majors,
                  "continued old allocation did not promote or run major");
        }
        // fillNursery's direct child root and discarded filler nodes have left
        // scope. The retained arrays contain bytes, so only the old owner's
        // initializing store keeps that young child alive across this minor.
        THCWeak.minor(false);
        check(owner.child.id == 73, "old initializing reference survived retaining minor");
        check(owner.payload[0] == (byte) owner.id, "old owner payload survived retaining minor");
        check(!young(observer, owner) && young(observer, owner.child), "retaining minor preserved generations");
        check(THCWeak.collections(1) == promotions && THCWeak.collections(2) == majors,
              "explicit retaining minor did not promote or run major");
        for (int i = 0; i < retained.length && retained[i] != null; ++i)
            check(retained[i][0] == (byte) i, "fallback fill payload survived");
        for (int i = 0; i < continued.length; ++i)
            check(continued[i][0] == (byte) (90 + i) &&
                  continued[i][continued[i].length - 1] == (byte) (100 + i), "continued old payload survived");
        verify(old, 20);
        Reference.reachabilityFence(continued);
        Reference.reachabilityFence(retained);
        Reference.reachabilityFence(old);
    }
    static void oversizedFailure() {
        boolean failed = false;
        try {
            // Aggregate capacity exceeds either fixed generation: no individual
            // reservation can satisfy this request, even after collection.
            byte[] impossible = new byte[(int) Runtime.getRuntime().totalMemory()];
            Reference.reachabilityFence(impossible);
        } catch (OutOfMemoryError expected) {
            failed = true;
        }
        check(failed, "oversized allocation retained final OutOfMemoryError");
        check(new byte[1024].length == 1024, "ordinary allocation remains possible after failed request");
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
        allocationFallback();
        System.gc();
        oversizedFailure();
        byte[][] young = combined();
        System.gc();
        long before = THCWeak.collections(1);
        THCWeak.minor(true);
        check(THCWeak.collections(1) == before + 1, "promotion succeeds after old garbage is reclaimed");
        verify(young, 80);
        System.out.println("GenerationCapacitySmoke passed: old TLAB fallback, initializing reference, capacity and failure recovery");
    }
}
