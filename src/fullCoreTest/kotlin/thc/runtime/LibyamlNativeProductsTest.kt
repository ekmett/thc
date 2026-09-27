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
import thc.*
import java.io.File
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.ref.Reference
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipFile

/** Real package products and native Haskell observations, not replacement
 * metadata. Native heap inspection below belongs to this test only: the guest
 * deliberately retains its existing rejection of unowned byte accesses. */
class LibyamlNativeProductsTest {
    private class Entry(language: Language, private val call: PackageScalarCall) : RootNode(language) {
        @Child private var access = PackageScalarAccess(call)
        override fun execute(frame: VirtualFrame): Any = when (call.result) {
            "void" -> { access.executeVoid(frame.arguments, Unit); Unit }
            "AddrRep" -> access.executeAddress(frame.arguments, Unit)
            else -> access.executeLong(frame.arguments, Unit)
        }
    }

    @Test fun originalNativeParserEventsAndEncoderMatchWithGuestHeapBoundaryExplicit() {
        val root = File(System.getProperty("thc.projectRoot"))
        val directory = File(System.getProperty("thc.libyamlFixture", File(root, "build/libyaml-native").path))
        val zip = File(System.getProperty("thc.libyamlBundle"))
        val source = File(root, "compiler/test-fixtures/OriginalLibyamlNative.hs")
        fun digest(file: File) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
        val sourceHash = digest(source)
        assertEquals(sourceHash, File(directory, "native-source.sha256").readText().substringBefore(' '))
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<*, *>
        assertEquals(sourceHash, manifest["sourceSha256"])
        assertEquals(digest(zip), manifest["bundleSha256"])
        assertEquals(digest(File(directory, "native.tsv")), manifest["nativeSha256"])
        assertEquals(digest(File(directory, "input.yaml")), manifest["inputSha256"])
        val modules = ZipFile(zip).use { archive -> archive.entries().asSequence()
            .filter { it.name.startsWith("core/") && it.name.endsWith(".json") }
            .map { archive.getInputStream(it).use { input -> Json.parse(input.readAllBytes().decodeToString()) as Map<String, Any?> } }.toList() }
        assertEquals(setOf("Paths_libyaml", "Text.Libyaml"), modules.map { it["module"] }.toSet())
        val merged = CoreModules.merge(modules)
        val link = (merged["packageScalarLinks"] as List<PackageScalarLink>).single()
        assertEquals("llvm-embedded-elf", link.format)
        assertEquals(50, link.abi.size)
        val expected = File(directory, "native.tsv").readLines()
        val input = File(directory, "input.yaml").readBytes()
        val observed = mutableListOf<String>()
        var rejectedGuestReads = 0
        Context.newBuilder("thc").allowNativeAccess(true)
            .withContextProfile(ContextProfile.SYNCHRONOUS_TEST).build().use { context ->
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
                    // Diagnostic-only read under the C owner's live lifetime.
                    // It does not confer guest Addr# authority or extend a lease.
                    fun inspect(pointer: ManagedAddress, count: Long): ByteArray {
                        assertTrue(count in 1..100_000)
                        val failure = assertThrows(RuntimeFault::class.java) { pointer.readWord8(0) }
                        assertTrue(failure.message!!.contains("Unowned numeric Addr#"))
                        rejectedGuestReads++
                        return MemorySegment.ofAddress(pointer.toNativeBits()).reinterpret(count).toArray(ValueLayout.JAVA_BYTE)
                    }
                    repeat(3) { iteration -> withParser { parser, event ->
                        observed += "parse\t${iteration + 1}"
                        do {
                            success("yaml_parser_parse", parser, event)
                            val kind = call("get_event_type", event) as Long
                            try {
                                observed += "marks\t${marks(call("get_start_mark", event) as ManagedAddress)}\t" +
                                    marks(call("get_end_mark", event) as ManagedAddress)
                                val bytes = if (kind != 6L) byteArrayOf() else inspect(
                                    call("get_scalar_value", event) as ManagedAddress, call("get_scalar_length", event) as Long)
                                observed += "$kind\t${bytes.size}\t${HexFormat.of().formatHex(bytes)}"
                            } finally { call("yaml_event_delete", event) }
                        } while (kind != 2L)
                    } }
                    assertEquals(expected.dropLast(1), observed, "three complete parses before encoder execution")
                    withParser { parser, event ->
                        val emitter = owner.nativeAllocations.malloc(432)
                        val buffer = owner.nativeAllocations.malloc(16)
                        try {
                            success("yaml_emitter_initialize", emitter)
                            try {
                                call("buffer_init", buffer)
                                try {
                                    call("my_emitter_set_output", emitter, buffer)
                                    do {
                                        success("yaml_parser_parse", parser, event)
                                        val kind = call("get_event_type", event) as Long
                                        success("yaml_emitter_emit", emitter, event) // consumes event
                                    } while (kind != 2L)
                                    val size = call("get_buffer_used", buffer) as Long
                                    assertTrue(size > 4096, "exercise the original growing encoder buffer")
                                    val bytes = inspect(call("get_buffer_buff", buffer) as ManagedAddress, size)
                                    observed += "encoded\t${bytes.size}\t${HexFormat.of().formatHex(bytes)}"
                                } finally {
                                    // Only this native test owns this original helper allocation.
                                    val pointer = call("get_buffer_buff", buffer) as ManagedAddress
                                    val linker = Linker.nativeLinker()
                                    linker.downcallHandle(linker.defaultLookup().find("free").orElseThrow(),
                                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
                                        .invokeWithArguments(MemorySegment.ofAddress(pointer.toNativeBits()))
                                }
                            } finally { call("yaml_emitter_delete", emitter) }
                        } finally { owner.nativeAllocations.free(buffer); owner.nativeAllocations.free(emitter) }
                    }
                    Reference.reachabilityFence(storage)
                } finally { context.leave() }
            }
        assertEquals(expected, observed)
        assertTrue(rejectedGuestReads > 3, "native checks must not hide the remaining guest heap-read boundary")
    }
}
