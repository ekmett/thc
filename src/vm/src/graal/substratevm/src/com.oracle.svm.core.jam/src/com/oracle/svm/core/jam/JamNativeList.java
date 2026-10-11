// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
package com.oracle.svm.core.jam;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.Pointer;
import org.graalvm.word.impl.Word;
import com.oracle.svm.core.nmt.NmtCategory;
import com.oracle.svm.core.memory.NullableNativeMemory;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.util.VMError;

/** Native collector scratch storage; entries never become accidental Java roots. */
final class JamNativeList {
    private Pointer data = Word.nullPointer();
    private long size;
    private long capacity;

    @Platforms(Platform.HOSTED_ONLY.class)
    JamNativeList() { }

    @Uninterruptible(reason = Uninterruptible.CORE_GC_CODE, callerMustBe = true)
    long size() { return size; }

    @Uninterruptible(reason = Uninterruptible.CORE_GC_CODE, callerMustBe = true)
    void clear() { size = 0; }

    @Uninterruptible(reason = Uninterruptible.CORE_GC_CODE, callerMustBe = true)
    long get(long index) {
        VMError.guarantee(index >= 0 && index < size, "Jam scratch index");
        return data.readLong(Word.unsigned(index).multiply(Long.BYTES));
    }

    @Uninterruptible(reason = Uninterruptible.CORE_GC_CODE, callerMustBe = true)
    void set(long index, long value) {
        VMError.guarantee(index >= 0 && index < size, "Jam scratch index");
        data.writeLong(Word.unsigned(index).multiply(Long.BYTES), value);
    }

    @Uninterruptible(reason = Uninterruptible.CORE_GC_CODE, callerMustBe = true)
    void add(long value) {
        if (size == capacity) {
            long next = capacity == 0 ? 256 : capacity * 2;
            VMError.guarantee(next > capacity && next <= Long.MAX_VALUE / Long.BYTES, "Jam scratch overflow");
            data = NullableNativeMemory.realloc(data, Word.unsigned(next).multiply(Long.BYTES), NmtCategory.GC);
            VMError.guarantee(data.isNonNull(), "Unable to allocate Jam collector scratch memory");
            capacity = next;
        }
        data.writeLong(Word.unsigned(size++).multiply(Long.BYTES), value);
    }

    @Uninterruptible(reason = "Isolate tear down.")
    void release() {
        NullableNativeMemory.free(data);
        data = Word.nullPointer();
        size = capacity = 0;
    }
}
