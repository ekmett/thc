// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/** Plain native-endian four-byte elements in the existing shared byte[] storage.
 * Signed and unsigned reads differ only in widening into the Long carrier.
 * Both writes store the low 32 bits. No copied/boxed element array or atomics.
 */
public final class ManagedInt32Array {
    private ManagedInt32Array() {}
    private static final VarHandle ELEMENTS =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.nativeOrder());

    private static int byteOffset(byte[] bytes, long index) {
        // Reject the full-width element index before narrowing or scaling.
        // Any incomplete trailing element remains inaccessible.
        if (index < 0 || index >= bytes.length / Integer.BYTES) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("ByteArray# 32-bit index outside its backing storage");
        }
        return (int) index * Integer.BYTES;
    }

    private static int checkedByteOffset(byte[] bytes, long offset) {
        if (offset < 0 || offset > bytes.length - Integer.BYTES) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("ByteArray# 32-bit byte offset outside its backing storage");
        }
        return (int) offset;
    }

    public static long readSigned(byte[] bytes, long index) {
        return (int) ELEMENTS.get(bytes, byteOffset(bytes, index));
    }

    public static long readUnsigned(byte[] bytes, long index) {
        return Integer.toUnsignedLong((int) ELEMENTS.get(bytes, byteOffset(bytes, index)));
    }

    public static void write(byte[] bytes, long index, long value) {
        ELEMENTS.set(bytes, byteOffset(bytes, index), (int) value);
    }

    public static long readSignedByteOffset(byte[] bytes, long offset) {
        return (int) ELEMENTS.get(bytes, checkedByteOffset(bytes, offset));
    }

    public static long readUnsignedByteOffset(byte[] bytes, long offset) {
        return Integer.toUnsignedLong((int) ELEMENTS.get(bytes, checkedByteOffset(bytes, offset)));
    }

    public static void writeByteOffset(byte[] bytes, long offset, long value) {
        ELEMENTS.set(bytes, checkedByteOffset(bytes, offset), (int) value);
    }
}
