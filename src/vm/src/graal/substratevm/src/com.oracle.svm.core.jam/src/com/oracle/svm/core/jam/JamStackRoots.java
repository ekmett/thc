/*
 * Copyright (c) 2013, 2026, Oracle and/or its affiliates. All rights reserved.
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

import org.graalvm.nativeimage.CurrentIsolate;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.word.Pointer;
import com.oracle.svm.core.c.NonmovableArray;
import com.oracle.svm.core.code.CodeInfo;
import com.oracle.svm.core.code.CodeInfoAccess;
import com.oracle.svm.core.code.CodeInfoTable;
import com.oracle.svm.core.code.RuntimeCodeInstallation;
import com.oracle.svm.core.code.RuntimeCodeInfoAccess;
import com.oracle.svm.core.config.ObjectLayout;
import com.oracle.svm.core.deopt.DeoptimizedFrame;
import com.oracle.svm.core.deopt.Deoptimizer;
import com.oracle.svm.core.heap.CodeReferenceMapDecoder;
import com.oracle.svm.core.heap.ObjectReferenceVisitor;
import com.oracle.svm.core.heap.ReferenceMapIndex;
import com.oracle.svm.core.stack.JavaFrame;
import com.oracle.svm.core.stack.JavaFrames;
import com.oracle.svm.core.stack.JavaStackWalk;
import com.oracle.svm.core.stack.JavaStackWalker;
import com.oracle.svm.core.thread.VMOperation;
import com.oracle.svm.core.thread.VMThreads;
import com.oracle.svm.shared.AlwaysInline;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.util.VMError;

/** SubstrateVM stack maps, including runtime-code tethers and derived references. */
final class JamStackRoots {
    @AlwaysInline("GC performance")
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    static void walk(ObjectReferenceVisitor visitor, Pointer currentThreadSp, boolean visitRuntimeCodeInfo) {
        /*
         * Walk the current thread (unlike all other threads, it does not have a usable frame
         * anchor).
         */
        JavaStackWalk walk = StackValue.get(JavaStackWalker.sizeOfJavaStackWalk());
        JavaStackWalker.initialize(walk, CurrentIsolate.getCurrentThread(), currentThreadSp);
        walkStack(CurrentIsolate.getCurrentThread(), walk, visitor, visitRuntimeCodeInfo);

        /*
         * Scan the stacks of all the threads. Other threads will be blocked at a safepoint (or in
         * native code) so they will each have a JavaFrameAnchor in their VMThread.
         */
        for (IsolateThread thread = VMThreads.firstThread(); thread.isNonNull(); thread = VMThreads.nextThread(thread)) {
            if (thread == CurrentIsolate.getCurrentThread()) {
                continue;
            }
            JavaStackWalker.initialize(walk, thread);
            walkStack(thread, walk, visitor, visitRuntimeCodeInfo);
        }
    }

    @AlwaysInline("GC performance")
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    private static void walkStack(IsolateThread thread, JavaStackWalk walk, ObjectReferenceVisitor visitor, boolean visitRuntimeCodeInfo) {
        assert VMOperation.isGCInProgress() : "This methods accesses a CodeInfo without a tether";

        while (JavaStackWalker.advance(walk, thread)) {
            JavaFrame frame = JavaStackWalker.getCurrentFrame(walk);
            VMError.guarantee(!JavaFrames.isUnknownFrame(frame), "GC must not encounter unknown frames");

            /* We are during a GC, so tethering of the CodeInfo is not necessary. */
            DeoptimizedFrame deoptFrame = Deoptimizer.checkEagerDeoptimized(frame);
            if (deoptFrame == null) {
                Pointer sp = frame.getSP();
                CodeInfo codeInfo = CodeInfoAccess.unsafeConvert(frame.getIPCodeInfo());

                if (JavaFrames.isInterpreterLeaveStub(frame) || JavaFrames.isInterpreterJNIDowncallStub(frame)) {
                    /* nothing to scan */
                } else {
                    NonmovableArray<Byte> referenceMapEncoding = CodeInfoAccess.getStackReferenceMapEncoding(codeInfo);
                    long referenceMapIndex = frame.getReferenceMapIndex();
                    if (referenceMapIndex == ReferenceMapIndex.NO_REFERENCE_MAP) {
                        throw CodeInfoTable.fatalErrorNoReferenceMap(sp, frame.getIP(), codeInfo);
                    }

                    CodeReferenceMapDecoder.walkOffsetsFromPointer(sp, referenceMapEncoding, referenceMapIndex, visitor, null);

                    if (RuntimeCodeInstallation.isEnabled() && visitRuntimeCodeInfo && !CodeInfoAccess.isAOTImageCode(codeInfo)) {
                        /*
                         * Runtime-installed code that is currently on the stack must be kept alive.
                         * So, we mark the tether as strongly reachable. The RuntimeCodeCacheWalker
                         * will handle all other object references later on.
                         */
                        RuntimeCodeInfoAccess.walkTether(codeInfo, visitor);
                    }
                }
            } else {
                // Side-alias pins preserve native addresses, but the managed frame reference can move.
                visitor.visitObjectReferences(frame.getSP(), true, ObjectLayout.singleton().getReferenceSize(), null, 1);
            }
        }
    }

}
