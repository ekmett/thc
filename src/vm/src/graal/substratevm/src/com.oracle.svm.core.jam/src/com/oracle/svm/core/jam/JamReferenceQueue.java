/*
 * Copyright (c) 2013, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.jam;

import java.lang.ref.Reference;
import com.oracle.svm.core.heap.ReferenceHandlerThread;
import com.oracle.svm.core.heap.ReferenceInternals;
import com.oracle.svm.core.locks.VMMutex;
import com.oracle.svm.core.locks.VMCondition;
import com.oracle.svm.core.thread.PlatformThreads;
import com.oracle.svm.guest.staging.core.thread.ThreadStatus;
import com.oracle.svm.core.thread.VMThreads;
import com.oracle.svm.core.nodes.CFunctionPrologueNode;
import com.oracle.svm.core.nodes.CFunctionEpilogueNode;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.NeverInline;
import jdk.graal.compiler.api.replacements.Fold;
import jdk.graal.compiler.core.common.SuppressFBWarnings;

/** Pending Java references use SubstrateVM's native-state waiting protocol. */
final class JamReferenceQueue {
    private static final VMMutex REF_MUTEX = new VMMutex("jam references");
    private static final VMCondition REF_CONDITION = new VMCondition(REF_MUTEX, "jam references");
    private Reference<?> refPendingList;
    private volatile long refListOfferCounter;
    private volatile long refListWaiterWakeUpCounter;

    @Fold
    static JamReferenceQueue get() { return JamGC.get().referenceQueue; }

    @Uninterruptible(reason = "GC owns the heap and reference queue.")
    void add(Reference<?> reference) {
        REF_MUTEX.lockNoTransition();
        try {
            boolean notify = refPendingList == null;
            ReferenceInternals.setNextDiscovered(reference, refPendingList);
            refPendingList = reference;
            if (notify) {
                refListOfferCounter++;
                REF_CONDITION.broadcast();
            }
        } finally {
            REF_MUTEX.unlock();
        }
    }

    @Uninterruptible(reason = "Safepoint while holding the lock could lead to a deadlock in GC.")
    public boolean hasReferencePendingList() {
        REF_MUTEX.lockNoTransition();
        try {
            return hasReferencePendingListUnsafe();
        } finally {
            REF_MUTEX.unlock();
        }
    }

    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    boolean hasReferencePendingListUnsafe() {
        return refPendingList != null;
    }

    public void waitForReferencePendingList() throws InterruptedException {
        /*
         * This method is only execute by the reference handler thread. So, it does not cause any
         * harm if it wakes up too frequently. However, we must guarantee that it does not miss any
         * updates. As awaitPendingRefsInNative() can't directly use object references, we use the
         * offer count and the wakeup count to detect if this thread was interrupted or if a new
         * pending reference list was set in the meanwhile. To prevent transient issues, the
         * execution order is crucial:
         *
         * - Another thread could set a new reference pending list at any safepoint. As
         * awaitPendingRefsInNative() can only access the offer count, it is necessary to ensure
         * that the offer count is reasonably accurate. So, we read the offer count *before*
         * checking if there is already a pending reference list. If there is no pending reference
         * list, then the read offer count is accurate (there is no other thread that could clear
         * the pending reference list in the meanwhile).
         *
         * - Another thread could interrupt this thread at any time. Interrupting will first set the
         * interrupted flag and then increment the wakeup count. So, it is crucial that we do
         * everything in the reverse order here: we read the wakeup count *before* the call to
         * Thread.interrupted().
         */
        assert ReferenceHandlerThread.isReferenceHandlerThread();
        long initialOffers = refListOfferCounter;
        long initialWakeUps = refListWaiterWakeUpCounter;
        if (ReferenceHandlerThread.isStopping()) {
            // Must re-check because initialWakeUps can already include the corresponding wakeup.
            return;
        }
        if (hasReferencePendingList()) {
            return;
        }

        // Throw an InterruptedException if the thread is interrupted before or after waiting.
        if (Thread.interrupted() || (!waitForPendingReferenceList(initialOffers, initialWakeUps) && Thread.interrupted())) {
            throw new InterruptedException();
        }
    }

    private static boolean waitForPendingReferenceList(long initialOffers, long initialWakeUps) {
        Thread currentThread = Thread.currentThread();
        int oldThreadStatus = PlatformThreads.getThreadStatus(currentThread);
        PlatformThreads.setThreadStatus(currentThread, ThreadStatus.PARKED);
        try {
            return transitionToNativeThenAwaitPendingRefs(initialOffers, initialWakeUps);
        } finally {
            PlatformThreads.setThreadStatus(currentThread, oldThreadStatus);
        }
    }

    @NeverInline("Must not be inlined in a caller that has an exception handler: We only support InvokeNode and not InvokeWithExceptionNode between a CFunctionPrologueNode and CFunctionEpilogueNode")
    private static boolean transitionToNativeThenAwaitPendingRefs(long initialOffers, long initialWakeUps) {
        // Note that we cannot hold the lock going into or out of native because we could enter a
        // safepoint during the transition and would risk a deadlock with the VMOperation.
        CFunctionPrologueNode.cFunctionPrologue(VMThreads.StatusSupport.STATUS_IN_NATIVE);
        boolean offered = awaitPendingRefsInNative(initialOffers, initialWakeUps);
        CFunctionEpilogueNode.cFunctionEpilogue(VMThreads.StatusSupport.STATUS_IN_NATIVE);
        return offered;
    }

    @Uninterruptible(reason = "In native.")
    @NeverInline("Provide a return address for the Java frame anchor.")
    private static boolean awaitPendingRefsInNative(long initialOffers, long initialWakeUps) {
        /*
         * This method is executing in native state and must not deal with object references.
         * Therefore it has to be static and cannot access the `refPendingList` field either. We
         * work around this by indicating updates and interrupts via counter updates. We can safely
         * access those counters as fields of JamReferenceQueue as long as we can get the JamReferenceQueue instance
         * folded to its memory address so that the field accesses become direct memory reads.
         */
        REF_MUTEX.lockNoTransition();
        try {
            while (get().refListOfferCounter == initialOffers && get().refListWaiterWakeUpCounter == initialWakeUps) {
                REF_CONDITION.blockNoTransition();
            }
            return get().refListWaiterWakeUpCounter == initialWakeUps;
        } finally {
            REF_MUTEX.unlock();
        }
    }

    @Uninterruptible(reason = "Safepoint while holding the lock could lead to a deadlock in GC.")
    @SuppressFBWarnings(value = "VO_VOLATILE_INCREMENT", justification = "We use a lock when incrementing the volatile field 'refListWaiterWakeUpCounter'.")
    public void wakeUpReferencePendingListWaiters() {
        REF_MUTEX.lockNoTransition();
        try {
            refListWaiterWakeUpCounter++;
            REF_CONDITION.broadcast();
        } finally {
            REF_MUTEX.unlock();
        }
    }

    @Uninterruptible(reason = "Safepoint while holding the lock could lead to a deadlock in GC.")
    public Reference<?> getAndClearReferencePendingList() {
        REF_MUTEX.lockNoTransition();
        try {
            Reference<?> list = refPendingList;
            if (list != null) {
                refPendingList = null;
            }
            return list;
        } finally {
            REF_MUTEX.unlock();
        }
    }

}
