// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Requested call-by-value conventions, distinct from already-evaluated value proofs. */
public final class CoreEntries {
    private CoreEntries() {}

    private static boolean[] parse(Object value, int arity) {
        if (value == null) return new boolean[arity];
        if (!(value instanceof List<?> marks)) throw new RuntimeFault("Invalid Core entry contract");
        if (marks.size() != arity) throw new RuntimeFault("Core entry contract arity mismatch");
        boolean[] result = new boolean[arity];
        for (int index = 0; index < arity; index++) {
            if (!(marks.get(index) instanceof Boolean mark)) throw new RuntimeFault("Invalid Core entry argument mark");
            result[index] = mark;
        }
        return result;
    }

    public static boolean[] lambda(List<?> expression) {
        if (expression.isEmpty() || !"lam".equals(expression.getFirst())) return new boolean[0];
        var arguments = (List<?>) expression.get(1);
        var metadata = CoreRepresentations.INSTANCE.metadata(expression);
        return parse(metadata == null ? null : metadata.get("entryStrict"), arguments.size());
    }

    public static boolean[] binding(Map<String, ?> binding) {
        if (!(binding.get("expr") instanceof List<?> rhs) || rhs.isEmpty() || !"lam".equals(rhs.getFirst())) return null;
        var marks = lambda(rhs);
        Object supplied = binding.get("entryStrict");
        if (supplied != null && !Arrays.equals(parse(supplied, marks.length), marks))
            throw new RuntimeFault("Binding and lambda entry contracts disagree");
        return marks;
    }

    public static boolean[] join(CoreJoinDefinition definition) {
        var marks = binding(definition.getBinding());
        if (marks == null) marks = new boolean[0];
        int size = definition.getParameters().size();
        if (size > marks.length) throw new RuntimeFault("Join entry contract arity mismatch");
        return Arrays.copyOf(marks, size);
    }
}
