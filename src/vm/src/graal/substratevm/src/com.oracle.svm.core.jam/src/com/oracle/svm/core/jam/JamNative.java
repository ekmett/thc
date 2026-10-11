// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
package com.oracle.svm.core.jam;

import java.util.List;
import java.nio.file.Path;
import com.oracle.svm.core.SubstrateOptions;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.function.CFunctionPointer;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CIntPointer;
import org.graalvm.nativeimage.c.type.CLongPointer;
import org.graalvm.nativeimage.c.type.WordPointer;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;
import static org.graalvm.nativeimage.c.function.CFunction.Transition.NO_TRANSITION;

/** The same opaque C ABI used by HotSpot. All calls run with GC or allocation ownership. */
@CContext(JamNative.Directives.class)
final class JamNative {
    static final class Directives implements CContext.Directives {
        @Override public boolean isInConfiguration() { return SubstrateOptions.useJamGC(); }
        @Override public List<String> getMacroDefinitions() { return List.of("JAM_VM_STATIC"); }
        @Override public List<String> getHeaderFiles() { return List.of("<jam_vm.h>"); }
        @Override public List<String> getOptions() {
            String include = System.getProperty("jam.native.include", libraryDirectory().resolve("include").toString());
            return List.of("-I" + include);
        }
        @Override public List<String> getLibraryPaths() { return List.of(libraryDirectory().resolve("static").toString()); }
        static Path libraryDirectory() {
            return Path.of(System.getProperty("jam.native.library", Path.of(System.getProperty("java.home"), "lib", "jam").toString()));
        }
    }

    @CFunction(value = "jam_vm_create", transition = NO_TRANSITION)
    static native Pointer create(Pointer base, UnsignedWord prefix, UnsignedWord oldBytes, UnsignedWord youngBytes, UnsignedWord reserve, UnsignedWord workers);
    @CFunction(value = "jam_vm_destroy", transition = NO_TRANSITION)
    static native void destroy(Pointer heap);
    @CFunction(value = "jam_vm_thread_create", transition = NO_TRANSITION)
    static native Pointer threadCreate(Pointer heap);
    @CFunction(value = "jam_vm_thread_enter", transition = NO_TRANSITION)
    static native void threadEnter(Pointer thread);
    @CFunction(value = "jam_vm_thread_leave", transition = NO_TRANSITION)
    static native void threadLeave(Pointer thread);
    @CFunction(value = "jam_vm_thread_destroy", transition = NO_TRANSITION)
    static native void threadDestroy(Pointer thread);
    @CFunction(value = "jam_vm_add_immortal_range", transition = NO_TRANSITION)
    static native void addImmortalRange(Pointer heap, int first, long limit);
    @CFunction(value = "jam_vm_allocate", transition = NO_TRANSITION)
    static native int allocate(Pointer heap, UnsignedWord words, int young);
    @CFunction(value = "jam_vm_used", transition = NO_TRANSITION)
    static native UnsignedWord used(Pointer heap, int young);
    @CFunction(value = "jam_vm_origin", transition = NO_TRANSITION)
    static native UnsignedWord origin(Pointer heap, int young);
    @CFunction(value = "jam_vm_compactor", transition = NO_TRANSITION)
    static native CCharPointer compactor(Pointer heap);
    @CFunction(value = "jam_vm_pin_object", transition = NO_TRANSITION)
    static native Pointer pinObject(Pointer heap, int object, UnsignedWord words);
    @CFunction(value = "jam_vm_pin_address", transition = NO_TRANSITION)
    static native Pointer pinAddress(Pointer registration);
    @CFunction(value = "jam_vm_unpin", transition = NO_TRANSITION)
    static native void unpin(Pointer heap, Pointer registration);
    @CFunction(value = "jam_vm_pin_roots", transition = NO_TRANSITION)
    static native void pinRoots(Pointer heap, CFunctionPointer scanner, JamScanContext context);
    @CFunction(value = "jam_vm_gap_count", transition = NO_TRANSITION)
    static native UnsignedWord gapCount(Pointer heap);
    @CFunction(value = "jam_vm_gap_at", transition = NO_TRANSITION)
    static native int gapAt(Pointer heap, UnsignedWord index);
    @CFunction(value = "jam_vm_gap_words", transition = NO_TRANSITION)
    static native UnsignedWord gapWords(Pointer heap, UnsignedWord index);
    @CFunction(value = "jam_vm_remember", transition = NO_TRANSITION)
    static native void remember(Pointer heap, int holder, long first, UnsignedWord count, UnsignedWord stride, int compressed);
    @CFunction(value = "jam_vm_remember_derived", transition = NO_TRANSITION)
    static native void rememberDerived(Pointer heap, int holder, long base, long slot, int compressed);
    @CFunction(value = "jam_vm_remembered", transition = NO_TRANSITION)
    static native void remembered(Pointer heap, CFunctionPointer visitor, JamScanContext context);
    @CFunction(value = "jam_vm_track_starts", transition = NO_TRANSITION)
    static native int trackStarts(Pointer heap);
    @CFunction(value = "jam_vm_start_bits", transition = NO_TRANSITION)
    static native CIntPointer startBits(Pointer heap, int young, WordPointer count);
    @CFunction(value = "jam_vm_tracks_starts", transition = NO_TRANSITION)
    static native int tracksStarts(Pointer heap);
    @CFunction(value = "jam_vm_record_start", transition = NO_TRANSITION)
    static native void recordStart(Pointer heap, int object);
    @CFunction(value = "jam_vm_begin", transition = NO_TRANSITION)
    static native void begin(Pointer heap, int minor);
    @CFunction(value = "jam_vm_trace", transition = NO_TRANSITION)
    static native void trace(Pointer heap, CIntPointer roots, UnsignedWord count, CFunctionPointer scanner, JamScanContext context, UnsignedWord workers);
    @CFunction(value = "jam_vm_trace_old", transition = NO_TRANSITION)
    static native void traceOld(Pointer heap, CIntPointer owners, UnsignedWord count, CFunctionPointer scanner, JamScanContext context);
    @CFunction(value = "jam_vm_marked", transition = NO_TRANSITION)
    static native int marked(Pointer heap, int object);
    @CFunction(value = "jam_vm_prepare", transition = NO_TRANSITION)
    static native int prepare(Pointer heap, int promote);
    @CFunction(value = "jam_vm_forward", transition = NO_TRANSITION)
    static native int forward(Pointer heap, int object);
    @CFunction(value = "jam_vm_finish", transition = NO_TRANSITION)
    static native void finish(Pointer heap);
    @CFunction(value = "jam_vm_claim", transition = NO_TRANSITION)
    static native int claim(Pointer visitor, int object, UnsignedWord words);
    @CFunction(value = "jam_vm_targets", transition = NO_TRANSITION)
    static native void targets(Pointer visitor, CIntPointer roots, UnsignedWord count);
    @CFunction(value = "jam_vm_fields", transition = NO_TRANSITION)
    static native void fields(Pointer visitor, CLongPointer slots, UnsignedWord count, int follow);
    @CFunction(value = "jam_vm_weak_create", transition = NO_TRANSITION)
    static native long weakCreate(Pointer heap, int key, int value, int finalizer);
    @CFunction(value = "jam_vm_weak_value", transition = NO_TRANSITION)
    static native int weakValue(Pointer heap, long id);
    @CFunction(value = "jam_vm_weak_roots", transition = NO_TRANSITION)
    static native void weakRoots(Pointer heap, CFunctionPointer scanner, JamScanContext context);
    @CFunction(value = "jam_vm_weak_close", transition = NO_TRANSITION)
    static native void weakClose(Pointer heap, CFunctionPointer scanner, JamScanContext context);
    @CFunction(value = "jam_vm_weak_finalizers", transition = NO_TRANSITION)
    static native void weakFinalizers(Pointer heap, CFunctionPointer scanner, JamScanContext context);
    @CFunction(value = "jam_vm_weak_take", transition = NO_TRANSITION)
    static native int weakTake(Pointer heap, CLongPointer id);
    @CFunction(value = "jam_vm_weak_finalize", transition = NO_TRANSITION)
    static native int weakFinalize(Pointer heap, long id);
    @CFunction(value = "jam_vm_weak_complete", transition = NO_TRANSITION)
    static native void weakComplete(Pointer heap, long id);
    @CFunction(value = "jam_vm_candidate_arm", transition = NO_TRANSITION)
    static native long candidateArm(Pointer heap, int owner, long waitGeneration);
    @CFunction(value = "jam_vm_candidate_poll", transition = NO_TRANSITION)
    static native int candidatePoll(Pointer heap, long ticket, long waitGeneration);
    @CFunction(value = "jam_vm_candidate_disarm", transition = NO_TRANSITION)
    static native int candidateDisarm(Pointer heap, long ticket, long waitGeneration);
    @CFunction(value = "jam_vm_candidate_complete", transition = NO_TRANSITION)
    static native void candidateComplete(Pointer heap, long ticket, long waitGeneration);
    @CFunction(value = "jam_vm_candidate_epoch", transition = NO_TRANSITION)
    static native long candidateEpoch(Pointer heap);
}
