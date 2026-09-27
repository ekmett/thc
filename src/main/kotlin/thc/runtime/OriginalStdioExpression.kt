// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.CompilerDirectives

/** Preserve typed addresses and numeric carriers; validate State before effects. */
internal class OriginalStdioExpression(private val operation: OriginalStdioOp,
    @field:Children private var operands: Array<Expr>, proof: CoreRepresentation) : Expr() {
    init { representation = proof.copy(evaluated = true) }
    override fun execute(frame: VirtualFrame): Nothing = fault("Original stdio call requires a State/result tuple destination")

    @field:CompilerDirectives.CompilationFinal(dimensions = 1)
    private val argumentIntegers = operation.arguments.map(NarrowInteger::fromRep).toTypedArray()

    /** The managed OS service API is machine-wide; only this declared foreign
     * boundary widens narrow guest carriers (including unsigned Word32). */
    private fun readInteger(frame: VirtualFrame, index: Int): Long {
        val integer = argumentIntegers[index]
        return if (integer == null) operands[index].executeRequiredLong(frame)
            else integer.widen(operands[index].executeRequiredInt(frame))
    }

    private fun writeInteger(frame: VirtualFrame, slot: Int, value: Long) {
        if (operation.narrowResult == null) FrameAccess.writeLong(frame, slot, value)
        else FrameAccess.writeInt(frame, slot, value.toInt())
    }

    private object ResumeCompleted : AstResumeStep {
        override fun resume(frame: VirtualFrame, input: Any?): Any? {
            if (input !== Unit) fault("Original pathname call continuation expected a completed async poll")
            return null
        }
    }

    override fun executeTuple(frame: VirtualFrame, slots: IntArray, offset: Int): Any? {
        if (operation.windowsEncoding) {
            val windows = WindowsCodePages.current(this)
            if (operation == OriginalStdioOp.MULTI_BYTE_TO_WIDE || operation == OriginalStdioOp.WIDE_TO_MULTI_BYTE) {
                val codePage = readInteger(frame, 0)
                val flags = readInteger(frame, 1)
                val input = operands[2].executeRequiredAddress(frame)
                val count = readInteger(frame, 3)
                val output = operands[4].executeRequiredAddress(frame)
                val capacity = readInteger(frame, 5)
                val defaultChar = if (operation == OriginalStdioOp.WIDE_TO_MULTI_BYTE) operands[6].executeRequiredAddress(frame) else null
                val usedDefault = if (operation == OriginalStdioOp.WIDE_TO_MULTI_BYTE) operands[7].executeRequiredAddress(frame) else null
                requireVoidCarrier(operands.last().execute(frame))
                writeInteger(frame, slots[offset], if (operation == OriginalStdioOp.MULTI_BYTE_TO_WIDE)
                    windows.multiByte(codePage, flags, input, count, output, capacity)
                else windows.wideChar(codePage, flags, input, count, output, capacity, defaultChar!!, usedDefault!!))
            } else {
                val number = if (operation == OriginalStdioOp.CODE_PAGE_INFO || operation == OriginalStdioOp.DBCS_LEAD_BYTE ||
                    operation == OriginalStdioOp.MAP_ERRNO_VALUE || operation == OriginalStdioOp.WINDOWS_ERROR_MESSAGE)
                    readInteger(frame, 0) else 0L
                val byte = if (operation == OriginalStdioOp.DBCS_LEAD_BYTE) readInteger(frame, 1) else 0L
                val address = if (operation == OriginalStdioOp.CODE_PAGE_INFO) operands[1].executeRequiredAddress(frame)
                    else if (operation == OriginalStdioOp.LOCAL_FREE) operands[0].executeRequiredAddress(frame) else null
                requireVoidCarrier(operands.last().execute(frame))
                if (operation == OriginalStdioOp.WINDOWS_ERROR_MESSAGE)
                    FrameAccess.writeObject(frame, slots[offset], windows.message(number))
                else if (operation == OriginalStdioOp.LOCAL_FREE)
                    FrameAccess.writeObject(frame, slots[offset], windows.localFree(address!!))
                else if (operation == OriginalStdioOp.MAP_ERRNO) windows.setErrno()
                else writeInteger(frame, slots[offset],
                    if (operation == OriginalStdioOp.LAST_ERROR) windows.error()
                    else if (operation == OriginalStdioOp.CODE_PAGE_INFO) windows.info(number, address!!)
                    else if (operation == OriginalStdioOp.DBCS_LEAD_BYTE) windows.leadByte(number, byte)
                    else if (operation == OriginalStdioOp.MAP_ERRNO_VALUE) windows.mapErrno(number)
                    else windows.codePage(operation == OriginalStdioOp.CONSOLE_CODE_PAGE))
            }
            return null
        }
        if (operation.windowsDirectory) {
            val first = if (operation != OriginalStdioOp.LAST_ERROR) operands[0].executeRequiredAddress(frame) else null
            val output = if (operation == OriginalStdioOp.FIND_FIRST || operation == OriginalStdioOp.FIND_NEXT)
                operands[1].executeRequiredAddress(frame) else null
            requireVoidCarrier(operands.last().execute(frame))
            val streams = WindowsDirectoryStreams.current(this)
            if (operation == OriginalStdioOp.FIND_FIRST)
                FrameAccess.writeObject(frame, slots[offset], streams.first(first!!, output!!))
            else writeInteger(frame, slots[offset],
                if (operation == OriginalStdioOp.FIND_NEXT) streams.next(first!!, output!!)
                else if (operation == OriginalStdioOp.FIND_CLOSE) streams.closeSearch(first!!)
                else streams.error())
            return null
        }
        if (operation.directoryStream) {
            if (operation == OriginalStdioOp.FDOPENDIR) {
                val fd = readInteger(frame, 0)
                requireVoidCarrier(operands[1].execute(frame))
                FrameAccess.writeObject(frame, slots[offset], CoreOriginalStdio.current(this).openDirectoryFd(fd))
            } else {
                val first = operands[0].executeRequiredAddress(frame)
                val second = if (operation == OriginalStdioOp.READDIR) operands[1].executeRequiredAddress(frame) else null
                requireVoidCarrier(operands.last().execute(frame))
                if (operation == OriginalStdioOp.OPENDIR)
                    FrameAccess.writeObject(frame, slots[offset], CoreOriginalStdio.current(this).openDirectory(first))
                else {
                    val streams = CoreOriginalStdio.directories(this)
                    if (operation == OriginalStdioOp.DIRENT_NAME)
                        FrameAccess.writeObject(frame, slots[offset], streams.name(first))
                    else if (operation == OriginalStdioOp.READDIR)
                        writeInteger(frame, slots[offset], streams.read(first, second!!))
                    else if (operation == OriginalStdioOp.CLOSEDIR)
                        writeInteger(frame, slots[offset], streams.closeStream(first))
                    else streams.freeEntry(first)
                }
            }
            return null
        }
        if (operation == OriginalStdioOp.UNLINKAT || operation == OriginalStdioOp.FSTATAT) {
            val fd = readInteger(frame, 0)
            val path = operands[1].executeRequiredAddress(frame)
            val result = if (operation == OriginalStdioOp.FSTATAT) {
                val destination = operands[2].executeRequiredAddress(frame)
                val flags = readInteger(frame, 3)
                requireVoidCarrier(operands[4].execute(frame))
                CoreOriginalStdio.current(this).statAt(fd, path, destination, flags)
            } else {
                val flags = readInteger(frame, 2)
                requireVoidCarrier(operands[3].execute(frame))
                CoreOriginalStdio.current(this).unlinkAt(fd, path, flags)
            }
            writeInteger(frame, slots[offset], result)
            // A safe-call poll resumes after the saved result, never at the effect.
            if (AstControl.enabled(this)) {
                val compiled = CompilerDirectives.inCompiledCode()
                GuestThreads.pollCurrent(this, false)?.let { request ->
                    request.compiledCapture = compiled
                    throw AstCapture(request, SynchronousMasking.current(this)).append(ResumeCompleted)
                }
            }
            return null
        }
        if (operation == OriginalStdioOp.GETCWD) {
            val output = operands[0].executeRequiredAddress(frame)
            val capacity = readInteger(frame, 1)
            requireVoidCarrier(operands[2].execute(frame))
            FrameAccess.writeObject(frame, slots[offset], CoreOriginalStdio.current(this).currentDirectory(output, capacity))
            return null
        }
        if (operation == OriginalStdioOp.SET_ERRNO) {
            val value = readInteger(frame, 0)
            requireVoidCarrier(operands[1].execute(frame))
            CoreOriginalStdio.current(this).setErrno(value)
            return null
        }
        if (operation.eventManager) {
            val stdio = CoreOriginalStdio.current(this)
            val result = if (operation.poll) {
                val address = operands[0].executeRequiredAddress(frame)
                val count = readInteger(frame, 1)
                val timeout = readInteger(frame, 2)
                requireVoidCarrier(operands[3].execute(frame))
                stdio.poll(address, count, timeout, this)
            } else if (operation.epollWait) {
                val fd = readInteger(frame, 0)
                val address = operands[1].executeRequiredAddress(frame)
                val maximum = readInteger(frame, 2)
                val timeout = readInteger(frame, 3)
                requireVoidCarrier(operands[4].execute(frame))
                stdio.epollWait(fd, address, maximum, timeout, this)
            } else if (operation == OriginalStdioOp.EPOLL_CTL) {
                val fd = readInteger(frame, 0)
                val command = readInteger(frame, 1)
                val target = readInteger(frame, 2)
                val address = operands[3].executeRequiredAddress(frame)
                requireVoidCarrier(operands[4].execute(frame))
                stdio.epollControl(fd, command, target, address)
            } else {
                val first = readInteger(frame, 0)
                val second = if (operation == OriginalStdioOp.IO_CONTROL_FD) readInteger(frame, 1) else 0L
                requireVoidCarrier(operands.last().execute(frame))
                if (operation == OriginalStdioOp.EPOLL_CREATE) stdio.epollCreate(first)
                else { stdio.controlFd(operation, first, second); 0L }
            }
            if (operation.result != null) writeInteger(frame, slots[offset], result)
            return null
        }
        if (operation == OriginalStdioOp.SIGPROCMASK) {
            val how = readInteger(frame, 0)
            val set = operands[1].executeRequiredAddress(frame)
            val oldset = operands[2].executeRequiredAddress(frame)
            requireVoidCarrier(operands[3].execute(frame))
            writeInteger(frame, slots[offset], ManagedSignalMask.execute(this, how, set, oldset))
            return null
        }
        if (operation.sigset) {
            val address = operands[0].executeRequiredAddress(frame)
            val signal = if (operation == OriginalStdioOp.SIGADDSET) readInteger(frame, 1) else 0L
            requireVoidCarrier(operands.last().execute(frame))
            writeInteger(frame, slots[offset], SigsetImage.execute(operation, address, signal, CoreOriginalStdio.current(this)))
            return null
        }
        if (operation.savedTermios) {
            val fd = readInteger(frame, 0)
            val address = if (operation == OriginalStdioOp.SET_SAVED_TERMIOS) operands[1].executeRequiredAddress(frame)
                else ManagedAddress.nullAddress()
            requireVoidCarrier(operands.last().execute(frame))
            val result = SavedTermios.execute(this, operation, fd, address)
            if (operation == OriginalStdioOp.GET_SAVED_TERMIOS) FrameAccess.writeObject(frame, slots[offset], result)
            return null
        }
        if (operation.termios) {
            val address = if (operation.termiosAddress) operands[0].executeRequiredAddress(frame) else ManagedAddress.nullAddress()
            val value = if (operation == OriginalStdioOp.POKE_LFLAG) readInteger(frame, 1) else 0L
            requireVoidCarrier(operands.last().execute(frame))
            if (operation == OriginalStdioOp.PTR_C_CC) FrameAccess.writeObject(frame, slots[offset], TermiosImage.pointer(address))
            else {
                val result = TermiosImage.scalar(operation, address, value)
                if (operation.result != null) writeInteger(frame, slots[offset], result)
            }
            return null
        }
        if (operation == OriginalStdioOp.LOCALE) {
            requireVoidCarrier(operands[0].execute(frame))
            FrameAccess.write(frame, slots[offset], CoreOriginalStdio.iconv(this).localeEncoding())
            return null
        }
        // Direct enum comparison remains constant during partial evaluation.
        val result = if (operation.eventPair) {
            val first = readInteger(frame, 0)
            val second = readInteger(frame, 1)
            requireVoidCarrier(operands[2].execute(frame))
            val stdio = CoreOriginalStdio.current(this)
            if (operation == OriginalStdioOp.EVENTFD) stdio.eventfd(first, second)
            else stdio.eventfdWrite(first, second)
        } else if (operation == OriginalStdioOp.PIPE) {
            val destination = operands[0].executeRequiredAddress(frame)
            requireVoidCarrier(operands[1].execute(frame))
            CoreOriginalStdio.current(this).pipe(destination)
        } else if (operation.waitStatus) {
            val status = readInteger(frame, 0)
            requireVoidCarrier(operands[1].execute(frame))
            CoreOriginalStdio.waitStatus(this, operation, status)
        } else if (operation.stat) {
            val address = if (operation.statField) operands[0].executeRequiredAddress(frame) else ManagedAddress.nullAddress()
            val mode = if (operation == OriginalStdioOp.SIZEOF_STAT || operation.statField) 0L
                else readInteger(frame, 0)
            val statOperands = operands
            val lastOperands = operands
            if (lastOperands == null) CompilerDirectives.transferToInterpreter()
            requireVoidCarrier(statOperands[lastOperands.lastIndex].execute(frame))
            PosixStat.execute(operation, address, mode)
        } else if (operation == OriginalStdioOp.CHDIR) {
            val path = operands[0].executeRequiredAddress(frame)
            requireVoidCarrier(operands[1].execute(frame))
            CoreOriginalStdio.current(this).changeDirectory(path)
        } else if (operation == OriginalStdioOp.SYMLINK) {
            val target = operands[0].executeRequiredAddress(frame)
            val path = operands[1].executeRequiredAddress(frame)
            requireVoidCarrier(operands[2].execute(frame))
            CoreOriginalStdio.current(this).symlink(target, path)
        } else if (operation == OriginalStdioOp.READLINK) {
            val path = operands[0].executeRequiredAddress(frame)
            val output = operands[1].executeRequiredAddress(frame)
            val capacity = readInteger(frame, 2)
            requireVoidCarrier(operands[3].execute(frame))
            CoreOriginalStdio.current(this).readlink(path, output, capacity)
        } else if (operation == OriginalStdioOp.ACCESS) {
            val path = operands[0].executeRequiredAddress(frame)
            val mode = readInteger(frame, 1)
            requireVoidCarrier(operands[2].execute(frame))
            CoreOriginalStdio.current(this).access(path, mode)
        } else if (operation.pathMode) {
            val path = operands[0].executeRequiredAddress(frame)
            val mode = readInteger(frame, 1)
            requireVoidCarrier(operands[2].execute(frame))
            CoreOriginalStdio.current(this).pathMode(operation, path, mode)
        } else if (operation.pathRemoval) {
            val path = operands[0].executeRequiredAddress(frame)
            requireVoidCarrier(operands[1].execute(frame))
            if (operation == OriginalStdioOp.RMDIR) CoreOriginalStdio.current(this).removeDirectory(path)
            else CoreOriginalStdio.current(this).unlink(path)
        } else if (operation == OriginalStdioOp.LOCK) {
            val key = readInteger(frame, 0)
            val device = readInteger(frame, 1)
            val inode = readInteger(frame, 2)
            val writing = readInteger(frame, 3)
            requireVoidCarrier(operands[4].execute(frame))
            CoreOriginalStdio.locks(this).lock(key, device, inode, writing)
        } else if (operation == OriginalStdioOp.UNLOCK) {
            val key = readInteger(frame, 0)
            requireVoidCarrier(operands[1].execute(frame))
            CoreOriginalStdio.locks(this).unlock(key)
        } else if (operation.opening) {
            val path = operands[0].executeRequiredAddress(frame)
            val flags = readInteger(frame, 1)
            val mode = readInteger(frame, 2)
            requireVoidCarrier(operands[3].execute(frame))
            CoreOriginalStdio.current(this).open(path, flags, mode, operation, this)
        } else if (operation == OriginalStdioOp.TCSETATTR) {
            val fd = readInteger(frame, 0)
            val action = readInteger(frame, 1)
            val address = operands[2].executeRequiredAddress(frame)
            requireVoidCarrier(operands[3].execute(frame))
            CoreOriginalStdio.current(this).tcsetattr(fd, action, address)
        } else if (operation.pathStat) {
            val path = operands[0].executeRequiredAddress(frame)
            val destination = operands[1].executeRequiredAddress(frame)
            requireVoidCarrier(operands[2].execute(frame))
            CoreOriginalStdio.current(this).pathStat(operation, path, destination)
        } else if (operation.readImage) {
            val fd = readInteger(frame, 0)
            val address = operands[1].executeRequiredAddress(frame)
            requireVoidCarrier(operands[2].execute(frame))
            if (operation == OriginalStdioOp.TCGETATTR) CoreOriginalStdio.current(this).tcgetattr(fd, address)
            else CoreOriginalStdio.current(this).fstat(fd, address)
        } else if (operation == OriginalStdioOp.ICONV_OPEN) {
            val to = operands[0].executeRequiredAddress(frame)
            val from = operands[1].executeRequiredAddress(frame)
            requireVoidCarrier(operands[2].execute(frame))
            CoreOriginalStdio.iconv(this).open(to, from)
        } else if (operation == OriginalStdioOp.ICONV_CLOSE) {
            val handle = readInteger(frame, 0)
            requireVoidCarrier(operands[1].execute(frame))
            CoreOriginalStdio.iconv(this).close(handle)
        } else if (operation == OriginalStdioOp.ICONV) {
            val handle = readInteger(frame, 0)
            val input = operands[1].executeRequiredAddress(frame)
            val inputCount = operands[2].executeRequiredAddress(frame)
            val output = operands[3].executeRequiredAddress(frame)
            val outputCount = operands[4].executeRequiredAddress(frame)
            requireVoidCarrier(operands[5].execute(frame))
            CoreOriginalStdio.iconv(this).convert(handle, input, inputCount, output, outputCount)
        } else if (operation == OriginalStdioOp.STRERROR) {
            val error = readInteger(frame, 0)
            val output = operands[1].executeRequiredAddress(frame)
            val length = readInteger(frame, 2)
            requireVoidCarrier(operands[3].execute(frame))
            CoreOriginalStdio.strerror(this).call(error, output, length)
        } else if (operation.processIdentity) {
            requireVoidCarrier(operands[0].execute(frame))
            ProcessIdentity.query(this, operation)
        } else if (operation == OriginalStdioOp.ERRNO) {
            requireVoidCarrier(operands[0].execute(frame))
            CoreOriginalStdio.current(this).errno()
        } else if (operation.flagConstant) {
            requireVoidCarrier(operands[0].execute(frame))
            CoreOriginalStdio.current(this).flagConstant(operation)
        } else if (operation.fcntl) {
            val fd = readInteger(frame, 0)
            val command = readInteger(frame, 1)
            val argument = if (operation == OriginalStdioOp.FCNTL_WRITE) readInteger(frame, 2) else 0L
            requireVoidCarrier(operands.last().execute(frame))
            CoreOriginalStdio.current(this).fcntl(fd, command, argument, operation == OriginalStdioOp.FCNTL_WRITE)
        } else if (operation.seekConstant) {
            requireVoidCarrier(operands[0].execute(frame))
            CoreOriginalStdio.current(this).seekConstant(operation)
        } else if (operation.readiness) {
            val fd = readInteger(frame, 0)
            val writing = readInteger(frame, 1)
            val milliseconds = readInteger(frame, 2)
            val socket = readInteger(frame, 3)
            requireVoidCarrier(operands[4].execute(frame))
            CoreOriginalStdio.current(this).ready(fd, writing, milliseconds, socket, this)
        } else if (operation == OriginalStdioOp.ISATTY || operation == OriginalStdioOp.CLOSE || operation == OriginalStdioOp.DUP) {
            val fd = readInteger(frame, 0)
            requireVoidCarrier(operands[1].execute(frame))
            val stdio = CoreOriginalStdio.current(this)
            if (operation == OriginalStdioOp.CLOSE) stdio.close(fd)
            else if (operation == OriginalStdioOp.DUP) stdio.duplicate(fd) else stdio.isTerminal(fd)
        } else if (operation == OriginalStdioOp.DUP2) {
            val fd = readInteger(frame, 0)
            val target = readInteger(frame, 1)
            requireVoidCarrier(operands[2].execute(frame))
            CoreOriginalStdio.current(this).duplicateTo(fd, target)
        } else if (operation == OriginalStdioOp.SEEK) {
            val fd = readInteger(frame, 0)
            val displacement = readInteger(frame, 1)
            val whence = readInteger(frame, 2)
            requireVoidCarrier(operands[3].execute(frame))
            CoreOriginalStdio.current(this).seek(fd, displacement, whence)
        } else if (operation == OriginalStdioOp.TRUNCATE) {
            val fd = readInteger(frame, 0)
            val length = readInteger(frame, 1)
            requireVoidCarrier(operands[2].execute(frame))
            CoreOriginalStdio.current(this).truncate(fd, length)
        } else {
            val fd = readInteger(frame, 0)
            val address = operands[1].executeRequiredAddress(frame)
            val count = readInteger(frame, 2)
            requireVoidCarrier(operands[3].execute(frame))
            val stdio = CoreOriginalStdio.current(this)
            if (operation == OriginalStdioOp.READ_SAFE || operation == OriginalStdioOp.READ_UNSAFE)
                stdio.read(fd, address, count)
            else stdio.write(fd, address, count)
        }
        writeInteger(frame, slots[offset], result)
        return null
    }
}
