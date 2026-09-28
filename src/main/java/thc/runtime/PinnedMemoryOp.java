// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import java.util.List;
public enum PinnedMemoryOp {
    NEW("newPinnedByteArray#", List.of(List.of("IntRep"), List.of()), true),
    NEW_ALIGNED("newAlignedPinnedByteArray#", List.of(List.of("IntRep"), List.of("IntRep"), List.of()), true),
    CONTENTS("byteArrayContents#", List.of(List.of("BoxedRep (Just Unlifted)")), false),
    MUTABLE_CONTENTS("mutableByteArrayContents#", List.of(List.of("BoxedRep (Just Unlifted)")), false),
    READ("readWord8OffAddr#", List.of(List.of("AddrRep"), List.of("IntRep"), List.of()), true),
    READ_INT8("readInt8OffAddr#", List.of(List.of("AddrRep"), List.of("IntRep"), List.of()), true),
    READ_CHAR("readCharOffAddr#", List.of(List.of("AddrRep"), List.of("IntRep"), List.of()), true),
    READ_WORD16("readWord16OffAddr#", List.of(List.of("AddrRep"), List.of("IntRep"), List.of()), true, ManagedAddressRead.WORD16),
    READ_INT16("readInt16OffAddr#", List.of(List.of("AddrRep"), List.of("IntRep"), List.of()), true, ManagedAddressRead.INT16),
    READ_WORD32("readWord32OffAddr#", List.of(List.of("AddrRep"), List.of("IntRep"), List.of()), true, ManagedAddressRead.WORD32),
    READ_WIDE_CHAR("readWideCharOffAddr#", List.of(List.of("AddrRep"), List.of("IntRep"), List.of()), true, ManagedAddressRead.WIDE_CHAR),
    READ_WORD("readWordOffAddr#", List.of(List.of("AddrRep"), List.of("IntRep"), List.of()), true, ManagedAddressRead.WORD),
    READ_INT32("readInt32OffAddr#", List.of(List.of("AddrRep"), List.of("IntRep"), List.of()), true, ManagedAddressRead.INT32),
    READ_INT("readIntOffAddr#", List.of(List.of("AddrRep"), List.of("IntRep"), List.of()), true, ManagedAddressRead.INT),
    READ_WORD64("readWord64OffAddr#", List.of(List.of("AddrRep"), List.of("IntRep"), List.of()), true, ManagedAddressRead.WORD64),
    READ_INT64("readInt64OffAddr#", List.of(List.of("AddrRep"), List.of("IntRep"), List.of()), true, ManagedAddressRead.INT64),
    READ_ADDR("readAddrOffAddr#", List.of(List.of("AddrRep"), List.of("IntRep"), List.of()), true),
    INDEX_ADDR_OFF("indexAddrOffAddr#", List.of(List.of("AddrRep"), List.of("IntRep")), false),
    INDEX_WORD8_AS_CHAR("indexWord8OffAddrAsChar#", List.of(List.of("AddrRep"), List.of("IntRep")), false, ManagedAddressRead.CHAR),
    INDEX_WORD8_AS_INT16("indexWord8OffAddrAsInt16#", List.of(List.of("AddrRep"), List.of("IntRep")), false, ManagedAddressRead.INT16),
    INDEX_WORD8_AS_WORD16("indexWord8OffAddrAsWord16#", List.of(List.of("AddrRep"), List.of("IntRep")), false, ManagedAddressRead.WORD16),
    INDEX_INT32("indexInt32OffAddr#", List.of(List.of("AddrRep"), List.of("IntRep")), false, ManagedAddressRead.INT32),
    INDEX_WORD32("indexWord32OffAddr#", List.of(List.of("AddrRep"), List.of("IntRep")), false, ManagedAddressRead.WORD32),
    INDEX_WIDE_CHAR("indexWideCharOffAddr#", List.of(List.of("AddrRep"), List.of("IntRep")), false, ManagedAddressRead.WIDE_CHAR),
    INDEX_INT("indexIntOffAddr#", List.of(List.of("AddrRep"), List.of("IntRep")), false, ManagedAddressRead.INT),
    INDEX_WORD("indexWordOffAddr#", List.of(List.of("AddrRep"), List.of("IntRep")), false, ManagedAddressRead.WORD),
    INDEX_INT64("indexInt64OffAddr#", List.of(List.of("AddrRep"), List.of("IntRep")), false, ManagedAddressRead.INT64),
    INDEX_WORD64("indexWord64OffAddr#", List.of(List.of("AddrRep"), List.of("IntRep")), false, ManagedAddressRead.WORD64),
    INDEX_ADDR_ARRAY("indexAddrArray#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep")), false),
    READ_ADDR_ARRAY("readAddrArray#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"), List.of()), true),
    WRITE("writeWord8OffAddr#", List.of(List.of("AddrRep"), List.of("IntRep"), List.of("Word8Rep"), List.of()), false),
    WRITE_INT8("writeInt8OffAddr#", List.of(List.of("AddrRep"), List.of("IntRep"), List.of("Int8Rep"), List.of()), false),
    WRITE_INT16("writeInt16OffAddr#", List.of(List.of("AddrRep"), List.of("IntRep"), List.of("Int16Rep"), List.of()), false),
    WRITE_WORD16("writeWord16OffAddr#", List.of(List.of("AddrRep"), List.of("IntRep"), List.of("Word16Rep"), List.of()), false),
    WRITE_INT32("writeInt32OffAddr#", List.of(List.of("AddrRep"), List.of("IntRep"), List.of("Int32Rep"), List.of()), false),
    WRITE_WORD32("writeWord32OffAddr#", List.of(List.of("AddrRep"), List.of("IntRep"), List.of("Word32Rep"), List.of()), false),
    WRITE_WIDE_CHAR("writeWideCharOffAddr#", List.of(List.of("AddrRep"), List.of("IntRep"), List.of("WordRep"), List.of()), false),
    WRITE_INT("writeIntOffAddr#", List.of(List.of("AddrRep"), List.of("IntRep"), List.of("IntRep"), List.of()), false),
    WRITE_WORD("writeWordOffAddr#", List.of(List.of("AddrRep"), List.of("IntRep"), List.of("WordRep"), List.of()), false),
    WRITE_INT64("writeInt64OffAddr#", List.of(List.of("AddrRep"), List.of("IntRep"), List.of("Int64Rep"), List.of()), false),
    WRITE_WORD64("writeWord64OffAddr#", List.of(List.of("AddrRep"), List.of("IntRep"), List.of("Word64Rep"), List.of()), false),
    WRITE_CHAR("writeCharOffAddr#", List.of(List.of("AddrRep"), List.of("IntRep"), List.of("WordRep"), List.of()), false),
    WRITE_ADDR("writeAddrOffAddr#", List.of(List.of("AddrRep"), List.of("IntRep"), List.of("AddrRep"), List.of()), false),
    WRITE_ADDR_ARRAY("writeAddrArray#", List.of(List.of("BoxedRep (Just Unlifted)"), List.of("IntRep"),
        List.of("AddrRep"), List.of()), false),
    COPY_ADDR_NON_OVERLAPPING("copyAddrToAddrNonOverlapping#",
        List.of(List.of("AddrRep"), List.of("AddrRep"), List.of("IntRep"), List.of()), false),
    COPY_ADDR("copyAddrToAddr#",
        List.of(List.of("AddrRep"), List.of("AddrRep"), List.of("IntRep"), List.of()), false),
    SET_ADDR("setAddrRange#",
        List.of(List.of("AddrRep"), List.of("IntRep"), List.of("IntRep"), List.of()), false);

    private final String primitive;
    private final List<List<String>> arguments;
    private final boolean tuple;
    private final ManagedAddressRead addressRead;
    PinnedMemoryOp(String primitive, List<List<String>> arguments, boolean tuple) { this(primitive, arguments, tuple, null); }
    PinnedMemoryOp(String primitive, List<List<String>> arguments, boolean tuple, ManagedAddressRead addressRead) {
        this.primitive = primitive; this.arguments = arguments; this.tuple = tuple; this.addressRead = addressRead;
    }
    public String getPrimitive() { return primitive; }
    public List<List<String>> getArguments() { return arguments; }
    public boolean getTuple() { return tuple; }
    public ManagedAddressRead getAddressRead() { return addressRead; }
    private static boolean exact(CoreRepresentation proof, List<String> reps) {
        String rep = reps.size() == 1 ? reps.getFirst() : null;
        CoreKind kind = switch (rep) {
            case null -> CoreKind.VOID;
            case "BoxedRep (Just Unlifted)" -> CoreKind.OBJECT;
            case "AddrRep" -> CoreKind.ADDRESS;
            default -> CoreKind.LONG;
        };
        return !proof.isAggregate() && !proof.isVector() && proof.getKind() == kind &&
            (kind == CoreKind.LONG || reps.equals(proof.getPrimReps()));
    }
    public void validate(List<CoreRepresentation> actual, List<?> flags, CoreRepresentation result) {
        if (actual.size() != arguments.size() || !flags.equals(java.util.Collections.nCopies(arguments.size(), false)))
            throw new RuntimeFault("Pinned memory argument representation mismatch: " + primitive);
        for (int i = 0; i < actual.size(); i++) if (!exact(actual.get(i), arguments.get(i)))
            throw new RuntimeFault("Pinned memory argument representation mismatch: " + primitive);
        List<String> payload;
        if (addressRead != null) payload = List.of(addressRead.getPayload());
        else if (this == READ_ADDR || this == READ_ADDR_ARRAY) payload = List.of("AddrRep");
        else if (this == READ) payload = List.of("Word8Rep");
        else if (this == READ_INT8) payload = List.of("Int8Rep");
        else if (this == READ_CHAR) payload = List.of("WordRep");
        else payload = List.of("BoxedRep (Just Unlifted)");
        boolean valid;
        if (tuple) {
            var fields = result.getComponents();
            valid = result.isTuple() && result.getKind() == CoreKind.UNKNOWN && java.util.Objects.requireNonNull(fields).size() == 2 &&
                exact(fields.get(0), List.of()) && exact(fields.get(1), payload) &&
                (fields.get(1).getKind() == CoreKind.LONG || payload.equals(result.getPrimReps()));
        } else valid = exact(result, this == CONTENTS || this == MUTABLE_CONTENTS || this == INDEX_ADDR_OFF || this == INDEX_ADDR_ARRAY ?
            List.of("AddrRep") : addressRead != null ? List.of(addressRead.getPayload()) : List.of());
        if (!valid) throw new RuntimeFault("Pinned memory result representation mismatch: " + primitive);
    }
    public static PinnedMemoryOp named(String name) {
        var alias = switch (name) {
            case "indexStablePtrArray#" -> INDEX_ADDR_ARRAY;
            case "readStablePtrArray#" -> READ_ADDR_ARRAY;
            case "writeStablePtrArray#" -> WRITE_ADDR_ARRAY;
            case "indexStablePtrOffAddr#" -> INDEX_ADDR_OFF;
            case "readStablePtrOffAddr#" -> READ_ADDR;
            case "writeStablePtrOffAddr#" -> WRITE_ADDR;
            case "indexWord8OffAddrAsInt#" -> INDEX_INT;
            case "indexWord8OffAddrAsWord#" -> INDEX_WORD;
            case "indexWord8OffAddrAsInt32#" -> INDEX_INT32;
            case "indexWord8OffAddrAsWord32#" -> INDEX_WORD32;
            case "indexWord8OffAddrAsInt64#" -> INDEX_INT64;
            case "indexWord8OffAddrAsWord64#" -> INDEX_WORD64;
            case "indexWord8OffAddrAsWideChar#" -> INDEX_WIDE_CHAR;
            case "indexWord8OffAddrAsAddr#" -> INDEX_ADDR_OFF;
            case "indexWord8OffAddrAsStablePtr#" -> INDEX_ADDR_OFF;
            case "indexWord8ArrayAsAddr#", "indexWord8ArrayAsStablePtr#" -> INDEX_ADDR_ARRAY;
            case "readWord8OffAddrAsInt#" -> READ_INT;
            case "readWord8OffAddrAsWord#" -> READ_WORD;
            case "readWord8OffAddrAsInt16#" -> READ_INT16;
            case "readWord8OffAddrAsWord16#" -> READ_WORD16;
            case "readWord8OffAddrAsInt32#" -> READ_INT32;
            case "readWord8OffAddrAsWord32#" -> READ_WORD32;
            case "readWord8OffAddrAsInt64#" -> READ_INT64;
            case "readWord8OffAddrAsWord64#" -> READ_WORD64;
            case "readWord8OffAddrAsWideChar#" -> READ_WIDE_CHAR;
            case "readWord8OffAddrAsChar#" -> READ_CHAR;
            case "readWord8OffAddrAsAddr#" -> READ_ADDR;
            case "readWord8OffAddrAsStablePtr#" -> READ_ADDR;
            case "readWord8ArrayAsAddr#", "readWord8ArrayAsStablePtr#" -> READ_ADDR_ARRAY;
            case "writeWord8OffAddrAsInt#" -> WRITE_INT;
            case "writeWord8OffAddrAsWord#" -> WRITE_WORD;
            case "writeWord8OffAddrAsInt16#" -> WRITE_INT16;
            case "writeWord8OffAddrAsWord16#" -> WRITE_WORD16;
            case "writeWord8OffAddrAsInt32#" -> WRITE_INT32;
            case "writeWord8OffAddrAsWord32#" -> WRITE_WORD32;
            case "writeWord8OffAddrAsInt64#" -> WRITE_INT64;
            case "writeWord8OffAddrAsWord64#" -> WRITE_WORD64;
            case "writeWord8OffAddrAsWideChar#" -> WRITE_WIDE_CHAR;
            case "writeWord8OffAddrAsChar#" -> WRITE_CHAR;
            case "writeWord8OffAddrAsAddr#" -> WRITE_ADDR;
            case "writeWord8OffAddrAsStablePtr#" -> WRITE_ADDR;
            case "writeWord8ArrayAsAddr#", "writeWord8ArrayAsStablePtr#" -> WRITE_ADDR_ARRAY;
            default -> null;
        };
        if (alias != null) return alias;
        for (var operation : values()) if (operation.primitive.equals(name)) return operation;
        return null;
    }
}
