// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.List;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import thc.Language;
import static thc.runtime.RuntimeFault.fault;

/** Immutable label metadata; reusable code resolves native authority only in its invoking context. */
public final class CFinalizerLabels extends Expr {
    private final String symbol;
    CFinalizerLabels(String symbol, CoreRepresentation proof) {
        requireProof(proof);
        this.symbol = symbol;
        setRepresentation(proof);
    }
    private static void requireProof(CoreRepresentation proof) {
        if (proof == null || !proof.getPresent() || proof.getKind() != CoreKind.ADDRESS || proof.isAggregate()
            || proof.isVector() || !List.of("AddrRep").equals(proof.getPrimReps()))
            throw fault("Original C function label requires exact AddrRep proof");
    }
    public static ManagedAddress fromCore(String symbol, CoreRepresentation proof) {
        requireProof(proof);
        return Language.currentState(null).cbits().finalizerLabel(symbol);
    }
    @TruffleBoundary ManagedAddress resolve() { return Language.currentState(null).cbits().finalizerLabel(symbol); }
    @Override public Object execute(VirtualFrame frame) { return resolve(); }
    @Override public ManagedAddress executeAddress(VirtualFrame frame) { return resolve(); }
}
