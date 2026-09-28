// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import jdk.incubator.vector.IntVector;
import org.junit.jupiter.api.Test;
import thc.Language;
import kotlin.Unit;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.PrimopTestContextKt.primopTestContext;

class ManagedAllocationTest {
    private void valid(RootCallTarget target) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
    }
    private void install(RootCallTarget target) throws Exception {
        target.getClass().getMethod("compile", boolean.class).invoke(target, true);
        valid(target);
        var runtime = Truffle.getRuntime();
        runtime.getClass()
            .getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"))
            .invoke(runtime, target);
    }
    @Test
    void copyingBytesAndPointerCellsKeepsTheFirstInstalledCall() throws Exception {
        try (var context = primopTestContext()) {
            context.initialize("thc");
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                class CopyRoot extends RootNode {
                    long compiledEntries;
                    CopyRoot() {
                        super(language);
                    }
                    @Override
                    public Object execute(VirtualFrame frame) {
                        if (CompilerDirectives.inCompiledCode())
                            compiledEntries++;
                        var source = (ManagedAllocation) frame.getArguments()[0];
                        var destination = (ManagedAllocation) frame.getArguments()[1];
                        destination.copyFrom(source, (Long) frame.getArguments()[2], (Long) frame.getArguments()[3],
                            (Long) frame.getArguments()[4]);
                        return Unit.INSTANCE;
                    }
                    void copy(ManagedAllocation from, ManagedAllocation to, long start, long end, long count)
                        throws Exception {
                        long before = compiledEntries;
                        getCallTarget().call(from, to, start, end, count);
                        assertEquals(before + 1, compiledEntries, "immediate installed copy");
                        valid(getCallTarget());
                        assertFalse(Thread.holdsLock(from));
                        assertFalse(Thread.holdsLock(to));
                    }
                }
                var root = new CopyRoot();
                var pairs = new ArrayList<ManagedAllocation[]>();
                for (boolean pinned : new boolean[] {false, true})
                    pairs.add(new ManagedAllocation[] {
                        ManagedAllocation.mutable(40, 8, pinned), ManagedAllocation.mutable(40, 8, pinned)});
                var pointer = ManagedAddress.fromByteArray(new byte[] {42});
                var target = root.getCallTarget();
                for (var pair : pairs) {
                    var source = pair[0];
                    var destination = pair[1];
                    source.writeByte(0, 73);
                    target.call(source, destination, 0L, 8L, 8L);
                    source.writeAddressByteOffset(16, pointer);
                    target.call(source, destination, 16L, 24L, 8L);
                    target.call(source, source, 0L, 0L, 0L);
                    source.fill(16, 8, 0);
                    destination.fill(24, 8, 0);
                }
                install(target);
                for (var pair : pairs) {
                    var source = pair[0];
                    var destination = pair[1];
                    root.copy(source, destination, 0, 8, 8);
                    assertEquals(73L, destination.readByte(8));
                    source.writeAddressByteOffset(16, pointer);
                    root.copy(source, destination, 16, 24, 8);
                    assertSame(pointer, destination.readAddressByteOffset(24));
                    root.copy(destination, destination, 8, 16, 24);
                    assertEquals(73L, destination.readByte(16));
                    assertSame(pointer, destination.readAddressByteOffset(32));
                    root.copy(source, destination, 40, 40, 0);
                    assertSame(pointer, destination.readAddressByteOffset(32));
                }
            } finally {
                context.leave();
            }
        }
    }
    @Test
    void copyFailuresKeepBytesCellsAndMonitorsUnchanged() {
        for (boolean pinned : new boolean[] {false, true}) {
            var source = ManagedAllocation.mutable(32, 8, pinned);
            var destination = ManagedAllocation.mutable(32, 8, pinned);
            var pointer = ManagedAddress.fromByteArray(new byte[] {42});
            source.writeAddressByteOffset(8, pointer);
            destination.writeAddressByteOffset(16, pointer);
            destination.writeByte(0, 73);
            for (var range : new long[][] {
                     {9, 0, 8}, {0, 17, 8}, {9, 0, 0}, {0, 17, 0}, {-1, 0, 0}, {0, 33, 0}, {0, 0, -1}, {0, 24, 9}}) {
                assertThrows(RuntimeFault.class, () -> destination.copyFrom(source, range[0], range[1], range[2]));
                assertSame(pointer, source.readAddressByteOffset(8));
                assertSame(pointer, destination.readAddressByteOffset(16));
                assertEquals(73L, destination.readByte(0));
                assertFalse(Thread.holdsLock(source));
                assertFalse(Thread.holdsLock(destination));
            }
            var exposed = ManagedAllocation.mutable(32, 8, pinned);
            exposed.exposeSegment();
            assertThrows(RuntimeFault.class, () -> exposed.copyFrom(source, 8, 0, 8));
            assertEquals(0L, exposed.readByte(0));
            assertFalse(Thread.holdsLock(exposed));
            assertFalse(Thread.holdsLock(source));
            var immutable = ManagedAllocation.immutable(new byte[32], 8);
            assertThrows(RuntimeFault.class, () -> immutable.copyFrom(source, 0, 0, 0));
            assertFalse(Thread.holdsLock(immutable));
            assertFalse(Thread.holdsLock(source));
        }
    }
    @Test
    void firstInstalledScalarWritesInvalidateOnlyWholeOverlappingPointerCells() throws Exception {
        try (var context = primopTestContext()) {
            context.initialize("thc");
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                class ScalarRoot extends RootNode {
                    long compiledEntries;
                    ScalarRoot() {
                        super(language);
                    }
                    @Override
                    public Object execute(VirtualFrame frame) {
                        if (CompilerDirectives.inCompiledCode())
                            compiledEntries++;
                        var storage = (ManagedAllocation) frame.getArguments()[0];
                        storage.writeNativeScalarByteOffset((Long) frame.getArguments()[1],
                            (Integer) frame.getArguments()[2], (Long) frame.getArguments()[3], true);
                        return Unit.INSTANCE;
                    }
                }
                var root = new ScalarRoot();
                var target = root.getCallTarget();
                var storage = List.of(ManagedAllocation.mutable(24, 8), ManagedAllocation.mutable(24, 8, true));
                var pointer = ManagedAddress.fromByteArray(new byte[] {42});
                for (var owner : storage) {
                    owner.writeAddressByteOffset(0, pointer);
                    target.call(owner, 0L, 8, 0x11223344L);
                    owner.writeAddressByteOffset(0, pointer);
                    owner.writeAddressByteOffset(8, pointer);
                }
                install(target);
                for (var owner : storage)
                    for (long offset : new long[] {0, 8, 16}) {
                        long before = root.compiledEntries;
                        target.call(owner, offset, 8, 0x11223344L);
                        assertEquals(
                            before + 1, root.compiledEntries, "immediate installed scalar overwrite at " + offset);
                        valid(target);
                        assertThrows(RuntimeFault.class, () -> owner.readAddressByteOffset(offset));
                        if (offset == 0)
                            assertSame(pointer, owner.readAddressByteOffset(8));
                        assertEquals(0x44L, owner.readByte(offset));
                        assertFalse(Thread.holdsLock(owner));
                    }
                for (var owner : storage) {
                    owner.writeAddressByteOffset(8, pointer);
                    assertThrows(RuntimeFault.class, () -> target.call(owner, 9L, 2, -1L));
                    assertSame(pointer, owner.readAddressByteOffset(8));
                    assertEquals(0x44L, owner.readByte(16));
                    assertFalse(Thread.holdsLock(owner));
                }
            } finally {
                context.leave();
            }
        }
    }
    @Test
    void installingPointerCellsKeepsTheNextCompiledDisjointAtomicAndScalarAccess() throws Exception {
        try (var context = primopTestContext()) {
            context.initialize("thc");
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                class AtomicRoot extends RootNode {
                    long compiledEntries;
                    AtomicRoot() {
                        super(language);
                    }
                    @Override
                    public Object execute(VirtualFrame frame) {
                        if (CompilerDirectives.inCompiledCode())
                            compiledEntries++;
                        var storage = (ManagedAllocation) frame.getArguments()[0];
                        storage.atomicInt(2, 1, 0, AtomicIntArrayOp.ADD);
                        return ManagedByteArray.readIntGuest(storage, 2);
                    }
                }
                var root = new AtomicRoot();
                var target = root.getCallTarget();
                var storage = List.of(ManagedAllocation.mutable(24, 8), ManagedAllocation.mutable(24, 8, true));
                for (var owner : storage) {
                    ManagedByteArray.writeIntGuest(owner, 2, 40);
                    assertEquals(41L, target.call(owner));
                }
                install(target);
                for (var owner : storage) {
                    long before = root.compiledEntries;
                    assertEquals(42L, target.call(owner));
                    assertEquals(before + 1, root.compiledEntries, "first installed pointer-free atomic/scalar access");
                    valid(target);
                    var address = ManagedAddress.fromAllocation(owner).plus(16);
                    owner.writeAddressByteOffset(0, address);
                    before = root.compiledEntries;
                    assertEquals(43L, target.call(owner));
                    assertEquals(before + 1, root.compiledEntries, "first installed access after pointer installation");
                    valid(target);
                    assertSame(address, owner.readAddressByteOffset(0));
                    assertFalse(Thread.holdsLock(owner));
                }
            } finally {
                context.leave();
            }
        }
    }
    @Test
    void pointerWritesAndReadsKeepTheFirstInstalledCallAndReferenceIdentity() throws Exception {
        try (var context = primopTestContext()) {
            context.initialize("thc");
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                class PointerRoot extends RootNode {
                    long compiledEntries;
                    PointerRoot() {
                        super(language);
                    }
                    @Override
                    public Object execute(VirtualFrame frame) {
                        if (CompilerDirectives.inCompiledCode())
                            compiledEntries++;
                        var storage = (ManagedAllocation) frame.getArguments()[0];
                        var address = (ManagedAddress) frame.getArguments()[2];
                        long offset = (Long) frame.getArguments()[1];
                        storage.writeAddressByteOffset(offset, address);
                        return storage.readAddressByteOffset(offset);
                    }
                }
                var root = new PointerRoot();
                var target = root.getCallTarget();
                var storage = List.of(ManagedAllocation.mutable(24, 8), ManagedAllocation.mutable(24, 8, true));
                var addresses = new ArrayList<ManagedAddress>();
                for (var owner : storage) addresses.add(ManagedAddress.fromAllocation(owner).plus(16));
                for (int i = 0; i < storage.size(); i++)
                    assertSame(addresses.get(i), target.call(storage.get(i), 0L, addresses.get(i)));
                install(target);
                for (int i = 0; i < storage.size(); i++) {
                    var owner = storage.get(i);
                    var address = addresses.get(i);
                    long before = root.compiledEntries;
                    assertSame(address, target.call(owner, 8L, address));
                    assertEquals(before + 1, root.compiledEntries, "first installed pointer write/read");
                    valid(target);
                    assertSame(address, owner.readAddressByteOffset(0));
                    assertSame(address, owner.readAddressByteOffset(8));
                    assertThrows(RuntimeFault.class, () -> owner.writeAddressByteOffset(4, address));
                    assertFalse(Thread.holdsLock(owner));
                    assertSame(address, owner.readAddressByteOffset(0));
                    assertSame(address, owner.readAddressByteOffset(8));
                }
            } finally {
                context.leave();
            }
        }
    }
    @Test
    void heapAndNativeByteAccessKeepTheFirstInstalledCallAndStorageIdentity() throws Exception {
        try (var context = primopTestContext()) {
            context.initialize("thc");
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                class ByteRoot extends RootNode {
                    long compiledEntries;
                    ByteRoot() {
                        super(language);
                    }
                    @Override
                    public Object execute(VirtualFrame frame) {
                        if (CompilerDirectives.inCompiledCode())
                            compiledEntries++;
                        var storage = (ManagedAllocation) frame.getArguments()[0];
                        long index = (Long) frame.getArguments()[1], value = (Long) frame.getArguments()[2];
                        storage.writeByte(index, value);
                        return storage.readByte(index);
                    }
                }
                var root = new ByteRoot();
                var heap = ManagedAllocation.mutable(16, 8);
                var nativeStorage = ManagedAllocation.mutable(16, 8, true);
                var heapAlias = heap.rawBytesIfPointerFree();
                var nativeAlias = Objects.requireNonNull(nativeStorage.nativeSegment());
                var target = root.getCallTarget();
                for (var storage : List.of(heap, nativeStorage))
                    for (long value : new long[] {0, 127, 128, 255})
                        assertEquals(value, target.call(storage, 7L, value));
                install(target);
                for (var storage : List.of(heap, nativeStorage)) {
                    long before = root.compiledEntries;
                    assertEquals(0xa5L, target.call(storage, 7L, 0x1a5L));
                    assertEquals(before + 1, root.compiledEntries, "first installed byte access");
                    valid(target);
                }
                assertSame(heapAlias, heap.rawBytesIfPointerFree());
                assertEquals((byte) 0xa5, heapAlias[7]);
                assertSame(nativeAlias, nativeStorage.nativeSegment());
                assertEquals((byte) 0xa5, nativeAlias.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)[7]);
                assertThrows(RuntimeFault.class, () -> heap.readByte(16));
                assertThrows(RuntimeFault.class, () -> nativeStorage.writeByte(-1, 0));
                assertEquals(0xa5L, nativeStorage.readByte(7));
            } finally {
                context.leave();
            }
        }
    }
    @Test
    void scalarAccessKeepsTheOwnerMonitorThroughTheOperationAndReleasesOnFailure() {
        var storage = ManagedAllocation.mutable(16, 8);
        var failure = new IllegalStateException("failed scalar operation");var element=assertThrows(IllegalStateException.class,()->{synchronized(storage){storage.elementSegment(0,8,false);assertTrue(Thread.holdsLock(storage));throw failure;
    }
});
assertSame(failure, element);
assertFalse(Thread.holdsLock(storage));var byteRange=assertThrows(IllegalStateException.class,()->{synchronized(storage){storage.byteRangeSegment(1,4,true);assertTrue(Thread.holdsLock(storage));throw failure;
}
});
assertSame(failure, byteRange);
assertFalse(Thread.holdsLock(storage));
}
@Test
void scalarArrayFieldsStayUsableBeforeAndAfterPointerInstallation() {
    var storage = ManagedAllocation.mutable(32, 8);
    var target = ManagedAddress.fromAllocation(storage).plus(24);
    ManagedByteArray.writeGuest(storage, 16, 9);
    assertEquals(9L, (long) ManagedByteArray.readGuest(storage, 16, true));
    storage.writeAddressByteOffset(0, target);
    assertEquals(9L, (long) ManagedByteArray.readGuest(storage, 16, true));
    ManagedByteArray.writeInt32Guest(storage, 5, 0x12345678);
    assertEquals(0x12345678L, Integer.toUnsignedLong(ManagedByteArray.readInt32Guest(storage, 5, true)));
    assertThrows(RuntimeFault.class, () -> ManagedByteArray.readGuest(storage, 0, true));
    assertThrows(RuntimeFault.class, () -> ManagedByteArray.writeInt32Guest(storage, 0, 0));
    assertSame(target, storage.readAddressByteOffset(0));
    assertEquals(9L, storage.readByte(16));
    var copied = ManagedAllocation.mutable(32, 8);
    ManagedByteArray.copyGuest(storage, 16, copied, 16, 8, true);
    assertEquals(0L, ManagedByteArray.compareGuest(storage, 16, copied, 16, 8));
    copied.writeAddressByteOffset(0, target);
    assertSame(target, copied.readAddressByteOffset(0));
    ManagedByteArray.writeIntGuest(storage, 0, 0x1234);
    assertThrows(RuntimeFault.class, () -> storage.readAddressByteOffset(0));
    assertEquals(0x1234L, ManagedByteArray.readIntGuest(storage, 0));
}
@Test
void vectorAccessKeepsTheOwnerMonitorThroughTheOperationAndReleasesOnFailure() {
    var storage = ManagedAllocation.mutable(32, 8);
    var failure = new IllegalStateException("failed vector operation");
    for (boolean scalarOffset : new boolean[] {false, true})
        for (int width : new int[] {4, 8})
            for (boolean writable : new boolean[] {false, true}) {
            var thrown=assertThrows(IllegalStateException.class,()->{synchronized(storage){storage.vectorSegment(1,scalarOffset,width,writable,16);assertTrue(Thread.holdsLock(storage));throw failure;
            }
});
assertSame(failure, thrown);
assertFalse(Thread.holdsLock(storage));assertThrows(RuntimeFault.class,()->{synchronized(storage){storage.vectorSegment(Long.MAX_VALUE,scalarOffset,width,writable,16);fail("Invalid vector range reached the operation");
}
});
assertFalse(Thread.holdsLock(storage));
long size;
synchronized (storage) {
    var segment = storage.vectorSegment(0, scalarOffset, width, writable, 16);
    assertTrue(Thread.holdsLock(storage));
    size = segment.byteSize();
}
assertEquals(32L, size);
assertFalse(Thread.holdsLock(storage));
}
}
@Test
void vectorAccessPreservesPointerOverlapAndImmutableWriteChecks() {
    var storage = ManagedAllocation.mutable(32, 8);
    var target = ManagedAddress.fromAllocation(storage).plus(24);
    var vector = IntVector.broadcast(IntVector.SPECIES_128, 7);
    storage.writeAddressByteOffset(0, target);
    assertThrows(RuntimeFault.class, () -> ManagedByteArray.readInt32VectorGuest(storage, 0, false));
    assertFalse(Thread.holdsLock(storage));
    assertThrows(RuntimeFault.class, () -> ManagedByteArray.writeInt32VectorGuest(storage, 1, vector, true));
    assertFalse(Thread.holdsLock(storage));
    assertSame(target, storage.readAddressByteOffset(0));
    ManagedByteArray.writeInt32VectorGuest(storage, 1, vector, false);
    assertSame(target, storage.readAddressByteOffset(0));
    assertEquals(vector, ManagedByteArray.readInt32VectorGuest(storage, 1, false));
    ManagedByteArray.writeInt32VectorGuest(storage, 0, vector, false);
    assertThrows(RuntimeFault.class, () -> storage.readAddressByteOffset(0));
    assertEquals(vector, ManagedByteArray.readInt32VectorGuest(storage, 0, false));
    var immutable = ManagedAllocation.immutable(new byte[16], 8);
    assertThrows(RuntimeFault.class, () -> ManagedByteArray.writeInt32VectorGuest(immutable, 0, vector, false));
    assertFalse(Thread.holdsLock(immutable));
    assertEquals(0, ManagedByteArray.readInt32VectorGuest(immutable, 0, false).lane(0));
}
@Test
void pinnedContentsAliasesKeepTheSameOwnerAndRejectRawPointerBits() {
    var storage = ManagedAllocation.mutable(32, 8);
    var base = ManagedAddress.fromAllocation(storage);
    var recreated = ManagedAddress.fromGuestByteArray(storage);
    var interior = base.plus(24);
    base.writeWord8(24, 73);
    base.writeAddressElementIndex(1, interior);
    assertTrue(recreated.readAddressElementIndex(1).sameLocation(interior));
    assertEquals(73L, recreated.readAddressElementIndex(1).readWord8(0));
    assertThrows(RuntimeFault.class, () -> base.readWord8(8));
    assertThrows(RuntimeFault.class, () -> ManagedByteArray.require(storage));
    assertThrows(RuntimeFault.class, () -> base.cbitsBacking());
    assertThrows(RuntimeFault.class, () -> base.writeWord8(9, 1));
    assertTrue(recreated.readAddressElementIndex(1).sameLocation(interior));
    assertEquals(73L, base.readWord8(24));
}
@Test
void pointerCellsSurviveAliasesAndWholeByteCopies() {
    var source = ManagedAllocation.mutable(32, 8);
    var destination = ManagedAllocation.mutable(32, 8);
    var referent = ManagedAddress.fromByteArray(new byte[] {4});
    source.writeAddressByteOffset(8, referent);
    assertSame(referent, source.readAddressByteOffset(8));
    assertThrows(RuntimeFault.class, () -> source.readByte(10));
    ManagedByteArray.copyGuest(source, 8, destination, 16, 8, true);
    assertSame(referent, destination.readAddressByteOffset(16));
    assertThrows(RuntimeFault.class, () -> destination.readByte(18));
    assertThrows(RuntimeFault.class, () -> destination.copyFrom(source, 10, 0, 8));
    assertSame(referent, destination.readAddressByteOffset(16));
    destination.writeByte(15, 7);
    assertSame(referent, destination.readAddressByteOffset(16));
    assertThrows(RuntimeFault.class, () -> destination.writeByte(23, 7));
    assertSame(referent, destination.readAddressByteOffset(16));
    destination.fill(16, 8, 0);
    assertThrows(RuntimeFault.class, () -> destination.readAddressByteOffset(16));
    assertEquals(0L, destination.readByte(23));
    assertSame(referent, source.readAddressByteOffset(8));
}
@Test
void overlappingCopySnapshotsCellsAndPartialWritesInvalidate() {
    var storage = ManagedAllocation.mutable(40, 8);
    var first = ManagedAddress.fromByteArray(new byte[] {1});
    var second = ManagedAddress.fromByteArray(new byte[] {2});
    storage.writeAddressByteOffset(0, first);
    storage.writeAddressByteOffset(16, second);
    storage.copyFrom(storage, 0, 8, 24);
    assertSame(first, storage.readAddressByteOffset(8));
    assertSame(second, storage.readAddressByteOffset(24));
    assertSame(first, storage.readAddressByteOffset(0));
    assertThrows(RuntimeFault.class, () -> storage.fill(12, 1, 0));
    assertSame(first, storage.readAddressByteOffset(8));
    storage.fill(8, 8, 0);
    assertThrows(RuntimeFault.class, () -> storage.readAddressByteOffset(8));
    assertSame(second, storage.readAddressByteOffset(24));
}
@Test
void resizeKeepsOnlyCompletePointerCellsAndNullIsAReference() {
    var storage = ManagedAllocation.mutable(24, 8);
    var nullAddress = ManagedAddress.nullAddress();
    storage.writeAddressByteOffset(8, nullAddress);
    assertSame(nullAddress, storage.readAddressByteOffset(8));
    assertSame(nullAddress, storage.resized(24).readAddressByteOffset(8));
    assertSame(nullAddress, storage.resized(16).readAddressByteOffset(8));
    assertThrows(RuntimeFault.class, () -> storage.resized(15));
    assertThrows(RuntimeFault.class, () -> ManagedAllocation.immutable(new byte[] {0}, 8).writeByte(0, 1));
    var immutableAddress = ManagedAddress.fromAllocation(ManagedAllocation.immutable(new byte[] {0}, 8));
    assertFalse(immutableAddress.cbitsWritable());
    assertThrows(RuntimeFault.class, () -> immutableAddress.requireRange(0, 1, true));
    var narrow = ManagedAllocation.mutable(12, 4);
    narrow.writeAddressByteOffset(4, nullAddress);
    assertSame(nullAddress, narrow.readAddressByteOffset(4));
    assertThrows(RuntimeFault.class, () -> narrow.copyFrom(storage, 0, 0, 4));
    var nativeExposed = ManagedAllocation.mutable(16, 8);
    assertSame(nativeExposed.rawBytesIfPointerFree(), nativeExposed.exposeToNative());
    assertThrows(RuntimeFault.class, () -> nativeExposed.writeAddressByteOffset(0, nullAddress));
    assertThrows(RuntimeFault.class, () -> ManagedByteArray.copyGuest(storage, 8, nativeExposed, 0, 8, true));
    assertEquals(0L, nativeExposed.readByte(0));
    var rawExposed = ManagedAllocation.mutable(16, 8);
    assertEquals(16, rawExposed.rawBytesIfPointerFree().length);
    assertThrows(RuntimeFault.class, () -> rawExposed.writeAddressByteOffset(0, nullAddress));
}
}
