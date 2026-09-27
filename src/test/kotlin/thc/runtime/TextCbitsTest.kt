// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import thc.*
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

class TextCbitsTest {
    @BeforeEach fun supportedCbitsPlatform() {
        assumeTrue(System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") in setOf("amd64", "x86_64"),
            "Original text cbits currently require Linux x86_64")
    }
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/text-cbits")
    private fun json(name: String) = Json.parse(File(directory, name).readText()) as Map<String, Any?>
    private fun module(stage: String) = json("$stage-core/TextCbitsAudit.json")
    private data class Row(val name: String, val bytes: ByteArray, val offset: Long, val length: Long,
        val count: Long, val result: String)
    private fun rows(): List<Row> {
        val inputs = File(directory, "inputs.tsv").readLines()
        val lines = File(directory, "oracle.tsv").readLines()
        assertEquals(520, lines.size)
        assertEquals(inputs, lines.map { it.substringBefore('\t') })
        return lines.map { line ->
            val (input, result) = line.split('\t')
            val fields = input.split(' ')
            assertEquals(5, fields.size)
            val row = Row(fields[0], HexFormat.of().parseHex(fields[1]), fields[2].toLong(), fields[3].toLong(),
                java.lang.Long.parseUnsignedLong(fields[4]), result)
            val data = row.bytes.copyOfRange(row.offset.toInt(), (row.offset + row.length).toInt())
            val expected = if (row.name == "reverse") {
                val reversed = String(data, Charsets.UTF_8).codePoints().toArray().reversed()
                    .joinToString("") { String(Character.toChars(it)) }.toByteArray(Charsets.UTF_8)
                hex(reversed)
            } else if (row.name == "memchr") data.indexOf(row.count.toByte()).toString() else {
                val points = String(data, Charsets.UTF_8).codePoints().toArray()
                if (java.lang.Long.compareUnsigned(row.count, points.size.toLong()) > 0) {
                    // Original C narrows the unsigned negated remainder to
                    // ssize_t before deciding which result branch to take.
                    val remainder = points.size.toLong() - row.count
                    if (remainder >= 0) row.length - remainder else -points.size.toLong()
                }
                else points.take(row.count.toInt()).sumOf { String(Character.toChars(it)).toByteArray(Charsets.UTF_8).size }.toLong()
            }.toString()
            assertEquals(expected, row.result, "independent UTF-8/search model $input")
            row
        }
    }
    private fun hex(bytes: ByteArray) = if (bytes.isEmpty()) "-" else HexFormat.of().formatHex(bytes)
    private fun context(inlining: Boolean = true, native: Boolean = true) = Context.newBuilder("thc")
        .allowNativeAccess(native).withContextProfile(ContextProfile.SYNCHRONOUS_TEST)
        .option("compiler.Inlining", inlining.toString()).build()
    private fun program(language: Language, source: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, source) else BytecodeProgram(language, source)
    private fun compiled(target: RootCallTarget) =
        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target), "installed code remains valid")

    @Test fun originalInstalledCallsMatchNativeWithInlining() = native(true)
    @Test fun originalInstalledCallsMatchNativeAcrossResidualCalls() = native(false)
    private fun native(inlining: Boolean) {
        originalProvenanceAndNativeCorpusRemainExact()
        val rows = rows()
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for ((name, corpus) in rows.groupBy { it.name }) {
                    val entryName = when (name) { "memchr" -> "textMemchr"; "reverse" -> "textReverse"; else -> "textMeasure" }
                    val source = CoreModules.reachable(module(stage), entryName) + ("instrument" to true)
                    val program = program(language, source, backend)
                    val entry = program.entryValue(entryName)
                    val host = program.hostEntryTarget(if (name == "reverse") 3 else 4)
                    val targets = (source["bindings"] as List<Map<String, Any?>>).map { program.entryTarget(it["id"] as String) }
                    fun call(row: Row) {
                        val arguments = if (name == "reverse") arrayOf(row.bytes, row.offset, row.length)
                            else arrayOf(row.bytes, row.offset, row.length, row.count)
                        val result = Calls.target(host, arrayOf(entry, arguments))
                        val actual = if (name != "reverse") result.toString() else when (result) {
                            is ByteArray -> hex(result)
                            is ManagedAllocation -> hex(ByteArray(result.size.toInt()) { result.readByte(it.toLong()).toByte() })
                            else -> error("Expected original reverse ByteArray#, got $result")
                        }
                        assertEquals(row.result, actual,
                            "$stage/$backend/$name/inlining=$inlining/${row.offset}/${row.length}/${row.count}")
                    }
                    corpus.forEach(::call)
                    for (target in targets.asReversed()) {
                        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                        compiled(target)
                    }
                    for (row in corpus.asReversed()) {
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        call(row)
                        assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before,
                            "first and subsequent installed calls: $stage/$backend/$name")
                        targets.forEach(::compiled)
                    }
                    assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                    assertEquals(0, language.handoffState.get().arguments.depth)
                    assertEquals(0, language.handoffState.get().results.depth)
                }
            } finally { context.leave() }
        }
    }

    @Test fun originalProvenanceAndNativeCorpusRemainExact() {
        val manifest = json("manifest.json")
        assertEquals(520L, manifest["nativeRows"])
        assertTrue(CoreTextForeign.supportedUnit(manifest["unit"]))
        val recordedUnit = File(directory, "logs/original-registration.stdout").readLines()
            .single { it.startsWith("id:") }.substringAfter(':').trim()
        assertEquals(recordedUnit, manifest["unit"], "Preserve the original installed package registration")
        assertEquals(setOf("compiler/test-fixtures/TextCbitsAudit.hs", "compiler/test-fixtures/TextCbitsNative.hs",
            "test/haskell-fixtures/TextCbitsFixtures.hs", "scripts/core_original_foreign.py", "scripts/audit-core.py",
            "scripts/core-capabilities.json", "compiler/pinned-text/2.1.3/cbits/utils.c",
            "compiler/pinned-text/2.1.3/cbits/measure_off.c", "compiler/pinned-text/2.1.3/cbits/reverse.c", "compiler/pinned-text/2.1.3/LICENSE",
            "compiler/pinned-text/2.1.3/openbsd-memchr.c", "src/main/c/text-api.c", "scripts/build-cbits.py"),
            (manifest["inputHashes"] as Map<*, *>).keys)
        for (key in listOf("inputHashes", "artifactHashes")) {
            val hashes = manifest[key] as Map<String, String>
            assertFalse(hashes.isEmpty())
            for ((path, hash) in hashes) assertEquals(hash,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())), path)
        }
        rows()
        for (stage in listOf("pre", "post")) {
            val audit = json("$stage-audit.json")
            assertEquals(true, audit["accepted"])
            assertEquals(emptyList<Any>(), audit["issues"])
            assertEquals(emptyList<Any>(), audit["missingGlobals"])
            val calls = foreignApps(module(stage))
            assertEquals(setOf(recordedUnit), calls.map {
                (((it[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as Map<*, *>)["unit"] }.toSet())
            assertEquals(setOf("_hs_text_memchr", "_hs_text_measure_off", "_hs_text_reverse"), calls.map {
                (((it[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as Map<*, *>)["symbol"] }.toSet())
        }
    }
    private fun foreignApps(value: Any?): List<MutableList<Any?>> = when (value) {
        is Map<*, *> -> value.values.flatMap(::foreignApps)
        is List<*> -> (if (value.firstOrNull() == "app" && (value.getOrNull(6) as? Map<*, *>)?.containsKey("foreignCall") == true)
            listOf(value as MutableList<Any?>) else emptyList()) + value.flatMap(::foreignApps)
        else -> emptyList()
    }

    @Test fun installedTextReleaseIdentityHasABoundedSuffixGrammar() {
        val accepted = listOf("text-2.1.3-inplace", "text-2.1.3-e182", "text-2.1.3-119b")
        val rejected = listOf(null, "text-2.1.3", "text-2.1.3-", "text-2.1.2-e182", "text-2.1.4-e182",
            "text-2.1.3-e182-extra", "text-2.1.3-e182\n", "text-2.1.3-e182 ", "other-text-2.1.3-e182",
            "text-2.1.3-foreign", "text-2.1.3-e182:forged")
        accepted.forEach { assertTrue(CoreTextForeign.supportedUnit(it), it) }
        rejected.forEach { assertFalse(CoreTextForeign.supportedUnit(it), it) }
        for (unit in accepted + rejected) for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val source = module("pre")
                foreignApps(source).forEach { app ->
                    (((app[6] as Map<*, *>)["foreignCall"] as Map<*, *>)["target"] as MutableMap<String, Any?>)["unit"] = unit
                }
                if (unit in accepted) program(language, source, backend)
                else assertThrows(RuntimeFault::class.java) { program(language, source, backend) }
            } finally { context.leave() }
        }
    }

    @Test fun exactInstalledIdentityStateAndArrayProofsAreRequired() {
        for (backend in listOf("ast", "bytecode")) for (name in listOf("textMeasure", "textReverse"))
            for (variant in listOf("unit", "safety", "arity", "array", "result"))
            context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val source = CoreModules.reachable(module("post"), name)
                    val app = foreignApps(source).single()
                    val call = (app[6] as MutableMap<String, Any?>)["foreignCall"] as MutableMap<String, Any?>
                    when (variant) {
                        "unit" -> (call["target"] as MutableMap<String, Any?>)["unit"] = "other-text"
                        "safety" -> call["safety"] = "safe"
                        "arity" -> call["suppliedArity"] = 4L
                        "array" -> ((call["argumentReps"] as MutableList<Any?>)[0] as MutableMap<String, Any?>)["primReps"] = listOf("AddrRep")
                        "result" -> (call["resultRep"] as MutableMap<String, Any?>)["primReps"] = listOf("Word64Rep")
                    }
                    assertThrows(RuntimeFault::class.java) { program(language, source, backend) }
                } finally { context.leave() }
            }
    }

    @Test fun originalHeapAndPinnedStorageRemainDirectReadOnlyViews() {
        context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                for (allocation in listOf(ManagedAllocation.mutable(32, 8), PinnedMemory.allocate(32, 16))) {
                    val segment = allocation.nativeSegment()
                    for (i in 0L..31L) allocation.writeByte(i, 97)
                    allocation.writeByte(7, 98)
                    assertEquals(3L, ManagedText.invoke(TextForeignOp.MEMCHR, allocation, 4, 10, 98))
                    assertEquals(5L, ManagedText.invoke(TextForeignOp.MEASURE, allocation, 4, 10, 5))
                    allocation.writeByte(7, 97)
                    assertEquals(-1L, ManagedText.invoke(TextForeignOp.MEMCHR, allocation, 4, 10, 98))
                    assertSame(segment, allocation.nativeSegment())
                    assertTrue((0L..31L).all { allocation.readByte(it) == 97L })
                }
            } finally { context.leave() }
        }
    }

    @Test fun rangeCarrierPointerAndPermissionGuardsFailBeforeCallingC() {
        context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val bytes = ByteArray(8) { 97 }
                for ((offset, length) in listOf(-1L to 0L, 0L to -1L, 9L to 0L, 7L to 2L, Long.MAX_VALUE to 1L))
                    assertThrows(RuntimeFault::class.java) { ManagedText.invoke(TextForeignOp.MEASURE, bytes, offset, length, 0) }
                assertEquals(0L, ManagedText.invoke(TextForeignOp.MEASURE, bytes, 8, 0, 0))
                assertEquals(-1L, ManagedText.invoke(TextForeignOp.MEMCHR, bytes, 8, 0, 97))
                assertThrows(RuntimeFault::class.java) { ManagedText.invoke(TextForeignOp.MEMCHR, bytes, 0, 8, 256) }
                assertThrows(RuntimeFault::class.java) { ManagedText.invoke(TextForeignOp.MEMCHR, ManagedAddress.fromByteArray(bytes), 0, 8, 97) }
                val pointer = PinnedMemory.allocate(8, 8)
                pointer.writeAddressByteOffset(0, ManagedAddress.fromHex("00"))
                assertThrows(RuntimeFault::class.java) { ManagedText.invoke(TextForeignOp.MEASURE, pointer, 0, 0, 0) }
                assertArrayEquals(ByteArray(8) { 97 }, bytes)
            } finally { context.leave() }
        }
        context(native = false).use { context ->
            context.initialize("thc"); context.enter()
            try { assertThrows(RuntimeFault::class.java) { ManagedText.invoke(TextForeignOp.MEMCHR, byteArrayOf(1), 0, 1, 1) } }
            finally { context.leave() }
        }
    }

    @Test fun reverseBorrowsDistinctWritableStorageAndPreservesBounds() {
        context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val input = "xA\u0000é中🙂z".toByteArray(Charsets.UTF_8)
                val expected = "🙂中é\u0000A".toByteArray(Charsets.UTF_8)
                fun storage(bytes: ByteArray, kind: Int): Any = when (kind) {
                    0 -> bytes.copyOf()
                    else -> (if (kind == 1) ManagedAllocation.mutable(bytes.size.toLong(), 8)
                        else PinnedMemory.allocate(bytes.size.toLong(), 16)).also { allocation ->
                        bytes.forEachIndexed { i, byte -> allocation.writeByte(i.toLong(), byte.toLong()) }
                    }
                }
                fun bytes(value: Any): ByteArray = when (value) {
                    is ByteArray -> value
                    is ManagedAllocation -> ByteArray(value.size.toInt()) { value.readByte(it.toLong()).toByte() }
                    else -> error("storage")
                }
                for (sourceKind in 0..2) for (destinationKind in 0..2) {
                    val source = storage(input, sourceKind)
                    val destination = storage(ByteArray(expected.size + 3) { 0x55 }, destinationKind)
                    ManagedText.reverse(destination, source, 1, expected.size.toLong())
                    assertArrayEquals(input, bytes(source))
                    assertArrayEquals(expected + ByteArray(3) { 0x55 }, bytes(destination))
                    ManagedText.reverse(destination, source, input.size.toLong(), 0)
                    assertArrayEquals(expected + ByteArray(3) { 0x55 }, bytes(destination))
                }
                val output = ByteArray(16) { 0x55 }
                for ((offset, length) in listOf(-1L to 1L, 0L to -1L, input.size.toLong() to 1L,
                    Long.MAX_VALUE to 2L, 0L to Long.MAX_VALUE))
                    assertThrows(RuntimeFault::class.java) { ManagedText.reverse(output, input, offset, length) }
                assertThrows(RuntimeFault::class.java) { ManagedText.reverse(ByteArray(1), input, 0, 2) }
                assertThrows(RuntimeFault::class.java) { ManagedText.reverse(input, input, 0, 1) }
                val allocation = ManagedAllocation.mutable(16, 8)
                val alias = allocation.rawBytesIfPointerFree()
                assertThrows(RuntimeFault::class.java) { ManagedText.reverse(allocation, alias, 0, 1) }
                assertThrows(RuntimeFault::class.java) { ManagedText.reverse(alias, allocation, 0, 1) }
                allocation.shrink(2)
                assertThrows(RuntimeFault::class.java) { ManagedText.reverse(output, allocation, 1, 2) }
                assertThrows(RuntimeFault::class.java) { ManagedText.reverse(allocation, input, 0, 3) }
                val immutable = ManagedAllocation.immutable(ByteArray(16), 8)
                assertThrows(RuntimeFault::class.java) { ManagedText.reverse(immutable, input, 0, 1) }
                val pointers = ManagedAllocation.mutable(16, 8)
                pointers.writeAddressByteOffset(0, ManagedAddress.fromHex("00"))
                assertThrows(RuntimeFault::class.java) { ManagedText.reverse(output, pointers, 0, 1) }
                assertThrows(RuntimeFault::class.java) { ManagedText.reverse(pointers, input, 0, 1) }
                assertThrows(RuntimeFault::class.java) { ManagedText.reverse(output, ManagedAddress.fromByteArray(input), 0, 1) }
                assertArrayEquals(ByteArray(16) { 0x55 }, output)
            } finally { context.leave() }
        }
        context(native = false).use { context ->
            context.initialize("thc"); context.enter()
            try { assertThrows(RuntimeFault::class.java) { ManagedText.reverse(ByteArray(1), byteArrayOf(1), 0, 1) } }
            finally { context.leave() }
        }
    }

    @Test fun nativeAddressesIncludingForeignOwnersAreNotByteArrayCarriers() {
        context().use { first ->
            first.initialize("thc"); first.enter()
            val address = try { Language.currentState().nativeAllocations.malloc(8) } finally { first.leave() }
            try {
                context().use { second ->
                    second.initialize("thc"); second.enter()
                    try {
                        assertThrows(RuntimeFault::class.java) {
                            ManagedText.invoke(TextForeignOp.MEMCHR, address, 0, 8, 0)
                        }
                        assertEquals(0L, ManagedText.invoke(TextForeignOp.MEMCHR, byteArrayOf(37), 0, 1, 37))
                    } finally { second.leave() }
                }
            } finally {
                first.enter()
                try { Language.currentState().nativeAllocations.free(address) } finally { first.leave() }
            }
        }
    }
}
