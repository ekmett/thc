// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.CountingConditionProfile;

final class Alternative extends Node {
    static final int DEFAULT_ALTERNATIVE = 0;
    static final int DATA_ALTERNATIVE = 1;
    static final int LITERAL_ALTERNATIVE = 2;
    private final int kind;
    private final Object value;
    @CompilationFinal(dimensions = 1) private final int[] fields;
    @Child private Expr body;
    @CompilationFinal(dimensions = 2) private final int[][] vectorFields;
    private final CountingConditionProfile matchProfile = CountingConditionProfile.create();
    Alternative(int kind, Object value, int[] fields, Expr body) { this(kind, value, fields, body, new int[0][]); }
    Alternative(int kind, Object value, int[] fields, Expr body, int[][] vectorFields) {
        this.kind = kind; this.value = value; this.fields = fields; this.body = body; this.vectorFields = vectorFields;
    }
    public int getKind() { return kind; }
    public Object getValue() { return value; }
    public int[] getFields() { return fields; }
    public Expr getBody() { return body; }
    public void setBody(Expr body) { this.body = body; }
    public int[][] getVectorFields() { return vectorFields; }
    boolean matchesData(DataValue value) { return matchProfile.profile(((DataLayout) this.value).matches(value)); }
    boolean matchesLong(long value) { return matchProfile.profile(value == (long) (Long) this.value); }
    boolean matches(VirtualFrame frame, int slot) {
        return matchProfile.profile(switch (kind) {
            case DATA_ALTERNATIVE -> frame.isObject(slot) && ((DataLayout) value).matches(frame.getObject(slot));
            case LITERAL_ALTERNATIVE -> matchesLiteral(frame, slot);
            default -> false;
        });
    }
    private boolean matchesLiteral(VirtualFrame frame, int slot) {
        if (value instanceof Integer literal) {
            if (frame.isInt(slot)) return frame.getInt(slot) == literal;
            Object scrutinee = FrameAccess.INSTANCE.read(frame, slot);
            return scrutinee instanceof Integer number && number.intValue() == literal.intValue();
        }
        if (value instanceof Long literal) {
            if (frame.isLong(slot)) return frame.getLong(slot) == literal;
            Object scrutinee = FrameAccess.INSTANCE.read(frame, slot);
            return scrutinee instanceof Long number && number.longValue() == literal.longValue();
        }
        // Remaining literal Addr# carriers compare by identity, never guest equals.
        return FrameAccess.INSTANCE.read(frame, slot) == value;
    }
}
