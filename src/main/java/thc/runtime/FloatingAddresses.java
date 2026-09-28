// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
/** Native-endian IEEE values preserve raw NaN and signed-zero bits. */
public final class FloatingAddresses {
    private FloatingAddresses() {}
    public static float readFloat(ManagedAddress address, long index) { return readFloat(address, index, false); }
    public static float readFloat(ManagedAddress address, long index, boolean byteOffset) { return Float.intBitsToFloat(ManagedAddressRead.WORD32.readInt(address, index, byteOffset)); }
    public static double readDouble(ManagedAddress address, long index) { return readDouble(address, index, false); }
    public static double readDouble(ManagedAddress address, long index, boolean byteOffset) { return Double.longBitsToDouble(ManagedAddressRead.WORD64.read(address, index, byteOffset)); }
    public static void writeFloat(ManagedAddress address, long index, float value) { writeFloat(address, index, value, false); }
    public static void writeFloat(ManagedAddress address, long index, float value, boolean byteOffset) { address.writeNativeScalar(index, 4, Float.floatToRawIntBits(value) & 0xffff_ffffL, byteOffset); }
    public static void writeDouble(ManagedAddress address, long index, double value) { writeDouble(address, index, value, false); }
    public static void writeDouble(ManagedAddress address, long index, double value, boolean byteOffset) { address.writeNativeScalar(index, 8, Double.doubleToRawLongBits(value), byteOffset); }
}
