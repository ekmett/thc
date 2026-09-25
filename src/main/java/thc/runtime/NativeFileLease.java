// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.channels.ClosedChannelException;

/** Private native-provider ownership. No fd or native pointer becomes guest data.
 * Cleanup is a host downcall, not a second guest LLVM invocation, and works after
 * ordinary LLVM disposal. Acquisition cancellation is not yet proven safe.
 * Linux consumes close even on EINTR. */
@ExportLibrary(InteropLibrary.class)
public final class NativeFileLease implements AutoCloseable, TruffleObject {
    private static final MemoryLayout CAPTURE = Linker.Option.captureStateLayout();
    private static final long ERRNO = CAPTURE.byteOffset(MemoryLayout.PathElement.groupElement("errno"));
    private static final MethodHandle CLOSE = Linker.nativeLinker().downcallHandle(
        Linker.nativeLinker().defaultLookup().find("close").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
        Linker.Option.captureCallState("errno"));
    private final Arena arena = Arena.ofShared();
    private final MemorySegment slot = arena.allocate(ValueLayout.JAVA_INT);
    private boolean closed;

    NativeFileLease() { slot.set(ValueLayout.JAVA_INT, 0, -1); }

    synchronized void requireOpen() throws ClosedChannelException {
        if (closed || slot.get(ValueLayout.JAVA_INT, 0) < 0) throw new ClosedChannelException();
    }

    synchronized boolean isOpen() { return !closed && slot.get(ValueLayout.JAVA_INT, 0) >= 0; }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            int fd = slot.get(ValueLayout.JAVA_INT, 0);
            slot.set(ValueLayout.JAVA_INT, 0, -1);
            if (fd >= 0) {
                try (Arena call = Arena.ofConfined()) {
                    MemorySegment errors = call.allocate(CAPTURE);
                    int result = (int) CLOSE.invokeExact(errors, fd);
                    if (result != 0) throw new NativeFileException("close", errors.get(ValueLayout.JAVA_INT, ERRNO));
                } catch (IOException | RuntimeException | Error failure) {
                    throw failure;
                } catch (Throwable failure) {
                    throw new IOException("Native close invocation failed", failure);
                }
            }
        } finally { arena.close(); }
    }

    @ExportMessage synchronized boolean isPointer() { return !closed; }
    @ExportMessage synchronized long asPointer() throws UnsupportedMessageException {
        if (closed) throw UnsupportedMessageException.create();
        return slot.address();
    }
}
