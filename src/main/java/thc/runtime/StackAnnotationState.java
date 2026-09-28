// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;

/** Persistent lazy payloads; never retains an executable frame or thread. */
public final class StackAnnotationState {
    public static final StackAnnotationState EMPTY = new StackAnnotationState(null, null);
    private final Object value;
    private final StackAnnotationState prior;
    private StackAnnotationState(Object value, StackAnnotationState prior) { this.value = value; this.prior = prior; }
    public Object getValue() { return value; }
    public StackAnnotationState getPrior() { return prior; }
    public StackAnnotationState push(Object value) { return new StackAnnotationState(value, this); }
    @TruffleBoundary public List<Object> values() {
        List<Object> values = new ArrayList<>();
        for (StackAnnotationState cursor = this; cursor.prior != null; cursor = cursor.prior) values.add(cursor.value);
        return Collections.unmodifiableList(values);
    }
    /** Only annotations inside the matching prompt travel with the delimited stack. */
    @TruffleBoundary public StackAnnotationState rebase(StackAnnotationState outside, StackAnnotationState ambient,
                                                       IdentityHashMap<StackAnnotationState, StackAnnotationState> copies) {
        copies.put(outside, ambient);
        List<StackAnnotationState> prefix = new ArrayList<>();
        StackAnnotationState cursor = this;
        while (!copies.containsKey(cursor)) {
            prefix.add(cursor);
            cursor = cursor.prior;
            if (cursor == null) throw RuntimeFault.fault("Annotation continuation lost its lexical boundary");
        }
        StackAnnotationState result = copies.get(cursor);
        for (int i = prefix.size() - 1; i >= 0; i--) {
            StackAnnotationState original = prefix.get(i);
            result = result.push(original.value); copies.put(original, result);
        }
        return result;
    }
}
