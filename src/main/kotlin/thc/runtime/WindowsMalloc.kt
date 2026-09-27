// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.*
import java.nio.file.Files
import java.security.MessageDigest
import thc.Json

/** One DLL owns both CRT bindings. Its C call captures errno before returning
 * to Java; Windows GetLastError and the JVM's default CRT are not substitutes.
 * Initialized only after ManagedNativeAllocations checks context/native access.
 * The existing allocation registry retains every lifetime and borrow boundary. */
internal object WindowsMalloc {
    private val linker = Linker.nativeLinker()
    private val lookup = run {
        check(WindowsDirectoryStreams.supportedHost())
        val document = javaClass.getResourceAsStream("/thc/cbits/windows-malloc-abi.json")
            ?.bufferedReader()?.use { Json.parse(it.readText()) } as? Map<*, *>
            ?: fault("Missing Windows malloc ABI receipt")
        val layout = document["layout"] as? Map<*, *> ?: fault("Missing Windows malloc layout")
        val target = (document["target"] as? String)?.split('-') ?: emptyList()
        check(document["schema"] == 1L && target.size == 4 && target[0] == "x86_64" &&
            target.drop(2) == listOf("windows", "gnu") &&
            layout["abi"] == 0x0808080404L && layout["enomem"] is Long &&
            (layout["enomem"] as Long) in 1L..Int.MAX_VALUE.toLong() &&
            layout["failureErrno"] == layout["enomem"])
        val modules = listOf("mallocModule", "freeModule", "errnoModule").map {
            (layout[it] as? String)?.lowercase() ?: fault("Missing Windows malloc CRT identity")
        }
        check(modules.toSet().size == 1 && modules.first().endsWith(".dll"))
        val bytes = javaClass.getResourceAsStream("/thc/cbits/windows-malloc.dll")?.use { it.readBytes() }
            ?: fault("Missing Windows malloc bridge")
        check(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == document["dllSha256"])
        val library = Files.createTempFile("thc-malloc-", ".dll")
        library.toFile().deleteOnExit()
        Files.write(library, bytes)
        SymbolLookup.libraryLookup(library, Arena.global()).also { api ->
            val abi = linker.downcallHandle(api.find("thc_windows_malloc_abi").orElseThrow(), FunctionDescriptor.of(JAVA_LONG))
            val error = linker.downcallHandle(api.find("thc_windows_malloc_enomem").orElseThrow(), FunctionDescriptor.of(JAVA_INT))
            check((abi.invokeExact() as Long) == layout["abi"] && (error.invokeExact() as Int).toLong() == layout["enomem"])
        }
    }
    val malloc = linker.downcallHandle(lookup.find("thc_windows_malloc").orElseThrow(),
        FunctionDescriptor.of(ADDRESS, JAVA_LONG, ADDRESS))
    val free = linker.downcallHandle(lookup.find("thc_windows_free").orElseThrow(), FunctionDescriptor.ofVoid(ADDRESS))
}
