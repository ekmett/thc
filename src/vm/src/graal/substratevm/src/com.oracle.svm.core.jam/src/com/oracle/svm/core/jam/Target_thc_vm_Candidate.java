// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
package com.oracle.svm.core.jam;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.shared.Uninterruptible;

/** Candidate registrations and their selection epoch belong to the current isolate. */
@TargetClass(className = "thc.vm.Candidate", onlyWith = {JamGC.Enabled.class, THCWeakPresent.class})
final class Target_thc_vm_Candidate {
    @Substitute private static void loadNativeLibrary() { }

    @Substitute
    public static long arm(Object owner, long waitGeneration) {
        if (owner == null) throw new NullPointerException("candidate owner");
        if (waitGeneration <= 0) throw new IllegalArgumentException("waitGeneration must be positive");
        long ticket = JamCandidateSupport.arm(owner, waitGeneration);
        if (ticket == 0) throw new OutOfMemoryError("Jam candidate registration metadata exhausted");
        return ticket;
    }

    @Substitute
    @Uninterruptible(reason = "Materialize the claimed owner as a strong Java result before a safepoint.")
    public static Object poll(long ticket, long waitGeneration) {
        JamHeap heap = JamHeap.get();
        heap.lock().lockNoTransition();
        try { return heap.decode(JamNative.candidatePoll(heap.nativeHeap(), ticket, waitGeneration)); }
        finally { heap.lock().unlock(); }
    }

    @Substitute
    @Uninterruptible(reason = "Materialize the reacquired owner as a strong Java result before a safepoint.")
    public static Object disarm(long ticket, long waitGeneration) {
        JamHeap heap = JamHeap.get();
        heap.lock().lockNoTransition();
        try { return heap.decode(JamNative.candidateDisarm(heap.nativeHeap(), ticket, waitGeneration)); }
        finally { heap.lock().unlock(); }
    }

    @Substitute
    @Uninterruptible(reason = "Release the claimed registry root under the heap lock.")
    public static void complete(long ticket, long waitGeneration) {
        JamHeap heap = JamHeap.get();
        heap.lock().lockNoTransition();
        try { JamNative.candidateComplete(heap.nativeHeap(), ticket, waitGeneration); }
        finally { heap.lock().unlock(); }
    }

    @Substitute
    @Uninterruptible(reason = "Acquire the native selection epoch for the current isolate.")
    public static long epoch() {
        return JamNative.candidateEpoch(JamHeap.get().nativeHeap());
    }
}

final class JamCandidateSupport {
    @Uninterruptible(reason = "Encode and publish the owner under the heap lock before a safepoint.")
    static long arm(Object owner, long waitGeneration) {
        JamHeap heap = JamHeap.get();
        heap.lock().lockNoTransition();
        try { return JamNative.candidateArm(heap.nativeHeap(), heap.encode(owner), waitGeneration); }
        finally { heap.lock().unlock(); }
    }
}
