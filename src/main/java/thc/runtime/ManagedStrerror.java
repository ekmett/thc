// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import java.util.function.Supplier;
import static thc.runtime.RuntimeFault.fault;

/** Original GHC's POSIX base_strerror_r over a bounded native scratch buffer.
 * Native pointers are never guest Addr# values; the full caller buffer is
 * copied back, including bytes written before an ordinary error return. */
public final class ManagedStrerror {
    private final Supplier<SulongCbits> cbits;
    private final GuestThreads threads;
    private final InteropLibrary interop = InteropLibrary.getUncached();
    public ManagedStrerror(Supplier<SulongCbits> cbits, GuestThreads threads) { this.cbits = cbits; this.threads = threads; }
    @TruffleBoundary public long call(long error, ManagedAddress output, long length) {
        if (error != (long) (int) error) throw fault("strerror requires a CInt error number");
        // The original wrapper ellipsizes ERANGE using buflen-4.
        if (length < 4 || length > 65536) throw fault("strerror output length outside supported range");
        // Keep an owned malloc output live through provider loading, C and copyback.
        var allocation = output.nativeAllocation$org_intelligence_thc();
        try (var loan = allocation == null ? null : allocation.borrow()) {
            return copyMessage(error, output, length);
        }
    }
    private long copyMessage(long error, ManagedAddress output, long length) {
        output.requireByteRegion$org_intelligence_thc(length, true);
        var nativeCode = cbits.get();
        var library = nativeCode.strerrorLibrary$org_intelligence_thc();
        var localeLibrary = nativeCode.strerrorLocaleLibrary$org_intelligence_thc();
        try {
            var original = interop.readMember(library, "base_strerror_r");
            var enterLocale = interop.readMember(localeLibrary, "thc_strerror_locale_enter");
            var leaveLocale = interop.readMember(localeLibrary, "thc_strerror_locale_leave");
            // A concurrent shrink cannot invalidate preflight or copyback.
            var owner = output.cbitsOwner$org_intelligence_thc();
            if (owner == null) return invoke(error, output, length, original, enterLocale, leaveLocale);
            synchronized (owner) { return invoke(error, output, length, original, enterLocale, leaveLocale); }
        } catch (InteropException failure) { throw propagate(failure); }
    }
    private long invoke(long error, ManagedAddress output, long length, Object original, Object enterLocale, Object leaveLocale)
        throws InteropException {
        output.requireByteRegion$org_intelligence_thc(length, true);
        try (var scope = new NativeLimbScope()) {
            var bytes = new byte[(int) length];
            for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) output.readWord8(i);
            var nativeBuffer = scope.allocate((length + 7L) & -8L);
            nativeBuffer.copyFrom(bytes, 0, bytes.length);
            // Keep the whole native locale/call/restore sequence outside guest delivery.
            var previous = threads.enterForeign(ForeignSafety.UNSAFE);
            Object status;
            try {
                var locale = interop.execute(enterLocale);
                if (interop.isNull(locale)) throw fault("Native strerror message locale unavailable");
                try { status = interop.execute(original, (int) error, nativeBuffer, length); }
                finally {
                    var restored = interop.execute(leaveLocale, locale);
                    if (!interop.fitsInInt(restored) || interop.asInt(restored) != 1)
                        throw fault("Native strerror message locale restore failed");
                }
            } finally { threads.leaveForeign(previous); }
            if (!interop.fitsInInt(status)) throw fault("Invalid native strerror CInt result");
            int result = interop.asInt(status);
            nativeBuffer.copyTo(bytes, 0, bytes.length);
            for (int i = 0; i < bytes.length; i++) output.writeWord8(i, bytes[i]);
            return result;
        }
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
