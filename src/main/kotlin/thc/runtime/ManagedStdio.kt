// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.nodes.Node

/** Original LP64 read/write/close/isatty/get_errno protocol over context descriptors, never host fds.
 * The current guest model runs each synchronous call on one host thread. This
 * error slot must migrate with guest-thread state before resumable scheduling. */
internal class ManagedStdio(private val files: ManagedFiles) {
    private val hostAbi by lazy { StdioHostAbi.load() }
    private val lastError = ThreadLocal.withInitial { 0L }

    /** Native adapters capture errno in C immediately, before any other call.
     * Zero means success and must preserve the guest's sticky error slot. */
    internal fun nativeError(error: Long) {
        hostAbi
        if (error < 0 || error > Int.MAX_VALUE) fault("Invalid native errno")
        if (error != 0L) lastError.set(error)
    }
    /** The same original get_errno declaration observes a linked CAPI failure. */
    internal fun captureForeignErrno(errno: Long) { lastError.set(errno) }

    private fun fileError(abi: StdioHostAbi, seek: Boolean = false): Long {
        val native = files.nativeErrno()
        return if (native != 0L) native
            else if (seek && files.errorKind() == 7L) abi.notSeekable()
            else abi.error(files.errorKind())
    }

    @TruffleBoundary fun read(fd: Long, address: ManagedAddress, count: Long): Long {
        val abi = hostAbi
        if (fd != fd.toInt().toLong()) throw RuntimeFault("Original read requires a canonical signed CInt descriptor")
        val received = files.read(fd, address, count)
        if (received < 0) lastError.set(fileError(abi))
        return received
    }

    @TruffleBoundary fun write(fd: Long, address: ManagedAddress, count: Long): Long {
        val abi = hostAbi // Validate the host C ABI before any external effect.
        if (fd != fd.toInt().toLong()) throw RuntimeFault("Original write requires a canonical signed CInt descriptor")
        // A negative Long represents an unsigned size_t >= 2^63, outside every
        // managed allocation. ManagedFiles validates the entire range before IO.
        val written = files.write(fd, address, count)
        if (written < 0) lastError.set(fileError(abi))
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

    @TruffleBoundary fun seekConstant(operation: OriginalStdioOp): Long = hostAbi.seekConstant(operation)

    @TruffleBoundary fun flagConstant(operation: OriginalStdioOp): Long = hostAbi.flagConstant(operation)
    @TruffleBoundary fun siginfoSize(): Long = hostAbi.siginfoBytes

    @TruffleBoundary fun eventfd(initial: Long, flags: Long): Long {
        val abi = hostAbi
        if (initial != initial.toInt().toLong() || flags != flags.toInt().toLong())
            fault("Original eventfd requires canonical CInt operands")
        val result = files.eventfd(initial.toInt(), flags.toInt())
        if (result < 0) lastError.set(fileError(abi))
        return result
    }

    @TruffleBoundary fun pipe(destination: ManagedAddress): Long {
        val abi = hostAbi
        val result = files.pipe(destination)
        if (result < 0) lastError.set(fileError(abi))
        return result
    }

    @TruffleBoundary fun eventfdWrite(fd: Long, value: Long): Long {
        val abi = hostAbi
        if (fd != fd.toInt().toLong()) fault("Original eventfd_write requires a canonical CInt descriptor")
        val result = files.eventfdWrite(fd, value)
        if (result < 0) lastError.set(fileError(abi))
        return result
    }

    @TruffleBoundary fun poll(address: ManagedAddress, count: Long, timeout: Long, node: com.oracle.truffle.api.nodes.Node?): Long {
        val abi = hostAbi
        if (timeout != timeout.toInt().toLong()) fault("Original poll requires a canonical CInt timeout")
        val result = files.poll(address, count, timeout.toInt(), node)
        if (result < 0) lastError.set(fileError(abi))
        return result
    }

    @TruffleBoundary fun controlFd(operation: OriginalStdioOp, first: Long, second: Long) {
        hostAbi
        val fd = if (operation == OriginalStdioOp.IO_CONTROL_FD) second else first
        if (fd != fd.toInt().toLong() || fd < -1)
            fault("Event-manager control requires a canonical CInt descriptor or -1")
        val slot = if (operation == OriginalStdioOp.IO_CONTROL_FD) {
            if (first !in 0L..0xffff_ffffL) fault("Event-manager capability requires a canonical CUInt")
            first
        } else if (operation == OriginalStdioOp.IO_WAKEUP_FD) -1L
        else if (operation == OriginalStdioOp.TIMER_CONTROL_FD) -2L
        else fault("Invalid event-manager control operation")
        files.controlFd(slot, fd)
    }

    @TruffleBoundary fun epollCreate(size: Long): Long {
        val abi = hostAbi
        if (size != size.toInt().toLong()) fault("Original epoll_create requires a canonical CInt size")
        val result = files.epollCreate(size.toInt())
        if (result < 0) lastError.set(fileError(abi))
        return result
    }

    @TruffleBoundary fun epollControl(fd: Long, operation: Long, target: Long, event: ManagedAddress): Long {
        val abi = hostAbi
        if (fd != fd.toInt().toLong() || operation != operation.toInt().toLong() || target != target.toInt().toLong())
            fault("Original epoll_ctl requires canonical CInt operands")
        val result = files.epollControl(fd, operation.toInt(), target, event)
        if (result < 0) lastError.set(fileError(abi))
        return result
    }

    @TruffleBoundary fun epollWait(fd: Long, events: ManagedAddress, maximum: Long, timeout: Long,
        node: com.oracle.truffle.api.nodes.Node?): Long {
        val abi = hostAbi
        if (fd != fd.toInt().toLong() || maximum != maximum.toInt().toLong() || timeout != timeout.toInt().toLong())
            fault("Original epoll_wait requires canonical CInt operands")
        val result = files.epollWait(fd, events, maximum.toInt(), timeout.toInt(), node)
        if (result < 0) lastError.set(fileError(abi))
        return result
    }

    @TruffleBoundary fun fcntl(fd: Long, command: Long, argument: Long, write: Boolean): Long {
        val abi = hostAbi
        if (fd != fd.toInt().toLong() || command != command.toInt().toLong())
            fault("Original fcntl requires canonical signed CInt descriptor and command")
        val expected = abi.flagConstant(if (write) OriginalStdioOp.F_SETFL else OriginalStdioOp.F_GETFL)
        val descriptorFlags = write && command == abi.flagConstant(OriginalStdioOp.F_SETFD)
        if (command != expected && !descriptorFlags)
            fault("Original fcntl supports F_GETFL/F_SETFL/F_SETFD with the matching arity")
        val result = files.fcntl(fd, argument, write, descriptorFlags)
        if (result < 0) lastError.set(fileError(abi))
        return result
    }

    @TruffleBoundary @JvmOverloads fun ready(fd: Long, writing: Long, milliseconds: Long, socket: Long,
                                           node: Node? = null): Long {
        val abi = hostAbi
        if (fd != fd.toInt().toLong()) throw RuntimeFault("Original fdReady requires a canonical signed CInt descriptor")
        if (writing !in 0L..1L || socket !in 0L..1L)
            throw RuntimeFault("Original fdReady requires canonical CBool arguments")
        // isSock is ignored by the original POSIX implementation. Pipe/socket
        // readiness must retain the direction even though regular files don't.
        val ready = files.ready(fd, milliseconds, writing != 0L, node)
        if (ready < 0) lastError.set(fileError(abi))
        return ready
    }

    @TruffleBoundary fun close(fd: Long): Long {
        val abi = hostAbi
        if (fd != fd.toInt().toLong()) throw RuntimeFault("Original close requires a canonical signed CInt descriptor")
        val closed = files.close(fd)
        if (closed < 0) lastError.set(fileError(abi))
        return closed
    }

    @TruffleBoundary fun unlink(path: ManagedAddress): Long {
        val abi = hostAbi
        val result = files.unlinkOriginal(path)
        if (result < 0) lastError.set(fileError(abi))
        return result
    }

    @TruffleBoundary fun open(path: ManagedAddress, flags: Long, mode: Long,
        operation: OriginalStdioOp = OriginalStdioOp.OPEN, node: Node? = null): Long {
        val abi = hostAbi
        val result = files.openOriginal(path, flags, mode, operation, node)
        if (result < 0) lastError.set(fileError(abi))
        return result
    }

    @TruffleBoundary fun duplicate(fd: Long): Long {
        val abi = hostAbi
        if (fd != fd.toInt().toLong()) fault("Original dup requires a canonical signed CInt descriptor")
        val result = files.duplicate(fd)
        if (result < 0) lastError.set(fileError(abi))
        return result
    }

    @TruffleBoundary fun duplicateTo(fd: Long, target: Long): Long {
        val abi = hostAbi
        if (fd != fd.toInt().toLong() || target != target.toInt().toLong())
            fault("Original dup2 requires canonical signed CInt descriptors")
        val result = files.duplicateTo(fd, target)
        if (result < 0) lastError.set(fileError(abi))
        return result
    }

    @TruffleBoundary fun seek(fd: Long, displacement: Long, whence: Long): Long {
        val abi = hostAbi
        if (fd != fd.toInt().toLong() || whence != whence.toInt().toLong())
            throw RuntimeFault("Original seek requires canonical signed CInt descriptor and whence")
        val position = files.seek(fd, displacement, abi.seekMode(whence) ?: -1L)
        if (position < 0) lastError.set(fileError(abi, seek = true))
        return position
    }

    @TruffleBoundary fun truncate(fd: Long, length: Long): Long {
        val abi = hostAbi
        if (fd != fd.toInt().toLong())
            throw RuntimeFault("Original truncate requires a canonical signed CInt descriptor")
        val result = files.truncateOriginal(fd, length)
        if (result < 0) lastError.set(fileError(abi))
        return result
    }

    @TruffleBoundary fun pathStat(operation: OriginalStdioOp, path: ManagedAddress, destination: ManagedAddress): Long {
        check(operation.pathStat)
        val result = files.pathStatOriginal(path, destination, operation == OriginalStdioOp.STAT)
        if (result < 0) lastError.set(fileError(hostAbi))
        return result
    }

    @TruffleBoundary fun fstat(fd: Long, destination: ManagedAddress): Long {
        val abi = hostAbi
        if (fd != fd.toInt().toLong()) fault("Original fstat requires a canonical signed CInt descriptor")
        val result = files.fstat(fd, destination)
        if (result < 0) lastError.set(fileError(abi))
        return result
    }

    @TruffleBoundary fun tcsetattr(fd: Long, action: Long, source: ManagedAddress): Long {
        val abi = hostAbi
        if (fd != fd.toInt().toLong() || action != action.toInt().toLong())
            fault("Original tcsetattr requires canonical signed CInt descriptor and action")
        val result = files.tcsetattr(fd, action.toInt(), source)
        if (result < 0) lastError.set(fileError(abi))
        return result
    }

    @TruffleBoundary fun tcgetattr(fd: Long, destination: ManagedAddress): Long {
        val abi = hostAbi
        if (fd != fd.toInt().toLong()) fault("Original tcgetattr requires a canonical signed CInt descriptor")
        val result = files.tcgetattr(fd, destination)
        if (result < 0) lastError.set(fileError(abi))
        return result
    }

    @TruffleBoundary fun isTerminal(fd: Long): Long {
        val abi = hostAbi
        if (fd != fd.toInt().toLong()) throw RuntimeFault("Original isatty requires a canonical signed CInt descriptor")
        val terminal = files.isTerminal(fd)
        if (terminal < 0) {
            lastError.set(fileError(abi))
            return 0L // POSIX isatty reports zero, not -1, for an invalid descriptor.
        }
        if (terminal == 0L) lastError.set(abi.notTerminal())
        return terminal
    }

    @TruffleBoundary fun dispose() { lastError.remove() }
}
