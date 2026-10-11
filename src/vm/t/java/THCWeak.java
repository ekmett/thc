// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

/** Test executor and collection controls around the public guest API. */
public final class THCWeak {
    static {
        System.loadLibrary("thc_jni");
    }

    private THCWeak() {}

    /** Register K -> (V, F), without a strong native root to any component. */
    public static long create(Object key, Object value, Object finalizer) {
        return thc.vm.Weak.create(key, value, (Runnable) finalizer);
    }

    /** Return the live value, or null when the association has died. */
    public static Object deref(long token) { return thc.vm.Weak.deref(token); }

    /** Claim one pending finalizer and write its token into tokenOut[0]. */
    public static Object take(long[] tokenOut) { return thc.vm.Weak.take(tokenOut); }

    /** Retire a registration and claim its finalizer at most once. */
    public static Object finalizeNow(long token) { return thc.vm.Weak.finalizeNow(token); }

    /** Release the running-finalizer root, including when execution threw. */
    public static void complete(long token) { thc.vm.Weak.complete(token); }

    /** Collect young; optionally promote its complete live set when old has room. */
    public static native void minor(boolean promote);

    /** Completed collection counters: 0 = minors, 1 = promotions, 2 = majors. */
    public static native long collections(int kind);

    /** Test executor. A Truffle runtime can execute returned guest closures itself. */
    public static int runFinalizers() {
        int count = 0;
        long[] token = new long[1];
        for (;;) {
            Object finalizer = take(token);
            if (finalizer == null) return count;
            try {
                ((Runnable) finalizer).run();
            } finally {
                complete(token[0]);
            }
            count++;
        }
    }

    public static void runFinalizer(long token) {
        Object finalizer = finalizeNow(token);
        if (finalizer == null) return;
        try {
            ((Runnable) finalizer).run();
        } finally {
            complete(token);
        }
    }
}
