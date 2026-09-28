// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import thc.Language;

/** Original stack/IPE service boundary: managed diagnostic images, not continuations. */
public final class ManagedStackRuntime {
    private ManagedStackRuntime() {}
    @TruffleBoundary public static ManagedAddress stackInfo(Object snapshot, TargetLayout layout) {
        return Language.currentState().getStackSnapshots().stackInfo(snapshot, layout);
    }
    @TruffleBoundary public static ManagedStackFrameInfo frameInfo(Object snapshot, long offset, TargetLayout layout) {
        return Language.currentState().getStackSnapshots().frameInfo(snapshot, offset, layout);
    }
    @TruffleBoundary public static long stackFields(Object snapshot, TargetLayout layout) {
        return Language.currentState().getStackSnapshots().stackFields(snapshot, layout);
    }
    @TruffleBoundary public static ManagedStackBitmap smallBitmap(Object snapshot, long offset, TargetLayout layout) {
        return Language.currentState().getStackSnapshots().smallBitmap(snapshot, offset, layout);
    }
    @TruffleBoundary public static long word(Object snapshot, long offset, TargetLayout layout) {
        return Language.currentState().getStackSnapshots().word(snapshot, offset, layout);
    }
    @TruffleBoundary public static ManagedStackAdvance advance(Object snapshot, long offset, TargetLayout layout) {
        return Language.currentState().getStackSnapshots().advance(snapshot, offset, layout);
    }
    @TruffleBoundary public static Void incompatibleGetter(OriginalStackInfoOp operation, Object snapshot, long offset, TargetLayout layout) {
        return Language.currentState().getStackSnapshots().incompatibleGetter(operation, snapshot, offset, layout);
    }
    @TruffleBoundary public static long lookupIpe(ManagedAddress key, ManagedAddress destination, TargetLayout layout) {
        return Language.currentState().getStackSnapshots().lookupIpe(key, destination, layout);
    }
}
