// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import static thc.runtime.RuntimeFault.fault;

/** A synchronous native projection of typed pointer cells, never a raw-byte view. */
final class PackagePointerCells {
    private static final class Allocation {
        final ManagedAllocation owner;
        final Map<Integer, ManagedAddress> before;
        final long size;
        final Map<Integer, Long> encoded = new LinkedHashMap<>();
        Map<Integer, Long> original, written;
        Allocation(ManagedAllocation owner, Map<Integer, ManagedAddress> before) {
            this.owner = owner; this.before = before; size = owner.getSize();
        }
    }
    private final PackageScalarFunction entry;
    private final List<ManagedAddress> addresses = new ArrayList<>();
    private final IdentityHashMap<ManagedAllocation, Allocation> allocations = new IdentityHashMap<>();
    private final IdentityHashMap<ManagedAddress, Long> bits = new IdentityHashMap<>();
    private boolean active;

    private PackagePointerCells(PackageScalarFunction entry, List<ManagedAddress> arguments) {
        this.entry = entry;
        var seen = new IdentityHashMap<ManagedAddress, Boolean>();
        var pending = new ArrayList<>(arguments);
        for (int index = 0; index < pending.size(); index++) {
            ManagedAddress address = pending.get(index);
            if (seen.put(address, Boolean.TRUE) != null) continue;
            addresses.add(address);
            var owner = address.cbitsOwner();
            if (owner == null || allocations.containsKey(owner)) continue;
            var cells = owner.pointerSnapshot();
            if (!cells.isEmpty()) {
                active = true;
                if (!owner.isPinned() || owner.getAddressWidth() != ValueLayout.ADDRESS.byteSize())
                    throw fault("Package pointer cells require native pinned storage and host pointer width");
                pending.addAll(cells.values());
            }
            if (owner.isPinned()) allocations.put(owner, new Allocation(owner, cells));
        }
        if (!active) return;
        PackageFinalizerRegistry.requireCurrent(entry);
        // Validate the whole transitive graph before projecting any cell or native image.
        for (var allocation : allocations.values()) {
            allocation.owner.requirePointerSnapshot(allocation.before, allocation.size, entry);
            for (var value : allocation.before.values()) validateCell(value);
        }
    }
    private void validateCell(ManagedAddress value) {
        if (value == ManagedAddress.nullAddress()) return;
        var returned = value.returnedAddress();
        if (returned != null) {
            returned.requireCurrent();
            if (value.hasNativeStorage()) { value.requireRange(0, 0); return; }
            if (!returned.isNative()) throw fault("Managed C pointer has no native pointer-cell encoding");
            return;
        }
        if (value.stableHandle() != null) { entry.getOwner().getStablePointers().validate(value); return; }
        if (value.nativeAllocation() != null || value.cbitsOwner() != null && value.cbitsOwner().isPinned()) {
            value.requireRange(0, 0); return;
        }
        if (value.nativeImageKey() != null) { value.requireRange(0, 0); return; }
        throw fault("Package pointer cell requires a live native pointer, not moving or opaque managed storage");
    }
    boolean contains(ManagedAddress address) { return active && allocations.containsKey(address.cbitsOwner()); }
    boolean holdsAllocationMonitors() { return active; }
    long address(ManagedAddress address) {
        address.requireByteRegion(0, false);
        var owner = address.cbitsOwner();
        return owner.nativeSegment().address() + address.cbitsOffset();
    }
    private long encode(ManagedAddress value) {
        Long existing = bits.get(value);
        if (existing != null) return existing;
        long encoded = contains(value) ? address(value) : value.toNativeBits();
        bits.put(value, encoded); return encoded;
    }
    @TruffleBoundary
    static <T> T invoke(PackageScalarFunction entry, List<ManagedAddress> arguments,
            Function<PackagePointerCells, Supplier<T>> prepare, BiFunction<PackagePointerCells, T, T> finish) {
        var projection = new PackagePointerCells(entry, arguments);
        return ManagedAddress.withNativeBorrows(projection.addresses, () -> {
            // Argument views may acquire native registries. Prepare them before owner
            // monitors/materialization, but keep every transitive malloc owner borrowed.
            Supplier<T> call = prepare.apply(projection);
            T result = projection.invoke(call);
            // Returned aliases are recovered after writeback, before those borrows end.
            return finish.apply(projection, result);
        });
    }
    private <T> T invoke(Supplier<T> call) {
        if (!active) return call.get();
        // Borrowed malloc owners precede allocation monitors. Registry/image operations
        // also finish before those monitors; C holds no registry monitor.
        for (var allocation : allocations.values())
            for (var cell : allocation.before.entrySet()) allocation.encoded.put(cell.getKey(), encode(cell.getValue()));
        Throwable failure = null;
        T result = null;
        boolean[] called = {false};
        try {
            result = ManagedAllocation.withPointerLocks(new ArrayList<>(allocations.keySet()), () -> {
                for (var allocation : allocations.values()) {
                    allocation.owner.requirePointerSnapshot(allocation.before, allocation.size, entry);
                    allocation.original = allocation.owner.nativePointerValues(allocation.before);
                }
                Throwable callFailure = null;
                T value = null;
                try {
                    for (var allocation : allocations.values()) allocation.owner.materializePointerCells(allocation.encoded);
                    called[0] = true;
                    value = call.get();
                } catch (Throwable thrown) { callFailure = thrown;
                } finally {
                    for (var allocation : allocations.values()) {
                        try {
                            if (called[0]) allocation.written = allocation.owner.nativePointerValues(allocation.before);
                            else allocation.owner.materializePointerCells(allocation.original);
                        } catch (Throwable cleanup) {
                            if (callFailure == null) callFailure = cleanup;
                            else if (callFailure != cleanup) callFailure.addSuppressed(cleanup);
                        }
                    }
                }
                if (callFailure != null) throw rethrow(callFailure);
                return value;
            });
        } catch (Throwable thrown) { failure = thrown; }
        if (called[0]) {
            try { reconcile(); }
            catch (Throwable cleanup) {
                if (failure == null) failure = cleanup;
                else if (failure != cleanup) failure.addSuppressed(cleanup);
            }
        }
        Reference.reachabilityFence(addresses);
        if (failure != null) throw rethrow(failure);
        return result;
    }
    private void reconcile() {
        // Never take registry/referent lifetime locks while holding allocation monitors.
        for (var allocation : allocations.values())
            entry.getOwner().getNativeAddresses().registerPackageAllocation(allocation.owner);
        var recovered = new IdentityHashMap<Allocation, Map<Integer, ManagedAddress>>();
        for (var allocation : allocations.values()) {
            var after = new LinkedHashMap<Integer, ManagedAddress>();
            for (var cell : allocation.written.entrySet()) {
                int offset = cell.getKey(); long value = cell.getValue();
                after.put(offset, value == allocation.encoded.get(offset)
                    ? allocation.before.get(offset) : recover(value));
            }
            recovered.put(allocation, after);
        }
        for (var allocation : allocations.values())
            allocation.owner.reconcilePointerCells(allocation.before, allocation.written, recovered.get(allocation), entry);
    }
    private ManagedAddress recover(long value) {
        if (value == 0) return ManagedAddress.nullAddress();
        for (var known : bits.entrySet()) if (known.getValue() == value) return known.getKey();
        var retained = recoverBacking(value);
        return retained != null ? retained : recover(entry, value);
    }
    ManagedAddress recoverBacking(long value) {
        // A concurrent free removes an owner from registry recovery before waiting
        // for this call's borrow. Retained ranges still carry that owner's authority.
        for (var address : addresses) {
            var returned = address.returnedAddress();
            var candidate = returned != null && returned.getBacking() != null ? returned.getBacking() : address;
            if (!candidate.hasNativeStorage() && candidate.nativeImageKey() == null) continue;
            var allocation = allocations.get(candidate.cbitsOwner());
            long offset = candidate.cbitsOffset();
            long base = allocation != null ? allocation.owner.nativeSegment().address() : candidate.toNativeBits() - offset;
            long size = allocation != null ? allocation.size : candidate.cbitsSize();
            long displacement = value - base;
            if (Long.compareUnsigned(displacement, size) <= 0) return candidate.plus(displacement - offset);
        }
        return null;
    }
    static ManagedAddress recover(PackageScalarFunction entry, long value) {
        PackageFinalizerRegistry.requireCurrent(entry);
        if (value == 0) return ManagedAddress.nullAddress();
        var state = entry.getOwner();
        var known = state.getStablePointers().recoverToken(value);
        if (known == null) known = state.getNativeAllocations().recoverAddress(value);
        if (known == null) {
            var address = state.getNativeAddresses().recover(value);
            if (address.hasNativeStorage() || address.nativeImageKey() != null) known = address;
        }
        return known != null ? known : ManagedAddress.fromReturnedAddress(new PackageReturnedAddress(
            state, entry.getAlive(), new PackageNativePointer(value, null), null));
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException rethrow(Throwable failure) throws E { throw (E) failure; }
}
