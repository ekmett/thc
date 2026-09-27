// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

final class BitPrimitives {
    private BitPrimitives() {}

    /** The width is fixed at lowering; unused upper result bits are canonical zeros. */
    static int scalarBitPrimitiveShift(String name) {
        return switch (name) {
            case "popCnt8#", "clz8#", "ctz8#", "bitReverse8#", "pdep8#", "pext8#" -> 56;
            case "popCnt16#", "clz16#", "ctz16#", "byteSwap16#", "bitReverse16#", "pdep16#", "pext16#" -> 48;
            case "popCnt32#", "clz32#", "ctz32#", "byteSwap32#", "bitReverse32#", "pdep32#", "pext32#" -> 32;
            default -> 0; // Machine Word# and explicit Word64# both have 64 bits.
        };
    }
}
