// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

/** The width is fixed at lowering; unused upper result bits are canonical zeros. */
internal fun scalarBitPrimitiveShift(name: String): Int = when (name) {
    "popCnt8#", "clz8#", "ctz8#", "bitReverse8#", "pdep8#", "pext8#" -> 56
    "popCnt16#", "clz16#", "ctz16#", "byteSwap16#", "bitReverse16#", "pdep16#", "pext16#" -> 48
    "popCnt32#", "clz32#", "ctz32#", "byteSwap32#", "bitReverse32#", "pdep32#", "pext32#" -> 32
    else -> 0 // Machine Word# and explicit Word64# both have 64 bits.
}
