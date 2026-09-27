// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.random.Random

class CoreJsonIndexTest {
    private fun index(text: String) = CoreJsonIndex.fromBytes(text.toByteArray(Charsets.UTF_8))

    /** Test-only scalar encoder, not a second production generator. */
    private fun sidecar(text: String, change: (MutableList<Pair<Int, Int>>) -> Unit = {}): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val events = ArrayList<Pair<Int, Int>>()
        val quarterCount = (bytes.size + 511) / 512
        val checkpoints = LongArray((quarterCount * 2 + 63) / 64)
        var quoted = false; var escaped = false
        for ((position, value) in bytes.withIndex()) {
            if (position % 512 == 0) {
                val quarter = position / 512
                val state = if (escaped) 2L else if (quoted) 1L else 0L
                checkpoints[quarter / 32] = checkpoints[quarter / 32] or (state shl ((quarter % 32) * 2))
            }
            val c = value.toInt() and 255
            if (quoted) {
                if (escaped) escaped = false
                else if (c == 92) escaped = true
                else if (c == 34) quoted = false
            } else if (c == 34) quoted = true
            else when (c) {
                123, 91 -> events.add(position to 3)
                125, 93 -> events.add(position to 0)
                44, 58 -> events.add(position to 2)
            }
        }
        change(events)
        val count = events.size
        val populations = IntArray(quarterCount)
        for (event in events) populations[event.first / 512]++
        val blocks = IntArray(((bytes.size + 2047) / 2048) * 2)
        var before = 0
        for (block in 0 until blocks.size / 2) {
            blocks[block * 2] = before
            for (run in 0..3) {
                val quarter = block * 4 + run
                val population = if (quarter < populations.size) populations[quarter] else 0
                if (run < 3) blocks[block * 2 + 1] = blocks[block * 2 + 1] or (population shl (run * 11))
                before += population
            }
        }
        val supers = if (bytes.isEmpty()) LongArray(0) else longArrayOf(0)
        val bp = LongArray((count * 2 + 63) / 64)
        for ((i, event) in events.withIndex()) {
            val bit = i * 2
            bp[bit / 64] = bp[bit / 64] or (event.second.toLong() shl (bit % 64))
        }
        val result = ByteBuffer.allocate(96 + supers.size * 8 + blocks.size * 4 + checkpoints.size * 8 + bp.size * 8)
            .order(ByteOrder.LITTLE_ENDIAN)
        result.put("THCJSIX1".toByteArray(Charsets.US_ASCII)).putInt(2).putInt(0)
        result.putLong(bytes.size.toLong()).putLong(count.toLong())
        result.put(MessageDigest.getInstance("SHA-256").digest(bytes))
        for (word in supers) result.putLong(word)
        for (word in blocks) result.putInt(word)
        for (word in checkpoints) result.putLong(word)
        for (word in bp) result.putLong(word)
        result.put(MessageDigest.getInstance("SHA-256").digest(result.array().copyOf(result.position())))
        return result.array()
    }

    private fun refreshIntegrity(bytes: ByteArray) {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes.copyOf(bytes.size - 32))
        digest.copyInto(bytes, bytes.size - 32)
    }

    @Test fun exactByteSpansAndClosingOrderDifferFromOpeningOrder() {
        val text = " \n{\"first\":[1,{\"λ\":\"😀\"},3],\"second\":{\"empty\":[]},\"last\":false}\t"
        index(text).use { source ->
            val root = source.root
            assertEquals(2, root.start)
            assertEquals(text.toByteArray(Charsets.UTF_8).size - 1, root.endExclusive)
            val first = root.member("first")!!
            val values = first.elements()
            assertEquals(3, values.size)
            assertEquals("[1,{\"λ\":\"😀\"},3]", first.bytes().toString(Charsets.UTF_8))
            assertEquals("{\"λ\":\"😀\"}", values[1].bytes().toString(Charsets.UTF_8))
            assertEquals("😀", values[1].member("λ")!!.decode())
            assertEquals("[]", root.member("second")!!.member("empty")!!.bytes().toString(Charsets.UTF_8))
            assertEquals(false, root.member("last")!!.decode())
            assertEquals(Json.parse(text), source.validateDocument())
        }
    }

    @Test fun quoteEscapesBackslashRunsUnicodeAndChunkTails() {
        val strings = listOf("", "[{}]:,", "\\", "\\\"", "\\\\\"[]", "\"\\\"", "λ😀", "a\u0000\nb")
        for (prefix in 0..130) for (value in strings) {
            val text = " ".repeat(prefix) + "[" + Json.stringify(value) + ",42]" + " ".repeat(prefix % 65)
            index(text).use { source ->
                val elements = source.root.elements()
                assertEquals(2, elements.size)
                assertTrue(elements[0].stringEquals(value), "prefix=$prefix value=$value")
                assertFalse(elements[0].stringEquals(value + "x"))
                assertEquals(value, elements[0].decode())
                assertEquals(42L, elements[1].decode())
            }
        }
        index("[\"\\u0078\\uD83D\\uDE00\",\"\\uD800\",\"\\\\u0078\"]").use { source ->
            val values = source.root.elements()
            assertTrue(values[0].stringEquals("x😀"))
            assertTrue(values[1].stringEquals("\uD800"))
            assertTrue(values[2].stringEquals("\\u0078"))
            assertEquals(0L, source.statistics().decodedSpanCount)
        }
    }

    @Test fun rootScalarsAndEmptyContainers() {
        for (text in listOf("0", "-42", "1.25e+2", "true", "false", "null", "\"\"", "[]", "{}")) {
            index(" \t$text\r\n").use { source ->
                assertEquals(text, source.root.bytes().toString(Charsets.UTF_8))
                assertEquals(Json.parse(text), source.root.decode())
            }
        }
        index("[[],{}]").use { source ->
            assertTrue(source.root.elements()[0].elements().isEmpty())
            assertTrue(source.root.elements()[1].members().isEmpty())
        }
    }

    @Test fun malformedStructuralBoundariesNeverProduceAnIndex() {
        for (text in listOf("", " ", "[", "{", "[}", "{]", "[1,]", "{\"a\":}",
            "{\"a\" 1}", "{1:2}", "{\"a\":1,}", "[1 2]", "[] []", "\"unterminated", "\"escape\\",
            "[\"raw\ncontrol\"]", "[true{}]", "{\"a\":1]")) {
            assertThrows(Exception::class.java, { index(text).close() }, text)
        }
    }

    @Test fun deferredScalarValidationAndFailuresAreMemoized() {
        index("{\"ok\":42,\"untouched\":[1e999,\"\\q\",truely]}").use { source ->
            assertEquals(0L, source.statistics().decodedSpanCount)
            assertEquals(42L, source.root.member("ok")!!.decode())
            assertEquals(1L, source.statistics().decodedSpanCount)
            val bad = source.root.member("untouched")!!.elements()[0]
            val first = assertThrows(Exception::class.java) { bad.decode() }
            val second = assertThrows(Exception::class.java) { bad.decode() }
            assertSame(first, second)
            assertEquals(2L, source.statistics().decodedSpanCount)
            assertThrows(Exception::class.java) { source.validateDocument() }
        }
    }

    @Test fun selectedDuplicateNamesAndExplicitWholeValidation() {
        index("{\"ok\":42,\"unused\":{\"x\":1,\"\\u0078\":2}}").use { source ->
            assertEquals(42L, source.root.member("ok")!!.decode())
            assertThrows(IllegalArgumentException::class.java) { source.validateDocument() }
        }
        index("{\"other\":1,\"\\u006fther\":2,\"ok\":42}").use { source ->
            assertEquals(42L, source.root.member("ok")!!.decode())
            assertThrows(IllegalArgumentException::class.java) { source.root.member("other") }
            assertThrows(IllegalArgumentException::class.java) { source.root.members() }
        }
    }

    @Test fun demandProjectionDoesNotDecodeOtherKeysOrValues() {
        index("{\"skip\":[{\"invalid\":1e999}],\"\\u0074ake\":7,\"last\":false}").use { source ->
            assertEquals(7L, source.root.member("take")!!.decode())
            assertEquals(1L, source.statistics().decodedSpanCount)
            assertEquals(1L, source.statistics().decodedByteCount)
            assertNull(source.root.member("missing"))
            assertEquals(1L, source.statistics().decodedSpanCount)
            assertEquals(listOf("skip", "take", "last"), source.root.members().map { it.name })
            assertEquals(4L, source.statistics().decodedSpanCount, "members decodes immediate keys, not values")
        }
    }

    @Test fun hugeUnusedStringUsesLocalInterestMasksWithoutDecode() {
        val text = "[\"" + "payload { [ \\\" \\\\ λ ".repeat(100_000) + "\",42]"
        val bytes = text.toByteArray(Charsets.UTF_8)
        CoreJsonIndex.fromBytes(bytes).use { source ->
            val before = source.statistics()
            val values = source.root.elements()
            assertEquals(42L, values[1].decode())
            val after = source.statistics()
            assertEquals(bytes.size.toLong(), after.structuralBytesScanned)
            assertEquals(bytes.size.toLong() * 2, after.indexSourceBytesScanned)
            assertEquals(1L, after.decodedSpanCount)
            assertEquals(2L, after.decodedByteCount)
            assertTrue(after.navigationByteReads - before.navigationByteReads < 32)
            assertTrue(after.regeneratedSourceBytes - before.regeneratedSourceBytes <= 1024)
            assertTrue(after.indexByteSize < bytes.size / 100,
                "source-backed index must not retain a dense input-sized bitmap")
        }
    }

    @Test fun scalarBoundaryWhitespaceScansAreNotHidden() {
        index("[\"skip\"" + " ".repeat(100_000) + ",42]").use { source ->
            val before = source.statistics()
            assertEquals(42L, source.root.elements()[1].decode())
            assertTrue(source.statistics().navigationByteReads - before.navigationByteReads >= 100_000)
            assertEquals(1L, source.statistics().decodedSpanCount)
        }
    }

    @Test fun farNestedSubtreeUsesBoundedBpNavigation() {
        val skipped = "[" + (0 until 20_000).joinToString(",") { "[$it,{\"x\":[true,false,null]}]" } + "]"
        index("[$skipped,42]").use { source ->
            val before = source.statistics()
            val values = source.root.elements()
            assertEquals(42L, values[1].decode())
            val after = source.statistics()
            assertEquals(1L, after.decodedSpanCount)
            assertTrue(after.balancedParenthesisBitsExamined - before.balancedParenthesisBitsExamined < 4096,
                "subtree skipping must not linearly scan the whole BP tree")
            assertTrue(after.navigationByteReads - before.navigationByteReads < 32)
            assertTrue(after.regeneratedSourceBytes - before.regeneratedSourceBytes <= 1024,
                "rank and select may each regenerate only one bounded512-byte block")
        }
    }

    @Test fun deepStructureDoesNotRecurseOnTheJvmStack() {
        val depth = 20_000
        index("[".repeat(depth) + "0" + "]".repeat(depth)).use { source ->
            assertEquals(depth * 2 + 1, source.root.endExclusive)
            assertEquals(depth * 2, source.root.elements()[0].endExclusive)
            assertEquals(0L, source.statistics().decodedSpanCount)
        }
    }

    @Test fun wideArrayIterationDoesNotRestartFromTheFirstSibling() {
        index((0 until 5000).joinToString(",", "[", "]")).use { source ->
            val before = source.statistics().balancedParenthesisBitsExamined
            var count = 0
            for (span in source.root.elements()) { assertEquals(count.toLong(), span.decode()); count++ }
            assertEquals(5000, count)
            assertTrue(source.statistics().balancedParenthesisBitsExamined - before < 25_000)
        }
    }

    @Test fun snapshotCloseAndAlreadyPublishedValues(@TempDir directory: Path) {
        val bytes = "[1,{\"x\":2}]".toByteArray()
        val source = CoreJsonIndex.fromBytes(bytes)
        bytes.fill(32)
        val retained = source.root.elements()[1]
        val value = retained.decode()
        assertSame(value, retained.decode())
        val path = directory.resolve("input.json")
        Files.writeString(path, "[7]")
        CoreJsonIndex.read(path).use { fromPath ->
            Files.writeString(path, "[9]")
            Files.delete(path)
            assertEquals(7L, fromPath.root.elements()[0].decode())
        }
        source.close(); source.close()
        assertEquals(mapOf("x" to 2L), value)
        assertThrows(IllegalStateException::class.java) { retained.decode() }
        assertThrows(IllegalStateException::class.java) { retained.start }
        assertThrows(IllegalStateException::class.java) { source.root }
    }

    @Test fun originalByteIdentityNotReserializedIdentity() {
        index("{\"x\":1}").use { compact -> index("{ \"x\" : 1 }").use { spaced ->
            assertEquals(compact.root.decode(), spaced.root.decode())
            assertNotEquals(compact.sha256(), spaced.sha256())
            val copy = compact.root.bytes(); copy.fill(32)
            assertEquals("{\"x\":1}", compact.root.bytes().toString(Charsets.UTF_8))
        } }
    }

    @Test fun loadedSidecarCountsWholeScansSeparatelyFromExplicitDecode() {
        for (text in listOf("0", "  \"λ😀\" ", "[]", "{}", "{\"x\":[1,{\"y\":true}],\"z\":null}")) {
            val source = text.toByteArray(Charsets.UTF_8)
            val binary = sidecar(text)
            CoreJsonIndex.loadSidecar(source, ByteArrayInputStream(binary)).use { loaded ->
                val initial = loaded.statistics()
                assertEquals(binary.size.toLong(), initial.serializedByteSize)
                assertEquals(source.size.toLong(), initial.structuralBytesScanned)
                assertEquals(source.size.toLong(), initial.indexSourceBytesScanned)
                assertEquals(source.size.toLong(), initial.sourceHashBytesScanned)
                assertEquals(source.size.toLong(), initial.sourceSnapshotBytesCopied)
                assertEquals(0L, initial.sourceFileBytesRead)
                assertEquals(0L, initial.decodedSpanCount)
                loaded.sha256(); loaded.sha256()
                assertEquals(source.size.toLong(), loaded.statistics().sourceHashBytesScanned)
                assertEquals(Json.parse(text), loaded.validateDocument())
            }
        }
    }

    @Test fun matchingSelfHashesDoNotAuthorizeWrongTopologyOrCounts() {
        fun rejected(text: String, binary: ByteArray = sidecar(text)) {
            assertThrows(Exception::class.java) { CoreJsonIndex.loadSidecar(text.toByteArray(), ByteArrayInputStream(binary)).close() }
        }
        for (text in listOf("[}", "{]", "[] []", "[1,]", "[true{}]", "[\"unterminated]", "{\"x\":1]")) rejected(text)
        val text = "[[1],2,{}]"
        rejected(text, sidecar(text) { it.subList(1, it.lastIndex).clear() })
        rejected(text, sidecar(text) { it[0] = it[0].first to 0 })
        // Intra-quarter offsets are deliberately NOT stored: this leaves the wire identical.
        assertArrayEquals(sidecar(text), sidecar(text) { it[1] = 2 to 3 })
        val acrossQuarter = "[" + " ".repeat(512) + "[]]"
        rejected(acrossQuarter, sidecar(acrossQuarter) { it[1] = 1 to 3 })
        rejected("[1]", sidecar("[2]"))
        // Even with a matching digest, m=0 cannot conceal a container root.
        rejected(text, sidecar(text) { it.clear() })
    }

    @Test fun sidecarEnvelopePaddingPopulationAndTailsAreChecked() {
        val text = "[{}]"
        val bytes = text.toByteArray()
        val original = sidecar(text)
        fun rejected(binary: ByteArray) {
            assertThrows(Exception::class.java) { CoreJsonIndex.loadSidecar(bytes, ByteArrayInputStream(binary)).close() }
        }
        for (length in original.indices) rejected(original.copyOf(length))
        rejected(original + 0)
        for (offset in listOf(0, 8, 12, 16, 24, 32, original.lastIndex)) {
            val bad = original.copyOf(); bad[offset] = (bad[offset].toInt() xor 1).toByte(); rejected(bad)
        }
        // One epoch at64, one block at72, one checkpoint word at80, then BP at88.
        val wrongPopulation = original.copyOf()
        wrongPopulation[76] = (wrongPopulation[76].toInt() xor 1).toByte()
        refreshIntegrity(wrongPopulation); rejected(wrongPopulation)
        val padding = original.copyOf(); padding[81] = 1
        refreshIntegrity(padding); rejected(padding)
        val checkpoint = original.copyOf(); checkpoint[80] = 3
        refreshIntegrity(checkpoint); rejected(checkpoint)
        val topology = original.copyOf(); topology[88] = (topology[88].toInt() xor 1).toByte()
        refreshIntegrity(topology); rejected(topology)
        val epoch = original.copyOf(); epoch[64] = 1
        refreshIntegrity(epoch); rejected(epoch)
        val historical = original.copyOf()
        ByteBuffer.wrap(historical).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 1)
        refreshIntegrity(historical); rejected(historical)
        val oversized = original.copyOf()
        ByteBuffer.wrap(oversized).order(ByteOrder.LITTLE_ENDIAN).putLong(24, Long.MAX_VALUE)
        refreshIntegrity(oversized); rejected(oversized)
    }

    @Test fun derivedExtentsAreCheckedBeforeAllocationOrNarrowing() {
        assertThrows(ArithmeticException::class.java) { JsonIndexShape(Int.MAX_VALUE.toLong(), Int.MAX_VALUE.toLong() - 1) }
        for ((source, count) in listOf(0L to 1L, -1L to 0L, 4L to 6L, Long.MAX_VALUE to 2L)) {
            assertThrows(IllegalArgumentException::class.java) { JsonIndexShape(source, count) }
        }
        val sparse = JsonIndexShape(Int.MAX_VALUE.toLong(), 2)
        assertEquals(1, sparse.epochCount)
        assertEquals(1048576, sparse.blockCount)
        assertEquals(8388608, sparse.checkpointBits)
        assertEquals(4, sparse.bpBits)
        val empty = JsonIndexShape(0, 0)
        assertEquals(0, empty.epochCount)
        assertEquals(0, empty.blockCount)
        assertEquals(0, empty.checkpointBits)
        assertEquals(0, empty.bpBits)
        assertEquals(96L, empty.serializedBytes)
        assertEquals(6, JsonIndexShape(4, 3).bpBits, "marker count may be odd")
    }

    @Test fun loadedOwnershipChecksCachedReadsAndRetainedIterators(@TempDir directory: Path) {
        val text = "[1,{\"x\":2}]"
        val source = text.toByteArray()
        var closed = false
        val stream = object : ByteArrayInputStream(sidecar(text)) { override fun close() { closed = true; super.close() } }
        val loaded = CoreJsonIndex.loadSidecar(source, stream)
        assertFalse(closed, "caller owns sidecar stream")
        source.fill(32)
        val cursor = loaded.root.elements().iterator()
        val first = cursor.next(); assertEquals(1L, first.decode())
        loaded.close()
        assertThrows(IllegalStateException::class.java) { first.decode() }
        assertThrows(IllegalStateException::class.java) { cursor.hasNext() }
        assertThrows(IllegalStateException::class.java) { cursor.next() }
        assertFalse(closed)
        val path = directory.resolve("input.json")
        Files.writeString(path, text)
        CoreJsonIndex.loadSidecar(path, ByteArrayInputStream(sidecar(text))).use { pinned ->
            Files.writeString(path, "null"); Files.delete(path)
            assertEquals(text, pinned.root.bytes().toString(Charsets.UTF_8))
            assertEquals(text.length.toLong(), pinned.statistics().sourceFileBytesRead)
            assertEquals(0L, pinned.statistics().sourceSnapshotBytesCopied)
        }
        val malformed = object : ByteArrayInputStream(ByteArray(3)) { override fun close() { closed = true } }
        assertThrows(Exception::class.java) { CoreJsonIndex.loadSidecar(byteArrayOf(48), malformed) }
        assertFalse(closed, "failure does not close caller's stream")
    }

    @Test fun sourceBackedRankSelectMatchesEveryMarkerAndBoundedBlockWork() {
        val text = "[\"" + "x".repeat(509) + "\\\"[],:" + "\\\\".repeat(800) + "λ😀\",{\"x\":[0,1]},true]"
        val expected = ArrayList<Int>()
        val encoded = sidecar(text) { events -> expected.addAll(events.map { it.first }) }
        val bytes = text.toByteArray(Charsets.UTF_8)
        CoreJsonIndex.loadSidecar(bytes, ByteArrayInputStream(encoded)).use { source ->
            var rank = 0
            for (position in 0..bytes.size) {
                val before = source.statistics()
                assertEquals(rank, source.rankInterest(position), "rank at $position")
                val after = source.statistics()
                assertTrue(after.regeneratedSourceBytes - before.regeneratedSourceBytes <= 512)
                assertTrue(after.regeneratedBlockCount - before.regeneratedBlockCount <= 1)
                if (rank < expected.size && expected[rank] == position) rank++
            }
            for (ordinal in expected.indices.reversed()) {
                val before = source.statistics()
                assertEquals(expected[ordinal], source.selectInterest(ordinal))
                assertTrue(source.statistics().regeneratedSourceBytes - before.regeneratedSourceBytes <= 512)
            }
            assertThrows(IllegalArgumentException::class.java) { source.rankInterest(-1) }
            assertThrows(IllegalArgumentException::class.java) { source.rankInterest(bytes.size + 1) }
            assertThrows(IllegalArgumentException::class.java) { source.selectInterest(expected.size) }
            assertEquals(Json.parse(text), source.validateDocument())
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoreJsonIndex.loadSidecar(ByteArray(0), ByteArrayInputStream(sidecar("")))
        }
    }

    @Test fun sourceMaskTranscriptionPreservesAllIncomingStatesAndTails() {
        val alphabet = byteArrayOf(34, 92, 123, 125, 91, 93, 44, 58, 32, 0, -1, 120)
        for (initial in 0..2) for (length in listOf(0, 1, 2, 31, 32, 33, 63, 64, 65, 511, 512)) {
            val source = ByteArray(length + 6) { alphabet[it % alphabet.size] }
            val actual = LongArray(8); val opens = LongArray(8); val closes = LongArray(8)
            val final = jsonMaskBlock(source, 3, length, initial, actual, opens, closes)
            var quoted = initial != 0; var escaped = initial == 2
            for (at in 0 until length) {
                val c = source[at + 3].toInt() and 255
                val marker = !quoted && c in listOf(123, 125, 91, 93, 44, 58)
                val bit = 1L shl (at and 63)
                assertEquals(marker, actual[at ushr 6] and bit != 0L)
                assertEquals(marker && (c == 123 || c == 91), opens[at ushr 6] and bit != 0L)
                assertEquals(marker && (c == 125 || c == 93), closes[at ushr 6] and bit != 0L)
                if (escaped) escaped = false
                else if (quoted && c == 92) escaped = true
                else if (c == 34) quoted = !quoted
            }
            assertEquals(if (escaped) 2 else if (quoted) 1 else 0, final)
            for (bit in length until 512) assertEquals(0L, actual[bit ushr 6] and (1L shl (bit and 63)))
        }
    }

    @Test fun uncachedDecodeLeavesCanonicalRetentionToTheAdapter() {
        index("[\"a sufficiently long demanded string\",1e999]").use { source ->
            val string = source.root.elements()[0]
            val first = string.decodeUncached(); val second = string.decodeUncached()
            assertEquals(first, second); assertNotSame(first, second)
            assertEquals(2L, source.statistics().decodedSpanCount)
            val cached = string.decode()
            assertSame(cached, string.decode())
            assertEquals(3L, source.statistics().decodedSpanCount)
            val bad = source.root.elements()[1]
            val failure = assertThrows(Exception::class.java) { bad.decodeUncached() }
            assertNotSame(failure, assertThrows(Exception::class.java) { bad.decodeUncached() })
            source.close()
            assertThrows(IllegalStateException::class.java) { string.decodeUncached() }
        }
    }

    @Test fun randomStructuralParityWithExistingJsonDecoder() {
        val random = Random(17029)
        fun tree(depth: Int): Any? = if (depth == 0) when (random.nextInt(5)) {
            0 -> null; 1 -> random.nextLong(); 2 -> random.nextBoolean(); 3 -> "λ😀[]{}\\\""; else -> random.nextDouble()
        } else if (random.nextBoolean()) List(random.nextInt(7)) { tree(depth - 1) }
        else (0 until random.nextInt(7)).associate { "key$it" to tree(depth - 1) }
        fun verify(span: CoreJsonIndex.Span, expected: Any?) {
            assertEquals(expected, Json.parse(span.bytes().toString(Charsets.UTF_8)))
            when (expected) {
                is List<*> -> {
                    val actual = span.elements(); assertEquals(expected.size, actual.size)
                    actual.zip(expected).forEach { (child, value) -> verify(child, value) }
                }
                is Map<*, *> -> {
                    val actual = span.members(); assertEquals(expected.keys.toList(), actual.map { it.name })
                    actual.forEach { verify(it.value, expected[it.name]) }
                }
                else -> assertEquals(expected, span.decode())
            }
        }
        repeat(100) {
            val text = Json.stringify(tree(5)); val expected = Json.parse(text)
            index(text).use { verify(it.root, expected) }
            CoreJsonIndex.loadSidecar(text.toByteArray(Charsets.UTF_8), ByteArrayInputStream(sidecar(text))).use { verify(it.root, expected) }
        }
    }

    @Test fun suppliedRankPortBoundariesAndIndependentQuarterPopulations() {
        for (length in listOf(0, 1, 63, 64, 65, 511, 512, 513, 2047, 2048, 2049, 4097)) {
            val random = Random(length)
            val words = LongArray((length + 63) / 64) { random.nextLong() }
            val original = words.copyOf()
            val rank = JsonRankDirectory.build(words, length.toLong())
            var expected = 0L
            for (position in 0..length) {
                assertEquals(expected, rank.rank1(position.toLong()), "$length / $position")
                if (position < length && words[position ushr 6] and (1L shl (position and 63)) != 0L) expected++
            }
            assertEquals(expected, rank.totalOnes)
            assertArrayEquals(original, words)
            assertEquals(((length.toLong() + 2047) / 2048) * 8 + if (length == 0) 0 else 8, rank.directoryBytes)
        }
        val packed = 1 or (511 shl 11) or (512 shl 22)
        assertEquals(listOf(0, 1, 512, 1024), (0..3).map { jsonRankRunPrefix(packed, it) })
        val cursor = JsonRankDirectoryCursor()
        assertEquals(-1, cursor.before(1, 0xffffffffL))
        assertThrows(ArithmeticException::class.java) { cursor.before(2, 1L shl 32) }
        assertEquals(0, cursor.before(1L shl 21, 1L shl 32))
    }
}
