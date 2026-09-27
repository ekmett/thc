// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Native transport for the original configured USE_LIBDW=0 finalizers. The
 * build rejects any enabled libdw configuration. These two original C bodies
 * are empty, retain no pointer, have no callback and require no filesystem IO.
 * Their code has process lifetime; CFinalizerFunction retains context ownership.
 */
internal object WindowsLibdwFinalizers {
    private val lookup: SymbolLookup by lazy {
        check(System.getProperty("os.name").startsWith("Windows"))
        val library = Files.createTempFile("thc-libdw-unavailable-", ".dll")
        library.toFile().deleteOnExit()
        WindowsLibdwFinalizers::class.java.getResourceAsStream("/thc/cbits/libdw-unavailable.dll").use { source ->
            if (source == null) fault("Missing original Windows libdw finalizers")
            Files.copy(source, library, StandardCopyOption.REPLACE_EXISTING)
        }
        SymbolLookup.libraryLookup(library, Arena.global())
    }

    fun function(symbol: String): MethodHandle {
        check(symbol == "libdwPoolRelease" || symbol == "backtraceFree")
        // Empty original bodies satisfy the critical-call contract. Heap access
        // pins the actual managed segment only for this call; no copied or
        // fabricated pointer and no post-call native address escapes.
        return Linker.nativeLinker().downcallHandle(lookup.find(symbol).orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS), Linker.Option.critical(true))
    }

    fun invoke(function: MethodHandle, address: ManagedAddress) {
        if (address === ManagedAddress.nullAddress()) {
            function.invokeExact(MemorySegment.NULL)
            return
        }
        address.withNativeBorrow {
            address.requireByteRegion(0)
            if (address.hasNativeStorage()) address.withNativeSegment { pointer ->
                function.invokeExact(pointer); Unit
            } else synchronized(address.cbitsOwner() ?: address) {
                address.requireByteRegion(0)
                function.invokeExact(address.cbitsSegment().asSlice(address.cbitsOffset())); Unit
            }
        }
    }
}
