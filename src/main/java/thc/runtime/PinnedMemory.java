// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.lang.foreign.ValueLayout;
import static thc.runtime.RuntimeServiceStatus.fault;
public final class PinnedMemory {
    private PinnedMemory() {}
    @TruffleBoundary public static ManagedAllocation allocate(long size, long alignment) {
        if (alignment <= 0 || (alignment & (alignment - 1)) != 0) throw fault("Pinned ByteArray# alignment must be a positive power of two");
        return ManagedAllocation.mutable(size, (int) ValueLayout.ADDRESS.byteSize(), true, alignment);
    }
    private static ManagedAllocation pointerArray(Object value) {
        if (value instanceof ManagedAllocation allocation) return allocation;
        throw fault("AddrArray# requires an allocation-owned pinned ByteArray#");
    }
    private static long byteOffset(ManagedAllocation array, long index) {
        long width = array.getAddressWidth();
        if (index < 0 || index > Long.MAX_VALUE / width) throw fault("AddrArray# element offset outside its backing storage");
        return index * width;
    }
    public static ManagedAddress readAddressArray(Object value, long index) { return readAddressArray(value, index, false); }
    public static ManagedAddress readAddressArray(Object value, long index, boolean byteOffset) {
        var array = pointerArray(value); return array.readAddressByteOffset(byteOffset ? index : byteOffset(array, index));
    }
    public static void writeAddressArray(Object value, long index, ManagedAddress address) { writeAddressArray(value, index, address, false); }
    public static void writeAddressArray(Object value, long index, ManagedAddress address, boolean byteOffset) {
        var array = pointerArray(value); array.writeAddressByteOffset(byteOffset ? index : byteOffset(array, index), address);
    }
}
