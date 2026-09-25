// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.BytecodeConfig
import com.oracle.truffle.api.bytecode.LocalAccessor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Json
import thc.Language
import java.io.File
import java.lang.reflect.Modifier

/** Heap ownership is independent of transient carriers and reusable call storage. */
class VectorHeapStorageTest {
    private fun vectors(): List<CoreRepresentation> {
        val catalog = Json.parse(File(System.getProperty("thc.projectRoot"), "scripts/simd-families.json").readText()) as Map<*, *>
        return (catalog["families"] as List<*>).map { raw ->
            val shape = raw as Map<*, *>
            CoreRepresentations.parse(mapOf("kind" to "vector", "evaluated" to true,
                "primReps" to listOf("VecRep ${shape["lanes"]} ${shape["element"]}"),
                "vector" to mapOf("lanes" to shape["lanes"], "element" to shape["element"])))
        }.also { assertEquals(24, it.size) }
    }
    private fun inLanguage(strategy: String, action: (Language) -> Unit) = Context.newBuilder("thc")
        .allowExperimentalOptions(true).option("engine.StaticObjectStorageStrategy", strategy).build().use { context ->
            context.initialize("thc"); context.enter()
            try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
            finally { context.leave() }
        }
    private class Slots(count: Int) {
        val layout = FrameLayout()
        val slots = IntArray(count) { layout.bind("field lane $it") }
        val frame: VirtualFrame = Truffle.getRuntime().createVirtualFrame(emptyArray(), layout.build())
    }
    private fun fill(proof: CoreRepresentation, target: Slots) {
        val vector = VectorLayout(proof)
        for (index in 0 until vector.lanes) when {
            vector.lane.isFloat -> FrameAccess.writeFloat(target.frame, target.slots[index],
                Float.fromBits(intArrayOf(Int.MIN_VALUE, 1, 0x7fc01234, 0x7f800000)[index % 4]))
            vector.lane.isDouble -> FrameAccess.writeDouble(target.frame, target.slots[index],
                Double.fromBits(longArrayOf(Long.MIN_VALUE, 1, 0x7ff8000000001234L, 0x7ff0000000000000L)[index % 4]))
            else -> {
                val bits = -1L - index * 104729L
                FrameAccess.writeLong(target.frame, target.slots[index], when (vector.lane.primReps!!.single()) {
                    "Int8Rep" -> bits.toByte().toLong(); "Word8Rep" -> bits and 255L
                    "Int16Rep" -> bits.toShort().toLong(); "Word16Rep" -> bits and 65535L
                    "Int32Rep" -> bits.toInt().toLong(); "Word32Rep" -> bits and 4294967295L
                    else -> bits
                })
            }
        }
    }
    private fun compare(proof: CoreRepresentation, expected: Slots, actual: Slots) {
        val vector = VectorLayout(proof)
        for (index in 0 until vector.lanes) when {
            vector.lane.isFloat -> assertEquals(expected.frame.getFloat(expected.slots[index]).toRawBits(),
                actual.frame.getFloat(actual.slots[index]).toRawBits())
            vector.lane.isDouble -> assertEquals(expected.frame.getDouble(expected.slots[index]).toRawBits(),
                actual.frame.getDouble(actual.slots[index]).toRawBits())
            else -> assertEquals(expected.frame.getLong(expected.slots[index]), actual.frame.getLong(actual.slots[index]))
        }
    }
    private fun metadata(proof: CoreRepresentation): Map<String, Any?> = mapOf(
        "id" to "VectorBox", "name" to "VectorBox", "kind" to "boxed", "arity" to 3,
        "fieldReps" to listOf(proof.primReps, listOf("IntRep"), listOf("BoxedRep (Just Lifted)")),
        "fieldTypes" to listOf(mapOf("kind" to "vector", "evaluated" to true, "primReps" to proof.primReps,
            "vector" to mapOf("lanes" to proof.vector!!.lanes, "element" to proof.vector.element)),
            mapOf("kind" to "long", "evaluated" to true, "primReps" to listOf("IntRep")),
            mapOf("kind" to "object", "evaluated" to false, "primReps" to listOf("BoxedRep (Just Lifted)"))),
        "strictFields" to listOf(false, false, false), "fieldLifted" to listOf(false, false, true))

    @Test fun everyShapeOwnsFinalLanesAfterCallerReuseWithLazyReferencesIntact() {
        for (strategy in listOf("field-based", "array-based")) inLanguage(strategy) { language ->
            for (proof in vectors()) {
                val lanes = proof.vector!!.lanes
                val source = Slots(lanes + 2); fill(proof, source)
                val expected = Slots(lanes); fill(proof, expected)
                val cell = RecCell()
                val captures = CaptureLayout.withVectors(language, arrayOf(proof, null, null),
                    booleanArrayOf(false, true, false), booleanArrayOf(false, true, false))
                assertEquals(lanes + 2, captures.storageSize)
                assertEquals(lanes, captures.fieldWidth(0)); assertEquals(1, captures.fieldWidth(1))
                assertEquals(proof, captures.vectorProof(0)); assertFalse(captures.isVector(1))
                FrameAccess.writeLong(source.frame, source.slots[lanes], Long.MIN_VALUE)
                FrameAccess.write(source.frame, source.slots[lanes + 1], cell)
                val environment = captures.capture(source.frame, source.slots)
                val fields = CoreFields(metadata(proof))
                val constructor = DataLayout.fromFields(language, "VectorBox", "VectorBox", fields)
                assertEquals(3, constructor.arity)
                assertEquals(lanes, constructor.fieldWidth(0)); assertEquals(proof, constructor.vectorProof(0))
                val never = object : RootNode(language) {
                    override fun execute(frame: VirtualFrame): Any = error("Lazy heap reference was forced")
                }.callTarget
                val thunk = Thunk(never, null)
                val value = constructor.allocate()
                constructor.initializeVector(value, 0, source.frame, source.slots, 0)
                constructor.initializeLong(value, 1, Long.MAX_VALUE)
                constructor.initialize(value, 2, thunk)
                source.slots.forEach(source.frame::clear)
                val restored = Slots(lanes)
                repeat(2) {
                    captures.restoreVector(environment, 0, restored.frame, restored.slots, 0)
                    compare(proof, expected, restored)
                    constructor.restoreVector(value, 0, restored.frame, restored.slots, 0)
                    compare(proof, expected, restored)
                }
                assertSame(cell, captures.readObject(environment, 2)); assertFalse(cell.initialized)
                assertEquals(Long.MIN_VALUE, captures.readLong(environment, 1))
                assertSame(thunk, constructor.read(value, 2)); assertEquals(0, thunk.state)
                assertEquals(Long.MAX_VALUE, constructor.readLong(value, 1))
                assertThrows(RuntimeFault::class.java) { captures.read(environment, 0) }
                assertThrows(RuntimeFault::class.java) { captures.readLong(environment, 0) }
                assertThrows(RuntimeFault::class.java) { captures.readFloat(environment, 0) }
                assertThrows(RuntimeFault::class.java) { captures.readDouble(environment, 0) }
                assertThrows(RuntimeFault::class.java) { captures.readObject(environment, 0) }
                assertThrows(IllegalStateException::class.java) { captures.captureValues(arrayOf(null, 0L, cell)) }
                assertThrows(RuntimeFault::class.java) { constructor.create(arrayOf(null, 0L, thunk)) }
                assertThrows(RuntimeFault::class.java) { constructor.read(value, 0) }
                if (strategy == "field-based") {
                    for ((owner, prefix) in listOf(environment to "capture_0_lane_", value to "field_0_lane_")) {
                        // StaticShape escapes underscores in property IDs when
                        // naming generated fields and may place them in a base class.
                        val generatedPrefix = prefix.replace("_", "__")
                        val properties = generateSequence(owner.javaClass) { it.superclass }
                            .flatMap { it.declaredFields.asSequence() }
                            .filter { it.name.startsWith(generatedPrefix) }.toList()
                        assertEquals(lanes, properties.size)
                        assertTrue(properties.all { it.type.isPrimitive && Modifier.isFinal(it.modifiers) })
                        val width = when (proof.vector.element) {
                            "Int8ElemRep", "Word8ElemRep" -> Byte::class.javaPrimitiveType
                            "Int16ElemRep", "Word16ElemRep" -> Short::class.javaPrimitiveType
                            "Int32ElemRep", "Word32ElemRep" -> Int::class.javaPrimitiveType
                            "Int64ElemRep", "Word64ElemRep" -> Long::class.javaPrimitiveType
                            "FloatElemRep" -> Float::class.javaPrimitiveType
                            else -> Double::class.javaPrimitiveType
                        }
                        assertTrue(properties.all { it.type == width })
                    }
                }
                assertEquals(0, language.handoffState.get().arguments.depth)
                assertEquals(0, language.handoffState.get().results.depth)
            }
        }
    }

    @Test fun bytecodePrimitiveLocalsPreserveAdaptiveCapturesBesideOwnedVectorLanes() {
        for (strategy in listOf("field-based", "array-based")) inLanguage(strategy) { language ->
            for (proof in vectors()) {
                val vector = VectorLayout(proof)
                val lanes = vector.lanes
                val expected = Slots(lanes); fill(proof, expected)
                val captures = CaptureLayout.withVectors(language, arrayOf(proof, null, null, null, null),
                    booleanArrayOf(false, true, true, true, false))
                val locals = mutableListOf<LocalAccessor>()
                val root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT) { b ->
                    b.beginRoot()
                    repeat(captures.storageSize) { locals += LocalAccessor.constantOf(b.createLocal("source $it", null)) }
                    b.beginReturn(); b.emitLoadConstant(0L); b.endReturn()
                    b.endRoot()
                }.getNode(0)
                val bytecode = root.bytecodeNode
                // Use the generated node's actual descriptor and typed local APIs,
                // including their primitive type profiles, without an AST slot shim.
                val frame = Truffle.getRuntime().createVirtualFrame(arrayOf(0L), root.frameDescriptor)
                val slots = locals.toTypedArray()
                for (index in 0 until lanes) when {
                    vector.lane.isFloat -> slots[index].setFloat(bytecode, frame, expected.frame.getFloat(expected.slots[index]))
                    vector.lane.isDouble -> slots[index].setDouble(bytecode, frame, expected.frame.getDouble(expected.slots[index]))
                    else -> slots[index].setLong(bytecode, frame, expected.frame.getLong(expected.slots[index]))
                }
                val float = Float.fromBits(0x7fc01234)
                val double = Double.fromBits(0x7ff8000000001234L)
                val cell = RecCell()
                slots[lanes].setLong(bytecode, frame, Long.MIN_VALUE)
                slots[lanes + 1].setFloat(bytecode, frame, float)
                slots[lanes + 2].setDouble(bytecode, frame, double)
                slots[lanes + 3].setObject(bytecode, frame, cell)
                // Direct LocalAccessor writes retain primitive frame carriers;
                // bytecode locals' typeProfile is populated by executed StoreLocal
                // instructions, which this isolated storage test does not emit.
                assertEquals(Long.MIN_VALUE, slots[lanes].getLong(bytecode, frame))
                assertEquals(float.toRawBits(), slots[lanes + 1].getFloat(bytecode, frame).toRawBits())
                assertEquals(double.toRawBits(), slots[lanes + 2].getDouble(bytecode, frame).toRawBits())
                val environment = captures.captureLocals(bytecode, frame, slots)
                slots.forEach { it.clear(bytecode, frame) }
                assertTrue(slots.all { it.isCleared(bytecode, frame) })
                assertTrue(captures.isLong(environment, 1))
                assertEquals(Long.MIN_VALUE, captures.readLong(environment, 1))
                assertTrue(captures.isObject(environment, 2)); assertTrue(captures.isObject(environment, 3))
                assertEquals(float.toRawBits(), (captures.readObject(environment, 2) as Float).toRawBits())
                assertEquals(double.toRawBits(), (captures.readObject(environment, 3) as Double).toRawBits())
                assertSame(cell, captures.readObject(environment, 4)); assertFalse(cell.initialized)
                captures.restoreVector(environment, 0, bytecode, frame, slots, 0)
                for (index in 0 until lanes) when {
                    vector.lane.isFloat -> assertEquals(expected.frame.getFloat(expected.slots[index]).toRawBits(),
                        slots[index].getFloat(bytecode, frame).toRawBits())
                    vector.lane.isDouble -> assertEquals(expected.frame.getDouble(expected.slots[index]).toRawBits(),
                        slots[index].getDouble(bytecode, frame).toRawBits())
                    else -> assertEquals(expected.frame.getLong(expected.slots[index]), slots[index].getLong(bytecode, frame))
                }
            }
        }
    }

    @Test fun vectorIdentityAndAllocationOwnershipRemainExact() {
        for (strategy in listOf("field-based", "array-based")) inLanguage(strategy) { language ->
            val signed = vectors().single { it.vector == CoreVector.INT8X16 }
            val unsigned = vectors().single { it.vector == CoreVector.WORD8X16 }
            val first = DataLayout.fromFields(language, "A", "A", CoreFields(metadata(signed)))
            val second = DataLayout.fromFields(language, "B", "B", CoreFields(metadata(unsigned)))
            val source = Slots(16); fill(signed, source)
            val value = first.allocate(); first.initializeVector(value, 0, source.frame, source.slots, 0)
            assertNotEquals(first.vectorProof(0), second.vectorProof(0))
            assertThrows(RuntimeFault::class.java) { second.restoreVector(value, 0, source.frame, source.slots, 0) }
            assertThrows(RuntimeFault::class.java) { second.initializeVector(value, 0, source.frame, source.slots, 0) }
            val a = CaptureLayout.withVectors(language, arrayOf(signed), booleanArrayOf(false))
            val b = CaptureLayout.withVectors(language, arrayOf(unsigned), booleanArrayOf(false))
            val environment = a.capture(source.frame, source.slots)
            assertThrows(RuntimeFault::class.java) { b.restoreVector(environment, 0, source.frame, source.slots, 0) }
            assertThrows(RuntimeFault::class.java) { CapturedFrame(a, Any()) }
            assertThrows(RuntimeFault::class.java) { DataValue(first, Any()) }
            assertThrows(IllegalArgumentException::class.java) {
                CaptureLayout.withVectors(language, arrayOf(signed), booleanArrayOf(true))
            }
            assertThrows(UnsupportedCore::class.java) { CoreFields(metadata(signed) - "fieldTypes") }
            assertThrows(RuntimeFault::class.java) {
                CoreFields(metadata(signed) + ("fieldTypes" to metadata(unsigned).getValue("fieldTypes")))
            }
            val tuple = CoreRepresentation(CoreKind.UNKNOWN, true, true,
                List(16) { "Int8Rep" }, List(16) { VectorLayout.laneProof(signed.vector!!) })
            assertThrows(RuntimeFault::class.java) {
                CaptureLayout.withVectors(language, arrayOf(tuple), booleanArrayOf(false))
            }
        }
    }
}
