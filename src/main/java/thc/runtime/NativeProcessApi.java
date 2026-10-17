// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Machine-code process transport, including cleanup after LLVM disposal. */
public final class NativeProcessApi {
    private NativeProcessApi() {}
    private static final SymbolLookup LIBRARY = load();
    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle SPAWN = function("spawn", ValueLayout.ADDRESS, ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
    private static final MethodHandle POLL = function("poll", ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
    private static final MethodHandle TERMINATE = function("terminate", ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
    private static final MethodHandle DISPOSE = function("dispose", ValueLayout.JAVA_INT);

    private static SymbolLookup load() {
        try {
            var file = NativeLibraryFiles.createTempFile("thc-process-", ".so");
            try (var input = NativeProcessApi.class.getResourceAsStream("/thc/native/native-process-api.so")) {
                if (input == null) throw new IllegalStateException("Missing native process bridge");
                Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return SymbolLookup.libraryLookup(file, Arena.global());
        } catch (Throwable failure) { throw propagate(failure); }
    }

    private static MethodHandle function(String name, MemoryLayout... parameters) {
        return LINKER.downcallHandle(LIBRARY.find("thc_process_" + name).orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, parameters));
    }

    private static MemorySegment string(Arena arena, byte[] bytes) {
        for (byte value : bytes) if (value == 0) throw new IllegalArgumentException("Embedded NUL in process string");
        var result = arena.allocate((long) bytes.length + 1);
        result.asSlice(0, bytes.length).copyFrom(MemorySegment.ofArray(bytes));
        return result;
    }

    private static MemorySegment vector(Arena arena, List<byte[]> strings) {
        var vector = arena.allocate(((long) strings.size() + 1) * 8, 8);
        for (int index = 0; index < strings.size(); index++)
            vector.setAtIndex(ValueLayout.ADDRESS, index, string(arena, strings.get(index)));
        return vector;
    }

    /** The caller receives every returned native owner in preallocated slots. */
    public static int spawn(List<byte[]> arguments, List<byte[]> environment, int directory, byte[] cwd,
                            int[] descriptors, int flags, byte[] searchPath, MemorySegment result) {
        return spawn(arguments, environment, directory, cwd, descriptors, new int[0], new int[0], flags, searchPath, result);
    }

    public static int spawn(List<byte[]> arguments, List<byte[]> environment, int directory, byte[] cwd,
                            int[] descriptors, int[] inheritedTargets, int[] inheritedSources,
                            int flags, byte[] searchPath, MemorySegment result) {
        try (var arena = Arena.ofConfined()) {
            if (inheritedTargets.length != inheritedSources.length) throw new IllegalArgumentException("Failed requirement.");
            if (descriptors.length != 3) throw new IllegalArgumentException("Failed requirement.");
            var argv = vector(arena, arguments);
            var env = vector(arena, environment);
            var path = cwd == null ? MemorySegment.NULL : string(arena, cwd);
            var search = searchPath == null ? MemorySegment.NULL : string(arena, searchPath);
            var streams = arena.allocate(12, 4);
            for (int index = 0; index < descriptors.length; index++) streams.setAtIndex(ValueLayout.JAVA_INT, index, descriptors[index]);
            var targets = inheritedTargets.length == 0 ? MemorySegment.NULL : arena.allocateFrom(ValueLayout.JAVA_INT, inheritedTargets);
            var sources = inheritedSources.length == 0 ? MemorySegment.NULL : arena.allocateFrom(ValueLayout.JAVA_INT, inheritedSources);
            return (int) SPAWN.invokeExact(argv, env, directory, path, streams, targets, sources, inheritedTargets.length, flags, search, result);
        } catch (Throwable failure) { throw propagate(failure); }
    }

    /** Status/exit are initialized even for failure, as in getProcessExitCode. */
    public static ProcessResult poll(int pidfd) {
        try (var arena = Arena.ofConfined()) {
            var result = arena.allocate(8, 4);
            int error = (int) POLL.invokeExact(pidfd, result);
            return new ProcessResult(error == 0 ? result.get(ValueLayout.JAVA_INT, 0) : -1,
                result.get(ValueLayout.JAVA_INT, 4), error);
        } catch (Throwable failure) { throw propagate(failure); }
    }

    public static ProcessResult terminate(int pidfd) {
        try (var arena = Arena.ofConfined()) {
            var error = arena.allocate(ValueLayout.JAVA_INT);
            int status = (int) TERMINATE.invokeExact(pidfd, error);
            return new ProcessResult(status, null, error.get(ValueLayout.JAVA_INT, 0));
        } catch (Throwable failure) { throw propagate(failure); }
    }

    public static void dispose(int pidfd) {
        try {
            int error = (int) DISPOSE.invokeExact(pidfd);
            if (error != 0) throw new NativeFileException("process disposal", error);
        } catch (Throwable failure) { throw propagate(failure); }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
