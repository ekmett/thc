// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;

/** Append-only identity membership owned by one suspended dependency inspection. */
final class ContinuationVisited {
    private static final int MAX_CAPACITY = 1 << 29;
    private static final int MAX_CACHED_CAPACITY = 256;
    private static final ThreadLocal<Object[]> SCRATCH = new ThreadLocal<>();
    private Object[] keys;
    private int size;

    private ContinuationVisited(Object[] keys) {
        this.keys = keys;
    }

    static ContinuationVisited acquire() {
        Object[] table = SCRATCH.get();
        if (table == null) table = new Object[128];
        else SCRATCH.set(null);
        return new ContinuationVisited(table);
    }

    void release() {
        Object[] table = keys;
        keys = null;
        if (table.length <= MAX_CACHED_CAPACITY) {
            // Keep the table leased until all guest references have been cleared.
            Arrays.fill(table, null);
            SCRATCH.set(table);
        }
    }

    private static int index(Object key, int mask) {
        int hash = System.identityHashCode(key);
        return (hash - (hash << 7)) & mask;
    }
    boolean add(Object key) {
        for (;;) {
            Object[] table = keys;
            int mask = table.length - 1;
            int at = index(key, mask);
            while (table[at] != null) {
                if (table[at] == key) return false;
                at = (at + 1) & mask;
            }
            int nextSize = size + 1;
            if (3 * nextSize > 2 * table.length) {
                if (table.length < MAX_CAPACITY) {
                    grow();
                    continue;
                }
                // Keep one empty probe slot, including at maximum capacity.
                if (size == MAX_CAPACITY - 1) throw new IllegalStateException("Capacity exhausted.");
            }
            table[at] = key;
            size = nextSize;
            return true;
        }
    }
    private void grow() {
        Object[] expanded = new Object[keys.length * 2];
        int mask = expanded.length - 1;
        for (Object key : keys) if (key != null) {
            int at = index(key, mask);
            while (expanded[at] != null) at = (at + 1) & mask;
            expanded[at] = key;
        }
        keys = expanded;
    }
}
