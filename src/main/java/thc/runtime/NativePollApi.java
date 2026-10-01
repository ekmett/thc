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
import thc.NativeIO;

/** Loaded only by the explicit Linux native-file capability. Class initialization
 * keeps eventfd lookup lazy on other hosts; these constants are the Linux ABI. */
public final class NativePollApi {
    private NativePollApi() {}
    private static final Linker LINKER = Linker.nativeLinker();
    public static final MemoryLayout CAPTURE = Linker.Option.captureStateLayout();
    private static final long ERRNO_OFFSET = CAPTURE.byteOffset(MemoryLayout.PathElement.groupElement("errno"));
    private static final MethodHandle EVENT = function("eventfd", ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
    private static final MethodHandle POLL = function("poll", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT);
    private static final MethodHandle READ = function("read", ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);
    private static final MethodHandle WRITE = function("write", ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);
    private static final MethodHandle CLOSE = function("close", ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);

    private static MethodHandle function(String name, ValueLayout result, MemoryLayout... arguments) {
        if (!NativeIO.supportedPosixHost())
            throw new IllegalStateException("Native readiness currently requires Linux x86_64");
        return LINKER.downcallHandle(LINKER.defaultLookup().find(name).orElseThrow(),
            FunctionDescriptor.of(result, arguments), Linker.Option.captureCallState("errno"));
    }

    private static int errno(MemorySegment errors) { return errors.get(ValueLayout.JAVA_INT, ERRNO_OFFSET); }

    public static int eventfd() {
        try (var arena = Arena.ofConfined()) {
            var errors = arena.allocate(CAPTURE);
            int fd = (int) EVENT.invokeExact(errors, 0, 0x800 | 0x80000); // NONBLOCK | CLOEXEC.
            if (fd < 0) throw new NativeFileException("eventfd", errno(errors));
            return fd;
        } catch (Throwable failure) { throw propagate(failure); }
    }

    public static int poll(MemorySegment descriptors, int timeout) { return poll(descriptors, timeout, 2L); }

    public static int poll(MemorySegment descriptors, int timeout, long count) {
        try (var arena = Arena.ofConfined()) {
            var errors = arena.allocate(CAPTURE);
            int result = (int) POLL.invokeExact(errors, descriptors, count, timeout);
            if (result < 0) {
                int error = errno(errors);
                if (error == 4) throw new InterruptedException(); // EINTR: retry through a safepoint.
                throw new NativeFileException("poll", error);
            }
            return result;
        } catch (Throwable failure) { throw propagate(failure); }
    }

    public static void signal(int fd, MemorySegment value, MemorySegment errors) {
        try {
            while (true) {
                long result = (long) WRITE.invokeExact(errors, fd, value, 8L);
                if (result == 8L) return;
                int error = errno(errors);
                // Retry EINTR before transfer; EAGAIN already guarantees a wake counter.
                if (result < 0 && error == 4) continue;
                if (result < 0 && error == 11) return;
                throw new NativeFileException("eventfd write", error);
            }
        } catch (Throwable failure) { throw propagate(failure); }
    }

    public static void drain(int fd, MemorySegment value, MemorySegment errors) {
        try {
            while (true) {
                long result = (long) READ.invokeExact(errors, fd, value, 8L);
                if (result == 8L) return;
                int error = errno(errors);
                if (result < 0 && error == 4) continue;
                if (result < 0 && error == 11) return;
                throw new NativeFileException("eventfd read", error);
            }
        } catch (Throwable failure) { throw propagate(failure); }
    }

    public static void close(int fd) {
        try (var arena = Arena.ofConfined()) {
            var errors = arena.allocate(CAPTURE);
            if ((int) CLOSE.invokeExact(errors, fd) != 0)
                throw new NativeFileException("close readiness descriptor", errno(errors));
        } catch (Throwable failure) { throw propagate(failure); }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
