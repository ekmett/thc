// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import static thc.runtime.RuntimeServiceStatus.fault;
import static thc.runtime.AstSelfCalls.requireReferenceCarrier;

/** Immutable frame destinations shared across lexical scopes and cloned nodes. */
public final class AstSelfLayout {
    private final CaptureLayout captureLayout;
    @CompilationFinal(dimensions = 1) private final int[] environmentSlots;
    @CompilationFinal(dimensions = 1) private final int[] argumentSlots;
    @CompilationFinal(dimensions = 1) private final CoreRepresentation[] argumentProofs;
    @CompilationFinal(dimensions = 1) private final boolean[] entryStrict;
    private final ArgumentLayout inputLayout;
    @CompilationFinal(dimensions = 2) private final int[][] environmentVectorSlots;
    @CompilationFinal(dimensions = 1) private final Class<?>[] argumentReferences;
    public AstSelfLayout(CaptureLayout captures, int[] environment, int[] arguments, CoreRepresentation[] proofs, boolean[] strict) {
        this(captures, environment, arguments, proofs, strict, null, new int[0][]);
    }
    public AstSelfLayout(CaptureLayout captures, int[] environment, int[] arguments, CoreRepresentation[] proofs, boolean[] strict, ArgumentLayout layout) {
        this(captures, environment, arguments, proofs, strict, layout, new int[0][]);
    }
    public AstSelfLayout(CaptureLayout captures, int[] environment, int[] arguments, CoreRepresentation[] proofs,
                         boolean[] strict, ArgumentLayout layout, int[][] vectors) {
        captureLayout = captures; environmentSlots = environment; argumentSlots = arguments; argumentProofs = proofs;
        entryStrict = strict; inputLayout = layout; environmentVectorSlots = vectors;
        argumentReferences = new Class<?>[proofs.length];
        for (int i = 0; i < proofs.length; i++) argumentReferences[i] = proofs[i].referenceCarrier();
    }
    public int getArity() { return argumentSlots.length; }
    public boolean[] getEntryStrict() { return entryStrict; }
    public ArgumentLayout getInputLayout() { return inputLayout; }

    @ExplodeLoop public RuntimeException transfer(VirtualFrame frame, Closure function, int[] temporaries) {
        // Temporaries cannot alias lexical slots; captures cannot overwrite a pending operand.
        if (captureLayout != null) {
            CapturedFrame environment = function.environment;
            if (environment == null) throw fault("Invalid captured frame");
            for (int i = 0; i < environmentSlots.length; i++) {
                int[] lanes = i < environmentVectorSlots.length ? environmentVectorSlots[i] : null;
                if (lanes == null) captureLayout.restore(environment, i, frame, environmentSlots[i]);
                else captureLayout.restoreVector(environment, i, frame, lanes, 0);
            }
        }
        for (int i = 0; i < argumentSlots.length; i++) {
            int destination = argumentSlots[i];
            if (i < argumentProofs.length && argumentProofs[i].getKind() == CoreKind.VOID)
                TupleResults.requireVoidCarrier(FrameAccess.INSTANCE.read(frame, temporaries[i]));
            if (destination < 0) continue;
            int source = temporaries[i];
            Class<?> reference = argumentReferences[i];
            if (reference != null) FrameAccess.INSTANCE.write(frame, destination, requireReferenceCarrier(FrameAccess.INSTANCE.read(frame, source), reference));
            else if (argumentProofs[i].isFloat()) {
                float value;
                if (frame.isFloat(source)) value = frame.getFloat(source);
                else if (frame.isObject(source) && frame.getObject(source) instanceof Float number) value = number;
                else throw fault("Expected primitive Float argument");
                FrameAccess.INSTANCE.writeFloat(frame, destination, value);
            } else if (argumentProofs[i].isDouble()) {
                double value;
                if (frame.isDouble(source)) value = frame.getDouble(source);
                else if (frame.isObject(source) && frame.getObject(source) instanceof Double number) value = number;
                else throw fault("Expected primitive Double argument");
                FrameAccess.INSTANCE.writeDouble(frame, destination, value);
            } else if (argumentProofs[i].isInt()) {
                int value;
                if (frame.isInt(source)) value = frame.getInt(source);
                else if (FrameAccess.INSTANCE.read(frame, source) instanceof Integer number) value = number;
                else throw fault("Expected primitive Int argument");
                FrameAccess.INSTANCE.writeInt(frame, destination, value);
            } else if (frame.isLong(source)) FrameAccess.INSTANCE.writeLong(frame, destination, frame.getLong(source));
            else if (argumentProofs[i].isLong()) {
                if (!(FrameAccess.INSTANCE.read(frame, source) instanceof Long number)) throw fault("Expected primitive Long argument");
                FrameAccess.INSTANCE.writeLong(frame, destination, number);
            } else FrameAccess.INSTANCE.write(frame, destination, FrameAccess.INSTANCE.read(frame, source));
        }
        // Even unused operands must not remain retained by this activation.
        for (int source : temporaries) frame.clear(source);
        throw AstSelfCall.INSTANCE;
    }
}
