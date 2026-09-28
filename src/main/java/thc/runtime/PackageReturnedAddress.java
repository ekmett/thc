// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Reference;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import thc.Language;
import static thc.runtime.RuntimeFault.fault;

/** Genuine C provenance retains its carrier and any known checked backing.
 * An unknown external pointer does not imply extent or deallocator ownership. */
public final class PackageReturnedAddress {
    private final Language.State owner;
    private final Assumption alive;
    private final Object carrier;
    private final ManagedAddress backing;
    private final long displacement;
    public PackageReturnedAddress(Language.State owner, Assumption alive, Object carrier, ManagedAddress backing) {
        this(owner, alive, carrier, backing, 0);
    }
    public PackageReturnedAddress(Language.State owner, Assumption alive, Object carrier, ManagedAddress backing, long displacement) {
        this.owner = owner; this.alive = alive; this.carrier = carrier; this.backing = backing; this.displacement = displacement;
    }
    public Language.State getOwner() { return owner; }
    public Object getCarrier() { return carrier; }
    public ManagedAddress getBacking() { return backing; }
    public void requireCurrent() {
        if (Language.currentState(null) != owner) throw fault("Returned package C pointer belongs to another context");
        if (!owner.getEnv().isNativeAccessAllowed()) throw fault("Returned package C pointer requires native access");
        if (!alive.isValid()) throw fault("Returned package C pointer registry is closed");
    }
    @TruffleBoundary public Object transport() {
        requireCurrent();
        if (backing != null) {
            backing.requireByteRegion$org_intelligence_thc(0, false);
            if (backing.hasNativeStorage$org_intelligence_thc() || backing.nativeImageKey$org_intelligence_thc() != null)
                return new PackageNativePointer(backing.toNativeBits(), null);
        }
        return displacement == 0 ? carrier : owner.getPackageCbits().pointer(carrier, displacement);
    }
    @TruffleBoundary public long bits() {
        requireCurrent();
        if (backing != null) return backing.toNativeBits();
        Object pointer = transport();
        var interop = InteropLibrary.getUncached();
        if (!interop.isPointer(pointer)) throw fault("Returned managed C pointer has no native address bits");
        try { return interop.asPointer(pointer); } catch (Exception failure) { throw rethrow(failure); }
    }
    @TruffleBoundary public boolean isNative() { requireCurrent(); return InteropLibrary.getUncached().isPointer(transport()); }
    @TruffleBoundary public <T> T withNativeWindow(long count, boolean writable, Function<MemorySegment, T> body) {
        requireRange(0, count, writable);
        var segment = MemorySegment.ofAddress(bits()).reinterpret(count);
        try { return body.apply(segment); } finally { Reference.reachabilityFence(this); }
    }
    @TruffleBoundary public long compare(ManagedAddress other, String operation) {
        requireCurrent();
        return ManagedAddress.Companion.withNativeBorrows$org_intelligence_thc(
            List.of(ManagedAddress.Companion.fromReturnedAddress$org_intelligence_thc(this), other), () -> {
                try { return InteropLibrary.getUncached().asLong(owner.getPackageCbits().memory(operation, transport(), owner.getPackageCbits().comparisonTransport(other))); }
                catch (Exception failure) { throw rethrow(failure); }
            });
    }
    public void requireRange(long offset, long count) { requireRange(offset, count, false); }
    @TruffleBoundary public void requireRange(long offset, long count, boolean writable) {
        requireCurrent();
        if (count < 0) throw fault("Negative returned C pointer access count");
        long relative;
        try { relative = Math.addExact(displacement, offset); Math.addExact(relative, count); }
        catch (ArithmeticException failure) { throw fault("Returned C pointer access offset overflow"); }
        if (backing != null) { backing.requireRange(offset, count, writable); return; }
        var interop = InteropLibrary.getUncached();
        if (interop.isPointer(carrier)) {
            long base;
            try { base = interop.asPointer(carrier); } catch (Exception failure) { throw rethrow(failure); }
            long start = base + relative;
            if (relative >= 0 && Long.compareUnsigned(start, base) < 0
                || relative < 0 && Long.compareUnsigned(start, base) > 0
                || Long.compareUnsigned(start + count, start) < 0 || start == 0 && count != 0)
                throw fault("Returned C pointer access wraps native address space");
        }
    }
    @TruffleBoundary public boolean overlaps(long count, ManagedAddress other, long otherCount) {
        requireRange(0, count); other.requireRange(0, otherCount, false);
        try { return InteropLibrary.getUncached().asInt(owner.getPackageCbits().memory("overlap", transport(), count, owner.getPackageCbits().transport(other), otherCount)) != 0; }
        catch (Exception failure) { throw rethrow(failure); }
    }
    @TruffleBoundary public long read(long offset, int width) {
        requireRange(offset, width);
        try { return InteropLibrary.getUncached().asLong(owner.getPackageCbits().memory("read", transport(), offset, width)); }
        catch (Exception failure) { throw rethrow(failure); }
    }
    @TruffleBoundary public void write(long offset, int width, long value) {
        requireRange(offset, width, true);
        owner.getPackageCbits().memory("write", transport(), offset, width, value);
    }
    @TruffleBoundary public long cStringLength() {
        requireCurrent();
        if (backing != null) return backing.cStringLength();
        try {
            long length = InteropLibrary.getUncached().asLong(owner.getPackageCbits().memory("strlen", transport()));
            if (length < 0) throw fault("Returned C string length exceeds signed count domain");
            return length;
        } catch (Exception failure) { throw rethrow(failure); }
    }
    @TruffleBoundary public byte[] copyOut(long count) {
        requireRange(0, count);
        if (count > Integer.MAX_VALUE) throw fault("Returned C copy exceeds managed array size");
        byte[] bytes = new byte[(int) count]; copyOutTo(bytes, 0, count); return bytes;
    }
    @TruffleBoundary public void copyOutTo(byte[] bytes, long offset, long count) {
        requireRange(0, count);
        if (count != 0) owner.getPackageCbits().memory("copy", owner.getPackageCbits().pointer(new CbitsBuffer(bytes, true), offset), transport(), count);
    }
    @TruffleBoundary public void copyIn(byte[] bytes) { copyInFrom(bytes, 0, bytes.length); }
    @TruffleBoundary public void copyInFrom(byte[] bytes, long offset, long count) {
        requireRange(0, count, true);
        if (count != 0) owner.getPackageCbits().memory("copy", transport(), owner.getPackageCbits().pointer(new CbitsBuffer(bytes, false), offset), count);
    }
    @TruffleBoundary public void fill(long count, long value) {
        requireRange(0, count, true);
        if (count != 0) owner.getPackageCbits().memory("fill", transport(), count, (int) value & 255);
    }
    @TruffleBoundary public ManagedAddress readAddress(long offset) {
        requireRange(offset, 8);
        Object result = Objects.requireNonNull(owner.getPackageCbits().memory("read_address", transport(), offset));
        var interop = InteropLibrary.getUncached();
        if (interop.isNull(result)) return ManagedAddress.Companion.nullAddress();
        if (interop.isPointer(result)) {
            long bits;
            try { bits = interop.asPointer(result); } catch (Exception failure) { throw rethrow(failure); }
            var known = owner.getStablePointers().recoverToken(bits);
            if (known == null) known = owner.getNativeAllocations().recoverAddress(bits);
            if (known == null) {
                var recovered = owner.getNativeAddresses().recover(bits);
                if (recovered.nativeImageKey$org_intelligence_thc() != null || recovered.hasNativeStorage$org_intelligence_thc()) known = recovered;
            }
            if (known != null) return known;
        }
        return ManagedAddress.Companion.fromReturnedAddress$org_intelligence_thc(new PackageReturnedAddress(owner, alive, result, null));
    }
    @TruffleBoundary public void writeAddress(long offset, ManagedAddress address) {
        requireRange(offset, 8, true);
        address.withNativeBorrow$org_intelligence_thc(() -> {
            Object pointer = owner.getPackageCbits().transport(address);
            return owner.getPackageCbits().memory("write_address", transport(), offset, pointer);
        });
    }
    @TruffleBoundary public PackageReturnedAddress plus(long displacement) {
        requireCurrent();
        if (displacement == 0) return this;
        long offset;
        try { offset = Math.addExact(this.displacement, displacement); }
        catch (ArithmeticException failure) { throw fault("Returned C pointer offset overflow"); }
        return new PackageReturnedAddress(owner, alive, carrier, backing == null ? null : backing.plus(displacement), offset);
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException rethrow(Throwable failure) throws E { throw (E) failure; }
}
