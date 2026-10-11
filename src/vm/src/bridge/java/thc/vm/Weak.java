// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

package thc.vm;

/**
 * Collector-aware generalized weak associations for guest runtimes.
 *
 * <p>Load this class from one shared host class loader. Tokens belong to this
 * JVM, are never reused, and are not security capabilities. A guest weak-handle
 * wrapper should retain only its token, not the key, value or finalizer.
 * Dropping the token does not cancel finalization.
 *
 * <p>Finalizers are JVM runnables. A runnable can enter whatever guest context
 * it needs; contexts are invisible to this API. Any host caller can
 * {@link #pump()} the JVM-wide queue. Low-level claims must be paired with
 * {@link #complete(long)}, including when execution fails. Pending and running
 * finalizers remain roots across nested collections.
 *
 * <p>This experimental interface requires the matching Jam-enabled JDK and
 * {@code libthc_bridge}. There is currently no queue notification or blocking
 * wait operation. The host decides when to pump.
 */
public final class Weak {
    static {
        loadNativeLibrary();
    }

    private Weak() {}

    @SuppressWarnings("restricted") // The host explicitly enables native access for this module.
    private static void loadNativeLibrary() {
        System.loadLibrary("thc_bridge");
    }

    /**
     * Check that the VM exports the hooks and Jam is the selected collector.
     * Does not collect or register an association.
     *
     * @throws UnsatisfiedLinkError if the native library or VM hooks are missing
     * @throws UnsupportedOperationException if another collector is selected
     */
    public static native void checkAvailable();

    /**
     * Register {@code key ⇒ (value, finalizer)} without strong JNI roots.
     * A live key retains the value and finalizer. Their own return edges to the
     * key cannot activate this association. No finalizer ordering is promised.
     *
     * @param key the nonnull weak key
     * @param value the conditional value; box null if dereference must distinguish
     *              a live null value from a dead association
     * @param finalizer code to run on the JVM, or null for no finalizer
     * @return a positive JVM-local token
     * @throws NullPointerException if key is null
     * @throws OutOfMemoryError if registration metadata or token space is exhausted;
     *         no association is installed and existing registrations are unchanged
     */
    public static native long create(Object key, Object value, Runnable finalizer);

    /**
     * Return the active value as an ordinary strong Java reference.
     *
     * @param token a registration token
     * @return the value, or null for a retired/unknown token or a live null value
     */
    public static native Object deref(long token);

    /**
     * Claim one pending finalizer without blocking. Competes atomically with
     * other polls and explicit finalization. The finalizer stays rooted until
     * completion; losing the returned object does not release that root.
     *
     * @param tokenOut an array with at least one element; receives the claimed
     *                 token, or zero when the queue is empty
     * @return the runnable, or null when no pending finalizer exists
     * @throws NullPointerException if tokenOut is null
     * @throws IllegalArgumentException if tokenOut has no elements
     */
    public static native Runnable take(long[] tokenOut);

    /**
     * Retire an active or queued association and claim its finalizer at most once.
     * This does not execute the finalizer. Retirement permanently clears deref,
     * even if guest execution later resurrects the key or throws.
     *
     * @param token a registration token
     * @return the claimed closure, or null if absent, already claimed or unknown
     */
    public static native Runnable finalizeNow(long token);

    /**
     * Run pending finalizers on the calling thread until a poll finds none.
     * Each runnable is invoked outside the GC safepoint and completed in a
     * finally block. Multiple or nested pumps may compete safely for claims;
     * they need not execute finalizers in registration order.
     *
     * <p>If a runnable throws, its claim is still completed and the exception
     * propagates to the caller. Remaining finalizers stay queued for a later
     * pump. This method does not trigger collection or wait for future work.
     *
     * @return the number of finalizers run before the queue was observed empty
     */
    public static int pump() {
        int count = 0;
        long[] token = new long[1];
        for (;;) {
            Runnable finalizer = take(token);
            if (finalizer == null) return count;
            try {
                finalizer.run();
            } finally {
                complete(token[0]);
            }
            count++;
        }
    }

    /**
     * Release a claimed finalizer's collector root after execution or deliberate
     * abandonment. Call in a finally block, or after asynchronous execution has
     * actually finished. Repeated calls and nonrunning tokens are harmless.
     *
     * @param token the token obtained from take or passed to finalizeNow
     */
    public static native void complete(long token);
}
