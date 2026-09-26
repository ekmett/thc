// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import jdk.incubator.vector.ByteVector
import jdk.incubator.vector.ShortVector
import jdk.incubator.vector.IntVector
import jdk.incubator.vector.LongVector
import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.DoubleVector
import jdk.incubator.vector.VectorOperators

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

class SimdFamiliesTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/simd-families")
    private fun context(inlining: Boolean) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target, "installed")
    }
    private fun activeTargets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val result = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val body = target.rootNode
            val nodes = if (body is BytecodeRoot) listOf(body) + body.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(body)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val active = call.currentCallTarget as? RootCallTarget ?: continue
                if (active.rootNode is GuestRoot) visit(active)
            }
            result.add(target)
        }
        visit(entry)
        return result
    }
    private fun count(p: ExecutableProgram) = (p.diagnostics().getValue("compiledEntries") as Number).toLong()
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
    }

    @Test fun rawVectorBoundariesRejectWrongSpeciesAndElementTypes() {
        val shorts = ShortVector.broadcast(ShortVector.SPECIES_128, 0.toShort()).withLane(1, 1).withLane(2, -1).withLane(3, Short.MIN_VALUE).withLane(4, Short.MAX_VALUE).withLane(5, 5).withLane(6, 6).withLane(7, 7)
        assertEquals(ShortVector.SPECIES_128, shorts.species())
        assertEquals(listOf(0, 1, -1, -32768, 32767, 5, 6, 7), (0 until 8).map { shorts.lane(it).toInt() })
        assertEquals(shorts, CoreVectors.requireShort(shorts, ShortVector.SPECIES_128))
        assertThrows(RuntimeFault::class.java) { CoreVectors.requireShort(shorts, ShortVector.SPECIES_256) }
        assertThrows(RuntimeFault::class.java) { CoreVectors.requireInt(shorts, IntVector.SPECIES_128) }
        for (wrong in listOf(null, 1L, shortArrayOf(1, 2)))
            assertThrows(RuntimeFault::class.java) { CoreVectors.requireShort(wrong, ShortVector.SPECIES_128) }
        assertThrows(RuntimeFault::class.java) { CoreVectors.requireByte(ByteVector.zero(ByteVector.SPECIES_128), ByteVector.SPECIES_256) }
        assertThrows(RuntimeFault::class.java) { CoreVectors.requireInt(IntVector.zero(IntVector.SPECIES_128), IntVector.SPECIES_256) }
        assertThrows(RuntimeFault::class.java) { CoreVectors.requireLong(LongVector.zero(LongVector.SPECIES_128), LongVector.SPECIES_256) }
        assertThrows(RuntimeFault::class.java) { CoreVectors.requireFloat(FloatVector.zero(FloatVector.SPECIES_128), FloatVector.SPECIES_256) }
        assertThrows(RuntimeFault::class.java) { CoreVectors.requireDouble(DoubleVector.zero(DoubleVector.SPECIES_128), DoubleVector.SPECIES_256) }
        for (index in listOf(-1L, 8L, Long.MIN_VALUE, Long.MAX_VALUE))
            assertThrows(RuntimeFault::class.java) { CoreVectors.laneIndex(index, 8) }
        for (index in 0L..7L) assertEquals(index.toInt(), CoreVectors.laneIndex(index, 8))
    }

    @Test fun exactLaneSignWidthLogicalTupleAndCallingProofsRemainRequired() {
        assertEquals(237, GeneratedVectors.operations.size)
        for ((name, tuple, vector) in listOf(
            Triple("Int8X32", GeneratedVectors.unpackedInt8X32, GeneratedVectors.proofInt8X32),
            Triple("Word8X32", GeneratedVectors.unpackedWord8X32, GeneratedVectors.proofWord8X32),
            Triple("Int8X64", GeneratedVectors.unpackedInt8X64, GeneratedVectors.proofInt8X64),
            Triple("Word8X64", GeneratedVectors.unpackedWord8X64, GeneratedVectors.proofWord8X64),
            Triple("Int16X32", GeneratedVectors.unpackedInt16X32, GeneratedVectors.proofInt16X32),
            Triple("Word16X32", GeneratedVectors.unpackedWord16X32, GeneratedVectors.proofWord16X32),
            Triple("Word64X2", GeneratedVectors.unpackedWord64X2, GeneratedVectors.proofWord64X2),
            Triple("Word32X8", GeneratedVectors.unpackedWord32X8, GeneratedVectors.proofWord32X8),
            Triple("Int32X8", GeneratedVectors.unpackedInt32X8, GeneratedVectors.proofInt32X8),
            Triple("Int32X16", GeneratedVectors.unpackedInt32X16, GeneratedVectors.proofInt32X16),
            Triple("FloatX8", GeneratedVectors.unpackedFloatX8, GeneratedVectors.proofFloatX8),
            Triple("DoubleX4", GeneratedVectors.unpackedDoubleX4, GeneratedVectors.proofDoubleX4),
            Triple("Int64X4", GeneratedVectors.unpackedInt64X4, GeneratedVectors.proofInt64X4),
            Triple("Int64X8", GeneratedVectors.unpackedInt64X8, GeneratedVectors.proofInt64X8),
            Triple("Word64X4", GeneratedVectors.unpackedWord64X4, GeneratedVectors.proofWord64X4),
            Triple("Word64X8", GeneratedVectors.unpackedWord64X8, GeneratedVectors.proofWord64X8),
            Triple("Word32X16", GeneratedVectors.unpackedWord32X16, GeneratedVectors.proofWord32X16),
            Triple("FloatX16", GeneratedVectors.unpackedFloatX16, GeneratedVectors.proofFloatX16),
            Triple("DoubleX8", GeneratedVectors.unpackedDoubleX8, GeneratedVectors.proofDoubleX8),
            Triple("Int16X16", GeneratedVectors.unpackedInt16X16, GeneratedVectors.proofInt16X16),
            Triple("Word16X16", GeneratedVectors.unpackedWord16X16, GeneratedVectors.proofWord16X16),
            Triple("Int8X16", CoreVectors.unpacked8, CoreVectors.proof8),
            Triple("Word8X16", CoreVectors.unpackedWord8, CoreVectors.proofWord8),
            Triple("Int16X8", CoreVectors.unpacked16, CoreVectors.proof16),
            Triple("Word16X8", CoreVectors.unpackedWord16, CoreVectors.proofWord16),
            Triple("Int32X4", CoreVectors.unpacked32, CoreVectors.proof32),
            Triple("Word32X4", CoreVectors.unpackedWord32, CoreVectors.proofWord32),
            Triple("Int64X2", CoreVectors.unpacked, CoreVectors.proof),
            Triple("FloatX4", CoreVectors.unpackedFloat, CoreVectors.proofFloat),
            Triple("DoubleX2", CoreVectors.unpackedDouble, CoreVectors.proofDouble))) {
            CoreVectors.validate("pack$name#", listOf(tuple), vector)
            CoreVectors.validate("times$name#", listOf(vector, vector), vector)
            CoreVectors.validate("unpack$name#", listOf(vector), tuple)
            val lane = tuple.components!!.first()
            val index = CoreRepresentation(CoreKind.LONG, true, true, listOf("IntRep"))
            CoreVectors.validate("insert$name#", listOf(vector, lane, index), vector)
            assertThrows(RuntimeFault::class.java) {
                CoreVectors.validate("insert$name#", listOf(vector, lane, index.copy(primReps = listOf("WordRep"))), vector)
            }
            assertThrows(RuntimeFault::class.java) {
                CoreVectors.validate("insert$name#", listOf(vector, lane.copy(primReps = listOf("IntRep")), index), vector)
            }
            for (index in tuple.components!!.indices) {
                val wrong = tuple.copy(components = tuple.components.mapIndexed { i, proof ->
                    if (i == index) proof.copy(primReps = listOf("WordRep")) else proof })
                assertThrows(RuntimeFault::class.java) { CoreVectors.validate("pack$name#", listOf(wrong), vector) }
            }
            for (wrong in listOf(CoreVectors.proof32, CoreVectors.proof16, CoreVectors.proofFloat, tuple)
                .filter { it.vector != vector.vector }) {
                assertThrows(RuntimeFault::class.java) { CoreVectors.validate("times$name#", listOf(vector, wrong), vector) }
                assertThrows(RuntimeFault::class.java) { CoreVectors.validate("times$name#", listOf(vector, vector), wrong) }
            }
            for (arity in listOf(0, 1, 3)) assertThrows(RuntimeFault::class.java) {
                CoreVectors.validate("times$name#", List(arity) { vector }, vector)
            }
            CoreRepresentations.requireInput(vector)
            val input = ArgumentLayout.fromProofs(listOf(vector))!!
            assertTrue(input.requiresTyped)
            assertEquals(1, input.physicalArity)
            assertThrows(RuntimeFault::class.java) {
                ArgumentLayout.validate(input, 0, ArgumentLayout.fromProofs(listOf(tuple)), 0, 1)
            }
        }
        for (flags in listOf(listOf(true), listOf(null), listOf(0L))) assertThrows(RuntimeFault::class.java) {
            CoreVectors.validateFlags(flags)
        }
    }

    @Test fun insertRejectsInvalidMachineIndicesBeforeAnyNarrowing() {
        val original = LongVector.broadcast(LongVector.SPECIES_128, 1L).withLane(1, 2L)
        for (index in listOf(-1L, 2L, Long.MIN_VALUE, Long.MAX_VALUE, 0x1_0000_0000L)) {
            assertThrows(RuntimeFault::class.java) { (original).withLane(CoreVectors.laneIndex(index, 2), -1L) }
            assertThrows(RuntimeFault::class.java) { BytecodeRoot.GeneratedDoubleX2Insert.apply(DoubleVector.broadcast(DoubleVector.SPECIES_128, 1.0), 2.0, index) }
        }
        for (index in listOf(-1L, 16L, Long.MAX_VALUE, 0x1_0000_0000L)) {
            assertThrows(RuntimeFault::class.java) { BytecodeRoot.GeneratedInt8X16Insert.apply(ByteVector.broadcast(ByteVector.SPECIES_128, 1.toByte()), 2, index) }
        }
    }

    @Test fun floatingExtremaRequireTwoExactCarriersAndResult() {
        for ((shape, proof) in listOf("FloatX4" to CoreVectors.proofFloat,
            "FloatX8" to GeneratedVectors.proofFloatX8, "FloatX16" to GeneratedVectors.proofFloatX16,
            "DoubleX2" to CoreVectors.proofDouble, "DoubleX4" to GeneratedVectors.proofDoubleX4,
            "DoubleX8" to GeneratedVectors.proofDoubleX8)) {
            for (operation in listOf("min", "max")) {
                val name = "$operation$shape#"
                CoreVectors.validate(name, listOf(proof, proof), proof)
                for (index in 0..1) assertThrows(RuntimeFault::class.java) {
                    CoreVectors.validate(name, MutableList(2) { proof }.also { it[index] = CoreVectors.proof }, proof)
                }
                for (arity in listOf(1, 3)) assertThrows(RuntimeFault::class.java) {
                    CoreVectors.validate(name, List(arity) { proof }, proof)
                }
                assertThrows(RuntimeFault::class.java) {
                    CoreVectors.validate(name, listOf(proof, proof), CoreVectors.proof)
                }
                assertThrows(RuntimeFault::class.java) { CoreVectors.validateFlags(listOf(false, true)) }
            }
        }
    }

    @Test fun word32X16ExtremaUseUnsignedLaneOrderAcrossTheFullCarrier() {
        val left = (IntVector.broadcast(IntVector.SPECIES_512, Int.MIN_VALUE)).withLane(CoreVectors.laneIndex(15L, 16), -1)
        val right = (IntVector.broadcast(IntVector.SPECIES_512, Int.MAX_VALUE)).withLane(CoreVectors.laneIndex(15L, 16), 0)
        val minimum = (left).lanewise(VectorOperators.UMIN, right)
        val maximum = (left).lanewise(VectorOperators.UMAX, right)
        assertEquals(Int.MAX_VALUE, minimum.lane(0))
        assertEquals(Int.MAX_VALUE, minimum.lane(7))
        assertEquals(0, minimum.lane(15))
        assertEquals(Int.MIN_VALUE, maximum.lane(0))
        assertEquals(Int.MIN_VALUE, maximum.lane(7))
        assertEquals(-1, maximum.lane(15))
    }

    @Test fun int32X16ExtremaKeepSignedLanesAndExactShape() {
        val left = (IntVector.broadcast(IntVector.SPECIES_512, Int.MIN_VALUE)).withLane(CoreVectors.laneIndex(15L, 16), -1)
        val right = (IntVector.broadcast(IntVector.SPECIES_512, Int.MAX_VALUE)).withLane(CoreVectors.laneIndex(15L, 16), 0)
        val minimum = (left).min(right)
        val maximum = (left).max(right)
        assertEquals(Int.MIN_VALUE, minimum.lane(0))
        assertEquals(Int.MIN_VALUE, minimum.lane(7))
        assertEquals(-1, minimum.lane(15))
        assertEquals(Int.MAX_VALUE, maximum.lane(0))
        assertEquals(Int.MAX_VALUE, maximum.lane(7))
        assertEquals(0, maximum.lane(15))
        val proof = GeneratedVectors.proofInt32X16
        for (name in listOf("minInt32X16#", "maxInt32X16#")) {
            CoreVectors.validate(name, listOf(proof, proof), proof)
            for (wrong in listOf(GeneratedVectors.proofWord32X16, GeneratedVectors.proofInt32X8,
                GeneratedVectors.unpackedInt32X16)) {
                assertThrows(RuntimeFault::class.java) { CoreVectors.validate(name, listOf(wrong, proof), proof) }
                assertThrows(RuntimeFault::class.java) { CoreVectors.validate(name, listOf(proof, wrong), proof) }
                assertThrows(RuntimeFault::class.java) { CoreVectors.validate(name, listOf(proof, proof), wrong) }
            }
            for (arity in listOf(0, 1, 3)) assertThrows(RuntimeFault::class.java) {
                CoreVectors.validate(name, List(arity) { proof }, proof)
            }
        }
    }

    @Test fun short16ExtremaDistinguishSignedFromUnsignedOrder() {
        val signedLeft = (ShortVector.broadcast(ShortVector.SPECIES_256, Short.MIN_VALUE)).withLane(CoreVectors.laneIndex(15L, 16), -1)
        val signedRight = (ShortVector.broadcast(ShortVector.SPECIES_256, Short.MAX_VALUE)).withLane(CoreVectors.laneIndex(15L, 16), 0)
        val unsignedLeft = (ShortVector.broadcast(ShortVector.SPECIES_256, Short.MIN_VALUE)).withLane(CoreVectors.laneIndex(15L, 16), -1)
        val unsignedRight = (ShortVector.broadcast(ShortVector.SPECIES_256, Short.MAX_VALUE)).withLane(CoreVectors.laneIndex(15L, 16), 0)
        val signedMin = (signedLeft).min(signedRight)
        val signedMax = (signedLeft).max(signedRight)
        val unsignedMin = (unsignedLeft).lanewise(VectorOperators.UMIN, unsignedRight)
        val unsignedMax = (unsignedLeft).lanewise(VectorOperators.UMAX, unsignedRight)
        assertEquals(listOf(-32768, -32768, -1), listOf(signedMin.lane(0), signedMin.lane(7), signedMin.lane(15)).map { it.toInt() })
        assertEquals(listOf(32767, 32767, 0), listOf(signedMax.lane(0), signedMax.lane(7), signedMax.lane(15)).map { it.toInt() })
        assertEquals(listOf(32767, 32767, 0), listOf(unsignedMin.lane(0), unsignedMin.lane(7), unsignedMin.lane(15)).map { it.toInt() and 0xffff })
        assertEquals(listOf(32768, 32768, 65535), listOf(unsignedMax.lane(0), unsignedMax.lane(7), unsignedMax.lane(15)).map { it.toInt() and 0xffff })
        for ((family, proof, wrongSign) in listOf(
            Triple("Int16X16", GeneratedVectors.proofInt16X16, GeneratedVectors.proofWord16X16),
            Triple("Word16X16", GeneratedVectors.proofWord16X16, GeneratedVectors.proofInt16X16))) {
            for (op in listOf("min", "max")) {
                val name = "$op$family#"
                CoreVectors.validate(name, listOf(proof, proof), proof)
                for (wrong in listOf(wrongSign, CoreVectors.proof16, GeneratedVectors.unpackedInt16X16)) {
                    assertThrows(RuntimeFault::class.java) { CoreVectors.validate(name, listOf(wrong, proof), proof) }
                    assertThrows(RuntimeFault::class.java) { CoreVectors.validate(name, listOf(proof, wrong), proof) }
                    assertThrows(RuntimeFault::class.java) { CoreVectors.validate(name, listOf(proof, proof), wrong) }
                }
            }
        }
    }

    // These early gates use actual pre-Tidy Core and the independent model.
    // They are deliberately separate from native evidence and capability enablement.
    @Test @Tag("simd-families-experiment") fun widerPreparedCoreCompilesWithInlining() = execute(true, true)
    @Test @Tag("simd-families-experiment") fun widerPreparedCoreCompilesAcrossResidualCalls() = execute(false, true)
    @Test @Tag("simd-families-experiment") fun nativeFamiliesWithInlining() = execute(true, false)
    @Test @Tag("simd-families-experiment") fun nativeFamiliesAcrossResidualCalls() = execute(false, false)

    private fun execute(inlining: Boolean, earlyWideGate: Boolean) {
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<String, Any?>
        for (item in (manifest["inputs"] as List<Map<String, String>>) + (manifest["artifacts"] as List<Map<String, String>>)) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, item.getValue("path")).readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(item["sha256"], hash, "Stale SIMD source/artifact: ${item["path"]}")
        }
        val expectedText = File(directory, "expected.tsv").readText()
        if (!earlyWideGate) {
            assertEquals(expectedText.lines().count { it.isNotEmpty() }.toLong(), (manifest["nativeRows"] as Number).toLong())
            assertEquals(expectedText, File(directory, "oracle.tsv").readText())
            assertEquals(listOf("pre", "post"), manifest["stages"])
        }
        val rows = expectedText.lineSequence().filter(String::isNotEmpty).map { it.split('\t') }.groupBy { it[0] }
        val names = if (earlyWideGate) listOf("timesInt32X8", "timesInt32X16", "timesWord64X2",
            "timesWord32X8", "floatX8Composite", "doubleX4Composite") else rows.keys.toList()
        for (stage in manifest["stages"] as List<String>) {
            val module = Json.parse(File(directory, "$stage-core/GeneratedSimdFamilies.json").readText()) as Map<String, Any?>
            val calls = ((manifest["structures"] as Map<String, Map<String, Any?>>).getValue(stage)["expectedGuestCalls"] as Map<String, Number>)
            for (name in names) for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val input = CoreModules.reachable(module, name) + ("instrument" to true)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, input) else BytecodeProgram(language, input)
                    val entry = program.entryTarget(name); val host = program.hostEntryTarget(3)
                    val function = context.asValue(EntryValue(program, name, 3))
                    val cases = when {
                        !earlyWideGate -> rows.getValue(name)
                        name.endsWith("Composite") -> rows.getValue(name).groupBy { it[1] }.values.flatMap { selectorRows ->
                            // Extrema's native corpus excludes NaNs, infinities and mixed-zero ties.
                            val indices = when (selectorRows.size) {
                                28 -> listOf(12, 14, 16)
                                398 -> listOf(0, 199, 397)
                                else -> listOf(348, 350, 404)
                            }
                            indices.map(selectorRows::get)
                        }
                        else -> rows.getValue(name).filterIndexed { i, _ -> i % 225 in listOf(0, 112, 224) }
                    }
                    val label = "$stage/$backend/$name/inline=$inlining"
                    fun check(row: List<String>) {
                        assertEquals(row[4].toLong(), function.execute(row[1].toLong(), row[2].toLong(), row[3].toLong()).asLong(), "$label/${row.drop(1)}")
                    }
                    cases.forEach(::check)
                    val active = activeTargets(host)
                    assertEquals(calls.getValue(name).toInt() + 1, active.size, "$label actual target count")
                    (active + entry).distinct().filter { it !== host }.forEach(::compile)
                    assertTrue(function.invokeMember("compile").asBoolean(), "$label host installation")
                    for (row in cases) {
                        val before = count(program)
                        check(row)
                        assertEquals(before + calls.getValue(name).toLong(), count(program), "$label exact guest entries")
                        assertEquals(active, activeTargets(host), "$label actual target identity")
                        valid(entry, "$label original"); active.forEach { valid(it, "$label active") }; released(language)
                    }
                    for (counter in listOf("unsupportedTraps", "blackholes")) {
                        assertEquals(0L, (program.diagnostics().getValue(counter) as Number).toLong(), "$label/$counter")
                    }
                } finally { context.leave() }
            }
        }
    }
}
