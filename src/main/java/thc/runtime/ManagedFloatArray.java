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
}
