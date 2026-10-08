// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import jam.vm.Lifted;

/** Nonforcing resolution in the existing boxed Object domain. */
public final class LiftedValues {
    private LiftedValues() {}
    /**
     * Follow available replacements without allocation, preserving the original on a cycle.
     * Published THC answer edges are monotonic. A single traversal keeps cycle detection
     * active if a pending edge is published during the walk; unavailable edges stop it.
     * Completed scalar thunks retain their ordinary Object answers without new wrappers.
     */
    public static Object resolveBoxed(Object value) {
        Object current = value, anchor = value;
        long power = 1, distance = 0;
        while (current != null) {
            Object next = current instanceof Thunk thunk && thunk.getState() == 2 ? thunk.getValue() :
                current instanceof Lifted lifted ? lifted.resolve() : null;
            if (next == null) return current;
            current = next;
            if (current == anchor) return value;
            if (++distance == power) {
                anchor = current; distance = 0;
                power = power <= Long.MAX_VALUE / 2 ? power * 2 : Long.MAX_VALUE;
            }
        }
        return null;
    }
}
