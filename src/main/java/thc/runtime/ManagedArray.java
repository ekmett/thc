// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.WeakHashMap;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Object[] is the actual Array# storage; elements remain opaque mutable references. */
public final class ManagedArray {
    private ManagedArray() {}
    private static final VarHandle ELEMENT = MethodHandles.arrayElementVarHandle(Object[].class);
    private static final class FrozenMetadata {
        private static final WeakHashMap<Object[], Boolean> ARRAYS = new WeakHashMap<>();
    }
    @TruffleBoundary public static synchronized boolean isFrozen(Object[] array) {
        return FrozenMetadata.ARRAYS.containsKey(array);
    }
    @TruffleBoundary public static synchronized Object[] thaw(Object[] array) {
        FrozenMetadata.ARRAYS.remove(array);
        return array;
    }
    public static Object[] allocate(long size, Object initial) {
        if (size < 0 || size > Integer.MAX_VALUE) throw fault("Array# size outside the managed allocation domain");
        var array = new Object[(int) size];
        Arrays.fill(array, initial);
        return array;
    }
    public static Object[] require(Object value) {
        if (value == null || value.getClass() != Object[].class) throw fault("Expected managed Array# object storage");
        return (Object[]) value;
    }
    private static int index(Object[] array, long index) {
        if (index < 0 || index >= array.length) throw fault("Array# index outside its backing storage");
        return (int) index;
    }
    public static Object read(Object[] array, long index) { return array[index(array, index)]; }
    public static void write(Object[] array, long index, Object value) { array[index(array, index)] = value; }
    /** Full-memory-order CAS follows completed thunk indirections without forcing. */
    public static Object compareExchange(Object[] array, long index, Object expected, Object replacement) {
        int at = index(array, index);
        Object witness = ELEMENT.compareAndExchange(array, at, expected, replacement);
        if (witness == expected) return expected;
        while (ManagedMutVar.completedBoxedIdentity(witness) == ManagedMutVar.completedBoxedIdentity(expected)) {
            Object prior = witness;
            witness = ELEMENT.compareAndExchange(array, at, prior, replacement);
            if (witness == prior) return expected;
        }
        return witness;
    }
    public static Object[] slice(Object[] array, long offset, long count) {
        range(array, offset, count);
        return Arrays.copyOfRange(array, (int) offset, (int) (offset + count));
    }
    private static void range(Object[] array, long offset, long count) {
        if (offset < 0 || offset > array.length || count < 0 || count > array.length - offset)
            throw fault("Array# slice outside its backing storage");
    }
    public static long size(Object[] array) { return array.length; }
    public static void copy(Object[] source, long sourceOffset, Object[] destination, long destinationOffset,
                            long count, boolean mutableSource) {
        range(source, sourceOffset, count);
        range(destination, destinationOffset, count);
        if (!mutableSource && source == destination) throw fault("copyArray# requires distinct arrays");
        System.arraycopy(source, (int) sourceOffset, destination, (int) destinationOffset, (int) count);
    }
    @TruffleBoundary public static synchronized Object[] freeze(Object[] array) {
        FrozenMetadata.ARRAYS.put(array, true);
        return array;
    }
}
