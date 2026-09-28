// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import java.io.IOException;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Original stdio protocol over context descriptors, never host fds. The Windows
 * descriptor receipt supplies CRT constants without admitting POSIX FCalls.
 * The current guest model runs each synchronous call on one host thread. This
 * error slot must migrate with guest-thread state before resumable scheduling. */
public final class ManagedStdio {
    private final ManagedFiles files;
    private final Object hostAbiLock = new Object();
    private volatile StdioHostAbi hostAbi;
    private final ThreadLocal<Long> lastError = ThreadLocal.withInitial(() -> 0L);

    public ManagedStdio(ManagedFiles files) { this.files = files; }

    private StdioHostAbi hostAbi() {
        var abi = hostAbi;
        if (abi == null) {
            synchronized (hostAbiLock) {
                abi = hostAbi;
                if (abi == null) {
                    try { hostAbi = abi = StdioHostAbi.load(); }
                    catch (IOException failure) { throw ManagedStdio.<RuntimeException>propagate(failure); }
                }
            }
        }
        return abi;
    }
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }

    /** Native adapters capture errno in C immediately, before any other call.
     * Zero means success and must preserve the guest's sticky error slot. */
    public void nativeError(long error) {
        if (WindowsDirectoryStreams.supportedHost()) WindowsCodePages.Abi.requireLayout(); else hostAbi();
        if (error < 0 || error > Integer.MAX_VALUE) throw fault("Invalid native errno");
        if (error != 0) lastError.set(error);
    }
    /** The same original get_errno declaration observes a linked CAPI failure. */
    public void captureForeignErrno(long errno) { lastError.set(errno); }

    private long fileError(StdioHostAbi abi) { return fileError(abi, false); }
    private long fileError(StdioHostAbi abi, boolean seek) {
        long nativeError = files.nativeErrno();
        return nativeError != 0 ? nativeError : seek && files.errorKind() == 7L ? abi.notSeekable() : abi.error(files.errorKind());
    }

    @TruffleBoundary public long read(long fd, ManagedAddress address, long count) {
        var abi = hostAbi();
        if (fd != (long) (int) fd) throw new RuntimeFault("Original read requires a canonical signed CInt descriptor");
        long received = files.read(fd, address, count, ForeignSafety.UNSAFE);
        if (received < 0) lastError.set(fileError(abi));
        return received;
    }
    @TruffleBoundary public long write(long fd, ManagedAddress address, long count) {
        var abi = hostAbi(); // Validate the host C ABI before any external effect.
        if (fd != (long) (int) fd) throw new RuntimeFault("Original write requires a canonical signed CInt descriptor");
        // Negative size_t values exceed every managed allocation. Validate the entire range before IO.
        long written = files.write(fd, address, count, ForeignSafety.UNSAFE);
        if (written < 0) lastError.set(fileError(abi));
        else if (count != 0 && written == 0) {
            // GHC's original write loop would otherwise spin without progress.
            lastError.set(abi.error(6));
            return -1L;
        }
        return written;
    }
    @TruffleBoundary public long errno() {
        if (WindowsDirectoryStreams.supportedHost()) WindowsCodePages.Abi.requireLayout(); else hostAbi();
        return lastError.get();
    }
    @TruffleBoundary public ManagedAddress openDirectory(ManagedAddress path) {
        var abi = hostAbi();
        var result = files.openDirectoryOriginal(path);
        if (result == ManagedAddress.nullAddress()) lastError.set(fileError(abi));
        return result;
    }
    @TruffleBoundary public ManagedAddress openDirectoryFd(long fd) {
        var abi = hostAbi();
        if (fd != (long) (int) fd) throw fault("Original fdopendir requires a canonical signed CInt");
        var result = files.openDirectoryDescriptor(fd);
        if (result == ManagedAddress.nullAddress()) lastError.set(fileError(abi));
        return result;
    }
    /** Zero explicitly clears the slot; every signed CInt is otherwise preserved. */
    @TruffleBoundary public void setErrno(long value) {
        if (WindowsDirectoryStreams.supportedHost()) WindowsCodePages.Abi.requireLayout(); else hostAbi();
        if (value != (long) (int) value) throw fault("Original set_errno requires a canonical signed CInt");
        lastError.set(value);
    }
    @TruffleBoundary public long seekConstant(OriginalStdioOp operation) { return hostAbi().seekConstant(operation); }
    @TruffleBoundary public long flagConstant(OriginalStdioOp operation) { return hostAbi().flagConstant(operation); }
    @TruffleBoundary public long siginfoSize() { return hostAbi().getSiginfoBytes(); }

    @TruffleBoundary public long eventfd(long initial, long flags) {
        var abi = hostAbi();
        if (initial != (long) (int) initial || flags != (long) (int) flags) throw fault("Original eventfd requires canonical CInt operands");
        long result = files.eventfd((int) initial, (int) flags);
        if (result < 0) lastError.set(fileError(abi));
        return result;
    }
    @TruffleBoundary public long pipe(ManagedAddress destination) {
        var abi = hostAbi();
        long result = files.pipe(destination);
        if (result < 0) lastError.set(fileError(abi));
        return result;
    }
    @TruffleBoundary public long eventfdWrite(long fd, long value) {
        var abi = hostAbi();
        if (fd != (long) (int) fd) throw fault("Original eventfd_write requires a canonical CInt descriptor");
        long result = files.eventfdWrite(fd, value);
        if (result < 0) lastError.set(fileError(abi));
        return result;
    }
    @TruffleBoundary public long poll(ManagedAddress address, long count, long timeout, Node node) {
        var abi = hostAbi();
        if (timeout != (long) (int) timeout) throw fault("Original poll requires a canonical CInt timeout");
        long result = files.poll(address, count, (int) timeout, node);
        if (result < 0) lastError.set(fileError(abi));
        return result;
    }
    @TruffleBoundary public void controlFd(OriginalStdioOp operation, long first, long second) {
        hostAbi();
        long fd = operation == OriginalStdioOp.IO_CONTROL_FD ? second : first;
        if (fd != (long) (int) fd || fd < -1) throw fault("Event-manager control requires a canonical CInt descriptor or -1");
        long slot;
        if (operation == OriginalStdioOp.IO_CONTROL_FD) {
            if (first < 0 || first > 0xffff_ffffL) throw fault("Event-manager capability requires a canonical CUInt");
            slot = first;
        } else if (operation == OriginalStdioOp.IO_WAKEUP_FD) slot = -1L;
        else if (operation == OriginalStdioOp.TIMER_CONTROL_FD) slot = -2L;
        else throw fault("Invalid event-manager control operation");
        files.controlFd(slot, fd);
    }
    @TruffleBoundary public long epollCreate(long size) {
        var abi = hostAbi();
        if (size != (long) (int) size) throw fault("Original epoll_create requires a canonical CInt size");
        long result = files.epollCreate((int) size);
        if (result < 0) lastError.set(fileError(abi));
        return result;
    }
    @TruffleBoundary public long epollControl(long fd, long operation, long target, ManagedAddress event) {
        var abi = hostAbi();
        if (fd != (long) (int) fd || operation != (long) (int) operation || target != (long) (int) target)
            throw fault("Original epoll_ctl requires canonical CInt operands");
        long result = files.epollControl(fd, (int) operation, target, event);
        if (result < 0) lastError.set(fileError(abi));
        return result;
    }
    @TruffleBoundary public long epollWait(long fd, ManagedAddress events, long maximum, long timeout, Node node) {
        var abi = hostAbi();
        if (fd != (long) (int) fd || maximum != (long) (int) maximum || timeout != (long) (int) timeout)
            throw fault("Original epoll_wait requires canonical CInt operands");
        long result = files.epollWait(fd, events, (int) maximum, (int) timeout, node);
        if (result < 0) lastError.set(fileError(abi));
        return result;
    }
    @TruffleBoundary public long fcntl(long fd, long command, long argument, boolean write) {
        var abi = hostAbi();
        if (fd != (long) (int) fd || command != (long) (int) command) throw fault("Original fcntl requires canonical signed CInt descriptor and command");
        long expected = abi.flagConstant(write ? OriginalStdioOp.F_SETFL : OriginalStdioOp.F_GETFL);
        boolean descriptorFlags = write && command == abi.flagConstant(OriginalStdioOp.F_SETFD);
        if (command != expected && !descriptorFlags) throw fault("Original fcntl supports F_GETFL/F_SETFL/F_SETFD with the matching arity");
        long result = files.fcntl(fd, argument, write, descriptorFlags);
        if (result < 0) lastError.set(fileError(abi));
        return result;
    }
    @TruffleBoundary public long ready(long fd, long writing, long milliseconds, long socket) { return ready(fd, writing, milliseconds, socket, null); }
    @TruffleBoundary public long ready(long fd, long writing, long milliseconds, long socket, Node node) {
        var abi = hostAbi();
        if (fd != (long) (int) fd) throw new RuntimeFault("Original fdReady requires a canonical signed CInt descriptor");
        if (writing < 0 || writing > 1 || socket < 0 || socket > 1) throw new RuntimeFault("Original fdReady requires canonical CBool arguments");
        // POSIX ignores isSock, but pipe/socket readiness retains the direction.
        long ready = files.ready(fd, milliseconds, writing != 0, node);
        if (ready < 0) lastError.set(fileError(abi));
        return ready;
    }
    @TruffleBoundary public long close(long fd) {
        var abi = hostAbi();
        if (fd != (long) (int) fd) throw new RuntimeFault("Original close requires a canonical signed CInt descriptor");
        long closed = files.close(fd, ForeignSafety.UNSAFE);
        if (closed < 0) lastError.set(fileError(abi));
        return closed;
    }
    @TruffleBoundary public long changeDirectory(ManagedAddress path) {
        var abi = hostAbi();
        long result = files.changeDirectoryOriginal(path);
        if (result < 0) lastError.set(fileError(abi));
        return result;
    }
    @TruffleBoundary public ManagedAddress currentDirectory(ManagedAddress output, long capacity) {
        var abi = hostAbi();
        long result = files.currentDirectoryOriginal(output, capacity);
        if (result < 0) { lastError.set(fileError(abi)); return ManagedAddress.nullAddress(); }
        return output;
    }
    @TruffleBoundary public long symlink(ManagedAddress target, ManagedAddress path) {
        long result = files.symlinkOriginal(target, path);
        if (result < 0) lastError.set(fileError(hostAbi()));
        return result;
    }
    @TruffleBoundary public long readlink(ManagedAddress path, ManagedAddress output, long capacity) {
        long result = files.readlinkOriginal(path, output, capacity);
        if (result < 0) lastError.set(fileError(hostAbi()));
        return result;
    }
    @TruffleBoundary public long statAt(long fd, ManagedAddress path, ManagedAddress destination, long flags) {
        var abi = hostAbi();
        if (fd != (long) (int) fd || flags != (long) (int) flags) throw fault("Original fstatat requires canonical signed CInt descriptor and flags");
        long result = files.statAtOriginal(fd, path, destination, (int) flags, abi.getAtFdcwd());
        if (result < 0) lastError.set(fileError(abi));
        return result;
    }
    @TruffleBoundary public long removeDirectory(ManagedAddress path) { return unlinkAt(hostAbi().getAtFdcwd(), path, hostAbi().getAtRemoveDir()); }
    @TruffleBoundary public long unlinkAt(long fd, ManagedAddress path, long flags) {
        var abi = hostAbi();
        if (fd != (long) (int) fd || flags != (long) (int) flags) throw fault("Original unlinkat requires canonical signed CInt descriptor and flags");
        long result = files.unlinkAtOriginal(fd, path, (int) flags, abi.getAtFdcwd(), abi.getAtRemoveDir());
        if (result < 0) lastError.set(fileError(abi));
        return result;
    }
    @TruffleBoundary public long access(ManagedAddress path, long mode) {
        if (mode != (long) (int) mode) throw fault("Original access requires a canonical signed CInt mode");
        long result = files.accessOriginal(path, (int) mode);
        if (result < 0) lastError.set(fileError(hostAbi()));
        return result;
    }
    @TruffleBoundary public long pathMode(OriginalStdioOp operation, ManagedAddress path, long mode) {
        if (!operation.getPathMode()) throw new IllegalStateException("Check failed.");
        if (mode < 0 || mode > 0xffff_ffffL) throw fault("Original pathname mode requires a canonical CMode");
        long result = files.pathModeOriginal(path, mode, operation == OriginalStdioOp.MKDIR);
        if (result < 0) lastError.set(fileError(hostAbi()));
        return result;
    }
    @TruffleBoundary public long unlink(ManagedAddress path) {
        var abi = hostAbi();
        long result = files.unlinkOriginal(path);
        if (result < 0) lastError.set(fileError(abi));
        return result;
    }
    @TruffleBoundary public long open(ManagedAddress path, long flags, long mode) {
        return open(path, flags, mode, OriginalStdioOp.OPEN, null);
    }
    @TruffleBoundary public long open(ManagedAddress path, long flags, long mode, OriginalStdioOp operation) {
        return open(path, flags, mode, operation, null);
    }
    @TruffleBoundary public long open(ManagedAddress path, long flags, long mode, OriginalStdioOp operation, Node node) {
        var abi = hostAbi();
        long result = files.openOriginal(path, flags, mode, operation, node);
        if (result < 0) lastError.set(fileError(abi));
        return result;
    }
    @TruffleBoundary public long duplicate(long fd) {
        var abi = hostAbi();
        if (fd != (long) (int) fd) throw fault("Original dup requires a canonical signed CInt descriptor");
        long result = files.duplicate(fd);
        if (result < 0) lastError.set(fileError(abi));
        return result;
    }
    @TruffleBoundary public long duplicateTo(long fd, long target) {
        var abi = hostAbi();
        if (fd != (long) (int) fd || target != (long) (int) target) throw fault("Original dup2 requires canonical signed CInt descriptors");
        long result = files.duplicateTo(fd, target);
        if (result < 0) lastError.set(fileError(abi));
        return result;
    }
    @TruffleBoundary public long seek(long fd, long displacement, long whence) {
        var abi = hostAbi();
        if (fd != (long) (int) fd || whence != (long) (int) whence) throw new RuntimeFault("Original seek requires canonical signed CInt descriptor and whence");
        var mode = abi.seekMode(whence);
        long position = files.seek(fd, displacement, mode == null ? -1L : mode, ForeignSafety.UNSAFE);
        if (position < 0) lastError.set(fileError(abi, true));
        return position;
    }
    @TruffleBoundary public long truncate(long fd, long length) {
        var abi = hostAbi();
        if (fd != (long) (int) fd) throw new RuntimeFault("Original truncate requires a canonical signed CInt descriptor");
        long result = files.truncateOriginal(fd, length);
        if (result < 0) lastError.set(fileError(abi));
        return result;
    }
    @TruffleBoundary public long pathStat(OriginalStdioOp operation, ManagedAddress path, ManagedAddress destination) {
        if (!operation.getPathStat()) throw new IllegalStateException("Check failed.");
        long result = files.pathStatOriginal(path, destination, operation == OriginalStdioOp.STAT);
        if (result < 0) lastError.set(fileError(hostAbi()));
        return result;
    }
    @TruffleBoundary public long fstat(long fd, ManagedAddress destination) {
        var abi = hostAbi();
        if (fd != (long) (int) fd) throw fault("Original fstat requires a canonical signed CInt descriptor");
        long result = files.fstat(fd, destination);
        if (result < 0) lastError.set(fileError(abi));
        return result;
    }
    @TruffleBoundary public long tcsetattr(long fd, long action, ManagedAddress source) {
        var abi = hostAbi();
        if (fd != (long) (int) fd || action != (long) (int) action) throw fault("Original tcsetattr requires canonical signed CInt descriptor and action");
        long result = files.tcsetattr(fd, (int) action, source);
        if (result < 0) lastError.set(fileError(abi));
        return result;
    }
    @TruffleBoundary public long tcgetattr(long fd, ManagedAddress destination) {
        var abi = hostAbi();
        if (fd != (long) (int) fd) throw fault("Original tcgetattr requires a canonical signed CInt descriptor");
        long result = files.tcgetattr(fd, destination);
        if (result < 0) lastError.set(fileError(abi));
        return result;
    }
    @TruffleBoundary public long isTerminal(long fd) {
        var abi = hostAbi();
        if (fd != (long) (int) fd) throw new RuntimeFault("Original isatty requires a canonical signed CInt descriptor");
        long terminal = files.isTerminal(fd, ForeignSafety.UNSAFE);
        if (terminal < 0) {
            lastError.set(fileError(abi));
            return 0L; // POSIX isatty reports zero, not -1, for an invalid descriptor.
        }
        if (terminal == 0) lastError.set(abi.notTerminal());
        return terminal;
    }
    @TruffleBoundary public void dispose() { lastError.remove(); }
}
