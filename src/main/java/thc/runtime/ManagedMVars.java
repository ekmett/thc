// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;
import thc.runtime.Unit;
import static thc.runtime.RuntimeServiceStatus.fault;

/** MVar AST operations preserve operands across a pre-commit asynchronous cut. */
public final class ManagedMVars {
    private ManagedMVars() {}

    public static Expr expression(MVarOp operation, CoreRepresentation proof, Expr[] operands, boolean async) {
        return expression(operation, proof, operands, async, null);
    }
    public static Expr expression(MVarOp operation, CoreRepresentation proof, Expr[] operands, boolean async, Expr blocked) {
        Expr expression = switch (operation) {
            case NEW -> new New(operands[0]);
            case TAKE, READ -> new Read(operands[0], operands[1], operation == MVarOp.TAKE, async, blocked);
            case TRY_TAKE, TRY_READ -> new TryRead(operands[0], operands[1], operation == MVarOp.TRY_TAKE);
            case PUT -> new Put(operands[0], operands[1], operands[2], async, blocked);
            case TRY_PUT -> new TryPut(operands[0], operands[1], operands[2]);
            case IS_EMPTY -> new IsEmpty(operands[0], operands[1]);
        };
        return expression.proven(new CoreRepresentation(proof.getKind(), true, proof.getPresent(),
            proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(),
            proof.getTagSlot(), proof.getAlternativeSlots()));
    }

    private abstract static class TupleExpression extends Expr {
        @Override public Object execute(VirtualFrame frame) { throw fault("Tuple primitive requires a destination"); }
    }
    private static final class New extends TupleExpression {
        @Child private Expr state;
        New(Expr state) { this.state = state; }
        @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
            TupleResults.requireVoidCarrier(state.execute(frame));
            FrameAccess.write(frame, slots[offset], new ManagedMVar());
            return null;
        }
    }
    private static final class Read extends TupleExpression {
        @Child private Expr cell;
        @Child private Expr state;
        private final boolean remove, async;
        @Child private Expr blocked;
        Read(Expr cell, Expr state, boolean remove, boolean async, Expr blocked) {
            this.cell = cell; this.state = state; this.remove = remove; this.async = async; this.blocked = blocked;
        }
        private Object blocked(VirtualFrame frame) { return blocked == null ? null : blocked.execute(frame); }
        private static final class Resume implements AstResumeStep {
            private final Read node;
            private PendingWait wait;
            private final ManagedMVar reference;
            private final int[] slots;
            private final int offset;
            Resume(Read node, ManagedMVar reference, int[] slots, int offset) {
                this.node = node; this.reference = reference; this.slots = slots; this.offset = offset;
            }
            @Override public Object resume(VirtualFrame frame, Object input) {
                if (input != Unit.INSTANCE) throw fault("Invalid AST MVar read resume value");
                Object value;
                try { value = wait != null ? wait.resume() : node.remove ? reference.take(node, true, node.blocked(frame)) : reference.read(node, true, node.blocked(frame)); }
                catch (PendingWait cut) { wait = cut; throw new AstCapture(cut, SynchronousMasking.current(node)).append(this); }
                catch (AsyncBlocked blocked) {
                    wait = null;
                    throw new AstCapture(blocked.getRequest(), SynchronousMasking.current(node)).append(this);
                }
                FrameAccess.write(frame, slots[offset], value);
                return null;
            }
        }
        @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
            var reference = ManagedMVar.require(cell.execute(frame));
            TupleResults.requireVoidCarrier(state.execute(frame));
            Object value;
            try { value = remove ? reference.take(this, async, blocked(frame)) : reference.read(this, async, blocked(frame)); }
            catch (PendingWait cut) {
                Resume resume = new Resume(this, reference, slots, offset); resume.wait = cut;
                throw new AstCapture(cut, SynchronousMasking.current(this)).append(resume);
            }
            catch (AsyncBlocked blocked) {
                throw new AstCapture(blocked.getRequest(), SynchronousMasking.current(this))
                    .append(new Resume(this, reference, slots, offset));
            }
            FrameAccess.write(frame, slots[offset], value);
            return null;
        }
    }
    private static final class TryRead extends TupleExpression {
        @Child private Expr cell;
        @Child private Expr state;
        private final boolean remove;
        TryRead(Expr cell, Expr state, boolean remove) { this.cell = cell; this.state = state; this.remove = remove; }
        @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
            var reference = ManagedMVar.require(cell.execute(frame));
            TupleResults.requireVoidCarrier(state.execute(frame));
            var result = remove ? reference.tryTake() : reference.tryRead();
            FrameAccess.writeLong(frame, slots[offset], result.getPresent() ? 1L : 0L);
            // Failure still overwrites the payload so an earlier successful read cannot retain a guest reference.
            FrameAccess.write(frame, slots[offset + 1], result.getValue());
            return null;
        }
    }
    private static final class Put extends Expr {
        @Child private Expr cell;
        @Child private Expr value;
        @Child private Expr state;
        private final boolean async;
        @Child private Expr blocked;
        Put(Expr cell, Expr value, Expr state, boolean async, Expr blocked) {
            this.cell = cell; this.value = value; this.state = state; this.async = async; this.blocked = blocked;
        }
        private Object blocked(VirtualFrame frame) { return blocked == null ? null : blocked.execute(frame); }
        private static final class Resume implements AstResumeStep {
            private final Put node;
            private PendingWait wait;
            private final ManagedMVar reference;
            private final Object stored;
            Resume(Put node, ManagedMVar reference, Object stored) {
                this.node = node; this.reference = reference; this.stored = stored;
            }
            @Override public Object resume(VirtualFrame frame, Object input) {
                if (input != Unit.INSTANCE) throw fault("Invalid AST MVar put resume value");
                try { if (wait != null) wait.resume(); else reference.put(stored, node, true, node.blocked(frame)); }
                catch (PendingWait cut) { wait = cut; throw new AstCapture(cut, SynchronousMasking.current(node)).append(this); }
                catch (AsyncBlocked blocked) {
                    wait = null;
                    throw new AstCapture(blocked.getRequest(), SynchronousMasking.current(node)).append(this);
                }
                return Unit.INSTANCE;
            }
        }
        @Override public Object execute(VirtualFrame frame) {
            var reference = ManagedMVar.require(cell.execute(frame));
            var stored = value.execute(frame);
            TupleResults.requireVoidCarrier(state.execute(frame));
            try { reference.put(stored, this, async, blocked(frame)); }
            catch (PendingWait cut) {
                Resume resume = new Resume(this, reference, stored); resume.wait = cut;
                throw new AstCapture(cut, SynchronousMasking.current(this)).append(resume);
            }
            catch (AsyncBlocked blocked) {
                throw new AstCapture(blocked.getRequest(), SynchronousMasking.current(this))
                    .append(new Resume(this, reference, stored));
            }
            return Unit.INSTANCE;
        }
    }
    private static final class TryPut extends TupleExpression {
        @Child private Expr cell;
        @Child private Expr value;
        @Child private Expr state;
        TryPut(Expr cell, Expr value, Expr state) { this.cell = cell; this.value = value; this.state = state; }
        @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
            var reference = ManagedMVar.require(cell.execute(frame));
            var stored = value.execute(frame);
            TupleResults.requireVoidCarrier(state.execute(frame));
            FrameAccess.writeLong(frame, slots[offset], reference.tryPut(stored) ? 1L : 0L);
            return null;
        }
    }
    private static final class IsEmpty extends TupleExpression {
        @Child private Expr cell;
        @Child private Expr state;
        IsEmpty(Expr cell, Expr state) { this.cell = cell; this.state = state; }
        @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
            var reference = ManagedMVar.require(cell.execute(frame));
            TupleResults.requireVoidCarrier(state.execute(frame));
            FrameAccess.writeLong(frame, slots[offset], reference.isEmpty() ? 1L : 0L);
            return null;
        }
    }
}
