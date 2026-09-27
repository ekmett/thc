// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import jdk.incubator.vector.IntVector
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.primopTestContext

class ManagedAllocationTest {
    @Test fun copyingBytesAndPointerCellsKeepsTheFirstInstalledCall() {
        primopTestContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val root = object : RootNode(language) {
                    var compiledEntries = 0L
                    override fun execute(frame: VirtualFrame): Any {
                        if (CompilerDirectives.inCompiledCode()) compiledEntries++
                        val source = frame.arguments[0] as ManagedAllocation
                        val destination = frame.arguments[1] as ManagedAllocation
                        destination.copyFrom(source, frame.arguments[2] as Long,
                            frame.arguments[3] as Long, frame.arguments[4] as Long)
                        return Unit
                    }
                }
                val pairs = listOf(false, true).map { pinned ->
                    ManagedAllocation.mutable(40, 8, pinned) to ManagedAllocation.mutable(40, 8, pinned)
                }
                val pointer = ManagedAddress.fromByteArray(byteArrayOf(42))
                val target = root.callTarget
                for ((source, destination) in pairs) {
                    source.writeByte(0, 73)
                    target.call(source, destination, 0L, 8L, 8L)
                    source.writeAddressByteOffset(16, pointer)
                    target.call(source, destination, 16L, 24L, 8L)
                    target.call(source, source, 0L, 0L, 0L)
                    source.fill(16, 8, 0); destination.fill(24, 8, 0)
                }
                target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                val runtime = Truffle.getRuntime()
                runtime.javaClass.getMethod("bypassedInstalledCode",
                    Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target)
                for ((source, destination) in pairs) {
                    fun copy(from: ManagedAllocation, to: ManagedAllocation, start: Long, end: Long, count: Long) {
                        val before = root.compiledEntries
                        target.call(from, to, start, end, count)
                        assertEquals(before + 1, root.compiledEntries, "immediate installed copy")
                        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                        assertFalse(Thread.holdsLock(from)); assertFalse(Thread.holdsLock(to))
                    }
                    copy(source, destination, 0, 8, 8)
                    assertEquals(73L, destination.readByte(8))
                    source.writeAddressByteOffset(16, pointer)
                    copy(source, destination, 16, 24, 8)
                    assertSame(pointer, destination.readAddressByteOffset(24))
                    copy(destination, destination, 8, 16, 24)
                    assertEquals(73L, destination.readByte(16))
                    assertSame(pointer, destination.readAddressByteOffset(32))
                    copy(source, destination, 40, 40, 0)
                    assertSame(pointer, destination.readAddressByteOffset(32))
                }
            } finally { context.leave() }
        }
    }

    @Test fun copyFailuresKeepBytesCellsAndMonitorsUnchanged() {
        for (pinned in listOf(false, true)) {
            val source = ManagedAllocation.mutable(32, 8, pinned)
            val destination = ManagedAllocation.mutable(32, 8, pinned)
            val pointer = ManagedAddress.fromByteArray(byteArrayOf(42))
            source.writeAddressByteOffset(8, pointer)
            destination.writeAddressByteOffset(16, pointer)
            destination.writeByte(0, 73)
            for ((from, to, count) in listOf(Triple(9L, 0L, 8L), Triple(0L, 17L, 8L),
                Triple(9L, 0L, 0L), Triple(0L, 17L, 0L), Triple(-1L, 0L, 0L),
                Triple(0L, 33L, 0L), Triple(0L, 0L, -1L), Triple(0L, 24L, 9L))) {
                assertThrows(RuntimeFault::class.java) { destination.copyFrom(source, from, to, count) }
                assertSame(pointer, source.readAddressByteOffset(8))
                assertSame(pointer, destination.readAddressByteOffset(16))
                assertEquals(73L, destination.readByte(0))
                assertFalse(Thread.holdsLock(source)); assertFalse(Thread.holdsLock(destination))
            }
            val exposed = ManagedAllocation.mutable(32, 8, pinned)
            exposed.exposeSegment()
            assertThrows(RuntimeFault::class.java) { exposed.copyFrom(source, 8, 0, 8) }
            assertEquals(0L, exposed.readByte(0))
            assertFalse(Thread.holdsLock(exposed)); assertFalse(Thread.holdsLock(source))
            val immutable = ManagedAllocation.immutable(ByteArray(32), 8)
            assertThrows(RuntimeFault::class.java) { immutable.copyFrom(source, 0, 0, 0) }
            assertFalse(Thread.holdsLock(immutable)); assertFalse(Thread.holdsLock(source))
        }
    }

    @Test fun firstInstalledScalarWritesInvalidateOnlyWholeOverlappingPointerCells() {
        primopTestContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val root = object : RootNode(language) {
                    var compiledEntries = 0L
                    override fun execute(frame: VirtualFrame): Any {
                        if (CompilerDirectives.inCompiledCode()) compiledEntries++
                        val storage = frame.arguments[0] as ManagedAllocation
                        storage.writeNativeScalarByteOffset(frame.arguments[1] as Long,
                            frame.arguments[2] as Int, frame.arguments[3] as Long, true)
                        return Unit
                    }
                }
                val target = root.callTarget
                val storage = listOf(ManagedAllocation.mutable(24, 8), ManagedAllocation.mutable(24, 8, pinned = true))
                val pointer = ManagedAddress.fromByteArray(byteArrayOf(42))
                for (owner in storage) {
                    owner.writeAddressByteOffset(0, pointer)
                    target.call(owner, 0L, 8, 0x11223344L)
                    owner.writeAddressByteOffset(0, pointer)
                    owner.writeAddressByteOffset(8, pointer)
                }
                target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                val runtime = Truffle.getRuntime()
                runtime.javaClass.getMethod("bypassedInstalledCode",
                    Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target)
                for (owner in storage) for (offset in listOf(0L, 8L, 16L)) {
                    val before = root.compiledEntries
                    target.call(owner, offset, 8, 0x11223344L)
                    assertEquals(before + 1, root.compiledEntries, "immediate installed scalar overwrite at $offset")
                    assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                    assertThrows(RuntimeFault::class.java) { owner.readAddressByteOffset(offset) }
                    if (offset == 0L) assertSame(pointer, owner.readAddressByteOffset(8))
                    assertEquals(0x44L, owner.readByte(offset))
                    assertFalse(Thread.holdsLock(owner))
                }
                for (owner in storage) {
                    owner.writeAddressByteOffset(8, pointer)
                    assertThrows(RuntimeFault::class.java) { target.call(owner, 9L, 2, -1L) }
                    assertSame(pointer, owner.readAddressByteOffset(8))
                    assertEquals(0x44L, owner.readByte(16))
                    assertFalse(Thread.holdsLock(owner))
                }
            } finally { context.leave() }
        }
    }

    @Test fun installingPointerCellsKeepsTheNextCompiledDisjointAtomicAndScalarAccess() {
        primopTestContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val root = object : RootNode(language) {
                    var compiledEntries = 0L
                    override fun execute(frame: VirtualFrame): Any {
                        if (CompilerDirectives.inCompiledCode()) compiledEntries++
                        val storage = frame.arguments[0] as ManagedAllocation
                        storage.atomicInt(2, 1, 0, AtomicIntArrayOp.ADD)
                        return ManagedByteArray.readIntGuest(storage, 2)
                    }
                }
                val target = root.callTarget
                val storage = listOf(ManagedAllocation.mutable(24, 8), ManagedAllocation.mutable(24, 8, pinned = true))
                for (owner in storage) {
                    ManagedByteArray.writeIntGuest(owner, 2, 40)
                    assertEquals(41L, target.call(owner))
                }
                target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                val runtime = Truffle.getRuntime()
                runtime.javaClass.getMethod("bypassedInstalledCode",
                    Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target)
                for (owner in storage) {
                    var before = root.compiledEntries
                    assertEquals(42L, target.call(owner))
                    assertEquals(before + 1, root.compiledEntries, "first installed pointer-free atomic/scalar access")
                    assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                    val address = ManagedAddress.fromAllocation(owner).plus(16)
                    owner.writeAddressByteOffset(0, address)
                    before = root.compiledEntries
                    assertEquals(43L, target.call(owner))
                    assertEquals(before + 1, root.compiledEntries, "first installed access after pointer installation")
                    assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                    assertSame(address, owner.readAddressByteOffset(0))
                    assertFalse(Thread.holdsLock(owner))
                }
            } finally { context.leave() }
        }
    }

    @Test fun pointerWritesAndReadsKeepTheFirstInstalledCallAndReferenceIdentity() {
        primopTestContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val root = object : RootNode(language) {
                    var compiledEntries = 0L
                    override fun execute(frame: VirtualFrame): Any {
                        if (CompilerDirectives.inCompiledCode()) compiledEntries++
                        val storage = frame.arguments[0] as ManagedAllocation
                        val address = frame.arguments[2] as ManagedAddress
                        val offset = frame.arguments[1] as Long
                        storage.writeAddressByteOffset(offset, address)
                        return storage.readAddressByteOffset(offset)
                    }
                }
                val target = root.callTarget
                val storage = listOf(ManagedAllocation.mutable(24, 8), ManagedAllocation.mutable(24, 8, pinned = true))
                val addresses = storage.map { ManagedAddress.fromAllocation(it).plus(16) }
                for ((owner, address) in storage.zip(addresses))
                    assertSame(address, target.call(owner, 0L, address))
                target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                val runtime = Truffle.getRuntime()
                runtime.javaClass.getMethod("bypassedInstalledCode",
                    Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target)
                for ((owner, address) in storage.zip(addresses)) {
                    val before = root.compiledEntries
                    assertSame(address, target.call(owner, 8L, address))
                    assertEquals(before + 1, root.compiledEntries, "first installed pointer write/read")
                    assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                    assertSame(address, owner.readAddressByteOffset(0))
                    assertSame(address, owner.readAddressByteOffset(8))
                    assertThrows(RuntimeFault::class.java) { owner.writeAddressByteOffset(4, address) }
                    assertFalse(Thread.holdsLock(owner))
                    assertSame(address, owner.readAddressByteOffset(0))
                    assertSame(address, owner.readAddressByteOffset(8))
                }
            } finally { context.leave() }
        }
    }

    @Test fun heapAndNativeByteAccessKeepTheFirstInstalledCallAndStorageIdentity() {
        primopTestContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val root = object : RootNode(language) {
                    var compiledEntries = 0L
                    override fun execute(frame: VirtualFrame): Any {
                        if (CompilerDirectives.inCompiledCode()) compiledEntries++
                        val storage = frame.arguments[0] as ManagedAllocation
                        val index = frame.arguments[1] as Long
                        val value = frame.arguments[2] as Long
                        storage.writeByte(index, value)
                        return storage.readByte(index)
                    }
                }
                val heap = ManagedAllocation.mutable(16, 8)
                val native = ManagedAllocation.mutable(16, 8, pinned = true)
                val heapAlias = heap.rawBytesIfPointerFree()
                val nativeAlias = native.nativeSegment()!!
                val target = root.callTarget
                for (storage in listOf(heap, native)) for (value in listOf(0L, 127L, 128L, 255L))
                    assertEquals(value, target.call(storage, 7L, value))
                target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                val runtime = Truffle.getRuntime()
                runtime.javaClass.getMethod("bypassedInstalledCode",
                    Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target)
                for (storage in listOf(heap, native)) {
                    val before = root.compiledEntries
                    assertEquals(0xa5L, target.call(storage, 7L, 0x1a5L))
                    assertEquals(before + 1, root.compiledEntries, "first installed byte access")
                    assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                }
                assertSame(heapAlias, heap.rawBytesIfPointerFree())
                assertEquals(0xa5.toByte(), heapAlias[7])
                assertSame(nativeAlias, native.nativeSegment())
                assertEquals(0xa5.toByte(), nativeAlias.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)[7])
                assertThrows(RuntimeFault::class.java) { heap.readByte(16) }
                assertThrows(RuntimeFault::class.java) { native.writeByte(-1, 0) }
                assertEquals(0xa5L, native.readByte(7))
            } finally { context.leave() }
        }
    }

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
        ManagedByteArray.writeGuest(storage, 16, (9).toInt())
        assertEquals(9L, ManagedByteArray.readGuest(storage, 16, true).toLong())
        storage.writeAddressByteOffset(0, target)
        assertEquals(9L, ManagedByteArray.readGuest(storage, 16, true).toLong())
        ManagedByteArray.writeInt32Guest(storage, 5, (0x12345678).toInt())
        assertEquals(0x12345678L, Integer.toUnsignedLong(ManagedByteArray.readInt32Guest(storage, 5, true)))
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.readGuest(storage, 0, true).toLong() }
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.writeInt32Guest(storage, 0, (0).toInt()) }
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

    @Test fun vectorAccessKeepsTheOwnerMonitorThroughTheOperationAndReleasesOnFailure() {
        val storage = ManagedAllocation.mutable(32, 8)
        val failure = IllegalStateException("failed vector operation")
        for (scalarOffset in listOf(false, true)) for (width in listOf(4, 8))
            for (writable in listOf(false, true)) {
                val thrown = assertThrows(IllegalStateException::class.java) {
                    storage.accessVector(1, scalarOffset, width, writable) {
                        assertTrue(Thread.holdsLock(storage))
                        throw failure
                    }
                }
                assertSame(failure, thrown)
                assertFalse(Thread.holdsLock(storage))
                assertThrows(RuntimeFault::class.java) {
                    storage.accessVector(Long.MAX_VALUE, scalarOffset, width, writable) {
                        fail<Unit>("Invalid vector range reached the operation")
                    }
                }
                assertFalse(Thread.holdsLock(storage))
                assertEquals(32L, storage.accessVector(0, scalarOffset, width, writable) {
                    assertTrue(Thread.holdsLock(storage))
                    it.byteSize()
                })
                assertFalse(Thread.holdsLock(storage))
            }
    }

    @Test fun vectorAccessPreservesPointerOverlapAndImmutableWriteChecks() {
        val storage = ManagedAllocation.mutable(32, 8)
        val target = ManagedAddress.fromAllocation(storage).plus(24)
        val vector = IntVector.broadcast(IntVector.SPECIES_128, 7)
        storage.writeAddressByteOffset(0, target)
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.readInt32VectorGuest(storage, 0, false) }
        assertFalse(Thread.holdsLock(storage))
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.writeInt32VectorGuest(storage, 1, vector, true) }
        assertFalse(Thread.holdsLock(storage))
        assertSame(target, storage.readAddressByteOffset(0))
        ManagedByteArray.writeInt32VectorGuest(storage, 1, vector, false)
        assertSame(target, storage.readAddressByteOffset(0))
        assertEquals(vector, ManagedByteArray.readInt32VectorGuest(storage, 1, false))
        ManagedByteArray.writeInt32VectorGuest(storage, 0, vector, false)
        assertThrows(RuntimeFault::class.java) { storage.readAddressByteOffset(0) }
        assertEquals(vector, ManagedByteArray.readInt32VectorGuest(storage, 0, false))
        val immutable = ManagedAllocation.immutable(ByteArray(16), 8)
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.writeInt32VectorGuest(immutable, 0, vector, false) }
        assertFalse(Thread.holdsLock(immutable))
        assertEquals(0, ManagedByteArray.readInt32VectorGuest(immutable, 0, false).lane(0))
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
