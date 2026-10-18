// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

import thc.vm.Weak;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicReference;

public final class JNIWeakSmoke {
    static { System.loadLibrary("thc_weak_test"); }

    private static Runnable collection = System::gc;
    private static volatile Object temporaryRoot;
    private static int finalizers;
    private record Payload(long value) { }
    private record Pending(long handle, WeakReference<Object> reference, long token) { }

    private static native long create(Object value);
    private static native Object read(long handle);
    private static native boolean present(long handle);
    private static native Object retainAndCollect(long handle);
    private static native void delete(long handle);

    // Called from JNI while its newly acquired local reference is the only root.
    public static void collect() {
        temporaryRoot = null;
        collection.run();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void inThread(Runnable action) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try { action.run(); }
            catch (Throwable error) { failure.set(error); }
        });
        thread.start();
        thread.join();
        if (failure.get() != null) throw new AssertionError("JNI weak worker failed", failure.get());
    }

    private static long weakWithTemporaryRoot() throws InterruptedException {
        long[] handle = new long[1];
        inThread(() -> {
            temporaryRoot = new Payload(42);
            handle[0] = create(temporaryRoot);
        });
        return handle[0];
    }

    private static void live(Runnable[] collectors) {
        Object value = new Payload(73);
        int identity = System.identityHashCode(value);
        long handle = create(value);
        try {
            for (Runnable gc : collectors) {
                collection = gc;
                collect();
                Object after = read(handle);
                check(after == value && System.identityHashCode(after) == identity,
                      "JNI weak global follows a live referent through collection");
                check(((Payload) after).value() == 73, "relocated referent retains its payload");
                Reference.reachabilityFence(value);
            }
        } finally { delete(handle); }
    }

    private static void strongLocal() throws InterruptedException {
        collection = System::gc;
        long handle = weakWithTemporaryRoot();
        try {
            // JNI acquires its local before collect() drops the temporary root.
            inThread(() -> {
                Object value = retainAndCollect(handle);
                check(value instanceof Payload && ((Payload) value).value() == 42,
                      "NewLocalRef retains a weak referent across a callback that collects");
                Reference.reachabilityFence(value);
            });
            collect();
            check(read(handle) == null, "JNI weak global clears after its acquired local is released");
        } finally { delete(handle); }
    }

    private static void finalizerOrder() throws InterruptedException {
        Pending[] pending = new Pending[1];
        inThread(() -> {
            Object key = new Payload(101);
            long handle = create(key);
            long token = Weak.create(key, key, () -> {
                check(((Payload) key).value() == 101, "finalizer retains its captured key");
                finalizers++;
            });
            pending[0] = new Pending(handle, new WeakReference<>(key), token);
        });
        Pending state = pending[0];
        try {
            System.gc();
            check(Weak.deref(state.token()) == null && state.reference().get() == null,
                  "Java weak clearing precedes newly queued generalized finalizer retention");
            inThread(() -> {
                Object value = read(state.handle());
                check(value instanceof Payload && ((Payload) value).value() == 101,
                      "JNI weak global survives finalizer retention, like a phantom reference");
            });
            inThread(() -> check(Weak.pump() == 1, "one generalized finalizer is claimed and completed"));
            check(finalizers == 1, "generalized finalizer runs once");
            System.gc();
            check(read(state.handle()) == null, "JNI weak global clears after finalizer completion");
        } finally { delete(state.handle()); }
    }

    public static void oldReferent(Runnable promote, Runnable minor, Runnable major) throws InterruptedException {
        long[] handle = new long[1];
        inThread(() -> {
            Object value = new Payload(211);
            handle[0] = create(value);
            promote.run();
            Reference.reachabilityFence(value);
        });
        try {
            minor.run();
            check(present(handle[0]), "minor collection does not prove an old JNI weak referent dead");
            major.run();
            check(read(handle[0]) == null, "major collection clears an unreachable old JNI weak referent");
        } finally { delete(handle[0]); }
    }

    public static void run(Runnable... collectors) throws InterruptedException {
        Weak.checkAvailable();
        check(create(null) == 0 && read(0) == null, "null JNI handles remain null");
        delete(0);
        live(collectors);
        strongLocal();
        finalizerOrder();
        System.out.println("JNI weak globals passed: relocation, strong locals and finalizer ordering");
    }

    public static void main(String[] args) throws InterruptedException { run(System::gc); }
}
