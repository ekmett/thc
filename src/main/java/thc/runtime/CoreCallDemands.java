// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Caller-side demand proofs, distinct from entry contracts and stored WHNF facts. */
public final class CoreCallDemands {
    public static final String CALL_DEMANDS_PROPERTY = "thc.callDemands";
    private CoreCallDemands() {}

    /** Validate the evidence even when the caller-side optimization is disabled. */
    public static boolean[] lowerApplication(List<?> expression, boolean enabled) {
        var marks = application(expression);
        if (!enabled) Arrays.fill(marks, false);
        return marks;
    }

    public static boolean[] application(List<?> expression) {
        if (expression.size() <= 2 || !(expression.get(2) instanceof List<?> arguments))
            throw new RuntimeFault("Application lacks arguments");
        var metadata = CoreRepresentations.metadata(expression);
        Object raw = metadata == null ? null : metadata.get("callDemand");
        if (raw == null) return new boolean[arguments.size()];
        if (!(raw instanceof Map<?, ?> demand)) throw new RuntimeFault("Invalid Core call demand");
        if (!(demand.get("arity") instanceof Number number)) throw new RuntimeFault("Invalid Core demand arity");
        int arity = number.intValue();
        if (arity < 0 || number.doubleValue() != arity) throw new RuntimeFault("Invalid Core demand arity");
        if (!(demand.get("strictArgs") instanceof List<?> rawMarks))
            throw new RuntimeFault("Invalid Core call demand arguments");
        if (rawMarks.size() != arguments.size()) throw new RuntimeFault("Core call demand argument count mismatch");
        boolean[] marks = new boolean[arguments.size()];
        for (int index = 0; index < marks.length; index++) {
            if (!(rawMarks.get(index) instanceof Boolean mark))
                throw new RuntimeFault("Invalid Core call demand argument mark");
            if (mark && index >= arity) throw new RuntimeFault("Core call demand exceeds its signature arity");
            marks[index] = mark;
        }
        // Below the signature's saturation threshold the call stays lazy,
        // independently of the callee's runtime arity.
        if (arguments.size() < arity) Arrays.fill(marks, false);
        return marks;
    }
}
