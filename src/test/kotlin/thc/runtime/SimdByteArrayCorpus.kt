// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import java.io.File
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.*

/** Independent host byte model; never invokes VectorMemory or guest operations. */
internal class SimdByteArrayCorpus(val family: String) {
    val floating = family in listOf("floatx4", "doublex2")
    val wide = family == "doublex2"
    val lanes = if (wide) 2 else 4
    val rowCount = when (family) { "floatx4" -> 6720; "doublex2" -> 4384; else -> 9666 }
    private val weights = listOf(3L, 5L, 7L, 11L).take(lanes)
    private val seeds = listOf(Long.MIN_VALUE, -4294967297, -2147483649, -2147483648, -2147483647,
        -1, 0, 1, 127, 128, 255, 256, 2147483646, 2147483647, 2147483648, 4294967295, 4294967296, Long.MAX_VALUE)
    private val edges = when (family) {
        "int32x4" -> listOf(-2147483648L, -2147483647, -65537, -1, 0, 1, 65537, 2147483646, 2147483647)
        "word32x4" -> listOf(0L, 1, 65535, 65536, 2147483647, 2147483648, 2147483649, 4294967294, 4294967295)
        "floatx4" -> listOf(-65536L, -257, -1, 0, 1, 255, 256, 65535)
        "doublex2" -> listOf(-(1L shl 40), -(1L shl 32)-1, -1, 0, 1, (1L shl 32)-1, (1L shl 40)-1, 1L shl 40)
        else -> error("Unknown SIMD memory family")
    }
    private val sentinels = when (family) {
        "int32x4" -> listOf(0x1234567L, -0x2345678, 0x3456789, -0x456789a)
        "word32x4" -> listOf(0x81234567L, 0x92345678, 0xa3456789, 0xb456789a)
        else -> listOf(-31L, 127, -1024, 4096).take(lanes)
    }
    private val rawBits = if (wide) listOf(0UL, 0x8000000000000000UL, 1UL, 0x8000000000000001UL,
        0x000fffffffffffffUL, 0x800fffffffffffffUL, 0x0010000000000000UL, 0x8010000000000000UL,
        0x3ff0000000000000UL, 0xbff0000000000000UL, 0x7fefffffffffffffUL, 0xffefffffffffffffUL,
        0x7ff0000000000000UL, 0xfff0000000000000UL, 0x7ff8123456789abcUL, 0xfff8abcdef012345UL).map { it.toLong() }
        else listOf(0L, 0x80000000, 1, 0x80000001, 0x007fffff, 0x807fffff, 0x00800000, 0x80800000,
            0x3f800000, 0xbf800000, 0x7f7fffff, 0xff7fffff, 0x7f800000, 0xff800000, 0x7fc12345, 0xffc54321)
    private val signalingBits = if (wide) listOf(0x7ff0000000000001UL, 0xfff0000000000001UL, 0x7ff123456789abcdUL, 0xfff543210fedcba9UL).map { it.toLong() }
        else listOf(0x7f800001L, 0xff800001, 0x7fa12345, 0xffa54321)
    private val offsetFamilies = listOf("vector", "scalar")
    private val operations = if (floating) listOf("Unit", "Index", "Read", "Write", "GraphIndex", "GraphStore")
        else listOf("Unit", "Index", "Read", "Write", "Store")
    private fun operation(name: String) = operations.singleOrNull { op -> offsetFamilies.any { name == "$it${op}Case" } }
        ?: throw IllegalArgumentException("Unknown SIMD memory entry")
    private fun scale(name: String) = if (name.startsWith("vector")) 16 else 16 / lanes
    private fun narrow(value: Long) = if (wide) value else if (family == "int32x4") value.toInt().toLong() else value and 0xffffffffL
    private fun score(values: List<Long>) = values.indices.sumOf { (if (floating) values[it] else narrow(values[it])) * weights[it] }
    private fun bits(value: Long) = if (wide) value.toDouble().toRawBits() else value.toFloat().toRawBits().toLong() and 0xffffffffL
    private fun initial() = ByteArray(64) { byte ->
        (((if (family == "int32x4") 0x10203040L else 0x89abcdefL) + (byte / 4) * 0x01030507L) ushr (8 * (byte % 4))).toByte()
    }
    private fun moved(original: ByteArray, address: Long, values: List<Long>): ByteArray {
        require(values.size == lanes && address in 0L..(original.size-16).toLong())
        return original.copyOf().also { bytes ->
            for (byte in 0..15) bytes[address.toInt()+byte] = (values[byte / (16 / lanes)] ushr (8 * (byte % (16 / lanes)))).toByte()
        }
    }
    private fun loaded(bytes: ByteArray, address: Long): List<Long> {
        require(address in 0L..(bytes.size-16).toLong())
        return (0 until lanes).map { lane -> narrow((0 until 16 / lanes).fold(0L) { value, byte ->
            value or ((bytes[address.toInt()+lane*(16 / lanes)+byte].toLong() and 255L) shl (8*byte))
        }) }
    }
    private fun patterns(offsetFamily: String, graph: Boolean = false, diagnostic: Boolean = false): List<List<Long>> {
        require(offsetFamily in offsetFamilies && !(graph && diagnostic))
        val values = if (!floating || graph) (0 until lanes).flatMap { lane -> edges.map { edge -> sentinels.toMutableList().also { it[lane] = edge } } }
            else (if (diagnostic) signalingBits else rawBits).let { domain -> domain.indices.map { index ->
                (0 until lanes).map { lane -> domain[(index + 5*lane) % domain.size] }
            } }
        return values.mapIndexed { index, row -> listOf((index % (48 / scale(offsetFamily) + 1)).toLong()) + row }
    }
    fun cases(): Map<String, List<List<Long>>> = linkedMapOf<String, List<List<Long>>>().also { result ->
        if (!floating) for (offsetFamily in offsetFamilies)
            result["${offsetFamily}UnitCase"] = (0..48 / scale(offsetFamily)).flatMap { offset -> seeds.map { listOf(offset.toLong(), it) } }
        for (offsetFamily in offsetFamilies) for (op in operations.filter { floating || it != "Unit" }) {
            val selectors = when (op) { "Unit" -> 2*lanes; "Index", "Read" -> if (floating) lanes else 0; "Write", "Store", "GraphStore" -> 64; else -> 0 }
            val domain = patterns(offsetFamily, op.startsWith("Graph"))
            result["$offsetFamily${op}Case"] = if (selectors == 0) domain else domain.flatMap { row -> (0 until selectors).map { row + it.toLong() } }
        }
    }
    fun expected(name: String, input: List<Long>): Long {
        val op = operation(name)
        val graph = op.startsWith("Graph")
        val arity = if (!floating && op == "Unit") 2 else lanes+1+if (op == "GraphIndex" || !floating && op in listOf("Index", "Read")) 0 else 1
        require(input.size == arity && input[0] in 0L..(48 / scale(name)).toLong())
        val address = input[0]*scale(name)
        if (!floating && op == "Unit") {
            val seed = input[1]
            val before = listOf(seed, seed+17, seed*3-29, seed xor 0x55aa55aaL).map(::narrow)
            val after = before.toMutableList()
            after[1] = narrow(if (name.startsWith("vector")) seed xor 0x80000000L else (before[1] and 0xffffffL) or (((seed+101) and 255L) shl 24))
            return score(before) + 15*score(after)
        }
        val values = input.subList(1, lanes+1)
        if (floating && !wide && !graph) require(values.all { it in 0..0xffffffffL })
        val encoded = if (graph) values.map(::bits) else values
        val storage = moved(initial(), address, encoded)
        if (op == "Unit") {
            require(input.last() in 0L until (2*lanes).toLong())
            return narrow(encoded[(input.last() % lanes).toInt()] xor if (input.last() == (lanes+1).toLong()) (if (wide) Long.MIN_VALUE else 0x80000000L) else 0L)
        }
        if (op in listOf("Index", "Read")) {
            if (!floating) return score(loaded(storage, address))
            require(input.last() in 0L until lanes.toLong())
            return loaded(storage, address)[input.last().toInt()]
        }
        if (op == "GraphIndex") return score(values)
        require(input.last() in 0L..63L)
        val selected = storage[input.last().toInt()].toLong() and 255L
        return if (wide && !graph) values[0] xor values[1] xor selected else score(values)*257+selected
    }
    fun checkedRows(text: String): Map<Pair<String, List<Long>>, Long> {
        val domain = cases().flatMap { (name, rows) -> rows.map { name to it } }
        val lines = text.lineSequence().toList().let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        require(lines.size == rowCount && domain.size == rowCount)
        return lines.mapIndexed { index, line ->
            val parts = line.split('\t'); val key = domain[index]
            val numbers = parts.drop(1).map { raw -> raw.toLong().also { require(raw == it.toString()) } }
            require(parts[0] == key.first && numbers.size == key.second.size+1 && numbers.dropLast(1) == key.second && numbers.last() == expected(key.first, key.second))
            key to numbers.last()
        }.toMap().also { require(it.size == rowCount) }
    }
    fun verify(root: File, provenance: Map<String, Any?>) {
        val directory = File(root, "build/simd-$family-bytearray")
        val expectedRows = checkedRows(File(directory, "expected.tsv").readText())
        if (provenance["nativeRows"] != null) assertEquals(expectedRows, checkedRows(File(directory, "oracle.tsv").readText()))
        val entries = provenance["entries"] as List<Map<String, Any?>>
        assertEquals(cases().keys.toList(), entries.map { it["name"] })
        for (entry in entries) {
            val rows = (entry["cases"] as List<List<Number>>).map { row -> row.map { it.toLong() } }
            assertEquals(cases().getValue(entry["name"] as String), rows)
            assertEquals(rows.first().size.toLong(), entry["arity"])
        }
        val graphs = provenance["graphEntries"] as List<Map<String, Any?>>
        assertEquals(4, graphs.size)
        assertEquals(offsetFamilies.flatMap { listOf("${it}Index${if (floating) "Graph" else "Worker"}", "${it}StoreGraph") }, graphs.map { it["name"] })
        for (entry in graphs) {
            val name = entry["name"] as String; val index = entry["operation"] == "index"
            val offsetFamily = if (name.startsWith("vector")) "vector" else "scalar"
            assertEquals(if (index) 2L else (lanes+3).toLong(), entry["arity"])
            assertEquals(scale(name).toLong(), entry["offsetUnitBytes"])
            assertEquals(if (name.endsWith("StoreGraph")) "store" else "index", entry["operation"])
            val domain = patterns(offsetFamily, floating)
            val graphCases = entry["cases"] as List<Map<String, Any?>>
            assertEquals(domain.size, graphCases.size)
            for ((position, row) in graphCases.withIndex()) {
                val input = domain[position]; val values = input.drop(1)
                val before = initial(); val after = moved(before, input[0]*scale(name), if (floating) values.map(::bits) else values)
                assertEquals(input[0], row["offset"]); assertEquals(values, row["lanes"])
                assertEquals(score(values), row["expectedScalar"])
                assertEquals(after.map { it.toLong() and 255L }, row["expectedBytes"])
                assertEquals((if (index) after else before).map { it.toLong() and 255L }, row["initialBytes"])
                val nativeEntry = "$offsetFamily${if (floating) "Graph" else ""}${if (index) "Index" else "Store"}Case"
                assertEquals(nativeEntry, row["nativeEntry"]); assertEquals(input, row["nativeArguments"])
                if (index) assertEquals(score(values), expectedRows.getValue(nativeEntry to input))
                else for (byte in 0..63) assertEquals(score(values)*257+(after[byte].toLong() and 255L), expectedRows.getValue(nativeEntry to (input + byte.toLong())))
            }
        }
    }
    fun controls(root: File) {
        val text = File(root, "build/simd-$family-bytearray/expected.tsv").readText()
        val rows = checkedRows(text); assertEquals(rowCount, rows.size)
        val lines = text.trimEnd('\n').lines()
        for (bad in listOf(lines.drop(1), lines + lines.first(), lines.reversed(), listOf(lines[1]) + lines.drop(1),
            listOf("unknown\t0\t0") + lines.drop(1), listOf(lines.first().substringBeforeLast('\t') + "\t999") + lines.drop(1), lines + ""))
            assertThrows(IllegalArgumentException::class.java) { checkedRows(bad.joinToString("\n", postfix = "\n")) }
        for (address in listOf(-1L, 49, 64, Long.MAX_VALUE, Long.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { loaded(ByteArray(64), address) }
            assertThrows(IllegalArgumentException::class.java) { moved(ByteArray(64), address, List(lanes) { 0L }) }
        }
        val original = initial(); val copy = moved(original, 4, sentinels); val snapshot = loaded(copy, 4)
        copy.fill(0, 4, 20); assertArrayEquals(initial(), original); assertEquals(sentinels.map(::narrow), snapshot)
        val operationsToReject = listOf("vectorIndexCase", "scalarReadCase", "vectorWriteCase")
        for (name in operationsToReject) {
            val good = cases().getValue(name).first()
            assertThrows(IllegalArgumentException::class.java) { expected(name, listOf(Long.MAX_VALUE) + good.drop(1)) }
            if (floating || name.endsWith("WriteCase")) assertThrows(IllegalArgumentException::class.java) {
                expected(name, good.dropLast(1) + if (name.endsWith("WriteCase")) 64L else lanes.toLong())
            }
        }
        assertThrows(IllegalArgumentException::class.java) { expected("unknown", emptyList()) }
        for (offsetFamily in offsetFamilies) {
            val domain = patterns(offsetFamily, floating)
            assertEquals(lanes*edges.size, domain.size)
            assertEquals((0L..(48 / scale(offsetFamily)).toLong()).toSet(), domain.map { it[0] }.toSet())
            for (lane in 0 until lanes) for (edge in edges) {
                val row = domain[lane*edges.size+edges.indexOf(edge)]
                assertEquals(edge, row[lane+1])
                for (other in 0 until lanes) if (other != lane) assertEquals(sentinels[other], row[other+1])
            }
            for (row in domain) if (floating) {
                val values = row.drop(1)
                val products = values.indices.map { values[it]*weights[it] }
                assertTrue(products.sumOf { abs(it) } < (1L shl if (wide) 44 else 24))
                assertEquals(score(values).toDouble(), if (wide) values.indices.sumOf { values[it].toDouble()*weights[it] }
                    else values.indices.fold(0f) { sum, lane -> sum+values[lane].toFloat()*weights[lane] }.toDouble())
                assertTrue(abs(score(values))*257+255 < Long.MAX_VALUE)
            }
            for ((name, inputs) in cases().filterKeys { it.startsWith(offsetFamily) }) {
                val op = operation(name)
                val selectors = when (op) { "Unit" -> if (floating) 2*lanes else 0; "Index", "Read" -> if (floating) lanes else 0
                    "Write", "Store", "GraphStore" -> 64; else -> 0 }
                if (selectors > 0) for (group in inputs.groupBy { it.dropLast(1) }.values)
                    assertEquals((0L until selectors.toLong()).toSet(), group.map { it.last() }.toSet())
            }
            if (floating) for (input in patterns(offsetFamily)) {
                val values = input.drop(1)
                for (selector in 0 until 2*lanes) assertEquals(
                    narrow(values[selector % lanes] xor if (selector == lanes+1) (if (wide) Long.MIN_VALUE else 0x80000000L) else 0L),
                    expected("${offsetFamily}UnitCase", input+selector.toLong()))
                for (lane in values.indices) for (op in listOf("Index", "Read"))
                    assertEquals(values[lane], expected("$offsetFamily${op}Case", input+lane.toLong()))
                val bytes = moved(initial(), input[0]*scale(offsetFamily), values)
                for (byte in 0..63) {
                    val answer = expected("${offsetFamily}WriteCase", input+byte.toLong())
                    assertEquals(bytes[byte].toLong() and 255L, if (wide) answer xor values[0] xor values[1] else answer-score(values)*257)
                }
            }
        }
        if (!floating) {
            val low = mutableSetOf<Long>(); val high = mutableSetOf<Long>()
            for (i in 0L..65535L) {
                val value = (((i*40503+97) and 65535L) shl 16) or i
                low.add(value and 65535); high.add(value ushr 16)
                assertEquals(narrow(value), narrow(value+(1L shl 32)))
                assertEquals(if (family == "int32x4") value.toInt().toLong() else value, narrow(value))
            }
            assertEquals(65536, low.size); assertEquals(65536, high.size)
        } else {
            fun integerEncoding(value: Long): Long {
                if (value == 0L) return 0
                val magnitude = abs(value); val power = 63-java.lang.Long.numberOfLeadingZeros(magnitude)
                val fraction = if (wide) 52 else 23; val bias = if (wide) 1023 else 127
                return (if (value < 0) (if (wide) Long.MIN_VALUE else 1L shl 31) else 0L) or
                    ((power+bias).toLong() shl fraction) or ((magnitude-(1L shl power)) shl (fraction-power))
            }
            for (value in -65536L..65536L) assertEquals(integerEncoding(value), bits(value))
            if (wide) for (bit in 0..40) for (delta in -1L..1L) for (sign in listOf(-1L, 1L)) {
                val value = ((1L shl bit)+delta)*sign
                if (abs(value) <= (1L shl 40)) assertEquals(integerEncoding(value), bits(value))
            }
            if (wide) for (bit in 0..63) for (raw in listOf(1L shl bit, (1L shl bit).inv())) for (lane in 0 until lanes) {
                val values = MutableList(lanes) { 0L }; values[lane] = raw
                for (offsetFamily in offsetFamilies) for (op in listOf("Index", "Read"))
                    assertEquals(raw, expected("$offsetFamily${op}Case", listOf(0L)+values+lane.toLong()))
            }
            val magnitudeMask = if (wide) Long.MAX_VALUE else 0x7fffffffL
            val exponent = if (wide) 0x7ff0000000000000L else 0x7f800000L
            val quiet = if (wide) 1L shl 51 else 1L shl 22
            val minNormal = if (wide) 1L shl 52 else 1L shl 23
            assertEquals(2, rawBits.count { it and magnitudeMask == 0L })
            assertEquals(4, rawBits.count { (it and magnitudeMask) in 1L until minNormal })
            assertEquals(2, rawBits.count { it and magnitudeMask == exponent })
            assertEquals(2, rawBits.count { (it and magnitudeMask) > exponent && it and quiet != 0L })
            assertTrue(signalingBits.all { it and exponent == exponent && it and (minNormal-1) != 0L && it and quiet == 0L })
            for (offsetFamily in offsetFamilies) for (lane in 0 until lanes)
                assertEquals(rawBits.toSet(), patterns(offsetFamily).map { it[lane+1] }.toSet())
            val diagnosticKeys = offsetFamilies.flatMap { offsetFamily ->
                listOf("Unit" to 2*lanes, "Index" to lanes, "Read" to lanes, "Write" to 64).flatMap { (op,count) ->
                    patterns(offsetFamily, diagnostic = true).flatMap { input -> (0 until count).map { "$offsetFamily${op}Case" to (input + it.toLong()) } }
                }
            }
            assertEquals(if (wide) 576 else 640, diagnosticKeys.size)
            assertTrue(diagnosticKeys.none { it in rows })
            for (key in diagnosticKeys) expected(key.first, key.second)
        }
    }
}
