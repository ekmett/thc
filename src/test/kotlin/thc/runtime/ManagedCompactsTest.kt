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

class ManagedCompactsTest {
    private class Copier(language: Language) : RootNode(language) {
        private val failures = Array(3) { index -> GlobalBinding("failure$index").also { it.initialize(index.toLong()) } }
        @Child private var copy = CompactCopyNode(Metrics(false), failures)
        override fun execute(frame: VirtualFrame): Any? = copy.execute(frame,
            frame.arguments[0] as ManagedCompact, frame.arguments[1], frame.arguments[2] as Boolean)
    }
    private fun withLanguage(action: (Language) -> Unit) = Context.newBuilder("thc").build().use { context ->
        context.initialize("thc"); context.enter()
        try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
        finally { context.leave() }
    }

    @Test fun graphCopiesSeparateSourcesPreserveRequestedSharingAndReuseRegionMembers() = withLanguage { language ->
        val registry = Language.currentState().compactRegions
        val leaf = DataLayout(language, "test:Leaf", "Leaf", arrayOf("IntRep"))
        val pair = DataLayout(language, "test:Pair", "Pair", arrayOf("LiftedRep", "LiftedRep"))
        val child = leaf.create(arrayOf(17L))
        val source = pair.create(arrayOf(child, child))
        val target = Copier(language).callTarget
        for (sharing in listOf(false, true)) {
            val region = ManagedCompact(registry, 32)
            val copied = target.call(region, source, sharing) as DataValue
            assertNotSame(source, copied)
            val left = pair.read(copied, 0) as DataValue
            val right = pair.read(copied, 1) as DataValue
            assertEquals(sharing, left === right)
            assertNotSame(child, left)
            assertEquals(17L, leaf.readLong(left, 0))
            assertTrue(registry.contains(region, copied)); assertTrue(registry.contains(region, left))
            assertFalse(registry.contains(region, source)); assertFalse(registry.containsAny(child))
            assertSame(copied, target.call(region, copied, sharing))
            val other = ManagedCompact(registry, 32)
            val second = target.call(other, copied, sharing)
            assertNotSame(copied, second); assertFalse(registry.contains(other, copied))
        }
    }

    @Test fun cyclesAndLongListsUsePrivateShellsAndAnExplicitWorkStack() = withLanguage { language ->
        val registry = Language.currentState().compactRegions
        val node = DataLayout(language, "test:Node", "Node", arrayOf("IntRep", "LiftedRep"))
        val end = DataLayout(language, "test:End", "End", emptyArray()).create(emptyArray())
        val source = node.allocate()
        node.initializeLong(source, 0, 91)
        node.initialize(source, 1, source)
        val target = Copier(language).callTarget
        val region = ManagedCompact(registry, 4096)
        val copied = target.call(region, source, true) as DataValue
        assertNotSame(source, copied)
        assertSame(copied, node.read(copied, 1))
        assertEquals(91L, node.readLong(copied, 0))
        assertThrows(RuntimeFault::class.java) { target.call(region, source, false) }
        var list: DataValue = end
        repeat(20_000) { list = node.create(arrayOf(it.toLong(), list)) }
        var result = target.call(region, list, false) as DataValue
        repeat(20_000) { index ->
            assertEquals(19_999L - index, node.readLong(result, 0))
            result = node.read(result, 1) as DataValue
        }
        assertSame(end, result)
        assertTrue(region.size() > 4096)
    }

    @Test fun frozenArraysAndBytesCopyWhileMutablePointersAndPinnedStorageReject() = withLanguage { language ->
        val registry = Language.currentState().compactRegions
        val target = Copier(language).callTarget
        val leaf = DataLayout(language, "test:Leaf", "Leaf", arrayOf("IntRep"))
        val value = leaf.create(arrayOf(7L))
        val region = ManagedCompact(registry, 4096)
        fun rejected(value: Any, reason: Long) {
            assertEquals(reason, assertThrows(GuestException::class.java) { target.call(region, value, true) }.payload)
        }
        val array = ManagedArray.allocate(2, value)
        rejected(array, 2)
        ManagedArray.freeze(array)
        val copy = target.call(region, array, true) as Array<*>
        assertNotSame(array, copy); assertSame(copy[0], copy[1]); assertNotSame(value, copy[0])
        ManagedArray.thaw(array); rejected(array, 2)
        val small = ManagedSmallArray.allocate(2, value)
        rejected(small, 2); ManagedSmallArray.freeze(small)
        val smallCopy = target.call(region, small, true) as SmallArrayStorage
        assertNotSame(small, smallCopy); assertTrue(smallCopy.frozen)
        ManagedSmallArray.thaw(small); rejected(small, 2)
        rejected(ManagedMutVar(value), 2)
        rejected(ManagedAllocation.mutable(8, 8, true), 1)
        val bytes = ManagedAllocation.mutable(8, 8)
        bytes.writeByte(0, 123)
        val compactBytes = target.call(region, bytes, true) as ManagedAllocation
        bytes.writeByte(0, 17)
        assertEquals(123L, compactBytes.readByte(0)); assertFalse(compactBytes.isWritable)
    }

    @Test fun membershipSurvivesAnEscapedValueAndRegionsRejectForeignContextsAndReentry() = withLanguage { language ->
        val registry = Language.currentState().compactRegions
        val layout = DataLayout(language, "test:Leaf", "Leaf", arrayOf("IntRep"))
        val target = Copier(language).callTarget
        val escaped = run {
            val region = ManagedCompact(registry, 1)
            target.call(region, layout.create(arrayOf(111L)), true) as DataValue
        }
        assertEquals(111L, layout.readLong(escaped, 0)); assertTrue(registry.containsAny(escaped))
        val region = ManagedCompact(registry, 1)
        val before = region.size()
        region.resize(8192); assertTrue(region.size() > before)
        region.begin()
        try {
            assertThrows(RuntimeFault::class.java) { region.resize(1) }
            assertThrows(RuntimeFault::class.java) { target.call(region, escaped, true) }
        } finally { region.end() }
        assertThrows(RuntimeFault::class.java) { ManagedCompacts().require(region) }
        assertThrows(RuntimeFault::class.java) { ManagedCompact(registry, -1) }
    }
}
