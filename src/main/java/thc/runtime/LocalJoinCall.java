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
    @CompilerDirectives.CompilationFinal(dimensions = 1) private final int[] destinations;
    @CompilerDirectives.CompilationFinal(dimensions = 1) private int[] releasedSlots;
    private final boolean directArgument;
    private final Metrics metrics;
    private final boolean stableScalars;
    @CompilerDirectives.CompilationFinal(dimensions = 2) private final int[][] typedTemporaries;
    @CompilerDirectives.CompilationFinal(dimensions = 1) private final CoreKind[] referenceKinds;
    @Children private TupleLocalRead[] typedCopies;
    public LocalJoinCall(thc.Language language, LocalJoinTarget target, Expr[] arguments, int[] temporaries, Metrics metrics) {
        this(language, target, arguments, temporaries, metrics, new int[target.getProofs().length][]);
    }
    public LocalJoinCall(thc.Language language, LocalJoinTarget target, Expr[] arguments, int[] temporaries, Metrics metrics, int[][] typedTemporaries) {
        this(language, target, arguments, temporaries, metrics, typedTemporaries, false);
    }
    LocalJoinCall(thc.Language language, LocalJoinTarget target, Expr[] arguments, int[] temporaries, Metrics metrics,
                  int[][] typedTemporaries, boolean stableScalars) {
        this(language, target, arguments, temporaries, metrics, typedTemporaries, stableScalars, false);
    }
    static LocalJoinCall unaryReference(thc.Language language, LocalJoinTarget target, Expr[] arguments, Metrics metrics) {
        return new LocalJoinCall(language, target, arguments, target.getSlots(), metrics, new int[1][], false, true);
    }
    private LocalJoinCall(thc.Language language, LocalJoinTarget target, Expr[] arguments, int[] destinations, Metrics metrics,
                          int[][] typedTemporaries, boolean stableScalars, boolean directArgument) {
        this.target = target; this.arguments = arguments; this.destinations = destinations;
        this.metrics = metrics; this.typedTemporaries = typedTemporaries; this.directArgument = directArgument;
        this.stableScalars = stableScalars;
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
                arguments[i].prepareTuple(typedTemporaries[i], 0);
            }
        }
    }
    LocalJoinJump prepareTransfer(VirtualFrame frame) { prepare(frame, 0); return transfer(frame); }
    /** Set before adoption on transfers proved to leave these slots dead. */
    void releaseSlotsOnTransfer(Object group, int[] slots) {
        if (slots.length != 0 && target.getGroup() == group) releasedSlots = slots;
    }
    @Override public Object execute(VirtualFrame frame) { throw prepareTransfer(frame); }
    @ExplodeLoop private void prepare(VirtualFrame frame, int first) {
        for (int i = first; i < arguments.length; i++) {
            try {
                if (typedCopies[i] != null) {
                    if (typedTemporaries[i] == null) throw fault("Missing typed join temporaries");
                    arguments[i].executeTuple(frame, typedTemporaries[i], 0);
                } else if (target.getProofs()[i].isLong()) writeLong(frame, destinations[i], arguments[i].executeRequiredLong(frame));
                else if (target.getProofs()[i].isFloat()) writeFloat(frame, destinations[i], arguments[i].executeRequiredFloat(frame));
                else if (target.getProofs()[i].isDouble()) writeDouble(frame, destinations[i], arguments[i].executeRequiredDouble(frame));
                else if (referenceKinds[i] == CoreKind.DATA) FrameAccess.write(frame, destinations[i], arguments[i].executeRequiredDataValue(frame));
                else if (referenceKinds[i] == CoreKind.CLOSURE) FrameAccess.write(frame, destinations[i], arguments[i].executeRequiredClosure(frame));
                else if (referenceKinds[i] == CoreKind.ADDRESS) FrameAccess.write(frame, destinations[i], arguments[i].executeRequiredAddress(frame));
                else FrameAccess.write(frame, destinations[i], arguments[i].execute(frame));
            } catch (AstCapture cut) {
                int index = i;
                throw cut.append((saved, input) -> { saveArgument(saved, index, input); prepare(saved, index + 1); throw transfer(saved); });
            } catch (DelimitedCut cut) {
                int index = i;
                throw cut.append(frame, (saved, input, ambient, outer) -> {
                    saveArgument(saved, index, input.get()); prepare(saved, index + 1); throw transfer(saved);
                });
            }
        }
    }
    private void saveArgument(VirtualFrame frame, int index, Object value) {
        if (typedCopies[index] != null) return;
        if (target.getProofs()[index].isLong()) {
            if (!(value instanceof Long scalar)) throw fault("Expected primitive Long join argument");
            writeLong(frame, destinations[index], scalar);
        } else if (target.getProofs()[index].isFloat()) {
            if (!(value instanceof Float scalar)) throw fault("Expected primitive Float join argument");
            writeFloat(frame, destinations[index], scalar);
        } else if (target.getProofs()[index].isDouble()) {
            if (!(value instanceof Double scalar)) throw fault("Expected primitive Double join argument");
            writeDouble(frame, destinations[index], scalar);
        } else {
            if (referenceKinds[index] == CoreKind.DATA && !(value instanceof DataValue)) throw fault("Expected constructor join argument");
            if (referenceKinds[index] == CoreKind.CLOSURE && !(value instanceof Closure)) throw fault("Expected closure join argument");
            if (referenceKinds[index] == CoreKind.ADDRESS && !(value instanceof ManagedAddress)) throw fault("Expected address join argument");
            FrameAccess.write(frame, destinations[index], value);
        }
    }
    @ExplodeLoop private LocalJoinJump transfer(VirtualFrame frame) {
        if (!directArgument) transferStaged(frame);
        // Every argument has arrived, including after suspension.
        target.clearBodySlots(frame);
        if (releasedSlots != null) for (int slot : releasedSlots) frame.clear(slot);
        Metrics invocation = metrics != null ? metrics : ((FunctionRoot) getRootNode()).invocationMetrics(frame);
        if (invocation.getEnabled()) invocation.incrementLocalJoinTransfers();
        return target.getJump();
    }
    @ExplodeLoop private void transferStaged(VirtualFrame frame) {
        for (int i = 0; i < arguments.length; i++) {
            if (typedCopies[i] != null) {
                if (target.getTypedSlots()[i] == null) throw fault("Missing typed join formals");
                typedCopies[i].executeTuple(frame, target.getTypedSlots()[i], 0);
            } else if (target.getProofs()[i].isLong()) writeLong(frame, target.getSlots()[i], frame.getLong(destinations[i]));
            else if (target.getProofs()[i].isFloat()) writeFloat(frame, target.getSlots()[i], frame.getFloat(destinations[i]));
            else if (target.getProofs()[i].isDouble()) writeDouble(frame, target.getSlots()[i], frame.getDouble(destinations[i]));
            else if (referenceKinds[i] == CoreKind.DATA || referenceKinds[i] == CoreKind.CLOSURE || referenceKinds[i] == CoreKind.ADDRESS) {
                Object value = frame.getObject(destinations[i]);
                if (referenceKinds[i] == CoreKind.DATA && !(value instanceof DataValue)) throw fault("Expected constructor join argument");
                if (referenceKinds[i] == CoreKind.CLOSURE && !(value instanceof Closure)) throw fault("Expected closure join argument");
                if (referenceKinds[i] == CoreKind.ADDRESS && !(value instanceof ManagedAddress)) throw fault("Expected address join argument");
                FrameAccess.write(frame, target.getSlots()[i], value);
            } else FrameAccess.write(frame, target.getSlots()[i], FrameAccess.read(frame, destinations[i]));
        }
        for (int temporary : destinations) if (temporary >= 0) frame.clear(temporary);
        for (int[] fields : typedTemporaries) if (fields != null) for (int field : fields) frame.clear(field);
    }
    // Program lowering proves both staging and formal lifetimes use the same scalar tag.
    private void writeLong(VirtualFrame frame, int slot, long value) {
        if (stableScalars) frame.setLong(slot, value);
        else FrameAccess.writeLong(frame, slot, value);
    }
    private void writeFloat(VirtualFrame frame, int slot, float value) {
        if (stableScalars) frame.setFloat(slot, value);
        else FrameAccess.writeFloat(frame, slot, value);
    }
    private void writeDouble(VirtualFrame frame, int slot, double value) {
        if (stableScalars) frame.setDouble(slot, value);
        else FrameAccess.writeDouble(frame, slot, value);
    }
    @Override public long executeLong(VirtualFrame frame) { execute(frame); throw new AssertionError(); }
    @Override public float executeFloat(VirtualFrame frame) { execute(frame); throw new AssertionError(); }
    @Override public double executeDouble(VirtualFrame frame) { execute(frame); throw new AssertionError(); }
    @Override public Closure executeClosure(VirtualFrame frame) { execute(frame); throw new AssertionError(); }
    @Override public DataValue executeDataValue(VirtualFrame frame) { execute(frame); throw new AssertionError(); }
    @Override public ManagedAddress executeAddress(VirtualFrame frame) { execute(frame); throw new AssertionError(); }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) { return execute(frame); }
}
