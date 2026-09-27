// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.FrameSlotKind
import com.oracle.truffle.api.frame.VirtualFrame
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.runtime.*

class ColdFunctionFrameTest {
    private fun root(proof: CoreRepresentation, asyncStrict: Boolean = false,
                     initial: FrameSlotKind = FrameSlotKind.Illegal): Pair<FunctionRoot, Int> {
        val layout = FrameLayout()
        val argument = layout.bind("argument")
        val descriptor = layout.build()
        descriptor.setSlotKind(argument, initial)
        val body = object : Expr() {
            override fun execute(frame: VirtualFrame): Any? = error("Preparation must not execute the body")
        }
        return FunctionRoot(null, descriptor, "cold frame", null, intArrayOf(), intArrayOf(argument),
            intArrayOf(0), body, Metrics(true), arrayOf(proof), entryStrict = booleanArrayOf(asyncStrict),
            enableAsync = asyncStrict) to argument
    }

    @Test fun exactArgumentCarriersAreEstablishedBeforeTargetPublicationWithoutExecutingCode() {
        val expected = listOf(CoreKind.LONG to FrameSlotKind.Long, CoreKind.FLOAT to FrameSlotKind.Float,
            CoreKind.DOUBLE to FrameSlotKind.Double, CoreKind.DATA to FrameSlotKind.Object,
            CoreKind.CLOSURE to FrameSlotKind.Object, CoreKind.ADDRESS to FrameSlotKind.Object)
        for ((kind, slotKind) in expected) {
            val (root, slot) = root(CoreRepresentation(kind, evaluated = true, present = true))
            assertEquals(slotKind, root.frameDescriptor.getSlotKind(slot))
            val target = root.callTarget
            assertSame(root, target.rootNode)
            assertEquals(slotKind, target.rootNode.frameDescriptor.getSlotKind(slot))
        }
    }

    @Test fun unknownLazyAndAsynchronouslyForcedInputsRetainDynamicStorage() {
        for (proof in listOf(CoreRepresentation.UNKNOWN, CoreRepresentation(CoreKind.DATA),
            CoreRepresentation(CoreKind.OBJECT))) {
            val (root, slot) = root(proof)
            assertEquals(FrameSlotKind.Illegal, root.frameDescriptor.getSlotKind(slot))
        }
        val (root, slot) = root(CoreRepresentation(CoreKind.LONG, evaluated = true), asyncStrict = true)
        assertEquals(FrameSlotKind.Illegal, root.frameDescriptor.getSlotKind(slot))
        val frame = Truffle.getRuntime().createVirtualFrame(arrayOf(0L, Any()), root.frameDescriptor)
        // The entry can hold a suspended/lazy value before the strict force.
        root.buildFrame(frame.arguments, frame)
        assertSame(frame.arguments[1], FrameAccess.read(frame, slot))
    }

    @Test fun preparedScalarSlotsStillWidenMonotonicallyAndDoNotNarrowExistingObjectSlots() {
        val proof = CoreRepresentation(CoreKind.LONG, evaluated = true, present = true)
        val (root, slot) = root(proof)
        val first = Truffle.getRuntime().createVirtualFrame(arrayOf(0L, Long.MIN_VALUE), root.frameDescriptor)
        root.buildFrame(first.arguments, first)
        val other = Truffle.getRuntime().createVirtualFrame(emptyArray(), root.frameDescriptor)
        val marker = Any()
        FrameAccess.writeObject(other, slot, marker)
        assertEquals(Long.MIN_VALUE, FrameAccess.read(first, slot))
        root.buildFrame(arrayOf(0L, Long.MAX_VALUE), first)
        assertEquals(FrameSlotKind.Object, root.frameDescriptor.getSlotKind(slot))
        assertEquals(Long.MAX_VALUE, FrameAccess.read(first, slot))
        assertSame(marker, FrameAccess.read(other, slot))
        val (alreadyWide, wideSlot) = root(proof, initial = FrameSlotKind.Object)
        assertEquals(FrameSlotKind.Object, alreadyWide.frameDescriptor.getSlotKind(wideSlot))
    }

    @Test fun denseSnapshotKindsComeFromTheActualPacketLayoutBeforeAnyLoan() {
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val layout = FrameLayout()
                val slots = IntArray(2) { layout.bind("snapshot $it") }
                val destination = layout.bind("destination")
                val packet = HandoffLayout(language, 0, listOf("long", "reference"))
                val entry = HandoffEntry(language, packet, true, slots, destination)
                val body = object : Expr() {
                    override fun execute(frame: VirtualFrame): Any? = error("No guest entry during preparation")
                }
                val root = FunctionRoot(language, layout.build(), "dense cold frame", null, intArrayOf(),
                    intArrayOf(), intArrayOf(), body, Metrics(true), handoff = entry)
                assertEquals(FrameSlotKind.Long, root.frameDescriptor.getSlotKind(slots[0]))
                assertEquals(FrameSlotKind.Object, root.frameDescriptor.getSlotKind(slots[1]))
                assertEquals(FrameSlotKind.Long, root.frameDescriptor.getSlotKind(destination))
                assertEquals(0L, entry.state().calls)
                assertEquals(0, entry.state().arguments.depth)
            } finally { context.leave() }
        }
    }
}
