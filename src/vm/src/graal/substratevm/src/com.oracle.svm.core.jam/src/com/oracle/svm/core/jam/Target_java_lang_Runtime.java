// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
package com.oracle.svm.core.jam;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.heap.GCCause;
import com.oracle.svm.guest.staging.SubstrateGCOptions;
import com.oracle.svm.shared.Uninterruptible;

/** Fixed arena capacity excludes the sparse compressed-reference address space. */
@TargetClass(value = Runtime.class, onlyWith = UseJamGC.class)
final class Target_java_lang_Runtime {
    @Substitute
    @Uninterruptible(reason = "Read both allocation cursors without a collection between them.")
    private long freeMemory() {
        JamHeap heap = JamHeap.get();
        heap.lock().lockNoTransition();
        try {
            long used = JamNative.used(heap.nativeHeap(), 0).add(JamNative.used(heap.nativeHeap(), 1))
                            .multiply(8).subtract(heap.prefixBytes().multiply(2)).rawValue();
            return heap.oldCapacity().add(heap.youngCapacity()).rawValue() - used;
        } finally { heap.lock().unlock(); }
    }
    @Substitute private long totalMemory() { return maxMemory(); }
    @Substitute private long maxMemory() { return JamHeap.get().oldCapacity().add(JamHeap.get().youngCapacity()).rawValue(); }
    @Substitute private void gc() {
        if (!SubstrateGCOptions.DisableExplicitGC.getValue()) JamGC.get().collectCompletely(GCCause.JavaLangSystemGC);
    }
}
