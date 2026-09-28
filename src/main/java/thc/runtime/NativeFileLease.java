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
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.channels.ClosedChannelException;

/** Private provider ownership; neither fd nor pointer becomes guest data.
 * Cleanup remains valid after LLVM disposal. Linux consumes close even on EINTR. */
@ExportLibrary(InteropLibrary.class)
public final class NativeFileLease implements AutoCloseable, TruffleObject {
    private final Arena arena;
    private final MemorySegment slot;
    // IO owns this lease's monitor. Readiness duplication uses only this short lock.
    private final Object lifetime = new Object();
    private boolean closed;

    public NativeFileLease() {
        // Resolve host bindings on acquisition, not during interop class preparation.
        MethodHandle close = NativeCalls.CLOSE;
        arena = Arena.ofShared();
        slot = arena.allocate(ValueLayout.JAVA_INT);
        slot.set(ValueLayout.JAVA_INT, 0, -1);
    }

    public synchronized MemorySegment openSlot() {
        if (closed) throw propagate(new ClosedChannelException());
        if (slot.get(ValueLayout.JAVA_INT, 0) != -1) throw new IllegalStateException("Open lease already populated");
        return slot;
    }
    public synchronized void requireOpen() {
        if (closed || slot.get(ValueLayout.JAVA_INT, 0) < 0) throw propagate(new ClosedChannelException());
    }
    public synchronized boolean isOpen() { return !closed && slot.get(ValueLayout.JAVA_INT, 0) >= 0; }

    public int duplicateForWait() {
        synchronized (lifetime) {
            if (closed || slot.get(ValueLayout.JAVA_INT, 0) < 0) throw propagate(new ClosedChannelException());
            try (var call = Arena.ofConfined()) {
                var errors = call.allocate(NativeCalls.CAPTURE);
                int result = (int) WaitDuplicate.FCNTL.invokeExact(errors, slot.get(ValueLayout.JAVA_INT, 0), 1030, 0);
                if (result < 0) throw new NativeFileException("duplicate readiness lease", errors.get(ValueLayout.JAVA_INT, NativeCalls.ERRNO));
                return result;
            } catch (Throwable failure) { throw failed("Native readiness duplication failed", failure); }
        }
    }

    @Override public synchronized void close() { closeOwned(true); }

    /** Retiring an O_PATH anchor must not turn a committed CWD/native effect into failure. */
    public synchronized void closeDirectory() { closeOwned(false); }

    private void closeOwned(boolean reportError) {
        synchronized (lifetime) {
            if (closed) return;
            closed = true;
            try {
                int fd = slot.get(ValueLayout.JAVA_INT, 0);
                slot.set(ValueLayout.JAVA_INT, 0, -1);
                if (fd >= 0) {
                    try (var call = Arena.ofConfined()) {
                        var errors = call.allocate(NativeCalls.CAPTURE);
                        int result = (int) NativeCalls.CLOSE.invokeExact(errors, fd);
                        if (result != 0 && reportError) throw new NativeFileException("close", errors.get(ValueLayout.JAVA_INT, NativeCalls.ERRNO));
                    } catch (Throwable failure) { throw failed("Native close invocation failed", failure); }
                }
            } finally { arena.close(); }
        }
    }

    @ExportMessage public synchronized boolean isPointer() { return !closed; }
    @ExportMessage public synchronized long asPointer() throws UnsupportedMessageException {
        if (closed) throw UnsupportedMessageException.create();
        return slot.address();
    }

    private static RuntimeException failed(String message, Throwable failure) {
        if (failure instanceof IOException || failure instanceof RuntimeException || failure instanceof Error) return propagate(failure);
        return propagate(new IOException(message, failure));
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }

    private static final class NativeCalls {
        static final StructLayout CAPTURE = Linker.Option.captureStateLayout();
        static final long ERRNO = CAPTURE.byteOffset(MemoryLayout.PathElement.groupElement("errno"));
        static final MethodHandle CLOSE = Linker.nativeLinker().downcallHandle(
            Linker.nativeLinker().defaultLookup().find("close").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), Linker.Option.captureCallState("errno"));
    }
    private static final class WaitDuplicate {
        static final MethodHandle FCNTL = Linker.nativeLinker().downcallHandle(
            Linker.nativeLinker().defaultLookup().find("fcntl").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
            Linker.Option.firstVariadicArg(2), Linker.Option.captureCallState("errno"));
    }
}
