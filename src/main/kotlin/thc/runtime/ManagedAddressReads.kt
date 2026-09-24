// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import java.nio.ByteOrder

/** Native-endian scalar reads from managed storage, never process addresses.
 * Offsets count elements, including negative offsets from a derived address. */
internal enum class ManagedAddressRead(val width: Int, val payload: String) {
    WORD16(2, "Word16Rep"), INT16(2, "Int16Rep"),
    WORD32(4, "Word32Rep"), WIDE_CHAR(4, "WordRep"), WORD(8, "WordRep"), INT32(4, "Int32Rep"), INT(8, "IntRep"),
    WORD64(8, "Word64Rep"), INT64(8, "Int64Rep");

    fun read(address: ManagedAddress, elementOffset: Long): Long {
        if (elementOffset < Long.MIN_VALUE / width || elementOffset > Long.MAX_VALUE / width)
            fault("Managed Addr# element offset overflow")
        val displacement = elementOffset * width
        address.requireRange(displacement, width.toLong())
        var value = 0L
        for (byte in 0 until width) {
            val shift = if (littleEndian) byte * 8 else (width - 1 - byte) * 8
            value = value or (address.readWord8(displacement + byte) shl shift)
        }
        return if (this == INT16) value.toShort().toLong()
            else if (this == INT32) value.toInt().toLong()
            else value
    }

    companion object {
        private val littleEndian = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
    }
}
