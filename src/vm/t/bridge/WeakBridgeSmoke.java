// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

import thc.vm.Weak;
import thc.vm.Lifted;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;

/** Exercise the packaged boundary without the test-only collector JNI library. */
public final class WeakBridgeSmoke {
    private static int executions;
    private static Object resurrected;
    private record Chain(Object root, long first, long second) { }
    private record Batch(long first, long second, WeakReference<Object> weak) { }
    private record GuestClosure(Object captured) implements Runnable {
        @Override public void run() {
            System.gc();
            check(captured != null, "captured guest object survives execution");
            executions++;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static long deadAssociation() {
        Object key = new Object();
        return Weak.create(key, new GuestClosure(key), new GuestClosure(key));
    }

    private static long install(Runnable finalizer) {
        return Weak.create(new Object(), new Object(), finalizer);
    }

    private static Chain chain() {
        Object first = new Object();
        Object second = new Object();
        long tail = Weak.create(second, new byte[4096], null);
        return new Chain(first, Weak.create(first, second, null), tail);
    }

    private static Batch batch(int[] count) {
        Object key = new Object();
        long first = Weak.create(key, new Object(), () -> {
            System.gc();
            resurrected = key;
            count[0]++;
        });
        long second = Weak.create(key, new Object(), () -> count[0]++);
        return new Batch(first, second, new WeakReference<>(key));
    }

    private static void generalized() {
        Chain chain = chain();
        for (int i = 0; i < 4; i++) {
            System.gc();
            check(Weak.deref(chain.first()) != null && Weak.deref(chain.second()) instanceof byte[],
                  "reverse registration order reaches a fixed point");
        }
        Reference.reachabilityFence(chain.root());
        int[] count = new int[1];
        Batch batch = batch(count);
        System.gc();
        check(Weak.deref(batch.first()) == null && Weak.deref(batch.second()) == null,
              "all dead associations freeze before tracing new finalizers");
        check(batch.weak().get() == null, "Java weak clears before generalized finalizer resurrection");
        check(Weak.pump() == 2 && count[0] == 2 && resurrected != null, "shared-key finalizers survive nested GC");
        System.gc();
        check(Weak.deref(batch.first()) == null && Weak.deref(batch.second()) == null && Weak.pump() == 0,
              "resurrection cannot rearm either association");
        resurrected = null;
        long permanent = Weak.create(WeakBridgeSmoke.class, new byte[4096], null);
        System.gc();
        check(Weak.deref(permanent) instanceof byte[], "permanent image key retains a moving value");
        Weak.finalizeNow(permanent);
        Weak.complete(permanent);
    }

    // A tiny language fixture: publication and terminal-state policy belong here.
    private static final class Thunk implements Lifted {
        volatile Lifted answer;
        @Override public Lifted resolve() { return answer; }
        @Override public Lifted project(int field) {
            Lifted value = answer;
            return value != null ? value.project(field) : null;
        }
    }

    private record Product(Lifted field) implements Lifted {
        @Override public Lifted resolve() { return null; }
        @Override public Lifted project(int index) { return index == 0 ? field : null; }
    }

    // The language's I# constructor stores int directly, not a stock Integer box.
    private record Int(int value) implements Lifted {
        @Override public Lifted resolve() { return null; }
        @Override public Lifted project(int index) { return null; }
    }

    private static void liftedAPI() {
        Thunk thunk = new Thunk();
        check(thunk.resolve() == null && thunk.project(0) == null, "unresolved means unavailable");
        Lifted value = new Int(42);
        thunk.answer = new Product(value);
        check(thunk.project(0) == value, "projection preserves an existing lifted reference");
        thunk.answer = value;
        Lifted slot = thunk;
        slot = slot.resolve();
        check(slot == value && ((Int) slot).value() == 42, "terminal replacement fits the lifted slot");
        check(slot.project(0) == null, "primitive payload is not boxed by projection");
    }

    private static long bootstrap(Lifted answer, int[] calls, long[] handle) {
        byte[] backing = new byte[4096];
        backing[0] = 42;
        Thunk thunk = new Thunk();
        long token = Weak.create(thunk, backing, () -> {
            System.gc();
            Lifted target = thunk.resolve();
            check(target != null, "queued bootstrap retains the published answer");
            handle[0] = Weak.create(target, backing, () -> calls[0]++);
            System.gc();
            check(Weak.deref(handle[0]) == backing, "replacement retains backing through nested GC");
            Reference.reachabilityFence(target);
        });
        thunk.answer = answer;
        return token;
    }

    private static void liftedHandoff() {
        Lifted answer = new Int(42);
        int[] calls = {0};
        long[] handle = {0}; // Stable control has no strong path to either conditional object.
        long old = bootstrap(answer, calls, handle);
        System.gc();
        check(Weak.deref(old) == null, "dead thunk retires its bootstrap registration");
        System.gc(); // Another collection while the bootstrap is queued, before take().
        check(Weak.pump() == 1 && calls[0] == 0, "handoff does not run real finalizer");
        check(handle[0] > 0 && handle[0] != old, "handoff installs a fresh opaque token");
        System.gc();
        check(Weak.deref(handle[0]) instanceof byte[] payload && payload.length == 4096 && payload[0] == 42,
              "independently live answer retains replacement value");
        Runnable finalizer = Weak.finalizeNow(handle[0]);
        check(finalizer != null, "replacement can be explicitly finalized");
        try { finalizer.run(); } finally { Weak.complete(handle[0]); }
        check(calls[0] == 1 && Weak.finalizeNow(handle[0]) == null && Weak.pump() == 0,
              "real finalizer is claimed once after handoff");
        Reference.reachabilityFence(answer);
    }

    public static void main(String[] args) throws Exception {
        liftedAPI();
        if (args.length != 0) {
            try {
                Weak.checkAvailable();
                throw new AssertionError("unsupported runtime admitted");
            } catch (UnsatisfiedLinkError | UnsupportedOperationException expected) {
                System.out.println("Weak bridge unavailable as expected: " + expected.getMessage());
                return;
            }
        }
        Weak.checkAvailable();
        Object key = new Object();
        Object value = new Object();
        GuestClosure finalizer = new GuestClosure(key);
        long live = Weak.create(key, value, finalizer);
        System.gc();
        check(Weak.deref(live) == value, "live key retains value");
        check(Weak.finalizeNow(live) == finalizer, "opaque closure returned");
        try {
            System.gc();
            check(Weak.deref(live) == null, "explicit retirement is irreversible");
            check(Weak.finalizeNow(live) == null, "running closure cannot be claimed twice");
        } finally {
            Weak.complete(live);
        }
        Weak.complete(live);
        Reference.reachabilityFence(key);

        long dead = deadAssociation();
        System.gc();
        check(Weak.deref(dead) == null, "value and finalizer cannot activate their own key");
        long[] token = { -1 };
        Object claimed = Weak.take(token);
        check(claimed instanceof GuestClosure && token[0] == dead, "queued guest closure claimed");
        try {
            System.gc();
            check(((GuestClosure) claimed).captured() != null, "claimed closure survives nested collection");
            check(Weak.finalizeNow(dead) == null, "explicit and queued claims share state");
        } finally {
            Weak.complete(token[0]);
        }
        check(Weak.take(token) == null && token[0] == 0, "empty poll resets token");
        check(Weak.deref(0) == null && Weak.finalizeNow(-1) == null, "unknown tokens do not alias");
        Weak.complete(0);
        try {
            Weak.create(null, value, null);
            throw new AssertionError("null key accepted");
        } catch (NullPointerException expected) {}
        try {
            Weak.take(new long[0]);
            throw new AssertionError("empty output accepted");
        } catch (IllegalArgumentException expected) {}
        long pumped = deadAssociation();
        System.gc();
        check(Weak.pump() == 1 && executions == 1, "any host can pump a JVM runnable");
        check(Weak.finalizeNow(pumped) == null && Weak.pump() == 0, "pump completes its claim once");
        int[] independentOwners = new int[2];
        install(() -> independentOwners[0]++);
        install(() -> independentOwners[1]++);
        System.gc();
        check(Weak.pump() == 2 && independentOwners[0] == 1 && independentOwners[1] == 1,
              "one pump runs callbacks from independent owners");
        RuntimeException failure = new RuntimeException("guest failure");
        long throwing = install(() -> { throw failure; });
        System.gc();
        try {
            Weak.pump();
            throw new AssertionError("guest failure swallowed");
        } catch (RuntimeException expected) {
            check(expected == failure, "guest failure propagated");
        }
        check(Weak.finalizeNow(throwing) == null && Weak.pump() == 0,
              "throwing finalizer is completed without retry");
        // Retired tokens never alias new registrations, even through metadata churn.
        long previous = throwing;
        for (int i = 0; i < 512; i++) {
            long current = Weak.create(key, value, null);
            check(current > 0 && current != previous, "weak tokens are never reused");
            Weak.finalizeNow(current);
            Weak.complete(current);
            check(Weak.deref(previous) == null && Weak.deref(current) == null,
                  "retired metadata stays unreachable after new registrations");
            previous = current;
        }
        System.gc();
        check(Weak.deref(previous) == null && Weak.pump() == 0, "retired entries do not return after GC");
        Reference.reachabilityFence(key);
        Reference.reachabilityFence(value);
        generalized();
        liftedHandoff();
        System.out.println("Weak bridge passed: JVM runnables, retirement, pumping and nested GC");
        if (Boolean.getBoolean("thc.runtime.audit")) {
            System.clearProperty("thc.runtime.audit");
            System.out.println("thc-runtime-audit-ready");
            System.out.flush();
            if (System.in.read() != '\n') throw new AssertionError("runtime audit did not resume");
        }
    }
}
