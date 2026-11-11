// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.nodes.Node;
import java.util.List;
import thc.Language;
import static thc.runtime.RuntimeFault.fault;

public final class EntryRoot extends ContextRoot {
    private final int arity;
    private final Metrics metrics;
    private final boolean typedResult;
    private final boolean forceResult;
    @Child private PreparedDispatch dispatch;
    @Child private GenericInputCall typedDispatch;
    @Child private Force force;
    public EntryRoot(TruffleLanguage<?> language, int arity, Metrics metrics) {
        super(language, new FrameLayout().build());
        this.arity = arity; this.metrics = metrics; typedResult = false; forceResult = true;
        dispatch = new PreparedDispatch(arity, false, metrics, new boolean[0], null); force = new Force(metrics);
    }
    /** A signature-specific host root; it does not change the program's arity-only cache. */
    public EntryRoot withSignature(Language language, List<CoreRepresentation> inputs, CoreRepresentation result, TupleShape resultShape) {
        return new EntryRoot(language, inputs, result, metrics, resultShape);
    }
    public EntryRoot(Language language, List<CoreRepresentation> inputs, CoreRepresentation result, Metrics metrics) {
        this(language, inputs, result, metrics, null);
    }
    public EntryRoot(Language language, List<CoreRepresentation> inputs, CoreRepresentation result, Metrics metrics, TupleShape resultShape) {
        super(language, new FrameLayout().build());
        arity = inputs.size(); this.metrics = metrics; typedResult = result.isTypedTransport();
        forceResult = result.isInt() || result.isLong() || result.isFloat() || result.isDouble();
        force = new Force(metrics);
        // A shared root's descriptor survives its preparation context, while the
        // host signature still authenticates its logical shape and vector species.
        if (resultShape != null) TupleShape.requireCompatible(result, resultShape.getProof());
        TupleDestination destination = typedResult ? new HostDestination(resultShape != null ? resultShape : new TupleShape(result, language)) : null;
        typedDispatch = new GenericInputCall(new HostInputSource(ArgumentLayout.fromProofs(inputs)), arity, false, metrics, destination, 0, true);
    }
    @Override public Object execute(VirtualFrame frame) {
        frame.setLong(FrameLayout.BLOOM_FILTER, 0L);
        Object value = force.execute(frame, frame.getArguments()[0]);
        if (arity == 0 && typedDispatch == null) return value;
        if (!(value instanceof Closure fn)) throw fault("Application of a non-function");
        if (!(frame.getArguments()[1] instanceof Object[] args)) throw fault("Invalid host arguments");
        if (typedDispatch != null) {
            Object result = typedDispatch.execute(frame, fn, args);
            return typedResult ? frame.getObject(FrameLayout.TAIL_RESULT) : forceResult ? force.execute(frame, result) : result;
        }
        return force.execute(frame, dispatch.execute(frame, fn, args));
    }
    @Override public String getName() { return "THC host entry/" + arity; }
    @Override protected com.oracle.truffle.api.nodes.ExecutionSignature prepareForAOT() {
        return com.oracle.truffle.api.nodes.ExecutionSignature.GENERIC;
    }

    private static final class HostInputSource extends InputSource {
        HostInputSource(ArgumentLayout layout) { super(layout); }
        @Override public int readInt(VirtualFrame frame, Node node, Object[] values, int index) { return (Integer) values[index]; }
        @Override public long readLong(VirtualFrame frame, Node node, Object[] values, int index) { return (Long) values[index]; }
        @Override public float readFloat(VirtualFrame frame, Node node, Object[] values, int index) { return (Float) values[index]; }
        @Override public double readDouble(VirtualFrame frame, Node node, Object[] values, int index) { return (Double) values[index]; }
        @Override public Object reference(VirtualFrame frame, Node node, Object[] values, int index) { return values[index]; }
        @Override public void setReference(VirtualFrame frame, Node node, Object[] values, int index, Object value) { values[index] = value; }
    }
    private static final class HostDestination extends TupleDestination {
        HostDestination(TupleShape shape) { super(shape); }
        @Override public void consume(VirtualFrame frame, Node node, Object result) { consumeFrom(frame, node, result, getShape()); }
        @Override protected void consumeFrom(VirtualFrame frame, Node node, Object result, TupleShape shape) {
            AsyncContinuations.publicResult(result, node);
            Object[] fields;
            if (result == TupleComplete.INSTANCE) {
                var pool = shape.getLanguage().getHandoffState().get().getResults();
                var storage = pool.completed();
                try { fields = copy(storage, shape); }
                finally { pool.releaseChecked(storage, getShape().getLayout()); }
            } else {
                if (!(result instanceof HandoffStorage storage)) throw fault("Host entry returned no typed result");
                fields = copy(storage, shape);
            }
            frame.setObject(FrameLayout.TAIL_RESULT, fields);
        }
        private Object[] copy(HandoffStorage storage, TupleShape shape) {
            var layout = shape.getLayout();
            if (storage.getLayout() != layout) throw fault("Host entry returned the wrong typed result layout");
            Object[] fields = new Object[shape.getWidth()];
            for (int i = 0; i < fields.length; i++) {
                if (layout.isInt(i)) fields[i] = layout.getInt(storage, i);
                else if (layout.isLong(i)) fields[i] = layout.getLong(storage, i);
                else if (layout.isFloat(i)) fields[i] = layout.getFloat(storage, i);
                else if (layout.isDouble(i)) fields[i] = layout.getDouble(storage, i);
                else fields[i] = shape.checkedReference(i, layout.getObject(storage, i));
            }
            return fields;
        }
    }
}
