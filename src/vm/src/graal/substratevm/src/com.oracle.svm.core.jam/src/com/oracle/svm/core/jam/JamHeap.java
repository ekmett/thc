// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
package com.oracle.svm.core.jam;

import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.c.type.CIntPointer;
import org.graalvm.nativeimage.c.type.WordPointer;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.impl.Word;
import com.oracle.svm.core.heap.GC;
import com.oracle.svm.core.heap.Heap;
import com.oracle.svm.core.heap.ObjectHeader;
import com.oracle.svm.core.heap.ObjectVisitor;
import com.oracle.svm.core.heap.UninterruptibleObjectReferenceVisitor;
import com.oracle.svm.core.heap.ReferenceInternals;
import com.oracle.svm.core.hub.InteriorObjRefWalker;
import com.oracle.svm.core.heap.RuntimeCodeInfoGCSupport;
import com.oracle.svm.core.hub.LayoutEncoding;
import com.oracle.svm.core.locks.VMMutex;
import com.oracle.svm.core.os.ImageHeapProvider;
import com.oracle.svm.core.thread.VMOperation;
import com.oracle.svm.core.thread.VMThreads;
import com.oracle.svm.core.thread.VMThreads.SafepointBehavior;
import org.graalvm.nativeimage.CurrentIsolate;
import com.oracle.svm.guest.staging.core.graal.KnownIntrinsics;
import com.oracle.svm.guest.staging.core.threadlocal.FastThreadLocalFactory;
import com.oracle.svm.core.heap.NoAllocationVerifier;
import com.oracle.svm.guest.staging.core.threadlocal.FastThreadLocalLong;
import com.oracle.svm.guest.staging.core.threadlocal.FastThreadLocalWord;
import com.oracle.svm.guest.staging.log.Log;
import com.oracle.svm.guest.staging.option.NotifyGCRuntimeOptionKey;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.guest.staging.core.jdk.UninterruptibleUtils.Math;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.AllAccess;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.DisallowLayered;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;
import com.oracle.svm.shared.util.VMError;
import jdk.graal.compiler.api.replacements.Fold;

/** Java heap ownership and remembered metadata around Jam's native hosted heap. */
@SingletonTraits(access = AllAccess.class, layeredCallbacks = NoLayeredCallbacks.class, other = DisallowLayered.class)
public final class JamHeap extends Heap {
    static final int IMAGE_OFFSET = 1 << 30;
    static final long YOUNG_OFFSET = 1L << 34;
    static final long ADDRESS_SPACE = 1L << 35;
    private static final FastThreadLocalLong allocatedBytes = FastThreadLocalFactory.createLong("Jam.allocatedBytes");
    private static final FastThreadLocalWord<Pointer> threadScope = FastThreadLocalFactory.createWord("Jam.heapScope");
    private final VMMutex allocationLock = new VMMutex("Jam allocation");
    private final JamObjectHeader header = new JamObjectHeader();
    final JamImageHeapInfo imageInfo = new JamImageHeapInfo();
    private final JamGC gc = new JamGC(this);
    private final RememberVisitor rememberVisitor = new RememberVisitor();
    private Pointer handle = Word.nullPointer();
    private UnsignedWord prefix = Word.zero();
    private UnsignedWord oldBytes = Word.zero();
    private UnsignedWord youngBytes = Word.zero();
    private Pointer oldTop = Word.nullPointer();
    private Pointer youngTop = Word.nullPointer();
    private volatile boolean tracksStarts;
    private long oldPeak;
    private long youngPeak;
    private long oldAfterCollection;
    private long youngAfterCollection;

    @Fold
    public static JamHeap get() { return ImageSingletons.lookup(JamHeap.class); }

    @Uninterruptible(reason = "Initialize the mapped isolate before allocation begins.")
    void initialize(Pointer nativeHeap, UnsignedWord guard, UnsignedWord oldCapacity, UnsignedWord youngCapacity) {
        handle = nativeHeap;
        prefix = guard;
        oldBytes = oldCapacity;
        youngBytes = youngCapacity;
        oldTop = oldBegin();
        youngTop = youngBegin();
    }

    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    Pointer nativeHeap() { return handle; }
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    Pointer base() { return KnownIntrinsics.heapBase(); }
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    UnsignedWord prefixBytes() { return prefix; }
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    UnsignedWord oldCapacity() { return oldBytes; }
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    UnsignedWord youngCapacity() { return youngBytes; }
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    Pointer oldBegin() { return base().add(prefix); }
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    Pointer oldEnd() { return oldBegin().add(oldBytes); }
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    Pointer youngBegin() { return base().add(Word.unsigned(YOUNG_OFFSET)).add(prefix); }
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    Pointer youngEnd() { return youngBegin().add(youngBytes); }
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    Pointer oldUsedEnd() { return oldTop; }
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    Pointer youngUsedEnd() { return youngTop; }
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    VMMutex lock() { return allocationLock; }

    @Uninterruptible(reason = "Keep encoded and materialized references in one safepoint-free scope.", callerMustBe = true)
    int encode(Object object) {
        return object == null ? 0 : (int) Word.objectToUntrackedPointer(object).subtract(base()).unsignedShiftRight(3).rawValue();
    }
    @Uninterruptible(reason = "Keep encoded and materialized references in one safepoint-free scope.", callerMustBe = true)
    Object decode(int offset) {
        return offset == 0 ? null : base().add(Word.unsigned((offset & 0xffffffffL)).shiftLeft(3)).toObjectNonNull();
    }
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    boolean isYoung(Pointer object) { return object.aboveOrEqual(youngBegin()) && object.belowThan(youngTop); }
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    boolean isOld(Pointer object) { return object.aboveOrEqual(oldBegin()) && object.belowThan(oldTop); }
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    boolean isManaged(Pointer object) { return isOld(object) || isYoung(object); }

    @Uninterruptible(reason = "Allocate raw object storage under the allocation lock.")
    Pointer allocateRaw(UnsignedWord bytes, boolean young) {
        VMError.guarantee(bytes.and(7).equal(0), "Jam allocation must be word aligned");
        int offset = JamNative.allocate(handle, bytes.unsignedShiftRight(3), young ? 1 : 0);
        if (offset == 0) { return Word.nullPointer(); }
        Pointer result = base().add(Word.unsigned((offset & 0xffffffffL)).shiftLeft(3));
        if (young) {
            youngTop = result.add(bytes);
            youngPeak = Math.max(youngPeak, usedBytes(true));
        } else {
            oldTop = result.add(bytes);
            oldPeak = Math.max(oldPeak, usedBytes(false));
        }
        return result;
    }

    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    long usedBytes(boolean young) { return (young ? youngTop.subtract(youngBegin()) : oldTop.subtract(oldBegin())).rawValue(); }
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    long peakBytes(boolean young) { return young ? youngPeak : oldPeak; }
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    long collectionBytes(boolean young) { return young ? youngAfterCollection : oldAfterCollection; }
    @Uninterruptible(reason = "Reset peak accounting under the allocator lock.")
    void resetPeak(boolean young) {
        lock().lockNoTransition();
        try {
            if (young) { youngPeak = usedBytes(true); }
            else { oldPeak = usedBytes(false); }
        } finally { lock().unlock(); }
    }

    @Uninterruptible(reason = "Register completed reference writes without reading their mutable targets.")
    void remember(Object holder, Pointer first, long count, int stride, boolean compressed) {
        Pointer owner = Word.objectToUntrackedPointer(holder);
        if (owner.aboveOrEqual(oldBegin()) && owner.belowThan(oldEnd())) {
            JamNative.remember(handle, encode(holder), first.subtract(base()).unsignedShiftRight(2).rawValue(),
                            Word.unsigned(count), Word.unsigned(stride / 4), compressed ? 1 : 0);
        }
    }

    @Uninterruptible(reason = "Update allocation bounds after publishing the collected arenas.")
    void finishCollection(boolean minor, boolean promoted) {
        Pointer previousOldTop = oldTop;
        oldTop = base().add(JamNative.used(handle, 0).shiftLeft(3));
        youngTop = base().add(Word.unsigned(YOUNG_OFFSET)).add(JamNative.used(handle, 1).shiftLeft(3));
        oldAfterCollection = usedBytes(false);
        youngAfterCollection = usedBytes(true);
        oldPeak = Math.max(oldPeak, oldAfterCollection);
        youngPeak = Math.max(youngPeak, youngAfterCollection);
        if (minor) VMError.guarantee(oldTop.aboveOrEqual(previousOldTop), "A minor cannot relocate the old arena");
        if (promoted) VMError.guarantee(youngTop.equal(youngBegin()), "Promotion must empty young space");
    }

    @Uninterruptible(reason = "Register derived slots using the VM's existing reference visitor.")
    void rememberDerived(Object holder, Pointer baseSlot, Pointer slot, boolean compressed) {
        Pointer owner = Word.objectToUntrackedPointer(holder);
        if (owner.aboveOrEqual(oldBegin()) && owner.belowThan(oldEnd())) {
            JamNative.rememberDerived(handle, encode(holder), baseSlot.subtract(base()).unsignedShiftRight(2).rawValue(),
                            slot.subtract(base()).unsignedShiftRight(2).rawValue(), compressed ? 1 : 0);
        }
    }

    private final class RememberVisitor implements UninterruptibleObjectReferenceVisitor {
        @Override @Uninterruptible(reason = "Register copied reference locations without tracing targets.")
        public void visitObjectReferences(Pointer first, boolean compressed, int stride, Object holder, int count) {
            remember(holder, first, count, stride, compressed);
        }
        @Override @Uninterruptible(reason = "The base remains an ordinary reference.")
        public void visitDerivedReferenceBase(Pointer slot, boolean compressed, int stride, Object holder) {
            remember(holder, slot, 1, stride, compressed);
        }
        @Override @Uninterruptible(reason = "Retain the base-to-interior relationship for relocation.")
        public void visitDerivedReference(Pointer base, Pointer slot, boolean compressed, Object holder) {
            rememberDerived(holder, base, slot, compressed);
        }
    }

    @Override
    @Uninterruptible(reason = "Register fields after a bulk object copy.", callerMustBe = true)
    public void dirtyAllReferencesOf(Object object) {
        if (!isOld(Word.objectToUntrackedPointer(object))) return;
        InteriorObjRefWalker.walkObjectInline(object, rememberVisitor);
        if (object instanceof Reference<?> reference) {
            remember(object, ReferenceInternals.getReferentFieldAddress(reference), 1, 4, true);
        }
    }

    @Override public GC getGC() { return gc; }
    JamGC collector() { return gc; }
    @Override @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public RuntimeCodeInfoGCSupport getRuntimeCodeInfoGCSupport() { return gc.getRuntimeCodeInfoGCSupport(); }
    @Override public void doReferenceHandling() { gc.doReferenceHandling(); }
    @Override public boolean hasReferencePendingList() { return gc.hasReferencePendingList(); }
    @Override public void waitForReferencePendingList() throws InterruptedException { gc.waitForReferencePendingList(); }
    @Override public void wakeUpReferencePendingListWaiters() { gc.wakeUpReferencePendingListWaiters(); }
    @Override public Reference<?> getAndClearReferencePendingList() { return gc.getAndClearReferencePendingList(); }
    @Override public long getMillisSinceLastWholeHeapExamined() { return Math.max(0, System.currentTimeMillis() - gc.lastMajorTime()); }
    @Override @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public UnsignedWord getUsedMemoryAfterLastGC() { return gc.collectedMemory(); }
    @Override @Uninterruptible(reason = "The identity hash resides in a permanent field.", callerMustBe = true)
    public long getIdentityHashSalt(Object object) { return 0; }
    @Override @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public ObjectHeader getObjectHeader() { return header; }
    @Override @Fold public int getHeapBaseAlignment() { return 16 * 1024; }
    @Override @Fold public int getImageHeapAlignment() { return 16 * 1024; }
    @Override @Fold public int getImageHeapOffsetInAddressSpace() { return IMAGE_OFFSET; }
    @Override @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public boolean isInImageHeap(Object object) { return object != null && isInImageHeap(Word.objectToUntrackedPointer(object)); }
    @Override @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public boolean isInImageHeap(Pointer object) { return object.aboveOrEqual(base().add(IMAGE_OFFSET)) && object.belowThan(base().add(ImageHeapProvider.get().getImageHeapEndOffsetInAddressSpace())); }
    @Override @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public boolean isInPrimaryImageHeap(Object object) { return isInImageHeap(object); }
    @Override @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public boolean isInPrimaryImageHeap(Pointer object) { return isInImageHeap(object); }
    @Override @Uninterruptible(reason = "Validate startup mapping.")
    public boolean verifyImageHeapMapping() { return handle.isNonNull() && isInImageHeap(JamHeap.class) && oldEnd().belowOrEqual(base().add(IMAGE_OFFSET)); }
    @Override @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public int getClassCount() { return imageInfo.classCount; }
    @Override protected List<Class<?>> getClassesInImageHeap() {
        List<Class<?>> result = new ArrayList<>();
        for (int i = 0; i < JamImageHeapInfo.PARTITIONS; i++) {
            if (imageInfo.first(i) == null) { continue; }
            Pointer current = Word.objectToUntrackedPointer(imageInfo.first(i));
            Pointer last = Word.objectToUntrackedPointer(imageInfo.last(i));
            while (current.belowOrEqual(last)) {
                Object object = current.toObjectNonNull();
                if (object instanceof Class<?> clazz) { result.add(clazz); }
                current = current.add(LayoutEncoding.getSizeFromObjectInGC(object));
            }
        }
        return result;
    }
    @Override public void walkObjects(ObjectVisitor visitor) { walkImageHeapObjects(visitor); walkCollectedHeapObjects(visitor); }
    @Override public void walkImageHeapObjects(ObjectVisitor visitor) { imageInfo.walk(visitor, false); }
    void walkImageHeapRoots(ObjectVisitor visitor) { imageInfo.walk(visitor, true); }
    @Override public boolean prepareForHeapWalk() {
        VMOperation.guaranteeInProgressAtSafepoint("Enable object starts with mutators stopped");
        if (tracksStarts) return false;
        VMError.guarantee(JamNative.trackStarts(handle) != 0, "Cannot allocate Jam object-start metadata");
        tracksStarts = true;
        return true;
    }
    @Uninterruptible(reason = "Publish an actual formatted object, never an allocation-buffer tail.")
    Object recordFormattedObject(Object object) {
        if (tracksStarts) JamBarrierSnippets.recordStart(object);
        return object;
    }
    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    boolean tracksStarts() { return tracksStarts; }
    @Override public void walkCollectedHeapObjects(ObjectVisitor visitor) {
        VMOperation.guaranteeInProgressAtSafepoint("Jam heap walking requires a safepoint");
        VMError.guarantee(tracksStarts, "Prepare object-start metadata before beginning a heap walk");
        retireAllTlabs();
        walkStarts(false, visitor);
        walkStarts(true, visitor);
    }
    private void walkStarts(boolean young, ObjectVisitor visitor) {
        WordPointer count = StackValue.get(WordPointer.class);
        CIntPointer words = JamNative.startBits(handle, young ? 1 : 0, count);
        long length = count.read().rawValue();
        Pointer origin = young ? base().add(Word.unsigned(YOUNG_OFFSET)) : base();
        for (long i = 0; i < length; ++i) {
            int bits = words.read(Word.unsigned(i));
            while (bits != 0) {
                int bit = Integer.numberOfTrailingZeros(bits);
                visitor.visitObject(origin.add(Word.unsigned(i * 32 + bit).shiftLeft(3)).toObjectNonNull());
                bits &= bits - 1;
            }
        }
    }
    @Override @Uninterruptible(reason = "Retire the fast path before allocation is suspended.")
    public void suspendAllocation() {
        JamThreadLocalAllocation.retire(CurrentIsolate.getCurrentThread());
    }
    @Override public void resumeAllocation() { /* The next allocation refills the TLAB. */ }
    @Override @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public boolean isAllocationDisallowed() { return NoAllocationVerifier.isActive() || SafepointBehavior.ignoresSafepoints(); }
    @Uninterruptible(reason = "Account for actual objects, excluding unused buffer tails.")
    void recordAllocatedBytes(IsolateThread thread, UnsignedWord bytes) {
        allocatedBytes.set(thread, allocatedBytes.get(thread) + bytes.rawValue());
    }
    @Override @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public long getThreadAllocatedMemory(IsolateThread thread) { return allocatedBytes.get(thread) + JamThreadLocalAllocation.usedBytes(thread).rawValue(); }
    @Override @Uninterruptible(reason = "Initialize per-thread allocation state.")
    public void attachThread(IsolateThread thread) {
        allocatedBytes.set(thread, 0);
        JamThreadLocalAllocation.initialize(thread);
        Pointer scope = JamNative.threadCreate(handle);
        threadScope.set(thread, scope);
        JamNative.threadEnter(scope);
    }
    @Override @Uninterruptible(reason = "Release per-thread allocation state.")
    public void detachThread(IsolateThread thread) {
        JamThreadLocalAllocation.retire(thread);
        releaseThreadScope(thread);
    }
    @Uninterruptible(reason = "Bind Jam before executing Java or VM work on this thread.")
    @Override public void enterThreadContext() {
        Pointer scope = threadScope.get();
        if (scope.isNonNull()) { JamThreadContext.enter(scope); }
    }
    @Uninterruptible(reason = "Restore the enclosing native context before a thread may switch isolates.")
    @Override public void leaveThreadContext() {
        Pointer scope = threadScope.get();
        if (scope.isNonNull()) { JamThreadContext.leave(scope); }
    }
    @Uninterruptible(reason = "Only inactive scopes may be reclaimed from another OS thread.")
    private void releaseThreadScope(IsolateThread thread) {
        Pointer scope = threadScope.get(thread);
        if (scope.isNull()) { return; }
        if (thread.equal(CurrentIsolate.getCurrentThread())) { JamNative.threadLeave(scope); }
        JamNative.threadDestroy(scope);
        threadScope.set(thread, Word.nullPointer());
    }
    @Override public void prepareForSafepoint() { }
    @Override public void endSafepoint() { }
    @Uninterruptible(reason = "Mutators are stopped before their unused buffers become filler objects.")
    void retireAllTlabs() {
        VMOperation.guaranteeInProgressAtSafepoint("Jam TLAB retirement requires a safepoint");
        for (IsolateThread thread = VMThreads.firstThread(); thread.isNonNull(); thread = VMThreads.nextThread(thread)) {
            JamThreadLocalAllocation.retire(thread);
        }
    }
    @Override public void optionValueChanged(NotifyGCRuntimeOptionKey<?> key) { }
    @Override public boolean printLocationInfo(Log log, UnsignedWord value, boolean access, boolean unsafe) {
        Pointer pointer = (Pointer) value;
        if (isManaged(pointer) || isInImageHeap(pointer)) { log.string("Jam heap ").zhex(value); return true; }
        return false;
    }
    @Override @Uninterruptible(reason = "Tear down native heap before releasing canonical reservation.")
    public boolean tearDown() {
        releaseThreadScope(CurrentIsolate.getCurrentThread());
        releaseNativeResources();
        return true;
    }
    @Uninterruptible(reason = "Also called when bootstrap failed before any VM thread existed.")
    void releaseNativeResources() {
        gc.tearDown();
        if (handle.isNonNull()) { JamNative.destroy(handle); handle = Word.nullPointer(); }
    }
}
