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
    private final thc.ManagedCallbackSignature callback;
    private final int programSlot;
    private final Program program;
    private final Language language;
    CFinalizerLabels(String symbol, CoreRepresentation proof) {
        this(symbol, proof, null, -1, null, null);
    }
    CFinalizerLabels(String symbol, CoreRepresentation proof, thc.ManagedCallbackSignature callback,
                    int programSlot, Program program, Language language) {
        validate(symbol, proof);
        this.symbol = symbol; this.callback = callback; this.programSlot = programSlot; this.program = program; this.language = language;
        setRepresentation(proof);
    }
    static void validate(String symbol, CoreRepresentation proof) {
        requireProof(proof);
        requireSymbol(symbol);
    }
    private static void requireSymbol(String symbol) {
        if (symbol == null || symbol.isEmpty() || symbol.indexOf('\0') >= 0)
            throw fault("Original C function label requires a nonempty symbol without NUL");
    }
    private static void requireProof(CoreRepresentation proof) {
        if (proof == null || !proof.getPresent() || proof.getKind() != CoreKind.ADDRESS || proof.isAggregate()
            || proof.isVector() || !List.of("AddrRep").equals(proof.getPrimReps()))
            throw fault("Original C function label requires exact AddrRep proof");
    }
    // Resolving a native label validates metadata and may load its owning library.
    @TruffleBoundary public static ManagedAddress fromCore(String symbol, CoreRepresentation proof) {
        validate(symbol, proof);
        return Language.currentState(null).cbits().finalizerLabel(symbol);
    }
    @TruffleBoundary ManagedAddress resolve() { return Language.currentState(null).cbits().finalizerLabel(symbol); }
    @Override public ManagedAddress execute(VirtualFrame frame) {
        return callback == null ? resolve() : Language.currentState(this).getNativeCallbacks().helper(callback,
            programSlot < 0 ? program : Program.instance(frame, programSlot), language);
    }
    @Override public ManagedAddress executeAddress(VirtualFrame frame) { return execute(frame); }
}
