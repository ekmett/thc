// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.nodes.*;
import static thc.runtime.RuntimeServiceStatus.fault;
public final class LocalJoinCall extends Expr {
    private final LocalJoinTarget target;
    @Children private Expr[] arguments;
    @CompilerDirectives.CompilationFinal(dimensions = 1) private final int[] temporaries;
    private final Metrics metrics;
    @CompilerDirectives.CompilationFinal(dimensions = 2) private final int[][] typedTemporaries;
    @CompilerDirectives.CompilationFinal(dimensions = 1) private final CoreKind[] referenceKinds;
    @Children private TupleLocalRead[] typedCopies;
    public LocalJoinCall(thc.Language language, LocalJoinTarget target, Expr[] arguments, int[] temporaries, Metrics metrics) {
        this(language, target, arguments, temporaries, metrics, new int[target.getProofs().length][]);
    }
    public LocalJoinCall(thc.Language language, LocalJoinTarget target, Expr[] arguments, int[] temporaries, Metrics metrics, int[][] typedTemporaries) {
        this.target = target; this.arguments = arguments; this.temporaries = temporaries; this.metrics = metrics; this.typedTemporaries = typedTemporaries;
        CoreRepresentation proof = target.getResult();
        setRepresentation(proof.copy(proof.getKind(), true, proof.getPresent(), proof.getPrimReps(), proof.getComponents(), proof.getVector(),
            proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots()));
        referenceKinds = new CoreKind[target.getProofs().length]; typedCopies = new TupleLocalRead[referenceKinds.length];
        for (int i = 0; i < referenceKinds.length; i++) {
            CoreRepresentation argument = target.getProofs()[i];
            referenceKinds[i] = argument.getEvaluated() ? argument.getKind() : CoreKind.UNKNOWN;
            if (argument.isTypedTransport()) {
                if (typedTemporaries[i] == null) throw fault("Missing typed join temporaries");
                typedCopies[i] = new TupleLocalRead(new TupleShape(argument, language), typedTemporaries[i]);
            }
        }
    }
    @Override public Object execute(VirtualFrame frame) { prepare(frame, 0); throw transfer(frame); }
    @ExplodeLoop private void prepare(VirtualFrame frame, int first) {
        for (int i = first; i < arguments.length; i++) {
            try {
                if (typedCopies[i] != null) {
                    if (typedTemporaries[i] == null) throw fault("Missing typed join temporaries");
                    arguments[i].executeTuple(frame, typedTemporaries[i], 0);
                } else if (target.getProofs()[i].isLong()) FrameAccess.writeLong(frame, temporaries[i], arguments[i].executeRequiredLong(frame));
                else if (target.getProofs()[i].isFloat()) FrameAccess.writeFloat(frame, temporaries[i], arguments[i].executeRequiredFloat(frame));
                else if (target.getProofs()[i].isDouble()) FrameAccess.writeDouble(frame, temporaries[i], arguments[i].executeRequiredDouble(frame));
                else if (referenceKinds[i] == CoreKind.DATA) FrameAccess.write(frame, temporaries[i], arguments[i].executeRequiredDataValue(frame));
                else if (referenceKinds[i] == CoreKind.CLOSURE) FrameAccess.write(frame, temporaries[i], arguments[i].executeRequiredClosure(frame));
                else if (referenceKinds[i] == CoreKind.ADDRESS) FrameAccess.write(frame, temporaries[i], arguments[i].executeRequiredAddress(frame));
                else FrameAccess.write(frame, temporaries[i], arguments[i].execute(frame));
            } catch (AstCapture cut) {
                int index = i;
                throw cut.append((saved, input) -> { saveArgument(saved, index, input); prepare(saved, index + 1); throw transfer(saved); });
            }
        }
    }
    private void saveArgument(VirtualFrame frame, int index, Object value) {
        if (typedCopies[index] != null) return;
        if (target.getProofs()[index].isLong()) {
            if (!(value instanceof Long scalar)) throw fault("Expected primitive Long join argument");
            FrameAccess.writeLong(frame, temporaries[index], scalar);
        } else if (target.getProofs()[index].isFloat()) {
            if (!(value instanceof Float scalar)) throw fault("Expected primitive Float join argument");
            FrameAccess.writeFloat(frame, temporaries[index], scalar);
        } else if (target.getProofs()[index].isDouble()) {
            if (!(value instanceof Double scalar)) throw fault("Expected primitive Double join argument");
            FrameAccess.writeDouble(frame, temporaries[index], scalar);
        } else {
            if (referenceKinds[index] == CoreKind.DATA && !(value instanceof DataValue)) throw fault("Expected constructor join argument");
            if (referenceKinds[index] == CoreKind.CLOSURE && !(value instanceof Closure)) throw fault("Expected closure join argument");
            if (referenceKinds[index] == CoreKind.ADDRESS && !(value instanceof ManagedAddress)) throw fault("Expected address join argument");
            FrameAccess.write(frame, temporaries[index], value);
        }
    }
    @ExplodeLoop private LocalJoinJump transfer(VirtualFrame frame) {
        for (int i = 0; i < arguments.length; i++) {
            if (typedCopies[i] != null) {
                if (target.getTypedSlots()[i] == null) throw fault("Missing typed join formals");
                typedCopies[i].executeTuple(frame, target.getTypedSlots()[i], 0);
            } else if (target.getProofs()[i].isLong()) FrameAccess.writeLong(frame, target.getSlots()[i], frame.getLong(temporaries[i]));
            else if (target.getProofs()[i].isFloat()) FrameAccess.writeFloat(frame, target.getSlots()[i], frame.getFloat(temporaries[i]));
            else if (target.getProofs()[i].isDouble()) FrameAccess.writeDouble(frame, target.getSlots()[i], frame.getDouble(temporaries[i]));
            else if (referenceKinds[i] == CoreKind.DATA || referenceKinds[i] == CoreKind.CLOSURE || referenceKinds[i] == CoreKind.ADDRESS) {
                Object value = frame.getObject(temporaries[i]);
                if (referenceKinds[i] == CoreKind.DATA && !(value instanceof DataValue)) throw fault("Expected constructor join argument");
                if (referenceKinds[i] == CoreKind.CLOSURE && !(value instanceof Closure)) throw fault("Expected closure join argument");
                if (referenceKinds[i] == CoreKind.ADDRESS && !(value instanceof ManagedAddress)) throw fault("Expected address join argument");
                FrameAccess.write(frame, target.getSlots()[i], value);
            } else FrameAccess.write(frame, target.getSlots()[i], FrameAccess.read(frame, temporaries[i]));
        }
        for (int temporary : temporaries) if (temporary >= 0) frame.clear(temporary);
        for (int[] fields : typedTemporaries) if (fields != null) for (int field : fields) frame.clear(field);
        Metrics invocation = metrics != null ? metrics : ((FunctionRoot) getRootNode()).invocationMetrics(frame);
        if (invocation.getEnabled()) invocation.incrementLocalJoinTransfers();
        throw target.getJump();
    }
    @Override public long executeLong(VirtualFrame frame) { execute(frame); throw new AssertionError(); }
    @Override public float executeFloat(VirtualFrame frame) { execute(frame); throw new AssertionError(); }
    @Override public double executeDouble(VirtualFrame frame) { execute(frame); throw new AssertionError(); }
    @Override public Closure executeClosure(VirtualFrame frame) { execute(frame); throw new AssertionError(); }
    @Override public DataValue executeDataValue(VirtualFrame frame) { execute(frame); throw new AssertionError(); }
    @Override public ManagedAddress executeAddress(VirtualFrame frame) { execute(frame); throw new AssertionError(); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) { return execute(frame); }
}
