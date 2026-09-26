// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language

class AggregateHeapStorageTest {
    private val long = mapOf("kind" to "long", "evaluated" to true, "primReps" to listOf("IntRep"))
    private val reference = mapOf("kind" to "data", "evaluated" to false, "primReps" to listOf("BoxedRep (Just Lifted)"))
    private val empty = mapOf("kind" to "unknown", "evaluated" to true, "aggregate" to "unboxed-tuple",
        "primReps" to emptyList<String>(), "components" to emptyList<Any>())
    private val sum = mapOf("kind" to "unknown", "evaluated" to true, "aggregate" to "unboxed-sum",
        "primReps" to listOf("WordRep", "BoxedRep (Just Lifted)"), "tagSlot" to 0,
        "alternatives" to listOf(empty, reference), "alternativeSlots" to listOf(emptyList<Int>(), listOf(1)))
    private fun metadata(vararg fields: Map<String, Any?>): Map<String, Any?> = mapOf(
        "id" to "test:Aggregate", "kind" to "boxed", "arity" to fields.size,
        "fieldTypes" to fields.toList(), "fieldReps" to fields.map { it["primReps"] },
        "fieldLifted" to fields.map { false }, "strictFields" to fields.map { true })
    private fun inLanguage(action: (Language) -> Unit) {
        for (strategy in listOf("field-based", "array-based")) Context.newBuilder("thc")
            .allowExperimentalOptions(true).option("engine.StaticObjectStorageStrategy", strategy).build().use { context ->
                context.initialize("thc"); context.enter()
                try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
                finally { context.leave() }
            }
    }
    @Test fun logicalFieldsFlattenWithoutLosingEmptyFieldsAndExactShapeChecks() = inLanguage { language ->
        val fields = CoreFields(metadata(long, empty, sum, long))
        assertArrayEquals(intArrayOf(0, 1, 1, 3, 4), fields.offsets)
        val layout = DataLayout.fromFields(language, "test:Aggregate", "Aggregate", fields)
        assertEquals(4, layout.logicalArity); assertEquals(4, layout.arity)
        assertEquals(0, layout.logicalWidth(1)); assertEquals(2, layout.logicalWidth(2))
        for (wrong in listOf(metadata(sum) + ("fieldReps" to listOf(listOf("WordRep"))),
            metadata(sum) + ("fieldLifted" to listOf(true)), metadata(sum + ("evaluated" to false)),
            metadata(sum + ("alternativeSlots" to listOf(emptyList<Int>(), listOf(0))))))
            assertThrows(RuntimeFault::class.java) { CoreFields(wrong) }
        assertThrows(UnsupportedCore::class.java) { CoreFields(metadata(long) +
            ("fieldReps" to listOf(listOf("IntRep", "IntRep")))) }
    }
    private class Copier(language: Language) : RootNode(language) {
        @Child private var copy = CompactCopyNode(Metrics(false), Array(3) { i ->
            GlobalBinding("failure$i").also { it.initialize(i.toLong()) } })
        override fun execute(frame: VirtualFrame): Any? = copy.execute(frame,
            frame.arguments[0] as ManagedCompact, frame.arguments[1], true)
    }
    @Test fun inactiveSumReferencesAreNotRootsWhileActivePayloadsRemainLazy() = inLanguage { language ->
        val layout = DataLayout.fromFields(language, "test:Sum", "Sum", CoreFields(metadata(sum)))
        val child = DataLayout(language, "test:Leaf", "Leaf", arrayOf("IntRep")).create(arrayOf(91L))
        val inactive = layout.create(arrayOf(1L, null))
        val active = layout.create(arrayOf(2L, child))
        val thunk = Thunk(object : RootNode(language) {
            override fun execute(frame: VirtualFrame): Any = error("Lazy sum payload was forced")
        }.callTarget, null)
        val lazy = layout.create(arrayOf(2L, thunk))
        assertTrue(layout.inactiveSumReference(inactive, 1)); assertFalse(layout.inactiveSumReference(active, 1))
        assertArrayEquals(emptyArray<Any?>(), ClosureInspection.image(inactive).pointers)
        assertArrayEquals(arrayOf(child), ClosureInspection.image(active).pointers)
        assertArrayEquals(arrayOf(thunk), ClosureInspection.image(lazy).pointers)
        assertEquals(0, thunk.state)
        val copy = Copier(language).callTarget
        for (source in listOf(inactive, active)) {
            val state = Language.currentState()
            val region = ManagedCompact(state.compactRegions, 4096)
            val value = copy.call(region, source) as DataValue
            assertNotSame(source, value)
            assertEquals(layout.readLong(source, 0), layout.readLong(value, 0))
            if (source === inactive) assertNull(layout.read(value, 1)) else {
                val copied = layout.read(value, 1) as DataValue
                assertNotSame(child, copied); assertEquals(91L, copied.layout.readLong(copied, 0))
            }
            val image = state.compactImages.first(region)
            val bytes = ByteArray(image.availableBytes().toInt())
            image.copyToByteArray(bytes, 0, bytes.size.toLong())
            fun restore(bytes: ByteArray): CompactImages.Fixed {
                val block = state.compactImages.allocate(bytes.size.toLong(), ManagedAddress.nullAddress())
                block.copyFromByteArray(bytes, 0, bytes.size.toLong())
                return state.compactImages.fixup(block, state.heapAddresses.address(value))
            }
            val restored = restore(bytes)
            val decoded = state.heapAddresses.dereference(restored.root) as DataValue
            assertEquals(layout.readLong(value, 0), layout.readLong(decoded, 0))
            if (source === inactive) {
                assertNull(layout.read(decoded, 1))
                // Header28, node identity8/tag1/layout8, then the sum tag at45.
                val malformed = bytes.copyOf()
                val buffer = java.nio.ByteBuffer.wrap(malformed)
                buffer.putLong(45, 7L)
                buffer.putLong(malformed.size - 8, java.util.zip.CRC32().also {
                    it.update(malformed, 0, malformed.size - 8)
                }.value)
                assertSame(ManagedAddress.nullAddress(), restore(malformed).root)
            } else assertEquals(91L, (layout.read(decoded, 1) as DataValue).let { it.layout.readLong(it, 0) })
        }
    }
}
