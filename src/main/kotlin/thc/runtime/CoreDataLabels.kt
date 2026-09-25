// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import thc.Language

/** Only the genuine read-only Word32 RTS label has a managed equivalent.
 * Its value is the live high-water mark of this context's logical guest Java
 * carriers (at least one), not GHC's physical -N setting or capability resizing.
 * Event-manager reconfiguration based on shrinking capabilities is still outside
 * the supported runtime contract. */
internal object CoreDataLabels {
    fun fromCore(symbol: String, proof: CoreRepresentation?): ManagedAddress {
        if (symbol != "enabled_capabilities") fault("Unsupported C data label $symbol")
        if (proof?.present != true || !proof.evaluated || proof.kind != CoreKind.ADDRESS ||
            proof.isAggregate || proof.isVector || proof.primReps != listOf("AddrRep"))
            fault("enabled_capabilities requires exact evaluated AddrRep proof")
        return ManagedAddress.enabledCapabilities(Language.currentState(null).threads)
    }
}
