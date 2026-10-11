// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

package thc.vm;

/**
 * Optional collector-selected retention of suspended guest owners.
 *
 * <p>The owner is both the reachability subject and the retained result. An armed
 * registration does not retain it during major classification; minor collections
 * conservatively retain every owner. A major selects all unreachable armed owners
 * against one closed reachability snapshot before tracing any selected owner.
 * Selected and claimed owners remain strong collector roots until completion.
 * These methods neither execute guest code nor resume or cancel an operation.
 *
 * <p>Load this class from one shared host class loader. Tickets belong to this
 * JVM (or Native Image isolate), are never reused, and are not security
 * capabilities. The caller's positive wait generation identifies its logical
 * operation independently of the ticket's registration generation. Every ticket
 * operation compares both. The caller maintains one outstanding registration
 * per suspended-owner transition and arbitrates completion using its own locks.
 *
 * <p>Publish the ticket, wait generation and an owner-free wake port in a
 * synchronized primitive inventory before releasing ordinary strong owner roots.
 * Keep the owner alive through publication, using a reachability fence when
 * necessary. A dormant carrier, its stack and its attachments must have no strong
 * path to the owner for major classification to select it. Dropping a ticket
 * does not disarm or release its registration. No progress is promised without
 * a major collection; none of these methods triggers collection.
 *
 * <p>Successful poll or disarm transfers the registration to one claimant while
 * retaining its root. Transfer the owner to an ordinary strong execution or
 * scheduler root, or deliberately finish terminal cancellation and cleanup,
 * before completing the claim. Scheduling failure must retain/retry ownership:
 * do not unconditionally complete in a finally block. A lost claimant leaks its
 * retained root until explicit recovery. After arm succeeds, publication or
 * capture rollback must likewise disarm and complete after restoring ownership
 * or finishing terminal cancellation.
 *
 * <p>This experimental optional capability requires the matching Jam-enabled
 * runtime and {@code libthc_bridge}. Missing candidate hooks raise
 * {@link UnsatisfiedLinkError} when used without preventing existing
 * {@link Weak} calls. Another collector raises
 * {@link UnsupportedOperationException}. Unsupported use never registers an owner.
 */
public final class Candidate {
    static {
        loadNativeLibrary();
    }

    private Candidate() {}

    @SuppressWarnings("restricted") // The host explicitly enables native access for this module.
    private static void loadNativeLibrary() {
        System.loadLibrary("thc_bridge");
    }

    /**
     * Publish one nonretaining armed registration while ordinary roots hold owner.
     * Rearming creates a fresh ticket even when the logical wait is unchanged.
     *
     * @param owner the nonnull owner of the captured execution and pending operation
     * @param waitGeneration the caller's positive logical operation generation
     * @return a positive registration ticket
     * @throws NullPointerException if owner is null
     * @throws IllegalArgumentException if waitGeneration is not positive
     * @throws OutOfMemoryError if metadata or ticket space is exhausted; no record
     *         is published and existing registrations remain unchanged
     */
    public static native long arm(Object owner, long waitGeneration);

    /**
     * Claim a selected owner once, returning an ordinary strong reference.
     * Armed owners are never retained merely by polling. The claimed root remains
     * until {@link #complete(long, long)}. Result-handoff failure leaves the
     * previous registration state and roots intact.
     *
     * @param ticket the registration ticket
     * @param waitGeneration the matching logical operation generation
     * @return the owner, or null if not selected, already claimed, or either
     *         generation is stale or invalid
     */
    public static native Object poll(long ticket, long waitGeneration);

    /**
     * Claim an armed or selected owner once, disabling future selection.
     * This deliberately reacquires ownership for normal completion, shutdown,
     * cancellation or rollback; it does not release the collector root.
     * Result-handoff failure leaves the previous state and roots intact.
     *
     * <p>A null result means this caller did not obtain the claim. It must not
     * discard an already committed operation: signal its result through the
     * normal owner protocol so the successful claimant can arbitrate it.
     *
     * @param ticket the registration ticket
     * @param waitGeneration the matching logical operation generation
     * @return the owner, or null if already claimed, retired, or either generation
     *         is stale or invalid
     */
    public static native Object disarm(long ticket, long waitGeneration);

    /**
     * Release a claimed root after strong ownership transfer or terminal cleanup.
     * Only the successful claimant may acknowledge its claim. Repetition and calls
     * for armed, selected, retired, stale or invalid registrations have no effect.
     *
     * @param ticket the ticket successfully claimed by poll or disarm
     * @param waitGeneration the matching logical operation generation
     */
    public static native void complete(long ticket, long waitGeneration);

    /**
     * Observe the selection sequence after selected references have been repaired.
     * It advances once for each published nonempty newly selected batch, saturating
     * at {@link Long#MAX_VALUE}. It neither classifies owners nor triggers GC.
     *
     * <p>A fresh service scans once. Subsequently snapshot this value before
     * scanning the synchronized inventory and record only that snapshot after a
     * successful scan. Never record a newer epoch loaded after scanning; selection
     * during the scan must force another pass. At saturation scan on every poll.
     * The acquire observation pairs with release publication but does not replace
     * inventory synchronization or the owner's operation lock.
     *
     * @return the nonnegative selection sequence for this JVM or isolate
     */
    public static native long epoch();
}
