// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/** Plain native-endian four-byte Float# view of shared byte[] storage.
 * Primitive float accesses only, with no wrapper or copied element array.
 * Signaling NaNs have no cross-platform raw-bit roundtrip guarantee.
 */
public final class ManagedFloatArray {
    private ManagedFloatArray() {}
    private static final VarHandle ELEMENTS =
            MethodHandles.byteArrayViewVarHandle(float[].class, ByteOrder.nativeOrder());

    private static int byteOffset(byte[] bytes, long index) {
        if (index < 0 || index >= bytes.length / Float.BYTES) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("ByteArray# Float index outside its backing storage");
        }
        return (int) index * Float.BYTES;
    }

    public static float read(byte[] bytes, long index) {
        return (float) ELEMENTS.get(bytes, byteOffset(bytes, index));
    }

    public static void write(byte[] bytes, long index, float value) {
        ELEMENTS.set(bytes, byteOffset(bytes, index), value);
    }

    /** Unlike FloatArray#, Word8ArrayAsFloat# counts bytes, not elements. */
    private static int checkedByteOffset(byte[] bytes, long offset) {
        if (offset < 0 || offset > (long) bytes.length - Float.BYTES) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("ByteArray# Float byte offset outside its backing storage");
        }
        return (int) offset;
    }

    public static float readByteOffset(byte[] bytes, long offset) {
        return (float) ELEMENTS.get(bytes, checkedByteOffset(bytes, offset));
    }

    public static void writeByteOffset(byte[] bytes, long offset, float value) {
        ELEMENTS.set(bytes, checkedByteOffset(bytes, offset), value);
    }
}
