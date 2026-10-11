/*
 * Copyright (c) 2019, 2019, Oracle and/or its affiliates. All rights reserved.
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

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.Pointer;
import org.graalvm.word.impl.Word;
import com.oracle.svm.core.code.CodeInfo;
import com.oracle.svm.core.code.CodeInfoAccess;
import com.oracle.svm.core.code.RuntimeCodeCache.CodeInfoVisitor;
import com.oracle.svm.core.code.RuntimeCodeInfoAccess;
import com.oracle.svm.core.code.UntetheredCodeInfoAccess;
import com.oracle.svm.core.heap.ObjectHeader;
import com.oracle.svm.core.heap.ReferenceAccess;
import com.oracle.svm.core.heap.RuntimeCodeCacheCleaner;
import com.oracle.svm.core.heap.RuntimeCodeInfoGCSupport;
import com.oracle.svm.core.heap.UninterruptibleObjectReferenceVisitor;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.guest.staging.SubstrateGCOptions;
import com.oracle.svm.shared.Uninterruptible;

/**
 * Jam liveness adaptation of RuntimeCodeCacheWalker and RuntimeCodeCacheReachabilityAnalyzer.
 * Stack tethers retain active code; inactive constants retain no dead language objects.
 */
final class JamCodeRoots extends RuntimeCodeInfoGCSupport implements CodeInfoVisitor {
    private final JamGC gc;
    private final Reachability reachability = new Reachability();
    private boolean unreachable;

    @Platforms(Platform.HOSTED_ONLY.class)
    JamCodeRoots(JamGC gc) { this.gc = gc; }

    @Override @Uninterruptible(reason = "All code metadata is enumerated in every collection.", callerMustBe = true)
    public void registerObjectFields(CodeInfo code) { }
    @Override @Uninterruptible(reason = "All code constants are enumerated in every collection.", callerMustBe = true)
    public void registerCodeConstants(CodeInfo code) { }
    @Override @Uninterruptible(reason = "All frame metadata is enumerated in every collection.", callerMustBe = true)
    public void registerFrameMetadata(CodeInfo code) { }
    @Override @Uninterruptible(reason = "All deoptimization metadata is enumerated in every collection.", callerMustBe = true)
    public void registerDeoptMetadata(CodeInfo code) { }

    @Override @Uninterruptible(reason = "Code roots are processed after all stack tethers.")
    public void visitCode(CodeInfo code) {
        if (RuntimeCodeInfoAccess.areAllObjectsOnImageHeap(code)) return;
        Object tether = UntetheredCodeInfoAccess.getTetherUnsafe(code);
        if (tether != null && !gc.marked(Word.objectToUntrackedPointer(tether))) {
            int state = CodeInfoAccess.getState(code);
            if (state == CodeInfo.STATE_REMOVED_FROM_CODE_CACHE) {
                RuntimeCodeInfoAccess.walkObjectFields(code, gc.rootVisitor());
                CodeInfoAccess.setState(code, CodeInfo.STATE_PENDING_FREE);
                return;
            }
            unreachable = false;
            if (state == CodeInfo.STATE_CODE_CONSTANTS_LIVE && SubstrateGCOptions.TreatRuntimeCodeInfoReferencesAsWeak.getValue()) {
                RuntimeCodeInfoAccess.walkWeakReferences(code, reachability);
            }
            if (state == CodeInfo.STATE_NON_ENTRANT || unreachable) {
                RuntimeCodeInfoAccess.walkObjectFields(code, gc.rootVisitor());
                CodeInfoAccess.setState(code, CodeInfo.STATE_PENDING_REMOVAL_FROM_CODE_CACHE);
                return;
            }
        }
        RuntimeCodeInfoAccess.walkStrongReferences(code, gc.rootVisitor());
        RuntimeCodeInfoAccess.walkWeakReferences(code, gc.rootVisitor());
    }

    private final class Reachability implements UninterruptibleObjectReferenceVisitor {
        @Override @Uninterruptible(reason = "Check weak code constants without retaining them.")
        public void visitObjectReferences(Pointer first, boolean compressed, int stride, Object holder, int count) {
            for (int i = 0; i < count; i++) {
                Pointer target = ReferenceAccess.singleton().readObjectAsUntrackedPointer(first.add(Word.unsigned(i).multiply(stride)), compressed);
                if (target.isNull() || gc.marked(target)) continue;
                Class<?> type = DynamicHub.toClass(ObjectHeader.readDynamicHubFromObject(target.toObject()));
                boolean assumed = false;
                for (Class<?> allowed : RuntimeCodeCacheCleaner.CLASSES_ASSUMED_REACHABLE) {
                    if (allowed.isAssignableFrom(type)) { assumed = true; break; }
                }
                if (!assumed) unreachable = true;
            }
        }
        @Override @Uninterruptible(reason = "Derived code references are checked by their bases.")
        public void visitDerivedReferenceBase(Pointer slot, boolean compressed, int stride, Object holder) { visitObjectReferences(slot, compressed, stride, holder, 1); }
        @Override @Uninterruptible(reason = "Interior addresses are not independently reachable objects.")
        public void visitDerivedReference(Pointer base, Pointer slot, boolean compressed, Object holder) { }
    }
}
