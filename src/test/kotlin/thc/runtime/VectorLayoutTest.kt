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
import jdk.incubator.vector.*

/** Independent payload construction for transport/heap tests, including nonuniform lanes. */
@Suppress("UNCHECKED_CAST")
internal fun rawVectorTestValue(proof: CoreRepresentation): Vector<*> {
    val layout = VectorLayout(proof)
    val count = layout.lanes
    fun bits(index: Int) = -1L - index * 104729L
    return when (proof.vector!!.element) {
        "Int8ElemRep", "Word8ElemRep" -> ByteVector.fromArray(layout.species as VectorSpecies<Byte>, ByteArray(count) { bits(it).toByte() }, 0)
        "Int16ElemRep", "Word16ElemRep" -> ShortVector.fromArray(layout.species as VectorSpecies<Short>, ShortArray(count) { bits(it).toShort() }, 0)
        "Int32ElemRep", "Word32ElemRep" -> IntVector.fromArray(layout.species as VectorSpecies<Int>, IntArray(count) { bits(it).toInt() }, 0)
        "Int64ElemRep", "Word64ElemRep" -> LongVector.fromArray(layout.species as VectorSpecies<Long>, LongArray(count) { bits(it) }, 0)
        "FloatElemRep" -> FloatVector.fromArray(layout.species as VectorSpecies<Float>, FloatArray(count) {
            Float.fromBits(intArrayOf(Int.MIN_VALUE, 1, 0x7fc01234, 0x7f800000)[it % 4]) }, 0)
        "DoubleElemRep" -> DoubleVector.fromArray(layout.species as VectorSpecies<Double>, DoubleArray(count) {
            Double.fromBits(longArrayOf(Long.MIN_VALUE, 1L, 0x7ff8000000001234L, 0x7ff0000000000000L)[it % 4]) }, 0)
        else -> error("Unexpected vector proof")
    }
}

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
        layout.write(slots.frame, slots.slots, 0, rawVectorTestValue(layout.proof))
    }
    private fun same(layout: VectorLayout, expected: Slots, actual: Slots) {
        assertSame(layout.read(expected.frame, expected.slots, 0), layout.read(actual.frame, actual.slots, 0))
    }
    @Test fun allShapesPreserveBitsAcrossCarriersDenseStorageAndCompletion() = withLanguage { language ->
        for (proof in vectors()) {
            val vector = VectorLayout(proof)
            val original = Slots(1); fill(vector, original)
            val copied = Slots(1)
            vector.write(copied.frame, copied.slots, 0, vector.read(original.frame, original.slots, 0))
            same(vector, original, copied)
            val shape = TupleShape(proof, language)
            assertEquals(1, shape.width)
            assertTrue(shape.layout.isObject(0)); assertFalse(shape.layout.isLong(0))
            val result = shape.finish(copied.frame, copied.slots)
            assertEquals(1, language.handoffState.get().results.depth)
            val consumed = Slots(1)
            shape.consume(consumed.frame, result, consumed.slots, 0)
            same(vector, original, consumed)
            assertEquals(0, language.handoffState.get().results.depth)
            assertEquals(0, language.handoffState.get().results.retainedReferences())
            // A deoptimized result is private storage, not a live pool loan.
            val fresh = shape.layout.create()
            val source = AstInputSource(ArgumentLayout.fromProofs(listOf(proof))!!, original.slots)
            source.copy(original.frame, object : Node() {}, null, 0, fresh, 0, 1)
            shape.consume(consumed.frame, fresh, consumed.slots, 0)
            same(vector, original, consumed)
        }
    }
    @Test fun papPrefixesOwnImmutableVectorReferencesAfterCallerLocalsAreReused() = withLanguage { language ->
        val target = object : RootNode(language) {
            override fun execute(frame: VirtualFrame): Any = error("PAP construction must not enter the callee")
        }.callTarget
        val node = object : Node() {}
        for (proof in vectors()) for (generic in listOf(false, true)) {
            val vector = VectorLayout(proof)
            val original = Slots(1); fill(vector, original)
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
            val expected = Slots(1)
            vector.write(expected.frame, expected.slots, 0, vector.read(original.frame, original.slots, 0))
            original.slots.forEach(original.frame::clear)
            for (function in listOf(first, second)) {
                val storage = function.typedSupplied!!
                assertFalse(storage.live); assertEquals(0, storage.inputMode)
                for (part in 0 until function.suppliedCount) {
                    val restored = Slots(1)
                    vector.write(restored.frame, restored.slots, 0, storage.layout.getObject(storage, part))
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
    @Test fun rawSpeciesAreCheckedAndReleasedAsReferencesOnSuccessAndFailure() = withLanguage { language ->
        val all = vectors()
        for (proof in all) {
            val vector = VectorLayout(proof)
            val shape = TupleShape(proof, language)
            val slots = Slots(1)
            val raw = rawVectorTestValue(proof)
            for (other in all) {
                val candidate = rawVectorTestValue(other)
                if (candidate.species() == raw.species()) {
                    assertSame(candidate, vector.require(candidate))
                    assertSame(shape.layout, TupleShape(other, language).layout)
                } else {
                    assertThrows(RuntimeFault::class.java) { vector.write(slots.frame, slots.slots, 0, candidate) }
                    assertThrows(RuntimeFault::class.java) { shape.layout.setObject(shape.layout.create(), 0, candidate) }
                }
            }
            for (wrong in listOf(null, 1L, longArrayOf(1L), Any()))
                assertThrows(RuntimeFault::class.java) { vector.write(slots.frame, slots.slots, 0, wrong) }
            val pool = language.handoffState.get().arguments
            val loan = pool.acquire(shape.layout)
            shape.layout.setObject(loan, 0, raw)
            assertEquals(1, pool.retainedReferences())
            pool.release(loan)
            assertEquals(0, pool.depth); assertEquals(0, pool.retainedReferences())
            FrameAccess.writeObject(slots.frame, slots.slots[0], Any())
            assertThrows(RuntimeFault::class.java) { shape.finish(slots.frame, slots.slots) }
            assertEquals(0, language.handoffState.get().results.depth)
            assertEquals(0, language.handoffState.get().results.retainedReferences())
        }
    }
    @Test fun physicalVectorAnnotationDoesNotEraseLogicalTupleIdentity() {
        val scalar = mapOf("kind" to "vector", "evaluated" to true, "primReps" to listOf("VecRep 4 FloatElemRep"),
            "vector" to mapOf("lanes" to 4, "element" to "FloatElemRep"))
        val state = mapOf("kind" to "void", "evaluated" to true, "primReps" to emptyList<String>())
        val tuple = scalar + mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple", "components" to listOf(state, scalar))
        val proof = CoreRepresentations.parse(tuple)
        assertTrue(proof.isTuple); assertFalse(proof.isVector)
        assertEquals(1, TupleShape.flatten(proof).size)
        assertFalse(TupleShape.compatible(proof, CoreRepresentations.parse(scalar)))
        for (wrong in listOf(mapOf("lanes" to 2, "element" to "DoubleElemRep"),
            mapOf("lanes" to 4, "element" to "Word32ElemRep")))
            assertThrows(RuntimeFault::class.java) { CoreRepresentations.parse(tuple + ("vector" to wrong)) }
    }
}
