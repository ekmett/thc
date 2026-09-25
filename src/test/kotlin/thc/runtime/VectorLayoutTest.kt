// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Json
import thc.Language
import java.io.File

/** Transport controls independent of arithmetic and of either loader's optimizations. */
class VectorLayoutTest {
    private val integer = CoreRepresentation(CoreKind.LONG, true, true, listOf("IntRep"))
    private fun vectors(): List<CoreRepresentation> {
        val catalog = Json.parse(File(System.getProperty("thc.projectRoot"), "scripts/simd-families.json").readText()) as Map<*, *>
        return (catalog["families"] as List<*>).map { raw ->
            val shape = raw as Map<*, *>
            CoreRepresentations.parse(mapOf("kind" to "vector", "evaluated" to true,
                "primReps" to listOf("VecRep ${shape["lanes"]} ${shape["element"]}"),
                "vector" to mapOf("lanes" to shape["lanes"], "element" to shape["element"])))
        }.also { assertEquals(24, it.size) }
    }
    private fun withLanguage(action: (Language) -> Unit) = Context.newBuilder("thc").build().use { context ->
        context.initialize("thc"); context.enter()
        try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
        finally { context.leave() }
    }
    private class Slots(count: Int) {
        val layout = FrameLayout()
        val slots = IntArray(count) { layout.bind("lane $it") }
        val frame: VirtualFrame = Truffle.getRuntime().createVirtualFrame(emptyArray(), layout.build())
    }
    private fun fill(layout: VectorLayout, slots: Slots) {
        for (index in 0 until layout.lanes) when {
            layout.lane.isFloat -> FrameAccess.writeFloat(slots.frame, slots.slots[index],
                Float.fromBits(intArrayOf(Int.MIN_VALUE, 1, 0x7fc01234, 0x7f800000)[index % 4]))
            layout.lane.isDouble -> FrameAccess.writeDouble(slots.frame, slots.slots[index],
                Double.fromBits(longArrayOf(Long.MIN_VALUE, 1L, 0x7ff8000000001234L, 0x7ff0000000000000L)[index % 4]))
            else -> {
                val value = -1L - index * 104729L
                val narrowed = when (layout.lane.primReps!!.single()) {
                    "Int8Rep" -> value.toByte().toLong(); "Word8Rep" -> value and 255L
                    "Int16Rep" -> value.toShort().toLong(); "Word16Rep" -> value and 65535L
                    "Int32Rep" -> value.toInt().toLong(); "Word32Rep" -> value and 4294967295L
                    else -> value
                }
                FrameAccess.writeLong(slots.frame, slots.slots[index], narrowed)
            }
        }
    }
    private fun same(layout: VectorLayout, expected: Slots, actual: Slots) {
        for (index in 0 until layout.lanes) when {
            layout.lane.isFloat -> assertEquals(expected.frame.getFloat(expected.slots[index]).toRawBits(),
                actual.frame.getFloat(actual.slots[index]).toRawBits())
            layout.lane.isDouble -> assertEquals(expected.frame.getDouble(expected.slots[index]).toRawBits(),
                actual.frame.getDouble(actual.slots[index]).toRawBits())
            else -> assertEquals(expected.frame.getLong(expected.slots[index]), actual.frame.getLong(actual.slots[index]))
        }
    }
    @Test fun allShapesPreserveBitsAcrossCarriersDenseStorageAndCompletion() = withLanguage { language ->
        for (proof in vectors()) {
            val vector = VectorLayout(proof)
            val original = Slots(vector.lanes); fill(vector, original)
            val copied = Slots(vector.lanes)
            vector.write(copied.frame, copied.slots, 0, vector.read(original.frame, original.slots, 0))
            same(vector, original, copied)
            val shape = TupleShape(proof, language)
            assertEquals(vector.lanes, shape.width)
            val lane = vector.lane.primReps!!.single()
            val narrow = lane in setOf("Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep", "Int32Rep", "Word32Rep")
            if (narrow) assertTrue(shape.layout.reps.all { it.endsWith("-lane") })
            val result = shape.finish(copied.frame, copied.slots)
            assertEquals(1, language.handoffState.get().results.depth)
            val consumed = Slots(vector.lanes)
            shape.consume(consumed.frame, result, consumed.slots, 0)
            same(vector, original, consumed)
            assertEquals(0, language.handoffState.get().results.depth)
            assertEquals(0, language.handoffState.get().results.retainedReferences())
            // A deoptimized result is private storage, not a live pool loan.
            val fresh = shape.layout.create()
            val source = AstInputSource(ArgumentLayout.fromProofs(listOf(proof))!!, original.slots)
            source.copy(original.frame, object : Node() {}, null, 0, fresh, 0, vector.lanes)
            shape.consume(consumed.frame, fresh, consumed.slots, 0)
            same(vector, original, consumed)
        }
    }
    @Test fun papPrefixesOwnPrimitiveLanesAfterCallerLocalsAreReused() = withLanguage { language ->
        val target = object : RootNode(language) {
            override fun execute(frame: VirtualFrame): Any = error("PAP construction must not enter the callee")
        }.callTarget
        val node = object : Node() {}
        for (proof in vectors()) for (generic in listOf(false, true)) {
            val vector = VectorLayout(proof)
            val original = Slots(vector.lanes); fill(vector, original)
            val source = AstInputSource(ArgumentLayout.fromProofs(listOf(proof))!!, original.slots)
            val input = TypedInputLayout.create(language,
                ArgumentLayout.fromProofs(listOf(proof, proof, integer)), false)!!
            val closure = Closure(null, arity = 3, target = target)
            fun append(function: Closure) = if (generic)
                genericTypedPap(function, input, source, original.frame, node, null, 1, 0, 1)
            else typedPap(function, input, source, original.frame, node, null, 0, 1,
                function.suppliedCount, function.arity)
            val first = append(closure)
            val second = append(first)
            assertEquals(1, first.suppliedCount); assertEquals(2, second.suppliedCount)
            assertSame(NO_PAP_ARGUMENTS, first.supplied); assertSame(NO_PAP_ARGUMENTS, second.supplied)
            assertNotSame(first.typedSupplied, second.typedSupplied)
            val expected = Slots(vector.lanes)
            vector.write(expected.frame, expected.slots, 0, vector.read(original.frame, original.slots, 0))
            original.slots.forEach(original.frame::clear)
            for (function in listOf(first, second)) {
                val storage = function.typedSupplied!!
                assertFalse(storage.live); assertEquals(0, storage.inputMode)
                for (part in 0 until function.suppliedCount) {
                    val restored = Slots(vector.lanes)
                    for (index in 0 until vector.lanes) {
                        val field = part * vector.lanes + index
                        when {
                            vector.lane.isFloat -> FrameAccess.writeFloat(restored.frame, restored.slots[index], storage.layout.getFloat(storage, field))
                            vector.lane.isDouble -> FrameAccess.writeDouble(restored.frame, restored.slots[index], storage.layout.getDouble(storage, field))
                            else -> FrameAccess.writeLong(restored.frame, restored.slots[index], storage.layout.getLong(storage, field))
                        }
                    }
                    same(vector, expected, restored)
                }
            }
            assertEquals(0, language.handoffState.get().arguments.depth)
        }
    }
    @Test fun logicalVectorIdentityCannotBeReplacedByEqualWidthTuplesOrOtherShapes() = withLanguage { language ->
        for (proof in vectors()) {
            val vector = VectorLayout(proof)
            val tuple = CoreRepresentation(CoreKind.UNKNOWN, true, true,
                List(vector.lanes) { vector.lane.primReps!!.single() }, List(vector.lanes) { vector.lane })
            val input = ArgumentLayout.fromProofs(listOf(proof))!!
            for (wrong in vectors().filter { it != proof } + tuple) {
                assertFalse(TupleShape.compatible(proof, wrong))
                assertFalse(TupleShape(proof, language).matches(TupleShape(wrong, language)))
                assertThrows(RuntimeFault::class.java) { TupleShape.requireCompatible(proof, wrong) }
                assertThrows(RuntimeFault::class.java) {
                    ArgumentLayout.validate(input, 0, ArgumentLayout.fromProofs(listOf(wrong)), 0, 1)
                }
                assertThrows(RuntimeFault::class.java) { CoreRepresentations.requireJoinArgument(proof, wrong) }
            }
            assertThrows(RuntimeFault::class.java) { ArgumentLayout.validate(input, 0, null, 0, 1) }
        }
    }
    @Test fun physicalVectorAnnotationDoesNotEraseLogicalTupleIdentity() {
        val scalar = mapOf("kind" to "vector", "evaluated" to true, "primReps" to listOf("VecRep 4 FloatElemRep"),
            "vector" to mapOf("lanes" to 4, "element" to "FloatElemRep"))
        val state = mapOf("kind" to "void", "evaluated" to true, "primReps" to emptyList<String>())
        val tuple = scalar + mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple", "components" to listOf(state, scalar))
        val proof = CoreRepresentations.parse(tuple)
        assertTrue(proof.isTuple); assertFalse(proof.isVector)
        assertEquals(4, TupleShape.flatten(proof).size)
        assertFalse(TupleShape.compatible(proof, CoreRepresentations.parse(scalar)))
        for (wrong in listOf(mapOf("lanes" to 2, "element" to "DoubleElemRep"),
            mapOf("lanes" to 4, "element" to "Word32ElemRep")))
            assertThrows(RuntimeFault::class.java) { CoreRepresentations.parse(tuple + ("vector" to wrong)) }
    }
}
