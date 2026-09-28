// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.nio.ByteOrder;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Checked ABI for the original pinned GHC MD5 C bitcode. All preconditions
 * remain before the first C store; context bytes remain the entire MD5 state. */
public final class ManagedMd5 {
    private ManagedMd5() {}
    private static final long CONTEXT_SIZE = 88;
    private static final long INPUT = 24;
    private static final long CONTEXT_ALIGNMENT = 4;

    private static void requireContext(ManagedAddress context) {
        context.requireRange(0, CONTEXT_SIZE, true);
        // GHC's MD5Context has C alignment 4. Check the logical offset before a C cast or store.
        if (context.cbitsOffset$org_intelligence_thc() % CONTEXT_ALIGNMENT != 0)
            throw fault("MD5 context address is not 4-byte aligned");
    }

    @TruffleBoundary
    public static void init(ManagedAddress context) {
        requireContext(context);
        var threads = Language.currentState(null).getThreads$org_intelligence_thc();
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try { Language.currentState(null).cbits$org_intelligence_thc().init(context); }
        finally { threads.leaveForeign(previous); }
    }

    @TruffleBoundary
    public static void update(ManagedAddress context, ManagedAddress input, long length) {
        if (length < 0 || length > Integer.MAX_VALUE) throw fault("MD5Update length outside nonnegative CInt domain");
        requireContext(context);
        input.requireRange(0, length, false);
        int previousCount = readWord(context, 16);
        long space = 64 - ((long) previousCount & 63);
        // Validate every planned memcpy before updating the count.
        long source = 0;
        long destination = INPUT + 64 - space;
        long chunk = Math.min(space, length);
        while (true) {
            if (context.overlaps(destination, chunk, input, source, chunk))
                throw fault("MD5Update overlapping memcpy regions");
            source += chunk;
            if (source == length) break;
            destination = INPUT;
            chunk = Math.min(64, length - source);
        }
        var threads = Language.currentState(null).getThreads$org_intelligence_thc();
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try { Language.currentState(null).cbits$org_intelligence_thc().update(context, input, (int) length); }
        finally { threads.leaveForeign(previous); }
    }

    @TruffleBoundary
    public static void finish(ManagedAddress output, ManagedAddress context) {
        requireContext(context);
        output.requireRange(0, 16, true);
        // C copies ctx->buf, then clears the entire context. Other overlap is defined and cleared too.
        if (context.overlaps(0, 16, output, 0, 16)) throw fault("MD5Final overlapping memcpy regions");
        var threads = Language.currentState(null).getThreads$org_intelligence_thc();
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try { Language.currentState(null).cbits$org_intelligence_thc().finish(output, context); }
        finally { threads.leaveForeign(previous); }
    }

    private static int readWord(ManagedAddress address, long offset) {
        int value = 0;
        for (int index = 0; index < 4; index++) {
            int shift = 8 * (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? index : 3 - index);
            value |= (int) address.readWord8(offset + index) << shift;
        }
        return value;
    }
}
