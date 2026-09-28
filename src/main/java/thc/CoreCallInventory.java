// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;

/** Exhaustive admission compares the complete sequence; demanded subsets still
 * account for each descriptor with its multiplicity. */
public final class CoreCallInventory {
    private CoreCallInventory() {}
    public static void check(Object expected, List<?> actual, boolean complete) {
        if (!(expected instanceof List<?> inventory)) throw new IllegalArgumentException("Missing original Core foreign-call inventory");
        if (complete) {
            if (!inventory.equals(actual)) throw new IllegalArgumentException("Retained Core foreign-call inventory differs");
        } else {
            var remaining = new HashMap<Object,Integer>();
            for (Object call : inventory) remaining.merge(call, 1, Integer::sum);
            for (Object call : actual) {
                int count = remaining.getOrDefault(call, 0);
                if (count <= 0) throw new IllegalArgumentException("Demanded Core foreign call is absent from original inventory");
                remaining.put(call, count - 1);
            }
        }
    }
}
