// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.io.IOException;
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

/** Native transport for the original configured USE_LIBDW=0 finalizers. The
 * build rejects any enabled libdw configuration. These two original C bodies
 * are empty, retain no pointer, have no callback and require no filesystem IO.
 * Their code has process lifetime; CFinalizerFunction retains context ownership. */
public final class WindowsLibdwFinalizers {
    private WindowsLibdwFinalizers() {}
    private static volatile SymbolLookup lookup;
    private static SymbolLookup lookup() {
        var result = lookup;
        if (result == null) synchronized (WindowsLibdwFinalizers.class) {
            result = lookup;
            if (result == null) {
                if (!System.getProperty("os.name").startsWith("Windows")) throw new IllegalStateException("Check failed.");
                try {
                    var library = Files.createTempFile("thc-libdw-unavailable-", ".dll");
                    library.toFile().deleteOnExit();
                    try (var source = WindowsLibdwFinalizers.class.getResourceAsStream("/thc/cbits/libdw-unavailable.dll")) {
                        if (source == null) throw fault("Missing original Windows libdw finalizers");
                        Files.copy(source, library, StandardCopyOption.REPLACE_EXISTING);
                    }
                    result = SymbolLookup.libraryLookup(library, Arena.global());
                } catch (IOException failure) { throw propagate(failure); }
                lookup = result;
            }
        }
        return result;
    }
    public static MethodHandle function(String symbol) {
        if (!symbol.equals("libdwPoolRelease") && !symbol.equals("backtraceFree")) throw new IllegalStateException("Check failed.");
        // Empty original bodies satisfy the critical-call contract. Heap access
        // pins the actual managed segment only for this call; no copied or
        // fabricated pointer and no post-call native address escapes.
        return Linker.nativeLinker().downcallHandle(lookup().find(symbol).orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS), Linker.Option.critical(true));
    }
    public static void invoke(MethodHandle function, ManagedAddress address) {
        try {
            if (address == ManagedAddress.nullAddress()) {
                function.invokeExact(MemorySegment.NULL);
                return;
            }
            var nativeOwner = address.nativeAllocation();
            try (var ignored = nativeOwner == null ? null : nativeOwner.borrow()) {
                address.requireByteRegion(0, false);
                if (address.hasNativeStorage()) {
                    address.withNativeSegmentInt(pointer -> {
                        try { function.invokeExact(pointer); }
                        catch (Throwable failure) { throw propagate(failure); }
                        return 0;
                    });
                } else {
                    var owner = address.cbitsOwner();
                    synchronized (owner == null ? address : owner) {
                        address.requireByteRegion(0, false);
                        function.invokeExact(address.cbitsSegment().asSlice(address.cbitsOffset()));
                    }
                }
            }
        } catch (Throwable failure) { throw propagate(failure); }
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
