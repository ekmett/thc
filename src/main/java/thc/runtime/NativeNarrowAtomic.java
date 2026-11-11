// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import static thc.runtime.RuntimeServiceStatus.fault;
final class NativeNarrowAtomic {
    private NativeNarrowAtomic() {}
    private static final SymbolLookup LIBRARY = load();
    private static final MethodHandle COMPARE = Linker.nativeLinker().downcallHandle(LIBRARY.find("thc_atomic_cas_narrow").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
    private static SymbolLookup load() {
        String extension = System.getProperty("os.name").startsWith("Mac") ? ".dylib" : ".so";
        try {
            var path = NativeLibraryFiles.createTempFile("thc-atomic-", extension);
            try (var input = NativeNarrowAtomic.class.getResourceAsStream("/thc/native/native-atomic-api" + extension)) {
                if (input == null) throw fault("Native narrow atomic provider is unavailable for " + System.getProperty("os.name") + "/" + System.getProperty("os.arch"));
                Files.copy(input, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return SymbolLookup.libraryLookup(path, Arena.global());
        } catch (Throwable failure) { throw propagate(failure); }
    }
    @TruffleBoundary static long cas(MemorySegment segment, long width, long expected, long desired) {
        try { return (long) COMPARE.invokeExact(segment, width, expected, desired); }
        catch (Throwable failure) { throw propagate(failure); }
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
