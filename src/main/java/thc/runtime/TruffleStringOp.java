// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;
import java.util.Map;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.ValueProfile;
import com.oracle.truffle.api.strings.TruffleString;
import com.oracle.truffle.api.strings.TruffleString.Encoding;
import static com.oracle.truffle.api.strings.TruffleString.ErrorHandling.RETURN_NEGATIVE;

/** Direct immutable TruffleString operations, not Java String adapters. */
public enum TruffleStringOp {
    ENCODING("encoding", List.of("IntRep"), "BoxedRep (Just Unlifted)"),
    FROM_BYTES("from_bytes", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "IntRep"), "BoxedRep (Just Unlifted)"),
    TO_BYTES("to_bytes", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)"),
    FROM_CODE_POINT("from_code_point", List.of("BoxedRep (Just Unlifted)", "IntRep"), "BoxedRep (Just Unlifted)"),
    FROM_INT64("from_int64", List.of("BoxedRep (Just Unlifted)", "Int64Rep"), "BoxedRep (Just Unlifted)"),
    BYTE_LENGTH("byte_length", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "IntRep"),
    CODE_POINT_LENGTH("code_point_length", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "IntRep"),
    IS_VALID("is_valid", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "IntRep"),
    READ_BYTE("read_byte", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep"), "IntRep"),
    CODE_POINT_AT("code_point_at", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep"), "IntRep"),
    CODE_POINT_AT_BYTE("code_point_at_byte", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep"), "IntRep"),
    CODE_POINT_BYTE_LENGTH("code_point_byte_length", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep"), "IntRep"),
    BYTE_TO_CODE_POINT("byte_to_code_point", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "IntRep"), "IntRep"),
    CODE_POINT_TO_BYTE("code_point_to_byte", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "IntRep"), "IntRep"),
    EQUAL("equal", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "IntRep"),
    COMPARE_BYTES("compare_bytes", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "IntRep"),
    HASH("hash", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "IntRep"),
    INDEX_OF_CODE_POINT("index_of_code_point", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "IntRep", "IntRep"), "IntRep"),
    BYTE_INDEX_OF_CODE_POINT("byte_index_of_code_point", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "IntRep", "IntRep"), "IntRep"),
    INDEX_OF_STRING("index_of_string", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "IntRep"), "IntRep"),
    BYTE_INDEX_OF_STRING("byte_index_of_string", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "IntRep"), "IntRep"),
    SUBSTRING("substring", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "IntRep"), "BoxedRep (Just Unlifted)"),
    SUBSTRING_BYTES("substring_bytes", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep", "IntRep"), "BoxedRep (Just Unlifted)"),
    CONCAT("concat", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)"),
    REPEAT("repeat", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)", "IntRep"), "BoxedRep (Just Unlifted)"),
    SWITCH_ENCODING("switch_encoding", List.of("BoxedRep (Just Unlifted)", "BoxedRep (Just Unlifted)"), "BoxedRep (Just Unlifted)"),
    PARSE_INT64("parse_int64", List.of("BoxedRep (Just Unlifted)", "IntRep"), "Int64Rep"),
    PARSE_DOUBLE("parse_double", List.of("BoxedRep (Just Unlifted)"), "DoubleRep");

    final String symbol;
    final List<String> arguments;
    final String result;

    TruffleStringOp(String symbol, List<String> arguments, String result) {
        this.symbol = "thc_string_v1_" + symbol;
        this.arguments = arguments;
        this.result = result;
    }

    boolean parsesNumber() { return this == PARSE_INT64 || this == PARSE_DOUBLE; }

    static TruffleStringOp validate(List<?> expression, boolean defined) {
        var metadata = CoreRepresentations.metadata(expression);
        if (metadata == null || !(metadata.get("foreignCall") instanceof Map<?, ?> call) ||
            !(call.get("target") instanceof Map<?, ?> target) ||
            !(target.get("symbol") instanceof String symbol) || !symbol.startsWith("thc_string_v1_")) return null;
        for (var operation : values()) if (operation.symbol.equals(symbol)) {
            List<String> types = operation == FROM_BYTES
                ? java.util.Arrays.asList(null, "ByteArray#", null, null) : null;
            CorePolyglot.validateAbi(expression, defined, operation.arguments, operation.result, true, types);
            return operation;
        }
        throw new UnsupportedCore("Unsupported TruffleString primitive: " + symbol);
    }

    /** One native node per site; no mutable node or profile is shared by roots. */
    static final class Site extends Node {
        private final TruffleStringOp operation;
        private final int programSlot;
        private final ValueProfile encodingProfile = ValueProfile.createIdentityProfile();
        @Child private Node node;
        @Child private ForeignExceptionAccess exceptions;

        Site(TruffleStringOp operation) { this(operation, -1); }
        Site(TruffleStringOp operation, int programSlot) {
            this.operation = operation;
            this.programSlot = programSlot;
            exceptions = operation.parsesNumber() ? new ForeignExceptionAccess() : null;
            node = switch (operation) {
                case FROM_BYTES -> TruffleString.FromByteArrayNode.create();
                case TO_BYTES -> TruffleString.CopyToByteArrayNode.create();
                case FROM_CODE_POINT -> TruffleString.FromCodePointNode.create();
                case FROM_INT64 -> TruffleString.FromLongNode.create();
                case CODE_POINT_LENGTH -> TruffleString.CodePointLengthNode.create();
                case IS_VALID -> TruffleString.IsValidNode.create();
                case READ_BYTE -> TruffleString.ReadByteNode.create();
                case CODE_POINT_AT -> TruffleString.CodePointAtIndexNode.create();
                case CODE_POINT_AT_BYTE -> TruffleString.CodePointAtByteIndexNode.create();
                case CODE_POINT_BYTE_LENGTH -> TruffleString.ByteLengthOfCodePointNode.create();
                case BYTE_TO_CODE_POINT -> TruffleString.ByteIndexToCodePointIndexNode.create();
                case CODE_POINT_TO_BYTE -> TruffleString.CodePointIndexToByteIndexNode.create();
                case EQUAL -> TruffleString.EqualNode.create();
                case COMPARE_BYTES -> TruffleString.CompareBytesNode.create();
                case HASH -> TruffleString.HashCodeNode.create();
                case INDEX_OF_CODE_POINT -> TruffleString.IndexOfCodePointNode.create();
                case BYTE_INDEX_OF_CODE_POINT -> TruffleString.ByteIndexOfCodePointNode.create();
                case INDEX_OF_STRING -> TruffleString.IndexOfStringNode.create();
                case BYTE_INDEX_OF_STRING -> TruffleString.ByteIndexOfStringNode.create();
                case SUBSTRING -> TruffleString.SubstringNode.create();
                case SUBSTRING_BYTES -> TruffleString.SubstringByteIndexNode.create();
                case CONCAT -> TruffleString.ConcatNode.create();
                case REPEAT -> TruffleString.RepeatNode.create();
                case SWITCH_ENCODING -> TruffleString.SwitchEncodingNode.create();
                case PARSE_INT64 -> TruffleString.ParseLongNode.create();
                case PARSE_DOUBLE -> TruffleString.ParseDoubleNode.create();
                default -> null;
            };
        }

        Object execute(Object[] a) { return execute(null, a); }
        Object execute(VirtualFrame frame, Object[] a) {
            if (operation == ENCODING) return encoding((Long) a[0]);
            try {
                if (operation == PARSE_INT64) return ((TruffleString.ParseLongNode) node).execute((TruffleString) a[0], index(a[1]));
                if (operation == PARSE_DOUBLE) return ((TruffleString.ParseDoubleNode) node).execute((TruffleString) a[0]);
            } catch (TruffleString.NumberFormatException failure) {
                // Resolve only invocation ownership here; no VirtualFrame is
                // passed into the cold translation boundary or retained.
                throw programSlot < 0 ? exceptions.raiseHost(failure) :
                    exceptions.raiseHost(failure, Program.instance(frame, programSlot).foreignExceptionBridge());
            }
            Encoding e = encodingProfile.profile((Encoding) a[0]);
            return switch (operation) {
                case FROM_BYTES -> fromBytes((TruffleString.FromByteArrayNode) node, a[1], index(a[2]), index(a[3]), e);
                case TO_BYTES -> ((TruffleString.CopyToByteArrayNode) node).execute(string(a[1]), e);
                case FROM_CODE_POINT -> requireCodePoint(((TruffleString.FromCodePointNode) node).execute(index(a[1]), e, false));
                case FROM_INT64 -> ((TruffleString.FromLongNode) node).execute((Long) a[1], e, false);
                case BYTE_LENGTH -> (long) string(a[1]).byteLength(e);
                case CODE_POINT_LENGTH -> (long) ((TruffleString.CodePointLengthNode) node).execute(string(a[1]), e);
                case IS_VALID -> ((TruffleString.IsValidNode) node).execute(string(a[1]), e) ? 1L : 0L;
                case READ_BYTE -> (long) ((TruffleString.ReadByteNode) node).execute(string(a[1]), index(a[2]), e);
                case CODE_POINT_AT -> (long) ((TruffleString.CodePointAtIndexNode) node).execute(string(a[1]), index(a[2]), e, RETURN_NEGATIVE);
                case CODE_POINT_AT_BYTE -> (long) ((TruffleString.CodePointAtByteIndexNode) node).execute(string(a[1]), index(a[2]), e, RETURN_NEGATIVE);
                case CODE_POINT_BYTE_LENGTH -> (long) ((TruffleString.ByteLengthOfCodePointNode) node).execute(string(a[1]), index(a[2]), e, RETURN_NEGATIVE);
                case BYTE_TO_CODE_POINT -> (long) ((TruffleString.ByteIndexToCodePointIndexNode) node).execute(string(a[1]), index(a[2]), index(a[3]), e);
                case CODE_POINT_TO_BYTE -> (long) ((TruffleString.CodePointIndexToByteIndexNode) node).execute(string(a[1]), index(a[2]), index(a[3]), e);
                case EQUAL -> ((TruffleString.EqualNode) node).execute(string(a[1]), string(a[2]), e) ? 1L : 0L;
                case COMPARE_BYTES -> (long) ((TruffleString.CompareBytesNode) node).execute(string(a[1]), string(a[2]), e);
                case HASH -> (long) ((TruffleString.HashCodeNode) node).execute(string(a[1]), e);
                case INDEX_OF_CODE_POINT -> (long) ((TruffleString.IndexOfCodePointNode) node).execute(string(a[1]), index(a[2]), index(a[3]), index(a[4]), e);
                case BYTE_INDEX_OF_CODE_POINT -> (long) ((TruffleString.ByteIndexOfCodePointNode) node).execute(string(a[1]), index(a[2]), index(a[3]), index(a[4]), e);
                case INDEX_OF_STRING -> (long) ((TruffleString.IndexOfStringNode) node).execute(string(a[1]), string(a[2]), index(a[3]), index(a[4]), e);
                case BYTE_INDEX_OF_STRING -> (long) ((TruffleString.ByteIndexOfStringNode) node).execute(string(a[1]), string(a[2]), index(a[3]), index(a[4]), e);
                case SUBSTRING -> ((TruffleString.SubstringNode) node).execute(string(a[1]), index(a[2]), index(a[3]), e, false);
                case SUBSTRING_BYTES -> ((TruffleString.SubstringByteIndexNode) node).execute(string(a[1]), index(a[2]), index(a[3]), e, false);
                case CONCAT -> ((TruffleString.ConcatNode) node).execute(string(a[1]), string(a[2]), e, false);
                case REPEAT -> ((TruffleString.RepeatNode) node).execute(string(a[1]), index(a[2]), e);
                case SWITCH_ENCODING -> ((TruffleString.SwitchEncodingNode) node).execute(string(a[1]), e);
                default -> throw new AssertionError(operation);
            };
        }
    }

    private static int index(Object value) { return Math.toIntExact((Long) value); }
    private static TruffleString string(Object value) { return (TruffleString) value; }

    private static Encoding encoding(long code) {
        return switch (Math.toIntExact(code)) {
            case 0 -> Encoding.UTF_8; case 1 -> Encoding.UTF_16; case 2 -> Encoding.UTF_32;
            case 3 -> Encoding.ISO_8859_1; case 4 -> Encoding.US_ASCII; case 5 -> Encoding.BYTES;
            case 6 -> Encoding.UTF_16LE; case 7 -> Encoding.UTF_16BE;
            case 8 -> Encoding.UTF_32LE; case 9 -> Encoding.UTF_32BE;
            default -> throw RuntimeFault.fault("Unknown TruffleString encoding code");
        };
    }

    private static TruffleString fromBytes(TruffleString.FromByteArrayNode node, Object bytes,
            int offset, int length, Encoding encoding) {
        if (bytes instanceof ManagedAllocation allocation) {
            // The existing checked copy rejects embedded pointers and disposed storage.
            byte[] snapshot = allocation.copyBytesOut(offset, length);
            return node.execute(snapshot, 0, snapshot.length, encoding, false);
        }
        return node.execute(ManagedByteArray.require(bytes), offset, length, encoding, true);
    }

    private static TruffleString requireCodePoint(TruffleString result) {
        if (result == null) throw RuntimeFault.fault("Code point is not representable in this encoding");
        return result;
    }
}
