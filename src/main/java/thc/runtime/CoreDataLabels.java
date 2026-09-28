// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Genuine read-only capabilities, immutable bytestring tables, and mutable compiler-unique RTS data labels.
 * The capabilities value is this context's CPU capacity snapshotted before guest pinning
 * (at least one), not the number of guest carriers or dynamic GHC -N resizing.
 * Event-manager reconfiguration based on shrinking capabilities is still outside
 * the supported runtime contract. */
public final class CoreDataLabels {
    private static final ManagedAddress LOWER_HEX_TABLE = lowerHexTable();

    private CoreDataLabels() {}

    private static ManagedAddress lowerHexTable() {
        // bytestring/cbits/aligned-static-hs-data.c: 256 ASCII pairs and a trailing NUL.
        // Its Base16 reader loads each pair as a native-endian Word16.
        var bytes = new byte[513];
        String digits = "0123456789abcdef";
        for (int value = 0; value < 256; value++) {
            bytes[2 * value] = (byte) digits.charAt(value >>> 4);
            bytes[2 * value + 1] = (byte) digits.charAt(value & 15);
        }
        return ManagedAddress.fromStaticBytes(bytes);
    }

    public static ManagedAddress fromCore(String symbol, CoreRepresentation proof) {
        return fromCore(symbol, proof, null);
    }

    public static ManagedAddress fromCore(String symbol, CoreRepresentation proof, TargetLayout layout) {
        if (!List.of("enabled_capabilities", "ghc_unique_counter64", "ghc_unique_inc", "RtsFlags", "hs_bytestring_lower_hex_table").contains(symbol))
            throw fault("Unsupported C data label " + symbol);
        if (proof == null || !proof.getPresent() || !proof.getEvaluated() || proof.getKind() != CoreKind.ADDRESS ||
            proof.isAggregate() || proof.isVector() || !List.of("AddrRep").equals(proof.getPrimReps()))
            throw fault("RTS data label requires exact evaluated AddrRep proof");
        if (symbol.equals("hs_bytestring_lower_hex_table")) return LOWER_HEX_TABLE;
        var state = Language.currentState(null);
        return switch (symbol) {
            case "enabled_capabilities" -> ManagedAddress.enabledCapabilities(state.getThreads());
            case "RtsFlags" -> state.getCompilerRts().flagsAddress(layout);
            default -> state.getCompilerRts().address(symbol);
        };
    }
}
