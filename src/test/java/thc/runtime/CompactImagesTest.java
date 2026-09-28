// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.RawVectorTestValues.rawVectorTestValue;

class CompactImagesTest {
    @FunctionalInterface
    private interface Action {
        void accept(Language language, Language.State state) throws Exception;
    }
    private void withLanguage(Action action) throws Exception {
        try (var context = Context.newBuilder("thc").build()) {
            context.initialize("thc");
            context.enter();
            try {
                action.accept(
                    TruffleLanguage.LanguageReference.create(Language.class).get(null), Language.currentState());
            } finally {
                context.leave();
            }
        }
    }
    private ManagedCompact newRegion(Language.State state, Object... values) {
        var region = new ManagedCompact(state.compactRegions, 4096);
        var owned = new ArrayList<ManagedCompact.Allocation>();
        for (var value : values) owned.add(new ManagedCompact.Allocation(value, 64L));
        region.begin();
        try {
            region.finish(owned);
            state.compactRegions.record(region, owned);
        } finally {
            region.end();
        }
        return region;
    }
    private List<byte[]> snapshot(CompactImages images, ManagedCompact region) {
        var blocks = new ArrayList<byte[]>();
        var block = images.first(region);
        while (block != ManagedAddress.nullAddress()) {
            var bytes = new byte[(int) block.availableBytes()];
            block.copyToByteArray(bytes, 0, bytes.length);
            blocks.add(bytes);
            block = images.next(region, block);
        }
        return blocks;
    }
    private static <T> T single(List<T> values) {
        if (values.isEmpty())
            throw new java.util.NoSuchElementException("List is empty.");
        if (values.size() != 1)
            throw new IllegalArgumentException("List has more than one element.");
        return values.getFirst();
    }
    private ManagedAddress copyBlocks(CompactImages images, List<byte[]> bytes) {
        var first = ManagedAddress.nullAddress();
        var previous = first;
        for (var block : bytes) {
            var next = images.allocate(block.length, previous);
            next.copyFromByteArray(block, 0, block.length);
            if (first == ManagedAddress.nullAddress())
                first = next;
            previous = next;
        }
        return first;
    }
    @Test
    void opaqueAddressesKeepIdentityWithoutForcingRootingOrNativeProjection() throws Exception {
        withLanguage((language, state) -> {
            var layout = new DataLayout(language, "test:Leaf", "Leaf", new String[] {"IntRep"});
            var value = layout.create(new Object[] {19L});
            var address = state.heapAddresses.address(value);
            assertTrue(address.sameLocation(state.heapAddresses.address(value)));
            assertSame(value, state.heapAddresses.dereference(address));
            assertFalse(address.sameLocation(state.heapAddresses.address(layout.create(new Object[] {19L}))));
            assertFalse(address.sameLocation(ManagedAddress.nullAddress()));
            assertSame(address, address.plus(0));
            assertThrows(RuntimeFault.class, () -> address.plus(1));
            assertThrows(RuntimeFault.class, () -> address.readWord8(0));
            assertThrows(RuntimeFault.class, address::toNativeBits);
            assertThrows(RuntimeFault.class, () -> new HeapAddresses().dereference(address));
            boolean[] entered = {false};
            var thunk = new Thunk(new RootNode(language) {
                @Override
                public Object execute(VirtualFrame frame) {
                    entered[0] = true;
                    return value;
                }
            }.getCallTarget(), null);
            assertThrows(RuntimeFault.class, () -> state.heapAddresses.address(thunk));
            assertFalse(entered[0]);
            thunk.setValue(value);
            thunk.setState(2);
            assertTrue(address.sameLocation(state.heapAddresses.address(thunk)));
            // Deterministically exercise the weak-reference-cleared state, without
            // relying on a collector schedule or retaining the object through Addr#.
            state.heapAddresses.require(address).getValue().clear();
            assertThrows(RuntimeFault.class, () -> state.heapAddresses.dereference(address));
        });
    }
    @Test
    void imageBytesRebuildSharingCyclesArraysAndExactScalarBits() throws Exception {
        withLanguage((language, state) -> {
            var layout = new DataLayout(
                language, "test:Node", "Node", new String[] {"IntRep", "FloatRep", "DoubleRep", "LiftedRep"});
            var original = layout.allocate();
            layout.initializeLong(original, 0, Long.MIN_VALUE);
            layout.initializeFloat(original, 1, Float.intBitsToFloat(0x7fc01234));
            layout.initializeDouble(original, 2, Double.longBitsToDouble(Long.MIN_VALUE));
            var storage = ManagedAllocation.immutable(new byte[] {-1, 0, 17}, 4);
            var small = ManagedSmallArray.freeze(new SmallArrayStorage(new Object[] {original, storage}));
            var array = ManagedArray.freeze(new Object[] {original, original, small});
            layout.initialize(original, 3, array);
            var region = newRegion(state, original, array, small, storage);
            var pointer = state.heapAddresses.address(original);
            var bytes = snapshot(state.compactImages, region);
            state.heapAddresses.require(pointer)
                .getValue()
                .clear(); // Import must not recover the source graph from the handle.
            var fixed = state.compactImages.fixup(copyBlocks(state.compactImages, bytes), pointer);
            var result = (DataValue) state.heapAddresses.dereference(fixed.getRoot());
            assertNotSame(original, result);
            assertEquals(Long.MIN_VALUE, layout.readLong(result, 0));
            assertEquals(0x7fc01234, Float.floatToRawIntBits(layout.readFloat(result, 1)));
            assertEquals(Long.MIN_VALUE, Double.doubleToRawLongBits(layout.readDouble(result, 2)));
            var copiedArray = (Object[]) layout.read(result, 3);
            assertNotSame(array, copiedArray);
            assertSame(result, copiedArray[0]);
            assertSame(result, copiedArray[1]);
            var copiedSmall = (SmallArrayStorage) copiedArray[2];
            assertNotSame(small, copiedSmall);
            assertTrue(copiedSmall.getFrozen());
            assertSame(result, copiedSmall.getElements()[0]);
            var copiedStorage = (ManagedAllocation) copiedSmall.getElements()[1];
            assertNotSame(storage, copiedStorage);
            assertFalse(copiedStorage.isWritable());
            assertEquals(4, copiedStorage.getAddressWidth());
            assertArrayEquals(new byte[] {-1, 0, 17}, copiedStorage.copyBytesOut(0, 3));
            assertTrue(state.compactRegions.contains(fixed.getRegion(), result));
            assertFalse(state.compactRegions.contains(fixed.getRegion(), original));
            assertFalse(fixed.getRoot().sameLocation(pointer));
            assertArrayEquals(
                bytes.get(0), snapshot(state.compactImages, region).get(0), "Export bytes stay unchanged");
        });
    }
    @Test
    void vectorPayloadsPreserveExactLaneBitsThroughImageBytes() throws Exception {
        withLanguage((language, state) -> {
            for (var shape :
                new Object[][] {{4, "FloatElemRep"}, {2, "DoubleElemRep"}, {16, "Word8ElemRep"}, {2, "Int64ElemRep"}}) {
                int lanes = (Integer) shape[0];
                String element = (String) shape[1], rep = "VecRep " + lanes + " " + element;
                var proof = Map.of("kind", "vector", "primReps", List.of(rep), "evaluated", true, "vector",
                    Map.of("lanes", lanes, "element", element));
                var layout = DataLayout.fromFields(language, "test:" + rep, "Vector",
                    new CoreFields(Map.of("id", "test:" + rep, "arity", 1, "fieldReps", List.of(List.of(rep)),
                        "fieldTypes", List.of(proof), "strictFields", List.of(true), "fieldLifted", List.of(false))));
                var slots = new FrameLayout();
                int slot = slots.bind("vector");
                var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], slots.build());
                var input = rawVectorTestValue(CoreRepresentations.parse(proof));
                FrameAccess.write(frame, slot, input);
                var original = layout.allocate();
                layout.initializeVector(original, 0, frame, new int[] {slot}, 0);
                var region = newRegion(state, original);
                var fixed =
                    state.compactImages.fixup(copyBlocks(state.compactImages, snapshot(state.compactImages, region)),
                        state.heapAddresses.address(original));
                var result = (DataValue) state.heapAddresses.dereference(fixed.getRoot());
                layout.restoreVector(result, 0, frame, new int[] {slot}, 0);
                var copied = (jdk.incubator.vector.Vector<?>) frame.getObject(slot);
                assertArrayEquals(input.reinterpretAsBytes().toArray(), copied.reinterpretAsBytes().toArray(), rep);
            }
        });
    }
    @Test
    void multipleBlocksCopyRealBytesAndRejectWrongBlockChains() throws Exception {
        withLanguage((language, state) -> {
            var bytes = new byte[200_000];
            for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 37);
            var region = newRegion(state, bytes);
            var pointer = state.heapAddresses.address(bytes);
            var exported = snapshot(state.compactImages, region);
            assertTrue(exported.size() > 1);
            var first = copyBlocks(state.compactImages, exported);
            var fixed = state.compactImages.fixup(first, pointer);
            var imported = (byte[]) state.heapAddresses.dereference(fixed.getRoot());
            assertNotSame(bytes, imported);
            assertArrayEquals(bytes, imported);
            assertThrows(RuntimeFault.class, () -> state.compactImages.fixup(first, pointer));
            var pending = state.compactImages.allocate(8, ManagedAddress.nullAddress());
            var next = state.compactImages.allocate(8, pending);
            assertThrows(RuntimeFault.class, () -> state.compactImages.allocate(8, pending));
            assertThrows(RuntimeFault.class, () -> state.compactImages.fixup(next, pointer));
            assertThrows(RuntimeFault.class, () -> state.compactImages.fixup(pending.plus(1), pointer));
            for (long size : new long[] {-1L, 0L, Long.MAX_VALUE})
                assertThrows(
                    RuntimeFault.class, () -> state.compactImages.allocate(size, ManagedAddress.nullAddress()));
        });
    }
    private byte[] malformed(byte[] bytes, Consumer<ByteBuffer> edit) {
        var copy = bytes.clone();
        var buffer = ByteBuffer.wrap(copy);
        edit.accept(buffer);
        var crc = new CRC32();
        crc.update(copy, 0, copy.length - 8);
        buffer.putLong(copy.length - 8, crc.getValue());
        return copy;
    }
    @Test
    void corruptTruncatedAndForeignImagesFailWithoutPublishingAResult() throws Exception {
        withLanguage((language, state) -> {
            var layout = new DataLayout(language, "test:Leaf", "Leaf", new String[] {"IntRep"});
            var value = layout.create(new Object[] {71L});
            var region = newRegion(state, value);
            var pointer = state.heapAddresses.address(value);
            var bytes = single(snapshot(state.compactImages, region));
            var corrupt = bytes.clone();
            corrupt[0] = (byte) (corrupt[0] ^ 1);
            for (var bad : List.of(corrupt, Arrays.copyOf(bytes, bytes.length - 1),
                     malformed(bytes, it -> it.putInt(24, Integer.MAX_VALUE)),
                     malformed(bytes, it -> it.put(36, (byte) 99)), malformed(bytes, it -> it.putLong(37, -1L)))) {
                var first = copyBlocks(state.compactImages, List.of(bad));
                var failed = state.compactImages.fixup(first, pointer);
                assertSame(ManagedAddress.nullAddress(), failed.getRoot());
                assertTrue(failed.getRegion().getObjects().isEmpty());
                assertThrows(RuntimeFault.class, () -> state.compactImages.fixup(first, pointer));
            }
            var foreign = new CompactImages(state.compactRegions, state.heapAddresses);
            try {
                assertSame(ManagedAddress.nullAddress(),
                    foreign.fixup(copyBlocks(foreign, List.of(bytes)), pointer).getRoot());
            } finally {
                foreign.close();
            }
            assertThrows(RuntimeFault.class,
                () -> state.compactImages.next(newRegion(state, value), state.compactImages.first(region)));
            var first = state.compactImages.first(region);
            region.resize(8192);
            assertThrows(RuntimeFault.class, () -> state.compactImages.next(region, first));
            var typed = new DataLayout(
                language, "test:Typed", "Typed", new String[] {"LiftedRep"}, new Class<?>[] {DataValue.class});
            var holder = typed.create(new Object[] {value});
            var wrongCarrier = single(snapshot(state.compactImages, newRegion(state, holder, value, new byte[] {1})));
            // Header 28, old identity 8, node tag 1, layout id 8; the first field's
            // node index is at 45. Point it at ByteArray instead of DataValue.
            var payload = ByteBuffer.wrap(wrongCarrier);
            payload.putInt(45, 2);
            var crc = new CRC32();
            crc.update(wrongCarrier, 0, wrongCarrier.length - 8);
            payload.putLong(wrongCarrier.length - 8, crc.getValue());
            var mismatch = state.compactImages.fixup(
                copyBlocks(state.compactImages, List.of(wrongCarrier)), state.heapAddresses.address(holder));
            assertSame(ManagedAddress.nullAddress(), mismatch.getRoot());
        });
    }
}
