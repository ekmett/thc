// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.math.BigInteger
import java.nio.ByteOrder
import java.security.MessageDigest

class IntArrayNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val names = listOf("unboxedAccum", "unboxedST", "unboxedEmpty", "orderedInts", "aliasIntBytes")
    private val operations = listOf(ByteArrayOp.READ_INT, ByteArrayOp.WRITE_INT, ByteArrayOp.INDEX_INT)
    private fun manifest() = Json.parse(File(root, "build/int-arrays/manifest.json").readText()) as Map<String, Any?>
    private fun merged(paths: List<String>) = CoreModules.merge(paths.map { Json.parse(File(root, it).readText()) as Map<String, Any?> })
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("isValidLastTier").invoke(target), label)
    private val inputs = buildSet {
        addAll(-16L..16L)
        addAll(listOf(Long.MIN_VALUE, Long.MIN_VALUE+1, Long.MAX_VALUE-1, Long.MAX_VALUE))
        for (bit in 0..63) for (delta in -1L..1L) for (sign in listOf(-1L, 1L))
            add(sign*((1L shl bit)+delta))
        for (bits in listOf("5555555555555555", "aaaaaaaaaaaaaaaa", "55aa55aa55aa55aa",
                "aa55aa55aa55aa55", "0123456789abcdef", "fedcba9876543210")) add(bits.toULong(16).toLong())
    }.sorted()
    private data class Row(val name: String, val input: Long, val answer: Long)
    private fun expectedRows(order: ByteOrder) = names.flatMap { name -> inputs.map { Row(name, it, mathematical(name, it, order)) } }
    private fun checkedRows(text: String, order: ByteOrder = ByteOrder.nativeOrder()): List<Row> {
        val lines = text.lineSequence().toList().let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        val rows = lines.map { line ->
            val fields = line.split('\t')
            require(fields.size == 3) { "Int-array oracle requires name/input/result" }
            Row(fields[0], fields[1].toLong(), fields[2].toLong())
        }
        require(rows == expectedRows(order)) { "Int-array native/model mismatch or incomplete/reordered corpus" }
        return rows
    }
    private val exactCounts = mapOf(
        "orderedInts" to mapOf("newByteArray#" to 2, "readIntArray#" to 2, "writeIntArray#" to 5,
            "unsafeFreezeByteArray#" to 2, "indexIntArray#" to 4),
        "aliasIntBytes" to mapOf("newByteArray#" to 1, "writeIntArray#" to 2, "writeWord8Array#" to 2,
            "readIntArray#" to 2, "unsafeFreezeByteArray#" to 1, "indexIntArray#" to 2, "indexWord8Array#" to 4))
    private fun required(name: String): Set<String> = when (name) {
        "unboxedEmpty" -> emptySet()
        else -> setOf("newByteArray#", "unsafeFreezeByteArray#", "readIntArray#", "writeIntArray#", "indexIntArray#") +
            when (name) { "orderedInts" -> setOf("sizeofByteArray#"); "aliasIntBytes" -> setOf("writeWord8Array#", "indexWord8Array#"); else -> emptySet() }
    }
    private fun checkedCore(name: String, evidence: ArrayCoreEvidence): Map<String, Int> {
        require(name in names) { "Unknown Int-array root: $name" }
        val counts = evidence.primitiveCounts
        require(counts.keys.containsAll(required(name))) { "Missing Int-array primitive: $name" }
        require(exactCounts[name].orEmpty().all { (primitive, count) -> counts[primitive] == count }) { "Int-array use count changed: $name" }
        return counts
    }

    // A list/byte model independent of the native oracle and managed VarHandle.
    private fun mathematical(name: String, seed: Long, order: ByteOrder = ByteOrder.nativeOrder()): Long {
        require(name in names) { "Unknown Int-array entry: $name" }
        val x = BigInteger.valueOf(seed)
        fun n(value: Long) = BigInteger.valueOf(value)
        if (name == "unboxedEmpty") return (x + n(7)).toLong()
        if (name == "unboxedAccum" || name == "unboxedST") {
            val a = MutableList(8) { x }
            if (name == "unboxedAccum") {
                for ((key, value) in listOf(-3 to n(3), 0 to n(5), -3 to n(-2), 4 to x))
                    a[key + 3] = a[key + 3] + value
            } else {
                val before = a[0]
                a[3] = before + n(7)
                val after = a[3]
                a[7] = n(3) * after - x
            }
            return (a[0] * n(7) + a[3] * n(11) + a[7] * n(13)).toLong()
        }
        val pattern = BigInteger("55aa55aa55aa55aa", 16)
        if (name == "orderedInts") {
            val a = mutableListOf(x, x + n(1), x.xor(pattern))
            val b = listOf(x + n(71))
            val before = a[1]
            a[1] = x + n(17)
            val after = a[1]
            return (before*n(3) + after*n(5) + a[0]*n(7) + a[1]*n(11) +
                a[2]*n(13) + b[0]*n(17) + n(32)).toLong()
        }
        check(name == "aliasIntBytes")
        val little = order == ByteOrder.LITTLE_ENDIAN
        val modulus = BigInteger.ONE.shiftLeft(64)
        val bytes = listOf(x, x.xor(pattern)).flatMap { value ->
            (0..7).map { byte -> value.mod(modulus).shiftRight((if (little) byte else 7-byte)*8).and(n(255)) }
        }.toMutableList()
        bytes[7] = (x+n(101)).mod(n(256))
        bytes[8] = (x+n(37)).mod(n(256))
        fun cell(index: Int): BigInteger {
            var value = BigInteger.ZERO
            for (byte in 0..7) value = value.or(bytes[index*8+byte].shiftLeft((if (little) byte else 7-byte)*8))
            return if (value.testBit(63)) value-modulus else value
        }
        val a = cell(0); val b = cell(1)
        return (a*n(3) + b*n(5) + a*n(7) + b*n(11) + bytes[0]*n(17) +
            bytes[7]*n(19) + bytes[8]*n(23) + bytes[15]*n(29)).toLong()
    }

    @Test fun independentModelsCoverFullWidthUpdatesAndBothByteOrders() {
        assertEquals(393, inputs.size)
        assertEquals(inputs.sorted().distinct(), inputs)
        for (bit in 0..63) for (delta in -1L..1L) for (sign in listOf(-1L, 1L))
            assertTrue(sign*((1L shl bit)+delta) in inputs)
        for (x in inputs) {
            assertEquals(44*x+62, mathematical("unboxedAccum", x))
            assertEquals(44*x+350, mathematical("unboxedST", x))
            assertEquals(x+7, mathematical("unboxedEmpty", x))
            assertEquals(3*(x+1)+16*(x+17)+7*x+13*(x xor 0x55aa55aa55aa55aaL)+17*(x+71)+32,
                mathematical("orderedInts", x))
            for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
                val little = order == ByteOrder.LITTLE_ENDIAN
                val shiftA = if (little) 56 else 0; val shiftB = if (little) 0 else 56
                val a = (x and (255L shl shiftA).inv()) or (((x+101) and 255) shl shiftA)
                val b = ((x xor 0x55aa55aa55aa55aaL) and (255L shl shiftB).inv()) or (((x+37) and 255) shl shiftB)
                val first = if (little) a and 255 else a ushr 56
                val last = if (little) b ushr 56 else b and 255
                assertEquals(10*a+16*b+17*first+19*((x+101) and 255)+23*((x+37) and 255)+29*last,
                    mathematical("aliasIntBytes", x, order), "$x/$order")
            }
        }
        assertNotEquals(mathematical("aliasIntBytes", 0, ByteOrder.LITTLE_ENDIAN), mathematical("aliasIntBytes", 0, ByteOrder.BIG_ENDIAN))
        assertThrows(IllegalArgumentException::class.java) { mathematical("unknown", 0) }
    }

    @Test fun exactCorpusRejectsMissingDuplicateReorderedMalformedAndWrongRows() {
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val rows = expectedRows(order)
            fun text(rows: List<Row>) = rows.joinToString("\n", postfix="\n") { "${it.name}\t${it.input}\t${it.answer}" }
            val valid = text(rows); val first = rows.first()
            assertEquals(1965, rows.size); assertEquals(rows, checkedRows(valid, order))
            val bad = listOf("", text(rows.drop(1)), text(rows+first), text(rows.reversed()),
                text(rows.toMutableList().apply { this[1] = first }),
                text(listOf(first.copy(name="unknown"))+rows.drop(1)),
                text(listOf(first.copy(input=first.input+1))+rows.drop(1)),
                text(listOf(first.copy(answer=first.answer+1))+rows.drop(1)),
                valid.replaceFirst("\t", " "), valid.replaceFirst("\t", "\textra\t"),
                "unboxedAccum\t9223372036854775808\t0\n", "unboxedAccum\t0\tnot-an-int\n", valid+"\n")
            for ((index, corrupt) in bad.withIndex())
                assertThrows(IllegalArgumentException::class.java, { checkedRows(corrupt, order) }, "$order/mutation$index")
        }
    }

    @Test fun exportedCoreRequiresAllPrimitiveNamesAndExactUseCounts() {
        val source = merged(paths())
        for (name in names) {
            checkedCore(name, ArrayCoreEvidence(source, name))
            for (primitive in required(name)) for (all in listOf(false, true)) {
                if (!all && (exactCounts[name]?.get(primitive) ?: 0) < 2) continue
                val module = Json.parse(Json.stringify(source)) as Map<String, Any?>
                val evidence = ArrayCoreEvidence(module, name)
                val uses = evidence.bindings.flatMap { evidence.nodes(it["expr"]) }
                    .filter { it.take(2) == listOf("prim", primitive) }
                assertTrue(uses.isNotEmpty())
                for (node in if (all) uses else uses.take(1)) (node as MutableList<Any?>)[1] = "missingArrayPrimitive#"
                assertThrows(IllegalArgumentException::class.java, { checkedCore(name, ArrayCoreEvidence(module, name)) },
                    "$name/$primitive/all=$all")
            }
        }
    }

    @Test fun genuinePublicArraysAndPrimitiveAliasesMatchNativeAndIndependentModel() {
        val manifest = manifest()
        assertEquals(names, manifest["entries"])
        assertEquals(inputs, (manifest["inputs"] as List<Number>).map { it.toLong() })
        assertEquals(names.size*inputs.size, (manifest["nativeRows"] as Number).toInt())
        assertEquals(if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) "little" else "big", manifest["byteOrder"])
        assertEquals(64, (manifest["wordBits"] as Number).toInt())
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<String, String>) {
            val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Stale Int-array fixture: $path; regenerate the Int-array fixtures")
        }
        val rows = checkedRows(File(root, "build/int-arrays/oracle.tsv").readText()).groupBy { it.name }
        assertEquals(names.toSet(), rows.keys)
        assertEquals((manifest["nativeRows"] as Number).toInt(), rows.values.sumOf { it.size })
        val stages = manifest["stages"] as Map<String, List<String>>
        assertEquals(setOf("pre", "post"), stages.keys)
        for ((stage, paths) in stages) {
            val module = merged(paths)
            for (name in names) {
                checkedCore(name, ArrayCoreEvidence(module, name))
                val cases = rows.getValue(name).map { it.input to it.answer }
                assertEquals(cases.size, cases.map { it.first }.toSet().size)
                assertEquals((manifest["inputs"] as List<Number>).map { it.toLong() }.toSet(), cases.map { it.first }.toSet())
                cases.forEach { (input, native) -> assertEquals(mathematical(name, input), native, "Native $name($input)") }
                for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val label = "$stage/$backend/$name"
                        val program = program(language, CoreModules.reachable(module, name) + ("instrument" to true), backend)
                        val host = program.hostEntryTarget(1)
                        val function = context.asValue(EntryValue(program, name, 1))
                        fun check(row: Pair<Long, Long>) = assertEquals(row.second, function.execute(row.first).asLong(), "$label(${row.first})")
                        fun compiled() = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        cases.forEach(::check)
                        assertTrue(function.invokeMember("compile").asBoolean(), "$label installation")
                        val original = program.entryTarget(name)
                        val active = NodeUtil.findAllNodeInstances(host.rootNode, DirectCallNode::class.java)
                            .filter { it.callTarget === original }.map { it.currentCallTarget as RootCallTarget }
                            .ifEmpty { listOf(original) }
                        for (row in cases.asReversed()) {
                            val before = compiled()
                            check(row)
                            assertTrue(compiled() > before, "$label(${row.first}) must enter installed guest code")
                            valid(host, "$label host remains installed")
                            active.forEach { valid(it, "$label active target remains installed") }
                        }
                        for (counter in listOf("unsupportedTraps", "blackholes"))
                            assertEquals(0L, (program.diagnostics().getValue(counter) as Number).toLong(), "$label/$counter")
                        assertEquals(0, language.handoffState.get().results.depth, "$label releases tuple results")
                        if (name in listOf("orderedInts", "aliasIntBytes"))
                            assertEquals(0L, language.handoffState.get().results.allocations, "$label direct tuple destinations")
                    } finally { context.leave() }
                }
            }
        }
    }

    private fun applications(value: Any?): List<MutableList<Any?>> = when (value) {
        is List<*> -> (if (value.firstOrNull() == "app") listOf(value as MutableList<Any?>) else emptyList()) + value.flatMap(::applications)
        is Map<*, *> -> value.values.flatMap(::applications)
        else -> emptyList()
    }
    private fun paths() = (manifest()["stages"] as Map<String, List<String>>).getValue("pre")

    @Test fun exactIntStateShapesSaturationAndLevityAreRequiredInBothLoadModes() {
        val paths = paths()
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (operation in operations) for (mutation in 0..9) for (diagnostic in listOf(false, true)) {
                    val module = CoreModules.reachable(merged(paths), "orderedInts")
                    val app = applications(module).first { (it[1] as List<*>).take(2) == listOf("prim", operation.primitive) }
                    val args = app[2] as MutableList<Any?>
                    val flags = app[3] as MutableList<Any?>
                    val metadata = CoreRepresentations.metadata(app) as MutableMap<String, Any?>
                    when (mutation) {
                        0 -> { args.removeAt(args.lastIndex); flags.removeAt(flags.lastIndex); metadata.remove("callDemand") }
                        1 -> { args.add(args[0]); flags.add(false); metadata.remove("callDemand") }
                        2 -> metadata.remove("rep")
                        3 -> metadata["rep"] = mapOf("kind" to "long", "primReps" to listOf("WordRep"), "evaluated" to true)
                        4 -> flags[0] = true
                        5 -> (CoreRepresentations.metadata(args[0] as List<Any?>)!!["rep"] as MutableMap<String, Any?>)["kind"] = "unknown"
                        6 -> (CoreRepresentations.metadata(args[1] as List<Any?>)!!["rep"] as MutableMap<String, Any?>)["primReps"] = listOf("Int64Rep")
                        7 -> (CoreRepresentations.metadata(args[0] as List<Any?>)!!["rep"] as MutableMap<String, Any?>)["primReps"] = listOf("BoxedRep (Just Lifted)")
                        8 -> if (operation == ByteArrayOp.READ_INT) {
                            val proof = metadata["rep"] as MutableMap<String, Any?>
                            (proof["components"] as MutableList<Any?>)[0] = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple",
                                "components" to emptyList<Any?>(), "primReps" to emptyList<String>(), "evaluated" to true)
                        } else metadata["rep"] = mapOf("kind" to "unknown", "primReps" to emptyList<String>(), "evaluated" to true)
                        9 -> if (operation == ByteArrayOp.READ_INT) (metadata["rep"] as MutableMap<String, Any?>)["kind"] = "long"
                            else flags[1] = true
                    }
                    val sameCarrier = mutation == 6 || (mutation == 3 && operation == ByteArrayOp.INDEX_INT)
                    if (sameCarrier) assertDoesNotThrow({
                        program(language, module + ("diagnosticUnsupported" to diagnostic), backend)
                    }, "$backend/${operation.primitive}/mutation$mutation/$diagnostic")
                    else assertThrows(RuntimeFault::class.java, {
                        program(language, module + ("diagnosticUnsupported" to diagnostic), backend)
                    }, "$backend/${operation.primitive}/mutation$mutation/$diagnostic")
                }
                for (operation in operations) {
                    val module = CoreModules.reachable(merged(paths), "orderedInts")
                    val app = applications(module).first { (it[1] as List<*>).take(2) == listOf("prim", operation.primitive) }
                    val primitive = (app[1] as List<*>).toList(); app.clear(); app.addAll(primitive)
                    assertThrows(UnsupportedCore::class.java) { program(language, module, backend) }
                }
            } finally { context.leave() }
        }
    }

    @Test fun invalidElementIndicesFailOnBothBackendsWithoutComparingNativeUndefinedAccesses() {
        val paths = paths()
        for (backend in listOf("ast", "bytecode")) executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (operation in operations) for (index in listOf(Long.MIN_VALUE, -1L, 3L, 1L shl 32, 1L shl 61, Long.MAX_VALUE)) {
                    val module = CoreModules.reachable(merged(paths), "orderedInts")
                    val app = applications(module).first { (it[1] as List<*>).take(2) == listOf("prim", operation.primitive) }
                    val args = app[2] as MutableList<Any?>
                    args[1] = listOf("lit", "int", index.toString(), CoreRepresentations.metadata(args[1] as List<Any?>))
                    val program = program(language, module, backend)
                    val function = context.asValue(EntryValue(program, "orderedInts", 1))
                    val failure = assertThrows(PolyglotException::class.java) { function.execute(5L) }
                    val guard = if (index < 0 || index > Long.MAX_VALUE / 8) "element" else "range"
                    assertEquals("${RuntimeFault::class.java.name}: Managed allocation $guard outside its backing storage",
                        failure.message, "$backend/$operation/$index")
                    assertEquals(0, language.handoffState.get().results.depth)
                    assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                }
                val good = context.asValue(EntryValue(program(language,
                    CoreModules.reachable(merged(paths), "orderedInts"), backend), "orderedInts", 1))
                assertEquals(mathematical("orderedInts", 5), good.execute(5L).asLong())
            } finally { context.leave() }
        }
    }
}
