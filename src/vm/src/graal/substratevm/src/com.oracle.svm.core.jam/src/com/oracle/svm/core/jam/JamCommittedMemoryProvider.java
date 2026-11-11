// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
package com.oracle.svm.core.jam;

import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.c.type.WordPointer;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.impl.Word;
import com.oracle.svm.core.IsolateArgumentAccess;
import com.oracle.svm.core.IsolateArgumentParser;
import com.oracle.svm.core.IsolateArguments;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.graal.nodes.WriteCodeBaseNode;
import com.oracle.svm.core.graal.nodes.WriteHeapBaseNode;
import com.oracle.svm.core.graal.snippets.CEntryPointSnippets;
import com.oracle.svm.core.os.AbstractCommittedMemoryProvider;
import com.oracle.svm.core.os.CommittedMemoryProvider;
import com.oracle.svm.core.os.ImageHeapProvider;
import com.oracle.svm.core.os.VirtualMemoryProvider;
import com.oracle.svm.guest.staging.SubstrateGCOptions;
import com.oracle.svm.guest.staging.c.function.CEntryPointErrors;
import com.oracle.svm.guest.staging.core.graal.KnownIntrinsics;
import com.oracle.svm.shared.NeverInline;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.AllAccess;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.DisallowLayered;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;
import com.oracle.svm.shared.util.UnsignedUtils;

/** Own the virtual reservation; Jam owns only mappings within its two managed domains. */
@SingletonTraits(access = AllAccess.class, layeredCallbacks = NoLayeredCallbacks.class, other = DisallowLayered.class)
final class JamCommittedMemoryProvider extends AbstractCommittedMemoryProvider {
    private Pointer reservation;

    @Override
    @Uninterruptible(reason = "Isolate bootstrap before Java allocation.")
    public int initialize(WordPointer heapBaseOut, IsolateArguments arguments) {
        UnsignedWord page = VirtualMemoryProvider.get().getGranularity();
        long requestedMax = IsolateArgumentAccess.readLong(arguments, IsolateArgumentParser.getOptionIndex(SubstrateGCOptions.MaxHeapSize));
        long requestedYoung = IsolateArgumentAccess.readLong(arguments, IsolateArgumentParser.getOptionIndex(SubstrateGCOptions.MaxNewSize));
        long requestedMin = IsolateArgumentAccess.readLong(arguments, IsolateArgumentParser.getOptionIndex(SubstrateGCOptions.MinHeapSize));
        long requestedSpace = IsolateArgumentAccess.readLong(arguments, IsolateArgumentParser.getOptionIndex(SubstrateGCOptions.ReservedAddressSpaceSize));
        if (requestedMax == 0) { requestedMax = 128L << 20; }
        if (requestedYoung == 0) { requestedYoung = requestedMax / 4; }
        if (requestedMax <= 0 || requestedYoung <= 0 || requestedYoung >= requestedMax || requestedMin < 0 || requestedMin > requestedMax ||
                        requestedSpace != 0 && requestedSpace != JamHeap.ADDRESS_SPACE) {
            return CEntryPointErrors.INSUFFICIENT_ADDRESS_SPACE;
        }
        UnsignedWord young = UnsignedUtils.roundUp(Word.unsigned(requestedYoung), page);
        UnsignedWord old = UnsignedUtils.roundUp(Word.unsigned(requestedMax - requestedYoung), page);
        UnsignedWord reserve = UnsignedUtils.min(Word.unsigned(8L << 20), UnsignedUtils.min(old, young));
        if (old.add(page).aboveThan(JamHeap.IMAGE_OFFSET) || young.add(page).add(reserve).aboveThan(Word.unsigned(JamHeap.YOUNG_OFFSET))) {
            return CEntryPointErrors.INSUFFICIENT_ADDRESS_SPACE;
        }
        UnsignedWord span = Word.unsigned(JamHeap.ADDRESS_SPACE);
        Pointer reserved = VirtualMemoryProvider.get().reserve(span, Word.unsigned(JamHeap.get().getHeapBaseAlignment()), false);
        if (reserved.isNull()) { return CEntryPointErrors.RESERVE_ADDRESS_SPACE_FAILED; }
        WordPointer end = StackValue.get(WordPointer.class);
        int status = ImageHeapProvider.get().initialize(reserved, span, heapBaseOut, end);
        if (status != CEntryPointErrors.NO_ERROR) {
            VirtualMemoryProvider.get().free(reserved, span);
            return status;
        }
        Pointer previousHeapBase = KnownIntrinsics.heapBase();
        Pointer previousCodeBase = SubstrateOptions.useRelativeCodePointers() ? KnownIntrinsics.codeBase() : Word.nullPointer();
        return initializeWithBase(heapBaseOut.read(), reserved, page, old, young, previousHeapBase, previousCodeBase);
    }

    @NeverInline("Capture the caller's base registers before activating the new image.")
    @Uninterruptible(reason = "Switch bases without carrying Java references between isolates.")
    private static int initializeWithBase(Pointer heapBase, Pointer reserved, UnsignedWord page, UnsignedWord old, UnsignedWord young,
                    Pointer previousHeapBase, Pointer previousCodeBase) {
        CEntryPointSnippets.initBaseRegisters(heapBase);
        int status = initializeMapped(reserved, page, old, young);
        if (status != CEntryPointErrors.NO_ERROR) {
            // At initial C entry, the saved registers need not identify an isolate.
            // Restore their raw values without unlocking or dereferencing that heap.
            WriteHeapBaseNode.writeCurrentVMHeapBase(previousHeapBase);
            if (SubstrateOptions.useRelativeCodePointers()) {
                WriteCodeBaseNode.writeCurrentVMCodeBase(previousCodeBase);
            }
            freeFailedReservation(reserved);
        }
        return status;
    }

    @NeverInline("Reload provider references after restoring the caller's base registers.")
    @Uninterruptible(reason = "Release the failed image only after it is no longer the active heap base.")
    private static void freeFailedReservation(Pointer reserved) {
        VirtualMemoryProvider.get().free(reserved, Word.unsigned(JamHeap.ADDRESS_SPACE));
    }

    @NeverInline("Reload singleton references after establishing the new isolate's heap base.")
    @Uninterruptible(reason = "Initialize native arenas in the new isolate.")
    private static int initializeMapped(Pointer reserved, UnsignedWord page, UnsignedWord old, UnsignedWord young) {
        JamHeap heap = JamHeap.get();
        if (!heap.base().equal(reserved) || ImageHeapProvider.get().getImageHeapEndOffsetInAddressSpace().aboveThan(Word.unsigned(JamHeap.YOUNG_OFFSET))) {
            return CEntryPointErrors.INSUFFICIENT_ADDRESS_SPACE;
        }
        UnsignedWord reserve = UnsignedUtils.min(Word.unsigned(8L << 20), UnsignedUtils.min(old, young));
        if (VirtualMemoryProvider.get().prepareForExternalMapping(reserved.add(page), old) != 0 ||
                        VirtualMemoryProvider.get().prepareForExternalMapping(reserved.add(Word.unsigned(JamHeap.YOUNG_OFFSET)).add(page), young) != 0) {
            return CEntryPointErrors.RESERVE_ADDRESS_SPACE_FAILED;
        }
        Pointer nativeHeap = JamNative.create(reserved, page, old, young, reserve, Word.unsigned(JamOptions.JamWorkers.getValue()));
        if (nativeHeap.isNull()) {
            return CEntryPointErrors.RESERVE_ADDRESS_SPACE_FAILED;
        }
        heap.initialize(nativeHeap, page, old, young);
        JamNative.addImmortalRange(nativeHeap, JamHeap.IMAGE_OFFSET >>> 3, ImageHeapProvider.get().getImageHeapEndOffsetInAddressSpace().unsignedShiftRight(3).rawValue());
        ((JamCommittedMemoryProvider) CommittedMemoryProvider.get()).reservation = reserved;
        return CEntryPointErrors.NO_ERROR;
    }

    @Override
    @Uninterruptible(reason = "No Jam thread descriptor exists on an early bootstrap failure.")
    public void abortInitialization() {
        JamHeap heap = JamHeap.get();
        // Upstream listeners and partially initialized locks may still refer to the image.
        // Jam restores inaccessible reservations in both arenas before destroying its backing.
        heap.releaseNativeResources();
    }

    @Override public UnsignedWord getCollectedHeapAddressSpaceSize() { return JamHeap.get().oldCapacity().add(JamHeap.get().youngCapacity()); }
    @Override protected UnsignedWord getReservedAddressSpaceSize() { return Word.unsigned(JamHeap.ADDRESS_SPACE); }
    @Override
    @Uninterruptible(reason = "The native heap was destroyed before its canonical reservation.")
    public int tearDown() {
        return VirtualMemoryProvider.get().free(reservation, Word.unsigned(JamHeap.ADDRESS_SPACE)) == 0 ? CEntryPointErrors.NO_ERROR : CEntryPointErrors.FREE_ADDRESS_SPACE_FAILED;
    }
}
