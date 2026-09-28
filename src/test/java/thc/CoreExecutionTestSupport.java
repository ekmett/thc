// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;

/** Null-preserving builders for the synthetic Core in execution tests. */
final class CoreExecutionTestSupport {
    private CoreExecutionTestSupport() {}
    @SafeVarargs static <T> List<T> list(T... values) { return Arrays.asList(values); }
    static Map<String, Object> map(Object... pairs) {
        if ((pairs.length & 1) != 0) throw new IllegalArgumentException("Unpaired test field");
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    @SuppressWarnings("unchecked") static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") static List<Map<String, Object>> objects(Object value) { return (List<Map<String, Object>>) value; }
    static Map<String, Object> with(Map<String, Object> original, String key, Object value) {
        var result = new LinkedHashMap<>(original); result.put(key, value); return result;
    }
}
