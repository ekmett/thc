// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.staticobject.DefaultStaticProperty;

/** Storage width and signedness are independent of the JVM Int computation
 * carrier. Word32 uses all 32 raw bits, including a negative JVM Int. */
public enum NarrowInteger {
    INT8("Int8Rep", 8, false), WORD8("Word8Rep", 8, true),
    INT16("Int16Rep", 16, false), WORD16("Word16Rep", 16, true),
    INT32("Int32Rep", 32, false), WORD32("Word32Rep", 32, true);

    private final String rep;
    private final int bits;
    private final boolean unsigned;
    private final Class<?> storageClass;

    NarrowInteger(String rep, int bits, boolean unsigned) {
        this.rep = rep;
        this.bits = bits;
        this.unsigned = unsigned;
        storageClass = switch (bits) { case 8 -> byte.class; case 16 -> short.class; default -> int.class; };
    }

    public String getRep() { return rep; }
    public int getBits() { return bits; }
    public boolean getUnsigned() { return unsigned; }
    public Class<?> getStorageClass() { return storageClass; }

    public int narrow(int value) {
        return switch (bits) {
            case 8 -> unsigned ? value & 255 : (byte) value;
            case 16 -> unsigned ? value & 65535 : (short) value;
            default -> value;
        };
    }

    /** Public scalar inputs are range checked before adopting the exact guest carrier. */
    public int fromHost(long value) {
        long min = unsigned ? 0L : -(1L << (bits - 1));
        long max = unsigned ? (1L << bits) - 1 : (1L << (bits - 1)) - 1;
        if (value < min || value > max) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeFault("Public narrow integer argument is out of range");
        }
        return (int) value;
    }

    /** Only declared widening/native-word boundaries use this conversion. */
    public long widen(int value) { return this == WORD32 ? Integer.toUnsignedLong(value) : narrow(value); }

    public int read(DefaultStaticProperty property, Object storage) {
        return switch (bits) {
            case 8 -> {
                int value = property.getByte(storage);
                yield unsigned ? value & 255 : value;
            }
            case 16 -> {
                int value = property.getShort(storage);
                yield unsigned ? value & 65535 : value;
            }
            default -> property.getInt(storage);
        };
    }

    public void write(DefaultStaticProperty property, Object storage, int value) {
        switch (bits) {
            case 8 -> property.setByte(storage, (byte) value);
            case 16 -> property.setShort(storage, (short) value);
            default -> property.setInt(storage, value);
        }
    }

    public static NarrowInteger fromRep(String rep) {
        if (rep == null) return null;
        return switch (rep) {
            case "Int8Rep" -> INT8;
            case "Word8Rep" -> WORD8;
            case "Int16Rep" -> INT16;
            case "Word16Rep" -> WORD16;
            case "Int32Rep" -> INT32;
            case "Word32Rep" -> WORD32;
            default -> null;
        };
    }
}
