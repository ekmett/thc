// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/** Real libc storage. Every access borrows the live allocation; free waits for
 * outstanding borrows and never calls into a disposed LLVM context. */
public final class NativeMallocAllocation implements AutoCloseable {
    private static final class Libc {
        static final Linker LINKER = Linker.nativeLinker();
        static final MemoryLayout CAPTURE = Linker.Option.captureStateLayout();
        static final long ERRNO = CAPTURE.byteOffset(MemoryLayout.PathElement.groupElement("errno"));
        static final MethodHandle MALLOC = LINKER.downcallHandle(
            LINKER.defaultLookup().find("malloc").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
            Linker.Option.captureCallState("errno"));
        static final MethodHandle FREE = LINKER.downcallHandle(
            LINKER.defaultLookup().find("free").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    }
    public record Result(NativeMallocAllocation allocation, long errno) {}
    private final MemorySegment pointer;
    private final MemorySegment segment;
    private final Arena arena;
    private final ReentrantReadWriteLock lifetime = new ReentrantReadWriteLock(true);
    private boolean closed;

    private NativeMallocAllocation(MemorySegment pointer, long size) {
        this.pointer = pointer;
        arena = Arena.ofShared();
        try { segment = pointer.reinterpret(size, arena, null); }
        catch (Throwable failure) { arena.close(); throw failure; }
    }
    public static Result allocate(long size) {
        if (size < 0) throw new RuntimeFault("Native malloc size exceeds the signed Long segment domain");
        try (Arena call = Arena.ofConfined()) {
            MemorySegment errors = call.allocate(Libc.CAPTURE);
            MemorySegment pointer = (MemorySegment) Libc.MALLOC.invokeExact(errors, size);
            if (pointer.address() == 0) return new Result(null, errors.get(ValueLayout.JAVA_INT, Libc.ERRNO));
            NativeMallocAllocation allocation = null;
            try {
                allocation = new NativeMallocAllocation(pointer, size);
                return new Result(allocation, 0);
            } catch (Throwable failure) {
                if (allocation == null) release(pointer); else allocation.close();
                throw failure;
            }
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw failed("Native malloc invocation failed", failure); }
    }
    private static void release(MemorySegment pointer) {
        try { Libc.FREE.invokeExact(pointer); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw failed("Native free invocation failed", failure); }
    }
    private static RuntimeFault failed(String message, Throwable cause) {
        RuntimeFault failure = new RuntimeFault(message);
        failure.initCause(cause);
        return failure;
    }
    @TruffleBoundary public Borrow borrow() {
        lifetime.readLock().lock();
        try {
            if (closed) throw new RuntimeFault("Native allocation is freed");
            return new Borrow();
        } catch (RuntimeException | Error failure) { lifetime.readLock().unlock(); throw failure; }
    }
    public final class Borrow implements AutoCloseable {
        private boolean released;
        private final Thread thread = Thread.currentThread();
        public MemorySegment segment() {
            if (released || thread != Thread.currentThread()) throw new RuntimeFault("Invalid native allocation borrow");
            return segment;
        }
        @Override @TruffleBoundary public void close() {
            if (thread != Thread.currentThread()) throw new RuntimeFault("Native allocation borrow belongs to another thread");
            if (!released) { released = true; lifetime.readLock().unlock(); }
        }
    }
    public void requireFreeable() {
        // A synchronous native callback must not deadlock upgrading its own borrow.
        if (lifetime.getReadHoldCount() != 0) throw new RuntimeFault("Cannot free an allocation borrowed by this thread");
    }
    @Override @TruffleBoundary public void close() {
        requireFreeable();
        lifetime.writeLock().lock();
        try {
            if (closed) return;
            closed = true;
            try { arena.close(); } finally { release(pointer); }
        } finally { lifetime.writeLock().unlock(); }
    }
}
