// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;

import static thc.runtime.RuntimeFault.fault;

/** Replay-local destinations for a typed input loan; never retains an activation. */
public final class BytecodeTypedInputSlots {
    private final TypedInputLayout entry;
    private final LocalAccessor bloom;
    @CompilationFinal(dimensions = 1) private final LocalAccessor[] arguments;
    @CompilationFinal(dimensions = 1) private final int[] indices;
    @CompilationFinal(dimensions = 1) private final CoreRepresentation[] proofs;
    private final CaptureLayout captureLayout;
    @CompilationFinal(dimensions = 1) private final LocalAccessor[] captures;
    @CompilationFinal(dimensions = 1) private final CoreRepresentation[] captureProofs;
    @CompilationFinal(dimensions = 1) private final BytecodeRoot.VectorCaptureSlots[] vectorCaptures;
    @CompilationFinal(dimensions = 1) private final Class<?>[] references;
    @CompilationFinal(dimensions = 1) private final Class<?>[] captureReferences;

    public BytecodeTypedInputSlots(TypedInputLayout entry, LocalAccessor bloom,
            LocalAccessor[] arguments, int[] indices, CoreRepresentation[] proofs,
            CaptureLayout captureLayout, LocalAccessor[] captures, CoreRepresentation[] captureProofs) {
        this(entry, bloom, arguments, indices, proofs, captureLayout, captures, captureProofs,
            new BytecodeRoot.VectorCaptureSlots[0]);
    }

    public BytecodeTypedInputSlots(TypedInputLayout entry, LocalAccessor bloom,
            LocalAccessor[] arguments, int[] indices, CoreRepresentation[] proofs,
            CaptureLayout captureLayout, LocalAccessor[] captures, CoreRepresentation[] captureProofs,
            BytecodeRoot.VectorCaptureSlots[] vectorCaptures) {
        this.entry = entry;
        this.bloom = bloom;
        this.arguments = arguments;
        this.indices = indices;
        this.proofs = proofs;
        this.captureLayout = captureLayout;
        this.captures = captures;
        this.captureProofs = captureProofs;
        this.vectorCaptures = vectorCaptures;
        references = new Class<?>[proofs.length];
        for (int i = 0; i < proofs.length; ++i) references[i] = proofs[i].referenceCarrier();
        captureReferences = new Class<?>[captureProofs.length];
        for (int i = 0; i < captureProofs.length; ++i) captureReferences[i] = captureProofs[i].referenceCarrier();
    }

    public void enter(VirtualFrame frame, BytecodeRoot root) {
        restore(frame, root.getBytecodeNode(), entry.take(frame.getArguments()), true, root.mask);
    }

    public void tail(VirtualFrame frame, BytecodeRoot root, TailCall transfer) {
        var input = transfer.getInput();
        if (input == null) throw fault("Typed input target received a scalar packet");
        restore(frame, root.getBytecodeNode(), input, false, root.mask);
    }

    /** All source slots are disjoint snapshots: copying formals and captures is a parallel move. */
    @ExplodeLoop
    public void self(VirtualFrame frame, BytecodeRoot root, BytecodeInputSource source, Closure function) {
        try {
            entry.validateSelfSource(source, frame, root);
            var bytecode = root.getBytecodeNode();
            var packet = entry.getPacket();
            for (int i = 0; i < arguments.length; ++i) {
                int from = indices[i];
                int field = entry.getHeader() + from;
                if (packet.isInt(field)) arguments[i].setInt(bytecode, frame, source.readInt(frame, root, null, from));
                else if (packet.isLong(field)) arguments[i].setLong(bytecode, frame, source.readLong(frame, root, null, from));
                else if (packet.isFloat(field)) arguments[i].setFloat(bytecode, frame, source.readFloat(frame, root, null, from));
                else if (packet.isDouble(field)) arguments[i].setDouble(bytecode, frame, source.readDouble(frame, root, null, from));
                else {
                    Object value = source.reference(frame, root, null, from);
                    Class<?> expected = references[i];
                    arguments[i].setObject(bytecode, frame,
                        expected == null ? value : AstSelfCalls.requireReferenceCarrier(value, expected));
                }
            }
            if (captureLayout != null) {
                var environment = function.environment;
                if (environment == null) throw fault("Invalid captured frame");
                restoreCaptured(frame, bytecode, environment);
            }
            // Neither bloom nor the current result destination changes on a self reentry.
        } finally {
            clearSource(source, frame, root);
        }
    }

    @ExplodeLoop
    private void restoreCaptured(VirtualFrame frame, BytecodeNode bytecode, CapturedFrame environment) {
        var layout = captureLayout;
        if (layout == null) throw fault("Missing capture layout");
        for (int i = 0; i < captures.length; ++i) {
            var proof = captureProofs[i];
            if (proof.isInt() && proof.getEvaluated()) captures[i].setInt(bytecode, frame, layout.readInt(environment, i));
            else if (proof.isLong() && proof.getEvaluated()) captures[i].setLong(bytecode, frame, layout.readLong(environment, i));
            else if (proof.isFloat() && proof.getEvaluated()) captures[i].setFloat(bytecode, frame, layout.readFloat(environment, i));
            else if (proof.isDouble() && proof.getEvaluated()) captures[i].setDouble(bytecode, frame, layout.readDouble(environment, i));
            else {
                Object value = layout.read(environment, i);
                Class<?> expected = captureReferences[i];
                captures[i].setObject(bytecode, frame,
                    expected == null ? value : AstSelfCalls.requireReferenceCarrier(value, expected));
            }
        }
        for (var vector : vectorCaptures) vector.restore(frame, bytecode, environment);
    }

    @ExplodeLoop
    private void restore(VirtualFrame frame, BytecodeNode bytecode,
            HandoffStorage input, boolean initial, long mask) {
        try {
            if (initial) entry.validate(input); else entry.validateTail(input);
            var packet = entry.getPacket();
            if (initial) bloom.setLong(bytecode, frame, packet.getLong(input, 0) | mask);
            for (int i = 0; i < arguments.length; ++i) {
                int from = indices[i] + entry.getHeader();
                if (packet.isInt(from)) arguments[i].setInt(bytecode, frame, packet.getInt(input, from));
                else if (packet.isLong(from)) arguments[i].setLong(bytecode, frame, packet.getLong(input, from));
                else if (packet.isFloat(from)) arguments[i].setFloat(bytecode, frame, packet.getFloat(input, from));
                else if (packet.isDouble(from)) arguments[i].setDouble(bytecode, frame, packet.getDouble(input, from));
                else {
                    Object value = packet.getObject(input, from);
                    Class<?> expected = references[i];
                    arguments[i].setObject(bytecode, frame,
                        expected == null ? value : AstSelfCalls.requireReferenceCarrier(value, expected));
                }
            }
            if (captureLayout != null) {
                if (!(packet.getObject(input, 1) instanceof CapturedFrame environment))
                    throw fault("Invalid captured frame");
                restoreCaptured(frame, bytecode, environment);
            }
        } finally {
            // A failed checked reference restore still relinquishes the loan.
            // This pool is independent of any outstanding tuple-result storage.
            entry.releaseChecked(input);
        }
    }

    /** Operand temporaries cease to own references after dispatch, including a tail bounce. */
    @ExplodeLoop
    public static void clearSource(BytecodeInputSource source, VirtualFrame frame, BytecodeRoot root) {
        for (var slot : source.getSlots()) slot.clear(root.getBytecodeNode(), frame);
    }

}
