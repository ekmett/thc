// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;
import java.util.function.Consumer;

/** Ordered, nullable documents for the independent format test models. */
final class CoreFormatTestSupport {
    private CoreFormatTestSupport() {}
    static Map<String, Object> map(Object... fields) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]);
        return result;
    }
    static Map<String, Object> with(Map<?, ?> original, Object... fields) {
        var result = new LinkedHashMap<String, Object>();
        original.forEach((key, value) -> result.put((String) key, value));
        result.putAll(map(fields));
        return result;
    }
    static Map<String, Object> without(Map<?, ?> original, String... fields) {
        var result = with(original); for (var field : fields) result.remove(field); return result;
    }
    static List<Object> list(Object... values) { return Arrays.asList(values); }
    @SuppressWarnings("unchecked")
    static Map<String, Object> document(String text) { return (Map<String, Object>) Json.parse(text); }
    static String request(List<String> paths, String entry, String backend, boolean sourceNotes,
            Boolean async, boolean indexed, boolean verify) {
        return CoreModules.request(paths, entry, true, false, backend, sourceNotes,
            false, null, async, indexed, verify);
    }
    static void visit(Map<String, Object> input, Consumer<Map<String, Object>> consumer) {
        CoreModules.visitRequestModules(input, consumer);
    }
}
