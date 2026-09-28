// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.RootNode;

/** Keep exceptional null diagnostics out of generic-call partial evaluation.
 * These checks do not outline dispatch, argument forcing, or guest execution. */
public final class ColdCallChecks {
    private ColdCallChecks() { }

    public static Object[] values(Object[] value) {
        if (value == null) throw missing(null);
        return value;
    }

    public static GuestRoot guestRoot(RootNode value) {
        if (value == null) throw missing("null cannot be cast to non-null type thc.runtime.GuestRoot");
        return (GuestRoot) value;
    }

    public static TypedInputLayout typedInput(TypedInputLayout value) {
        if (value == null) throw missing(null);
        return value;
    }

    public static RootCallTarget target(Object value) {
        if (value == null) throw missing("null cannot be cast to non-null type com.oracle.truffle.api.RootCallTarget");
        return (RootCallTarget) value;
    }

    public static TailCall transfer(Object value) {
        if (value == null) throw missing("null cannot be cast to non-null type thc.runtime.TailCall");
        return (TailCall) value;
    }

    @TruffleBoundary
    private static NullPointerException missing(String message) {
        return new NullPointerException(message);
    }
}
