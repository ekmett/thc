// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreFormatTestSupport.*;

class JsonWriterTest {
    @Test void exactEscapingAndContainerFormatting() {
        var controls = new StringBuilder();
        for (int i = 0; i <= 31; i++) controls.append((char) i);
        var value = map("\"\\\n", new Object[]{controls.toString(), "λ😀\uD800", null, true, false, -42L, 1.25, 1.0e100},
            "empty", List.of(), "object", Map.of());
        var expected = "{\"\\\"\\\\\\n\":[\"" +
            "\\u0000\\u0001\\u0002\\u0003\\u0004\\u0005\\u0006\\u0007\\u0008\\t\\n\\u000b\\u000c\\r" +
            "\\u000e\\u000f\\u0010\\u0011\\u0012\\u0013\\u0014\\u0015\\u0016\\u0017\\u0018\\u0019\\u001a\\u001b\\u001c\\u001d\\u001e\\u001f\",\"λ😀\uD800\",null,true,false,-42,1.25,1.0E100],\"empty\":[],\"object\":{}}";
        assertEquals(expected, Json.stringify(value));
        assertEquals(Json.parse(expected), Json.parse(Json.stringify(value)));
    }
    private Object tree(Random random, int depth) {
        if (depth == 0) return switch (random.nextInt(5)) {
            case 0 -> null;
            case 1 -> random.nextLong();
            case 2 -> random.nextBoolean();
            case 3 -> random.nextDouble();
            default -> "id:" + random.nextInt() + "\n\\\"\u0000λ";
        };
        int kind = random.nextInt(3), size = random.nextInt(6);
        if (kind == 0) {
            var values = new ArrayList<>();
            for (int i = 0; i < size; i++) values.add(tree(random, depth - 1));
            return values;
        }
        if (kind == 1) {
            Object[] values = new Object[size];
            for (int i = 0; i < size; i++) values[i] = tree(random, depth - 1);
            return values;
        }
        var values = new LinkedHashMap<String, Object>();
        for (int i = 0; i < size; i++) values.put("key" + i, tree(random, depth - 1));
        return values;
    }
    @Test void matchesPreviousTransportForNestedCoreShapedDocuments() {
        var random = new Random(1931);
        for (int i = 0; i < 100; i++) {
            var document = tree(random, 5);
            assertEquals(previous(document), Json.stringify(document), "document " + i);
        }
        Object deep = "payload".repeat(1024);
        for (int i = 0; i < 100; i++) deep = Map.of("expr", List.of("node", deep));
        assertEquals(previous(deep), Json.stringify(deep));
    }
    @Test void traversesAnIterableOnceAndPreservesRejection() {
        int[] iterations = {0};
        Iterable<Object> values = () -> {
            if (iterations[0]++ != 0) throw new IllegalStateException("Check failed.");
            return list(1L, "two", null).iterator();
        };
        assertEquals("[1,\"two\",null]", Json.stringify(values));
        assertEquals(1, iterations[0]);
        var keyFailure = assertThrows(IllegalArgumentException.class, () -> Json.stringify(Map.of(1, "x")));
        assertEquals("JSON object key must be a string", keyFailure.getMessage());
        assertThrows(IllegalStateException.class, () -> Json.stringify(List.of(new Object())));
    }
    // Frozen transport reference: catches format changes as well as round-trip changes.
    private String previous(Object value) {
        return switch (value) {
            case null -> "null";
            case String text -> {
                var out = new StringBuilder("\"");
                for (char c : text.toCharArray()) switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> { if (c < 32) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
                }
                yield out.append('"').toString();
            }
            case Boolean flag -> flag.toString();
            case Number number -> number.toString();
            case Map<?, ?> object -> {
                var out = new StringJoiner(",", "{", "}");
                for (var entry : object.entrySet()) {
                    if (!(entry.getKey() instanceof String)) throw new IllegalArgumentException("JSON object key must be a string");
                    out.add(previous(entry.getKey()) + ":" + previous(entry.getValue()));
                }
                yield out.toString();
            }
            case Iterable<?> items -> {
                var out = new StringJoiner(",", "[", "]");
                for (var item : items) out.add(previous(item));
                yield out.toString();
            }
            case Object[] items -> {
                var out = new StringJoiner(",", "[", "]");
                for (var item : items) out.add(previous(item));
                yield out.toString();
            }
            default -> throw new IllegalStateException("Unsupported JSON value: " + value.getClass().getName());
        };
    }
}
