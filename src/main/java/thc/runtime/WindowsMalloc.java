// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import thc.Json;

import static java.lang.foreign.ValueLayout.*;

/** One DLL owns both CRT bindings. Its C call captures errno before returning
 * to Java; Windows GetLastError and the JVM's default CRT are not substitutes.
 * Initialized only after ManagedNativeAllocations checks context/native access.
 * The existing allocation registry retains every lifetime and borrow boundary. */
final class WindowsMalloc {
    static final MethodHandle malloc;
    static final MethodHandle free;

    static {
        try {
            var linker = Linker.nativeLinker();
            check(WindowsDirectoryStreams.supportedHost());
            Object parsed;
            try (var stream = WindowsMalloc.class.getResourceAsStream("/thc/cbits/windows-malloc-abi.json")) {
                if (stream == null) throw RuntimeFault.fault("Missing Windows malloc ABI receipt");
                parsed = Json.INSTANCE.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
            if (!(parsed instanceof Map<?, ?>)) throw RuntimeFault.fault("Missing Windows malloc ABI receipt");
            var document = (Map<?, ?>) parsed;
            if (!(document.get("layout") instanceof Map<?, ?>)) throw RuntimeFault.fault("Missing Windows malloc layout");
            var layout = (Map<?, ?>) document.get("layout");
            var target = document.get("target") instanceof String text ? text.split("-", -1) : new String[0];
            check(Long.valueOf(1).equals(document.get("schema")) && target.length == 4 && target[0].equals("x86_64") &&
                target[2].equals("windows") && target[3].equals("gnu") &&
                Long.valueOf(0x0808080404L).equals(layout.get("abi")) && layout.get("enomem") instanceof Long enomem &&
                enomem >= 1 && enomem <= Integer.MAX_VALUE && enomem.equals(layout.get("failureErrno")));
            var modules = new HashSet<String>();
            for (var name : List.of("mallocModule", "freeModule", "errnoModule")) {
                if (!(layout.get(name) instanceof String)) throw RuntimeFault.fault("Missing Windows malloc CRT identity");
                modules.add(((String) layout.get(name)).toLowerCase(Locale.ROOT));
            }
            check(modules.size() == 1 && modules.iterator().next().endsWith(".dll"));
            byte[] bytes;
            try (var stream = WindowsMalloc.class.getResourceAsStream("/thc/cbits/windows-malloc.dll")) {
                if (stream == null) throw RuntimeFault.fault("Missing Windows malloc bridge");
                bytes = stream.readAllBytes();
            }
            check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(document.get("dllSha256")));
            var library = NativeLibraryFiles.createTempFile("thc-malloc-", ".dll");
            Files.write(library, bytes);
            var lookup = SymbolLookup.libraryLookup(library, Arena.global());
            var abi = linker.downcallHandle(lookup.find("thc_windows_malloc_abi").orElseThrow(), FunctionDescriptor.of(JAVA_LONG));
            var error = linker.downcallHandle(lookup.find("thc_windows_malloc_enomem").orElseThrow(), FunctionDescriptor.of(JAVA_INT));
            check((long) abi.invokeExact() == (Long) layout.get("abi") && (long) (int) error.invokeExact() == (Long) layout.get("enomem"));
            malloc = linker.downcallHandle(lookup.find("thc_windows_malloc").orElseThrow(), FunctionDescriptor.of(ADDRESS, JAVA_LONG, ADDRESS));
            free = linker.downcallHandle(lookup.find("thc_windows_free").orElseThrow(), FunctionDescriptor.ofVoid(ADDRESS));
        } catch (Error error) {
            throw error;
        } catch (Throwable failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static void check(boolean condition) {
        if (!condition) throw new IllegalStateException("Check failed.");
    }

    private WindowsMalloc() {}
}
