// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/** Plain native-endian primitive views of shared byte[] storage; no atomics or copies.
 * Element indices and explicit byte offsets are checked before narrowing.
 * Floating accesses preserve defined raw bits; signaling NaNs have no portable bit-copy promise.
 */
public final class ByteArrayAccess {
    private ByteArrayAccess() {}
    private static final VarHandle INTS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());
    private static final VarHandle INT16S = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.nativeOrder());
    private static final VarHandle INT32S = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.nativeOrder());
    private static final VarHandle FLOATS = MethodHandles.byteArrayViewVarHandle(float[].class, ByteOrder.nativeOrder());
    private static final VarHandle DOUBLES = MethodHandles.byteArrayViewVarHandle(double[].class, ByteOrder.nativeOrder());

    private static int elementOffset(byte[] bytes, long index, int width, String representation) {
        if (index < 0 || index >= bytes.length / width) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("ByteArray# " + representation + " index outside its backing storage");
        }
        return (int) index * width;
    }

    // Word8ArrayAs* counts bytes and permits unaligned starts.
    private static int byteOffset(byte[] bytes, long offset, int width, String representation) {
        if (offset < 0 || offset > (long) bytes.length - width) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("ByteArray# " + representation + " byte offset outside its backing storage");
        }
        return (int) offset;
    }

    // Int#/Word# share the same raw 64-bit carrier.
    public static long readInt(byte[] bytes, long index) {
        return (long) INTS.get(bytes, elementOffset(bytes, index, Long.BYTES, "Int"));
    }

    public static void writeInt(byte[] bytes, long index, long value) {
        INTS.set(bytes, elementOffset(bytes, index, Long.BYTES, "Int"), value);
    }

    // Int16#/Word16# reads widen; writes retain the low 16 bits.
    public static long readInt16(byte[] bytes, long index) {
        return (short) INT16S.get(bytes, elementOffset(bytes, index, Short.BYTES, "16-bit"));
    }

    public static long readWord16(byte[] bytes, long index) {
        return Short.toUnsignedLong((short) INT16S.get(bytes, elementOffset(bytes, index, Short.BYTES, "16-bit")));
    }

    public static void writeInt16(byte[] bytes, long index, long value) {
        INT16S.set(bytes, elementOffset(bytes, index, Short.BYTES, "16-bit"), (short) value);
    }

    public static long readInt16ByteOffset(byte[] bytes, long offset) {
        return (short) INT16S.get(bytes, byteOffset(bytes, offset, Short.BYTES, "16-bit"));
    }

    public static long readWord16ByteOffset(byte[] bytes, long offset) {
        return Short.toUnsignedLong((short) INT16S.get(bytes, byteOffset(bytes, offset, Short.BYTES, "16-bit")));
    }

    public static void writeInt16ByteOffset(byte[] bytes, long offset, long value) {
        INT16S.set(bytes, byteOffset(bytes, offset, Short.BYTES, "16-bit"), (short) value);
    }

    // Int32#/Word32# reads widen; writes retain the low 32 bits.
    public static long readInt32(byte[] bytes, long index) {
        return (int) INT32S.get(bytes, elementOffset(bytes, index, Integer.BYTES, "32-bit"));
    }

    public static long readWord32(byte[] bytes, long index) {
        return Integer.toUnsignedLong((int) INT32S.get(bytes, elementOffset(bytes, index, Integer.BYTES, "32-bit")));
    }

    public static void writeInt32(byte[] bytes, long index, long value) {
        INT32S.set(bytes, elementOffset(bytes, index, Integer.BYTES, "32-bit"), (int) value);
    }

    public static long readInt32ByteOffset(byte[] bytes, long offset) {
        return (int) INT32S.get(bytes, byteOffset(bytes, offset, Integer.BYTES, "32-bit"));
    }

    public static long readWord32ByteOffset(byte[] bytes, long offset) {
        return Integer.toUnsignedLong((int) INT32S.get(bytes, byteOffset(bytes, offset, Integer.BYTES, "32-bit")));
    }

    public static void writeInt32ByteOffset(byte[] bytes, long offset, long value) {
        INT32S.set(bytes, byteOffset(bytes, offset, Integer.BYTES, "32-bit"), (int) value);
    }

    // Float# uses primitive float access to four shared bytes.
    public static float readFloat(byte[] bytes, long index) {
        return (float) FLOATS.get(bytes, elementOffset(bytes, index, Float.BYTES, "Float"));
    }

    public static void writeFloat(byte[] bytes, long index, float value) {
        FLOATS.set(bytes, elementOffset(bytes, index, Float.BYTES, "Float"), value);
    }

    public static float readFloatByteOffset(byte[] bytes, long offset) {
        return (float) FLOATS.get(bytes, byteOffset(bytes, offset, Float.BYTES, "Float"));
    }

    public static void writeFloatByteOffset(byte[] bytes, long offset, float value) {
        FLOATS.set(bytes, byteOffset(bytes, offset, Float.BYTES, "Float"), value);
    }

    // Double# uses primitive double access to eight shared bytes.
    public static double readDouble(byte[] bytes, long index) {
        return (double) DOUBLES.get(bytes, elementOffset(bytes, index, Double.BYTES, "Double"));
    }

    public static void writeDouble(byte[] bytes, long index, double value) {
        DOUBLES.set(bytes, elementOffset(bytes, index, Double.BYTES, "Double"), value);
    }

    public static double readDoubleByteOffset(byte[] bytes, long offset) {
        return (double) DOUBLES.get(bytes, byteOffset(bytes, offset, Double.BYTES, "Double"));
    }

    public static void writeDoubleByteOffset(byte[] bytes, long offset, double value) {
        DOUBLES.set(bytes, byteOffset(bytes, offset, Double.BYTES, "Double"), value);
    }
}
