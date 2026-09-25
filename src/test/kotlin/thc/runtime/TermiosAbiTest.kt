// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.condition.OS
import thc.Json
import java.io.File
import java.security.MessageDigest

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
class TermiosAbiTest {
    private fun document() = TermiosImage::class.java.getResourceAsStream("/thc/native/termios-abi.json")!!.use {
        Json.parse(it.reader().readText()) as Map<*, *>
    }
    private fun parse(value: Any?) = TermiosImage.parse(value, System.getProperty("os.name"), System.getProperty("os.arch"))

    @Test fun completeImagePreflightPointerCellsAndAliasLifetime() {
        val doc = document()
        val source = File(System.getProperty("thc.projectRoot"), "src/main/c/termios-abi-probe.c").readBytes()
        assertEquals(OriginalStdioChecks.hex(MessageDigest.getInstance("SHA-256").digest(source)), doc["sourceSha256"])
        val abi = parse(doc)
        val bytes = ByteArray(abi.size.toInt() + 2) { 90 }
        val address = ManagedAddress.fromByteArray(bytes).plus(1)
        for (pattern in listOf(0L, 1L, 0x80000000L, 0xffffffffL, 0xdeadbeefL)) {
            abi.poke(address, pattern)
            assertEquals(pattern, abi.lflag(address))
            assertEquals(90.toByte(), bytes.first()); assertEquals(90.toByte(), bytes.last())
        }
        val cc = abi.cc(address)
        cc.writeWord8(0, 171)
        val offset = ((doc["termios"] as Map<*, *>)["ccOffset"] as Number).toInt()
        assertEquals(171.toByte(), bytes[1 + offset])
        val saved = bytes.clone()
        for (bad in listOf(-1L, 1L shl 32, Long.MIN_VALUE, Long.MAX_VALUE))
            assertThrows(RuntimeFault::class.java) { abi.poke(address, bad) }
        for (bad in listOf(ManagedAddress.nullAddress(), address.plus(2),
            ManagedAddress.fromByteArray(ByteArray(abi.size.toInt() - 1)))) {
            assertThrows(RuntimeFault::class.java) { abi.lflag(bad) }
            assertThrows(RuntimeFault::class.java) { abi.poke(bad, 7L) }
            assertThrows(RuntimeFault::class.java) { abi.cc(bad) }
        }
        assertArrayEquals(saved, bytes)
        val pinned = ManagedAddress.fromAllocation(PinnedMemory.allocate(abi.size, 8))
        pinned.writeAddressElementIndex(0, address)
        assertThrows(RuntimeFault::class.java) { abi.lflag(pinned) }
        assertThrows(RuntimeFault::class.java) { abi.poke(pinned, 7L) }
        assertThrows(RuntimeFault::class.java) { abi.cc(pinned) }
        assertSame(address, pinned.readAddressElementIndex(0))
        val readonly = ManagedAddress.fromHex("00".repeat(abi.size.toInt()))
        assertThrows(RuntimeFault::class.java) { abi.poke(readonly, 7L) }
        assertEquals(0L, abi.lflag(readonly))
    }

    @Test fun closedProbeSchemaBoundsAndWidthsReject() {
        val doc = document(); parse(doc)
        for ((key, bad) in listOf("schema" to true, "schema" to 1.0, "system" to "Darwin", "architecture" to "aarch64",
            "target" to "x86_64-unknown-linux-gnux32"))
            assertThrows(RuntimeFault::class.java) { parse(doc + (key to bad)) }
        val layout = doc["termios"] as Map<*, *>
        for (key in layout.keys) {
            assertThrows(RuntimeFault::class.java) { parse(doc + ("termios" to (layout - key))) }
            for (bad in listOf(null, true, 1.0, "1", Long.MAX_VALUE))
                assertThrows(RuntimeFault::class.java) { parse(doc + ("termios" to (layout + (key to bad)))) }
        }
        for ((key, bad) in listOf("extra" to 0L, "size" to 0L, "lflagOffset" to -1L, "ccOffset" to -1L,
            "ccCount" to 0L, "lflagBytes" to 8L, "ccBytes" to 4L, "alignment" to 8L,
            "ccOffset" to layout["lflagOffset"], "vmin" to layout["vtime"]))
            assertThrows(RuntimeFault::class.java) { parse(doc + ("termios" to (layout + (key to bad)))) }
    }
}
