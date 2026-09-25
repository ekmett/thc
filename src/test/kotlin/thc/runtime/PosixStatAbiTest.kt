// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import thc.Json
import java.io.File
import java.security.MessageDigest

@EnabledOnOs(OS.LINUX, disabledReason = "Only original Linux stat scalar declarations have native/Core proof")
class PosixStatAbiTest {
    private fun document() = PosixStat::class.java.getResourceAsStream("/thc/native/posix-stat-abi.json")!!.use {
        Json.parse(it.reader().readText()) as Map<*, *>
    }
    private fun parse(value: Any?) = PosixStat.parse(value, System.getProperty("os.name"), System.getProperty("os.arch"))

    @Test fun nativeLayoutRangesMutableReadsAndPointerCellsAreChecked() {
        val document = document()
        val source = File(System.getProperty("thc.projectRoot"), "src/main/c/posix-stat-abi-probe.c").readBytes()
        assertEquals(OriginalStdioChecks.hex(MessageDigest.getInstance("SHA-256").digest(source)), document["sourceSha256"])
        val abi = parse(document)
        val fields = (document["stat"] as Map<*, *>)["fields"] as Map<*, *>
        val bytes = ByteArray(abi.size.toInt())
        val address = ManagedAddress.fromByteArray(bytes)
        for ((name, raw) in fields) {
            val field = raw as Map<*, *>
            val offset = (field["offset"] as Number).toInt()
            val width = (field["width"] as Number).toInt()
            bytes.fill(0)
            assertEquals(0L, abi.field(name as String, address))
            for (index in 0 until width) bytes[offset + index] = -1
            assertEquals(if (width == 8) -1L else 0xffffffffL, abi.field(name, address))
            assertThrows(RuntimeFault::class.java) { abi.field(name, ManagedAddress.fromByteArray(bytes.copyOf(offset + width - 1))) }
            assertThrows(RuntimeFault::class.java) { abi.field(name, ManagedAddress.nullAddress()) }
        }
        val pinned = ManagedAddress.fromAllocation(PinnedMemory.allocate(abi.size, 8))
        val dev = fields["st_dev"] as Map<*, *>
        val offset = (dev["offset"] as Number).toLong()
        pinned.writeAddressElementIndex(offset / 8, address)
        assertThrows(RuntimeFault::class.java) { abi.field("st_dev", pinned) }
        for (mode in listOf(-1L, 1L shl 32, Long.MIN_VALUE, Long.MAX_VALUE))
            assertThrows(RuntimeFault::class.java) { abi.isType("regular", mode) }
    }

    @Test fun malformedHostLayoutAndMaskReceiptsFailClosed() {
        val document = document()
        parse(document)
        for ((key, wrong) in listOf("schema" to true, "schema" to 1.0, "system" to "Darwin", "architecture" to "riscv64",
            "target" to "x86_64-unknown-linux-gnux32", "target" to "x86_64-unknown-linux-musl"))
            assertThrows(RuntimeFault::class.java) { parse(document + (key to wrong)) }
        val stat = document["stat"] as Map<*, *>
        for ((key, wrong) in listOf("size" to 0L, "size" to Long.MAX_VALUE, "alignment" to 3L,
            "alignment" to 8.0, "extra" to 1L))
            assertThrows(RuntimeFault::class.java) { parse(document + ("stat" to (stat + (key to wrong)))) }
        val fields = stat["fields"] as Map<*, *>
        for ((name, raw) in fields) for ((key, wrong) in listOf("width" to 1L, "width" to true,
            "offset" to -1L, "offset" to Long.MAX_VALUE, "offset" to 0.0, "extra" to 0L)) {
            val mutated = fields + (name to ((raw as Map<*, *>) + (key to wrong)))
            assertThrows(RuntimeFault::class.java) { parse(document + ("stat" to (stat + ("fields" to mutated)))) }
        }
        val types = stat["types"] as Map<*, *>
        for (name in types.keys) for (wrong in listOf(null, true, 1.0, 0L, -1L, 1L shl 32))
            assertThrows(RuntimeFault::class.java) {
                parse(document + ("stat" to (stat + ("types" to (types + (name to wrong))))))
            }
        for (section in listOf("fields", "types")) {
            val values = stat[section] as Map<*, *>
            for (key in values.keys) assertThrows(RuntimeFault::class.java) {
                parse(document + ("stat" to (stat + (section to (values - key)))))
            }
        }
    }
}
