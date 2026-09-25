// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.interop.InteropLibrary

/** Original GHC's POSIX base_strerror_r over a bounded native scratch buffer.
 * Native pointers are never guest Addr# values; only the terminating message
 * bytes are copied back to the caller's checked mutable allocation. */
internal class ManagedStrerror(private val cbits: () -> SulongCbits, private val threads: GuestThreads) {
    private val interop = InteropLibrary.getUncached()

    @TruffleBoundary fun call(error: Long, output: ManagedAddress, length: Long): Long {
        if (error != error.toInt().toLong()) fault("strerror requires a CInt error number")
        // The original wrapper ellipsizes ERANGE using buflen-4. Its installed
        // Haskell caller supplies 512; reject unsafe or unbounded other sizes.
        if (length !in 4L..65536L) fault("strerror output length outside supported range")
        output.requireByteRegion(length, writable = true)
        val library = cbits().strerrorLibrary()
        val invoke = {
            output.requireByteRegion(length, writable = true)
            NativeLimbScope().use { scope ->
                val native = scope.allocate((length + 7L) and -8L)
                val previous = threads.enterForeign()
                val status = try {
                    interop.execute(interop.readMember(library, "base_strerror_r"), error.toInt(), native, length)
                } finally { threads.leaveForeign(previous) }
                if (!interop.fitsInInt(status)) fault("Invalid native strerror CInt result")
                val result = interop.asInt(status)
                if (result == 0) {
                    val bytes = ByteArray(length.toInt())
                    native.copyTo(bytes, 0, bytes.size)
                    val terminator = bytes.indexOf(0)
                    if (terminator < 0) fault("Native strerror omitted its terminator")
                    for (i in 0..terminator) output.writeWord8(i.toLong(), bytes[i].toLong())
                }
                result.toLong()
            }
        }
        // A concurrent shrink cannot invalidate preflight or copyback.
        val owner = output.cbitsOwner()
        return if (owner == null) invoke() else synchronized(owner) { invoke() }
    }
}
