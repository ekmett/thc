package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/** Native-endian 64-bit Int# views of the existing ByteArray# backing storage.
 * Plain accesses preserve byte aliasing without a wrapper, long[] copy or boxing.
 * This is not an atomic/concurrent array interface.
 */
public final class ManagedIntArray {
    private ManagedIntArray() {}
    private static final VarHandle ELEMENTS =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());

    private static int byteOffset(byte[] bytes, long index) {
        // Check the full-width element index before narrowing or multiplying.
        if (index < 0 || index >= bytes.length / Long.BYTES) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("ByteArray# Int index outside its backing storage");
        }
        return (int) index * Long.BYTES;
    }

    public static long read(byte[] bytes, long index) {
        return (long) ELEMENTS.get(bytes, byteOffset(bytes, index));
    }

    public static void write(byte[] bytes, long index, long value) {
        ELEMENTS.set(bytes, byteOffset(bytes, index), value);
    }
}
