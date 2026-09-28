// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

public enum VectorMemoryFamily {
    INT8, WORD8, INT16, WORD16, INT32, WORD32, INT64, WORD64, FLOAT32, DOUBLE64;

    public CoreRepresentation getVectorProof() {
        return switch (this) {
            case INT8 -> CoreVectors.proof8;
            case WORD8 -> CoreVectors.proofWord8;
            case INT16 -> CoreVectors.proof16;
            case WORD16 -> CoreVectors.proofWord16;
            case INT32 -> CoreVectors.proof32;
            case WORD32 -> CoreVectors.proofWord32;
            case INT64 -> CoreVectors.proof;
            case WORD64 -> GeneratedVectors.proofWord64X2;
            case FLOAT32 -> CoreVectors.proofFloat;
            case DOUBLE64 -> CoreVectors.proofDouble;
        };
    }
}
