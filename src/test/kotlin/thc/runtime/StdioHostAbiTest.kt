// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Json
import java.io.File
import java.security.MessageDigest

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
        val source = File(System.getProperty("thc.projectRoot"), "src/main/c/stdio-abi-probe.c").readBytes()
        assertEquals(OriginalStdioChecks.hex(MessageDigest.getInstance("SHA-256").digest(source)), document["sourceSha256"])
        val abi = parse(document)
        for ((kind, name) in listOf(1L to "ENOENT", 2L to "EACCES", 3L to "EEXIST", 4L to "EBADF",
            5L to "EINVAL", 6L to "EIO", 7L to "ENOTSUP", 8L to "EBUSY", 9L to "EISDIR", 10L to "EMFILE"))
            assertEquals((raw[name] as Number).toLong(), abi.error(kind))
        assertEquals((raw["ENOTTY"] as Number).toLong(), abi.notTerminal())
        assertEquals(abi.error(6), abi.error(0)); assertEquals(abi.error(6), abi.error(Long.MAX_VALUE))
        val seek = document["seek"] as Map<*, *>
        for ((mode, operation) in listOf(OriginalStdioOp.SEEK_SET, OriginalStdioOp.SEEK_CUR, OriginalStdioOp.SEEK_END).withIndex()) {
            val constant = (seek[operation.name] as Number).toLong()
            assertEquals(constant, abi.seekConstant(operation))
            assertEquals(mode.toLong(), abi.seekMode(constant))
        }
    }

    @Test fun seekConstantsAreIndependentSignedCIntsNotPrivateModes() {
        val values = mapOf("SEEK_SET" to Int.MIN_VALUE.toLong(), "SEEK_CUR" to 71L, "SEEK_END" to -9L)
        val abi = parse(document() + ("seek" to values))
        for ((mode, operation) in listOf(OriginalStdioOp.SEEK_SET, OriginalStdioOp.SEEK_CUR, OriginalStdioOp.SEEK_END).withIndex()) {
            assertEquals(values.getValue(operation.name), abi.seekConstant(operation))
            assertEquals(mode.toLong(), abi.seekMode(values.getValue(operation.name)))
        }
        for (unknown in listOf(0L, 1L, 2L, Long.MIN_VALUE, Long.MAX_VALUE)) assertNull(abi.seekMode(unknown))
        assertThrows(RuntimeFault::class.java) { abi.seekConstant(OriginalStdioOp.ERRNO) }
    }

    @Test fun seekProbeRejectsMissingExtraDuplicateAndNonCIntFields() {
        val original = document()
        val seek = original["seek"] as Map<*, *>
        for (field in seek.keys) {
            for (wrong in listOf(null, true, false, 1.0, "0", Int.MIN_VALUE.toLong() - 1, Int.MAX_VALUE.toLong() + 1))
                assertThrows(RuntimeFault::class.java) { parse(original + ("seek" to (seek + (field to wrong)))) }
            assertThrows(RuntimeFault::class.java) { parse(original + ("seek" to (seek - field))) }
            for (other in seek.keys - field)
                assertThrows(RuntimeFault::class.java) { parse(original + ("seek" to (seek + (field to seek[other])))) }
        }
        for (wrong in listOf(null, emptyList<Any?>(), emptyMap<String, Any?>(), seek + ("extra" to 0)))
            assertThrows(RuntimeFault::class.java) { parse(original + ("seek" to wrong)) }
        assertThrows(RuntimeFault::class.java) { parse(original - "seek") }
    }

    @Test fun mismatchedPlatformWidthsAndMalformedErrnoReject() {
        val original = document()
        for ((key, value) in listOf("schema" to true, "schema" to 1.0, "schema" to 2,
            "system" to "Windows", "architecture" to "riscv64", "target" to "x86_64-unknown-linux-gnux32",
            "target" to "aarch64", "target" to null))
            assertThrows(RuntimeFault::class.java) { parse(original + (key to value)) }
        val widths = original["widths"] as Map<*, *>
        for ((name, wrong) in listOf("charBits" to 16, "int" to 8, "bool" to 4, "pointer" to 4, "size" to 4, "ssize" to 4,
            "int" to 4.0, "pointer" to null))
            assertThrows(RuntimeFault::class.java) { parse(original + ("widths" to (widths + (name to wrong)))) }
        val errors = original["errno"] as Map<*, *>
        for (wrong in listOf(null, true, 9.0, 0, -1, Int.MAX_VALUE.toLong() + 1))
            assertThrows(RuntimeFault::class.java) { parse(original + ("errno" to (errors + ("EBADF" to wrong)))) }
        assertThrows(RuntimeFault::class.java) { parse(original + ("errno" to (errors - "EBADF"))) }
        assertThrows(RuntimeFault::class.java) { parse(original + ("errno" to (errors + ("extra" to 1)))) }
    }

    @Test fun everyProbeFieldRequiresAnExactIntegerAndClosedFieldSet() {
        val original = document()
        for (section in listOf("widths", "errno")) {
            val fields = original[section] as Map<*, *>
            for (name in fields.keys) {
                for (wrong in listOf(null, true, false, 4.0, "8", -1, 0, 1L shl 31))
                    assertThrows(RuntimeFault::class.java, {
                        parse(original + (section to (fields + (name to wrong))))
                    }, "$section/$name/$wrong")
                assertThrows(RuntimeFault::class.java) { parse(original + (section to (fields - name))) }
            }
            for (wrong in listOf(null, emptyList<Any?>(), emptyMap<String, Any?>(), fields + ("extra" to 1)))
                assertThrows(RuntimeFault::class.java) { parse(original + (section to wrong)) }
        }
        for (wrong in listOf(null, emptyList<Any?>(), emptyMap<String, Any?>()))
            assertThrows(RuntimeFault::class.java) { parse(wrong) }
    }

    @Test fun compilerTargetsMustMatchTheRuntimePlatformWithoutX32OrMuslAliases() {
        for ((system, architecture, target) in listOf(
            Triple("Linux", "amd64", "x86_64-unknown-linux-gnu"), Triple("Linux", "arm64", "aarch64-unknown-linux-gnu"),
            Triple("Darwin", "x86_64", "x86_64-apple-darwin25"), Triple("Darwin", "aarch64", "arm64-apple-darwin25"))) {
            val value = document() + mapOf("system" to system, "architecture" to architecture, "target" to target)
            assertDoesNotThrow { StdioHostAbi.parse(value, system, architecture) }
            for (wrong in listOf("x86_64-unknown-linux-gnux32", "x86_64-unknown-linux-musl", "aarch64", "wasm32-wasi"))
                assertThrows(RuntimeFault::class.java) { StdioHostAbi.parse(value + ("target" to wrong), system, architecture) }
            assertThrows(RuntimeFault::class.java) { StdioHostAbi.parse(value, "Windows", architecture) }
            assertThrows(RuntimeFault::class.java) { StdioHostAbi.parse(value, system, "riscv64") }
        }
    }
}
