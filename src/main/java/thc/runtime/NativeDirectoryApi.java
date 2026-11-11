// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Filesystem callbacks may run outside an entered guest context. This bridge
 * therefore uses FFM, not LLVM or a guest-context callback. */
public final class NativeDirectoryApi {
    private NativeDirectoryApi() {}
    private static final SymbolLookup LIBRARY = library();
    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle OPEN = LINKER.downcallHandle(LIBRARY.find("thc_directory_open").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle NAME = LINKER.downcallHandle(LIBRARY.find("thc_directory_name").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
    private static final MethodHandle DUP_COMMAND = LINKER.downcallHandle(LIBRARY.find("thc_directory_dup_command").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT));
    private static final MethodHandle RANGE_ERROR = LINKER.downcallHandle(LIBRARY.find("thc_directory_range_error").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT));
    private static final class Streams {
        private static final MethodHandle OPEN = LINKER.downcallHandle(LIBRARY.find("thc_directory_stream_open").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        private static final MethodHandle FD_OPEN = LINKER.downcallHandle(LIBRARY.find("thc_directory_stream_fdopen").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        private static final MethodHandle READ = LINKER.downcallHandle(LIBRARY.find("thc_directory_stream_read").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        private static final MethodHandle CLOSE = LINKER.downcallHandle(LIBRARY.find("thc_directory_stream_close").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    }

    private static SymbolLookup library() {
        try {
            String suffix = StdioHostAbi.load().librarySuffix();
            var file = NativeLibraryFiles.createTempFile("thc-directory-", suffix);
            try (var input = NativeDirectoryOwner.class.getResourceAsStream("/thc/native/native-directory-api" + suffix)) {
                if (input == null) throw new IllegalStateException("Missing native directory bridge");
                Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return SymbolLookup.libraryLookup(file, Arena.global());
        } catch (Throwable failure) { throw propagate(failure); }
    }
    public static int duplicateCommand() {
        try { return (int) DUP_COMMAND.invokeExact(); }
        catch (Throwable failure) { throw propagate(failure); }
    }
    public static int rangeError() {
        try { return (int) RANGE_ERROR.invokeExact(); }
        catch (Throwable failure) { throw propagate(failure); }
    }
    private static void requireStreams() {
        if (!thc.NativeIO.supportedPosixHost())
            throw new UnsupportedOperationException("Directory streams require the verified glibc readdir contract");
    }
    public static void openStream(int at, byte[] path, MemorySegment slot) {
        requireStreams();
        try (var arena = Arena.ofConfined()) {
            var bytes = arena.allocate(path.length);
            bytes.copyFrom(MemorySegment.ofArray(path));
            int error = (int) Streams.OPEN.invokeExact(at, bytes, slot);
            if (error != 0) throw new NativeFileException("opendir", error);
        } catch (Throwable failure) { throw propagate(failure); }
    }
    public static void fdOpenStream(MemorySegment lease, MemorySegment slot) {
        requireStreams();
        try {
            int error = (int) Streams.FD_OPEN.invokeExact(lease, slot);
            if (error != 0) throw new NativeFileException("fdopendir", error);
        } catch (Throwable failure) { throw propagate(failure); }
    }
    @FunctionalInterface public interface ReadResultConsumer {
        void accept(int result, int error, byte[] name);
    }
    public static void readStream(MemorySegment stream, int errno, ReadResultConsumer receive) {
        requireStreams();
        try (var arena = Arena.ofConfined()) {
            var name = arena.allocate(ValueLayout.ADDRESS);
            var size = arena.allocate(ValueLayout.JAVA_LONG);
            var error = arena.allocate(ValueLayout.JAVA_INT);
            error.set(ValueLayout.JAVA_INT, 0, errno);
            int result = (int) Streams.READ.invokeExact(stream, name, size, error);
            long count = size.get(ValueLayout.JAVA_LONG, 0);
            check(result == 0 || result == -1);
            check(count >= 0 && count < Integer.MAX_VALUE);
            var pointer = name.get(ValueLayout.ADDRESS, 0);
            check((result == -1) == (pointer.address() == 0));
            byte[] bytes = result == -1 ? null : pointer.reinterpret(count + 1).toArray(ValueLayout.JAVA_BYTE);
            check(bytes == null || bytes[bytes.length - 1] == 0);
            receive.accept(result, error.get(ValueLayout.JAVA_INT, 0), bytes);
        } catch (Throwable failure) { throw propagate(failure); }
    }
    public static int closeStream(MemorySegment slot) {
        requireStreams();
        try { return (int) Streams.CLOSE.invokeExact(slot); }
        catch (Throwable failure) { throw propagate(failure); }
    }
    public static void open(int at, byte[] path, MemorySegment slot) {
        try (var arena = Arena.ofConfined()) {
            check(path.length != 0 && path[path.length - 1] == 0);
            for (int i = 0; i < path.length - 1; i++) check(path[i] != 0);
            var bytes = arena.allocate(path.length);
            bytes.copyFrom(MemorySegment.ofArray(path));
            int error = (int) OPEN.invokeExact(at, bytes, slot);
            if (error != 0) throw new NativeFileException("chdir", error);
        } catch (Throwable failure) { throw propagate(failure); }
    }
    public static byte[] name(int fd, int capacity) {
        try (var arena = Arena.ofConfined()) {
            if (capacity < 0) throw new IllegalArgumentException("Failed requirement.");
            var output = arena.allocate(Math.max(1, capacity));
            long result = (long) NAME.invokeExact(fd, output, (long) capacity);
            if (result < 0) throw new NativeFileException("getcwd", (int) -result);
            check(result < capacity && output.get(ValueLayout.JAVA_BYTE, result) == 0);
            var bytes = new byte[(int) result];
            MemorySegment.copy(output, 0, MemorySegment.ofArray(bytes), 0, result);
            return bytes;
        } catch (Throwable failure) { throw propagate(failure); }
    }
    private static void check(boolean condition) {
        if (!condition) throw new IllegalStateException("Check failed.");
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E {
        throw (E) failure;
    }
}
