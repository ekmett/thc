// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Json
import java.io.File
import java.math.BigInteger

/** Independent JVM model: explicit machine arithmetic, no Vector API or runtime primops. */
internal class IntegerSimdModel(val family: String) {
    val signed = family.startsWith("int")
    val width = when (family) { "int8x16" -> 8; "int16x8", "word16x8" -> 16; "word32x4" -> 32; else -> error(family) }
    val lanes = 128 / width
    val mask = (1L shl width) - 1
    val operations = listOf("plusCase", "minusCase", "timesCase") + (if (signed) listOf("negateCase") else emptyList()) + listOf("packCase", "broadcastCase")
    val names = operations + listOf("laneCase", "scalarHelperCase", "tupleHelperCase")
    val weights = listOf(3L, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47, 53, 59).take(lanes)
    val edges: List<Long> = if (signed) {
        val half = 1L shl (width - 1); val middle = if (width == 8) 65L else 257L
        listOf(-half, -half + 1, -middle, -1, 0, 1, middle, half - 2, half - 1)
    } else { val half = 1L shl (width - 1); listOf(0L, 1, 2, half / 2 - 1, half - 1, half, half + 1, mask - 1, mask) }
    fun narrow(value: Long): Long {
        val bits = value and mask
        return if (signed) (bits xor (1L shl (width - 1))) - (1L shl (width - 1)) else bits
    }
    fun operands(a: Long, b: Long): Pair<List<Long>, List<Long>> {
        val half = 1L shl (width - 1)
        return listOf(a,b,a+1,b-1,a+half-1,b-half,a*3+7,b*5-11,a*7+29,b*9-31,a*11+37,b*13-41,a*15+43,b*17-47,a*19+53,b*21-59)
            .take(lanes).map(::narrow) to
            listOf(b+2,a-3,b*7+13,a*11-17,b+half,a-half+1,b*13+19,a*17-23,b*23+61,a*25-67,b*27+71,a*29-73,b*31+79,a*33-83,b*35+89,a*37-97)
                .take(lanes).map(::narrow)
    }
    fun resultLanes(name: String, a: Long, b: Long): List<Long> {
        require(name in operations)
        val (x,y) = operands(a,b)
        return x.indices.map { i -> narrow(when (name) {
            "plusCase" -> x[i]+y[i]; "minusCase" -> x[i]-y[i]; "timesCase" -> x[i]*y[i]
            "negateCase" -> -x[i]; "packCase" -> x[i]; else -> a-b+29
        }) }
    }
    fun expected(name: String, args: List<Long>): Long {
        require(name in names && args.size == if (name == "laneCase") 4 else 2)
        if (name == "laneCase") {
            require(args[0] in operations.indices.map(Int::toLong) && args[1] in 0L until lanes.toLong())
            return resultLanes(operations[args[0].toInt()], args[2], args[3])[args[1].toInt()]
        }
        val operation = when (name) { "scalarHelperCase" -> "plusCase"; "tupleHelperCase" -> "timesCase"; else -> name }
        return weights.zip(resultLanes(operation, args[0], args[1])).sumOf { (w,x) -> w*x } + if (name == "scalarHelperCase") 48 else 0
    }
    fun parse(text: String): Map<Pair<String,List<Long>>,Long> {
        val result = linkedMapOf<Pair<String,List<Long>>,Long>()
        for (line in if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split('\n')) {
            val fields = line.split('\t'); val name = fields[0]
            require(name in names && fields.size == if (name == "laneCase") 6 else 4) { "Unknown name or row arity" }
            val key = name to fields.drop(1).dropLast(1).map(String::toLong)
            require(result.put(key, fields.last().toLong()) == null) { "Duplicate native key" }
        }
        return result
    }
    fun checkedRows(text: String): Map<Pair<String,List<Long>>,Long> = parse(text).also { rows ->
        for ((key,value) in rows) assertEquals(expected(key.first,key.second),value,"$family/$key independent model")
    }
}

class IntegerSimdModelTest {
    private val families = listOf("int8x16","int16x8","word16x8","word32x4")
    private val root = File(System.getProperty("thc.projectRoot"))

    @Test fun originalCorporaAgreeWithIndependentModelAndCompleteLaneGrids() {
        val counts = mapOf("int8x16" to 9168, "int16x8" to 6032, "word16x8" to 5116, "word32x4" to 4882)
        for (family in families) {
            val model = IntegerSimdModel(family)
            val directory = File(root,"build/simd-$family")
            val rows = model.checkedRows(File(directory,"expected.tsv").readText())
            assertEquals(counts.getValue(family), rows.size)
            val provenance = Json.parse(File(directory,"provenance.json").readText()) as Map<*,*>
            if (provenance["nativeRows"] != null) assertEquals(rows, model.checkedRows(File(directory,"oracle.tsv").readText()))
            for (op in model.operations.indices) for (lane in 0 until model.lanes) {
                val selected = rows.keys.filter { it.first == "laneCase" && it.second.take(2) == listOf(op.toLong(),lane.toLong()) }
                assertEquals(81,selected.size)
                if (model.operations[op] == "broadcastCase") {
                    assertEquals(model.edges.toSet(),selected.map { rows.getValue(it) }.toSet())
                } else {
                    val observed = selected.map { (_,args) -> val (a,b) = model.operands(args[2],args[3]); a[lane] to b[lane] }.toSet()
                    assertEquals(model.edges.flatMap { a -> model.edges.map { b -> a to b } }.toSet(),observed)
                }
            }
            assertThrows(IllegalArgumentException::class.java) { model.parse(File(directory,"expected.tsv").readText() + File(directory,"expected.tsv").readLines().first() + "\n") }
            assertNotEquals(rows,model.parse(File(directory,"expected.tsv").readLines().drop(1).joinToString("\n")))
        }
    }
    @Test fun narrowingAndUnsignedProductsKeepEveryRetainedEncodingControl() {
        for (family in families) {
            val m = IntegerSimdModel(family)
            val samples = if (m.width == 32) (0L..65535).map { (it shl 16) or ((it*257 xor 0xa5a5) and 65535) }
                else (0L..m.mask).toList()
            assertEquals(samples.size,samples.toSet().size)
            if (m.width == 32) {
                assertEquals((0L..65535).toSet(), samples.map { it ushr 16 }.toSet())
                assertEquals((0L..65535).toSet(), samples.map { it and 65535 }.toSet())
            }
            for (bits in samples) {
                val expected = if (m.signed) if (bits > m.mask/2) bits-m.mask-1 else bits else bits
                assertEquals(expected,m.narrow(bits)); assertEquals(expected,m.narrow(bits+(1L shl 48)))
                if (!m.signed) for (right in m.edges) {
                    val exact = BigInteger.valueOf(bits).multiply(BigInteger.valueOf(right)).and(BigInteger.valueOf(m.mask)).toLong()
                    assertEquals(exact,m.narrow(bits*right))
                }
            }
            assertEquals(if (m.signed) -1L else m.mask,m.narrow(-1))
            assertEquals(0L,m.narrow(m.mask+1)); assertEquals(1L,m.narrow(m.mask*m.mask))
            assertEquals(0L,m.narrow(Long.MIN_VALUE)); assertEquals(if (m.signed) -1L else m.mask,m.narrow(Long.MAX_VALUE))
        }
        val byte = IntegerSimdModel("int8x16")
        for (a in -128L..127L) for (b in -128L..127L)
            assertEquals((((a*b) and 255) xor 128)-128,byte.resultLanes("timesCase",a,b-2)[0])
    }
    @Test fun laneOrderModuloInputsAndMalformedRowsRemainObservable() {
        for (family in families) {
            val m = IntegerSimdModel(family)
            for (name in m.operations) {
                assertEquals(m.expected(name,listOf(123,-456)),m.expected(name,listOf(123+(1L shl 48),-456-(1L shl 32))))
                if (name == "broadcastCase") continue
                val values = m.resultLanes(name,12345,-6789)
                assertTrue(values.toSet().size > 1)
                val score = m.weights.zip(values).sumOf { (a,b) -> a*b }
                for (i in values.indices) for (j in 0 until i) if (values[i] != values[j]) {
                    val swapped = values.toMutableList().also { it[i] = values[j]; it[j] = values[i] }
                    assertNotEquals(score,m.weights.zip(swapped).sumOf { (a,b) -> a*b })
                }
            }
            for (bad in listOf("unknown\t0\t0\n","plusCase\t0\t0\n","plusCase\t9223372036854775808\t0\t0\n","plusCase\t0\t0\t0\n\n"))
                assertThrows(IllegalArgumentException::class.java) { m.parse(bad) }
            for (args in listOf(listOf(-1L,0,0,0),listOf(m.operations.size.toLong(),0,0,0),listOf(0L,-1,0,0),listOf(0L,m.lanes.toLong(),0,0)))
                assertThrows(IllegalArgumentException::class.java) { m.expected("laneCase",args) }
        }
    }
}
