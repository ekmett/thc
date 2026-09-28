// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.nodes.ExplodeLoop;
import java.nio.ByteOrder;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Native-endian reads retain exact primitive carriers and the full native loan. */
public enum ManagedAddressRead {
    CHAR(1, "WordRep"), WORD16(2, "Word16Rep"), INT16(2, "Int16Rep"), WORD32(4, "Word32Rep"),
    WIDE_CHAR(4, "WordRep"), WORD(8, "WordRep"), INT32(4, "Int32Rep"), INT(8, "IntRep"),
    WORD64(8, "Word64Rep"), INT64(8, "Int64Rep");
    private final int width;
    private final String payload;
    private final boolean intCarrier;
    private static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
    ManagedAddressRead(int width, String payload) {
        this.width = width; this.payload = payload; intCarrier = NarrowInteger.fromRep(payload) != null;
    }
    public int getWidth() { return width; }
    public String getPayload() { return payload; }
    public boolean isInt() { return intCarrier; }
    public int readInt(ManagedAddress address, long elementOffset) { return readInt(address, elementOffset, false); }
    public int readInt(ManagedAddress address, long elementOffset, boolean byteOffset) {
        if (!intCarrier) throw fault("Expected a narrow address read");
        var loan = address.borrow();
        Throwable failure = null;
        try {
            return readIntBorrowed(address, elementOffset, byteOffset);
        } catch (Throwable error) {
            failure = error;
            throw error;
        } finally {
            if (loan != null) loan.closeAfter(failure);
        }
    }
    private int readIntBorrowed(ManagedAddress address, long elementOffset, boolean byteOffset) {
        Long capabilities = address.readCapabilitiesWord32(elementOffset, width);
        if (capabilities != null) {
            if (this != WORD32) throw fault("enabled_capabilities requires readWord32OffAddr#");
            return capabilities.intValue();
        }
        int stride = byteOffset ? 1 : width;
        if (elementOffset < Long.MIN_VALUE / stride || elementOffset > Long.MAX_VALUE / stride)
            throw fault("Managed Addr# element offset overflow");
        long displacement = elementOffset * stride;
        address.requireRange(displacement, width);
        int value = 0;
        for (int index = 0; index < width; index++)
            value |= address.readWord8Int(displacement + index) << ((LITTLE_ENDIAN ? index : width - 1 - index) * 8);
        return this == INT16 ? (short) value : value;
    }
    public long read(ManagedAddress address, long elementOffset) { return read(address, elementOffset, false); }
    public long read(ManagedAddress address, long elementOffset, boolean byteOffset) {
        if (intCarrier) throw fault("Expected a machine or 64-bit address read");
        var loan = address.borrow();
        Throwable failure = null;
        try {
            return readBorrowed(address, elementOffset, byteOffset);
        } catch (Throwable error) {
            failure = error;
            throw error;
        } finally {
            if (loan != null) loan.closeAfter(failure);
        }
    }
    @ExplodeLoop
    private long readBorrowed(ManagedAddress address, long elementOffset, boolean byteOffset) {
        Long capabilities = address.readCapabilitiesWord32(elementOffset, width);
        if (capabilities != null) {
            if (this != WORD32) throw fault("enabled_capabilities requires readWord32OffAddr#");
            return capabilities;
        }
        int stride = byteOffset ? 1 : width;
        if (elementOffset < Long.MIN_VALUE / stride || elementOffset > Long.MAX_VALUE / stride)
            throw fault("Managed Addr# element offset overflow");
        long displacement = elementOffset * stride;
        address.requireRange(displacement, width);
        long value = 0;
        for (int index = 0; index < width; index++)
            value |= address.readWord8(displacement + index) << ((LITTLE_ENDIAN ? index : width - 1 - index) * 8);
        if (this == INT16) return (short) value;
        if (this == INT32) return (int) value;
        return value;
    }
}
