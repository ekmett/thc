// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
package com.oracle.svm.core.jam;

import org.graalvm.word.Pointer;
import org.graalvm.word.impl.Word;
import com.oracle.svm.core.heap.ObjectVisitor;
import com.oracle.svm.core.hub.LayoutEncoding;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.BuildPhaseProvider.AfterHeapLayout;
import com.oracle.svm.guest.staging.core.heap.UnknownObjectField;
import com.oracle.svm.guest.staging.core.heap.UnknownPrimitiveField;
import com.oracle.svm.shared.util.VMError;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.AllAccess;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.DisallowLayered;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;

@SingletonTraits(access = AllAccess.class, layeredCallbacks = NoLayeredCallbacks.class, other = DisallowLayered.class)
final class JamImageHeapInfo {
    static final int PARTITIONS = 4;
    @UnknownObjectField(availability = AfterHeapLayout.class, canBeNull = true) private Object firstReadOnly;
    @UnknownObjectField(availability = AfterHeapLayout.class, canBeNull = true) private Object lastReadOnly;
    @UnknownObjectField(availability = AfterHeapLayout.class, canBeNull = true) private Object firstRelocatable;
    @UnknownObjectField(availability = AfterHeapLayout.class, canBeNull = true) private Object lastRelocatable;
    @UnknownObjectField(availability = AfterHeapLayout.class, canBeNull = true) private Object firstPatched;
    @UnknownObjectField(availability = AfterHeapLayout.class, canBeNull = true) private Object lastPatched;
    @UnknownObjectField(availability = AfterHeapLayout.class, canBeNull = true) private Object firstWritable;
    @UnknownObjectField(availability = AfterHeapLayout.class, canBeNull = true) private Object lastWritable;
    @UnknownPrimitiveField(availability = AfterHeapLayout.class) int classCount;

    void setBounds(int partition, Object first, Object last) {
        switch (partition) {
            case 0 -> { firstReadOnly = first; lastReadOnly = last; }
            case 1 -> { firstRelocatable = first; lastRelocatable = last; }
            case 2 -> { firstPatched = first; lastPatched = last; }
            case 3 -> { firstWritable = first; lastWritable = last; }
            default -> throw VMError.shouldNotReachHere("Invalid Jam image partition");
        }
    }

    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    Object first(int partition) {
        return switch (partition) {
            case 0 -> firstReadOnly;
            case 1 -> firstRelocatable;
            case 2 -> firstPatched;
            case 3 -> firstWritable;
            default -> throw VMError.shouldNotReachHere("Invalid Jam image partition");
        };
    }

    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    Object last(int partition) {
        return switch (partition) {
            case 0 -> lastReadOnly;
            case 1 -> lastRelocatable;
            case 2 -> lastPatched;
            case 3 -> lastWritable;
            default -> throw VMError.shouldNotReachHere("Invalid Jam image partition");
        };
    }

    @Uninterruptible(reason = "Walk permanent image objects at a safepoint.", calleeMustBe = false)
    boolean walk(ObjectVisitor visitor, boolean writableOnly) {
        for (int i = writableOnly ? 2 : 0; i < PARTITIONS; i++) {
            if (first(i) == null) { continue; }
            Pointer current = Word.objectToUntrackedPointer(first(i));
            Pointer end = Word.objectToUntrackedPointer(last(i));
            while (current.belowOrEqual(end)) {
                Object object = current.toObjectNonNull();
                visitor.visitObject(object);
                current = current.add(LayoutEncoding.getSizeFromObjectInGC(object));
            }
        }
        return true;
    }
}
