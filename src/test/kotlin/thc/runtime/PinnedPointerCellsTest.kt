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
        val char8: Long, val byte8: Long, val halfwordRead: Long, val halfwordWrite: Long,
        val wideBytes: List<Long>, val wideReads: List<Long>)
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
        assertDoesNotThrow { ManagedAddressRead.WORD16.read(base, 8) }
        assertThrows(RuntimeFault::class.java) { ManagedAddressRead.WORD16.read(base, 4) }
        assertThrows(RuntimeFault::class.java) { ManagedAddressRead.INT16.read(base, Long.MAX_VALUE) }
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

    @Test fun wideScalarStoresCheckWholeElementsAndPointerOverlap() {
        val base = ManagedAddress.fromAllocation(PinnedMemory.allocate(64, 1))
        val target = base.plus(56)
        base.writeAddressElementIndex(1, target)
        base.writeNativeScalar(4, 4, 0x12345678)
        base.writeNativeScalar(5, 8, 0x1020304050607080)
        assertSame(target, base.readAddressElementIndex(1))
        for (width in listOf(4, 8)) {
            for (index in listOf(-1L, Long.MIN_VALUE, Long.MAX_VALUE))
                assertThrows(RuntimeFault::class.java) { base.writeNativeScalar(index, width, -1) }
        }
        assertThrows(RuntimeFault::class.java) { base.plus(2).writeNativeScalar(1, 8, -1) }
        assertSame(target, base.readAddressElementIndex(1))
        val expected = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder())
            .putInt(16, 0x12345678).putLong(40, 0x1020304050607080).array()
        for (index in (16..19).plus(40..47))
            assertEquals(expected[index].toLong() and 255, base.readWord8(index.toLong()))
        base.writeNativeScalar(1, 8, -1)
        assertThrows(RuntimeFault::class.java) { base.readAddressElementIndex(1) }
        for (index in 8..15) assertEquals(255L, base.readWord8(index.toLong()))
    }

    private fun wideStoreModel(input: Long): List<Long> {
        val bytes = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder())
        bytes.putInt(16, input.toInt())
        bytes.putInt(20, (input + 17).toInt())
        bytes.putLong(24, input)
        bytes.putLong(32, input + 33)
        bytes.putLong(40, input)
        bytes.putLong(48, input + 49)
        return (16..55).map { bytes.get(it).toLong() and 255L }
    }

    @Test fun wideIndexesUseElementOffsetsAndRejectPointerOverlap() {
        val base = ManagedAddress.fromAllocation(PinnedMemory.allocate(64, 1))
        base.writeNativeScalar(4, 4, -1)
        base.writeNativeScalar(5, 8, 0x123456789abcdef)
        val derived = base.plus(24)
        assertEquals(-1L, ManagedAddressRead.INT32.read(derived, -2))
        assertEquals(0xffffffffL, ManagedAddressRead.WORD32.read(derived, -2))
        assertEquals(0x123456789abcdefL, ManagedAddressRead.INT64.read(derived, 2))
        assertEquals(0x123456789abcdefL, ManagedAddressRead.WORD64.read(derived, 2))
        assertEquals(0x123456789abcdefL, ManagedAddressRead.INT64.read(base.plus(48), -1))
        for (operation in listOf(ManagedAddressRead.INT32, ManagedAddressRead.WORD32,
            ManagedAddressRead.INT, ManagedAddressRead.WORD,
            ManagedAddressRead.INT64, ManagedAddressRead.WORD64))
            for (index in listOf(Long.MIN_VALUE, Long.MAX_VALUE))
                assertThrows(RuntimeFault::class.java) { operation.read(derived, index) }
        val target = base.plus(56)
        base.writeAddressElementIndex(1, target)
        assertThrows(RuntimeFault::class.java) { ManagedAddressRead.WORD64.read(base, 1) }
        assertSame(target, base.readAddressElementIndex(1))
    }

    private fun wideReadModel(input: Long): List<Long> = listOf(input.toInt().toLong(),
        (input + 17) and 0xffffffffL, input, input + 33, input, input + 49, input, input + 49)

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
            assertEquals(10, fields.size)
            Row(fields[0].toLong(), fields[1].toLong(), fields[2].toLong(), fields[3].toLong(),
                fields[4].toLong(), fields[5].toLong(), fields[6].toLong(), fields[7].toLong(),
                fields[8].split(',').map(String::toLong), fields[9].split(',').map(String::toLong))
        }
        assertEquals(listOf(0L, 1L, 17L, 127L, 255L, 256L, 32767L, 32768L, 65535L,
            4294967297L, 81985529216486895L, -1L, -32768L),
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
            val halfword = row.input and 65535L
            val signedHalfword = halfword.toShort().toLong()
            assertEquals(((signedHalfword + 32768L) shl 48) + ((signedHalfword + 32768L) shl 32) +
                (halfword shl 16) + halfword, row.halfwordRead)
            assertEquals(halfwordModel(row.input), row.halfwordWrite)
            assertEquals(wideStoreModel(row.input), row.wideBytes)
            assertEquals(wideReadModel(row.input), row.wideReads)
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
                "readInt16OffAddr#", "readWord16OffAddr#", "indexInt16OffAddr#", "indexWord16OffAddr#",
                "writeInt16OffAddr#", "writeWord16OffAddr#",
                "writeInt32OffAddr#", "writeWord32OffAddr#", "writeIntOffAddr#",
                "writeWordOffAddr#", "writeInt64OffAddr#", "writeWord64OffAddr#",
                "indexInt32OffAddr#", "indexWord32OffAddr#", "indexIntOffAddr#",
                "indexWordOffAddr#", "indexInt64OffAddr#", "indexWord64OffAddr#",
                "readInt64OffAddr#", "readWord64OffAddr#",
                "readWord8OffAddr#", "indexWord8Array#")))
            for (primitive in setOf("indexInt32OffAddr#", "indexWord32OffAddr#",
                "indexIntOffAddr#", "indexWordOffAddr#", "indexInt64OffAddr#", "indexWord64OffAddr#",
                "readInt64OffAddr#", "readWord64OffAddr#")) {
                val evidence = (audit["primitives"] as List<Map<String, Any?>>).single { it["name"] == primitive }
                val owners = (evidence["uses"] as List<Map<String, Any?>>).map { it["owner"] }.toSet()
                assertEquals(setOf("main:PinnedPointerCellsAudit.wideReadSelector"), owners, "$stage/$primitive")
            }
            for (primitive in setOf("writeInt32OffAddr#", "writeWord32OffAddr#",
                "writeIntOffAddr#", "writeWordOffAddr#", "writeInt64OffAddr#", "writeWord64OffAddr#")) {
                val evidence = (audit["primitives"] as List<Map<String, Any?>>).single { it["name"] == primitive }
                val owners = (evidence["uses"] as List<Map<String, Any?>>).map { it["owner"] }.toSet()
                assertEquals(setOf("main:PinnedPointerCellsAudit.wideStoreByte",
                    "main:PinnedPointerCellsAudit.wideReadSelector"), owners, "$stage/$primitive")
            }
            for (primitive in setOf("readInt8OffAddr#", "writeInt8OffAddr#", "indexInt8OffAddr#",
                "indexWord8OffAddr#", "writeInt16OffAddr#", "writeWord16OffAddr#")) {
                val evidence = (audit["primitives"] as List<Map<String, Any?>>).single { it["name"] == primitive }
                val owners = (evidence["uses"] as List<Map<String, Any?>>).map { it["owner"] }.toSet()
                val owner = if (primitive.endsWith("16OffAddr#")) "halfwordWriteRoundtrip" else "byte8Roundtrip"
                assertEquals(setOf("main:PinnedPointerCellsAudit.$owner"), owners, "$stage/$primitive")
            }
            for (primitive in setOf("readInt16OffAddr#", "readWord16OffAddr#", "indexInt16OffAddr#",
                "indexWord16OffAddr#")) {
                val evidence = (audit["primitives"] as List<Map<String, Any?>>).single { it["name"] == primitive }
                val owners = (evidence["uses"] as List<Map<String, Any?>>).map { it["owner"] }.toSet()
                assertEquals(setOf("main:PinnedPointerCellsAudit.halfwordReadRoundtrip"), owners, "$stage/$primitive")
            }
            val paths = listOf("PinnedPointerCellsAudit.json", "THC.InterfaceClosure.json")
                .map { File(directory, "core/$it") }
            val merged = CoreModules.merge(paths.map { Json.parse(it.readText()) as Map<String, Any?> })
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    for (entry in listOf("pointerRoundtrip", "pointerArrayRoundtrip", "pointerOrder",
                        "char8Roundtrip", "byte8Roundtrip", "halfwordReadRoundtrip", "halfwordWriteRoundtrip")) {
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
                            "byte8Roundtrip" -> row.byte8
                            "halfwordReadRoundtrip" -> row.halfwordRead
                            else -> row.halfwordWrite
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
                    val entry = "wideStoreByte"
                    val source = CoreModules.reachable(merged, entry) + ("instrument" to true)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, source)
                        else BytecodeProgram(language, source)
                    val function = context.asValue(EntryValue(program, entry, 2))
                    fun checkWide(row: Row, selectors: IntProgression) {
                        for (selector in selectors) {
                            assertEquals(row.wideBytes[selector], function.execute(row.input, selector).asLong(),
                                "$stage/$backend/$entry/${row.input}/$selector")
                        }
                        assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                    }
                    rows.forEach { checkWide(it, 0..39) }
                    assertTrue(function.invokeMember("compile").asBoolean(), "$stage/$backend/$entry compilation")
                    valid(program.hostEntryTarget(2), "$stage/$backend/$entry host target after compilation")
                    for (row in rows.asReversed()) {
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        checkWide(row, 39 downTo 0)
                        var after = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        if (after == before) {
                            assertTrue(function.invokeMember("compile").asBoolean(),
                                "$stage/$backend/$entry recompilation")
                            checkWide(row, 39 downTo 0)
                            after = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        }
                        assertTrue(after > before, "$stage/$backend/$entry/${row.input}: $before->$after")
                    }
                    val readEntry = "wideReadSelector"
                    val readSource = CoreModules.reachable(merged, readEntry) + ("instrument" to true)
                    val readProgram: ExecutableProgram = if (backend == "ast") Program(language, readSource)
                        else BytecodeProgram(language, readSource)
                    val readFunction = context.asValue(EntryValue(readProgram, readEntry, 2))
                    fun checkRead(row: Row, selectors: IntProgression) {
                        for (selector in selectors)
                            assertEquals(row.wideReads[selector], readFunction.execute(row.input, selector).asLong(),
                                "$stage/$backend/$readEntry/${row.input}/$selector")
                        assertEquals(0L, (readProgram.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                    }
                    rows.forEach { checkRead(it, 0..7) }
                    assertTrue(readFunction.invokeMember("compile").asBoolean(), "$stage/$backend/$readEntry compilation")
                    valid(readProgram.hostEntryTarget(2), "$stage/$backend/$readEntry host target after compilation")
                    for (row in rows.asReversed()) {
                        val before = (readProgram.diagnostics().getValue("compiledEntries") as Number).toLong()
                        checkRead(row, 7 downTo 0)
                        var after = (readProgram.diagnostics().getValue("compiledEntries") as Number).toLong()
                        if (after == before) {
                            assertTrue(readFunction.invokeMember("compile").asBoolean(),
                                "$stage/$backend/$readEntry recompilation")
                            checkRead(row, 7 downTo 0)
                            after = (readProgram.diagnostics().getValue("compiledEntries") as Number).toLong()
                        }
                        assertTrue(after > before, "$stage/$backend/$readEntry/${row.input}: $before->$after")
                    }
                } finally { context.leave() }
            }
        }
    }
}
