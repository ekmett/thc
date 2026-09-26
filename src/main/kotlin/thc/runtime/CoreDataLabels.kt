// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import thc.Language

/** Genuine read-only capabilities and mutable compiler-unique RTS data labels.
 * The capabilities value is this context's CPU capacity snapshotted before guest pinning
 * (at least one), not the number of guest carriers or dynamic GHC -N resizing.
 * Event-manager reconfiguration based on shrinking capabilities is still outside
 * the supported runtime contract. */
internal object CoreDataLabels {
    fun fromCore(symbol: String, proof: CoreRepresentation?): ManagedAddress {
        if (symbol !in setOf("enabled_capabilities", "ghc_unique_counter64", "ghc_unique_inc"))
            fault("Unsupported C data label $symbol")
        if (proof?.present != true || !proof.evaluated || proof.kind != CoreKind.ADDRESS ||
            proof.isAggregate || proof.isVector || proof.primReps != listOf("AddrRep"))
            fault("RTS data label requires exact evaluated AddrRep proof")
        val state = Language.currentState(null)
        return if (symbol == "enabled_capabilities") ManagedAddress.enabledCapabilities(state.threads)
        else state.compilerRts.address(symbol)
    }
}
