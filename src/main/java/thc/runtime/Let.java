// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import static thc.runtime.RuntimeFault.fault;

final class Let extends Expr {
    @CompilationFinal(dimensions = 1) private final int[] slots;
    @Child private Expr body;
    @Children private LocalBinding[] bindings;
    private final boolean recursive;
    Let(int[] slots, Expr[] rhs, boolean[] primitiveEligible, Expr body, boolean recursive) {
        this(slots, rhs, primitiveEligible, body, recursive, new int[rhs.length][]);
    }
    Let(int[] slots, Expr[] rhs, boolean[] primitiveEligible, Expr body, boolean recursive, int[][] vectorSlots) {
        this.slots = slots; this.body = body; this.recursive = recursive;
        setRepresentation(body.getRepresentation());
        bindings = new LocalBinding[rhs.length];
        for (int i = 0; i < rhs.length; i++)
            bindings[i] = new LocalBinding(slots[i], rhs[i], !recursive && primitiveEligible[i], vectorSlots[i]);
    }
    @Override public Object execute(VirtualFrame frame) { initialize(frame, null, 0); return body.execute(frame); }
    @Override public int executeInt(VirtualFrame frame) throws UnexpectedResultException {
        initialize(frame, null, 0); return body.executeInt(frame);
    }
    @Override public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        initialize(frame, null, 0); return body.executeLong(frame);
    }
    @Override public float executeFloat(VirtualFrame frame) throws UnexpectedResultException {
        initialize(frame, null, 0); return body.executeFloat(frame);
    }
    @Override public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        initialize(frame, null, 0); return body.executeDouble(frame);
    }
    @Override public Closure executeClosure(VirtualFrame frame) throws UnexpectedResultException {
        initialize(frame, null, 0); return body.executeClosure(frame);
    }
    @Override public DataValue executeDataValue(VirtualFrame frame) throws UnexpectedResultException {
        initialize(frame, null, 0); return body.executeDataValue(frame);
    }
    @Override public ManagedAddress executeAddress(VirtualFrame frame) throws UnexpectedResultException {
        initialize(frame, null, 0); return body.executeAddress(frame);
    }
    @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
        initialize(frame, slots, offset); return body.executeTuple(frame, slots, offset);
    }
    private void initialize(VirtualFrame frame, int[] destination, int offset) {
        try { initializeFrom(frame, 0); }
        catch (AstCapture cut) {
            throw cut.append(new AstResumeStep() {
                @Override public Object resume(VirtualFrame frame, Object input) {
                    return destination == null ? body.execute(frame) : body.executeTuple(frame, destination, offset);
                }
            });
        }
    }
    @ExplodeLoop private void initializeFrom(VirtualFrame frame, int start) {
        if (recursive) {
            // Closures capture cells, never the mutable activation frame.
            if (start == 0) for (int slot : slots) FrameAccess.INSTANCE.write(frame, slot, new RecCell());
            for (int i = start; i < slots.length; i++) {
                Object raw = FrameAccess.INSTANCE.read(frame, slots[i]);
                if (!(raw instanceof RecCell cell)) throw fault("Invalid recursive cell");
                try { cell.setValue(bindings[i].evaluate(frame)); }
                catch (AstCapture cut) {
                    int index = i;
                    throw cut.append(new AstResumeStep() {
                        @Override public Object resume(VirtualFrame frame, Object input) {
                            cell.setValue(input);
                            cell.setInitialized(true);
                            initializeFrom(frame, index + 1);
                            return kotlin.Unit.INSTANCE;
                        }
                    });
                }
                cell.setInitialized(true);
            }
            // Publish direct locals only after every RHS has captured the group.
            for (int slot : slots) {
                Object raw = FrameAccess.INSTANCE.read(frame, slot);
                if (!(raw instanceof RecCell cell)) throw fault("Invalid recursive cell");
                FrameAccess.INSTANCE.write(frame, slot, cell.getValue());
            }
        } else {
            for (int i = start; i < bindings.length; i++) {
                try { bindings[i].write(frame); }
                catch (AstCapture cut) {
                    int index = i;
                    throw cut.append(new AstResumeStep() {
                        @Override public Object resume(VirtualFrame frame, Object input) {
                            initializeFrom(frame, index + 1);
                            return kotlin.Unit.INSTANCE;
                        }
                    });
                }
            }
        }
    }
}
