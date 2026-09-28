// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import thc.Language;
import java.util.List;

/** Context-owned RTS data labels and the capacity snapshotted before guest pinning. */
public final class CoreDataLabels {
    private CoreDataLabels() {}
    public static ManagedAddress fromCore(String symbol, CoreRepresentation proof) { return fromCore(symbol, proof, null); }
    public static ManagedAddress fromCore(String symbol, CoreRepresentation proof, TargetLayout layout) {
        if (!symbol.equals("enabled_capabilities") && !symbol.equals("ghc_unique_counter64") &&
            !symbol.equals("ghc_unique_inc") && !symbol.equals("RtsFlags"))
            throw new RuntimeFault("Unsupported C data label " + symbol);
        if (proof == null || !proof.getPresent() || !proof.getEvaluated() || proof.getKind() != CoreKind.ADDRESS ||
            proof.isAggregate() || proof.isVector() || !List.of("AddrRep").equals(proof.getPrimReps()))
            throw new RuntimeFault("RTS data label requires exact evaluated AddrRep proof");
        var state = Language.currentState(null);
        return switch (symbol) {
            case "enabled_capabilities" -> ManagedAddress.Companion.enabledCapabilities$org_intelligence_thc(state.getThreads$org_intelligence_thc());
            case "RtsFlags" -> state.getCompilerRts$org_intelligence_thc().flagsAddress(layout);
            default -> state.getCompilerRts$org_intelligence_thc().address(symbol);
        };
    }
}
