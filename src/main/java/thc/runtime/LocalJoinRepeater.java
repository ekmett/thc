// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.nodes.*;
import static thc.runtime.RuntimeServiceStatus.fault;
final class LocalJoinRepeater extends Node implements RepeatingNode {
    private final Object group;
    private final int selector, result;
    @Children private Expr[] bodies;
    @CompilerDirectives.CompilationFinal(dimensions = 1) private final int[] tupleSlots;
    private final boolean delimited, tuple, exactLong, exactFloat, exactDouble;
    private final CoreKind referenceKind;
    LocalJoinRepeater(Object group, int selector, int result, Expr[] bodies, CoreRepresentation proof, int[] tupleSlots, boolean delimited) {
        this.group = group; this.selector = selector; this.result = result; this.bodies = bodies; this.tupleSlots = tupleSlots; this.delimited = delimited;
        tuple = proof.isTypedTransport(); exactLong = proof.isLong(); exactFloat = proof.isFloat(); exactDouble = proof.isDouble();
        referenceKind = proof.getEvaluated() ? proof.getKind() : CoreKind.UNKNOWN;
    }
    private void executeBody(VirtualFrame frame, Expr body) {
        if (!delimited && !AstControl.captures(this)) { executeUninterrupted(frame, body); return; }
        try { executeUninterrupted(frame, body); }
        catch (AstCapture cut) { throw cut.append((saved, input) -> { saveResult(saved, input); return null; }); }
        catch (DelimitedCut cut) {
            if (!delimited) throw cut;
            throw cut.append(frame, (saved, input, ambient, outerMask) -> {
                Object value = input.get(); if (!tuple) FrameAccess.write(saved, result, value); return null;
            });
        }
    }
    private void saveResult(VirtualFrame frame, Object value) {
        if (tuple) return;
        if (exactLong) {
            if (!(value instanceof Long scalar)) throw fault("Expected primitive Long join result");
            FrameAccess.writeLong(frame, result, scalar);
        } else if (exactFloat) {
            if (!(value instanceof Float scalar)) throw fault("Expected primitive Float join result");
            FrameAccess.writeFloat(frame, result, scalar);
        } else if (exactDouble) {
            if (!(value instanceof Double scalar)) throw fault("Expected primitive Double join result");
            FrameAccess.writeDouble(frame, result, scalar);
        } else {
            if (referenceKind == CoreKind.DATA && !(value instanceof DataValue)) throw fault("Expected constructor join result");
            if (referenceKind == CoreKind.CLOSURE && !(value instanceof Closure)) throw fault("Expected closure join result");
            if (referenceKind == CoreKind.ADDRESS && !(value instanceof ManagedAddress)) throw fault("Expected address join result");
            FrameAccess.write(frame, result, value);
        }
    }
    private void executeUninterrupted(VirtualFrame frame, Expr body) {
        if (tuple) body.executeTuple(frame, tupleSlots, 0);
        else if (exactLong) FrameAccess.writeLong(frame, result, body.executeRequiredLong(frame));
        else if (exactFloat) FrameAccess.writeFloat(frame, result, body.executeRequiredFloat(frame));
        else if (exactDouble) FrameAccess.writeDouble(frame, result, body.executeRequiredDouble(frame));
        else if (referenceKind == CoreKind.DATA) FrameAccess.write(frame, result, body.executeRequiredDataValue(frame));
        else if (referenceKind == CoreKind.CLOSURE) FrameAccess.write(frame, result, body.executeRequiredClosure(frame));
        else if (referenceKind == CoreKind.ADDRESS) FrameAccess.write(frame, result, body.executeRequiredAddress(frame));
        else FrameAccess.write(frame, result, body.execute(frame));
    }
    @ExplodeLoop void executeTarget(VirtualFrame frame, long selected) {
        for (int index = 1; index < bodies.length; index++) if (selected == index) { executeBody(frame, bodies[index]); return; }
        throw fault("Invalid local join selector");
    }
    void executeOnce(VirtualFrame frame) {
        try { executeBody(frame, bodies[0]); }
        catch (LocalJoinJump jump) {
            if (jump.getTarget().getGroup() != group) throw jump;
            executeTarget(frame, jump.getTarget().getIndex());
        }
    }
    @Override public boolean executeRepeating(VirtualFrame frame) {
        try {
            long selected = frame.getLong(selector);
            boolean enteredCompiled = CompilerDirectives.inCompiledCode();
            if (AstControl.enabled(this)) {
                AsyncRequest request = GuestThreads.pollCurrent(this, false);
                if (request != null) {
                    request.compiledCapture = enteredCompiled;
                    throw new AstCapture(request, SynchronousMasking.current(this)).append((saved, input) -> {
                        if (input != thc.runtime.Unit.INSTANCE) throw fault("Local join loop continuation requires Unit");
                        executeSelected(saved, selected); return null;
                    });
                }
            }
            executeSelected(frame, selected); return false;
        } catch (LocalJoinJump jump) {
            if (jump.getTarget().getGroup() != group) throw jump;
            frame.setLong(selector, jump.getTarget().getIndex()); return true;
        }
    }
    private void executeSelected(VirtualFrame frame, long selected) {
        if (selected == 0L) executeBody(frame, bodies[0]); else executeTarget(frame, selected);
    }
}
