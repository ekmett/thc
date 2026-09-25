// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary

/** Original LP64 read/write/close/isatty/get_errno protocol over context descriptors, never host fds.
 * The current guest model runs each synchronous call on one host thread. This
 * error slot must migrate with guest-thread state before resumable scheduling. */
internal class ManagedStdio(private val files: ManagedFiles) {
    private val hostAbi by lazy { StdioHostAbi.load() }
    private val lastError = ThreadLocal.withInitial { 0L }

    @TruffleBoundary fun read(fd: Long, address: ManagedAddress, count: Long): Long {
        val abi = hostAbi
        if (fd != fd.toInt().toLong()) throw RuntimeFault("Original read requires a canonical signed CInt descriptor")
        val received = files.read(fd, address, count)
        if (received < 0) lastError.set(abi.error(files.errorKind()))
        return received
    }

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

    @TruffleBoundary fun close(fd: Long): Long {
        val abi = hostAbi
        if (fd != fd.toInt().toLong()) throw RuntimeFault("Original close requires a canonical signed CInt descriptor")
        val closed = files.close(fd)
        if (closed < 0) lastError.set(abi.error(files.errorKind()))
        return closed
    }

    @TruffleBoundary fun seek(fd: Long, displacement: Long, whence: Long): Long {
        val abi = hostAbi
        if (fd != fd.toInt().toLong() || whence != whence.toInt().toLong())
            throw RuntimeFault("Original seek requires canonical signed CInt descriptor and whence")
        val position = files.seek(fd, displacement, whence)
        if (position < 0) lastError.set(if (files.errorKind() == 7L) abi.notSeekable()
            else abi.error(files.errorKind()))
        return position
    }

    @TruffleBoundary fun truncate(fd: Long, length: Long): Long {
        val abi = hostAbi
        if (fd != fd.toInt().toLong())
            throw RuntimeFault("Original truncate requires a canonical signed CInt descriptor")
        val result = files.truncateOriginal(fd, length)
        if (result < 0) {
            val kind = files.errorKind()
            // Streams have no channel; POSIX ftruncate reports EINVAL here.
            lastError.set(abi.error(if (kind == 7L) 5 else kind))
        }
        return result
    }

    @TruffleBoundary fun isTerminal(fd: Long): Long {
        val abi = hostAbi
        if (fd != fd.toInt().toLong()) throw RuntimeFault("Original isatty requires a canonical signed CInt descriptor")
        val terminal = files.isTerminal(fd)
        if (terminal < 0) {
            lastError.set(abi.error(files.errorKind()))
            return 0L // POSIX isatty reports zero, not -1, for an invalid descriptor.
        }
        if (terminal == 0L) lastError.set(abi.notTerminal())
        return terminal
    }

    @TruffleBoundary fun dispose() { lastError.remove() }
}
