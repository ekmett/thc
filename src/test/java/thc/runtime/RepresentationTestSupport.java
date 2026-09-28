// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.*;

/** Nullable Core metadata builders for the representation controls. */
final class RepresentationTestSupport {
    private RepresentationTestSupport() {}
    @SafeVarargs static <T> List<T> list(T... values) { return Arrays.asList(values); }
    static Map<String, Object> map(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    static Map<String, Object> with(Map<String, Object> original, Object... pairs) {
        var result = new LinkedHashMap<>(original); result.putAll(map(pairs)); return result;
    }
    static Map<String, Object> without(Map<String, Object> original, String key) {
        var result = new LinkedHashMap<>(original); result.remove(key); return result;
    }
    @SuppressWarnings("unchecked") static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") static List<Object> expression(Object value) { return (List<Object>) value; }
    @SuppressWarnings("unchecked") static List<Map<String, Object>> objects(Object value) { return (List<Map<String, Object>>) value; }
    @FunctionalInterface interface CheckedConsumer<T> { void accept(T value) throws Exception; }
    @FunctionalInterface interface CheckedBiConsumer<T, U> { void accept(T value, U other) throws Exception; }
    @FunctionalInterface interface CheckedRunnable { void run() throws Exception; }
    @SuppressWarnings("unchecked") static <E extends Throwable> RuntimeException rethrow(Throwable failure) throws E { throw (E) failure; }
}
