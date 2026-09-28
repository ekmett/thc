// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import static thc.runtime.RuntimeServiceStatus.fault;
public final class BytecodeTupleSlots extends TupleDestination {
    @CompilationFinal(dimensions = 1) private final LocalAccessor[] slots;
    private final boolean capturesYield;
    private final boolean capturesFrame;
    public BytecodeTupleSlots(TupleShape shape, LocalAccessor[] slots) { this(shape, slots, false, false); }
    public BytecodeTupleSlots(TupleShape shape, LocalAccessor[] slots, boolean capturesYield) { this(shape, slots, capturesYield, false); }
    public BytecodeTupleSlots(TupleShape shape, LocalAccessor[] slots, boolean capturesYield, boolean capturesFrame) {
        super(shape); this.slots = slots; this.capturesYield = capturesYield; this.capturesFrame = capturesFrame;
    }
    public boolean getCapturesYield() { return capturesYield; }
    @ExplodeLoop private void write(VirtualFrame frame, BytecodeNode node, HandoffStorage output) {
        HandoffLayout layout = getShape().getLayout();
        try { for (int i = 0; i < slots.length; i++) {
            if (layout.isInt(i)) layout.setInt(output, i, slots[i].getInt(node, frame));
            else if (layout.isLong(i)) layout.setLong(output, i, slots[i].getLong(node, frame));
            else if (layout.isFloat(i)) layout.setFloat(output, i, slots[i].getFloat(node, frame));
            else if (layout.isDouble(i)) layout.setDouble(output, i, slots[i].getDouble(node, frame));
            else layout.setObject(output, i, getShape().checkedReference(i, slots[i].getObject(node, frame)));
        } } catch (UnexpectedResultException failure) { throw rethrow(failure); }
    }
    public Object finish(VirtualFrame frame, BytecodeNode node) {
        TupleShape shape = getShape();
        if (shape.inlineResult()) {
            HandoffStorage virtual = shape.getLayout().create(); write(frame, node, virtual);
            if (capturesFrame) CompilerDirectives.ensureVirtualizedHere(virtual); else CompilerDirectives.ensureVirtualized(virtual);
            return virtual;
        }
        TupleResultPool pool = shape.getLanguage().getHandoffState$org_intelligence_thc().get().getResults();
        HandoffStorage output = pool.acquire(shape.getLayout());
        try { write(frame, node, output); return pool.complete(output); }
        catch (Throwable failure) { pool.release(output, shape.getLayout()); throw failure; }
    }
    @ExplodeLoop public void copyFrom(VirtualFrame frame, BytecodeNode node, HandoffStorage receiver) {
        HandoffLayout layout = getShape().getLayout();
        for (int i = 0; i < slots.length; i++) {
            if (layout.isInt(i)) slots[i].setInt(node, frame, layout.getInt(receiver, i));
            else if (layout.isLong(i)) slots[i].setLong(node, frame, layout.getLong(receiver, i));
            else if (layout.isFloat(i)) slots[i].setFloat(node, frame, layout.getFloat(receiver, i));
            else if (layout.isDouble(i)) slots[i].setDouble(node, frame, layout.getDouble(receiver, i));
            else slots[i].setObject(node, frame, getShape().checkedReference(i, layout.getObject(receiver, i)));
        }
    }
    @Override public void consume(VirtualFrame frame, Node node, Object result) {
        var rootValue = node.getRootNode();
        if (rootValue == null) {
            CompilerDirectives.transferToInterpreter();
            throw new NullPointerException("null cannot be cast to non-null type thc.runtime.BytecodeRoot");
        }
        BytecodeRoot root = (BytecodeRoot) rootValue;
        if (result == TupleComplete.INSTANCE) {
            TupleResultPool pool = getShape().getLanguage().getHandoffState$org_intelligence_thc().get().getResults();
            HandoffStorage output = pool.completed();
            try {
                if (output.getLayout() != getShape().getLayout()) throw new IllegalStateException("Check failed.");
                copyFrom(frame, root.getBytecodeNode(), output);
            } finally { pool.releaseChecked(output, getShape().getLayout()); }
        } else {
            if (!(result instanceof HandoffStorage carrier)) throw fault("Invalid tuple result carrier");
            if (carrier.getLayout() != getShape().getLayout()) throw new IllegalStateException("Check failed.");
            copyFrom(frame, root.getBytecodeNode(), carrier);
        }
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException rethrow(Throwable failure) throws E { throw (E) failure; }
}
