// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.interop.InvalidBufferOffsetException
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.EntryValue
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest
import java.util.function.LongSupplier

class ShrinkByteArrayTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/shrink-bytearrays")
    private val names = listOf("shrinkBytes", "shrinkPinned")
    private fun json(file: File) = Json.parse(file.readText()) as Map<String, Any?>
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build()

    private data class Row(val name: String, val seed: Long, val size: Long, val answer: Long)
    private fun model(name: String, seed: Long, size: Long): Long {
        val bytes = List(size.toInt()) { ((seed + 17L * it) and 255L) }
        return size * 256 + if (name == "shrinkPinned") bytes.first() else
            bytes.foldIndexed(0L) { index, sum, byte -> sum + byte * (index + 1) }
    }
    private fun rows(): List<Row> {
        val expected = names.flatMap { name -> listOf(-7L, 0L, 1L, 42L, 255L).flatMap { seed ->
            (if (name == "shrinkPinned") 1L..2L else 0L..2L).toList()
                .plus(listOf(7L, 16L)).map { size -> Row(name, seed, size, model(name, seed, size)) }
        } }
        val actual = File(directory, "oracle.tsv").readLines().map { line ->
            val fields = line.split('\t')
            assertEquals(4, fields.size)
            Row(fields[0], fields[1].toLong(), fields[2].toLong(), fields[3].toLong())
        }
        assertEquals(expected, actual, "Native shrink/model inventory")
        return actual
    }

    @Test fun originalNativeShrinkRunsInBothBackendsAndCompiledCode() {
        val manifest = json(File(directory, "manifest.json"))
        assertEquals(1L, manifest["schema"])
        assertEquals("9.14.1", manifest["ghc"])
        assertEquals(names, manifest["entries"])
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, hash) in manifest[kind] as Map<String, String>)
                assertEquals(hash, digest(File(root, path)), "Stale shrink $kind: $path")
        val cases = rows()
        assertEquals(cases.size.toLong(), (manifest["nativeRows"] as Number).toLong())
        for ((stage, paths) in manifest["stages"] as Map<String, List<String>>) {
            val merged = CoreModules.merge(paths.map { json(File(root, it)) })
            for (name in names) {
                val audit = json(File(directory, "$stage/$name.audit.json"))
                assertEquals(true, audit["accepted"], "$stage/$name")
                assertEquals(emptyList<Any>(), audit["issues"])
                assertEquals(emptyList<Any>(), audit["missingGlobals"])
                assertTrue((audit["primitives"] as List<Map<String, Any?>>)
                    .map { it["name"] }.contains("shrinkMutableByteArray#"))
                for (backend in listOf("ast", "bytecode")) context().use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val linked = CoreModules.reachable(merged, name) + ("instrument" to true)
                        val program = if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                        val host = program.hostEntryTarget(2)
                        val entry = context.asValue(EntryValue(program, name, 2))
                        val selected = cases.filter { it.name == name }
                        for (row in selected)
                            assertEquals(row.answer, entry.execute(row.seed, row.size).asLong(),
                                "$stage/$backend/$name(${row.seed},${row.size})")
                        assertTrue(entry.invokeMember("compile").asBoolean(), "$stage/$backend/$name install")
                        for (row in selected.asReversed()) {
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            assertEquals(row.answer, entry.execute(row.seed, row.size).asLong())
                            assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before,
                                "$stage/$backend/$name(${row.seed},${row.size}) compiled")
                            assertEquals(true, host.javaClass.getMethod("isValidLastTier").invoke(host))
                        }
                        assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                        assertEquals(0, language.handoffState.get().results.depth)
                    } finally { context.leave() }
                }
            }
        }
    }

    @Test fun shrinkingPreservesAliasesLogicalBoundsAndPointerCellSafety() {
        val owner = ManagedByteArray.allocateGuest(32)
        val alias = ManagedAddress.fromAllocation(owner)
        for (index in 0L until 32L) owner.writeByte(index, index)
        owner.shrink(16)
        assertEquals(16L, ManagedByteArray.sizeGuest(owner))
        assertSame(owner, ManagedByteArray.freezeGuest(owner))
        assertEquals(15L, alias.readWord8(15))
        assertEquals(15L, ManagedByteArray.readGuest(owner, 15, true))
        assertDoesNotThrow { ManagedByteArray.readIntGuest(owner, 1) }
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.readIntGuest(owner, 2) }
        assertThrows(RuntimeFault::class.java) { alias.readWord8(16) }
        assertThrows(RuntimeFault::class.java) { owner.writeByte(16, 1) }
        for (length in listOf(-1L, 17L, Long.MAX_VALUE))
            assertThrows(RuntimeFault::class.java) { owner.shrink(length) }
        assertEquals(16L, owner.size)
        assertEquals(15L, alias.readWord8(15))
        val raw = ByteArray(16)
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.shrinkGuest(raw, 8) }
        assertEquals(16, raw.size)
        owner.shrink(0)
        assertEquals(0L, owner.size)
        assertThrows(RuntimeFault::class.java) { alias.readWord8(0) }

        val exposed = ManagedByteArray.allocateGuest(32)
        val backing = exposed.rawBytesIfPointerFree()
        exposed.shrink(16)
        assertSame(backing, exposed.rawBytesIfPointerFree())
        assertEquals(16L, exposed.size)
        val buffer = CbitsBuffer(backing, true, LongSupplier { exposed.size })
        val interop = InteropLibrary.getUncached()
        assertEquals(16L, interop.getBufferSize(buffer))
        assertThrows(InvalidBufferOffsetException::class.java) { interop.readBufferByte(buffer, 16) }

        val pointers = ManagedByteArray.allocateGuest(24)
        val address = ManagedAddress.fromAllocation(pointers)
        pointers.writeAddressByteOffset(8, address)
        pointers.writeAddressByteOffset(16, address)
        assertThrows(RuntimeFault::class.java) { pointers.shrink(20) }
        assertEquals(24L, pointers.size)
        assertSame(address, pointers.readAddressByteOffset(16))
        pointers.shrink(16)
        assertSame(address, pointers.readAddressByteOffset(8))
        assertThrows(RuntimeFault::class.java) { pointers.readAddressByteOffset(16) }
    }

    @Test fun vectorsObserveShrunkBoundsWithoutLosingValidPrefix() {
        val owner = ManagedByteArray.allocateGuest(32)
        val vector = IntVector.broadcast(IntVector.SPECIES_128, 1).withLane(1, 2).withLane(2, 3).withLane(3, 4)
        ManagedByteArray.writeInt32VectorGuest(owner, 0, vector, false)
        owner.shrink(16)
        val read = ManagedByteArray.readInt32VectorGuest(owner, 0, false)
        assertEquals(listOf(1, 2, 3, 4), listOf(read.first, read.second, read.third, read.fourth))
        assertDoesNotThrow { ManagedByteArray.readWord32VectorGuest(owner, 0, false) }
        assertDoesNotThrow { ManagedByteArray.readFloatVectorGuest(owner, 0, false) }
        assertDoesNotThrow { ManagedByteArray.readDoubleVectorGuest(owner, 0, false) }
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.readInt32VectorGuest(owner, 1, false) }
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.readWord32VectorGuest(owner, 1, false) }
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.readFloatVectorGuest(owner, 1, false) }
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.readDoubleVectorGuest(owner, 1, false) }
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.writeInt32VectorGuest(owner, 1, vector, false) }
        assertEquals(1, ManagedByteArray.readInt32VectorGuest(owner, 0, false).first)
    }
}
