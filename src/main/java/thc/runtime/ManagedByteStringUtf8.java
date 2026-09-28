// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import thc.Language;

/** Synchronous read-only borrowing; the original C validator never gets an
 * unowned native pointer or a writable view, including for malloc storage. */
final class ManagedByteStringUtf8 {
    private ManagedByteStringUtf8() {}

    @TruffleBoundary
    public static long validate(ManagedAddress address, long length) {
        var state = Language.currentState(null);
        var cbits = state.cbits();
        // Language loading may block; do it before taking storage locks.
        Object function = cbits.utf8Function$org_intelligence_thc();
        // The original function returns true before inspecting the pointer.
        if (address == ManagedAddress.Companion.nullAddress() && length == 0)
            return call(state, cbits, function, address, 0L, length);
        var allocation = address.nativeAllocation$org_intelligence_thc();
        if (allocation != null) {
            try (var borrow = allocation.borrow()) {
                address.requireByteRegion$org_intelligence_thc(length, false);
                return view(state, cbits, function, address,
                        borrow.segment().asSlice(address.cbitsOffset$org_intelligence_thc()).asByteBuffer(), 0, length);
            }
        }
        var owner = address.cbitsOwner$org_intelligence_thc();
        if (owner != null) {
            synchronized (owner) {
                return managed(state, cbits, function, address, length);
            }
        }
        return managed(state, cbits, function, address, length);
    }

    private static long managed(Language.State state, SulongCbits cbits, Object function, ManagedAddress address, long length) {
        address.requireByteRegion$org_intelligence_thc(length, false);
        return view(state, cbits, function, address, address.cbitsBuffer$org_intelligence_thc(),
                address.cbitsOffset$org_intelligence_thc(), length);
    }

    private static long view(Language.State state, SulongCbits cbits, Object function, ManagedAddress address,
            ByteBuffer buffer, long offset, long length) {
        return call(state, cbits, function, address,
                new CbitsBuffer(buffer.asReadOnlyBuffer(), false, () -> offset + length, offset), length);
    }

    private static long call(Language.State state, SulongCbits cbits, Object function, ManagedAddress address,
            Object bytes, long length) {
        var threads = state.getThreads();
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try { return cbits.utf8Validate$org_intelligence_thc(function, bytes, length); }
        finally {
            threads.leaveForeign(previous);
            Reference.reachabilityFence(address);
            Reference.reachabilityFence(bytes);
        }
    }
}
