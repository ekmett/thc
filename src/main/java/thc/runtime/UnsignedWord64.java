// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import java.math.BigInteger;
/** A Word64 result remains a Truffle number throughout the public interop boundary. */
@ExportLibrary(InteropLibrary.class)
public final class UnsignedWord64 implements TruffleObject {
    private final long bits;
    private final BigInteger number;
    public UnsignedWord64(long bits) {
        this.bits = bits;
        var signed = BigInteger.valueOf(bits);
        number = bits < 0 ? signed.add(BigInteger.ONE.shiftLeft(64)) : signed;
    }
    @ExportMessage public boolean isNumber() { return true; }
    @ExportMessage public boolean fitsInByte() { return bits >= 0 && bits <= Byte.MAX_VALUE; }
    @ExportMessage public boolean fitsInShort() { return bits >= 0 && bits <= Short.MAX_VALUE; }
    @ExportMessage public boolean fitsInInt() { return bits >= 0 && bits <= Integer.MAX_VALUE; }
    @ExportMessage public boolean fitsInLong() { return bits >= 0; }
    @ExportMessage public boolean fitsInBigInteger() { return true; }
    // Every Word64 is within either exponent range. Exactness depends only
    // on the span from its highest set bit to its lowest set bit.
    private boolean fitsBinary(int precision) {
        return bits == 0 || 64 - Long.numberOfLeadingZeros(bits) - Long.numberOfTrailingZeros(bits) <= precision;
    }
    @ExportMessage public boolean fitsInFloat() { return fitsBinary(24); }
    @ExportMessage public boolean fitsInDouble() { return fitsBinary(53); }
    @ExportMessage public byte asByte() throws UnsupportedMessageException {
        if (!fitsInByte()) throw UnsupportedMessageException.create(); return (byte) bits;
    }
    @ExportMessage public short asShort() throws UnsupportedMessageException {
        if (!fitsInShort()) throw UnsupportedMessageException.create(); return (short) bits;
    }
    @ExportMessage public int asInt() throws UnsupportedMessageException {
        if (!fitsInInt()) throw UnsupportedMessageException.create(); return (int) bits;
    }
    @ExportMessage public long asLong() throws UnsupportedMessageException {
        if (!fitsInLong()) throw UnsupportedMessageException.create(); return bits;
    }
    @ExportMessage public BigInteger asBigInteger() { return number; }
    @ExportMessage public float asFloat() throws UnsupportedMessageException {
        if (!fitsInFloat()) throw UnsupportedMessageException.create();
        // An exactly representable upper-half value has a zero low bit.
        return bits >= 0 ? (float) bits : (float) (bits >>> 1) * 2.0f;
    }
    @ExportMessage public double asDouble() throws UnsupportedMessageException {
        if (!fitsInDouble()) throw UnsupportedMessageException.create();
        return bits >= 0 ? (double) bits : (double) (bits >>> 1) * 2.0;
    }
}
