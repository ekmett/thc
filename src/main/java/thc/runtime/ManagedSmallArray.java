// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import static thc.runtime.RuntimeServiceStatus.fault;

public final class ManagedSmallArray {
    private ManagedSmallArray() {}
    public static SmallArrayStorage allocate(long size, Object initial) {
        if (size < 0 || size > Integer.MAX_VALUE) throw fault("SmallArray# size outside the managed allocation domain");
        var elements = new Object[(int) size];
        Arrays.fill(elements, initial);
        return new SmallArrayStorage(elements);
    }
    public static SmallArrayStorage require(Object value) {
        if (value instanceof SmallArrayStorage array) return array;
        throw fault("Expected managed SmallArray# storage");
    }
    public static long size(SmallArrayStorage array) { return array.getLogicalSize(); }
    private static int index(SmallArrayStorage array, long index) {
        if (index < 0 || index >= size(array)) throw fault("SmallArray# index outside its backing storage");
        return (int) index;
    }
    public static Object read(SmallArrayStorage array, long index) { return array.getElements()[index(array, index)]; }
    public static void write(SmallArrayStorage array, long index, Object value) { array.getElements()[index(array, index)] = value; }
    public static Object compareExchange(SmallArrayStorage array, long index, Object expected, Object replacement) {
        return ManagedArray.compareExchange(array.getElements(), index(array, index), expected, replacement);
    }
    public static SmallArrayStorage freeze(SmallArrayStorage array) { array.setFrozen(true); return array; }
    public static SmallArrayStorage thaw(SmallArrayStorage array) { array.setFrozen(false); return array; }
    private static void range(SmallArrayStorage array, long offset, long count) {
        long size = size(array);
        if (offset < 0 || offset > size || count < 0 || count > size - offset)
            throw fault("SmallArray# slice outside its backing storage");
    }
    public static SmallArrayStorage slice(SmallArrayStorage array, long offset, long count) {
        range(array, offset, count);
        return new SmallArrayStorage(Arrays.copyOfRange(array.getElements(), (int) offset, (int) (offset + count)));
    }
    public static void copy(SmallArrayStorage source, long sourceOffset, SmallArrayStorage destination,
                            long destinationOffset, long count, boolean mutableSource) {
        range(source, sourceOffset, count);
        range(destination, destinationOffset, count);
        if (!mutableSource && source == destination) throw fault("copySmallArray# requires distinct arrays");
        System.arraycopy(source.getElements(), (int) sourceOffset, destination.getElements(), (int) destinationOffset, (int) count);
    }
}
