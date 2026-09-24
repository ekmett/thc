// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary

/** Original LP64 write/get_errno protocol over context descriptors, never host fd1.
 * The current guest model runs each synchronous call on one host thread. This
 * error slot must migrate with guest-thread state before resumable scheduling. */
internal class ManagedStdio(private val files: ManagedFiles) {
    private val hostAbi by lazy { StdioHostAbi.load() }
    private val lastError = ThreadLocal.withInitial { 0L }

    @TruffleBoundary fun write(fd: Long, address: ManagedAddress, count: Long): Long {
        val abi = hostAbi // Validate the host C ABI before any external effect.
        if (fd != fd.toInt().toLong()) throw RuntimeFault("Original write requires a canonical signed CInt descriptor")
        // A negative Long represents an unsigned size_t >= 2^63, outside every
        // managed allocation. ManagedFiles validates the entire range before IO.
        val written = files.write(fd, address, count)
        if (written < 0) lastError.set(abi.error(files.errorKind()))
        else if (count != 0L && written == 0L) {
            // GHC's original write loop would otherwise spin without progress.
            lastError.set(abi.error(6))
            return -1L
        }
        return written
    }

    @TruffleBoundary fun errno(): Long {
        hostAbi
        return lastError.get()
    }

    @TruffleBoundary fun dispose() { lastError.remove() }
}
