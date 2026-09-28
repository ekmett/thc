// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.zip.CRC32;
import static thc.runtime.ManagedMutVar.completedBoxedIdentity;
import static thc.runtime.RuntimeServiceStatus.fault;

/** A managed byte image, not the GHC heap ABI. Layout handles belong to the
 * originating context; graph payloads and backreferences live in bytes.
 * Exported images retain no source objects. Cross-context images fail closed. */
public final class CompactImages {
    private record Export(long generation, List<ManagedAddress> blocks) {}
    private static final class Import {
        final ArrayList<ManagedAllocation> blocks = new ArrayList<>();
        int size;
    }
    public static final class Fixed {
        private final ManagedCompact region;
        private final ManagedAddress root;
        Fixed(ManagedCompact region, ManagedAddress root) { this.region = region; this.root = root; }
        public ManagedCompact getRegion() { return region; }
        public ManagedAddress getRoot() { return root; }
    }
    private static final class InvalidImage extends RuntimeException {
        InvalidImage() { super(null, null, false, false); }
    }
    private record Decoded(List<Object> values, long[] oldIds) {}
    private static final int MAGIC = 0x54484343, VERSION = 1, BLOCK_BYTES = 64 * 1024;
    private static final long MAX_IMAGE_BYTES = 256 * 1024 * 1024L;
    private static final int DATA = 1, ALLOCATION = 2, BYTES = 3, ARRAY = 4, SMALL_ARRAY = 5;
    private final ManagedCompacts regions;
    private final HeapAddresses heap;
    private final UUID identity = UUID.randomUUID();
    private final WeakHashMap<ManagedCompact, Export> exports = new WeakHashMap<>();
    private final IdentityHashMap<DataLayout, Long> layoutIds = new IdentityHashMap<>();
    private final HashMap<Long, DataLayout> layouts = new HashMap<>();
    // Raw imports are consumed exactly once. Disposal releases abandoned chains.
    private final IdentityHashMap<ManagedAllocation, Import> imports = new IdentityHashMap<>();
    private boolean closed;
    public CompactImages(ManagedCompacts regions, HeapAddresses heap) { this.regions = regions; this.heap = heap; }
    private void requireOpen() { if (closed) throw fault("Compact image context is closed"); }
    private static InvalidImage invalid() { return new InvalidImage(); }

    @TruffleBoundary public synchronized ManagedAddress first(ManagedCompact region) {
        requireOpen(); regions.require(region);
        return region.snapshot(values -> {
            var prior = exports.get(region);
            if (prior != null && prior.generation() == region.getGeneration()) return prior.blocks().getFirst();
            byte[] bytes;
            try { bytes = encode(values); }
            catch (IOException failure) { throw propagate(failure); }
            var blocks = new ArrayList<ManagedAddress>();
            for (int offset = 0; offset < bytes.length; offset += BLOCK_BYTES)
                blocks.add(ManagedAddress.fromAllocation(ManagedAllocation.immutableGuest(
                    Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + BLOCK_BYTES)), 8)));
            exports.put(region, new Export(region.getGeneration(), blocks));
            return blocks.getFirst();
        });
    }
    @TruffleBoundary public synchronized ManagedAddress next(ManagedCompact region, ManagedAddress current) {
        requireOpen(); regions.require(region);
        return region.snapshot(values -> {
            var image = exports.get(region);
            if (image == null) throw fault("Compact block was not exported by this region");
            if (image.generation() != region.getGeneration()) throw fault("Compact region changed during block iteration");
            int index = -1;
            for (int i = 0; i < image.blocks().size(); i++) if (image.blocks().get(i).sameLocation(current)) { index = i; break; }
            if (index < 0) throw fault("Compact block belongs to another region");
            return index + 1 < image.blocks().size() ? image.blocks().get(index + 1) : ManagedAddress.nullAddress();
        });
    }
    @TruffleBoundary public synchronized ManagedAddress allocate(long size, ManagedAddress previous) {
        requireOpen();
        if (size <= 0 || size > MAX_IMAGE_BYTES) throw fault("Compact import block size outside managed target domain");
        Import chain;
        if (previous == ManagedAddress.nullAddress()) chain = new Import();
        else {
            var allocation = previous.cbitsOwner();
            chain = allocation == null ? null : imports.get(allocation);
            if (chain == null) throw fault("Previous compact import block is unknown or consumed");
            if (previous.cbitsOffset() != 0L || chain.blocks.getLast() != allocation)
                throw fault("Compact import blocks must be appended at the chain tail");
        }
        if (size > MAX_IMAGE_BYTES - chain.size) throw fault("Compact import image exceeds managed target domain");
        var allocation = ManagedAllocation.mutable(size, 8, true);
        chain.blocks.add(allocation); chain.size += (int) size;
        imports.put(allocation, chain);
        return ManagedAddress.fromAllocation(allocation);
    }
    @TruffleBoundary public synchronized Fixed fixup(ManagedAddress first, ManagedAddress oldRoot) {
        requireOpen();
        var allocation = first.cbitsOwner();
        var chain = allocation == null ? null : imports.get(allocation);
        if (chain == null) throw fault("Compact import block is unknown or already consumed");
        if (first.cbitsOffset() != 0L || chain.blocks.getFirst() != allocation)
            throw fault("compactFixupPointers# requires the first import block");
        for (var block : chain.blocks) imports.remove(block);
        var region = new ManagedCompact(regions, BLOCK_BYTES);
        var handle = oldRoot.heapHandle();
        if (handle == null || handle.getOwner() != heap) return failed(region);
        heap.require(handle);
        var bytes = new byte[chain.size];
        int offset = 0;
        for (var block : chain.blocks) {
            var copy = block.copyBytesOut(0, block.getSize());
            System.arraycopy(copy, 0, bytes, offset, copy.length); offset += copy.length;
        }
        try {
            var decoded = decode(bytes);
            var values = decoded.values();
            int index = -1;
            for (int i = 0; i < decoded.oldIds().length; i++) if (decoded.oldIds()[i] == handle.getId()) { index = i; break; }
            Object root;
            if (index >= 0) root = values.get(index);
            else {
                root = handle.getValue().get();
                if (!(root instanceof DataValue data) || data.getLayout().getArity() != 0) return failed(region);
            }
            var owned = new ArrayList<ManagedCompact.Allocation>();
            for (var value : values) if (!(value instanceof DataValue data) || data.getLayout().getArity() != 0)
                owned.add(new ManagedCompact.Allocation(value, bytesUsed(value)));
            region.begin();
            try { region.finish(owned); regions.record(region, owned); }
            finally { region.end(); }
            return new Fixed(region, heap.address(root));
        } catch (InvalidImage | IOException | IllegalArgumentException failure) { return failed(region); }
    }
    private static Fixed failed(ManagedCompact region) { return new Fixed(region, ManagedAddress.nullAddress()); }
    private static long bytesUsed(Object value) {
        if (value instanceof DataValue data) return data.getLayout().compactBytes();
        if (value instanceof ManagedAllocation allocation) return 16L + allocation.getSize();
        if (value instanceof byte[] bytes) return 16L + bytes.length;
        if (value instanceof Object[] array) return 24L + 8L * array.length;
        if (value instanceof SmallArrayStorage array) return 16L + 8L * array.getLogicalSize();
        throw invalid();
    }
    private static List<Object> children(Object value) {
        if (value instanceof DataValue data) {
            var fields = new ArrayList<Object>();
            var layout = data.getLayout();
            for (int i = 0; i < layout.getArity(); i++) if (layout.compactPointer(i) && !layout.inactiveSumReference(data, i)) fields.add(layout.read(data, i));
            return fields;
        }
        if (value instanceof Object[] array) return Arrays.asList(array);
        if (value instanceof SmallArrayStorage array) return new ArrayList<>(Arrays.asList(array.getElements()).subList(0, array.getLogicalSize()));
        return List.of();
    }
    private long layoutId(DataLayout layout) {
        var prior = layoutIds.get(layout);
        if (prior != null) return prior;
        long id = (long) layouts.size() + 1;
        layoutIds.put(layout, id); layouts.put(id, layout);
        return id;
    }
    private static void visit(Object raw, IdentityHashMap<Object, Integer> indices, List<Object> nodes) {
        var value = completedBoxedIdentity(raw);
        if (value == null) throw fault("Null in compact image");
        if (value instanceof Thunk) throw fault("Unevaluated value in compact image");
        if (!indices.containsKey(value)) { indices.put(value, nodes.size()); nodes.add(value); }
    }
    private static int reference(Object value, IdentityHashMap<Object, Integer> indices) {
        var index = indices.get(completedBoxedIdentity(value));
        if (index == null) throw fault("Missing compact image node");
        return index;
    }
    private byte[] encode(List<Object> roots) throws IOException {
        var indices = new IdentityHashMap<Object, Integer>();
        var nodes = new ArrayList<Object>();
        for (var root : roots) visit(root, indices, nodes);
        for (int index = 0; index < nodes.size(); index++) for (var child : children(nodes.get(index))) visit(child, indices, nodes);
        var buffer = new ByteArrayOutputStream();
        var output = new DataOutputStream(buffer);
        output.writeInt(MAGIC); output.writeInt(VERSION);
        output.writeLong(identity.getMostSignificantBits()); output.writeLong(identity.getLeastSignificantBits());
        output.writeInt(nodes.size());
        for (var value : nodes) {
            output.writeLong(heap.handle(value).getId());
            if (value instanceof DataValue data) {
                var layout = data.getLayout();
                output.writeByte(DATA); output.writeLong(layoutId(layout));
                for (int field = 0; field < layout.getArity(); field++) {
                    if (layout.compactPointer(field)) output.writeInt(layout.inactiveSumReference(data, field) ? -1 : reference(layout.read(data, field), indices));
                    else layout.writeCompactScalar(data, field, output);
                }
            } else if (value instanceof ManagedAllocation allocation) {
                output.writeByte(ALLOCATION); output.writeInt(allocation.getAddressWidth());
                output.writeInt((int) allocation.getSize()); output.write(allocation.copyBytesOut(0, allocation.getSize()));
            } else if (value instanceof byte[] bytes) {
                output.writeByte(BYTES); output.writeInt(bytes.length); output.write(bytes);
            } else if (value instanceof Object[] array) {
                output.writeByte(ARRAY); output.writeInt(array.length);
                for (var field : array) output.writeInt(reference(field, indices));
            } else if (value instanceof SmallArrayStorage array) {
                output.writeByte(SMALL_ARRAY); output.writeInt(array.getLogicalSize());
                int length = array.getLogicalSize();
                for (int field = 0; field < length; field++) output.writeInt(reference(array.getElements()[field], indices));
            } else throw fault("Unsupported compact image object");
            if (buffer.size() > MAX_IMAGE_BYTES - 8) throw fault("Compact image exceeds managed target domain");
        }
        var checksum = new CRC32(); checksum.update(buffer.toByteArray());
        output.writeLong(checksum.getValue());
        return buffer.toByteArray();
    }
    private static int size(DataInputStream input, int width) throws IOException {
        int size = input.readInt();
        if (size < 0 || size > input.available() / width) throw invalid();
        return size;
    }
    private static int reference(DataInputStream input, int count) throws IOException {
        int index = input.readInt();
        if (index < 0 || index >= count) throw invalid();
        return index;
    }
    private Decoded decode(byte[] bytes) throws IOException {
        if (bytes.length < 36) throw invalid();
        long expected = new DataInputStream(new ByteArrayInputStream(bytes, bytes.length - 8, 8)).readLong();
        var checksum = new CRC32(); checksum.update(bytes, 0, bytes.length - 8);
        if (checksum.getValue() != expected) throw invalid();
        var input = new DataInputStream(new ByteArrayInputStream(bytes, 0, bytes.length - 8));
        if (input.readInt() != MAGIC || input.readInt() != VERSION || input.readLong() != identity.getMostSignificantBits() || input.readLong() != identity.getLeastSignificantBits()) throw invalid();
        int count = input.readInt();
        if (count < 0 || count > input.available() / 9) throw invalid();
        var nodes = new Object[count];
        var references = new int[count][];
        var oldIds = new long[count];
        var unique = new HashSet<Long>();
        for (int index = 0; index < count; index++) {
            long id = input.readLong();
            if (id <= 0 || !unique.add(id)) throw invalid();
            oldIds[index] = id;
            int tag = input.readUnsignedByte();
            switch (tag) {
                case DATA -> {
                    var layout = layouts.get(input.readLong());
                    if (layout == null) throw invalid();
                    var value = layout.getArity() == 0 ? layout.create(new Object[0]) : layout.allocate();
                    var fields = new int[layout.getArity()]; Arrays.fill(fields, -1);
                    for (int field = 0; field < fields.length; field++) {
                        boolean inactive;
                        try { inactive = layout.inactiveSumReference(value, field); }
                        catch (RuntimeFault failure) { throw invalid(); }
                        if (inactive) {
                            if (input.readInt() != -1) throw invalid();
                            layout.initialize(value, field, null);
                        } else if (layout.compactPointer(field)) fields[field] = reference(input, count);
                        else layout.readCompactScalar(value, field, input);
                    }
                    references[index] = fields; nodes[index] = value;
                }
                case ALLOCATION -> {
                    int width = input.readInt(); if (width != 4 && width != 8) throw invalid();
                    nodes[index] = ManagedAllocation.immutableGuest(input.readNBytes(size(input, 1)), width);
                }
                case BYTES -> {
                    byte[] payload = input.readNBytes(size(input, 1));
                    nodes[index] = thc.Language.currentState().getNativeByteArrays()
                        ? ManagedAllocation.immutableGuest(payload, 8) : payload;
                }
                case ARRAY, SMALL_ARRAY -> {
                    int length = size(input, 4);
                    var fields = new int[length];
                    for (int field = 0; field < length; field++) fields[field] = reference(input, count);
                    references[index] = fields;
                    nodes[index] = tag == ARRAY ? ManagedArray.freeze(new Object[length]) : ManagedSmallArray.freeze(new SmallArrayStorage(new Object[length]));
                }
                default -> throw invalid();
            }
        }
        if (input.available() != 0) throw invalid();
        for (int index = 0; index < nodes.length; index++) {
            var fields = references[index];
            if (fields == null) continue;
            var value = nodes[index];
            if (value instanceof DataValue data) {
                var layout = data.getLayout();
                for (int field = 0; field < fields.length; field++) if (fields[field] >= 0) {
                    var target = nodes[fields[field]];
                    if (!layout.acceptsCompactPointer(field, target)) throw invalid();
                    layout.initialize(data, field, target);
                }
            } else if (value instanceof Object[] array) {
                for (int field = 0; field < fields.length; field++) array[field] = nodes[fields[field]];
            } else if (value instanceof SmallArrayStorage array) {
                for (int field = 0; field < fields.length; field++) array.getElements()[field] = nodes[fields[field]];
            } else throw invalid();
        }
        for (var node : nodes) if (node == null) throw invalid();
        return new Decoded(Arrays.asList(nodes), oldIds);
    }
    public synchronized void close() { closed = true; exports.clear(); imports.clear(); layoutIds.clear(); layouts.clear(); }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
