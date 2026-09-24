// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/** Plain native-endian Double# views of shared byte[] storage. No arithmetic,
 * wrapper array or copy. Signaling NaNs have no cross-platform bit-copy promise.
 */
public final class ManagedDoubleArray {
    private ManagedDoubleArray() {}
    private static final VarHandle ELEMENTS =
            MethodHandles.byteArrayViewVarHandle(double[].class, ByteOrder.nativeOrder());

    private static int byteOffset(byte[] bytes, long index) {
        if (index < 0 || index >= bytes.length / Double.BYTES) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("ByteArray# Double index outside its backing storage");
        }
        return (int) index * Double.BYTES;
    }

    public static double read(byte[] bytes, long index) {
        return (double) ELEMENTS.get(bytes, byteOffset(bytes, index));
    }

    public static void write(byte[] bytes, long index, double value) {
        ELEMENTS.set(bytes, byteOffset(bytes, index), value);
    }

    /** Unlike DoubleArray#, Word8ArrayAsDouble# counts bytes, not elements. */
    private static int checkedByteOffset(byte[] bytes, long offset) {
        if (offset < 0 || offset > (long) bytes.length - Double.BYTES) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("ByteArray# Double byte offset outside its backing storage");
        }
        return (int) offset;
    }

    public static double readByteOffset(byte[] bytes, long offset) {
        return (double) ELEMENTS.get(bytes, checkedByteOffset(bytes, offset));
    }

    public static void writeByteOffset(byte[] bytes, long offset, double value) {
        ELEMENTS.set(bytes, checkedByteOffset(bytes, offset), value);
    }
}
