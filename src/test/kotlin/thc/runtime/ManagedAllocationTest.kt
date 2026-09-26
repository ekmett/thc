// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ManagedAllocationTest {
    @Test fun scalarAccessKeepsTheOwnerMonitorThroughTheOperationAndReleasesOnFailure() {
        val storage = ManagedAllocation.mutable(16, 8)
        val failure = IllegalStateException("failed scalar operation")
        val element = assertThrows(IllegalStateException::class.java) {
            storage.accessElement(0, 8, false) {
                assertTrue(Thread.holdsLock(storage))
                throw failure
            }
        }
        assertSame(failure, element)
        assertFalse(Thread.holdsLock(storage))
        val byteRange = assertThrows(IllegalStateException::class.java) {
            storage.accessByteRange(1, 4, true) {
                assertTrue(Thread.holdsLock(storage))
                throw failure
            }
        }
        assertSame(failure, byteRange)
        assertFalse(Thread.holdsLock(storage))
    }

    @Test fun scalarArrayFieldsStayUsableBeforeAndAfterPointerInstallation() {
        val storage = ManagedAllocation.mutable(32, 8)
        val target = ManagedAddress.fromAllocation(storage).plus(24)
        ManagedByteArray.writeGuest(storage, 16, 9)
        assertEquals(9L, ManagedByteArray.readGuest(storage, 16, true))
        storage.writeAddressByteOffset(0, target)
        assertEquals(9L, ManagedByteArray.readGuest(storage, 16, true))
        ManagedByteArray.writeInt32Guest(storage, 5, 0x12345678)
        assertEquals(0x12345678L, ManagedByteArray.readInt32Guest(storage, 5, true))
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.readGuest(storage, 0, true) }
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.writeInt32Guest(storage, 0, 0) }
        assertSame(target, storage.readAddressByteOffset(0))
        assertEquals(9L, storage.readByte(16))
        val copied = ManagedAllocation.mutable(32, 8)
        ManagedByteArray.copyGuest(storage, 16, copied, 16, 8, true)
        assertEquals(0L, ManagedByteArray.compareGuest(storage, 16, copied, 16, 8))
        copied.writeAddressByteOffset(0, target)
        assertSame(target, copied.readAddressByteOffset(0))
        ManagedByteArray.writeIntGuest(storage, 0, 0x1234)
        assertThrows(RuntimeFault::class.java) { storage.readAddressByteOffset(0) }
        assertEquals(0x1234L, ManagedByteArray.readIntGuest(storage, 0))
    }

    @Test fun pinnedContentsAliasesKeepTheSameOwnerAndRejectRawPointerBits() {
        val storage = ManagedAllocation.mutable(32, 8)
        val base = ManagedAddress.fromAllocation(storage)
        val recreated = ManagedAddress.fromGuestByteArray(storage)
        val interior = base.plus(24)
        base.writeWord8(24, 73)
        base.writeAddressElementIndex(1, interior)
        assertTrue(recreated.readAddressElementIndex(1).sameLocation(interior))
        assertEquals(73L, recreated.readAddressElementIndex(1).readWord8(0))
        assertThrows(RuntimeFault::class.java) { base.readWord8(8) }
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.require(storage) }
        assertThrows(RuntimeFault::class.java) { base.cbitsBacking() }
        assertThrows(RuntimeFault::class.java) { base.writeWord8(9, 1) }
        assertTrue(recreated.readAddressElementIndex(1).sameLocation(interior))
        assertEquals(73L, base.readWord8(24))
    }

    @Test fun pointerCellsSurviveAliasesAndWholeByteCopies() {
        val source = ManagedAllocation.mutable(32, 8)
        val destination = ManagedAllocation.mutable(32, 8)
        val referent = ManagedAddress.fromByteArray(byteArrayOf(4))
        source.writeAddressByteOffset(8, referent)
        assertSame(referent, source.readAddressByteOffset(8))
        assertThrows(RuntimeFault::class.java) { source.readByte(10) }
        ManagedByteArray.copyGuest(source, 8, destination, 16, 8, true)
        assertSame(referent, destination.readAddressByteOffset(16))
        assertThrows(RuntimeFault::class.java) { destination.readByte(18) }
        assertThrows(RuntimeFault::class.java) { destination.copyFrom(source, 10, 0, 8) }
        assertSame(referent, destination.readAddressByteOffset(16))
        destination.writeByte(15, 7)
        assertSame(referent, destination.readAddressByteOffset(16))
        assertThrows(RuntimeFault::class.java) { destination.writeByte(23, 7) }
        assertSame(referent, destination.readAddressByteOffset(16))
        destination.fill(16, 8, 0)
        assertThrows(RuntimeFault::class.java) { destination.readAddressByteOffset(16) }
        assertEquals(0L, destination.readByte(23))
        assertSame(referent, source.readAddressByteOffset(8))
    }

    @Test fun overlappingCopySnapshotsCellsAndPartialWritesInvalidate() {
        val storage = ManagedAllocation.mutable(40, 8)
        val first = ManagedAddress.fromByteArray(byteArrayOf(1))
        val second = ManagedAddress.fromByteArray(byteArrayOf(2))
        storage.writeAddressByteOffset(0, first)
        storage.writeAddressByteOffset(16, second)
        storage.copyFrom(storage, 0, 8, 24)
        assertSame(first, storage.readAddressByteOffset(8))
        assertSame(second, storage.readAddressByteOffset(24))
        assertSame(first, storage.readAddressByteOffset(0))
        assertThrows(RuntimeFault::class.java) { storage.fill(12, 1, 0) }
        assertSame(first, storage.readAddressByteOffset(8))
        storage.fill(8, 8, 0)
        assertThrows(RuntimeFault::class.java) { storage.readAddressByteOffset(8) }
        assertSame(second, storage.readAddressByteOffset(24))
    }

    @Test fun resizeKeepsOnlyCompletePointerCellsAndNullIsAReference() {
        val storage = ManagedAllocation.mutable(24, 8)
        val nullAddress = ManagedAddress.nullAddress()
        storage.writeAddressByteOffset(8, nullAddress)
        assertSame(nullAddress, storage.readAddressByteOffset(8))
        assertSame(nullAddress, storage.resized(24).readAddressByteOffset(8))
        assertSame(nullAddress, storage.resized(16).readAddressByteOffset(8))
        assertThrows(RuntimeFault::class.java) { storage.resized(15) }
        assertThrows(RuntimeFault::class.java) { ManagedAllocation.immutable(byteArrayOf(0), 8).writeByte(0, 1) }
        val immutableAddress = ManagedAddress.fromAllocation(ManagedAllocation.immutable(byteArrayOf(0), 8))
        assertFalse(immutableAddress.cbitsWritable())
        assertThrows(RuntimeFault::class.java) { immutableAddress.requireRange(0, 1, writable = true) }
        val narrow = ManagedAllocation.mutable(12, 4)
        narrow.writeAddressByteOffset(4, nullAddress)
        assertSame(nullAddress, narrow.readAddressByteOffset(4))
        assertThrows(RuntimeFault::class.java) { narrow.copyFrom(storage, 0, 0, 4) }
        val nativeExposed = ManagedAllocation.mutable(16, 8)
        assertSame(nativeExposed.rawBytesIfPointerFree(), nativeExposed.exposeToNative())
        assertThrows(RuntimeFault::class.java) { nativeExposed.writeAddressByteOffset(0, nullAddress) }
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.copyGuest(storage, 8, nativeExposed, 0, 8, true) }
        assertEquals(0L, nativeExposed.readByte(0))
        val rawExposed = ManagedAllocation.mutable(16, 8)
        assertEquals(16, rawExposed.rawBytesIfPointerFree().size)
        assertThrows(RuntimeFault::class.java) { rawExposed.writeAddressByteOffset(0, nullAddress) }
    }
}
