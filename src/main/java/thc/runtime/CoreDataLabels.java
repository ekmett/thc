// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Native data labels, with overrides only for context-owned THC RTS state.
 * The capabilities value is this context's CPU capacity snapshotted before guest pinning
 * (at least one), not the number of guest carriers or dynamic GHC -N resizing.
 * Event-manager reconfiguration based on shrinking capabilities is still outside
 * the supported runtime contract. */
public final class CoreDataLabels extends Expr {
    private final String symbol;
    private final TargetLayout layout;
    CoreDataLabels(String symbol, CoreRepresentation proof, TargetLayout layout) {
        requireProof(proof); this.symbol = symbol; this.layout = layout; setRepresentation(proof);
    }
    @Override public ManagedAddress execute(com.oracle.truffle.api.frame.VirtualFrame frame) {
        return fromCore(symbol, getRepresentation(), layout);
    }
    @Override public ManagedAddress executeAddress(com.oracle.truffle.api.frame.VirtualFrame frame) { return execute(frame); }
    private static void requireProof(CoreRepresentation proof) {
        if (proof == null || !proof.getPresent() || !proof.getEvaluated() || proof.getKind() != CoreKind.ADDRESS ||
            proof.isAggregate() || proof.isVector() || !List.of("AddrRep").equals(proof.getPrimReps()))
            throw fault("C data label requires exact evaluated AddrRep proof");
    }

    public static ManagedAddress fromCore(String symbol, CoreRepresentation proof) {
        return fromCore(symbol, proof, null);
    }

    public static ManagedAddress fromCore(String symbol, CoreRepresentation proof, TargetLayout layout) {
        requireProof(proof);
        var state = Language.currentState(null);
        return switch (symbol) {
            case "enabled_capabilities" -> ManagedAddress.enabledCapabilities(state.getThreads());
            case "RtsFlags" -> state.getCompilerRts().flagsAddress(layout);
            case "ghc_unique_counter64", "ghc_unique_inc" -> state.getCompilerRts().address(symbol);
            default -> state.getPackageCbits().dataAddress(null, symbol);
        };
    }
}
