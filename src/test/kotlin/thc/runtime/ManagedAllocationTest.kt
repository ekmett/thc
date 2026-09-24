// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ManagedAllocationTest {
    @Test fun pointerCellsSurviveAliasesAndWholeByteCopies() {
        val source = ManagedAllocation.mutable(32, 8)
        val destination = ManagedAllocation.mutable(32, 8)
        val referent = ManagedAddress.fromByteArray(byteArrayOf(4))
        source.writeAddress(8, referent)
        assertSame(referent, source.readAddress(8))
        assertThrows(RuntimeFault::class.java) { source.readByte(10) }
        destination.copyFrom(source, 8, 16, 8)
        assertSame(referent, destination.readAddress(16))
        assertThrows(RuntimeFault::class.java) { destination.readByte(18) }
        assertThrows(RuntimeFault::class.java) { destination.copyFrom(source, 10, 0, 8) }
        assertSame(referent, destination.readAddress(16))
        destination.writeByte(15, 7)
        assertSame(referent, destination.readAddress(16))
        assertThrows(RuntimeFault::class.java) { destination.writeByte(23, 7) }
        assertSame(referent, destination.readAddress(16))
        destination.fill(16, 8, 0)
        assertThrows(RuntimeFault::class.java) { destination.readAddress(16) }
        assertEquals(0L, destination.readByte(23))
        assertSame(referent, source.readAddress(8))
    }

    @Test fun overlappingCopySnapshotsCellsAndPartialWritesInvalidate() {
        val storage = ManagedAllocation.mutable(40, 8)
        val first = ManagedAddress.fromByteArray(byteArrayOf(1))
        val second = ManagedAddress.fromByteArray(byteArrayOf(2))
        storage.writeAddress(0, first)
        storage.writeAddress(16, second)
        storage.copyFrom(storage, 0, 8, 24)
        assertSame(first, storage.readAddress(8))
        assertSame(second, storage.readAddress(24))
        assertSame(first, storage.readAddress(0))
        assertThrows(RuntimeFault::class.java) { storage.fill(12, 1, 0) }
        assertSame(first, storage.readAddress(8))
        storage.fill(8, 8, 0)
        assertThrows(RuntimeFault::class.java) { storage.readAddress(8) }
        assertSame(second, storage.readAddress(24))
    }

    @Test fun resizeKeepsOnlyCompletePointerCellsAndNullIsAReference() {
        val storage = ManagedAllocation.mutable(24, 8)
        val nullAddress = ManagedAddress.nullAddress()
        storage.writeAddress(8, nullAddress)
        assertSame(nullAddress, storage.readAddress(8))
        assertSame(nullAddress, storage.resized(24).readAddress(8))
        assertSame(nullAddress, storage.resized(16).readAddress(8))
        assertThrows(RuntimeFault::class.java) { storage.resized(15) }
        assertThrows(RuntimeFault::class.java) { ManagedAllocation.immutable(byteArrayOf(0), 8).writeByte(0, 1) }
        val narrow = ManagedAllocation.mutable(12, 4)
        narrow.writeAddress(4, nullAddress)
        assertSame(nullAddress, narrow.readAddress(4))
        assertThrows(RuntimeFault::class.java) { narrow.copyFrom(storage, 0, 0, 4) }
    }
}
