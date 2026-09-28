// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Json
import thc.Language
import java.io.File

class FourWayAggregateStorageTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private fun constructors(): Map<String, Map<String, Any?>> {
        verifyFourWayEvidence(root)
        val module = Json.parse(File(root, "build/fourway-aggregate/pre/core/FourWayAggregateFields.json").readText()) as Map<String, Any?>
        return (module["constructors"] as List<Map<String, Any?>>).associateBy { it["name"] as String }
    }
    private fun eachLayout(action: (Language, Map<String, Map<String, Any?>>) -> Unit) {
        for (strategy in listOf("field-based", "array-based")) Context.newBuilder("thc")
            .allowExperimentalOptions(true).option("engine.StaticObjectStorageStrategy", strategy).build().use { context ->
                context.initialize("thc"); context.enter()
                try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null), constructors()) }
                finally { context.leave() }
            }
    }

    @Test fun originalWorkerHasTwoLogicalFieldsAndThreePhysicalFields() = eachLayout { language, constructors ->
        val info = constructors.getValue("VirtualRegWithFormat")
        val fields = CoreFields(info)
        val layout = DataLayout.fromFields(language, info["id"] as String, "VirtualRegWithFormat", fields)
        assertEquals(2, layout.logicalArity); assertEquals(3, layout.arity)
        assertArrayEquals(intArrayOf(0, 2, 3), fields.offsets)
        assertTrue(layout.isLong(0)); assertTrue(layout.isLong(1)); assertFalse(layout.isLong(2))
        val formatInfo = constructors.getValue("II64")
        val format = DataLayout.fromFields(language, formatInfo["id"] as String, "II64", CoreFields(formatInfo)).allocate()
        for (tag in 1L..4L) for (bits in listOf(0L, 1L, 0xffffffffL, 0x100000000L, Long.MIN_VALUE, -1L)) {
            val value = layout.create(arrayOf(tag, bits, format))
            assertEquals(tag, layout.readLong(value, 0)); assertEquals(bits, layout.readLong(value, 1))
            assertSame(format, layout.read(value, 2))
        }
        val proof = fields.logicalProofs[0]
        assertEquals(listOf(CoreKind.LONG, CoreKind.LONG), SumShape.storage(proof).map { it.kind })
        for (tag in listOf(Long.MIN_VALUE, -1L, 0L, 5L, 0x100000001L, Long.MAX_VALUE))
            assertThrows(RuntimeFault::class.java) { SumShape.checkedTag(tag, 4) }
    }

    @Test fun nestedPhysicalOffsetsDoNotEraseLogicalStateOrEmptyTupleIdentity() = eachLayout { language, constructors ->
        val fields = CoreFields(constructors.getValue("NestedBox"))
        assertArrayEquals(intArrayOf(0, 1, 6, 7), fields.offsets)
        val nested = fields.logicalProofs[1]
        val shape = TupleShape(nested, language)
        assertEquals(5, shape.width); assertArrayEquals(intArrayOf(0, 1, 4), shape.offsets)
        val inner = nested.components!![1]
        val innerComponents = inner.components!!
        assertEquals(CoreKind.VOID, innerComponents[0].kind)
        assertTrue(innerComponents[1].isEmptyTuple)
        assertEquals(0, TupleShape.flatten(innerComponents[0]).size)
        assertEquals(0, TupleShape.flatten(innerComponents[1]).size)
        assertEquals(2, TupleShape.flatten(innerComponents[2]).size)
        assertFalse(innerComponents[3].evaluated)
        val scalar = fields.logicalProofs[0]
        val arguments = ArgumentLayout.fromProofs(listOf(scalar, nested, scalar))!!
        assertEquals(3, arguments.logicalArity); assertEquals(7, arguments.physicalArity)
        assertEquals(listOf(0, 1, 6, 7), (0..3).map(arguments::offset))
        val input = TypedInputLayout.create(language, arguments, false)!!
        assertEquals(6, input.prefix(2).reps.size)
        // A real logical mismatch remains a mismatch even at equal physical width.
        val changedInner = inner.let { originalProof -> originalProof.copy(originalProof.kind, originalProof.evaluated, originalProof.present, originalProof.primReps, innerComponents.toMutableList().also { it[1] = innerComponents[0] }, originalProof.vector, originalProof.alternatives, originalProof.tagSlot, originalProof.alternativeSlots) }
        val changed = nested.let { originalProof -> originalProof.copy(originalProof.kind, originalProof.evaluated, originalProof.present, originalProof.primReps, nested.components!!.toMutableList().also { it[1] = changedInner }, originalProof.vector, originalProof.alternatives, originalProof.tagSlot, originalProof.alternativeSlots) }
        val other = ArgumentLayout.fromProofs(listOf(scalar, changed, scalar))!!
        assertEquals(arguments.physicalArity, other.physicalArity)
        assertThrows(RuntimeFault::class.java) { ArgumentLayout.validate(arguments, 0, other, 0, 3) }
    }

    private class Copier(language: Language) : RootNode(language) {
        @Child private var copy = CompactCopyNode(Metrics(false), Array(3) { index ->
            GlobalBinding("failure$index").also { it.initialize(index.toLong()) }
        })
        override fun execute(frame: VirtualFrame): Any? = copy.execute(frame,
            frame.arguments[0] as ManagedCompact, frame.arguments[1], true)
    }

    @Test fun nestedInactiveReferencesArePaddingNotRoots() = eachLayout { language, constructors ->
        val info = constructors.getValue("MixedBox")
        val fields = CoreFields(info)
        assertArrayEquals(intArrayOf(0, 6, 7), fields.offsets)
        val layout = DataLayout.fromFields(language, info["id"] as String, "MixedBox", fields)
        val child = DataLayout(language, "test:Leaf", "Leaf", arrayOf("IntRep")).create(arrayOf(91L))
        val lazy = Thunk(object : RootNode(language) {
            override fun execute(frame: VirtualFrame): Any = error("Nested lazy neighbour was forced")
        }.callTarget, null)
        for (tag in 1L..4L) {
            val value = layout.create(arrayOf(17L, tag, if (tag == 1L) child else null, Long.MIN_VALUE, lazy, 19L, 23L))
            assertEquals(tag != 1L, layout.inactiveSumReference(value, 2))
            assertFalse(layout.inactiveSumReference(value, 4), "The lazy neighbour is outside the nested sum")
            assertArrayEquals(if (tag == 1L) arrayOf(child, lazy) else arrayOf(lazy), ClosureInspection.image(value).pointers)
            assertEquals(0, lazy.state)
        }
        for (tag in listOf(0L, 5L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            val value = layout.create(arrayOf(17L, tag, null, 0L, child, 19L, 23L))
            assertThrows(RuntimeFault::class.java) { layout.inactiveSumReference(value, 2) }
        }
        val copy = Copier(language).callTarget
        for (tag in 1L..4L) {
            val source = layout.create(arrayOf(17L, tag, if (tag == 1L) child else null, Long.MIN_VALUE, child, 19L, 23L))
            val state = Language.currentState()
            val region = ManagedCompact(state.compactRegions, 4096)
            val value = copy.call(region, source) as DataValue
            assertNotSame(source, value)
            for (index in listOf(0, 1, 3, 5, 6)) assertEquals(layout.readLong(source, index), layout.readLong(value, index))
            if (tag == 1L) assertSame(layout.read(value, 2), layout.read(value, 4)) else assertNull(layout.read(value, 2))
            assertNotSame(child, layout.read(value, 4))
            val image = state.compactImages.first(region)
            val bytes = ByteArray(image.availableBytes().toInt())
            image.copyToByteArray(bytes, 0, bytes.size.toLong())
            val block = state.compactImages.allocate(bytes.size.toLong(), ManagedAddress.nullAddress())
            block.copyFromByteArray(bytes, 0, bytes.size.toLong())
            val fixed = state.compactImages.fixup(block, state.heapAddresses.address(value))
            val restored = state.heapAddresses.dereference(fixed.root) as DataValue
            assertEquals(tag, layout.readLong(restored, 1)); assertEquals(Long.MIN_VALUE, layout.readLong(restored, 3))
            if (tag == 1L) assertSame(layout.read(restored, 2), layout.read(restored, 4)) else assertNull(layout.read(restored, 2))
        }
    }
}
