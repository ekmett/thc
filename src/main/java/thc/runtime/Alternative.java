// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.profiles.CountingConditionProfile;
import static thc.runtime.RuntimeFault.fault;

final class Alternative extends Node {
    static final int UNUSED_FIELD = Integer.MIN_VALUE;
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
        // A lazy traversal can first reach the terminating constructor after compilation.
        if (matchProfile != null && kind == DATA_ALTERNATIVE && value instanceof DataLayout layout && layout.getArity() == 0)
            matchProfile.disable();
    }
    void prepareOutlinedComparison(Expr scrutinee) {
        if (matchProfile != null && kind == LITERAL_ALTERNATIVE && Long.valueOf(1L).equals(value) &&
                body instanceof AstSameFrameArm && scrutinee instanceof Primitive primitive && primitive.producesComparisonFlag())
            matchProfile.disable();
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
    boolean matchesData(VirtualFrame frame, Object value) {
        boolean matches = layout(frame).matches(value);
        return matchProfile == null ? matches : matchProfile.profile(matches);
    }
    boolean matchesLong(VirtualFrame frame, long value) {
        boolean matches = value == (long) (Long) this.value;
        if (matchProfile == null) return matches;
        finishLoopExitInInterpreter(frame, matches);
        return matchProfile.profile(matches);
    }
    boolean matches(VirtualFrame frame, int slot) {
        boolean matches = switch (kind) {
            case DATA_ALTERNATIVE -> frame.isObject(slot) && layout(frame).matches(frame.getObject(slot));
            case LITERAL_ALTERNATIVE -> matchesLiteral(frame, slot);
            default -> false;
        };
        if (matchProfile == null) return matches;
        finishLoopExitInInterpreter(frame, matches);
        return matchProfile.profile(matches);
    }
    private void finishLoopExitInInterpreter(VirtualFrame frame, boolean matches) {
        // A self loop in OSR uses a borrowed frame. Its first tuple return can
        // train the literal match without invalidating the installed backedge.
        if (CompilerDirectives.inCompiledCode() &&
                !CompilerDirectives.isPartialEvaluationConstant(frame.getFrameDescriptor()) &&
                matches && kind == LITERAL_ALTERNATIVE &&
                getParent() instanceof Case selection && selection.getParent() instanceof FunctionBody &&
                (body instanceof TupleConstruct || body instanceof AstOperands operands && operands.constructsTuple()))
            CompilerDirectives.transferToInterpreter();
    }
    private boolean matchesLiteral(VirtualFrame frame, int slot) {
        if (value instanceof Integer literal) {
            if (knownKind(frame, slot, FrameSlotKind.Int) || frame.isInt(slot)) return frame.getInt(slot) == literal;
            return literal.equals(numericObject(frame, slot));
        }
        if (value instanceof Long literal) {
            if (knownKind(frame, slot, FrameSlotKind.Long) || frame.isLong(slot)) return frame.getLong(slot) == literal;
            return literal.equals(numericObject(frame, slot));
        }
        return FrameAccess.read(frame, slot) == value;
    }
    private static boolean knownKind(VirtualFrame frame, int slot, FrameSlotKind kind) {
        // A prepared case binder can use its known descriptor. Saved frames need their activation tags.
        var descriptor = frame.getFrameDescriptor();
        return CompilerDirectives.isPartialEvaluationConstant(descriptor) && descriptor.getSlotKind(slot) == kind;
    }
    private static Object numericObject(VirtualFrame frame, int slot) {
        return switch (FrameSlotKind.fromTag(frame.getTag(slot))) {
            case Object -> frame.getObject(slot);
            case Int, Long, Float, Double, Boolean -> null;
            default -> throw fault("Unsupported runtime frame slot tag");
        };
    }
}
