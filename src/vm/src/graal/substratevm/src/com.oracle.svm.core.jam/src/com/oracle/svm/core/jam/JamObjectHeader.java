// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
package com.oracle.svm.core.jam;

import org.graalvm.word.Pointer;
import org.graalvm.word.impl.ObjectAccess;
import org.graalvm.word.impl.Word;

import com.oracle.svm.core.config.ObjectLayout;
import com.oracle.svm.core.heap.ObjectHeader;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.image.ImageHeapObject;
import com.oracle.svm.guest.staging.core.graal.KnownIntrinsics;
import com.oracle.svm.shared.AlwaysInline;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.util.VMError;

/** Narrow hub followed by a permanent identity-hash field; Jam owns forwarding metadata. */
public final class JamObjectHeader extends ObjectHeader {
    @Override
    public int getReservedHubBitsMask() {
        return 0;
    }

    @Override
    public long encodeHubPointerForImageHeap(ImageHeapObject object, long offset) {
        verifyDynamicHubOffset(offset);
        return offset >>> 3;
    }

    @Override
    public long encodeAsTLABObjectHeader(long offset) {
        verifyDynamicHubOffset(offset);
        return offset >>> 3;
    }

    @Override
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public Word encodeAsTLABObjectHeader(DynamicHub hub) {
        return Word.unsigned(Word.objectToUntrackedPointer(hub).subtract(KnownIntrinsics.heapBase()).rawValue() >>> 3);
    }

    @Override
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public Word encodeAsUnmanagedObjectHeader(DynamicHub hub) {
        return encodeAsTLABObjectHeader(hub);
    }

    @Override
    public int constantHeaderSize() {
        return Integer.BYTES;
    }

    @Override
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public void verifyDynamicHubOffset(long offset) {
        VMError.guarantee(offset > 0 && offset < (1L << 35) && (offset & 7) == 0, "Jam hub outside compressed address space");
    }

    @Override
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public Word readHeaderFromPointer(Pointer object) {
        return Word.unsigned((object.readInt(ObjectLayout.singleton().getHubOffset()) & 0xffffffffL));
    }

    @Override
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public Word readHeaderFromObject(Object object) {
        return Word.unsigned((ObjectAccess.readInt(object, ObjectLayout.singleton().getHubOffset()) & 0xffffffffL));
    }

    @Override
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public Pointer extractPotentialDynamicHubFromHeader(Word header) {
        return KnownIntrinsics.heapBase().add(header.and(Word.unsigned(0xffff_ffffL)).shiftLeft(3));
    }

    @Override
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    @AlwaysInline(INLINE_INITIALIZE_HEADER_INIT_REASON)
    protected void initializeObjectHeader(Pointer object, Word header, boolean isArrayLike, MemWriter writer) {
        writer.writeLong(object, ObjectLayout.singleton().getHubOffset(), header.rawValue());
    }

    @Override
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public boolean hasOptionalIdentityHashField(Word header) {
        return false;
    }

    @Override
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public boolean hasIdentityHashFromAddress(Word header) {
        return false;
    }

    @Override
    @Uninterruptible(reason = "Jam never hashes a movable address.", callerMustBe = true)
    public void setIdentityHashFromAddress(Pointer object, Word header) {
        throw VMError.shouldNotReachHere("Jam requires a permanent identity-hash field");
    }
}
