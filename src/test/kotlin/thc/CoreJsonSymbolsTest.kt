// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CoreJsonSymbolsTest {
    @TempDir lateinit var directory: Path
    private val source get() = directory.resolve("module.json")
    private val symbols get() = directory.resolve("module.json.symbols")
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private val byteOrder = Comparator<String> { left, right ->
        val a = left.toByteArray(Charsets.UTF_8)
        val b = right.toByteArray(Charsets.UTF_8)
        var result = 0
        for (i in 0 until minOf(a.size, b.size)) {
            result = (a[i].toInt() and 255) - (b[i].toInt() and 255)
            if (result != 0) break
        }
        if (result == 0) a.size.compareTo(b.size) else result
    }

    /** Independent test writer, not a runtime producer or index-building fallback. */
    private fun fixture(bindings: List<Map<String, Any?>>, padding: Int = 0) {
        val bytes = java.io.ByteArrayOutputStream()
        fun write(text: String) { bytes.write(text.toByteArray(Charsets.UTF_8)) }
        write("{\"unused\":\"" + "x".repeat(padding) + "\",\"bindings\":[")
        val positions = linkedMapOf<String, Long>()
        for ((index, binding) in bindings.withIndex()) {
            if (index != 0) write(",")
            positions[binding["id"] as String] = bytes.size().toLong()
            write(Json.stringify(binding))
        }
        write("]}")
        Files.write(source, bytes.toByteArray())
        Files.writeString(symbols, positions.keys.sortedWith(byteOrder).joinToString("") { "$it ${positions.getValue(it)}\n" })
    }
    private fun binding(id: String, value: Long = 7) = mapOf("id" to id, "name" to "same display name",
        "expr" to listOf("lit", "int", value.toString()), "annotation" to "} [ \\\" λ😀")

    @Test fun binarySearchUsesRawUtf8OrderAndSelectedObjectNotNextSortedPosition() {
        val ids = listOf("unit:M.z", "unit:M.😀", "unit:M.a space name", "unit:M.\ue000", "unit:M.a", "unit:M.λ")
        fixture(ids.mapIndexed { index, id -> binding(id, index.toLong()) }, 1024 * 1024)
        CoreJsonSymbols(source, symbols).use { reader ->
            assertEquals(0L, reader.statistics().directoryOpens)
            for ((index, id) in ids.withIndex()) {
                val selected = reader.binding(id)!!
                assertEquals(id, selected["id"])
                assertEquals(index.toString(), (selected["expr"] as List<*>)[2])
                assertSame(selected, reader.binding(id))
            }
            assertNull(reader.binding("unit:M.absent"))
            val counts = reader.statistics()
            assertEquals(1L, counts.directoryOpens)
            assertEquals(1L, counts.sourceOpens)
            assertEquals(ids.size.toLong(), counts.decodedBindings)
            assertEquals(0L, counts.hashBytesScanned)
            assertTrue(counts.sourceByteReads < 10000, "unused megabyte is mapped, never decoded or scanned")
            assertEquals(Files.size(source), counts.sourceMappedBytes)
        }
    }

    @Test fun missesDoNotOpenTheJsonAndEmptyDirectoryHasNoRows() {
        Files.writeString(symbols, "unit:M.f 100\n")
        CoreJsonSymbols(source, symbols).use { reader ->
            assertNull(reader.binding("unit:M.absent"))
            assertEquals(0L, reader.statistics().sourceOpens)
        }
        Files.writeString(symbols, "")
        CoreJsonSymbols(source, symbols).use { reader ->
            assertNull(reader.binding("unit:M.f"))
            assertEquals(0L, reader.statistics().sourceOpens)
        }
    }

    @Test fun exactIdentityBoundsUtf8AndFailuresAreCheckedOnlyAtDemand() {
        fixture(listOf(binding("actual")))
        val offset = Files.readString(symbols).substringAfterLast(' ').trim()
        Files.writeString(symbols, "different $offset\n")
        CoreJsonSymbols(source, symbols).use { reader ->
            val first = assertThrows(IllegalArgumentException::class.java) { reader.binding("different") }
            assertTrue(first.message!!.contains("identity mismatch"))
            assertSame(first, assertThrows(IllegalArgumentException::class.java) { reader.binding("different") })
            assertEquals(1L, reader.statistics().decodedBindings)
        }
        for (row in listOf("actual -1\n", "actual 9223372036854775808\n", "actual 99999999\n",
            "actual 0\n", "actual x\n", "actual 1", "actual \n")) {
            Files.writeString(symbols, row)
            CoreJsonSymbols(source, symbols).use { reader ->
                assertThrows(Exception::class.java) { reader.binding("actual") }
            }
        }
        Files.write(source, byteArrayOf(123, 34, 105, 100, 34, 58, 34, -1, 34, 125))
        Files.writeString(symbols, "actual 0\n")
        CoreJsonSymbols(source, symbols).use { reader ->
            assertThrows(java.nio.charset.CharacterCodingException::class.java) { reader.binding("actual") }
        }
    }

    @Test fun exactFileHashesAreOptInAndSourceHashIsNotPaidForAMiss() {
        fixture(listOf(binding("f")))
        val jsonHash = hash(Files.readAllBytes(source))
        val directoryHash = hash(Files.readAllBytes(symbols))
        CoreJsonSymbols(source, symbols, true, jsonHash, directoryHash).use { reader ->
            assertNull(reader.binding("missing"))
            assertEquals(Files.size(symbols), reader.statistics().hashBytesScanned)
            assertNotNull(reader.binding("f"))
            assertEquals(Files.size(symbols) + Files.size(source), reader.statistics().hashBytesScanned)
        }
        CoreJsonSymbols(source, symbols, true, "0".repeat(64), directoryHash).use { reader ->
            assertThrows(IllegalArgumentException::class.java) { reader.binding("f") }
            assertEquals(1L, reader.statistics().sourceOpens)
        }
        CoreJsonSymbols(source, symbols, false, "0".repeat(64), "0".repeat(64)).use { reader ->
            assertNotNull(reader.binding("f"))
            assertEquals(0L, reader.statistics().hashBytesScanned)
        }
    }

    @Test fun concurrentDemandSharesOneDecodeAndMappingsSurvivePathReplacementUntilClose() {
        fixture(listOf(binding("first"), binding("second")))
        val reader = CoreJsonSymbols(source, symbols)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = pool.invokeAll(List(8) { java.util.concurrent.Callable { reader.binding("first") } }).map { it.get() }
            assertTrue(results.all { it === results.first() })
            assertEquals(1L, reader.statistics().decodedBindings)
            val replacement = directory.resolve("replacement.json")
            Files.writeString(replacement, "null")
            Files.move(replacement, source, StandardCopyOption.REPLACE_EXISTING)
            assertEquals("second", reader.binding("second")!!["id"])
            reader.close()
            assertEquals("first", results.first()!!["id"])
            assertThrows(IllegalStateException::class.java) { reader.binding("first") }
        } finally { reader.close(); pool.shutdownNow() }
    }

    @Test fun explicitVerificationObservesReplacementEvenWithAnIdleTrustedMapping() {
        fixture(listOf(binding("f")))
        val jsonHash = hash(Files.readAllBytes(source))
        val directoryHash = hash(Files.readAllBytes(symbols))
        CoreFileMappings(1024 * 1024, 4).use { cache ->
            fun reader(verify: Boolean) = CoreJsonSymbols(source, symbols, verify, jsonHash, directoryHash, cache)
            reader(false).use { assertNotNull(it.binding("f")) }
            reader(false).use {
                assertNotNull(it.binding("f"))
                assertEquals(2L, it.statistics().mappingCacheHits)
            }
            // Replacement, not in-place mutation of an immutable mapping.
            val replacement = directory.resolve("changed.json")
            Files.writeString(replacement, "{}")
            Files.move(replacement, source, StandardCopyOption.REPLACE_EXISTING)
            repeat(2) { reader(true).use {
                val failure = assertThrows(IllegalArgumentException::class.java) { it.binding("f") }
                assertTrue(failure.message.orEmpty().contains("hash mismatch"))
                assertEquals(0L, it.statistics().mappingCacheHits)
                assertEquals(Files.size(symbols) + Files.size(source), it.statistics().hashBytesScanned)
            } }
            reader(false).use { assertNotNull(it.binding("f"), "trusted identity still names the pinned old immutable bytes") }
        }
    }

    @Test fun oneLookupDoesNotCreateAHeapRowIndexForNOrTwiceN() {
        for (size in listOf(512, 1024)) {
            fixture((0 until size).reversed().map { binding("unit:M.f${it.toString().padStart(5, '0')}") })
            CoreJsonSymbols(source, symbols).use { reader ->
                assertEquals("unit:M.f00250", reader.binding("unit:M.f00250")!!["id"])
                val counts = reader.statistics()
                assertEquals(1L, counts.decodedBindings)
                assertTrue(counts.lookupComparisons <= 12)
                assertTrue(counts.directoryByteReads < Files.size(symbols) / 4)
                assertTrue(counts.sourceByteReads < 512)
            }
        }
    }

    @Test fun moduleMetadataSkipsBindingsAndOtherModulesWithoutOpeningTheSymbolDirectory() {
        val prefix = "{\"module\":\"Aλ\",\"bindings\":"
        val body = "[" + Json.stringify(binding("unit:A.huge") + ("unused" to "x".repeat(1024 * 1024))) + "]"
        val suffix = ",\"constructors\":[],\"source\":\"after binding body\"}"
        val leading = "{\"module\":\"B\",\"bindings\":[unparsed invalid cold body]}\n"
        val start = leading.toByteArray().size.toLong()
        val bindingsStart = start + prefix.toByteArray().size
        val bindingsEnd = bindingsStart + body.toByteArray().size
        val end = bindingsEnd + suffix.toByteArray().size
        Files.writeString(source, leading + prefix + body + suffix + "\n{unparsed module C}")
        val span = CoreJsonSymbols.ModuleSpan(start, end, bindingsStart, bindingsEnd)
        val reader = CoreJsonSymbols(source, symbols)
        val counts = reader.counters
        reader.use {
            val module = reader.moduleMetadata(span)
            assertEquals("Aλ", module["module"])
            assertEquals(emptyList<Any>(), module["bindings"])
            assertEquals("after binding body", module["source"])
            assertSame(module, reader.moduleMetadata(span))
            val statistics = counts.statistics()
            assertEquals(0L, statistics.directoryOpens)
            assertEquals(1L, statistics.sourceOpens)
            assertEquals(1L, statistics.decodedModules)
            assertEquals(0L, statistics.decodedBindings)
            assertEquals((prefix + suffix).toByteArray().size.toLong(), statistics.metadataBytes)
            assertTrue(statistics.sourceByteReads < 200)
            assertThrows(IllegalArgumentException::class.java) {
                reader.moduleMetadata(span.copy(bindingsEnd = end + 1))
            }
            assertThrows(IllegalArgumentException::class.java) {
                reader.moduleMetadata(span.copy(bindingsStart = bindingsStart + 1))
            }
        }
        assertEquals(1L, counts.statistics().decodedModules)
        assertTrue(counts.javaClass.declaredFields.all { it.type == java.lang.Long.TYPE },
            "detached counters must not retain source owners, caches, or closures")
        assertThrows(IllegalStateException::class.java) { reader.moduleMetadata(span) }
    }
}
