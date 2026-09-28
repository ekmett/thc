// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Genuine read-only capabilities and mutable compiler-unique RTS data labels.
 * The capabilities value is this context's CPU capacity snapshotted before guest pinning
 * (at least one), not the number of guest carriers or dynamic GHC -N resizing.
 * Event-manager reconfiguration based on shrinking capabilities is still outside
 * the supported runtime contract. */
public final class CoreDataLabels {
    private CoreDataLabels() {}

    public static ManagedAddress fromCore(String symbol, CoreRepresentation proof) {
        return fromCore(symbol, proof, null);
    }

    public static ManagedAddress fromCore(String symbol, CoreRepresentation proof, TargetLayout layout) {
        if (!List.of("enabled_capabilities", "ghc_unique_counter64", "ghc_unique_inc", "RtsFlags").contains(symbol))
            throw fault("Unsupported C data label " + symbol);
        if (proof == null || !proof.getPresent() || !proof.getEvaluated() || proof.getKind() != CoreKind.ADDRESS ||
            proof.isAggregate() || proof.isVector() || !List.of("AddrRep").equals(proof.getPrimReps()))
            throw fault("RTS data label requires exact evaluated AddrRep proof");
        var state = Language.currentState(null);
        return switch (symbol) {
            case "enabled_capabilities" -> ManagedAddress.Companion.enabledCapabilities$org_intelligence_thc(state.getThreads$org_intelligence_thc());
            case "RtsFlags" -> state.getCompilerRts$org_intelligence_thc().flagsAddress(layout);
            default -> state.getCompilerRts$org_intelligence_thc().address(symbol);
        };
    }
}
