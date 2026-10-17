// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
package com.oracle.svm.core.jam;

import org.graalvm.nativeimage.CurrentIsolate;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.struct.RawField;
import org.graalvm.nativeimage.c.struct.RawFieldOffset;
import org.graalvm.nativeimage.c.struct.RawStructure;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.word.LocationIdentity;
import org.graalvm.word.PointerBase;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.impl.Word;
import com.oracle.svm.core.config.ObjectLayout;
import com.oracle.svm.core.genscavenge.FillerObjectUtil;
import com.oracle.svm.core.genscavenge.graal.nodes.FormatArrayNode;
import com.oracle.svm.core.genscavenge.graal.nodes.FormatObjectNode;
import com.oracle.svm.core.genscavenge.graal.nodes.FormatPodNode;
import com.oracle.svm.core.genscavenge.graal.nodes.FormatStoredContinuationNode;
import com.oracle.svm.core.heap.OutOfMemoryUtil;
import com.oracle.svm.core.heap.Pod;
import com.oracle.svm.core.heap.StoredContinuation;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.hub.LayoutEncoding;
import com.oracle.svm.core.thread.ContinuationSupport;
import com.oracle.svm.guest.staging.core.heap.RestrictHeapAccess;
import com.oracle.svm.guest.staging.core.threadlocal.FastThreadLocalBytes;
import com.oracle.svm.guest.staging.core.threadlocal.FastThreadLocalFactory;
import com.oracle.svm.shared.Uninterruptible;
import jdk.graal.compiler.api.replacements.Fold;
import com.oracle.svm.shared.util.UnsignedUtils;
import static com.oracle.svm.core.graal.snippets.SubstrateAllocationSnippets.TLAB_START_IDENTITY;
import static com.oracle.svm.core.graal.snippets.SubstrateAllocationSnippets.TLAB_TOP_IDENTITY;
import static com.oracle.svm.core.graal.snippets.SubstrateAllocationSnippets.TLAB_END_IDENTITY;
import com.oracle.svm.shared.util.VMError;
import jdk.graal.compiler.replacements.AllocationSnippets.FillContent;

/** Allocation remains rooted across collection; raw addresses exist only while formatting. */
final class JamThreadLocalAllocation {
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final int MAX_TLAB_OBJECT = BUFFER_BYTES / 2;
    private static final FastThreadLocalBytes<Descriptor> local = FastThreadLocalFactory.createBytes(() -> SizeOf.get(Descriptor.class), "Jam TLAB");

    @RawStructure
    interface Descriptor extends PointerBase {
        @RawField Word getStart(LocationIdentity identity);
        @RawField void setStart(Pointer value, LocationIdentity identity);
        @RawField Word getTop(LocationIdentity identity);
        @RawField void setTop(Pointer value, LocationIdentity identity);
        @RawField Word getEnd(LocationIdentity identity);
        @RawField void setEnd(Pointer value, LocationIdentity identity);
        @RawFieldOffset static int offsetOfTop() { throw VMError.shouldNotReachHereAtRuntime(); }
        @RawFieldOffset static int offsetOfEnd() { throw VMError.shouldNotReachHereAtRuntime(); }
    }

    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    static Word tlabAddress() { return Word.pointer(local.getAddress().rawValue()); }

    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    static boolean fitsTlabPolicy(UnsignedWord size) { return size.belowOrEqual(MAX_TLAB_OBJECT); }

    @Uninterruptible(reason = "Initialize a thread's nursery buffer.")
    static void initialize(IsolateThread thread) {
        Descriptor descriptor = local.getAddress(thread);
        descriptor.setStart(Word.nullPointer(), TLAB_START_IDENTITY);
        descriptor.setTop(Word.nullPointer(), TLAB_TOP_IDENTITY);
        descriptor.setEnd(Word.nullPointer(), TLAB_END_IDENTITY);
    }

    @Fold
    static int fillerReserve() {
        int minimum = ObjectLayout.singleton().getMinRuntimeHeapInstanceSize();
        return minimum > ObjectLayout.singleton().getAlignment() ? minimum : 0;
    }

    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    static UnsignedWord usedBytes(IsolateThread thread) {
        Descriptor descriptor = local.getAddress(thread);
        return descriptor.getTop(TLAB_TOP_IDENTITY).subtract(descriptor.getStart(TLAB_START_IDENTITY));
    }

    @Uninterruptible(reason = "Retire only this thread's buffer or a stopped mutator's buffer.")
    static void retire(IsolateThread thread) {
        Descriptor descriptor = local.getAddress(thread);
        Pointer top = descriptor.getTop(TLAB_TOP_IDENTITY);
        if (top.isNull()) { return; }
        Pointer hardEnd = descriptor.getEnd(TLAB_END_IDENTITY).add(fillerReserve());
        if (top.belowThan(hardEnd)) { FillerObjectUtil.writeFillerObjectAt(top, hardEnd.subtract(top), false); }
        JamHeap.get().recordAllocatedBytes(thread, usedBytes(thread));
        initialize(thread);
    }

    static Object slowPathNewInstance(Word header) {
        DynamicHub hub = JamHeap.get().getObjectHeader().dynamicHubFromObjectHeader(header);
        return allocate(hub, -1, LayoutEncoding.getPureInstanceAllocationSize(hub.getLayoutEncoding()), null);
    }

    static Object slowPathNewArrayLikeObject(Word header, int length, byte[] referenceMap) {
        if (length < 0) { throw new NegativeArraySizeException(); }
        DynamicHub hub = JamHeap.get().getObjectHeader().dynamicHubFromObjectHeader(header);
        return allocate(hub, length, LayoutEncoding.getArrayAllocationSize(hub.getLayoutEncoding(), length), referenceMap);
    }

    @RestrictHeapAccess(access = RestrictHeapAccess.Access.NO_ALLOCATION, reason = "Implementation of Java allocation.")
    private static Object allocate(DynamicHub hub, int length, UnsignedWord size, byte[] referenceMap) {
        JamHeap heap = JamHeap.get();
        VMError.guarantee(!heap.isAllocationDisallowed(), "Allocation is suspended");
        boolean fitsOld = size.belowOrEqual(heap.oldCapacity());
        boolean old = fitsOld && size.aboveThan(heap.youngCapacity().unsignedShiftRight(2));
        if (!fitsOld && size.aboveThan(heap.youngCapacity())) { throw OutOfMemoryUtil.heapSizeExceeded(); }
        Object result = tryAllocate(hub, length, size, referenceMap, old);
        if (result != null) { return result; }
        JamGC.get().collectForAllocation(size, old);
        result = tryAllocate(hub, length, size, referenceMap, old);
        if (result != null) { return result; }
        if (size.belowOrEqual(old ? heap.youngCapacity() : heap.oldCapacity())) {
            result = tryAllocate(hub, length, size, referenceMap, !old);
            if (result != null) { return result; }
            JamGC.get().collectForAllocation(size, !old);
            result = tryAllocate(hub, length, size, referenceMap, !old);
            if (result != null) { return result; }
        }
        throw OutOfMemoryUtil.heapSizeExceeded();
    }

    @Uninterruptible(reason = "Raw allocation is formatted before releasing allocation ownership.")
    private static Object tryAllocate(DynamicHub hub, int length, UnsignedWord size, byte[] referenceMap, boolean old) {
        JamHeap heap = JamHeap.get();
        heap.lock().lockNoTransition();
        try {
            Pointer memory = Word.nullPointer();
            if (!old && fitsTlabPolicy(size)) {
                Descriptor descriptor = local.getAddress();
                Pointer top = descriptor.getTop(TLAB_TOP_IDENTITY);
                Pointer end = descriptor.getEnd(TLAB_END_IDENTITY);
                if (top.isNonNull() && top.add(size).belowOrEqual(end)) {
                    memory = top;
                    descriptor.setTop(top.add(size), TLAB_TOP_IDENTITY);
                } else {
                    retire(CurrentIsolate.getCurrentThread());
                    UnsignedWord desired = UnsignedUtils.max(size.add(fillerReserve()),
                                    UnsignedUtils.min(Word.unsigned(BUFFER_BYTES), heap.youngCapacity().unsignedShiftRight(4)));
                    desired = UnsignedUtils.roundUp(desired, Word.unsigned(ObjectLayout.singleton().getAlignment()));
                    memory = heap.allocateRaw(desired, true);
                    if (memory.isNonNull()) {
                        descriptor.setStart(memory, TLAB_START_IDENTITY);
                        descriptor.setTop(memory.add(size), TLAB_TOP_IDENTITY);
                        descriptor.setEnd(memory.add(desired).subtract(fillerReserve()), TLAB_END_IDENTITY);
                    }
                }
            }
            if (memory.isNull()) {
                memory = heap.allocateRaw(size, !old);
                if (memory.isNonNull()) { heap.recordAllocatedBytes(CurrentIsolate.getCurrentThread(), size); }
            }
            if (memory.isNull()) { return null; }
            Class<?> type = DynamicHub.toClass(hub);
            if (length < 0) {
                return FormatObjectNode.formatObject(memory, type, false, FillContent.WITH_ZEROES, true);
            }
            if (ContinuationSupport.isSupported() && type == StoredContinuation.class) {
                return FormatStoredContinuationNode.formatStoredContinuation(memory, type, length, false, false, true);
            }
            if (Pod.RuntimeSupport.isPresent() && referenceMap != null) {
                return FormatPodNode.formatPod(memory, type, length, referenceMap, false, false, FillContent.WITH_ZEROES, true);
            }
            return FormatArrayNode.formatArray(memory, type, length, false, false, FillContent.WITH_ZEROES, true);
        } finally {
            heap.lock().unlock();
        }
    }
}
