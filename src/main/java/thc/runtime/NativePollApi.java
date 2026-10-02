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

/** Private readiness transport for the selected Linux/Darwin native-file ABI.
 * Host libc calls remain available during context cancellation and disposal. */
public final class NativePollApi {
    private NativePollApi() {}
    private static final StdioHostAbi ABI = hostAbi();
    private static final boolean DARWIN = ABI.librarySuffix().equals(".dylib");
    private static final int EAGAIN = DARWIN ? 35 : 11;
    private static final Linker LINKER = Linker.nativeLinker();
    public static final MemoryLayout CAPTURE = Linker.Option.captureStateLayout();
    private static final long ERRNO_OFFSET = CAPTURE.byteOffset(MemoryLayout.PathElement.groupElement("errno"));
    private static final MethodHandle POLL = function("poll", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, DARWIN ? ValueLayout.JAVA_INT : ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT);
    private static final MethodHandle READ = function("read", ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);
    private static final MethodHandle WRITE = function("write", ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);
    private static final MethodHandle CLOSE = function("close", ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);

    private static MethodHandle function(String name, ValueLayout result, MemoryLayout... arguments) {
        return LINKER.downcallHandle(LINKER.defaultLookup().find(name).orElseThrow(),
            FunctionDescriptor.of(result, arguments), Linker.Option.captureCallState("errno"));
    }

    private static StdioHostAbi hostAbi() {
        if (!NativeFileProvider.supportedHost())
            throw new IllegalStateException("Native readiness requires matching selected-ABI resources");
        try { return StdioHostAbi.load(); }
        catch (Throwable failure) { throw propagate(failure); }
    }

    private static final class Linux {
        private static final MethodHandle EVENT = function("eventfd", ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
    }

    private static final class Darwin {
        private static final MethodHandle PIPE = function("pipe", ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
        private static final MethodHandle SELECT = function("pselect$DARWIN_EXTSN", ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        private static final MethodHandle FCNTL = LINKER.downcallHandle(LINKER.defaultLookup().find("fcntl").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
            Linker.Option.firstVariadicArg(2), Linker.Option.captureCallState("errno"));
    }

    /** Linux shares one eventfd; Darwin owns both ends of a nonblocking pipe. */
    record Wakeup(int read, int write) implements AutoCloseable {
        @Override public void close() {
            try { if (write != read) NativePollApi.close(write); }
            finally { NativePollApi.close(read); }
        }
    }

    static Wakeup wake() {
        if (!DARWIN) {
            int fd = eventfd();
            return new Wakeup(fd, fd);
        }
        try (var arena = Arena.ofConfined()) {
            var errors = arena.allocate(CAPTURE);
            var descriptors = arena.allocate(8, 4);
            if ((int) Darwin.PIPE.invokeExact(errors, descriptors) != 0)
                throw new NativeFileException("readiness pipe", errno(errors));
            var wake = new Wakeup(descriptors.get(ValueLayout.JAVA_INT, 0), descriptors.get(ValueLayout.JAVA_INT, 4));
            try {
                for (int fd : new int[] {wake.read(), wake.write()}) {
                    if ((int) Darwin.FCNTL.invokeExact(errors, fd, (int) ABI.flagConstant(OriginalStdioOp.F_SETFD),
                            (int) ABI.flagConstant(OriginalStdioOp.FD_CLOEXEC)) < 0 ||
                        (int) Darwin.FCNTL.invokeExact(errors, fd, (int) ABI.flagConstant(OriginalStdioOp.F_SETFL),
                            (int) ABI.flagConstant(OriginalStdioOp.O_NONBLOCK)) < 0)
                        throw new NativeFileException("configure readiness pipe", errno(errors));
                }
                return wake;
            } catch (Throwable failure) {
                try { wake.close(); } catch (Throwable closing) { failure.addSuppressed(closing); }
                throw failure;
            }
        } catch (Throwable failure) { throw propagate(failure); }
    }

    private static int errno(MemorySegment errors) { return errors.get(ValueLayout.JAVA_INT, ERRNO_OFFSET); }

    public static int eventfd() {
        if (DARWIN) throw new UnsupportedOperationException("Native event manager requires Linux eventfd");
        try (var arena = Arena.ofConfined()) {
            var errors = arena.allocate(CAPTURE);
            int fd = (int) Linux.EVENT.invokeExact(errors, 0, 0x800 | 0x80000); // NONBLOCK | CLOEXEC.
            if (fd < 0) throw new NativeFileException("eventfd", errno(errors));
            return fd;
        } catch (Throwable failure) { throw propagate(failure); }
    }

    /** Darwin poll/kevent miss FIFO EOF; unlimited pselect preserves readiness
     * without an FD_SETSIZE ceiling. Only the owned target and wake reader enter. */
    static int readiness(MemorySegment descriptors, int timeout) {
        if (!DARWIN) return poll(descriptors, timeout, 2L);
        try (var arena = Arena.ofConfined()) {
            int fd = descriptors.get(ValueLayout.JAVA_INT, 0);
            int wake = descriptors.get(ValueLayout.JAVA_INT, 8);
            boolean writing = descriptors.get(ValueLayout.JAVA_SHORT, 4) == 4;
            int count = Math.addExact(Math.max(fd, wake), 1);
            long bytes = ((long) count + 31) / 32 * 4;
            var readers = arena.allocate(bytes, 4);
            var target = writing ? arena.allocate(bytes, 4) : readers;
            watch(target, fd);
            watch(readers, wake);
            var duration = MemorySegment.NULL;
            if (timeout >= 0) {
                duration = arena.allocate(16, 8); // Darwin LP64 timespec.
                duration.set(ValueLayout.JAVA_LONG, 0, (long) timeout / 1000);
                duration.set(ValueLayout.JAVA_LONG, 8, (long) (timeout % 1000) * 1_000_000);
            }
            var errors = arena.allocate(CAPTURE);
            MemorySegment writers = writing ? target : MemorySegment.NULL;
            int result = (int) Darwin.SELECT.invokeExact(errors, count, readers,
                writers, MemorySegment.NULL, duration, MemorySegment.NULL);
            if (result < 0) {
                int error = errno(errors);
                if (error == 4) throw new InterruptedException();
                throw new NativeFileException("pselect readiness", error);
            }
            descriptors.set(ValueLayout.JAVA_SHORT, 6, (short) (ready(target, fd) ? (writing ? 4 : 1) : 0));
            descriptors.set(ValueLayout.JAVA_SHORT, 14, (short) (ready(readers, wake) ? 1 : 0));
            return result;
        } catch (Throwable failure) { throw propagate(failure); }
    }

    private static void watch(MemorySegment set, int fd) {
        long offset = (long) (fd / 32) * 4;
        set.set(ValueLayout.JAVA_INT, offset, set.get(ValueLayout.JAVA_INT, offset) | (1 << (fd % 32)));
    }

    private static boolean ready(MemorySegment set, int fd) {
        return (set.get(ValueLayout.JAVA_INT, (long) (fd / 32) * 4) & (1 << (fd % 32))) != 0;
    }

    public static int poll(MemorySegment descriptors, int timeout, long count) {
        try (var arena = Arena.ofConfined()) {
            var errors = arena.allocate(CAPTURE);
            // Darwin nfds_t is unsigned int; Linux LP64 uses unsigned long.
            int result = DARWIN
                ? (int) POLL.invokeExact(errors, descriptors, Math.toIntExact(count), timeout)
                : (int) POLL.invokeExact(errors, descriptors, count, timeout);
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
                // Retry EINTR before transfer; EAGAIN already guarantees a pending wake.
                if (result < 0 && error == 4) continue;
                if (result < 0 && error == EAGAIN) return;
                throw new NativeFileException("readiness wake write", error);
            }
        } catch (Throwable failure) { throw propagate(failure); }
    }

    public static void drain(int fd, MemorySegment value, MemorySegment errors) {
        try {
            while (true) {
                long result = (long) READ.invokeExact(errors, fd, value, 8L);
                if (result > 0) {
                    if (DARWIN) continue; // A pipe retains every queued write; drain to EAGAIN.
                    if (result == 8L) return; // eventfd resets its complete counter in one read.
                }
                int error = errno(errors);
                if (result < 0 && error == 4) continue;
                if (result < 0 && error == EAGAIN) return;
                throw new NativeFileException("readiness wake read", error);
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
