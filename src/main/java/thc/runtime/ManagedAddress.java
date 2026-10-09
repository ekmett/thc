// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** An Addr# retains its original storage and authority. Immutable literal loads
 * remain separate from mutable storage so only literal contents may fold. */
public final class ManagedAddress {
    @CompilationFinal(dimensions = 1) private final byte[] literalBytes;
    private final byte[] mutableBytes;
    private final long offset;
    private final ManagedAllocation owner;
    private final StablePointers.Handle stable;
    private final Long numeric;
    private final CFinalizerFunction finalizer;
    private final ManagedNativeAllocations.Owner nativeOwner;
    private final GuestThreads capabilities;
    private final HeapAddresses.Handle heap;
    private final CompilerRts compiler;
    private final PackageReturnedAddress foreign;
    private final CompilerRts rtsFlags;
    private final WindowsNativeIo ioMode;

    private ManagedAddress(byte[] literalBytes, byte[] mutableBytes, long offset) {
        this(literalBytes, mutableBytes, offset, null, null, null, null, null, null, null, null, null, null);
    }
    private ManagedAddress(byte[] literalBytes, byte[] mutableBytes, long offset, ManagedAllocation owner,
            StablePointers.Handle stable, Long numeric, CFinalizerFunction finalizer,
            ManagedNativeAllocations.Owner nativeOwner, GuestThreads capabilities, HeapAddresses.Handle heap,
            CompilerRts compiler, PackageReturnedAddress foreign, CompilerRts rtsFlags) {
        this.literalBytes = literalBytes; this.mutableBytes = mutableBytes; this.offset = offset;
        this.owner = owner; this.stable = stable; this.numeric = numeric; this.finalizer = finalizer;
        this.nativeOwner = nativeOwner; this.capabilities = capabilities; this.heap = heap;
        this.compiler = compiler; this.foreign = foreign; this.rtsFlags = rtsFlags;
        this.ioMode = null;
    }

    private ManagedAddress(WindowsNativeIo ioMode) {
        literalBytes = null; mutableBytes = null; offset = 0; owner = null;
        stable = null; numeric = null; finalizer = null; nativeOwner = null;
        capabilities = null; heap = null; compiler = null; foreign = null; rtsFlags = null;
        this.ioMode = ioMode;
    }
    static ManagedAddress windowsIoMode(WindowsNativeIo owner) { return new ManagedAddress(owner); }

    /** An RTS data label is never a projection of a JVM or native pointer. */
    public Long readCapabilitiesWord32(long elementOffset, int width) {
        if (capabilities == null) return null;
        if (Language.currentState(null).getThreads() != capabilities)
            throw fault("RTS data label belongs to another THC context");
        if (width != 4 || elementOffset != 0)
            throw fault("enabled_capabilities permits only an aligned Word32 read at offset zero");
        long count = Math.max(1L, capabilities.capabilityCount());
        if (count > 0xffff_ffffL) throw fault("enabled_capabilities exceeds Word32");
        return count;
    }
    public ManagedNativeAllocations.Owner nativeAllocation() { return nativeOwner; }
    public PackageReturnedAddress returnedAddress() { return foreign; }
    public Long numericBits() { return numeric; }
    public boolean hasNativeStorage() { return nativeOwner != null || owner != null && owner.hasNativeStorage(); }
    public boolean isNativeBase() { return nativeOwner != null && offset == 0; }
    // A nullable resource preserves the original no-native-owner loan without a wrapper.
    ManagedNativeAllocations.Owner.Borrow borrow() { return nativeOwner == null ? null : nativeOwner.borrow(); }
    public <T> T withNativeBorrow(Supplier<T> body) {
        try (var loan = borrow()) { return body.get(); }
    }
    public <T> T withNativeSegment(Function<MemorySegment, T> body) {
        if (nativeOwner != null) return ownedNativeSegment(body);
        if (owner == null) throw fault("Address has no owned native allocation");
        synchronized (owner) {
            var segment = owner.nativeSegment();
            if (segment == null) throw fault("Address has no owned native allocation");
            return body.apply(segment.asSlice(offset));
        }
    }
    public int withNativeSegmentInt(ToIntFunction<MemorySegment> body) {
        if (nativeOwner != null) return ownedNativeSegmentInt(body);
        if (owner == null) throw fault("Address has no owned native allocation");
        synchronized (owner) {
            var segment = owner.nativeSegment();
            if (segment == null) throw fault("Address has no owned native allocation");
            return body.applyAsInt(segment.asSlice(offset));
        }
    }
    public long withNativeSegmentLong(ToLongFunction<MemorySegment> body) {
        if (nativeOwner != null) return ownedNativeSegmentLong(body);
        if (owner == null) throw fault("Address has no owned native allocation");
        synchronized (owner) {
            var segment = owner.nativeSegment();
            if (segment == null) throw fault("Address has no owned native allocation");
            return body.applyAsLong(segment.asSlice(offset));
        }
    }
    // Keep native-owner borrowed access behind a boundary; the managed pinned
    // branch above remains in the guest graph.
    @TruffleBoundary private <T> T ownedNativeSegment(Function<MemorySegment, T> body) {
        try (var loan = nativeOwner.borrow()) { return body.apply(loan.segment().asSlice(offset)); }
    }
    @TruffleBoundary private int ownedNativeSegmentInt(ToIntFunction<MemorySegment> body) {
        try (var loan = nativeOwner.borrow()) { return body.applyAsInt(loan.segment().asSlice(offset)); }
    }
    @TruffleBoundary private long ownedNativeSegmentLong(ToLongFunction<MemorySegment> body) {
        try (var loan = nativeOwner.borrow()) { return body.applyAsLong(loan.segment().asSlice(offset)); }
    }
    public <T> T withNativeBorrows(ManagedAddress other, Supplier<T> body) {
        if (nativeOwner == null) return other.withNativeBorrow(body);
        if (other.nativeOwner == null || nativeOwner == other.nativeOwner) return withNativeBorrow(body);
        int order = Integer.compareUnsigned(System.identityHashCode(nativeOwner), System.identityHashCode(other.nativeOwner));
        if (order < 0) try (var first = borrow(); var second = other.borrow()) { return body.get(); }
        if (order > 0) try (var first = other.borrow(); var second = borrow()) { return body.get(); }
        synchronized (NATIVE_BORROW_TIE) {
            try (var first = borrow(); var second = other.borrow()) { return body.get(); }
        }
    }
    public StablePointers.Handle stableHandle() { return stable; }
    public HeapAddresses.Handle heapHandle() { return heap; }
    public CFinalizerFunction finalizerFunction() { return finalizer; }
    private void requireBytes() {
        if (ioMode != null) { ioMode.readModeByte(0); throw fault("Windows IO selection permits only a read-only CBool load"); }
        if (compiler != null) compiler.requireCurrent();
        if (foreign != null) foreign.requireCurrent();
        if (rtsFlags != null) { rtsFlags.requireCurrent(); throw fault("RtsFlags permits only supported read-only fields"); }
        if (heap != null) throw fault("Opaque guest heap address is not byte-addressable");
        if (capabilities != null) throw fault("RTS data label is not byte-addressable");
        if (stable != null) throw fault("Opaque StablePtr# is not byte-addressable");
        if (finalizer != null) throw fault("Opaque C function label is not byte-addressable");
        if (numeric != null) throw fault("Unowned numeric Addr# is not byte-addressable");
    }
    public Object nativeImageKey() { return literalBytes != null ? literalBytes : owner != null && owner.isStaticImage() ? owner : null; }
    public byte[] nativeImageBytes() {
        if (literalBytes != null) return literalBytes;
        if (owner != null && owner.isStaticImage()) return owner.copyBytesOut(0, owner.getSize());
        throw fault("Native image requires static literal or runtime metadata storage");
    }
    public long toNativeBits() {
        if (ioMode != null) { ioMode.readModeByte(0); throw fault("Windows IO selection has no numeric guest address"); }
        if (compiler != null) compiler.requireCurrent();
        if (rtsFlags != null) { rtsFlags.requireCurrent(); throw fault("RtsFlags has no numeric guest address"); }
        if (foreign != null) return foreign.bits();
        if (heap != null) throw fault("Opaque guest heap address has no native pointer bits");
        if (capabilities != null) throw fault("RTS data label has no numeric guest address");
        if (finalizer != null) throw fault("Opaque C function label has no numeric guest address");
        if (stable != null) return StablePointers.current(null).nativeToken(this);
        if (nativeOwner != null) return ownedNativeBits();
        return this == NULL ? 0 : numeric != null ? numeric : NativeAddresses.current(null).project(this);
    }
    @TruffleBoundary private long ownedNativeBits() {
        try (var loan = nativeOwner.borrow()) { return loan.segment().address() + offset; }
    }
    public byte[] rawBacking() {
        requireBytes();
        if (nativeOwner != null) throw fault("Owned native storage cannot be exposed as a JVM byte array");
        if (owner != null) return owner.rawBytesIfPointerFree();
        if (literalBytes != null) return literalBytes.clone();
        if (mutableBytes != null) return mutableBytes;
        throw fault("Null Addr# has no backing storage");
    }
    public byte[] cbitsBacking() { requireBytes(); return owner != null ? owner.exposeToNative() : rawBacking(); }
    public MemorySegment cbitsSegment() {
        requireBytes();
        if (owner != null) return owner.exposeSegment();
        if (literalBytes != null) return MemorySegment.ofArray(literalBytes).asReadOnly();
        if (mutableBytes != null) return MemorySegment.ofArray(mutableBytes);
        throw fault("Address has no managed buffer storage");
    }
    public ByteBuffer cbitsBuffer() { return cbitsSegment().asByteBuffer(); }
    public Object cbitsStorageKey() {
        if (owner != null) return owner.storageKey();
        if (literalBytes != null) return literalBytes;
        if (mutableBytes != null) return mutableBytes;
        throw fault("Address has no managed buffer storage");
    }
    public boolean cbitsWritable() { requireBytes(); if (nativeOwner != null) nativeOwner.requireLive(); return nativeOwner != null || (owner != null ? owner.isWritable() : mutableBytes != null); }
    public long cbitsOffset() { size(); return offset; }
    public ManagedAllocation cbitsOwner() { return owner; }
    public long cbitsSize() { return size(); }
    public long availableBytes() {
        if (externalPointer() != null) throw fault("External C pointer has no known allocation extent");
        requireRange(0, 0); return size() - offset;
    }
    private PackageReturnedAddress externalPointer() { return foreign != null && foreign.getBacking() == null ? foreign : null; }
    public boolean hasExternalStorage() { return externalPointer() != null; }
    public boolean hasNativeIOStorage() { return hasNativeStorage() || externalPointer() != null && externalPointer().isNative(); }
    public <T> T withNativeIOWindow(long count, boolean writable, Function<MemorySegment, T> body) {
        var pointer = externalPointer();
        // Preserve the original nullable callback-result fallback.
        T result = pointer == null ? null : pointer.withNativeWindow(count, writable, body);
        return result != null ? result : withNativeSegment(body);
    }
    public void requireByteRegion(long count) { requireByteRegion(count, false); }
    public void requireByteRegion(long count, boolean writable) {
        requireRange(0, count, writable);
        if (owner != null) owner.requireByteRegion(offset, count, writable);
    }
    /** Preflight the native 64-bit pointer exchange without byte transport or
     * mutation. Managed pointer cells must stay typed and unexposed to raw C. */
    void requireAddressCell() {
        requireRange(0, 8, true);
        if (owner != null) {
            if (owner.getAddressWidth() != 8) throw fault("Native address cell requires the 64-bit pointer ABI");
            owner.requireAddressCell(offset);
        } else if (nativeOwner == null && externalPointer() == null) throw fault("Addr# has no allocation-owned pointer cells");
    }
    private long size() {
        requireBytes();
        if (nativeOwner != null) { nativeOwner.requireLive(); return nativeOwner.getSize(); }
        if (owner != null) return owner.getSize();
        byte[] bytes = literalBytes != null ? literalBytes : mutableBytes;
        if (bytes == null) throw fault("Null Addr# has no backing storage");
        return bytes.length;
    }

    public boolean sameLocation(ManagedAddress other) {
        if (ioMode != null || other.ioMode != null) {
            if (ioMode != null) ioMode.readModeByte(0); if (other.ioMode != null) other.ioMode.readModeByte(0);
            if (nativeOwner != null) nativeOwner.requireLive(); if (other.nativeOwner != null) other.nativeOwner.requireLive();
            return ioMode != null && ioMode == other.ioMode;
        }
        if (compiler != null) compiler.requireCurrent(); if (other.compiler != null) other.compiler.requireCurrent();
        if (foreign != null) foreign.requireCurrent(); if (other.foreign != null) other.foreign.requireCurrent();
        if (rtsFlags != null || other.rtsFlags != null) {
            if (rtsFlags != null) rtsFlags.requireCurrent(); if (other.rtsFlags != null) other.rtsFlags.requireCurrent();
            if (nativeOwner != null) nativeOwner.requireLive(); if (other.nativeOwner != null) other.nativeOwner.requireLive();
            return rtsFlags != null && rtsFlags == other.rtsFlags && offset == other.offset;
        }
        if (externalPointer() != null) return externalPointer().compare(other, "equal") != 0;
        if (other.externalPointer() != null) return other.externalPointer().compare(this, "equal") != 0;
        if (heap != null || other.heap != null) {
            var registry = HeapAddresses.current();
            if (heap != null) registry.require(heap); if (other.heap != null) registry.require(other.heap);
            return heap != null && heap == other.heap;
        }
        if (capabilities != null || other.capabilities != null) {
            var current = Language.currentState(null).getThreads();
            if (capabilities != null && capabilities != current || other.capabilities != null && other.capabilities != current)
                throw fault("RTS data label belongs to another THC context");
            return capabilities != null && capabilities == other.capabilities;
        }
        if (nativeOwner != null) nativeOwner.requireLive(); if (other.nativeOwner != null) other.nativeOwner.requireLive();
        if (finalizer != null || other.finalizer != null) {
            var provider = Language.currentState(null).cbits();
            if (finalizer != null) finalizer.requireOwner(provider); if (other.finalizer != null) other.finalizer.requireOwner(provider);
            return finalizer != null && finalizer == other.finalizer;
        }
        if (stable != null) {
            var registry = StablePointers.current(null); registry.validate(this);
            return other.stable != null && registry.equal(this, other);
        }
        if (other.stable != null) { StablePointers.current(null).validate(other); return false; }
        if (numeric != null || other.numeric != null) return toNativeBits() == other.toNativeBits();
        if (offset != other.offset) return false;
        if (this == NULL || other == NULL) return this == other;
        if (nativeOwner != null || other.nativeOwner != null) return nativeOwner != null && nativeOwner == other.nativeOwner;
        if (owner != null && other.owner != null) return owner == other.owner;
        if (owner != null) return other.mutableBytes != null && owner.ownsStorage(other.mutableBytes);
        if (other.owner != null) return mutableBytes != null && other.owner.ownsStorage(mutableBytes);
        if (literalBytes != null) return literalBytes == other.literalBytes;
        return mutableBytes != null && mutableBytes == other.mutableBytes;
    }

    /** The owning service serializes this weak allocation/offset index. */
    public static final class WeakLocations<V> {
        private static final class Key extends WeakReference<ManagedAllocation> {
            private final long offset;
            private final int hash;
            Key(ManagedAllocation owner, long offset, ReferenceQueue<ManagedAllocation> queue) {
                super(owner, queue); this.offset = offset; hash = 31 * System.identityHashCode(owner) + Long.hashCode(offset);
            }
            @Override public int hashCode() { return hash; }
            @Override public boolean equals(Object value) {
                if (this == value) return true;
                if (!(value instanceof Key other) || offset != other.offset) return false;
                var allocation = get();
                return allocation != null && allocation == other.get();
            }
        }
        private final ReferenceQueue<ManagedAllocation> queue = new ReferenceQueue<>();
        private final HashMap<Key, V> entries = new HashMap<>();
        private void reap() { for (Reference<?> reference; (reference = queue.poll()) != null;) entries.remove(reference); }
        public int getSize() { reap(); return entries.size(); }
        public V get(ManagedAddress address) {
            reap(); if (address.owner == null) return null;
            try { return entries.get(new Key(address.owner, address.offset, null)); }
            finally { Reference.reachabilityFence(address); }
        }
        public void set(ManagedAddress address, V value) {
            reap(); if (address.owner == null) throw fault("Weak location registration requires an allocation-owned Addr#");
            try { entries.put(new Key(address.owner, address.offset, queue), value); }
            finally { Reference.reachabilityFence(address); }
        }
        public void clear() { entries.clear(); reap(); }
    }

    public int compareWithinAllocation(ManagedAddress other) {
        if (ioMode != null || other.ioMode != null) {
            if (ioMode != null) ioMode.readModeByte(0); if (other.ioMode != null) other.ioMode.readModeByte(0);
            throw fault("Windows IO selection has no address ordering");
        }
        var address = this;
        // As with difference, traverse backing chains without recursive partial evaluation.
        while (true) {
            if (address.foreign != null) address.foreign.requireCurrent(); if (other.foreign != null) other.foreign.requireCurrent();
            if (address.rtsFlags != null || other.rtsFlags != null) {
                if (address.rtsFlags != null) address.rtsFlags.requireCurrent(); if (other.rtsFlags != null) other.rtsFlags.requireCurrent();
                throw fault("RtsFlags has no address ordering");
            }
            var backing = address.foreign == null ? null : address.foreign.getBacking();
            var otherBacking = other.foreign == null ? null : other.foreign.getBacking();
            if (backing == null && otherBacking == null) return address.compareUnwrapped(other);
            if (backing != null) address = backing;
            if (otherBacking != null) other = otherBacking;
        }
    }
    private int compareUnwrapped(ManagedAddress other) {
        if (foreign != null) return (int) foreign.compare(other, "compare");
        if (other.foreign != null) return -(int) other.foreign.compare(this, "compare");
        if (heap != null || other.heap != null) throw fault("Opaque guest heap addresses have no ordering");
        if (capabilities != null || other.capabilities != null) throw fault("RTS data label has no address ordering");
        if (nativeOwner != null) nativeOwner.requireLive(); if (other.nativeOwner != null) other.nativeOwner.requireLive();
        if (finalizer != null || other.finalizer != null) throw fault("Opaque C function label has no address ordering");
        if (stable != null || other.stable != null) throw fault("Opaque StablePtr# has no address ordering");
        if (numeric != null || other.numeric != null) return Long.compareUnsigned(toNativeBits(), other.toNativeBits());
        if (this == NULL || other == NULL) {
            if (this == other) return 0;
            throw fault("Ordered Addr# comparison requires the same managed allocation");
        }
        boolean shared = nativeOwner != null || other.nativeOwner != null ? nativeOwner != null && nativeOwner == other.nativeOwner :
            owner != null ? owner == other.owner : literalBytes != null ? literalBytes == other.literalBytes : mutableBytes != null && mutableBytes == other.mutableBytes;
        if (!shared) throw fault("Ordered Addr# comparison requires the same managed allocation");
        return Long.compare(offset, other.offset);
    }
    public ManagedAddress plus(long displacement) {
        if (ioMode != null) { ioMode.readModeByte(displacement); return this; }
        if (rtsFlags != null) {
            rtsFlags.requireCurrent(); return displacement == 0 ? this : new ManagedAddress(null, null, displacedOffset(displacement), null, null, null, null, null, null, null, null, null, rtsFlags);
        }
        if (heap != null) { HeapAddresses.current().require(heap); if (displacement != 0) throw fault("Opaque guest heap address cannot be offset"); return this; }
        if (capabilities != null) { readCapabilitiesWord32(0, 4); if (displacement != 0) throw fault("RTS data label cannot be offset"); return this; }
        if (foreign != null) return fromReturnedAddress(foreign.plus(displacement));
        if (numeric != null) return NativeAddresses.current(null).recover(numeric + displacement);
        requireBytes();
        if (this == NULL) { if (displacement == 0) return this; throw fault("Cannot offset null Addr#"); }
        size();
        return displacement == 0 ? this : new ManagedAddress(literalBytes, mutableBytes, displacedOffset(displacement), owner, null, null, null, nativeOwner, null, null, compiler, null, null);
    }
    private long displacedOffset(long displacement) {
        try { return Math.addExact(offset, displacement); } catch (ArithmeticException failure) { throw fault("Managed Addr# offset overflow"); }
    }
    public long difference(ManagedAddress other) {
        var address = this;
        // Keep backing chains iterative so partial evaluation cannot recursively inline them.
        while (true) {
            if (address.foreign != null) address.foreign.requireCurrent();
            if (other.foreign != null) other.foreign.requireCurrent();
            var backing = address.foreign == null ? null : address.foreign.getBacking();
            var otherBacking = other.foreign == null ? null : other.foreign.getBacking();
            if (backing == null && otherBacking == null) return address.differenceUnwrapped(other);
            if (backing != null) address = backing;
            if (otherBacking != null) other = otherBacking;
        }
    }
    private long differenceUnwrapped(ManagedAddress other) {
        if (foreign != null) return foreign.compare(other, "difference");
        if (other.foreign != null) return -other.foreign.compare(this, "difference");
        if (nativeOwner != null) nativeOwner.requireLive(); if (other.nativeOwner != null) other.nativeOwner.requireLive();
        if (numeric != null || other.numeric != null || nativeOwner != null && other.nativeOwner != null) return toNativeBits() - other.toNativeBits();
        if (this == NULL && other == NULL) return 0;
        requireBytes(); other.requireBytes();
        boolean shared = owner != null && other.owner != null ? owner == other.owner :
            owner != null ? other.mutableBytes != null && owner.ownsStorage(other.mutableBytes) :
            other.owner != null ? mutableBytes != null && other.owner.ownsStorage(mutableBytes) :
            literalBytes != null ? literalBytes == other.literalBytes || literalBytes == other.mutableBytes :
            mutableBytes != null && (mutableBytes == other.mutableBytes || mutableBytes == other.literalBytes);
        if (!shared) throw fault("minusAddr# requires related managed pointers or numeric/native addresses");
        return offset - other.offset;
    }
    public long remainder(long divisor) {
        if (divisor == 0) throw fault("remAddr# divisor is zero");
        long bits;
        if (this == NULL || numeric != null || foreign != null || hasNativeStorage()) bits = toNativeBits();
        else { requireBytes(); size(); bits = offset; }
        return Long.remainderUnsigned(bits, divisor);
    }
    private int index(long displacement) { requireRange(displacement, 1); return (int) (offset + displacement); }

    public long indexChar(long displacement) { return readWord8(displacement); }
    @TruffleBoundary public String utf8() {
        var pointer = externalPointer();
        if (pointer != null) {
            byte[] bytes = pointer.copyOut(pointer.cStringLength());
            try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
            catch (CharacterCodingException failure) { throw fault("Invalid UTF-8 at the polyglot boundary"); }
        }
        requireRange(0, 1);
        var bytes = cbitsBuffer();
        int start = (int) offset, limit = (int) size();
        if (start < 0 || start >= limit) throw fault("UTF-8 address outside its backing storage");
        int end = start;
        while (end < limit && bytes.get(end) != 0) end++;
        if (end == limit) throw fault("Unterminated polyglot UTF-8 address");
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(bytes.slice(start, end - start)).toString(); }
        catch (CharacterCodingException failure) { throw fault("Invalid UTF-8 at the polyglot boundary"); }
    }
    public long readWord8(long displacement) { return readWord8Int(displacement); }
    public int readWord8Int(long displacement) {
        if (ioMode != null) return ioMode.readModeByte(displacement);
        if (rtsFlags != null) return (int) rtsFlags.readFlagByte(displacedOffset(displacement));
        var pointer = externalPointer();
        if (pointer != null) return (int) pointer.read(displacement, 1);
        if (nativeOwner != null) return readOwnedNativeByte(displacement);
        int index = index(displacement);
        if (owner != null) return owner.readByteInt(index);
        if (literalBytes != null) return literalBytes[index] & 255;
        if (mutableBytes == null) CompilerDirectives.transferToInterpreter();
        return java.util.Objects.requireNonNull(mutableBytes)[index] & 255;
    }
    @TruffleBoundary private int readOwnedNativeByte(long displacement) {
        try (var loan = nativeOwner.borrow()) {
            requireRange(displacement, 1);
            return loan.segment().get(ValueLayout.JAVA_BYTE, offset + displacement) & 255;
        }
    }
    @TruffleBoundary public long cStringLength() {
        if (foreign != null) return foreign.cStringLength();
        if (nativeOwner != null) try (var loan = nativeOwner.borrow()) {
            requireRange(0, 0);
            var segment = loan.segment();
            long limit = segment.byteSize() - offset;
            for (long length = 0; length < limit; length++)
                if (segment.get(ValueLayout.JAVA_BYTE, offset + length) == 0) return length;
            throw fault("Unterminated original C string inside managed Addr#");
        }
        if (owner == null) return scanString();
        synchronized (owner) { return scanString(); }
    }
    private long scanString() {
        long limit = availableBytes();
        for (long length = 0; length < limit; length++) if (readWord8Int(length) == 0) return length;
        throw fault("Unterminated original C string inside managed Addr#");
    }
    @TruffleBoundary public long compareBytes(ManagedAddress other, long count) {
        return withNativeBorrows(other, () -> {
            if (this != NULL || count != 0) requireByteRegion(count);
            if (other != NULL || count != 0) other.requireByteRegion(count);
            for (long index = 0; index < count; index++) {
                int difference = readWord8Int(index) - other.readWord8Int(index);
                if (difference != 0) return (long) difference;
            }
            return 0L;
        });
    }
    @TruffleBoundary public ManagedAddress findByte(long value, long count) {
        try (var loan = borrow()) {
            if (this != NULL || count != 0) requireByteRegion(count);
            int needle = (int) value & 255;
            for (long index = 0; index < count; index++) if (readWord8Int(index) == needle) return plus(index);
            return NULL;
        }
    }
    public void writeWord8(long displacement, long value) { writeWord8Int(displacement, (int) value); }
    public void writeWord8Int(long displacement, int value) {
        var pointer = externalPointer();
        if (pointer != null) { pointer.write(displacement, 1, value); return; }
        if (nativeOwner != null) { writeOwnedNativeByte(displacement, value); return; }
        if (owner != null) { owner.writeByteInt(index(displacement), value); return; }
        if (mutableBytes == null) throw fault("Cannot write through an immutable literal Addr#");
        int index = index(displacement); mutableBytes[index] = (byte) value;
    }
    @TruffleBoundary private void writeOwnedNativeByte(long displacement, int value) {
        try (var loan = nativeOwner.borrow()) {
            requireRange(displacement, 1, true);
            loan.segment().set(ValueLayout.JAVA_BYTE, offset + displacement, (byte) value);
        }
    }
    public void writeNativeInt(long elementOffset, int width, int value) { writeNativeInt(elementOffset, width, value, false); }
    public void writeNativeInt(long elementOffset, int width, int value, boolean byteOffset) {
        if (width != 2 && width != 4) throw fault("Unsupported managed Addr# scalar width");
        int stride = byteOffset ? 1 : width;
        if (elementOffset < Long.MIN_VALUE / stride || elementOffset > Long.MAX_VALUE / stride)
            throw fault("Managed Addr# element offset overflow");
        long displacement = elementOffset * stride;
        var pointer = externalPointer();
        if (pointer != null) { pointer.write(displacement, width, value); return; }
        requireRange(displacement, width, true);
        boolean little = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
        if (nativeOwner != null) { writeOwnedNativeScalar(displacement, width, value, little); return; }
        if (owner != null) { owner.writeNativeIntByteOffset(offset + displacement, width, value, little); return; }
        if (mutableBytes == null) throw fault("Cannot write through an immutable literal Addr#");
        int start = (int) (offset + displacement);
        for (int index = 0; index < width; index++) mutableBytes[start + index] = (byte) (value >>> ((little ? index : width - 1 - index) * 8));
    }
    public void writeNativeScalar(long elementOffset, int width, long value) { writeNativeScalar(elementOffset, width, value, false); }
    public void writeNativeScalar(long elementOffset, int width, long value, boolean byteOffset) {
        if (width != 2 && width != 4 && width != 8) throw fault("Unsupported managed Addr# scalar width");
        int stride = byteOffset ? 1 : width;
        if (elementOffset < Long.MIN_VALUE / stride || elementOffset > Long.MAX_VALUE / stride)
            throw fault("Managed Addr# element offset overflow");
        long displacement = elementOffset * stride;
        var pointer = externalPointer();
        if (pointer != null) { pointer.write(displacement, width, value); return; }
        requireRange(displacement, width, true);
        boolean little = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
        if (nativeOwner != null) { writeOwnedNativeScalar(displacement, width, value, little); return; }
        if (owner != null) { owner.writeNativeScalarByteOffset(offset + displacement, width, value, little); return; }
        if (mutableBytes == null) throw fault("Cannot write through an immutable literal Addr#");
        int start = (int) (offset + displacement);
        for (int index = 0; index < width; index++) mutableBytes[start + index] = (byte) (value >>> ((little ? index : width - 1 - index) * 8));
    }
    @TruffleBoundary private void writeOwnedNativeScalar(long displacement, int width, long value, boolean little) {
        try (var loan = nativeOwner.borrow()) {
            var segment = loan.segment();
            for (int index = 0; index < width; index++) {
                int shift = (little ? index : width - 1 - index) * 8;
                segment.set(ValueLayout.JAVA_BYTE, offset + displacement + index, (byte) (value >>> shift));
            }
        }
    }
    public ByteVector readVectorBytes(long elementOffset, int stride) { return readVectorBytes(elementOffset, stride, 16); }
    public ByteVector readVectorBytes(long elementOffset, int stride, int vectorBytes) {
        if (vectorBytes != 16 && vectorBytes != 32 && vectorBytes != 64) throw fault("Unsupported Addr# vector width");
        var species = ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8));
        long displacement = vectorDisplacement(elementOffset, stride);
        var pointer = externalPointer();
        if (pointer != null) return ByteVector.fromArray(species, pointer.plus(displacement).copyOut(vectorBytes), 0);
        if (nativeOwner != null) return readOwnedNativeVector(displacement, vectorBytes, species);
        requireRange(displacement, vectorBytes);
        long start = offset + displacement;
        if (owner != null) synchronized (owner) {
            var segment = owner.vectorSegment(start, true, 1, false, vectorBytes);
            return (ByteVector) VectorMemory.read(species, segment, start, ByteOrder.nativeOrder());
        }
        byte[] bytes = literalBytes != null ? literalBytes : mutableBytes;
        if (bytes == null) throw fault("Null Addr# has no backing storage");
        return ByteVector.fromArray(species, bytes, (int) start);
    }
    public void writeVectorBytes(long elementOffset, int stride, ByteVector value) { writeVectorBytes(elementOffset, stride, value, 16); }
    public void writeVectorBytes(long elementOffset, int stride, ByteVector value, int vectorBytes) {
        if (vectorBytes != 16 && vectorBytes != 32 && vectorBytes != 64) throw fault("Unsupported Addr# vector width");
        var vector = RuntimeTypes.requireByte(value, ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(vectorBytes * 8)));
        long displacement = vectorDisplacement(elementOffset, stride);
        var pointer = externalPointer();
        if (pointer != null) { pointer.plus(displacement).copyIn(vector.toArray()); return; }
        if (nativeOwner != null) { writeOwnedNativeVector(displacement, vectorBytes, vector); return; }
        requireRange(displacement, vectorBytes, true);
        long start = offset + displacement;
        if (owner != null) {
            synchronized (owner) {
                var segment = owner.vectorSegment(start, true, 1, true, vectorBytes);
                VectorMemory.write(vector, segment, start, ByteOrder.nativeOrder());
            }
            return;
        }
        if (mutableBytes == null) throw fault("Cannot write through an immutable literal Addr#");
        vector.intoArray(mutableBytes, (int) start);
    }
    @TruffleBoundary private ByteVector readOwnedNativeVector(long displacement, int vectorBytes, VectorSpecies<Byte> species) {
        try (var loan = nativeOwner.borrow()) {
            requireRange(displacement, vectorBytes);
            return (ByteVector) VectorMemory.read(species, loan.segment(), offset + displacement, ByteOrder.nativeOrder());
        }
    }
    @TruffleBoundary private void writeOwnedNativeVector(long displacement, int vectorBytes, ByteVector vector) {
        try (var loan = nativeOwner.borrow()) {
            requireRange(displacement, vectorBytes, true);
            VectorMemory.write(vector, loan.segment(), offset + displacement, ByteOrder.nativeOrder());
        }
    }
    private long vectorDisplacement(long elementOffset, int stride) {
        if (stride != 1 && stride != 2 && stride != 4 && stride != 8 && stride != 16 && stride != 32 && stride != 64)
            throw fault("Unsupported Addr# vector stride");
        if (elementOffset < Long.MIN_VALUE / stride || elementOffset > Long.MAX_VALUE / stride)
            throw fault("Managed Addr# vector offset overflow");
        return elementOffset * stride;
    }
    public void writeWord16(long elementOffset, long value) { writeNativeScalar(elementOffset, 2, value); }

    // The former inline callback is now a direct typed operation. Preserve the
    // owner monitor through preflight, backing-lock acquisition and mutation.
    int atomicInt(AtomicAddressOp operation, int operand, int replacement) {
        int width = operation.getWidth();
        requireRange(0, width, true);
        if (offset % width != 0) throw fault("Misaligned atomic Addr#");
        if (owner != null) synchronized (owner) {
            var segment = owner.atomicSegment(offset, width, true);
            synchronized (owner.storageKey()) { return operation.managedInt(segment, (int) offset, operand, replacement); }
        }
        byte[] bytes = literalBytes != null ? literalBytes : mutableBytes;
        if (bytes == null) throw fault("Atomic Addr# has no byte storage");
        synchronized (bytes) { return operation.managedInt(MemorySegment.ofArray(bytes), (int) offset, operand, replacement); }
    }
    int nativeAtomicInt(AtomicAddressOp operation, int operand, int replacement) {
        if (nativeOwner != null) return ownedNativeAtomicInt(operation, operand, replacement);
        if (owner == null) throw fault("Address has no owned native allocation");
        synchronized (owner) {
            var segment = owner.nativeSegment();
            if (segment == null) throw fault("Address has no owned native allocation");
            return operation.nativeInt(this, segment.asSlice(offset), operand, replacement);
        }
    }
    long nativeAtomicLong(AtomicAddressOp operation, long operand, long replacement) {
        if (nativeOwner != null) return ownedNativeAtomicLong(operation, operand, replacement);
        if (owner == null) throw fault("Address has no owned native allocation");
        synchronized (owner) {
            var segment = owner.nativeSegment();
            if (segment == null) throw fault("Address has no owned native allocation");
            return operation.nativeLong(this, segment.asSlice(offset), operand, replacement);
        }
    }
    @TruffleBoundary private int ownedNativeAtomicInt(AtomicAddressOp operation, int operand, int replacement) {
        try (var loan = nativeOwner.borrow()) {
            return operation.nativeInt(this, loan.segment().asSlice(offset), operand, replacement);
        }
    }
    @TruffleBoundary private long ownedNativeAtomicLong(AtomicAddressOp operation, long operand, long replacement) {
        try (var loan = nativeOwner.borrow()) {
            return operation.nativeLong(this, loan.segment().asSlice(offset), operand, replacement);
        }
    }
    @TruffleBoundary long nativeAtomicPointer(AtomicAddressOp operation, long expected, long desired) {
        try (var loan = nativeOwner.borrow()) {
            return operation.nativePointer(this, loan.segment().asSlice(offset), expected, desired);
        }
    }
    long atomicLong(AtomicAddressOp operation, long operand, long replacement) {
        int width = operation.getWidth(); boolean writable = operation != AtomicAddressOp.READ;
        requireRange(0, width, writable);
        if (offset % width != 0) throw fault("Misaligned atomic Addr#");
        if (owner != null) synchronized (owner) {
            var segment = owner.atomicSegment(offset, width, writable);
            synchronized (owner.storageKey()) { return operation.managedLong(segment, (int) offset, operand, replacement); }
        }
        byte[] bytes = literalBytes != null ? literalBytes : mutableBytes;
        if (bytes == null) throw fault("Atomic Addr# has no byte storage");
        synchronized (bytes) { return operation.managedLong(MemorySegment.ofArray(bytes), (int) offset, operand, replacement); }
    }
    public ManagedAddress atomicPointer(ManagedAddress expected, ManagedAddress desired) {
        if (owner == null) throw fault("Pointer atomic requires managed pointer cells or owned native storage");
        while (true) {
            ManagedAddress old;
            synchronized (owner) {
                requireRange(0, owner.getAddressWidth(), true);
                if (offset % owner.getAddressWidth() != 0) throw fault("Misaligned atomic pointer Addr#");
            }
            old = owner.readAddressByteOffset(offset);
            // Do not hold the cell owner across referent lifetime/registry locks.
            desired.sameLocation(desired);
            if (expected != null) expected.sameLocation(expected);
            old.sameLocation(old);
            boolean matches = expected == null || old.sameLocation(expected);
            synchronized (owner) {
                requireRange(0, owner.getAddressWidth(), true);
                if (owner.knownAddressByteOffset(offset) == old) {
                    if (matches) owner.writeAddressByteOffset(offset, desired);
                    return old;
                }
            }
        }
    }
    public void requireRange(long displacement, long count) { requireRange(displacement, count, false); }
    public void requireRange(long displacement, long count, boolean writable) {
        var pointer = externalPointer();
        if (pointer != null) { pointer.requireRange(displacement, count, writable); return; }
        if (writable && owner == null && mutableBytes == null && nativeOwner == null)
            throw fault("Cannot write through an immutable literal Addr#");
        if (writable && owner != null && !owner.isWritable()) throw fault("Cannot write through an immutable managed allocation");
        long start = displacedOffset(displacement), limit = size();
        if (count < 0 || start < 0 || start > limit || count > limit - start) throw fault("Managed Addr# range outside its backing storage");
    }
    public boolean overlaps(long displacement, long count, ManagedAddress other, long otherDisplacement, long otherCount) {
        requireRange(displacement, count); other.requireRange(otherDisplacement, otherCount);
        if (count == 0 || otherCount == 0) return false;
        var pointer = externalPointer(); var otherPointer = other.externalPointer();
        if (pointer != null || otherPointer != null) {
            if (pointer != null) return pointer.plus(displacement).overlaps(count, other.plus(otherDisplacement), otherCount);
            return otherPointer.plus(otherDisplacement).overlaps(otherCount, plus(displacement), count);
        }
        boolean shared = nativeOwner != null || other.nativeOwner != null ? nativeOwner != null && nativeOwner == other.nativeOwner :
            owner != null && other.owner != null ? owner == other.owner :
            owner != null ? other.mutableBytes != null && owner.ownsStorage(other.mutableBytes) :
            other.owner != null ? mutableBytes != null && other.owner.ownsStorage(mutableBytes) :
            literalBytes != null ? literalBytes == other.literalBytes || literalBytes == other.mutableBytes :
            mutableBytes != null && (mutableBytes == other.mutableBytes || mutableBytes == other.literalBytes);
        if (!shared) return false;
        long start = offset + displacement, otherStart = other.offset + otherDisplacement;
        return start < otherStart + otherCount && otherStart < start + count;
    }

    public ManagedAddress moveTo(ManagedAddress destination, long count) { copyTo(destination, count, true); return destination; }
    public void copyNonOverlappingTo(ManagedAddress destination, long count) { copyTo(destination, count, false); }
    private void requireDistinctArray(Object array) {
        boolean same;
        if (array instanceof ManagedAllocation allocation) same = owner == allocation ||
            mutableBytes != null && allocation.ownsStorage(mutableBytes) || literalBytes != null && allocation.ownsStorage(literalBytes);
        else if (array instanceof byte[] bytes) same = mutableBytes == bytes || literalBytes == bytes || owner != null && owner.ownsStorage(bytes);
        else throw fault("Expected a managed ByteArray#");
        if (same) throw fault("Array/address copy requires distinct backing allocations");
    }
    public void copyToByteArray(Object destination, long destinationOffset, long count) {
        var loan = borrow();
        Throwable failure = null;
        try {
            // A zero-byte copy from null still checks the destination and identity.
            if (this != NULL || count != 0) requireRange(0, count);
            requireDistinctArray(destination);
            long destinationSize = ManagedByteArray.sizeGuest(destination);
            if (destinationOffset < 0 || destinationOffset > destinationSize || count > destinationSize - destinationOffset)
                throw fault("ByteArray# copy range outside its backing storage");
            if (destination instanceof ManagedAllocation allocation && !allocation.isWritable())
                throw fault("Cannot write through an immutable managed allocation");
            if (count == 0) return;
            var pointer = externalPointer();
            if (pointer != null) {
                if (destination instanceof byte[] bytes) pointer.copyOutTo(bytes, destinationOffset, count);
                else if (destination instanceof ManagedAllocation allocation) {
                    if (pointer.isNative()) pointer.withNativeWindow(count, false, segment -> {
                        allocation.copyBytesIn(segment, 0, destinationOffset, count); return null;
                    });
                    else allocation.copyBytesIn(pointer.copyOut(count), 0, destinationOffset, count);
                }
                return;
            }
            if (nativeOwner != null) copyOwnedNativeToArray(destination, destinationOffset, count);
            else ManagedByteArray.copyGuest(owner != null ? owner : literalBytes != null ? literalBytes : mutableBytes,
                offset, destination, destinationOffset, count, false);
        } catch (Throwable error) {
            failure = error;
            throw error;
        } finally {
            if (loan != null) loan.closeAfter(failure);
        }
    }
    public void copyFromByteArray(Object source, long sourceOffset, long count) {
        var loan = borrow();
        Throwable failure = null;
        try {
            requireRange(0, count, true); requireDistinctArray(source);
            long sourceSize = ManagedByteArray.sizeGuest(source);
            if (sourceOffset < 0 || sourceOffset > sourceSize || count > sourceSize - sourceOffset)
                throw fault("ByteArray# copy range outside its backing storage");
            if (count == 0) return;
            var pointer = externalPointer();
            if (pointer != null) {
                if (source instanceof byte[] bytes) pointer.copyInFrom(bytes, sourceOffset, count);
                else if (source instanceof ManagedAllocation allocation && pointer.isNative()) pointer.withNativeWindow(count, true, segment -> {
                    allocation.copyBytesTo(sourceOffset, segment, 0, count); return null;
                });
                else {
                    byte[] bytes = new byte[(int) count];
                    ManagedByteArray.copyGuest(source, sourceOffset, bytes, 0, count, false); pointer.copyIn(bytes);
                }
                return;
            }
            if (nativeOwner != null) copyArrayToOwnedNative(source, sourceOffset, count);
            else ManagedByteArray.copyGuest(source, sourceOffset, owner != null ? owner : mutableBytes, offset, count, false);
        } catch (Throwable error) {
            failure = error;
            throw error;
        } finally {
            if (loan != null) loan.closeAfter(failure);
        }
    }
    @TruffleBoundary private void copyOwnedNativeToArray(Object destination, long destinationOffset, long count) {
        try (var access = nativeOwner.borrow()) {
            var source = access.segment();
            if (destination instanceof ManagedAllocation allocation) allocation.copyBytesIn(source, offset, destinationOffset, count);
            else MemorySegment.copy(source, offset, MemorySegment.ofArray((byte[]) destination), destinationOffset, count);
        }
    }
    @TruffleBoundary private void copyArrayToOwnedNative(Object source, long sourceOffset, long count) {
        try (var access = nativeOwner.borrow()) {
            var target = access.segment();
            if (source instanceof ManagedAllocation allocation) allocation.copyBytesTo(sourceOffset, target, offset, count);
            else MemorySegment.copy(MemorySegment.ofArray((byte[]) source), sourceOffset, target, offset, count);
        }
    }
    public void fill(long count, long value) {
        var loan = borrow();
        Throwable failure = null;
        try {
            requireRange(0, count, true);
            var pointer = externalPointer();
            if (pointer != null) pointer.fill(count, value);
            else if (nativeOwner != null) fillOwnedNative(count, value);
            else if (owner != null) owner.fill(offset, count, value);
            else java.util.Arrays.fill(java.util.Objects.requireNonNull(mutableBytes), (int) offset, (int) (offset + count), (byte) value);
        } catch (Throwable error) {
            failure = error;
            throw error;
        } finally {
            if (loan != null) loan.closeAfter(failure);
        }
    }
    @TruffleBoundary private void fillOwnedNative(long count, long value) {
        try (var access = nativeOwner.borrow()) { access.segment().asSlice(offset, count).fill((byte) value); }
    }
    // Bulk transfer and ordered lifetime acquisition stay outside guest graphs.
    @TruffleBoundary private void copyTo(ManagedAddress destination, long count, boolean allowOverlap) {
        withNativeBorrows(destination, () -> {
            requireRange(0, count); destination.requireRange(0, count, true);
            if (!allowOverlap && overlaps(0, count, destination, 0, count))
                throw fault("copyAddrToAddrNonOverlapping# requires disjoint regions");
            if (count == 0) return null;
            var pointer = externalPointer(); var targetPointer = destination.externalPointer();
            if (pointer != null || targetPointer != null) {
                if ((pointer != null || hasNativeStorage()) && (targetPointer != null || destination.hasNativeStorage())) {
                    var library = (pointer != null ? pointer : targetPointer).getOwner().getPackageCbits();
                    library.memory("copy", library.transport(destination), library.transport(this), count);
                } else if (pointer != null) {
                    Object storage = destination.owner != null ? destination.owner : destination.mutableBytes;
                    if (storage == null) throw fault("Missing copy destination storage");
                    copyToByteArray(storage, destination.offset, count);
                } else {
                    Object storage = owner != null ? owner : literalBytes != null ? literalBytes : mutableBytes;
                    if (storage == null) throw fault("Missing copy source storage");
                    destination.copyFromByteArray(storage, offset, count);
                }
                return null;
            }
            if (nativeOwner != null && destination.nativeOwner != null) {
                try (var source = nativeOwner.borrow(); var target = destination.nativeOwner.borrow()) {
                    MemorySegment.copy(source.segment(), offset, target.segment(), destination.offset, count);
                }
            } else if (nativeOwner != null) {
                try (var source = nativeOwner.borrow()) {
                    if (destination.owner != null) destination.owner.copyBytesIn(source.segment(), offset, destination.offset, count);
                    else MemorySegment.copy(source.segment(), offset, MemorySegment.ofArray(java.util.Objects.requireNonNull(destination.mutableBytes)), destination.offset, count);
                }
            } else if (destination.nativeOwner != null) {
                try (var target = destination.nativeOwner.borrow()) {
                    if (owner != null) owner.copyBytesTo(offset, target.segment(), destination.offset, count);
                    else {
                        byte[] bytes = literalBytes != null ? literalBytes : mutableBytes;
                        if (bytes == null) throw fault("Null Addr# has no backing storage");
                        MemorySegment.copy(MemorySegment.ofArray(bytes), offset, target.segment(), destination.offset, count);
                    }
                }
            } else if (owner != null && destination.owner != null) destination.owner.copyFrom(owner, offset, destination.offset, count);
            else if (owner != null) owner.copyBytesTo(offset, MemorySegment.ofArray(java.util.Objects.requireNonNull(destination.mutableBytes)), destination.offset, count);
            else {
                byte[] bytes = literalBytes != null ? literalBytes : mutableBytes;
                if (bytes == null) throw fault("Null Addr# has no backing storage");
                if (destination.owner != null) destination.owner.copyBytesIn(bytes, (int) offset, destination.offset, count);
                else System.arraycopy(bytes, (int) offset, java.util.Objects.requireNonNull(destination.mutableBytes), (int) destination.offset, (int) count);
            }
            return null;
        });
    }
    @TruffleBoundary @Override public String toString() {
        if (ioMode != null) return "Addr#(rts_IOManagerIsWin32Native)";
        if (heap != null) return "Addr#(opaque guest heap)";
        if (stable != null) return "Addr#(opaque StablePtr)";
        if (this == NULL) return "Addr#(null)";
        if (capabilities != null) return "Addr#(enabled_capabilities)";
        if (rtsFlags != null) return "Addr#(RtsFlags+" + offset + ")";
        if (foreign != null) return "Addr#(returned C pointer)";
        if (numeric != null) return "Addr#(unowned numeric address)";
        return "Addr#(" + (literalBytes != null ? "literal" : "managed") + "+" + offset + ")";
    }
    public ManagedAddress readAddressElementIndex(long elementOffset) { return readAddressElementIndex(elementOffset, false); }
    public ManagedAddress readAddressElementIndex(long elementOffset, boolean byteOffset) {
        var pointer = externalPointer();
        if (pointer != null) {
            long displacement;
            try { displacement = Math.multiplyExact(elementOffset, byteOffset ? 1L : 8L); }
            catch (ArithmeticException failure) { throw fault("Native Addr# element offset overflow"); }
            return pointer.readAddress(displacement);
        }
        if (nativeOwner != null) {
            long stride = byteOffset ? 1 : 8;
            if (elementOffset < Long.MIN_VALUE / stride || elementOffset > Long.MAX_VALUE / stride)
                throw fault("Native Addr# element offset overflow");
            long displacement = elementOffset * stride, bits = readOwnedNativePointer(displacement);
            var address = StablePointers.current(null).recoverToken(bits);
            if (address != null) return address;
            address = Language.currentState(null).getNativeAllocations().recoverAddress(bits);
            return address != null ? address : NativeAddresses.current(null).recover(bits);
        }
        if (owner == null) throw fault("Addr# has no allocation-owned pointer cells");
        long width = owner.getAddressWidth(), stride = byteOffset ? 1 : width;
        if (elementOffset < Long.MIN_VALUE / stride || elementOffset > Long.MAX_VALUE / stride)
            throw fault("Managed Addr# element offset overflow");
        long displacement = elementOffset * stride;
        requireRange(displacement, width); return owner.readAddressByteOffset(offset + displacement);
    }
    @TruffleBoundary private long readOwnedNativePointer(long displacement) {
        try (var loan = nativeOwner.borrow()) {
            requireRange(displacement, 8);
            return loan.segment().get(ValueLayout.JAVA_LONG_UNALIGNED, offset + displacement);
        }
    }
    public void writeAddressElementIndex(long elementOffset, ManagedAddress value) { writeAddressElementIndex(elementOffset, value, false); }
    public void writeAddressElementIndex(long elementOffset, ManagedAddress value, boolean byteOffset) {
        var pointer = externalPointer();
        if (pointer != null) {
            long displacement;
            try { displacement = Math.multiplyExact(elementOffset, byteOffset ? 1L : 8L); }
            catch (ArithmeticException failure) { throw fault("Native Addr# element offset overflow"); }
            pointer.writeAddress(displacement, value); return;
        }
        if (nativeOwner != null) { writeNativeScalar(elementOffset, 8, value.toNativeBits(), byteOffset); return; }
        if (owner == null) throw fault("Addr# has no allocation-owned pointer cells");
        long width = owner.getAddressWidth(), stride = byteOffset ? 1 : width;
        if (elementOffset < Long.MIN_VALUE / stride || elementOffset > Long.MAX_VALUE / stride)
            throw fault("Managed Addr# element offset overflow");
        long displacement = elementOffset * stride;
        requireRange(displacement, width, true); owner.writeAddressByteOffset(offset + displacement, value);
    }
    public void copyFromAllocationBytes(ManagedAllocation source, long sourceByteOffset, long countBytes) {
        if (owner == null) throw fault("Addr# has no allocation-owned pointer cells");
        requireRange(0, countBytes, true); owner.copyFrom(source, sourceByteOffset, offset, countBytes);
    }
    private static final Object NATIVE_BORROW_TIE = new Object();
    /** Synchronous multi-pointer calls retain every distinct native owner. */
    public static <T> T withNativeBorrows(List<ManagedAddress> addresses, Supplier<T> body) {
        var owners = new ArrayList<ManagedNativeAllocations.Owner>();
        for (var address : addresses) {
            var allocation = address.nativeOwner;
            if (allocation == null && address.foreign != null && address.foreign.getBacking() != null)
                allocation = address.foreign.getBacking().nativeAllocation();
            if (allocation != null && !owners.contains(allocation)) owners.add(allocation);
        }
        owners.sort((first, second) -> Integer.compareUnsigned(System.identityHashCode(first), System.identityHashCode(second)));
        boolean collision = false;
        for (int i = 1; i < owners.size(); i++)
            if (System.identityHashCode(owners.get(i - 1)) == System.identityHashCode(owners.get(i))) { collision = true; break; }
        if (collision) synchronized (NATIVE_BORROW_TIE) { return acquire(owners, 0, body); }
        return acquire(owners, 0, body);
    }
    private static <T> T acquire(List<ManagedNativeAllocations.Owner> owners, int index, Supplier<T> body) {
        if (index == owners.size()) return body.get();
        try (var loan = owners.get(index).borrow()) { return acquire(owners, index + 1, body); }
    }
    private static final ManagedAddress NULL = new ManagedAddress(null, null, 0);
    public static ManagedAddress nullAddress() { return NULL; }
    public static ManagedAddress fromNativeAllocation(ManagedNativeAllocations.Owner owner) {
        return new ManagedAddress(null, null, 0, null, null, null, null, owner, null, null, null, null, null);
    }
    public static ManagedAddress unownedNumeric(long bits) {
        return bits == 0 ? NULL : new ManagedAddress(null, null, 0, null, null, bits, null, null, null, null, null, null, null);
    }
    public static ManagedAddress fromReturnedAddress(PackageReturnedAddress address) {
        var backing = address.getBacking();
        return backing == null ? new ManagedAddress(null, null, 0, null, null, null, null, null, null, null, null, address, null) :
            new ManagedAddress(backing.literalBytes, backing.mutableBytes, backing.offset, backing.owner,
                backing.stable, backing.numeric, backing.finalizer, backing.nativeOwner, backing.capabilities,
                backing.heap, backing.compiler, address, null);
    }
    public static ManagedAddress fromNativeImageSource(Object source, long offset) {
        if (source instanceof ManagedAllocation allocation) return fromAllocation(allocation).plus(offset);
        if (source instanceof byte[] bytes) return new ManagedAddress(bytes, null, offset);
        throw fault("Invalid native image source");
    }
    public static ManagedAddress fromStableHandle(StablePointers.Handle handle) {
        return new ManagedAddress(null, null, 0, null, handle, null, null, null, null, null, null, null, null);
    }
    public static ManagedAddress fromHeapHandle(HeapAddresses.Handle handle) {
        return new ManagedAddress(null, null, 0, null, null, null, null, null, null, handle, null, null, null);
    }
    public static ManagedAddress fromCFinalizer(CFinalizerFunction function) {
        return new ManagedAddress(null, null, 0, null, null, null, function, null, null, null, null, null, null);
    }
    public static ManagedAddress enabledCapabilities(GuestThreads threads) {
        return new ManagedAddress(null, null, 0, null, null, null, null, null, threads, null, null, null, null);
    }
    public static ManagedAddress rtsFlags(CompilerRts compiler) {
        return new ManagedAddress(null, null, 0, null, null, null, null, null, null, null, null, null, compiler);
    }
    public static ManagedAddress fromByteArray(byte[] bytes) { return new ManagedAddress(null, bytes, 0); }
    public static ManagedAddress compilerCell(byte[] bytes, CompilerRts compiler) {
        return new ManagedAddress(null, bytes, 0, null, null, null, null, null, null, null, compiler, null, null);
    }
    public static ManagedAddress fromAllocation(ManagedAllocation allocation) {
        return new ManagedAddress(null, null, 0, allocation, null, null, null, null, null, null, null, null, null);
    }
    public static ManagedAddress fromStaticBytes(byte[] bytes) { return fromStaticBytes(bytes, 8); }
    public static ManagedAddress fromStaticBytes(byte[] bytes, int pointerBytes) {
        return fromAllocation(ManagedAllocation.immutable(bytes, pointerBytes, true));
    }
    public static ManagedAddress fromGuestByteArray(Object value) {
        if (value instanceof ManagedAllocation allocation) return fromAllocation(allocation);
        if (value instanceof byte[] bytes) return fromByteArray(bytes);
        throw fault("Expected a managed ByteArray#");
    }
    @TruffleBoundary public static ManagedAddress fromHex(String hex) {
        if (hex.length() % 2 != 0) throw new RuntimeFault("Malformed string-bytes literal");
        byte[] bytes = new byte[hex.length() / 2 + 1];
        for (int index = 0; index < bytes.length - 1; index++) {
            int high = digit(hex.charAt(index * 2)), low = digit(hex.charAt(index * 2 + 1));
            bytes[index] = (byte) ((high << 4) | low);
        }
        return new ManagedAddress(bytes, null, 0);
    }
    private static int digit(char value) {
        if (value >= '0' && value <= '9') return value - '0';
        if (value >= 'a' && value <= 'f') return value - 'a' + 10;
        if (value >= 'A' && value <= 'F') return value - 'A' + 10;
        throw new RuntimeFault("Malformed string-bytes literal");
    }
}
