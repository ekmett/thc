// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.interop.InteropLibrary

/** Original GHC's POSIX base_strerror_r over a bounded native scratch buffer.
 * Native pointers are never guest Addr# values; the full caller buffer is
 * copied back, including bytes written before an ordinary error return. */
internal class ManagedStrerror(private val cbits: () -> SulongCbits, private val threads: GuestThreads) {
    private val interop = InteropLibrary.getUncached()

    @TruffleBoundary fun call(error: Long, output: ManagedAddress, length: Long): Long {
        if (error != error.toInt().toLong()) fault("strerror requires a CInt error number")
        // The original wrapper ellipsizes ERANGE using buflen-4. Its installed
        // Haskell caller supplies 512; reject unsafe or unbounded other sizes.
        if (length !in 4L..65536L) fault("strerror output length outside supported range")
        output.requireByteRegion(length, writable = true)
        val nativeCode = cbits()
        val library = nativeCode.strerrorLibrary()
        val localeLibrary = nativeCode.strerrorLocaleLibrary()
        val original = interop.readMember(library, "base_strerror_r")
        val enterLocale = interop.readMember(localeLibrary, "thc_strerror_locale_enter")
        val leaveLocale = interop.readMember(localeLibrary, "thc_strerror_locale_leave")
        val invoke = {
            output.requireByteRegion(length, writable = true)
            NativeLimbScope().use { scope ->
                val bytes = ByteArray(length.toInt()) { output.readWord8(it.toLong()).toByte() }
                val native = scope.allocate((length + 7L) and -8L)
                native.copyFrom(bytes, 0, bytes.size)
                // Keep the whole native locale/call/restore sequence outside
                // guest delivery. Hard host cancellation inside C is not an
                // unwindable guest safepoint.
                val previous = threads.enterForeign()
                val status = try {
                    val locale = interop.execute(enterLocale)
                    if (interop.isNull(locale)) fault("Native strerror message locale unavailable")
                    try {
                        interop.execute(original, error.toInt(), native, length)
                    } finally {
                        val restored = interop.execute(leaveLocale, locale)
                        if (!interop.fitsInInt(restored) || interop.asInt(restored) != 1)
                            fault("Native strerror message locale restore failed")
                    }
                } finally { threads.leaveForeign(previous) }
                if (!interop.fitsInInt(status)) fault("Invalid native strerror CInt result")
                val result = interop.asInt(status)
                native.copyTo(bytes, 0, bytes.size)
                for (i in bytes.indices) output.writeWord8(i.toLong(), bytes[i].toLong())
                result.toLong()
            }
        }
        // A concurrent shrink cannot invalidate preflight or copyback.
        val owner = output.cbitsOwner()
        return if (owner == null) invoke() else synchronized(owner) { invoke() }
    }
}
