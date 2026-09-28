// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import thc.*
import java.io.File
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.ref.Reference
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipFile

/** Real package products and native Haskell observations. C-owned output uses
 * the original returned pointer; only the C caller performs its destruction. */
class LibyamlNativeProductsTest {
    @TempDir lateinit var temporary: Path
    private fun digest(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Decode only the genuine two-module unit. Original IDs select compact
     * records in the original order, so full foreign-call inventory admission
     * checks executable bodies too, not merely header equality. */
    private fun selectedModules(originals: List<Pair<Map<String, Any?>, String>>): List<Map<String, Any?>> {
        val path = System.getProperty("thc.libyamlPackages") ?: return originals.map { it.first }
        val format = System.getProperty("thc.libyamlFormat")
        require(format in setOf("json", "compact"))
        val directory = CoreUnitDirectory.read(Json.parse(File(path).readText()) as Map<*, *>)!!
        val unit = originals.map { it.first["unit"] }.distinct().single()
        val selected = directory.modules.filter { it.unit == unit }
        assertEquals(originals.map { it.first["module"] }.toSet(), selected.map { it.name }.toSet())
        directory.open(false, false).use { sources ->
            val decoded = originals.map { (original, hash) ->
                val record = selected.single { it.name == original["module"] }
                assertEquals(hash, record.sha256, "selected module must be the exact native fixture capture")
                assertEquals(format == "compact", record.storage is CoreUnitDirectory.CompactStorage)
                val ids = (original["bindings"] as List<Map<String, Any?>>).map { it["id"] as String }
                val bindings = ids.map { id -> requireNotNull(sources.binding(id)) { "Missing original binding $id" } }
                assertEquals(ids, bindings.map { it["id"] })
                sources.metadata(record) + ("bindings" to bindings)
            }
            val count = decoded.sumOf { (it["bindings"] as List<*>).size }.toLong()
            if (format == "compact") {
                assertEquals(0, sources.counters().size, "compact execution must not fall back to JSON")
                val counters = sources.compactCounters().map { it.statistics() }
                assertEquals(2, counters.size, "unrelated package modules stay unopened")
                assertEquals(count, counters.sumOf { it.decodedBindings })
                assertEquals(0L, counters.sumOf { it.debugBytesRead + it.hashBytesRead })
            } else {
                assertEquals(0, sources.compactCounters().size)
                val counters = sources.counters().map { it.statistics() }
                assertEquals(1, counters.size, "unrelated package units stay unopened")
                assertEquals(count, counters.sumOf { it.decodedBindings })
                assertEquals(0L, counters.sumOf { it.hashBytesScanned })
            }
            return decoded
        }
    }

    private class Entry(language: Language, private val call: PackageScalarCall) : RootNode(language) {
        @Child private var access = PackageScalarAccess(call)
        override fun execute(frame: VirtualFrame): Any = when (call.result) {
            "void" -> { access.executeVoid(frame.arguments, Unit); Unit }
            "AddrRep" -> access.executeAddress(frame.arguments, Unit)
            else -> access.executeLong(frame.arguments, Unit)
        }
    }

    @Test fun originalNativeParserEventsAndEncoderMatchThroughReturnedPointers() {
        val root = File(System.getProperty("thc.projectRoot"))
        val directory = File(System.getProperty("thc.libyamlFixture", File(root, "build/libyaml-native").path))
        val zip = File(System.getProperty("thc.libyamlBundle"))
        val source = File(root, "compiler/test-fixtures/OriginalLibyamlNative.hs")
        fun digest(file: File) = digest(file.readBytes())
        val sourceHash = digest(source)
        assertEquals(sourceHash, File(directory, "native-source.sha256").readText().substringBefore(' '))
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<*, *>
        assertEquals(sourceHash, manifest["sourceSha256"])
        assertEquals(digest(zip), manifest["bundleSha256"])
        assertEquals(digest(File(directory, "native.tsv")), manifest["nativeSha256"])
        assertEquals(digest(File(directory, "input.yaml")), manifest["inputSha256"])
        val originals = ZipFile(zip).use { archive -> archive.entries().asSequence()
            .filter { it.name.startsWith("core/") && it.name.endsWith(".json") }
            .map { archive.getInputStream(it).use { input ->
                val bytes = input.readAllBytes()
                (Json.parse(bytes.decodeToString()) as Map<String, Any?>) to digest(bytes)
            } }.toList() }
        val modules = selectedModules(originals)
        assertEquals(setOf("Paths_libyaml", "Text.Libyaml"), modules.map { it["module"] }.toSet())
        val merged = CoreModules.merge(modules)
        val link = (merged["packageScalarLinks"] as List<PackageScalarLink>).single()
        val originalLink = (CoreModules.merge(originals.map { it.first })["packageScalarLinks"] as List<PackageScalarLink>).single()
        assertTrue(link.same(originalLink), "execution must use the same original native component and declared ABI")
        assertEquals("llvm-embedded-elf", link.format)
        assertEquals(50, link.abi.size)
        val expected = File(directory, "native.tsv").readLines()
        val input = File(directory, "input.yaml").readBytes()
        val observed = mutableListOf<String>()
        var returnedReads = 0
        NativeFileProvider.createContext(emptySet(), ContextProfile.SYNCHRONOUS_TEST).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val owner = Language.currentState()
                    owner.packageCbits.link(link)
                    val entries = link.abi.associateBy { it.symbol }.mapValues { (_, signature) ->
                        Entry(language, PackageScalarCall(link, signature)).callTarget
                    }
                    fun call(symbol: String, vararg args: Any?): Any? = entries.getValue(symbol).call(*args)
                    fun success(symbol: String, vararg args: Any?) = assertEquals(1L, call(symbol, *args), symbol)
                    val returnSlot = owner.nativeAllocations.malloc(16)
                    try {
                        for (value in listOf(0L, 2147483647L, 2147483648L, 4294967295L)) {
                            for (byte in 0..3) returnSlot.writeWord8(12L + byte, value ushr (byte * 8) and 255)
                            observed += "unsigned-result\t${call("get_buffer_used", returnSlot)}"
                        }
                    } finally { owner.nativeAllocations.free(returnSlot) }
                    fun marks(mark: ManagedAddress): String = listOf("get_mark_index", "get_mark_line", "get_mark_column")
                        .joinToString(",") { call(it, mark).toString() }
                    val markSlot = owner.nativeAllocations.malloc(24)
                    try {
                        for (value in listOf(0L, 2147483647L, 2147483648L, 4294967295L, 4294967296L, -1L)) {
                            for (offset in listOf(0, 8, 16)) for (byte in 0..7)
                                markSlot.writeWord8((offset + byte).toLong(), value ushr (byte * 8) and 255)
                            observed += "mark-result\t${marks(markSlot)}"
                        }
                    } finally { owner.nativeAllocations.free(markSlot) }
                    // Pinned input is intentionally retained between many calls;
                    // a temporary per-call copy cannot satisfy libyaml's API.
                    val storage = PinnedMemory.allocate(input.size.toLong(), 8)
                    val address = ManagedAddress.fromAllocation(storage)
                    input.forEachIndexed { index, byte -> address.writeWord8(index.toLong(), byte.toLong() and 255) }
                    fun withParser(body: (ManagedAddress, ManagedAddress) -> Unit) {
                        val parser = owner.nativeAllocations.malloc(480)
                        val event = owner.nativeAllocations.malloc(104)
                        try {
                            success("yaml_parser_initialize", parser)
                            try {
                                call("yaml_parser_set_input_string", parser, address, input.size.toLong())
                                body(parser, event)
                            } finally { call("yaml_parser_delete", parser) }
                        } finally { owner.nativeAllocations.free(event); owner.nativeAllocations.free(parser) }
                    }
                    fun inspect(pointer: ManagedAddress, count: Long): ByteArray {
                        assertTrue(count in 1..100_000)
                        assertNotNull(pointer.returnedAddress())
                        assertNull(pointer.nativeAllocation(), "C retains allocation ownership")
                        assertThrows(RuntimeFault::class.java) { pointer.availableBytes() }
                        val numeric = ManagedAddress.unownedNumeric(pointer.toNativeBits())
                        assertTrue(pointer.sameLocation(numeric))
                        assertTrue(numeric.sameLocation(pointer))
                        assertEquals(0L, pointer.difference(numeric))
                        assertEquals(0L, numeric.difference(pointer))
                        assertEquals(-1, pointer.compareWithinAllocation(numeric.plus(1)))
                        assertEquals(1, numeric.plus(1).compareWithinAllocation(pointer))
                        assertThrows(RuntimeFault::class.java) { numeric.readWord8(0) }
                        assertThrows(RuntimeFault::class.java) { numeric.writeWord8(0, 1) }
                        assertThrows(RuntimeFault::class.java) { pointer.requireRange(0, -1) }
                        assertThrows(RuntimeFault::class.java) { pointer.requireRange(Long.MAX_VALUE, 1) }
                        assertTrue(pointer.sameLocation(pointer.plus(0)))
                        assertEquals(1L, pointer.plus(1).difference(pointer))
                        val bytes = ByteArray(count.toInt())
                        pointer.copyToByteArray(bytes, 0, count)
                        assertEquals(bytes[0].toLong() and 255, pointer.readWord8(0))
                        returnedReads++
                        return bytes
                    }
                    repeat(3) { iteration -> withParser { parser, event ->
                        observed += "parse\t${iteration + 1}"
                        do {
                            success("yaml_parser_parse", parser, event)
                            val kind = call("get_event_type", event) as Long
                            try {
                                val start = call("get_start_mark", event) as ManagedAddress
                                assertTrue(start.sameLocation(event.plus(56)))
                                assertEquals(48L, start.availableBytes(), "known interior alias keeps its actual event allocation bound")
                                assertThrows(RuntimeFault::class.java) { start.readWord8(48) }
                                assertEquals(call("get_mark_index", start), ManagedAddressRead.WORD64.read(start, 0))
                                observed += "marks\t${marks(call("get_start_mark", event) as ManagedAddress)}\t" +
                                    marks(call("get_end_mark", event) as ManagedAddress)
                                val bytes = if (kind != 6L) byteArrayOf() else inspect(
                                    call("get_scalar_value", event) as ManagedAddress, call("get_scalar_length", event) as Long)
                                observed += "$kind\t${bytes.size}\t${HexFormat.of().formatHex(bytes)}"
                                if (kind == 6L) {
                                    val tag = call("get_scalar_tag", event) as ManagedAddress
                                    assertEquals(0L, tag.cStringLength(), "implicit input tag is the helper's static empty C string")
                                }
                            } finally { call("yaml_event_delete", event) }
                        } while (kind != 2L)
                    } }
                    assertEquals(expected.dropLast(1), observed, "three complete parses before encoder execution")
                    withParser { parser, event ->
                        val emitter = owner.nativeAllocations.malloc(432)
                        val buffer = owner.nativeAllocations.malloc(16)
                        try {
                            success("yaml_emitter_initialize", emitter)
                            call("buffer_init", buffer)
                            try {
                                try {
                                    call("my_emitter_set_output", emitter, buffer)
                                    do {
                                        success("yaml_parser_parse", parser, event)
                                        val kind = call("get_event_type", event) as Long
                                        success("yaml_emitter_emit", emitter, event) // consumes event
                                    } while (kind != 2L)
                                } finally { call("yaml_emitter_delete", emitter) }
                                // The output belongs to the caller, not the emitter.
                                // Its actual C destruction occurs below, after reads.
                                val size = call("get_buffer_used", buffer) as Long
                                assertTrue(size > 4096, "exercise the original growing encoder buffer")
                                val pointer = call("get_buffer_buff", buffer) as ManagedAddress
                                val bytes = inspect(pointer, size)
                                pointer.withNativeIOWindow(size, false) {
                                    assertEquals(pointer.toNativeBits(), it.address())
                                    assertEquals(size, it.byteSize(), "window is the IO request, not an allocation extent")
                                    assertArrayEquals(bytes, it.toArray(ValueLayout.JAVA_BYTE))
                                }
                                val path = temporary.resolve("encoded.yaml")
                                val filename = ManagedAddress.fromByteArray(path.toString().toByteArray() + byteArrayOf(0))
                                val descriptor = owner.files.open(filename, 3)
                                assertTrue(descriptor >= 3)
                                try {
                                    assertTrue(pointer.hasNativeIOStorage())
                                    assertEquals(size, owner.files.write(descriptor, pointer, size))
                                    assertArrayEquals(bytes, Files.readAllBytes(path))
                                    assertEquals(0L, owner.files.seek(descriptor, 0, 0))
                                    pointer.writeWord8(0, 0)
                                    assertEquals(size, owner.files.read(descriptor, pointer, size))
                                    assertEquals(bytes[0].toLong() and 255, pointer.readWord8(0))
                                } finally { assertEquals(0L, owner.files.close(descriptor)) }
                                val managed = ManagedAllocation.mutable(size, 8)
                                pointer.copyToByteArray(managed, 0, size)
                                assertArrayEquals(bytes, managed.copyBytesOut(0, size))
                                pointer.copyFromByteArray(managed, 0, size)
                                observed += "encoded\t${bytes.size}\t${HexFormat.of().formatHex(bytes)}"
                            } finally {
                                // Only this native test owns this original helper allocation.
                                val pointer = call("get_buffer_buff", buffer) as ManagedAddress
                                val linker = Linker.nativeLinker()
                                linker.downcallHandle(linker.defaultLookup().find("free").orElseThrow(),
                                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
                                    .invokeWithArguments(MemorySegment.ofAddress(pointer.toNativeBits()))
                            }
                        } finally { owner.nativeAllocations.free(buffer); owner.nativeAllocations.free(emitter) }
                    }
                    Reference.reachabilityFence(storage)
                } finally { context.leave() }
            }
        assertEquals(expected, observed)
        assertTrue(returnedReads > 3, "exercise returned C storage across parser calls and encoder growth")
    }
}
