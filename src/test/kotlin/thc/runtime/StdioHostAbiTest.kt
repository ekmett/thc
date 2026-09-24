// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Json

class StdioHostAbiTest {
    private fun document(): Map<String, Any?> = StdioHostAbi::class.java.getResourceAsStream("/thc/native/stdio-host-abi.json")!!.use {
        @Suppress("UNCHECKED_CAST")
        Json.parse(it.reader().readText()) as Map<String, Any?>
    }
    private val system = System.getProperty("os.name").let { if (it.startsWith("Mac")) "Darwin" else it }
    private val arch = System.getProperty("os.arch")
    private fun parse(value: Any?) = StdioHostAbi.parse(value, system, arch)

    @Test fun actualProbeLoadsWithoutNativeAccessAndMapsPrivateCategoriesToCValues() {
        val document = document(); val raw = document["errno"] as Map<*, *>
        val abi = parse(document)
        for ((kind, name) in listOf(1L to "ENOENT", 2L to "EACCES", 3L to "EEXIST", 4L to "EBADF",
            5L to "EINVAL", 6L to "EIO", 7L to "ENOTSUP", 8L to "EBUSY", 9L to "EISDIR"))
            assertEquals((raw[name] as Number).toLong(), abi.error(kind))
        assertEquals(abi.error(6), abi.error(0)); assertEquals(abi.error(6), abi.error(Long.MAX_VALUE))
    }

    @Test fun mismatchedPlatformWidthsAndMalformedErrnoReject() {
        val original = document()
        for ((key, value) in listOf("schema" to true, "schema" to 1.0, "schema" to 2,
            "system" to "Windows", "architecture" to "riscv64", "target" to "x86_64-unknown-linux-gnux32",
            "target" to "aarch64", "target" to null))
            assertThrows(RuntimeFault::class.java) { parse(original + (key to value)) }
        val widths = original["widths"] as Map<*, *>
        for ((name, wrong) in listOf("charBits" to 16, "int" to 8, "pointer" to 4, "size" to 4, "ssize" to 4,
            "int" to 4.0, "pointer" to null))
            assertThrows(RuntimeFault::class.java) { parse(original + ("widths" to (widths + (name to wrong)))) }
        val errors = original["errno"] as Map<*, *>
        for (wrong in listOf(null, true, 9.0, 0, -1, Int.MAX_VALUE.toLong() + 1))
            assertThrows(RuntimeFault::class.java) { parse(original + ("errno" to (errors + ("EBADF" to wrong)))) }
        assertThrows(RuntimeFault::class.java) { parse(original + ("errno" to (errors - "EBADF"))) }
        assertThrows(RuntimeFault::class.java) { parse(original + ("errno" to (errors + ("extra" to 1)))) }
    }
}
