// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.profiles.CountingConditionProfile;
import static thc.runtime.RuntimeFault.fault;

final class Alternative extends Node {
    static final int DEFAULT_ALTERNATIVE = 0;
    static final int DATA_ALTERNATIVE = 1;
    static final int LITERAL_ALTERNATIVE = 2;
    private final int kind;
    private final Object value;
    @CompilationFinal(dimensions = 1) private final int[] fields;
    @Child private Expr body;
    @CompilationFinal(dimensions = 1) private int[] deadReferences = new int[0];
    @CompilationFinal(dimensions = 2) private final int[][] vectorFields;
    private final CountingConditionProfile matchProfile;
    private final int programSlot;
    private final int constructorIndex;
    Alternative(int kind, Object value, int[] fields, Expr body) { this(kind, value, fields, body, new int[0][]); }
    Alternative(int kind, Object value, int[] fields, Expr body, int[][] vectorFields) {
        this(kind, value, fields, body, vectorFields, true);
    }
    Alternative(int kind, Object value, int[] fields, Expr body, int[][] vectorFields, boolean profileChoice) {
        this(kind, value, fields, body, vectorFields, profileChoice, -1, -1);
    }
    Alternative(int kind, Object value, int[] fields, Expr body, int[][] vectorFields, boolean profileChoice,
                int programSlot, int constructorIndex) {
        this.kind = kind; this.value = value; this.fields = fields; this.body = body; this.vectorFields = vectorFields;
        this.programSlot = programSlot; this.constructorIndex = constructorIndex;
        // A singleton still checks its match. Do not store the uncached sentinel:
        // NodeUtil clones it, losing the identity guard around its disabled counters.
        matchProfile = profileChoice ? CountingConditionProfile.create() : null;
    }
    public int getKind() { return kind; }
    public Object getValue() { return value; }
    public int[] getFields() { return fields; }
    public Expr getBody() { return body; }
    public void setBody(Expr body) { this.body = body; }
    public int[][] getVectorFields() { return vectorFields; }
    /** Assigned by lowering before this alternative is adopted. */
    void discardUnusedFields(int[] slots) { deadReferences = slots; }
    @ExplodeLoop void releaseUnusedFields(VirtualFrame frame) {
        for (int slot : deadReferences) frame.clear(slot);
    }
    DataLayout layout(VirtualFrame frame) {
        return constructorIndex < 0 ? (DataLayout) value : Program.instance(frame, programSlot).constructorLayout(constructorIndex);
    }
    void restore(DataValue data, int index, VirtualFrame frame, int slot) {
        if (value instanceof DataLayout.Reusable storage) storage.restore(layout(frame), data, index, frame, slot);
        else layout(frame).restore(data, index, frame, slot);
    }
    void restoreVector(DataValue data, int index, VirtualFrame frame, int[] slots) {
        if (value instanceof DataLayout.Reusable storage) storage.restoreVector(layout(frame), data, index, frame, slots, 0);
        else layout(frame).restoreVector(data, index, frame, slots, 0);
    }
    boolean matchesData(VirtualFrame frame, DataValue value) {
        boolean matches = layout(frame).matches(value);
        return matchProfile == null ? matches : matchProfile.profile(matches);
    }
    boolean matchesLong(long value) {
        boolean matches = value == (long) (Long) this.value;
        return matchProfile == null ? matches : matchProfile.profile(matches);
    }
    boolean matches(VirtualFrame frame, int slot) {
        boolean matches = switch (kind) {
            case DATA_ALTERNATIVE -> frame.isObject(slot) && layout(frame).matches(frame.getObject(slot));
            case LITERAL_ALTERNATIVE -> matchesLiteral(frame, slot);
            default -> false;
        };
        return matchProfile == null ? matches : matchProfile.profile(matches);
    }
    private boolean matchesLiteral(VirtualFrame frame, int slot) {
        if (value instanceof Integer literal) {
            if (frame.isInt(slot)) return frame.getInt(slot) == literal;
            Object scrutinee = numericObject(frame, slot);
            return scrutinee instanceof Integer number && number.intValue() == literal.intValue();
        }
        if (value instanceof Long literal) {
            if (frame.isLong(slot)) return frame.getLong(slot) == literal;
            Object scrutinee = numericObject(frame, slot);
            return scrutinee instanceof Long number && number.longValue() == literal.longValue();
        }
        // Remaining literal Addr# carriers compare by identity, never guest equals.
        return FrameAccess.INSTANCE.read(frame, slot) == value;
    }
    private static Object numericObject(VirtualFrame frame, int slot) {
        if (frame.isObject(slot)) return frame.getObject(slot);
        // Other primitive carriers cannot match; do not box them just to reject them.
        if (frame.isInt(slot) || frame.isLong(slot) || frame.isFloat(slot) ||
            frame.isDouble(slot) || frame.isBoolean(slot)) return null;
        throw fault("Unsupported runtime frame slot tag");
    }
}
