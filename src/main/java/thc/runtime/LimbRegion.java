// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import static thc.runtime.RuntimeFault.fault;

/** Checked little-limb-first, native-byte-order regions retain guest storage. */
public final class LimbRegion {
    private final ManagedAddress address;
    private final long limbs;
    private final boolean writable;
    private LimbRegion(ManagedAddress address, long limbs, boolean writable) {
        this.address = address; this.limbs = limbs; this.writable = writable;
    }
    public ManagedAddress getAddress() { return address; }
    public long getLimbs() { return limbs; }
    public long getByteSize() { return limbs * 8; }
    public boolean overlaps(LimbRegion other) { return address.overlaps(0, getByteSize(), other.address, 0, other.getByteSize()); }
    public boolean sameStart(LimbRegion other) {
        return address.sameLocation(other.address) || overlaps(other)
            && address.cbitsOffset$org_intelligence_thc() == other.address.cbitsOffset$org_intelligence_thc();
    }
    public void requireOutput(long size) {
        if (!writable || limbs != size) throw fault("Limb output requires an exact writable region");
        address.requireRange(0, getByteSize(), true);
    }
    public void requirePositive() { if (limbs == 0) throw fault("This limb operation requires a nonempty input"); }
    public void validate() { address.requireRange(0, getByteSize(), writable); }
    public static LimbRegion read(Object value, long limbs) { return read(value, limbs, false); }
    public static LimbRegion read(Object value, long limbs, boolean allowEmpty) {
        return region(ManagedAddress.Companion.fromGuestByteArray(value), limbs, false, allowEmpty);
    }
    public static LimbRegion write(Object value, long limbs) { return write(value, limbs, false); }
    public static LimbRegion write(Object value, long limbs, boolean allowEmpty) {
        return region(ManagedAddress.Companion.fromGuestByteArray(value), limbs, true, allowEmpty);
    }
    public static LimbRegion region(ManagedAddress address, long limbs, boolean writable) { return region(address, limbs, writable, false); }
    public static LimbRegion region(ManagedAddress address, long limbs, boolean writable, boolean allowEmpty) {
        if (limbs < (allowEmpty ? 0 : 1) || limbs > Integer.MAX_VALUE / 8L)
            throw fault("Limb count outside managed Word-array domain");
        address.requireRange(0, limbs * 8, writable);
        return new LimbRegion(address, limbs, writable);
    }
}
