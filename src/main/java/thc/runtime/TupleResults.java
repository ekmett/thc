// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import static thc.runtime.RuntimeServiceStatus.fault;

public final class TupleResults {
    private TupleResults() {}
    public static void requireVoidCarrier(Object value) {
        if (value != thc.runtime.Unit.INSTANCE) throw fault("Invalid zero-width scalar carrier");
    }
    public static HandoffStorage ownedTupleResult(Object result, TupleShape shape) {
        TupleResultPool pool = shape.getLanguage().getHandoffState().get().getResults();
        boolean pooled = result == TupleComplete.INSTANCE;
        HandoffStorage source;
        if (pooled) source = pool.completed();
        else if (result instanceof HandoffStorage storage) source = storage;
        else throw fault("Invalid completed tuple result carrier");
        try {
            HandoffLayout layout = shape.getLayout();
            if (source.getLayout() != layout) throw new IllegalStateException("Check failed.");
            HandoffStorage owned = layout.create();
            int width = shape.getWidth();
            // Specialize fixed tuple fields during PE without exploding dynamic widths.
            if (CompilerDirectives.isPartialEvaluationConstant(width)) copyFixed(source, owned, shape, layout, width);
            else for (int i = 0; i < width; i++) copyField(source, owned, shape, layout, i);
            return owned;
        } finally { if (pooled) pool.releaseChecked(source, shape.getLayout()); }
    }
    @ExplodeLoop private static void copyFixed(HandoffStorage source, HandoffStorage owned, TupleShape shape, HandoffLayout layout, int width) {
        for (int i = 0; i < width; i++) copyField(source, owned, shape, layout, i);
    }
    private static void copyField(HandoffStorage source, HandoffStorage owned, TupleShape shape, HandoffLayout layout, int i) {
        if (layout.isInt(i)) layout.setInt(owned, i, layout.getInt(source, i));
        else if (layout.isLong(i)) layout.setLong(owned, i, layout.getLong(source, i));
        else if (layout.isFloat(i)) layout.setFloat(owned, i, layout.getFloat(source, i));
        else if (layout.isDouble(i)) layout.setDouble(owned, i, layout.getDouble(source, i));
        else layout.setObject(owned, i, shape.checkedReference(i, layout.getObject(source, i)));
    }
}
