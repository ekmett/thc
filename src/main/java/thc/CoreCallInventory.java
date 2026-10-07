// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;
import java.util.function.Consumer;

/** Compare descriptors with multiplicity; binding traversal order is not an ABI contract. */
public final class CoreCallInventory {
    private CoreCallInventory() {}
    /** Keep non-null descriptors, including malformed values for admission to reject. */
    public static List<Object> calls(Object value) {
        var result = new ArrayList<Object>();
        collect(value, fields -> {
            if (fields.get("foreignCall") != null) result.add(fields.get("foreignCall"));
        });
        return result;
    }
    /** Archive and CAPI scans inspect only map-valued descriptors. */
    public static List<Map<?,?>> mapCalls(Object value) {
        var result = new ArrayList<Map<?,?>>();
        collect(value, fields -> {
            if (fields.get("foreignCall") instanceof Map<?,?> call) result.add(call);
        });
        return result;
    }
    private static void collect(Object value, Consumer<Map<?,?>> accept) {
        if (value instanceof Map<?,?> fields) {
            accept.accept(fields);
            for (Object child : fields.values()) collect(child, accept);
        } else if (value instanceof List<?> fields) for (Object child : fields) collect(child, accept);
    }
    public static void check(Object expected, List<?> actual, boolean complete) {
        if (!(expected instanceof List<?> inventory)) throw new IllegalArgumentException("Missing original Core foreign-call inventory");
        if (complete && inventory.size() != actual.size())
            throw new IllegalArgumentException("Retained Core foreign-call inventory differs");
        var remaining = new HashMap<Object,Integer>();
        for (Object call : inventory) remaining.merge(call, 1, Integer::sum);
        for (Object call : actual) {
            int count = remaining.getOrDefault(call, 0);
            if (count <= 0) throw new IllegalArgumentException("Demanded Core foreign call is absent from original inventory");
            remaining.put(call, count - 1);
        }
    }
}
