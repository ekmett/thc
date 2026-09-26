// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import jdk.incubator.vector.*
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.EntryValue
import thc.Json
import thc.Language
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

class SimdArithmeticTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/simd-arithmetic")
    private fun json(file: File) = Json.parse(file.readText()) as Map<String, Any?>
    private data class Shape(val name: String, val primitive: String, val lanes: Int, val width: Int,
        val scalar: String, val pattern: Int) {
        val shuffle = primitive.startsWith("shuffle")
        val unsigned = scalar.startsWith("Word")
        val floating = scalar == "Float" || scalar == "Double"
    }
    private data class Row(val name: String, val a: Long, val b: Long, val result: Long)
    private fun evidence(): Pair<List<Shape>, List<Row>> {
        val manifest = json(File(directory, "manifest.json"))
        assertEquals("9.14.1", manifest["ghc"]); assertEquals("scalar-lane", manifest["nativeMode"])
        for ((path, expected) in (manifest["inputHashes"] as Map<String, String>) +
            (manifest["artifactHashes"] as Map<String, String>)) {
            val digest = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals(expected, digest, path)
        }
        val shapes = (manifest["entries"] as List<Map<String, Any?>>).map { record ->
            fun int(key: String) = (record.getValue(key) as Number).toInt()
            Shape(record["name"] as String, record["primitive"] as String, int("lanes"), int("width"),
                record["scalar"] as String, int("pattern"))
        }
        val expected = GeneratedVectors.operations.filter { it.startsWith("quot") || it.startsWith("rem") || it.startsWith("shuffle") }.toSet()
        assertEquals(78, expected.size); assertEquals(expected, shapes.map { it.primitive }.toSet())
        assertEquals(138, shapes.size)
        val rows = File(directory, "oracle.tsv").readLines().map { line ->
            val fields = line.split('\t'); assertEquals(4, fields.size)
            Row(fields[0], fields[1].toLong(), fields[2].toLong(), fields[3].toLong())
        }
        assertEquals((manifest["rows"] as Number).toInt(), rows.size)
        assertEquals(shapes.map { it.name }, rows.map { it.name }.distinct())
        return shapes to rows
    }
    private fun lane(raw: Long, shape: Shape): BigInteger {
        val modulus = BigInteger.ONE.shiftLeft(shape.width)
        val bits = BigInteger.valueOf(raw).mod(modulus)
        return if (!shape.unsigned && !shape.floating && bits.testBit(shape.width - 1)) bits - modulus else bits
    }
    /** Independent scalar BigInteger division and bit-lane selection, no Vector API. */
    private fun model(shape: Shape, a: Long, b: Long): Long {
        var checksum = 0L
        for (index in 0 until shape.lanes) {
            val value = if (shape.shuffle) {
                val selected = when (shape.pattern) {
                    0 -> shape.lanes - 1 - index + if (index % 2 == 1) shape.lanes else 0
                    1 -> index + 1
                    else -> if (index % 2 == 1) 2 * shape.lanes - 1 else 0
                }
                if (selected >= shape.lanes) lane(b - (selected - shape.lanes) * 7919L, shape)
                else lane(a + selected * 104729L, shape)
            } else {
                val x = lane(a + index * 104729L, shape)
                val y = lane(b - index * 7919L, shape)
                if (shape.primitive.startsWith("quot")) x / y else x % y
            }
            checksum += value.toLong() * (2L * index + 1)
        }
        return checksum
    }
    @Test fun nativeScalarOracleAgreesWithIndependentLaneModel() {
        val (shapes, rows) = evidence()
        val byName = shapes.associateBy { it.name }
        for (row in rows) assertEquals(row.result, model(byName.getValue(row.name), row.a, row.b), row.toString())
    }
    private fun targets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val result = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val body = target.rootNode
            val nodes = if (body is BytecodeRoot) listOf(body) + body.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(body)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val child = call.currentCallTarget as? RootCallTarget ?: continue
                if (child.rootNode is GuestRoot) visit(child)
            }
            result.add(target)
        }
        visit(entry)
        return result
    }
    private fun valid(target: RootCallTarget) = target.javaClass.getMethod("isValidLastTier").invoke(target) == true
    private fun compiled(program: ExecutableProgram) = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
    private fun language(action: (Context, Language) -> Unit) = Context.newBuilder("thc")
        .allowExperimentalOptions(true).option("compiler.Inlining", "false")
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.SingleTierCompilationThreshold", "10000000").option("engine.CompilationFailureAction", "Throw")
        .build().use { context ->
            context.initialize("thc"); context.enter()
            try { action(context, TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
            finally { context.leave() }
        }
    @Test fun originalCoreKeepsExactFirstInstalledEntriesOnBothBackends() {
        val (shapes, rows) = evidence()
        val module = json(File(directory, "pre-core/SimdArithmeticAudit.json"))
        val cases = rows.groupBy { it.name }
        for (backend in listOf("ast", "bytecode")) language { context, language ->
            for (shape in shapes) {
                val label = backend + "/" + shape.name
                val audit = json(File(directory, shape.name + "-audit.json"))
                assertEquals(true, audit["accepted"])
                assertEquals(emptyList<Any>(), audit["missingGlobals"]); assertEquals(emptyList<Any>(), audit["issues"])
                assertEquals(1, (audit["reachableBindings"] as List<*>).size, label)
                val input = CoreModules.reachable(module, shape.name) + ("instrument" to true)
                val program: ExecutableProgram = if (backend == "ast") Program(language, input) else BytecodeProgram(language, input)
                val function = context.asValue(EntryValue(program, shape.name, 2))
                fun check(row: Row) = assertEquals(row.result, function.execute(row.a, row.b).asLong(), "$backend/$row")
                cases.getValue(shape.name).forEach(::check)
                assertEquals(0L, compiled(program), "$label interpreted")
                val entry = program.entryTarget(shape.name)
                val host = program.hostEntryTarget(2)
                val active = targets(host)
                assertEquals(2, active.size, "$label exact target graph")
                assertTrue(entry in active)
                for (target in active.filter { it !== host }) {
                    target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                    assertTrue(valid(target), label)
                    val runtime = Truffle.getRuntime()
                    runtime.javaClass.getMethod("bypassedInstalledCode",
                        Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target)
                    assertTrue(valid(target), label)
                }
                assertTrue(function.invokeMember("compile").asBoolean(), label)
                for (row in cases.getValue(shape.name).asReversed()) {
                    val before = compiled(program); check(row)
                    assertEquals(before + 1, compiled(program), "$label first-installed/$row")
                    assertEquals(active, targets(host)); assertSame(entry, program.entryTarget(shape.name))
                    active.forEach { assertTrue(valid(it), "$label/$row") }
                    val state = language.handoffState.get()
                    assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
                    assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
                }
            }
        }
    }
    @Test fun literalShuffleIndicesAreCheckedBeforeExecution() {
        evidence()
        val module = json(File(directory, "pre-core/SimdArithmeticAudit.json"))
        fun nodes(value: Any?): List<MutableList<Any?>> = when (value) {
            is MutableList<*> -> listOf(value as MutableList<Any?>) + value.flatMap(::nodes)
            is Map<*, *> -> value.values.flatMap(::nodes)
            else -> emptyList()
        }
        for (backend in listOf("ast", "bytecode")) language { _, language ->
            for ((shape, lanes) in listOf("Int8X16" to 16, "Int8X64" to 64, "Word16X32" to 32)) {
                val primitive = "shuffle$shape#"
                val original = CoreModules.reachable(module, "shuffle${shape}Pattern0")
                for (bad in listOf(-1L, 2L * lanes, Long.MAX_VALUE, null)) {
                    val input = Json.parse(Json.stringify(original)) as Map<String, Any?>
                    val call = nodes(input).single { it.firstOrNull() == "app" &&
                        (it.getOrNull(1) as? List<*>)?.getOrNull(1) == primitive }
                    val tuple = (call[2] as List<*>)[2] as MutableList<Any?>
                    val fields = tuple[2] as MutableList<Any?>
                    val index = fields[0] as MutableList<Any?>
                    if (bad == null) fields[0] = mutableListOf("var", "not-a-literal", index.last())
                    else index[2] = bad.toString()
                    assertThrows(RuntimeFault::class.java, {
                        if (backend == "ast") Program(language, input) else BytecodeProgram(language, input)
                    }, "$backend/$shape/$bad")
                }
            }
        }
    }
    @Test fun divisionRejectsAZeroInAnyLaneAndChecksEveryUnsignedBit() {
        for (bits in listOf(128,256,512)) {
            val species = VectorShape.forBitSize(bits)
            val bytes = ByteVector.broadcast(ByteVector.SPECIES_128.withShape(species), (-1).toByte())
            val shorts = ShortVector.broadcast(ShortVector.SPECIES_128.withShape(species), (-1).toShort())
            val ints = IntVector.broadcast(IntVector.SPECIES_128.withShape(species), -1)
            val longs = LongVector.broadcast(LongVector.SPECIES_128.withShape(species), -1L)
            for (unsigned in listOf(false,true)) {
                assertThrows(ArithmeticException::class.java) { VectorIntegerDivision.quotByte(bytes, bytes.withLane(bytes.length()-1,0), unsigned) }
                assertThrows(ArithmeticException::class.java) { VectorIntegerDivision.remShort(shorts, shorts.withLane(shorts.length()-1,0), unsigned) }
                assertThrows(ArithmeticException::class.java) { VectorIntegerDivision.quotInt(ints, ints.withLane(ints.length()-1,0), unsigned) }
                assertThrows(ArithmeticException::class.java) { VectorIntegerDivision.remLong(longs, longs.withLane(longs.length()-1,0), unsigned) }
            }
            val quotient = VectorIntegerDivision.quotLong(longs, LongVector.broadcast(longs.species(),2L), true)
            val remainder = VectorIntegerDivision.remLong(longs, LongVector.broadcast(longs.species(),2L), true)
            for (lane in 0 until longs.length()) {
                assertEquals(Long.MAX_VALUE, quotient.lane(lane)); assertEquals(1L, remainder.lane(lane))
            }
        }
    }
}
