// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

public enum VectorMemoryFamily {
    INT8, WORD8, INT16, WORD16, INT32, WORD32, INT64, WORD64, FLOAT32, DOUBLE64;

    public CoreRepresentation getVectorProof() {
        return switch (this) {
            case INT8 -> CoreVectors.INSTANCE.getProof8();
            case WORD8 -> CoreVectors.INSTANCE.getProofWord8();
            case INT16 -> CoreVectors.INSTANCE.getProof16();
            case WORD16 -> CoreVectors.INSTANCE.getProofWord16();
            case INT32 -> CoreVectors.INSTANCE.getProof32();
            case WORD32 -> CoreVectors.INSTANCE.getProofWord32();
            case INT64 -> CoreVectors.INSTANCE.getProof();
            case WORD64 -> GeneratedVectors.proofWord64X2;
            case FLOAT32 -> CoreVectors.INSTANCE.getProofFloat();
            case DOUBLE64 -> CoreVectors.INSTANCE.getProofDouble();
        };
    }
}
