package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/** Plain native-endian two-byte elements in shared byte[] storage.
 * Signed and unsigned reads widen into Long; writes retain the low 16 bits.
 * No copied/boxed element array, atomic access or changed freeze identity.
 */
public final class ManagedInt16Array {
    private ManagedInt16Array() {}
    private static final VarHandle ELEMENTS =
            MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.nativeOrder());

    private static int byteOffset(byte[] bytes, long index) {
        // Check the full-width element index before narrowing/scaling.
        // An odd trailing byte cannot supply a complete element.
        if (index < 0 || index >= bytes.length / Short.BYTES) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("ByteArray# 16-bit index outside its backing storage");
        }
        return (int) index * Short.BYTES;
    }

    public static long readSigned(byte[] bytes, long index) {
        return (short) ELEMENTS.get(bytes, byteOffset(bytes, index));
    }

    public static long readUnsigned(byte[] bytes, long index) {
        return Short.toUnsignedLong((short) ELEMENTS.get(bytes, byteOffset(bytes, index)));
    }

    public static void write(byte[] bytes, long index, long value) {
        ELEMENTS.set(bytes, byteOffset(bytes, index), (short) value);
    }
}
