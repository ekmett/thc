// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.ref.WeakReference

/** Model/contract controls; authentic compiled consumers live in PinnedPointerCellsTest. */
class MutableByteArrayContentsTest {
    private val reference = CoreRepresentation(CoreKind.OBJECT, evaluated = true, present = true,
        primReps = listOf("BoxedRep (Just Unlifted)"))
    private val address = CoreRepresentation(CoreKind.ADDRESS, evaluated = true, present = true,
        primReps = listOf("AddrRep"))
    private val operation = PinnedMemoryOp.MUTABLE_CONTENTS

    @Test fun exactUnliftedInputAndAddressResultDoNotAdmitOtherLogicalCarriers() {
        assertSame(operation, PinnedMemoryOp.named("mutableByteArrayContents#"))
        assertDoesNotThrow { operation.validate(listOf(reference), listOf(false), address) }
        for (bad in listOf(CoreRepresentation.UNKNOWN, reference.copy(kind = CoreKind.DATA),
                reference.copy(primReps = listOf("BoxedRep (Just Lifted)")),
                reference.copy(primReps = listOf("BoxedRep Nothing")),
                reference.copy(components = emptyList()), address))
            assertThrows(RuntimeFault::class.java) { operation.validate(listOf(bad), listOf(false), address) }
        for (bad in listOf(CoreRepresentation.UNKNOWN, reference, address.copy(kind = CoreKind.OBJECT),
                address.copy(primReps = listOf("WordRep")), address.copy(components = listOf(address))))
            assertThrows(RuntimeFault::class.java) { operation.validate(listOf(reference), listOf(false), bad) }
        for (flags in listOf(emptyList(), listOf(true), listOf(0L), listOf(false, false)))
            assertThrows(RuntimeFault::class.java) { operation.validate(listOf(reference), flags, address) }
        for (args in listOf(emptyList(), listOf(reference, reference)))
            assertThrows(RuntimeFault::class.java) { operation.validate(args, List(args.size) { false }, address) }
    }

    private fun contents(value: Any?): ManagedAddress {
        var reads = 0
        val operand = object : Expr() {
            init { representation = reference }
            override fun execute(frame: VirtualFrame): Any? { reads++; return value }
        }
        val expression = PinnedMemoryExpression(operation, address, arrayOf(operand))
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), FrameDescriptor.newBuilder().build())
        val result = expression.executeAddress(frame)
        assertEquals(1, reads)
        assertTrue(result.sameLocation(expression.execute(frame) as ManagedAddress))
        assertEquals(2, reads)
        return result
    }

    @Test fun mutableContentsKeepsBackingIdentityAndBidirectionalWritesWithoutFreezing() {
        for (array in listOf(ByteArray(24), PinnedMemory.allocate(24, 8))) {
            val first = contents(array)
            val alias = contents(array)
            assertTrue(first.sameLocation(alias))
            assertFalse(first.sameLocation(contents(ByteArray(24))))
            ManagedByteArray.writeGuest(array, 0, 0x1e9)
            assertEquals(0xe9L, alias.readWord8(0))
            first.plus(7).writeWord8(0, 0x1ff)
            assertEquals(255L, ManagedByteArray.readGuest(array, 7, true))
            val frozen = ManagedByteArray.freezeGuest(array)
            assertSame(array, frozen)
            assertTrue(first.sameLocation(ManagedAddress.fromGuestByteArray(frozen)))
            // Unsafe freeze does not copy or change physical backing identity.
            assertEquals(255L, alias.readWord8(7))
            assertThrows(RuntimeFault::class.java) { first.plus(-1) }
            assertThrows(RuntimeFault::class.java) { first.plus(Long.MAX_VALUE) }
            assertThrows(RuntimeFault::class.java) { first.plus(24).readWord8(0) }
        }
        val empty = contents(PinnedMemory.allocate(0, 1))
        assertTrue(empty.sameLocation(empty.plus(0)))
        assertThrows(RuntimeFault::class.java) { empty.readWord8(0) }
        for (value in listOf(null, Unit, 0L, ManagedAddress.nullAddress()))
            assertThrows(RuntimeFault::class.java) { contents(value) }
    }

    @Test fun contentsRetainsPointerOwnershipWithoutExposingPointerBits() {
        fun allocate(): Pair<WeakReference<ManagedAllocation>, ManagedAddress> {
            val array = PinnedMemory.allocate(24, 8)
            val base = contents(array)
            val target = ManagedAddress.fromByteArray(byteArrayOf(73))
            PinnedMemory.writeAddressArray(array, 0, target)
            assertSame(target, base.readAddressElementIndex(0))
            assertThrows(RuntimeFault::class.java) { base.readWord8(0) }
            assertThrows(RuntimeFault::class.java) { base.writeWord8(0, 1) }
            assertSame(target, base.readAddressElementIndex(0))
            base.writeWord8(16, 91)
            return WeakReference(array) to base.plus(16)
        }
        val (owner, survivor) = allocate()
        System.gc()
        assertNotNull(owner.get(), "A surviving Addr# strongly retains its original allocation")
        assertEquals(91L, survivor.readWord8(0))
        assertEquals(73L, survivor.plus(-16).readAddressElementIndex(0).readWord8(0))
    }
}
