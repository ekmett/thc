// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.UnexpectedResultException
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class FloatingAddressTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private data class Row(val floatBits: Long, val doubleBits: Long, val actual: List<Long>)
    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build()

    @Test fun wrongTypedSpecializationPreservesActualWidthAndTupleEffects() {
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), FrameDescriptor.newBuilder().build())
        val base = ManagedAddress.fromAllocation(PinnedMemory.allocate(32, 1))
        FloatingAddresses.writeFloat(base, 2, -0.0f)
        FloatingAddresses.writeDouble(base, 2, -0.0)
        fun operand(value: Any): Expr = object : Expr() {
            override fun execute(frame: VirtualFrame): Any = value
        }
        fun node(op: FloatingAddressOp, vararg operands: Expr) =
            FloatingAddressExpression(op, CoreRepresentation.UNKNOWN, arrayOf(*operands))
        val floatIndex = node(FloatingAddressOp.INDEX_FLOAT, operand(base), operand(2L))
        val floatMiss = assertThrows(UnexpectedResultException::class.java) { floatIndex.executeDouble(frame) }
        assertTrue(floatMiss.result is Float)
        assertEquals(0x80000000L, (java.lang.Float.floatToRawIntBits(floatMiss.result as Float).toLong() and 0xffffffffL))
        val doubleIndex = node(FloatingAddressOp.INDEX_DOUBLE, operand(base), operand(2L))
        val doubleMiss = assertThrows(UnexpectedResultException::class.java) { doubleIndex.executeFloat(frame) }
        assertTrue(doubleMiss.result is Double)
        assertEquals(Long.MIN_VALUE, java.lang.Double.doubleToRawLongBits(doubleMiss.result as Double))
        var stateEvaluations = 0
        val state = object : Expr() {
            override fun execute(frame: VirtualFrame): Any { stateEvaluations++; return Unit }
        }
        val read = node(FloatingAddressOp.READ_FLOAT, operand(base), operand(2L), state)
        assertThrows(RuntimeFault::class.java) { read.execute(frame) }
        assertThrows(RuntimeFault::class.java) { read.executeFloat(frame) }
        assertEquals(0, stateEvaluations)
        assertThrows(RuntimeFault::class.java) { floatIndex.executeTuple(frame, intArrayOf(0), 0) }
        val write = node(FloatingAddressOp.WRITE_FLOAT, operand(base), operand(2L), operand(1.0f), state)
        assertThrows(RuntimeFault::class.java) { write.executeDouble(frame) }
        assertEquals(0, stateEvaluations)
        assertEquals(0x80000000L, (java.lang.Float.floatToRawIntBits(FloatingAddresses.readFloat(base, 2)).toLong() and 0xffffffffL))
    }

    @Test fun nativeEndianFloatingStoragePreservesBitsAndChecksWholeElement() {
        val base = ManagedAddress.fromAllocation(PinnedMemory.allocate(64, 1))
        val target = base.plus(56)
        base.writeAddressElementIndex(1, target)
        val floatBits = 0x7fc01234L
        val doubleBits = 0x7ff8000000001234L
        FloatingAddresses.writeFloat(base, 4, java.lang.Float.intBitsToFloat((floatBits).toInt()))
        FloatingAddresses.writeDouble(base, 3, java.lang.Double.longBitsToDouble(doubleBits))
        assertEquals(floatBits, (java.lang.Float.floatToRawIntBits(FloatingAddresses.readFloat(base.plus(20), -1)).toLong() and 0xffffffffL))
        assertEquals(doubleBits, java.lang.Double.doubleToRawLongBits(FloatingAddresses.readDouble(base.plus(32), -1)))
        val expected = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder())
            .putInt(16, floatBits.toInt()).putLong(24, doubleBits).array()
        for (index in (16..19).plus(24..31))
            assertEquals(expected[index].toLong() and 255, base.readWord8(index.toLong()))
        assertSame(target, base.readAddressElementIndex(1))
        for (index in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertThrows(RuntimeFault::class.java) { FloatingAddresses.writeFloat(base, index, 1f) }
            assertThrows(RuntimeFault::class.java) { FloatingAddresses.readDouble(base, index) }
        }
        assertThrows(RuntimeFault::class.java) { FloatingAddresses.writeFloat(base.plus(9), 0, 1f) }
        assertThrows(RuntimeFault::class.java) { FloatingAddresses.readDouble(base, 1) }
        assertSame(target, base.readAddressElementIndex(1))
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun originalGhcFloatingAddrCompositeMatchesNativeInBothBackends() {
        val manifest = Json.parse(File(root, "build/floating-address/manifest.json").readText()) as Map<String, Any?>
        assertEquals(1L, manifest["schema"])
        assertEquals("9.14.1", manifest["ghc"])
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in manifest[kind] as Map<String, String>) {
                val digest = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, digest, "Stale floating Addr " + kind + ": " + path)
            }
        val rows = File(root, "build/floating-address/oracle.tsv").readLines().map { line ->
            val fields = line.split('\t')
            assertEquals(6, fields.size)
            Row(fields[0].toLong(), fields[1].toULong().toLong(),
                fields.drop(2).map { it.toULong().toLong() })
        }
        assertEquals(10, rows.size)
        for (row in rows) {
            val model = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder())
                .putInt(16, row.floatBits.toInt()).putLong(24, row.doubleBits)
            assertEquals(listOf(model.getInt(16).toLong() and 0xffffffffL,
                model.getInt(16).toLong() and 0xffffffffL, model.getLong(24), model.getLong(24)),
                row.actual)
        }
        for (stage in listOf("pre", "post")) {
            val directory = File(root, "build/floating-address/" + stage)
            val audit = Json.parse(File(directory, "audit.json").readText()) as Map<String, Any?>
            assertEquals(true, audit["accepted"])
            assertEquals(emptyList<Any>(), audit["missingGlobals"])
            val required = setOf("indexFloatOffAddr#", "readFloatOffAddr#", "writeFloatOffAddr#",
                "indexDoubleOffAddr#", "readDoubleOffAddr#", "writeDoubleOffAddr#")
            val primitives = audit["primitives"] as List<Map<String, Any?>>
            for (primitive in required) {
                val evidence = primitives.single { it["name"] == primitive }
                val owners = (evidence["uses"] as List<Map<String, Any?>>).map { it["owner"] }.toSet()
                assertEquals(setOf("main:FloatingAddressAudit.floatingAddressBits"), owners,
                    stage + "/" + primitive)
            }
            val module = Json.parse(File(directory, "core/FloatingAddressAudit.json").readText()) as Map<String, Any?>
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val entry = "floatingAddressBits"
                    val source = CoreModules.reachable(module, entry) + ("instrument" to true)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, source)
                        else BytecodeProgram(language, source)
                    val function = context.asValue(EntryValue(program, entry, 3))
                    fun check(row: Row, selectors: IntProgression) {
                        for (selector in selectors)
                            assertEquals(row.actual[selector],
                                function.execute(row.floatBits, row.doubleBits, selector).asLong(),
                                stage + "/" + backend + "/" + selector)
                        assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                    }
                    rows.forEach { check(it, 0..3) }
                    assertTrue(function.invokeMember("compile").asBoolean(), stage + "/" + backend)
                    assertEquals(true, program.hostEntryTarget(3).javaClass
                        .getMethod("isValidLastTier").invoke(program.hostEntryTarget(3)))
                    for (row in rows.asReversed()) {
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        check(row, 3 downTo 0)
                        var after = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        if (after == before) {
                            assertTrue(function.invokeMember("compile").asBoolean())
                            check(row, 3 downTo 0)
                            after = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        }
                        assertTrue(after > before, stage + "/" + backend + ": " + before + "->" + after)
                    }
                } finally { context.leave() }
            }
        }
    }
}
