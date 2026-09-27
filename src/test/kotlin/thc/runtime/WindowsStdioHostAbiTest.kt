// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import thc.Json
import thc.Language

class WindowsStdioHostAbiTest {
    @BeforeEach fun windows() { assumeTrue(WindowsDirectoryStreams.supportedHost()) }
    @Suppress("UNCHECKED_CAST")
    private fun document() = javaClass.getResourceAsStream("/thc/native/stdio-host-abi.json")!!.use {
        Json.parse(it.bufferedReader().readText()) as Map<String, Any?>
    }
    private fun parse(value: Any?) = StdioHostAbi.parse(value, "Windows", "amd64")

    @Test fun receiptComesFromTheWindowsProbeAndRetainsCrtWidths() {
        val value = document()
        val source = File(System.getProperty("thc.projectRoot"), "src/main/c/windows-stdio-abi-probe.c")
        val hash = MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(hash, value["sourceSha256"])
        assertEquals(mapOf("charBits" to 8L, "pointer" to 8L, "int" to 4L, "long" to 4L,
            "bool" to 1L, "size" to 8L, "crtReadResult" to 4L, "crtReadCount" to 4L), value["widths"])
        val abi = parse(value)
        assertEquals(WindowsCodePages.Abi.errno.getValue("EBADF"), abi.error(4))
        assertEquals(WindowsCodePages.Abi.errno.getValue("EINVAL"), abi.error(5))
        for (mode in listOf(OriginalStdioOp.SEEK_SET, OriginalStdioOp.SEEK_CUR, OriginalStdioOp.SEEK_END).withIndex())
            assertEquals(mode.index.toLong(), abi.seekMode(abi.seekConstant(mode.value)))
    }

    @Test fun windowsReceiptCannotSupplyPosixCapabilities() {
        val value = document()
        val abi = parse(value)
        for (action in listOf<() -> Any?>({ abi.requireOpenAbi() }, { abi.openReadable(0) },
            { abi.openWritable(0) }, { abi.openAppend(0) }, { abi.flagConstant(OriginalStdioOp.F_GETFL) },
            { abi.atFdcwd }, { abi.atRemoveDir }, { abi.atSymlinkNoFollow }, { abi.atEmptyPath }, { abi.siginfoBytes }))
            assertThrows(RuntimeFault::class.java) { action() }
        for (field in listOf("open", "at", "siginfoBytes"))
            assertThrows(RuntimeFault::class.java) { parse(value + (field to 0L)) }
        for (system in listOf("Linux", "Darwin"))
            assertThrows(RuntimeFault::class.java) { StdioHostAbi.parse(value, system, "x86_64") }
    }

    @Test fun wrongWidthsPlatformsProfilesAndTargetsFailClosed() {
        val value = document()
        val widths = value["widths"] as Map<*, *>
        for (field in widths.keys) {
            for (wrong in listOf(null, true, 0, -1, 8.0, "4", 16L))
                assertThrows(RuntimeFault::class.java) { parse(value + ("widths" to (widths + (field to wrong)))) }
            assertThrows(RuntimeFault::class.java) { parse(value + ("widths" to (widths - field))) }
        }
        for (field in listOf("long", "crtReadResult", "crtReadCount"))
            assertThrows(RuntimeFault::class.java) { parse(value + ("widths" to (widths + (field to 8L)))) }
        for ((key, wrong) in listOf("schema" to 1L, "profile" to "posix", "system" to "Linux",
            "architecture" to "aarch64", "target" to "x86_64-pc-windows-msvc19.33.0",
            "target" to "x86_64-unknown-linux-gnu"))
            assertThrows(RuntimeFault::class.java) { parse(value + (key to wrong)) }
    }

    @Test fun malformedErrnoAndSeekConstantsReject() {
        val value = document()
        for (section in listOf("errno", "seek")) {
            val fields = value[section] as Map<*, *>
            for (field in fields.keys) {
                for (wrong in listOf(null, true, "9", 9.0, Int.MAX_VALUE.toLong() + 1))
                    assertThrows(RuntimeFault::class.java) { parse(value + (section to (fields + (field to wrong)))) }
                assertThrows(RuntimeFault::class.java) { parse(value + (section to (fields - field))) }
            }
            assertThrows(RuntimeFault::class.java) { parse(value + (section to (fields + ("extra" to 9L)))) }
        }
        val seek = value["seek"] as Map<*, *>
        assertThrows(RuntimeFault::class.java) { parse(value + ("seek" to (seek + ("SEEK_CUR" to seek["SEEK_SET"])))) }
    }

    @Test fun contextDescriptorsKeepOffsetsEofAndStickyErrnoWithoutHostFileAuthority() {
        val output = ByteArrayOutputStream()
        Context.newBuilder("thc").`in`(ByteArrayInputStream(byteArrayOf(3, 7))).out(output).build().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val stdio = Language.currentState().stdio
                val bytes = ByteArray(6) { 91 }
                val alias = ManagedAddress.fromByteArray(bytes).plus(2)
                assertEquals(-1L, stdio.close(-1))
                assertEquals(parse(document()).error(4), stdio.errno())
                val error = stdio.errno()
                assertEquals(2L, stdio.read(0, alias, 3))
                assertEquals(0L, stdio.read(0, alias, 3))
                assertEquals(2L, stdio.write(1, alias, 2))
                assertArrayEquals(byteArrayOf(91, 91, 3, 7, 91, 91), bytes)
                assertArrayEquals(byteArrayOf(3, 7), output.toByteArray())
                assertEquals(error, stdio.errno())
                assertThrows(RuntimeFault::class.java) { stdio.write(1L shl 32, alias, 1) }
            } finally { context.leave() }
        }
    }
}
