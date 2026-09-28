// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Preserve typed addresses and numeric carriers; validate State before effects. */
final class OriginalStdioExpression extends Expr {
    private final OriginalStdioOp operation;
    @Children private Expr[] operands;
    @CompilerDirectives.CompilationFinal(dimensions = 1) private final NarrowInteger[] argumentIntegers;

    OriginalStdioExpression(OriginalStdioOp operation, Expr[] operands, CoreRepresentation proof) {
        this.operation = operation;
        this.operands = operands;
        setRepresentation(new CoreRepresentation(proof.getKind(), true, proof.getPresent(),
            proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(),
            proof.getTagSlot(), proof.getAlternativeSlots()));
        argumentIntegers = new NarrowInteger[operation.getArguments().size()];
        for (int index = 0; index < argumentIntegers.length; index++)
            argumentIntegers[index] = NarrowInteger.fromRep(operation.getArguments().get(index));
    }
    @Override public Object execute(VirtualFrame frame) {
        throw fault("Original stdio call requires a State/result tuple destination");
    }
    /** The managed OS service API is machine-wide; only this declared foreign
     * boundary widens narrow guest carriers (including unsigned Word32). */
    private long readInteger(VirtualFrame frame, int index) {
        var integer = argumentIntegers[index];
        return integer == null ? operands[index].executeRequiredLong(frame) : integer.widen(operands[index].executeRequiredInt(frame));
    }
    private void writeInteger(VirtualFrame frame, int slot, long value) {
        if (operation.getNarrowResult() == null) FrameAccess.writeLong(frame, slot, value);
        else FrameAccess.writeInt(frame, slot, (int) value);
    }
    private static final class ResumeCompleted implements AstResumeStep {
        private static final ResumeCompleted INSTANCE = new ResumeCompleted();
        @Override public Object resume(VirtualFrame frame, Object input) {
            if (input != thc.runtime.Unit.INSTANCE) throw fault("Original pathname call continuation expected a completed async poll");
            return null;
        }
    }

    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        if (operation.getWindowsEncoding()) {
            var windows = WindowsCodePages.current(this);
            if (operation == OriginalStdioOp.MULTI_BYTE_TO_WIDE || operation == OriginalStdioOp.WIDE_TO_MULTI_BYTE) {
                long codePage = readInteger(frame, 0);
                long flags = readInteger(frame, 1);
                var input = operands[2].executeRequiredAddress(frame);
                long count = readInteger(frame, 3);
                var output = operands[4].executeRequiredAddress(frame);
                long capacity = readInteger(frame, 5);
                var defaultChar = operation == OriginalStdioOp.WIDE_TO_MULTI_BYTE ? operands[6].executeRequiredAddress(frame) : null;
                var usedDefault = operation == OriginalStdioOp.WIDE_TO_MULTI_BYTE ? operands[7].executeRequiredAddress(frame) : null;
                TupleResults.requireVoidCarrier(operands[operands.length - 1].execute(frame));
                writeInteger(frame, slots[offset], operation == OriginalStdioOp.MULTI_BYTE_TO_WIDE
                    ? windows.multiByte(codePage, flags, input, count, output, capacity)
                    : windows.wideChar(codePage, flags, input, count, output, capacity, defaultChar, usedDefault));
            } else {
                long number = operation == OriginalStdioOp.CODE_PAGE_INFO || operation == OriginalStdioOp.DBCS_LEAD_BYTE ||
                    operation == OriginalStdioOp.MAP_ERRNO_VALUE || operation == OriginalStdioOp.WINDOWS_ERROR_MESSAGE ? readInteger(frame, 0) : 0L;
                long value = operation == OriginalStdioOp.DBCS_LEAD_BYTE ? readInteger(frame, 1) : 0L;
                var address = operation == OriginalStdioOp.CODE_PAGE_INFO ? operands[1].executeRequiredAddress(frame)
                    : operation == OriginalStdioOp.LOCAL_FREE ? operands[0].executeRequiredAddress(frame) : null;
                TupleResults.requireVoidCarrier(operands[operands.length - 1].execute(frame));
                if (operation == OriginalStdioOp.WINDOWS_ERROR_MESSAGE) FrameAccess.writeObject(frame, slots[offset], windows.message(number));
                else if (operation == OriginalStdioOp.LOCAL_FREE) FrameAccess.writeObject(frame, slots[offset], windows.localFree(address));
                else if (operation == OriginalStdioOp.MAP_ERRNO) windows.setErrno();
                else writeInteger(frame, slots[offset], operation == OriginalStdioOp.LAST_ERROR ? windows.error()
                    : operation == OriginalStdioOp.CODE_PAGE_INFO ? windows.info(number, address)
                    : operation == OriginalStdioOp.DBCS_LEAD_BYTE ? windows.leadByte(number, value)
                    : operation == OriginalStdioOp.MAP_ERRNO_VALUE ? windows.mapErrno(number)
                    : windows.codePage(operation == OriginalStdioOp.CONSOLE_CODE_PAGE));
            }
            return null;
        }
        if (operation.getWindowsDirectory()) {
            var first = operation != OriginalStdioOp.LAST_ERROR ? operands[0].executeRequiredAddress(frame) : null;
            var output = operation == OriginalStdioOp.FIND_FIRST || operation == OriginalStdioOp.FIND_NEXT ? operands[1].executeRequiredAddress(frame) : null;
            TupleResults.requireVoidCarrier(operands[operands.length - 1].execute(frame));
            var streams = WindowsDirectoryStreams.current(this);
            if (operation == OriginalStdioOp.FIND_FIRST) FrameAccess.writeObject(frame, slots[offset], streams.first(first, output));
            else writeInteger(frame, slots[offset], operation == OriginalStdioOp.FIND_NEXT ? streams.next(first, output)
                : operation == OriginalStdioOp.FIND_CLOSE ? streams.closeSearch(first) : streams.error());
            return null;
        }
        if (operation.getDirectoryStream()) {
            if (operation == OriginalStdioOp.FDOPENDIR) {
                long fd = readInteger(frame, 0);
                TupleResults.requireVoidCarrier(operands[1].execute(frame));
                FrameAccess.writeObject(frame, slots[offset], CoreOriginalStdio.current(this).openDirectoryFd(fd));
            } else {
                var first = operands[0].executeRequiredAddress(frame);
                var second = operation == OriginalStdioOp.READDIR ? operands[1].executeRequiredAddress(frame) : null;
                TupleResults.requireVoidCarrier(operands[operands.length - 1].execute(frame));
                if (operation == OriginalStdioOp.OPENDIR) FrameAccess.writeObject(frame, slots[offset], CoreOriginalStdio.current(this).openDirectory(first));
                else {
                    var streams = CoreOriginalStdio.directories(this);
                    if (operation == OriginalStdioOp.DIRENT_NAME) FrameAccess.writeObject(frame, slots[offset], streams.name(first));
                    else if (operation == OriginalStdioOp.READDIR) writeInteger(frame, slots[offset], streams.read(first, second));
                    else if (operation == OriginalStdioOp.CLOSEDIR) writeInteger(frame, slots[offset], streams.closeStream(first));
                    else streams.freeEntry(first);
                }
            }
            return null;
        }
        if (operation == OriginalStdioOp.UNLINKAT || operation == OriginalStdioOp.FSTATAT) {
            long fd = readInteger(frame, 0);
            var path = operands[1].executeRequiredAddress(frame);
            long result;
            if (operation == OriginalStdioOp.FSTATAT) {
                var destination = operands[2].executeRequiredAddress(frame);
                long flags = readInteger(frame, 3);
                TupleResults.requireVoidCarrier(operands[4].execute(frame));
                result = CoreOriginalStdio.current(this).statAt(fd, path, destination, flags);
            } else {
                long flags = readInteger(frame, 2);
                TupleResults.requireVoidCarrier(operands[3].execute(frame));
                result = CoreOriginalStdio.current(this).unlinkAt(fd, path, flags);
            }
            writeInteger(frame, slots[offset], result);
            // A safe-call poll resumes after the saved result, never at the effect.
            if (AstControl.enabled(this)) {
                boolean compiled = CompilerDirectives.inCompiledCode();
                var request = GuestThreads.pollCurrent(this, false);
                if (request != null) {
                    request.compiledCapture = compiled;
                    throw new AstCapture(request, SynchronousMasking.current(this)).append(ResumeCompleted.INSTANCE);
                }
            }
            return null;
        }
        if (operation == OriginalStdioOp.GETCWD) {
            var output = operands[0].executeRequiredAddress(frame);
            long capacity = readInteger(frame, 1);
            TupleResults.requireVoidCarrier(operands[2].execute(frame));
            FrameAccess.writeObject(frame, slots[offset], CoreOriginalStdio.current(this).currentDirectory(output, capacity));
            return null;
        }
        if (operation == OriginalStdioOp.SET_ERRNO) {
            long value = readInteger(frame, 0);
            TupleResults.requireVoidCarrier(operands[1].execute(frame));
            CoreOriginalStdio.current(this).setErrno(value);
            return null;
        }
        if (operation.getEventManager()) {
            var stdio = CoreOriginalStdio.current(this);
            long result;
            if (operation.getPoll()) {
                var address = operands[0].executeRequiredAddress(frame);
                long count = readInteger(frame, 1);
                long timeout = readInteger(frame, 2);
                TupleResults.requireVoidCarrier(operands[3].execute(frame));
                result = stdio.poll(address, count, timeout, this, ForeignSafety.synchronous(operation.getSafety()));
            } else if (operation.getEpollWait()) {
                long fd = readInteger(frame, 0);
                var address = operands[1].executeRequiredAddress(frame);
                long maximum = readInteger(frame, 2);
                long timeout = readInteger(frame, 3);
                TupleResults.requireVoidCarrier(operands[4].execute(frame));
                result = stdio.epollWait(fd, address, maximum, timeout, this, ForeignSafety.synchronous(operation.getSafety()));
            } else if (operation == OriginalStdioOp.EPOLL_CTL) {
                long fd = readInteger(frame, 0);
                long command = readInteger(frame, 1);
                long target = readInteger(frame, 2);
                var address = operands[3].executeRequiredAddress(frame);
                TupleResults.requireVoidCarrier(operands[4].execute(frame));
                result = stdio.epollControl(fd, command, target, address);
            } else {
                long first = readInteger(frame, 0);
                long second = operation == OriginalStdioOp.IO_CONTROL_FD ? readInteger(frame, 1) : 0L;
                TupleResults.requireVoidCarrier(operands[operands.length - 1].execute(frame));
                if (operation == OriginalStdioOp.EPOLL_CREATE) result = stdio.epollCreate(first);
                else { stdio.controlFd(operation, first, second); result = 0L; }
            }
            if (operation.getResult() != null) writeInteger(frame, slots[offset], result);
            return null;
        }
        if (operation == OriginalStdioOp.SIGPROCMASK) {
            long how = readInteger(frame, 0);
            var set = operands[1].executeRequiredAddress(frame);
            var oldset = operands[2].executeRequiredAddress(frame);
            TupleResults.requireVoidCarrier(operands[3].execute(frame));
            writeInteger(frame, slots[offset], ManagedSignalMask.execute(this, how, set, oldset));
            return null;
        }
        if (operation.getSigset()) {
            var address = operands[0].executeRequiredAddress(frame);
            long signal = operation == OriginalStdioOp.SIGADDSET ? readInteger(frame, 1) : 0L;
            TupleResults.requireVoidCarrier(operands[operands.length - 1].execute(frame));
            writeInteger(frame, slots[offset], SigsetImage.execute(operation, address, signal, CoreOriginalStdio.current(this)));
            return null;
        }
        if (operation.getSavedTermios()) {
            long fd = readInteger(frame, 0);
            var address = operation == OriginalStdioOp.SET_SAVED_TERMIOS ? operands[1].executeRequiredAddress(frame) : ManagedAddress.nullAddress();
            TupleResults.requireVoidCarrier(operands[operands.length - 1].execute(frame));
            var result = SavedTermios.execute(this, operation, fd, address);
            if (operation == OriginalStdioOp.GET_SAVED_TERMIOS) FrameAccess.writeObject(frame, slots[offset], result);
            return null;
        }
        if (operation.getTermios()) {
            var address = operation.getTermiosAddress() ? operands[0].executeRequiredAddress(frame) : ManagedAddress.nullAddress();
            long value = operation == OriginalStdioOp.POKE_LFLAG ? readInteger(frame, 1) : 0L;
            TupleResults.requireVoidCarrier(operands[operands.length - 1].execute(frame));
            if (operation == OriginalStdioOp.PTR_C_CC) FrameAccess.writeObject(frame, slots[offset], TermiosImage.pointer(address));
            else {
                long result = TermiosImage.scalar(operation, address, value);
                if (operation.getResult() != null) writeInteger(frame, slots[offset], result);
            }
            return null;
        }
        if (operation == OriginalStdioOp.LOCALE) {
            TupleResults.requireVoidCarrier(operands[0].execute(frame));
            FrameAccess.write(frame, slots[offset], CoreOriginalStdio.iconv(this).localeEncoding());
            return null;
        }
        // Direct enum comparison remains constant during partial evaluation.
        long result;
        if (operation.getEventPair()) {
            long first = readInteger(frame, 0);
            long second = readInteger(frame, 1);
            TupleResults.requireVoidCarrier(operands[2].execute(frame));
            var stdio = CoreOriginalStdio.current(this);
            result = operation == OriginalStdioOp.EVENTFD ? stdio.eventfd(first, second) : stdio.eventfdWrite(first, second);
        } else if (operation == OriginalStdioOp.PIPE) {
            var destination = operands[0].executeRequiredAddress(frame);
            TupleResults.requireVoidCarrier(operands[1].execute(frame));
            result = CoreOriginalStdio.current(this).pipe(destination);
        } else if (operation.getWaitStatus()) {
            long status = readInteger(frame, 0);
            TupleResults.requireVoidCarrier(operands[1].execute(frame));
            result = CoreOriginalStdio.waitStatus(this, operation, status);
        } else if (operation.getStat()) {
            var address = operation.getStatField() ? operands[0].executeRequiredAddress(frame) : ManagedAddress.nullAddress();
            long mode = operation == OriginalStdioOp.SIZEOF_STAT || operation.getStatField() ? 0L : readInteger(frame, 0);
            var statOperands = operands;
            var lastOperands = operands;
            if (lastOperands == null) CompilerDirectives.transferToInterpreter();
            TupleResults.requireVoidCarrier(statOperands[lastOperands.length - 1].execute(frame));
            result = PosixStat.execute(operation, address, mode);
        } else if (operation == OriginalStdioOp.CHDIR) {
            var path = operands[0].executeRequiredAddress(frame);
            TupleResults.requireVoidCarrier(operands[1].execute(frame));
            result = CoreOriginalStdio.current(this).changeDirectory(path);
        } else if (operation == OriginalStdioOp.SYMLINK) {
            var target = operands[0].executeRequiredAddress(frame);
            var path = operands[1].executeRequiredAddress(frame);
            TupleResults.requireVoidCarrier(operands[2].execute(frame));
            result = CoreOriginalStdio.current(this).symlink(target, path);
        } else if (operation == OriginalStdioOp.READLINK) {
            var path = operands[0].executeRequiredAddress(frame);
            var output = operands[1].executeRequiredAddress(frame);
            long capacity = readInteger(frame, 2);
            TupleResults.requireVoidCarrier(operands[3].execute(frame));
            result = CoreOriginalStdio.current(this).readlink(path, output, capacity);
        } else if (operation == OriginalStdioOp.ACCESS) {
            var path = operands[0].executeRequiredAddress(frame);
            long mode = readInteger(frame, 1);
            TupleResults.requireVoidCarrier(operands[2].execute(frame));
            result = CoreOriginalStdio.current(this).access(path, mode);
        } else if (operation.getPathMode()) {
            var path = operands[0].executeRequiredAddress(frame);
            long mode = readInteger(frame, 1);
            TupleResults.requireVoidCarrier(operands[2].execute(frame));
            result = CoreOriginalStdio.current(this).pathMode(operation, path, mode);
        } else if (operation.getPathRemoval()) {
            var path = operands[0].executeRequiredAddress(frame);
            TupleResults.requireVoidCarrier(operands[1].execute(frame));
            result = operation == OriginalStdioOp.RMDIR ? CoreOriginalStdio.current(this).removeDirectory(path) : CoreOriginalStdio.current(this).unlink(path);
        } else if (operation == OriginalStdioOp.LOCK) {
            long key = readInteger(frame, 0);
            long device = readInteger(frame, 1);
            long inode = readInteger(frame, 2);
            long writing = readInteger(frame, 3);
            TupleResults.requireVoidCarrier(operands[4].execute(frame));
            result = CoreOriginalStdio.locks(this).lock(key, device, inode, writing);
        } else if (operation == OriginalStdioOp.UNLOCK) {
            long key = readInteger(frame, 0);
            TupleResults.requireVoidCarrier(operands[1].execute(frame));
            result = CoreOriginalStdio.locks(this).unlock(key);
        } else if (operation.getOpening()) {
            var path = operands[0].executeRequiredAddress(frame);
            long flags = readInteger(frame, 1);
            long mode = readInteger(frame, 2);
            TupleResults.requireVoidCarrier(operands[3].execute(frame));
            result = CoreOriginalStdio.current(this).open(path, flags, mode, operation, this);
        } else if (operation == OriginalStdioOp.TCSETATTR) {
            long fd = readInteger(frame, 0);
            long action = readInteger(frame, 1);
            var address = operands[2].executeRequiredAddress(frame);
            TupleResults.requireVoidCarrier(operands[3].execute(frame));
            result = CoreOriginalStdio.current(this).tcsetattr(fd, action, address);
        } else if (operation.getPathStat()) {
            var path = operands[0].executeRequiredAddress(frame);
            var destination = operands[1].executeRequiredAddress(frame);
            TupleResults.requireVoidCarrier(operands[2].execute(frame));
            result = CoreOriginalStdio.current(this).pathStat(operation, path, destination);
        } else if (operation.getReadImage()) {
            long fd = readInteger(frame, 0);
            var address = operands[1].executeRequiredAddress(frame);
            TupleResults.requireVoidCarrier(operands[2].execute(frame));
            result = operation == OriginalStdioOp.TCGETATTR ? CoreOriginalStdio.current(this).tcgetattr(fd, address) : CoreOriginalStdio.current(this).fstat(fd, address);
        } else if (operation == OriginalStdioOp.ICONV_OPEN) {
            var to = operands[0].executeRequiredAddress(frame);
            var from = operands[1].executeRequiredAddress(frame);
            TupleResults.requireVoidCarrier(operands[2].execute(frame));
            result = CoreOriginalStdio.iconv(this).open(to, from);
        } else if (operation == OriginalStdioOp.ICONV_CLOSE) {
            long handle = readInteger(frame, 0);
            TupleResults.requireVoidCarrier(operands[1].execute(frame));
            result = CoreOriginalStdio.iconv(this).close(handle);
        } else if (operation == OriginalStdioOp.ICONV) {
            long handle = readInteger(frame, 0);
            var input = operands[1].executeRequiredAddress(frame);
            var inputCount = operands[2].executeRequiredAddress(frame);
            var output = operands[3].executeRequiredAddress(frame);
            var outputCount = operands[4].executeRequiredAddress(frame);
            TupleResults.requireVoidCarrier(operands[5].execute(frame));
            result = CoreOriginalStdio.iconv(this).convert(handle, input, inputCount, output, outputCount);
        } else if (operation == OriginalStdioOp.STRERROR) {
            long error = readInteger(frame, 0);
            var output = operands[1].executeRequiredAddress(frame);
            long length = readInteger(frame, 2);
            TupleResults.requireVoidCarrier(operands[3].execute(frame));
            result = CoreOriginalStdio.strerror(this).call(error, output, length);
        } else if (operation.getProcessIdentity()) {
            TupleResults.requireVoidCarrier(operands[0].execute(frame));
            result = ProcessIdentity.query(this, operation);
        } else if (operation == OriginalStdioOp.ERRNO) {
            TupleResults.requireVoidCarrier(operands[0].execute(frame));
            result = CoreOriginalStdio.current(this).errno();
        } else if (operation.getFlagConstant()) {
            TupleResults.requireVoidCarrier(operands[0].execute(frame));
            result = CoreOriginalStdio.current(this).flagConstant(operation);
        } else if (operation.getFcntl()) {
            long fd = readInteger(frame, 0);
            long command = readInteger(frame, 1);
            long argument = operation == OriginalStdioOp.FCNTL_WRITE ? readInteger(frame, 2) : 0L;
            TupleResults.requireVoidCarrier(operands[operands.length - 1].execute(frame));
            result = CoreOriginalStdio.current(this).fcntl(fd, command, argument, operation == OriginalStdioOp.FCNTL_WRITE);
        } else if (operation.getSeekConstant()) {
            TupleResults.requireVoidCarrier(operands[0].execute(frame));
            result = CoreOriginalStdio.current(this).seekConstant(operation);
        } else if (operation.getReadiness()) {
            long fd = readInteger(frame, 0);
            long writing = readInteger(frame, 1);
            long milliseconds = readInteger(frame, 2);
            long socket = readInteger(frame, 3);
            TupleResults.requireVoidCarrier(operands[4].execute(frame));
            result = CoreOriginalStdio.current(this).ready(fd, writing, milliseconds, socket, this);
        } else if (operation == OriginalStdioOp.ISATTY || operation == OriginalStdioOp.CLOSE || operation == OriginalStdioOp.DUP) {
            long fd = readInteger(frame, 0);
            TupleResults.requireVoidCarrier(operands[1].execute(frame));
            var stdio = CoreOriginalStdio.current(this);
            result = operation == OriginalStdioOp.CLOSE ? stdio.close(fd) : operation == OriginalStdioOp.DUP ? stdio.duplicate(fd) : stdio.isTerminal(fd);
        } else if (operation == OriginalStdioOp.DUP2) {
            long fd = readInteger(frame, 0);
            long target = readInteger(frame, 1);
            TupleResults.requireVoidCarrier(operands[2].execute(frame));
            result = CoreOriginalStdio.current(this).duplicateTo(fd, target);
        } else if (operation == OriginalStdioOp.SEEK) {
            long fd = readInteger(frame, 0);
            long displacement = readInteger(frame, 1);
            long whence = readInteger(frame, 2);
            TupleResults.requireVoidCarrier(operands[3].execute(frame));
            result = CoreOriginalStdio.current(this).seek(fd, displacement, whence);
        } else if (operation == OriginalStdioOp.TRUNCATE) {
            long fd = readInteger(frame, 0);
            long length = readInteger(frame, 1);
            TupleResults.requireVoidCarrier(operands[2].execute(frame));
            result = CoreOriginalStdio.current(this).truncate(fd, length);
        } else {
            long fd = readInteger(frame, 0);
            var address = operands[1].executeRequiredAddress(frame);
            long count = readInteger(frame, 2);
            TupleResults.requireVoidCarrier(operands[3].execute(frame));
            var stdio = CoreOriginalStdio.current(this);
            result = operation == OriginalStdioOp.READ_SAFE || operation == OriginalStdioOp.READ_UNSAFE ? stdio.read(fd, address, count) : stdio.write(fd, address, count);
        }
        writeInteger(frame, slots[offset], result);
        return null;
    }
}
