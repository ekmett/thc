// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import sun.misc.Unsafe;

/** Exercises exact-slot barriers and policy boundaries on the jam-backed JVM. */
public final class GenerationSmoke {
    static final Unsafe U;
    static final long LEFT;
    static final class Node {
        Object left, right;
        final int id;
        Node(int id) { this.id = id; }
    }
    static class Padding {
        int headerGap;
        long p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15;
        long p16, p17, p18, p19, p20, p21, p22, p23, p24, p25, p26, p27, p28, p29, p30, p31;
        long p32, p33, p34, p35, p36, p37, p38, p39, p40, p41, p42, p43, p44, p45, p46, p47;
        long p48, p49, p50, p51, p52, p53, p54, p55, p56, p57, p58, p59, p60, p61, p62, p63;
        long p64, p65, p66, p67, p68, p69, p70, p71, p72, p73, p74, p75, p76, p77, p78, p79;
        long p80, p81, p82, p83, p84, p85, p86, p87, p88, p89, p90, p91, p92, p93, p94, p95;
        long p96, p97, p98, p99, p100, p101, p102, p103, p104, p105, p106, p107, p108, p109, p110, p111;
        long p112, p113, p114, p115, p116, p117, p118, p119, p120, p121, p122, p123, p124, p125, p126, p127;
    }
    static final class Large extends Padding { Object tail; }
    static final class Capture implements Runnable {
        final Object captured;
        Capture(Object captured) { this.captured = captured; }
        public void run() { Reference.reachabilityFence(captured); }
    }
    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            U = (Unsafe) f.get(null);
            LEFT = U.objectFieldOffset(Node.class.getDeclaredField("left"));
            check(U.objectFieldOffset(Large.class.getDeclaredField("tail")) >= 1024,
                  "large-instance field must lie at least 1 KiB from the object start");
        } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static void store(Node owner, Object value) { owner.left = value; }
    static void arrayStore(Object[] owner, int index, Object value) { owner[index] = value; }
    static void unsafeStore(Node owner, Object value) { U.putObject(owner, LEFT, value); }
    static void largeStore(Large owner, Object value) { owner.tail = value; }
    static void copy(Object[] from, Object[] to) { System.arraycopy(from, 0, to, 2304, 4); }
    static void warm(Node owner, Large large, Object[] array, Object same) {
        for (int i = 0; i < 40_000; i++) {
            store(owner, same);
            arrayStore(array, i & (array.length - 1), same);
            unsafeStore(owner, same);
            largeStore(large, same);
            copy(array, array);
        }
    }
    static void install(Node ordinary, Node unsafe, Large large, Object[] array, AtomicReference<Object> atomic) {
        Node a = new Node(101), b = new Node(102), c = new Node(103), d = new Node(104);
        a.right = a; b.right = a;
        store(ordinary, a);
        unsafeStore(unsafe, b);
        arrayStore(array, 1536, c); // Source slot far from the old array's header.
        Object[] from = {d, a, b, c};
        copy(from, array);
        largeStore(large, new Node(106));
        atomic.set(new Node(105));
    }
    static void checkGraph(Node ordinary, Node unsafe, Large large, Object[] array, AtomicReference<Object> atomic) {
        Node a = (Node) ordinary.left, b = (Node) unsafe.left;
        check(a.id == 101 && a.right == a && b.id == 102 && b.right == a, "field/Unsafe/shared cycle");
        check(((Node) array[1536]).id == 103 && ((Node) array[2304]).id == 104, "array/arraycopy barriers");
        check(array[2305] == a && array[2306] == b && array[2307] == array[1536], "arraycopy aliasing");
        check(((Node) atomic.get()).id == 105, "volatile reference barrier");
        check(((Node) large.tail).id == 106, "remembered slot covers a distant field");
    }
    static void barriers() {
        Node ordinary = new Node(1), unsafe = new Node(2);
        Large large = new Large();
        Object[] array = new Object[4096];
        AtomicReference<Object> atomic = new AtomicReference<>();
        warm(ordinary, large, array, ordinary);
        JamWeak.minor(true);
        long promotions = JamWeak.collections(1);
        check(promotions > 0, "old owners established by promotion");
        install(ordinary, unsafe, large, array, atomic);
        long minors = JamWeak.collections(0);
        for (int n = 0; n < 5; n++) {
            JamWeak.minor(false);
            checkGraph(ordinary, unsafe, large, array, atomic);
        }
        check(JamWeak.collections(0) >= minors + 5, "five real minors");
        check(JamWeak.collections(1) == promotions, "retaining minors did not promote");
        // A major changes old offsets; its relocated remembered slots must survive another minor.
        System.gc();
        JamWeak.minor(false);
        checkGraph(ordinary, unsafe, large, array, atomic);
        JamWeak.minor(true);
        check(JamWeak.collections(1) > promotions, "requested promotion completed");
        checkGraph(ordinary, unsafe, large, array, atomic);
        Reference.reachabilityFence(ordinary);
        Reference.reachabilityFence(unsafe);
        Reference.reachabilityFence(array);
        Reference.reachabilityFence(atomic);
    }
    static long installAssociation(Object oldKey) {
        return JamWeak.create(oldKey, new Node(211), null);
    }
    static long oldKeyAssociation() {
        Object key = new Object();
        JamWeak.minor(true);
        long token = installAssociation(key);
        Reference.reachabilityFence(key);
        return token;
    }
    static void oldKeyPolicy() {
        long token = oldKeyAssociation();
        JamWeak.minor(false);
        check(((Node) JamWeak.deref(token)).id == 211, "untraced old key conservatively retains young value");
        System.gc();
        check(JamWeak.deref(token) == null, "major can reject dead old key");
    }
    static WeakReference<Object> newWeak() { return new WeakReference<>(null); }
    static void installWeakReferent(WeakReference<Object> ref) {
        // Reference.referent has no Java setter; Unsafe must emit its normal write barrier.
        try {
            long offset = U.objectFieldOffset(Reference.class.getDeclaredField("referent"));
            U.putObject(ref, offset, new Node(301));
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    static void oldJavaWeak() {
        WeakReference<Object> ref = newWeak();
        JamWeak.minor(true);
        installWeakReferent(ref);
        JamWeak.minor(false);
        check(ref.get() == null, "remembered old Java weak owner must not strengthen its young referent");
        Reference.reachabilityFence(ref);
    }
    static long[] frozenBatch() {
        Object oldKey = new Object();
        JamWeak.minor(true);
        Object youngKey = new Object();
        Object value = new Node(401);
        long live = JamWeak.create(oldKey, value, null);
        long first = JamWeak.create(youngKey, null, new Capture(youngKey));
        long second = JamWeak.create(youngKey, value, new Capture(null));
        Reference.reachabilityFence(oldKey);
        return new long[] {live, first, second};
    }
    static void finalizerBatch() {
        long[] tokens = frozenBatch();
        JamWeak.minor(false);
        check(JamWeak.deref(tokens[0]) != null, "old key activation in mixed batch");
        check(JamWeak.deref(tokens[2]) == null, "captured young key cannot reactivate retired peer");
        long[] id = new long[1];
        Object first = JamWeak.take(id);
        check(first instanceof Capture && id[0] == tokens[1], "first frozen finalizer");
        JamWeak.minor(true);
        System.gc();
        check(((Capture) first).captured != null, "running finalizer capture survives minor/promotion/major");
        JamWeak.complete(id[0]);
        Object second = JamWeak.take(id);
        check(second instanceof Capture && id[0] == tokens[2], "second frozen finalizer");
        JamWeak.complete(id[0]);
        check(JamWeak.take(id) == null && JamWeak.finalizeNow(tokens[1]) == null, "at most once after promotion");
    }
    public static void main(String[] args) {
        barriers(); oldJavaWeak(); oldKeyPolicy(); finalizerBatch();
        check(JamWeak.collections(2) > 0, "major collection exercised");
        System.out.printf("GenerationSmoke passed: minors=%d promotions=%d majors=%d%n",
            JamWeak.collections(0), JamWeak.collections(1), JamWeak.collections(2));
    }
}
