// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PinnedPointerCellsTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private data class Row(val input: Long, val pointer: Long, val array: Long, val order: Long,
        val char8: Long, val byte8: Long, val halfword: Long)
    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build()
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)

    @Test fun pointerArrayCellsRequireAnOwnerAndRejectInvalidIndicesBeforeMutation() {
        val nullAddress = ManagedAddress.nullAddress()
        val raw = ByteArray(32)
        assertThrows(RuntimeFault::class.java) { PinnedMemory.writeAddressArray(raw, 0, nullAddress) }
        assertThrows(RuntimeFault::class.java) { PinnedMemory.readAddressArray(raw, 0) }
        val pinned = PinnedMemory.allocate(32, 1)
        PinnedMemory.writeAddressArray(pinned, 1, nullAddress)
        assertSame(nullAddress, PinnedMemory.readAddressArray(pinned, 1))
        assertThrows(RuntimeFault::class.java) { PinnedMemory.writeAddressArray(pinned, -1, nullAddress) }
        assertThrows(RuntimeFault::class.java) { PinnedMemory.writeAddressArray(pinned, Long.MAX_VALUE, nullAddress) }
        assertSame(nullAddress, PinnedMemory.readAddressArray(pinned, 1))
        val base = ManagedAddress.fromAllocation(pinned)
        val target = base.plus(24)
        base.writeAddressElementIndex(1, target)
        base.writeWord8(16, 0x1e9)
        assertEquals(0xe9L, base.readWord8(16))
        assertThrows(RuntimeFault::class.java) { base.writeWord8(8, 0x41) }
        assertSame(target, base.readAddressElementIndex(1))
    }

    @Test fun orderedAddressesRequireOneAllocationAndPreserveCheckedOffsets() {
        val nullAddress = ManagedAddress.nullAddress()
        assertEquals(0, nullAddress.compareWithinAllocation(nullAddress))
        val bytes = ByteArray(16)
        val base = ManagedAddress.fromByteArray(bytes)
        val alias = ManagedAddress.fromByteArray(bytes)
        assertEquals(0, base.compareWithinAllocation(alias))
        assertTrue(base.compareWithinAllocation(alias.plus(1)) < 0)
        assertTrue(base.plus(16).compareWithinAllocation(alias) > 0) // one past
        assertThrows(RuntimeFault::class.java) { base.plus(Long.MAX_VALUE) }
        assertThrows(RuntimeFault::class.java) { base.plus(-1) }
        assertThrows(RuntimeFault::class.java) { base.compareWithinAllocation(nullAddress) }
        assertThrows(RuntimeFault::class.java) { nullAddress.compareWithinAllocation(base) }
        assertThrows(RuntimeFault::class.java) {
            base.compareWithinAllocation(ManagedAddress.fromByteArray(bytes.copyOf()))
        }
        val pinned = ManagedAddress.fromAllocation(PinnedMemory.allocate(16, 1))
        assertTrue(pinned.compareWithinAllocation(pinned.plus(16)) < 0)
        assertThrows(RuntimeFault::class.java) { base.compareWithinAllocation(pinned) }
    }

    @Test fun halfwordStoresCheckWholeElementAndPreserveDisjointPointerCells() {
        val base = ManagedAddress.fromAllocation(PinnedMemory.allocate(32, 1))
        val target = base.plus(24)
        base.writeAddressElementIndex(1, target)
        base.writeWord16(8, 0x12345)
        val expected = ByteBuffer.allocate(2).order(ByteOrder.nativeOrder()).putShort(0x2345.toShort()).array()
        assertEquals(expected[0].toLong() and 255, base.readWord8(16))
        assertEquals(expected[1].toLong() and 255, base.readWord8(17))
        assertSame(target, base.readAddressElementIndex(1))
        for (index in listOf(4L, 7L, 16L, Long.MAX_VALUE, Long.MIN_VALUE))
            assertThrows(RuntimeFault::class.java) { base.writeWord16(index, 0x55aa) }
        assertSame(target, base.readAddressElementIndex(1))
        assertEquals(expected[0].toLong() and 255, base.readWord8(16))
        assertThrows(RuntimeFault::class.java) { ManagedAddress.fromHex("0000").writeWord16(0, 1) }
        base.plus(2).writeWord16(-1, -1)
        assertEquals(255L, base.readWord8(0))
        assertEquals(255L, base.readWord8(1))
    }

    private fun halfwordModel(input: Long): Long {
        val bytes = ByteBuffer.allocate(32).order(ByteOrder.nativeOrder())
        bytes.putShort(24, input.toShort())
        bytes.putShort(16, (input + 32768).toShort())
        return (1L shl 32) + (16..17).plus(24..25).fold(0L) { packed, offset ->
            (packed shl 8) or (bytes.get(offset).toLong() and 255L)
        }
    }

    @Test fun originalPinnedFreezeContentsAndKeepAliveMatchNativeInBothBackends() {
        val manifest = Json.parse(File(root, "build/pinned-pointer-cells/manifest.json").readText()) as Map<String, Any?>
        assertEquals(1L, manifest["schema"])
        assertEquals("9.14.1", manifest["ghc"])
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in manifest[kind] as Map<String, String>) {
                val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, actual, "Stale pinned pointer-cell $kind: $path")
            }
        val rows = File(root, "build/pinned-pointer-cells/oracle.tsv").readLines().map { line ->
            val fields = line.split('\t')
            assertEquals(7, fields.size)
            Row(fields[0].toLong(), fields[1].toLong(), fields[2].toLong(), fields[3].toLong(),
                fields[4].toLong(), fields[5].toLong(), fields[6].toLong())
        }
        assertEquals(listOf(0L, 1L, 17L, 127L, 255L, 256L, -1L, 32767L, 32768L, 65535L, -32768L),
            rows.map { it.input })
        for (row in rows) {
            assertEquals(1009L + 17L * (row.input and 255L), row.pointer)
            assertEquals(1L, row.array)
            assertEquals(if (row.input and 7L == 0L) 122L else 127L, row.order)
            assertEquals(0x01010101L * (row.input and 255L), row.char8)
            val unsigned = row.input and 255L
            val signed = unsigned.toByte().toLong()
            assertEquals(((signed + 128L) shl 24) + ((signed + 128L) shl 16) +
                (unsigned shl 8) + unsigned, row.byte8)
            assertEquals(halfwordModel(row.input), row.halfword)
        }
        for (stage in listOf("pre", "post")) {
            val directory = File(root, "build/pinned-pointer-cells/$stage")
            val audit = Json.parse(File(directory, "audit.json").readText()) as Map<String, Any?>
            assertEquals(true, audit["accepted"])
            assertEquals(emptyList<Any>(), audit["missingGlobals"])
            val primitives = (audit["primitives"] as List<Map<String, Any?>>).map { it["name"] }.toSet()
            assertTrue(primitives.containsAll(setOf("newPinnedByteArray#", "unsafeFreezeByteArray#", "byteArrayContents#",
                "keepAlive#", "writeAddrOffAddr#", "readAddrOffAddr#", "indexAddrOffAddr#",
                "writeAddrArray#", "readAddrArray#", "indexAddrArray#",
                "ltAddr#", "leAddr#", "gtAddr#", "geAddr#",
                "readCharOffAddr#", "writeCharOffAddr#", "indexCharOffAddr#",
                "readCharArray#", "writeCharArray#", "indexCharArray#",
                "readInt8OffAddr#", "writeInt8OffAddr#", "indexInt8OffAddr#", "indexWord8OffAddr#",
                "writeInt16OffAddr#", "writeWord16OffAddr#",
                "readWord8OffAddr#", "indexWord8Array#")))
            for (primitive in setOf("readInt8OffAddr#", "writeInt8OffAddr#", "indexInt8OffAddr#",
                "indexWord8OffAddr#", "writeInt16OffAddr#", "writeWord16OffAddr#")) {
                val evidence = (audit["primitives"] as List<Map<String, Any?>>).single { it["name"] == primitive }
                val owners = (evidence["uses"] as List<Map<String, Any?>>).map { it["owner"] }.toSet()
                val owner = if (primitive.endsWith("16OffAddr#")) "halfwordWriteRoundtrip" else "byte8Roundtrip"
                assertEquals(setOf("main:PinnedPointerCellsAudit.$owner"), owners, "$stage/$primitive")
            }
            val paths = listOf("PinnedPointerCellsAudit.json", "THC.InterfaceClosure.json")
                .map { File(directory, "core/$it") }
            val merged = CoreModules.merge(paths.map { Json.parse(it.readText()) as Map<String, Any?> })
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    for (entry in listOf("pointerRoundtrip", "pointerArrayRoundtrip", "pointerOrder",
                        "char8Roundtrip", "byte8Roundtrip", "halfwordWriteRoundtrip")) {
                        val source = CoreModules.reachable(merged, entry) + ("instrument" to true)
                        val program: ExecutableProgram = if (backend == "ast") Program(language, source)
                            else BytecodeProgram(language, source)
                        val function = context.asValue(EntryValue(program, entry, 1))
                        fun check(input: Long, expected: Long) {
                            assertEquals(expected, function.execute(input).asLong(), "$stage/$backend/$entry/$input")
                            assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                        }
                        fun expected(row: Row) = when (entry) {
                            "pointerRoundtrip" -> row.pointer
                            "pointerArrayRoundtrip" -> row.array
                            "pointerOrder" -> row.order
                            "char8Roundtrip" -> row.char8
                            "halfwordWriteRoundtrip" -> row.halfword
                            else -> row.byte8
                        }
                        rows.forEach { row -> check(row.input, expected(row)) }
                        // EntryValue compiles the active DirectCallNode target (which may
                        // be a split clone) and the public host root, checking both tiers.
                        assertTrue(function.invokeMember("compile").asBoolean(), "$stage/$backend/$entry compilation")
                        valid(program.hostEntryTarget(1), "$stage/$backend/$entry host target after compilation")
                        for (row in rows.asReversed()) {
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            check(row.input, expected(row))
                            var after = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            if (after == before) {
                                // A dependency can retire the public call-boundary stub;
                                // require a bounded fresh last-tier execution for this row.
                                assertTrue(function.invokeMember("compile").asBoolean(),
                                    "$stage/$backend/$entry recompilation")
                                check(row.input, expected(row))
                                after = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            }
                            assertTrue(after > before, "$stage/$backend/$entry/${row.input}: $before->$after")
                        }
                    }
                } finally { context.leave() }
            }
        }
    }
}
