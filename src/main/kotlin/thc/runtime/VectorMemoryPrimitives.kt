// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")

package thc.runtime

import jdk.incubator.vector.VectorShape
import jdk.incubator.vector.IntVector
import jdk.incubator.vector.ByteVector
import jdk.incubator.vector.ShortVector
import jdk.incubator.vector.LongVector
import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.DoubleVector

import com.oracle.truffle.api.frame.VirtualFrame

/** Closed local memory families with exact vector representation proofs. */
internal enum class VectorMemoryFamily {
    INT8, WORD8, INT16, WORD16, INT32, WORD32, INT64, WORD64, FLOAT32, DOUBLE64;
    val vectorProof: CoreRepresentation get() = when (this) {
        INT32 -> CoreVectors.proof32
        WORD32 -> CoreVectors.proofWord32
        FLOAT32 -> CoreVectors.proofFloat
        DOUBLE64 -> CoreVectors.proofDouble
        INT8 -> CoreVectors.proof8
        WORD8 -> CoreVectors.proofWord8
        INT16 -> CoreVectors.proof16
        WORD16 -> CoreVectors.proofWord16
        INT64 -> CoreVectors.proof
        WORD64 -> GeneratedVectors.proofWord64X2
    }
}

/** These are local intrinsics, not vector function or aggregate ABIs. */
internal enum class VectorMemoryOp(val primitive: String, val scalarOffset: Boolean,
    val family: VectorMemoryFamily = VectorMemoryFamily.INT32, val vectorBytes: Int = 16,
    private val wideProof: CoreRepresentation? = null, val isAddress: Boolean = false) {
    INDEX_INT8X32("indexInt8X32Array#", false, VectorMemoryFamily.INT8, 32, GeneratedVectors.proofInt8X32),
    INDEX_INT8X32_SCALAR("indexInt8ArrayAsInt8X32#", true, VectorMemoryFamily.INT8, 32, GeneratedVectors.proofInt8X32),
    READ_INT8X32("readInt8X32Array#", false, VectorMemoryFamily.INT8, 32, GeneratedVectors.proofInt8X32),
    READ_INT8X32_SCALAR("readInt8ArrayAsInt8X32#", true, VectorMemoryFamily.INT8, 32, GeneratedVectors.proofInt8X32),
    WRITE_INT8X32("writeInt8X32Array#", false, VectorMemoryFamily.INT8, 32, GeneratedVectors.proofInt8X32),
    WRITE_INT8X32_SCALAR("writeInt8ArrayAsInt8X32#", true, VectorMemoryFamily.INT8, 32, GeneratedVectors.proofInt8X32),
    INDEX_WORD8X32("indexWord8X32Array#", false, VectorMemoryFamily.WORD8, 32, GeneratedVectors.proofWord8X32),
    INDEX_WORD8X32_SCALAR("indexWord8ArrayAsWord8X32#", true, VectorMemoryFamily.WORD8, 32, GeneratedVectors.proofWord8X32),
    READ_WORD8X32("readWord8X32Array#", false, VectorMemoryFamily.WORD8, 32, GeneratedVectors.proofWord8X32),
    READ_WORD8X32_SCALAR("readWord8ArrayAsWord8X32#", true, VectorMemoryFamily.WORD8, 32, GeneratedVectors.proofWord8X32),
    WRITE_WORD8X32("writeWord8X32Array#", false, VectorMemoryFamily.WORD8, 32, GeneratedVectors.proofWord8X32),
    WRITE_WORD8X32_SCALAR("writeWord8ArrayAsWord8X32#", true, VectorMemoryFamily.WORD8, 32, GeneratedVectors.proofWord8X32),
    INDEX_INT8X64("indexInt8X64Array#", false, VectorMemoryFamily.INT8, 64, GeneratedVectors.proofInt8X64),
    INDEX_INT8X64_SCALAR("indexInt8ArrayAsInt8X64#", true, VectorMemoryFamily.INT8, 64, GeneratedVectors.proofInt8X64),
    READ_INT8X64("readInt8X64Array#", false, VectorMemoryFamily.INT8, 64, GeneratedVectors.proofInt8X64),
    READ_INT8X64_SCALAR("readInt8ArrayAsInt8X64#", true, VectorMemoryFamily.INT8, 64, GeneratedVectors.proofInt8X64),
    WRITE_INT8X64("writeInt8X64Array#", false, VectorMemoryFamily.INT8, 64, GeneratedVectors.proofInt8X64),
    WRITE_INT8X64_SCALAR("writeInt8ArrayAsInt8X64#", true, VectorMemoryFamily.INT8, 64, GeneratedVectors.proofInt8X64),
    INDEX_WORD8X64("indexWord8X64Array#", false, VectorMemoryFamily.WORD8, 64, GeneratedVectors.proofWord8X64),
    INDEX_WORD8X64_SCALAR("indexWord8ArrayAsWord8X64#", true, VectorMemoryFamily.WORD8, 64, GeneratedVectors.proofWord8X64),
    READ_WORD8X64("readWord8X64Array#", false, VectorMemoryFamily.WORD8, 64, GeneratedVectors.proofWord8X64),
    READ_WORD8X64_SCALAR("readWord8ArrayAsWord8X64#", true, VectorMemoryFamily.WORD8, 64, GeneratedVectors.proofWord8X64),
    WRITE_WORD8X64("writeWord8X64Array#", false, VectorMemoryFamily.WORD8, 64, GeneratedVectors.proofWord8X64),
    WRITE_WORD8X64_SCALAR("writeWord8ArrayAsWord8X64#", true, VectorMemoryFamily.WORD8, 64, GeneratedVectors.proofWord8X64),
    INDEX_INT16X32("indexInt16X32Array#", false, VectorMemoryFamily.INT16, 64, GeneratedVectors.proofInt16X32),
    INDEX_INT16X32_SCALAR("indexInt16ArrayAsInt16X32#", true, VectorMemoryFamily.INT16, 64, GeneratedVectors.proofInt16X32),
    READ_INT16X32("readInt16X32Array#", false, VectorMemoryFamily.INT16, 64, GeneratedVectors.proofInt16X32),
    READ_INT16X32_SCALAR("readInt16ArrayAsInt16X32#", true, VectorMemoryFamily.INT16, 64, GeneratedVectors.proofInt16X32),
    WRITE_INT16X32("writeInt16X32Array#", false, VectorMemoryFamily.INT16, 64, GeneratedVectors.proofInt16X32),
    WRITE_INT16X32_SCALAR("writeInt16ArrayAsInt16X32#", true, VectorMemoryFamily.INT16, 64, GeneratedVectors.proofInt16X32),
    INDEX_WORD16X32("indexWord16X32Array#", false, VectorMemoryFamily.WORD16, 64, GeneratedVectors.proofWord16X32),
    INDEX_WORD16X32_SCALAR("indexWord16ArrayAsWord16X32#", true, VectorMemoryFamily.WORD16, 64, GeneratedVectors.proofWord16X32),
    READ_WORD16X32("readWord16X32Array#", false, VectorMemoryFamily.WORD16, 64, GeneratedVectors.proofWord16X32),
    READ_WORD16X32_SCALAR("readWord16ArrayAsWord16X32#", true, VectorMemoryFamily.WORD16, 64, GeneratedVectors.proofWord16X32),
    WRITE_WORD16X32("writeWord16X32Array#", false, VectorMemoryFamily.WORD16, 64, GeneratedVectors.proofWord16X32),
    WRITE_WORD16X32_SCALAR("writeWord16ArrayAsWord16X32#", true, VectorMemoryFamily.WORD16, 64, GeneratedVectors.proofWord16X32),
    INDEX_INT16X16("indexInt16X16Array#", false, VectorMemoryFamily.INT16, 32, GeneratedVectors.proofInt16X16),
    INDEX_INT16X16_SCALAR("indexInt16ArrayAsInt16X16#", true, VectorMemoryFamily.INT16, 32, GeneratedVectors.proofInt16X16),
    READ_INT16X16("readInt16X16Array#", false, VectorMemoryFamily.INT16, 32, GeneratedVectors.proofInt16X16),
    READ_INT16X16_SCALAR("readInt16ArrayAsInt16X16#", true, VectorMemoryFamily.INT16, 32, GeneratedVectors.proofInt16X16),
    WRITE_INT16X16("writeInt16X16Array#", false, VectorMemoryFamily.INT16, 32, GeneratedVectors.proofInt16X16),
    WRITE_INT16X16_SCALAR("writeInt16ArrayAsInt16X16#", true, VectorMemoryFamily.INT16, 32, GeneratedVectors.proofInt16X16),
    INDEX_WORD16X16("indexWord16X16Array#", false, VectorMemoryFamily.WORD16, 32, GeneratedVectors.proofWord16X16),
    INDEX_WORD16X16_SCALAR("indexWord16ArrayAsWord16X16#", true, VectorMemoryFamily.WORD16, 32, GeneratedVectors.proofWord16X16),
    READ_WORD16X16("readWord16X16Array#", false, VectorMemoryFamily.WORD16, 32, GeneratedVectors.proofWord16X16),
    READ_WORD16X16_SCALAR("readWord16ArrayAsWord16X16#", true, VectorMemoryFamily.WORD16, 32, GeneratedVectors.proofWord16X16),
    WRITE_WORD16X16("writeWord16X16Array#", false, VectorMemoryFamily.WORD16, 32, GeneratedVectors.proofWord16X16),
    WRITE_WORD16X16_SCALAR("writeWord16ArrayAsWord16X16#", true, VectorMemoryFamily.WORD16, 32, GeneratedVectors.proofWord16X16),
    INDEX_INT32X8("indexInt32X8Array#", false, VectorMemoryFamily.INT32, 32, GeneratedVectors.proofInt32X8),
    INDEX_INT32X8_SCALAR("indexInt32ArrayAsInt32X8#", true, VectorMemoryFamily.INT32, 32, GeneratedVectors.proofInt32X8),
    READ_INT32X8("readInt32X8Array#", false, VectorMemoryFamily.INT32, 32, GeneratedVectors.proofInt32X8),
    READ_INT32X8_SCALAR("readInt32ArrayAsInt32X8#", true, VectorMemoryFamily.INT32, 32, GeneratedVectors.proofInt32X8),
    WRITE_INT32X8("writeInt32X8Array#", false, VectorMemoryFamily.INT32, 32, GeneratedVectors.proofInt32X8),
    WRITE_INT32X8_SCALAR("writeInt32ArrayAsInt32X8#", true, VectorMemoryFamily.INT32, 32, GeneratedVectors.proofInt32X8),
    INDEX_WORD32X8("indexWord32X8Array#", false, VectorMemoryFamily.WORD32, 32, GeneratedVectors.proofWord32X8),
    INDEX_WORD32X8_SCALAR("indexWord32ArrayAsWord32X8#", true, VectorMemoryFamily.WORD32, 32, GeneratedVectors.proofWord32X8),
    READ_WORD32X8("readWord32X8Array#", false, VectorMemoryFamily.WORD32, 32, GeneratedVectors.proofWord32X8),
    READ_WORD32X8_SCALAR("readWord32ArrayAsWord32X8#", true, VectorMemoryFamily.WORD32, 32, GeneratedVectors.proofWord32X8),
    WRITE_WORD32X8("writeWord32X8Array#", false, VectorMemoryFamily.WORD32, 32, GeneratedVectors.proofWord32X8),
    WRITE_WORD32X8_SCALAR("writeWord32ArrayAsWord32X8#", true, VectorMemoryFamily.WORD32, 32, GeneratedVectors.proofWord32X8),
    INDEX_INT32X16("indexInt32X16Array#", false, VectorMemoryFamily.INT32, 64, GeneratedVectors.proofInt32X16),
    INDEX_INT32X16_SCALAR("indexInt32ArrayAsInt32X16#", true, VectorMemoryFamily.INT32, 64, GeneratedVectors.proofInt32X16),
    READ_INT32X16("readInt32X16Array#", false, VectorMemoryFamily.INT32, 64, GeneratedVectors.proofInt32X16),
    READ_INT32X16_SCALAR("readInt32ArrayAsInt32X16#", true, VectorMemoryFamily.INT32, 64, GeneratedVectors.proofInt32X16),
    WRITE_INT32X16("writeInt32X16Array#", false, VectorMemoryFamily.INT32, 64, GeneratedVectors.proofInt32X16),
    WRITE_INT32X16_SCALAR("writeInt32ArrayAsInt32X16#", true, VectorMemoryFamily.INT32, 64, GeneratedVectors.proofInt32X16),
    INDEX_WORD32X16("indexWord32X16Array#", false, VectorMemoryFamily.WORD32, 64, GeneratedVectors.proofWord32X16),
    INDEX_WORD32X16_SCALAR("indexWord32ArrayAsWord32X16#", true, VectorMemoryFamily.WORD32, 64, GeneratedVectors.proofWord32X16),
    READ_WORD32X16("readWord32X16Array#", false, VectorMemoryFamily.WORD32, 64, GeneratedVectors.proofWord32X16),
    READ_WORD32X16_SCALAR("readWord32ArrayAsWord32X16#", true, VectorMemoryFamily.WORD32, 64, GeneratedVectors.proofWord32X16),
    WRITE_WORD32X16("writeWord32X16Array#", false, VectorMemoryFamily.WORD32, 64, GeneratedVectors.proofWord32X16),
    WRITE_WORD32X16_SCALAR("writeWord32ArrayAsWord32X16#", true, VectorMemoryFamily.WORD32, 64, GeneratedVectors.proofWord32X16),
    INDEX_INT64X4("indexInt64X4Array#", false, VectorMemoryFamily.INT64, 32, GeneratedVectors.proofInt64X4),
    INDEX_INT64X4_SCALAR("indexInt64ArrayAsInt64X4#", true, VectorMemoryFamily.INT64, 32, GeneratedVectors.proofInt64X4),
    READ_INT64X4("readInt64X4Array#", false, VectorMemoryFamily.INT64, 32, GeneratedVectors.proofInt64X4),
    READ_INT64X4_SCALAR("readInt64ArrayAsInt64X4#", true, VectorMemoryFamily.INT64, 32, GeneratedVectors.proofInt64X4),
    WRITE_INT64X4("writeInt64X4Array#", false, VectorMemoryFamily.INT64, 32, GeneratedVectors.proofInt64X4),
    WRITE_INT64X4_SCALAR("writeInt64ArrayAsInt64X4#", true, VectorMemoryFamily.INT64, 32, GeneratedVectors.proofInt64X4),
    INDEX_WORD64X4("indexWord64X4Array#", false, VectorMemoryFamily.WORD64, 32, GeneratedVectors.proofWord64X4),
    INDEX_WORD64X4_SCALAR("indexWord64ArrayAsWord64X4#", true, VectorMemoryFamily.WORD64, 32, GeneratedVectors.proofWord64X4),
    READ_WORD64X4("readWord64X4Array#", false, VectorMemoryFamily.WORD64, 32, GeneratedVectors.proofWord64X4),
    READ_WORD64X4_SCALAR("readWord64ArrayAsWord64X4#", true, VectorMemoryFamily.WORD64, 32, GeneratedVectors.proofWord64X4),
    WRITE_WORD64X4("writeWord64X4Array#", false, VectorMemoryFamily.WORD64, 32, GeneratedVectors.proofWord64X4),
    WRITE_WORD64X4_SCALAR("writeWord64ArrayAsWord64X4#", true, VectorMemoryFamily.WORD64, 32, GeneratedVectors.proofWord64X4),
    INDEX_INT64X8("indexInt64X8Array#", false, VectorMemoryFamily.INT64, 64, GeneratedVectors.proofInt64X8),
    INDEX_INT64X8_SCALAR("indexInt64ArrayAsInt64X8#", true, VectorMemoryFamily.INT64, 64, GeneratedVectors.proofInt64X8),
    READ_INT64X8("readInt64X8Array#", false, VectorMemoryFamily.INT64, 64, GeneratedVectors.proofInt64X8),
    READ_INT64X8_SCALAR("readInt64ArrayAsInt64X8#", true, VectorMemoryFamily.INT64, 64, GeneratedVectors.proofInt64X8),
    WRITE_INT64X8("writeInt64X8Array#", false, VectorMemoryFamily.INT64, 64, GeneratedVectors.proofInt64X8),
    WRITE_INT64X8_SCALAR("writeInt64ArrayAsInt64X8#", true, VectorMemoryFamily.INT64, 64, GeneratedVectors.proofInt64X8),
    INDEX_WORD64X8("indexWord64X8Array#", false, VectorMemoryFamily.WORD64, 64, GeneratedVectors.proofWord64X8),
    INDEX_WORD64X8_SCALAR("indexWord64ArrayAsWord64X8#", true, VectorMemoryFamily.WORD64, 64, GeneratedVectors.proofWord64X8),
    READ_WORD64X8("readWord64X8Array#", false, VectorMemoryFamily.WORD64, 64, GeneratedVectors.proofWord64X8),
    READ_WORD64X8_SCALAR("readWord64ArrayAsWord64X8#", true, VectorMemoryFamily.WORD64, 64, GeneratedVectors.proofWord64X8),
    WRITE_WORD64X8("writeWord64X8Array#", false, VectorMemoryFamily.WORD64, 64, GeneratedVectors.proofWord64X8),
    WRITE_WORD64X8_SCALAR("writeWord64ArrayAsWord64X8#", true, VectorMemoryFamily.WORD64, 64, GeneratedVectors.proofWord64X8),
    INDEX_FLOATX8("indexFloatX8Array#", false, VectorMemoryFamily.FLOAT32, 32, GeneratedVectors.proofFloatX8),
    INDEX_FLOATX8_SCALAR("indexFloatArrayAsFloatX8#", true, VectorMemoryFamily.FLOAT32, 32, GeneratedVectors.proofFloatX8),
    READ_FLOATX8("readFloatX8Array#", false, VectorMemoryFamily.FLOAT32, 32, GeneratedVectors.proofFloatX8),
    READ_FLOATX8_SCALAR("readFloatArrayAsFloatX8#", true, VectorMemoryFamily.FLOAT32, 32, GeneratedVectors.proofFloatX8),
    WRITE_FLOATX8("writeFloatX8Array#", false, VectorMemoryFamily.FLOAT32, 32, GeneratedVectors.proofFloatX8),
    WRITE_FLOATX8_SCALAR("writeFloatArrayAsFloatX8#", true, VectorMemoryFamily.FLOAT32, 32, GeneratedVectors.proofFloatX8),
    INDEX_FLOATX16("indexFloatX16Array#", false, VectorMemoryFamily.FLOAT32, 64, GeneratedVectors.proofFloatX16),
    INDEX_FLOATX16_SCALAR("indexFloatArrayAsFloatX16#", true, VectorMemoryFamily.FLOAT32, 64, GeneratedVectors.proofFloatX16),
    READ_FLOATX16("readFloatX16Array#", false, VectorMemoryFamily.FLOAT32, 64, GeneratedVectors.proofFloatX16),
    READ_FLOATX16_SCALAR("readFloatArrayAsFloatX16#", true, VectorMemoryFamily.FLOAT32, 64, GeneratedVectors.proofFloatX16),
    WRITE_FLOATX16("writeFloatX16Array#", false, VectorMemoryFamily.FLOAT32, 64, GeneratedVectors.proofFloatX16),
    WRITE_FLOATX16_SCALAR("writeFloatArrayAsFloatX16#", true, VectorMemoryFamily.FLOAT32, 64, GeneratedVectors.proofFloatX16),
    INDEX_DOUBLEX4("indexDoubleX4Array#", false, VectorMemoryFamily.DOUBLE64, 32, GeneratedVectors.proofDoubleX4),
    INDEX_DOUBLEX4_SCALAR("indexDoubleArrayAsDoubleX4#", true, VectorMemoryFamily.DOUBLE64, 32, GeneratedVectors.proofDoubleX4),
    READ_DOUBLEX4("readDoubleX4Array#", false, VectorMemoryFamily.DOUBLE64, 32, GeneratedVectors.proofDoubleX4),
    READ_DOUBLEX4_SCALAR("readDoubleArrayAsDoubleX4#", true, VectorMemoryFamily.DOUBLE64, 32, GeneratedVectors.proofDoubleX4),
    WRITE_DOUBLEX4("writeDoubleX4Array#", false, VectorMemoryFamily.DOUBLE64, 32, GeneratedVectors.proofDoubleX4),
    WRITE_DOUBLEX4_SCALAR("writeDoubleArrayAsDoubleX4#", true, VectorMemoryFamily.DOUBLE64, 32, GeneratedVectors.proofDoubleX4),
    INDEX_DOUBLEX8("indexDoubleX8Array#", false, VectorMemoryFamily.DOUBLE64, 64, GeneratedVectors.proofDoubleX8),
    INDEX_DOUBLEX8_SCALAR("indexDoubleArrayAsDoubleX8#", true, VectorMemoryFamily.DOUBLE64, 64, GeneratedVectors.proofDoubleX8),
    READ_DOUBLEX8("readDoubleX8Array#", false, VectorMemoryFamily.DOUBLE64, 64, GeneratedVectors.proofDoubleX8),
    READ_DOUBLEX8_SCALAR("readDoubleArrayAsDoubleX8#", true, VectorMemoryFamily.DOUBLE64, 64, GeneratedVectors.proofDoubleX8),
    WRITE_DOUBLEX8("writeDoubleX8Array#", false, VectorMemoryFamily.DOUBLE64, 64, GeneratedVectors.proofDoubleX8),
    WRITE_DOUBLEX8_SCALAR("writeDoubleArrayAsDoubleX8#", true, VectorMemoryFamily.DOUBLE64, 64, GeneratedVectors.proofDoubleX8),
    INDEX_INT8_ADDRESS("indexInt8X16OffAddr#", false, VectorMemoryFamily.INT8, isAddress = true),
    INDEX_INT8_ADDRESS_SCALAR("indexInt8OffAddrAsInt8X16#", true, VectorMemoryFamily.INT8, isAddress = true),
    READ_INT8_ADDRESS("readInt8X16OffAddr#", false, VectorMemoryFamily.INT8, isAddress = true),
    READ_INT8_ADDRESS_SCALAR("readInt8OffAddrAsInt8X16#", true, VectorMemoryFamily.INT8, isAddress = true),
    WRITE_INT8_ADDRESS("writeInt8X16OffAddr#", false, VectorMemoryFamily.INT8, isAddress = true),
    WRITE_INT8_ADDRESS_SCALAR("writeInt8OffAddrAsInt8X16#", true, VectorMemoryFamily.INT8, isAddress = true),
    INDEX_WORD8_ADDRESS("indexWord8X16OffAddr#", false, VectorMemoryFamily.WORD8, isAddress = true),
    INDEX_WORD8_ADDRESS_SCALAR("indexWord8OffAddrAsWord8X16#", true, VectorMemoryFamily.WORD8, isAddress = true),
    READ_WORD8_ADDRESS("readWord8X16OffAddr#", false, VectorMemoryFamily.WORD8, isAddress = true),
    READ_WORD8_ADDRESS_SCALAR("readWord8OffAddrAsWord8X16#", true, VectorMemoryFamily.WORD8, isAddress = true),
    WRITE_WORD8_ADDRESS("writeWord8X16OffAddr#", false, VectorMemoryFamily.WORD8, isAddress = true),
    WRITE_WORD8_ADDRESS_SCALAR("writeWord8OffAddrAsWord8X16#", true, VectorMemoryFamily.WORD8, isAddress = true),
    INDEX_INT16_ADDRESS("indexInt16X8OffAddr#", false, VectorMemoryFamily.INT16, isAddress = true),
    INDEX_INT16_ADDRESS_SCALAR("indexInt16OffAddrAsInt16X8#", true, VectorMemoryFamily.INT16, isAddress = true),
    READ_INT16_ADDRESS("readInt16X8OffAddr#", false, VectorMemoryFamily.INT16, isAddress = true),
    READ_INT16_ADDRESS_SCALAR("readInt16OffAddrAsInt16X8#", true, VectorMemoryFamily.INT16, isAddress = true),
    WRITE_INT16_ADDRESS("writeInt16X8OffAddr#", false, VectorMemoryFamily.INT16, isAddress = true),
    WRITE_INT16_ADDRESS_SCALAR("writeInt16OffAddrAsInt16X8#", true, VectorMemoryFamily.INT16, isAddress = true),
    INDEX_WORD16_ADDRESS("indexWord16X8OffAddr#", false, VectorMemoryFamily.WORD16, isAddress = true),
    INDEX_WORD16_ADDRESS_SCALAR("indexWord16OffAddrAsWord16X8#", true, VectorMemoryFamily.WORD16, isAddress = true),
    READ_WORD16_ADDRESS("readWord16X8OffAddr#", false, VectorMemoryFamily.WORD16, isAddress = true),
    READ_WORD16_ADDRESS_SCALAR("readWord16OffAddrAsWord16X8#", true, VectorMemoryFamily.WORD16, isAddress = true),
    WRITE_WORD16_ADDRESS("writeWord16X8OffAddr#", false, VectorMemoryFamily.WORD16, isAddress = true),
    WRITE_WORD16_ADDRESS_SCALAR("writeWord16OffAddrAsWord16X8#", true, VectorMemoryFamily.WORD16, isAddress = true),
    INDEX_INT64_ADDRESS("indexInt64X2OffAddr#", false, VectorMemoryFamily.INT64, isAddress = true),
    INDEX_INT64_ADDRESS_SCALAR("indexInt64OffAddrAsInt64X2#", true, VectorMemoryFamily.INT64, isAddress = true),
    READ_INT64_ADDRESS("readInt64X2OffAddr#", false, VectorMemoryFamily.INT64, isAddress = true),
    READ_INT64_ADDRESS_SCALAR("readInt64OffAddrAsInt64X2#", true, VectorMemoryFamily.INT64, isAddress = true),
    WRITE_INT64_ADDRESS("writeInt64X2OffAddr#", false, VectorMemoryFamily.INT64, isAddress = true),
    WRITE_INT64_ADDRESS_SCALAR("writeInt64OffAddrAsInt64X2#", true, VectorMemoryFamily.INT64, isAddress = true),
    INDEX_WORD64_ADDRESS("indexWord64X2OffAddr#", false, VectorMemoryFamily.WORD64, isAddress = true),
    INDEX_WORD64_ADDRESS_SCALAR("indexWord64OffAddrAsWord64X2#", true, VectorMemoryFamily.WORD64, isAddress = true),
    READ_WORD64_ADDRESS("readWord64X2OffAddr#", false, VectorMemoryFamily.WORD64, isAddress = true),
    READ_WORD64_ADDRESS_SCALAR("readWord64OffAddrAsWord64X2#", true, VectorMemoryFamily.WORD64, isAddress = true),
    WRITE_WORD64_ADDRESS("writeWord64X2OffAddr#", false, VectorMemoryFamily.WORD64, isAddress = true),
    WRITE_WORD64_ADDRESS_SCALAR("writeWord64OffAddrAsWord64X2#", true, VectorMemoryFamily.WORD64, isAddress = true),
    INDEX_INT8("indexInt8X16Array#", false, VectorMemoryFamily.INT8),
    INDEX_INT8_SCALAR("indexInt8ArrayAsInt8X16#", true, VectorMemoryFamily.INT8),
    READ_INT8("readInt8X16Array#", false, VectorMemoryFamily.INT8),
    READ_INT8_SCALAR("readInt8ArrayAsInt8X16#", true, VectorMemoryFamily.INT8),
    WRITE_INT8("writeInt8X16Array#", false, VectorMemoryFamily.INT8),
    WRITE_INT8_SCALAR("writeInt8ArrayAsInt8X16#", true, VectorMemoryFamily.INT8),
    INDEX_WORD8("indexWord8X16Array#", false, VectorMemoryFamily.WORD8),
    INDEX_WORD8_SCALAR("indexWord8ArrayAsWord8X16#", true, VectorMemoryFamily.WORD8),
    READ_WORD8("readWord8X16Array#", false, VectorMemoryFamily.WORD8),
    READ_WORD8_SCALAR("readWord8ArrayAsWord8X16#", true, VectorMemoryFamily.WORD8),
    WRITE_WORD8("writeWord8X16Array#", false, VectorMemoryFamily.WORD8),
    WRITE_WORD8_SCALAR("writeWord8ArrayAsWord8X16#", true, VectorMemoryFamily.WORD8),
    INDEX_INT16("indexInt16X8Array#", false, VectorMemoryFamily.INT16),
    INDEX_INT16_SCALAR("indexInt16ArrayAsInt16X8#", true, VectorMemoryFamily.INT16),
    READ_INT16("readInt16X8Array#", false, VectorMemoryFamily.INT16),
    READ_INT16_SCALAR("readInt16ArrayAsInt16X8#", true, VectorMemoryFamily.INT16),
    WRITE_INT16("writeInt16X8Array#", false, VectorMemoryFamily.INT16),
    WRITE_INT16_SCALAR("writeInt16ArrayAsInt16X8#", true, VectorMemoryFamily.INT16),
    INDEX_WORD16("indexWord16X8Array#", false, VectorMemoryFamily.WORD16),
    INDEX_WORD16_SCALAR("indexWord16ArrayAsWord16X8#", true, VectorMemoryFamily.WORD16),
    READ_WORD16("readWord16X8Array#", false, VectorMemoryFamily.WORD16),
    READ_WORD16_SCALAR("readWord16ArrayAsWord16X8#", true, VectorMemoryFamily.WORD16),
    WRITE_WORD16("writeWord16X8Array#", false, VectorMemoryFamily.WORD16),
    WRITE_WORD16_SCALAR("writeWord16ArrayAsWord16X8#", true, VectorMemoryFamily.WORD16),
    INDEX_INT64("indexInt64X2Array#", false, VectorMemoryFamily.INT64),
    INDEX_INT64_SCALAR("indexInt64ArrayAsInt64X2#", true, VectorMemoryFamily.INT64),
    READ_INT64("readInt64X2Array#", false, VectorMemoryFamily.INT64),
    READ_INT64_SCALAR("readInt64ArrayAsInt64X2#", true, VectorMemoryFamily.INT64),
    WRITE_INT64("writeInt64X2Array#", false, VectorMemoryFamily.INT64),
    WRITE_INT64_SCALAR("writeInt64ArrayAsInt64X2#", true, VectorMemoryFamily.INT64),
    INDEX_WORD64("indexWord64X2Array#", false, VectorMemoryFamily.WORD64),
    INDEX_WORD64_SCALAR("indexWord64ArrayAsWord64X2#", true, VectorMemoryFamily.WORD64),
    READ_WORD64("readWord64X2Array#", false, VectorMemoryFamily.WORD64),
    READ_WORD64_SCALAR("readWord64ArrayAsWord64X2#", true, VectorMemoryFamily.WORD64),
    WRITE_WORD64("writeWord64X2Array#", false, VectorMemoryFamily.WORD64),
    WRITE_WORD64_SCALAR("writeWord64ArrayAsWord64X2#", true, VectorMemoryFamily.WORD64),
    INDEX("indexInt32X4Array#", false),
    INDEX_SCALAR("indexInt32ArrayAsInt32X4#", true),
    READ("readInt32X4Array#", false),
    READ_SCALAR("readInt32ArrayAsInt32X4#", true),
    WRITE("writeInt32X4Array#", false),
    WRITE_SCALAR("writeInt32ArrayAsInt32X4#", true),
    INDEX_WORD("indexWord32X4Array#", false, VectorMemoryFamily.WORD32),
    INDEX_WORD_SCALAR("indexWord32ArrayAsWord32X4#", true, VectorMemoryFamily.WORD32),
    READ_WORD("readWord32X4Array#", false, VectorMemoryFamily.WORD32),
    READ_WORD_SCALAR("readWord32ArrayAsWord32X4#", true, VectorMemoryFamily.WORD32),
    WRITE_WORD("writeWord32X4Array#", false, VectorMemoryFamily.WORD32),
    WRITE_WORD_SCALAR("writeWord32ArrayAsWord32X4#", true, VectorMemoryFamily.WORD32),
    INDEX_FLOAT("indexFloatX4Array#", false, VectorMemoryFamily.FLOAT32),
    INDEX_FLOAT_SCALAR("indexFloatArrayAsFloatX4#", true, VectorMemoryFamily.FLOAT32),
    READ_FLOAT("readFloatX4Array#", false, VectorMemoryFamily.FLOAT32),
    READ_FLOAT_SCALAR("readFloatArrayAsFloatX4#", true, VectorMemoryFamily.FLOAT32),
    WRITE_FLOAT("writeFloatX4Array#", false, VectorMemoryFamily.FLOAT32),
    WRITE_FLOAT_SCALAR("writeFloatArrayAsFloatX4#", true, VectorMemoryFamily.FLOAT32),
    INDEX_DOUBLE("indexDoubleX2Array#", false, VectorMemoryFamily.DOUBLE64),
    INDEX_DOUBLE_SCALAR("indexDoubleArrayAsDoubleX2#", true, VectorMemoryFamily.DOUBLE64),
    READ_DOUBLE("readDoubleX2Array#", false, VectorMemoryFamily.DOUBLE64),
    READ_DOUBLE_SCALAR("readDoubleArrayAsDoubleX2#", true, VectorMemoryFamily.DOUBLE64),
    WRITE_DOUBLE("writeDoubleX2Array#", false, VectorMemoryFamily.DOUBLE64),
    WRITE_DOUBLE_SCALAR("writeDoubleArrayAsDoubleX2#", true, VectorMemoryFamily.DOUBLE64);

    val isRead: Boolean = primitive.startsWith("read")
    val isWrite: Boolean = primitive.startsWith("write")
    val isIndex: Boolean = primitive.startsWith("index")
    val vectorProof: CoreRepresentation get() = wideProof ?: family.vectorProof
    internal fun validateArguments(actual: List<CoreRepresentation>, flags: List<*>) {
        val expected = listOf(if (isAddress) CoreVectorMemory.addressProof else CoreVectorMemory.arrayProof, CoreVectorMemory.indexProof) + when {
            isRead -> listOf(CoreVectorMemory.stateProof)
            isWrite -> listOf(vectorProof, CoreVectorMemory.stateProof)
            else -> emptyList()
        }
        if (actual.size != expected.size || flags != List(expected.size) { false } ||
            actual.indices.any { if (isAddress && it == 1) !actual[it].present || actual[it].isAggregate || actual[it].kind != CoreKind.LONG
                else !CoreVectorMemory.exact(expected[it], actual[it]) })
            throw RuntimeFault("Vector memory primitive argument representation mismatch: $primitive")
    }
    fun validate(actual: List<CoreRepresentation>, flags: List<*>, result: CoreRepresentation) {
        if (isRead) throw UnsupportedCore("Vector memory read requires an immediate exact case")
        validateArguments(actual, flags)
        if (!CoreVectorMemory.exact(if (isWrite) CoreVectorMemory.stateProof else vectorProof, result))
            throw RuntimeFault("Vector memory primitive result representation mismatch: $primitive")
    }
    companion object {
        fun named(name: String): VectorMemoryOp? = entries.firstOrNull { it.primitive == name }
    }
}

internal data class VectorReadCase(val operation: VectorMemoryOp, val arguments: List<List<Any?>>,
    val stateBinder: String, val vectorBinder: String, val body: List<Any?>)

internal object CoreVectorMemory {
    val stateProof = CoreRepresentation(CoreKind.VOID, true, true, emptyList())
    internal val arrayProof = CoreRepresentation(CoreKind.OBJECT, true, true, listOf("BoxedRep (Just Unlifted)"))
    internal val addressProof = CoreRepresentation(CoreKind.ADDRESS, true, true, listOf("AddrRep"))
    internal val indexProof = CoreRepresentation(CoreKind.LONG, true, true, listOf("IntRep"))
    internal fun exact(expected: CoreRepresentation, actual: CoreRepresentation): Boolean =
        actual.present && !actual.isTuple && actual.kind == expected.kind &&
            actual.primReps == expected.primReps && actual.vector == expected.vector

    private fun requireProof(condition: Boolean, detail: String) {
        if (!condition) throw RuntimeFault("Invalid local vector read case: $detail")
    }
    private fun exactInteger(value: Any?, expected: Long): Boolean =
        (value is Int || value is Long) && (value as Number).toLong() == expected
    private fun vectorAnnotation(raw: Any?, proof: CoreRepresentation): Boolean {
        val value = raw as? Map<*, *> ?: return false
        return value.keys == setOf("lanes", "element") &&
            exactInteger(value["lanes"], proof.vector!!.lanes.toLong()) && value["element"] == proof.vector.element
    }
    /** The pinned exporter annotates this aggregate with its sole physical VecRep.
     * Validate the original map without admitting it to generic CoreVector.parse. */
    private fun readResult(raw: Any?, binder: Boolean, proof: CoreRepresentation) {
        val value = raw as? Map<*, *> ?: throw RuntimeFault("Missing local vector read result proof")
        val components = value["components"] as? List<*>
        requireProof(value["kind"] == "unknown" && value["aggregate"] == "unboxed-tuple" &&
            value["primReps"] == proof.primReps && vectorAnnotation(value["vector"], proof) &&
            value["evaluated"] is Boolean && (!binder || value["evaluated"] == true) && components?.size == 2,
            "expected exact State/vector proof for ${proof.vector}")
        // Check the raw vector shape before generic parsing normalizes its lane count.
        requireProof(vectorAnnotation((components!![1] as? Map<*, *>)?.get("vector"), proof) &&
            exact(stateProof, CoreRepresentations.parse(components[0])) &&
            exact(proof, CoreRepresentations.parse(components[1])), "result components")
    }
    private fun termUses(value: Any?, id: String): Boolean = when (value) {
        is List<*> -> value.firstOrNull() == "var" && value.getOrNull(1) == id || value.any { termUses(it, id) }
        is Map<*, *> -> value.values.any { termUses(it, id) }
        else -> false
    }
    fun readCase(expr: List<Any?>, constructors: Map<String, Map<String, Any?>>): VectorReadCase? {
        if (expr.firstOrNull() != "case") return null
        val app = expr.getOrNull(1) as? List<*> ?: return null
        if (app.firstOrNull() != "app") return null
        val function = app.getOrNull(1) as? List<*> ?: return null
        if (function.firstOrNull() != "prim") return null
        val operation = (function.getOrNull(1) as? String)?.let(VectorMemoryOp::named) ?: return null
        if (!operation.isRead) return null
        val arguments = (app.getOrNull(2) as? List<*>)?.map {
            it as? List<Any?> ?: throw RuntimeFault("Invalid local vector read argument")
        } ?: throw RuntimeFault("Missing local vector read arguments")
        val flags = app.getOrNull(3) as? List<*> ?: throw RuntimeFault("Missing local vector read flags")
        operation.validateArguments(arguments.map(CoreRepresentations::expression), flags)
        readResult((app.getOrNull(6) as? Map<*, *>)?.get("rep"), false, operation.vectorProof)
        val whole = expr.getOrNull(2) as? String ?: throw RuntimeFault("Missing local vector read case binder")
        val metadata = expr.getOrNull(4) as? Map<*, *> ?: throw RuntimeFault("Missing local vector read metadata")
        val binder = metadata["binder"] as? Map<*, *> ?: throw RuntimeFault("Missing local vector read binder metadata")
        requireProof(binder["id"] == whole && binder["lifted"] == false && binder["coercion"] == false && "joinValueArity" !in binder,
            "whole-tuple binder identity/levity")
        readResult(binder["rep"], true, operation.vectorProof)
        val alternatives = expr.getOrNull(3) as? List<*> ?: throw RuntimeFault("Missing local vector read alternatives")
        requireProof(alternatives.size == 1, "requires one tuple alternative")
        val alternative = alternatives.single() as? List<*> ?: throw RuntimeFault("Invalid local vector read alternative")
        val constructor = (alternative.getOrNull(1) as? String)?.let(constructors::get)
        requireProof(alternative.firstOrNull() == "data" && constructor?.get("kind") == "unboxed-tuple" &&
            exactInteger(constructor?.get("arity"), 2), "requires a registered tuple2 constructor")
        val ids = alternative.getOrNull(2) as? List<*> ?: throw RuntimeFault("Missing local vector read pattern ids")
        requireProof(ids.size == 2 && ids.all { it is String } && ids.distinct().size == 2 && whole !in ids,
            "pattern binder identities")
        val records = (alternative.getOrNull(4) as? Map<*, *>)?.get("binders") as? List<*>
        requireProof(records?.size == 2, "missing ordered pattern metadata")
        listOf(stateProof, operation.vectorProof).forEachIndexed { i, expected ->
            val record = records!![i] as? Map<*, *> ?: throw RuntimeFault("Invalid local vector read pattern metadata")
            requireProof(record["id"] == ids[i] && record["lifted"] == false && record["coercion"] == false && "joinValueArity" !in record &&
                (!expected.isVector || vectorAnnotation((record["rep"] as? Map<*, *>)?.get("vector"), expected)) &&
                exact(expected, CoreRepresentations.parse(record["rep"])) &&
                (record["rep"] as? Map<*, *>)?.get("evaluated") == true, "pattern binder representation")
        }
        val body = alternative.getOrNull(3) as? List<Any?> ?: throw RuntimeFault("Missing local vector read continuation")
        requireProof(body.isNotEmpty(), "empty continuation")
        requireProof(!termUses(body, whole), "whole tuple binder escapes")
        return VectorReadCase(operation, arguments, ids[0] as String, ids[1] as String, body)
    }
}

/** The read form is constructed only by the validated immediate-case lowering.
 * It publishes a dense local vector, never a State/vector tuple value. */
internal class VectorByteArrayExpression(private val operation: VectorMemoryOp,
    @field:Children private var arguments: Array<Expr>) : Expr() {
    init { representation = if (operation.isWrite) CoreVectorMemory.stateProof else operation.vectorProof }
    override fun execute(frame: VirtualFrame): Any {
        val array = arguments[0].execute(frame)
        val index = arguments[1].executeRequiredLong(frame)
        if (operation.isWrite) {
            // A subject enum when creates a mutable switch-map array load that
            // prevents partial evaluation from selecting this node's family.
            when {
                operation.family === VectorMemoryFamily.INT8 || operation.family === VectorMemoryFamily.WORD8 -> {
                    val value = CoreVectors.requireByte(arguments[2].execute(frame), ByteVector.SPECIES_128.withShape(VectorShape.forBitSize(operation.vectorBytes * 8)))
                    ManagedByteArray.requireState(arguments[3].execute(frame))
                    ManagedByteArray.writeByteVectorGuest(array, index, value, operation.scalarOffset, operation.vectorBytes)
                }
                operation.family === VectorMemoryFamily.INT16 || operation.family === VectorMemoryFamily.WORD16 -> {
                    val value = CoreVectors.requireShort(arguments[2].execute(frame), ShortVector.SPECIES_128.withShape(VectorShape.forBitSize(operation.vectorBytes * 8)))
                    ManagedByteArray.requireState(arguments[3].execute(frame))
                    ManagedByteArray.writeShortVectorGuest(array, index, value, operation.scalarOffset, operation.vectorBytes)
                }
                operation.family === VectorMemoryFamily.INT64 || operation.family === VectorMemoryFamily.WORD64 -> {
                    val value = CoreVectors.requireLong(arguments[2].execute(frame), LongVector.SPECIES_128.withShape(VectorShape.forBitSize(operation.vectorBytes * 8)))
                    ManagedByteArray.requireState(arguments[3].execute(frame))
                    ManagedByteArray.writeLongVectorGuest(array, index, value, operation.scalarOffset, operation.vectorBytes)
                }
                operation.family === VectorMemoryFamily.INT32 -> {
                    val value = CoreVectors.requireInt(arguments[2].execute(frame), IntVector.SPECIES_128.withShape(VectorShape.forBitSize(operation.vectorBytes * 8)))
                    ManagedByteArray.requireState(arguments[3].execute(frame))
                    ManagedByteArray.writeInt32VectorGuest(array, index, value, operation.scalarOffset, operation.vectorBytes)
                }
                operation.family === VectorMemoryFamily.WORD32 -> {
                    val value = CoreVectors.requireInt(arguments[2].execute(frame), IntVector.SPECIES_128.withShape(VectorShape.forBitSize(operation.vectorBytes * 8)))
                    ManagedByteArray.requireState(arguments[3].execute(frame))
                    ManagedByteArray.writeWord32VectorGuest(array, index, value, operation.scalarOffset, operation.vectorBytes)
                }
                operation.family === VectorMemoryFamily.FLOAT32 -> {
                    val value = CoreVectors.requireFloat(arguments[2].execute(frame), FloatVector.SPECIES_128.withShape(VectorShape.forBitSize(operation.vectorBytes * 8)))
                    ManagedByteArray.requireState(arguments[3].execute(frame))
                    ManagedByteArray.writeFloatVectorGuest(array, index, value, operation.scalarOffset, operation.vectorBytes)
                }
                operation.family === VectorMemoryFamily.DOUBLE64 -> {
                    val value = CoreVectors.requireDouble(arguments[2].execute(frame), DoubleVector.SPECIES_128.withShape(VectorShape.forBitSize(operation.vectorBytes * 8)))
                    ManagedByteArray.requireState(arguments[3].execute(frame))
                    ManagedByteArray.writeDoubleVectorGuest(array, index, value, operation.scalarOffset, operation.vectorBytes)
                }
                else -> fault("Unsupported local vector memory family")
            }
            return Unit
        }
        if (operation.isRead) ManagedByteArray.requireState(arguments[2].execute(frame))
        // Return each public carrier directly; a value-producing when joins at the inaccessible AbstractVector.
        when {
            operation.family === VectorMemoryFamily.INT8 || operation.family === VectorMemoryFamily.WORD8 ->
                return ManagedByteArray.readByteVectorGuest(array, index, operation.scalarOffset, operation.vectorBytes)
            operation.family === VectorMemoryFamily.INT16 || operation.family === VectorMemoryFamily.WORD16 ->
                return ManagedByteArray.readShortVectorGuest(array, index, operation.scalarOffset, operation.vectorBytes)
            operation.family === VectorMemoryFamily.INT64 || operation.family === VectorMemoryFamily.WORD64 ->
                return ManagedByteArray.readLongVectorGuest(array, index, operation.scalarOffset, operation.vectorBytes)
            operation.family === VectorMemoryFamily.INT32 -> return ManagedByteArray.readInt32VectorGuest(array, index, operation.scalarOffset, operation.vectorBytes)
            operation.family === VectorMemoryFamily.WORD32 -> return ManagedByteArray.readWord32VectorGuest(array, index, operation.scalarOffset, operation.vectorBytes)
            operation.family === VectorMemoryFamily.FLOAT32 -> return ManagedByteArray.readFloatVectorGuest(array, index, operation.scalarOffset, operation.vectorBytes)
            operation.family === VectorMemoryFamily.DOUBLE64 -> return ManagedByteArray.readDoubleVectorGuest(array, index, operation.scalarOffset, operation.vectorBytes)
            else -> fault("Unsupported local vector memory family")
        }
    }
}
