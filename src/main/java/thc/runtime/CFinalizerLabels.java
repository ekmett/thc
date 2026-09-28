// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;
import thc.Language;
import static thc.runtime.RuntimeFault.fault;

public final class CFinalizerLabels {
    private CFinalizerLabels() {}
    public static ManagedAddress fromCore(String symbol, CoreRepresentation proof) {
        if (proof == null || !proof.getPresent() || proof.getKind() != CoreKind.ADDRESS || proof.isAggregate()
            || proof.isVector() || !List.of("AddrRep").equals(proof.getPrimReps()))
            throw fault("Original C function label requires exact AddrRep proof");
        return Language.currentState(null).cbits().finalizerLabel(symbol);
    }
}
