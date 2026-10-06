// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;

/** Small strict JSON transport reader; Core is exported structurally, never parsed from dumps. */
public final class Json {
    public static final Json INSTANCE = new Json();
    private Json() {}
    public static Object parse(String text) { return new Reader(text, true).readDocument(false); }
    public static String stringify(Object value) {
        var result = new StringBuilder();
        appendJson(result, value);
        return result.toString();
    }
    /** Retain a tree independently of mutable caller maps and lists; nulls are values. */
    public static Object immutable(Object value) {
        if (value instanceof Map<?,?> fields) {
            var copy = new LinkedHashMap<Object,Object>();
            fields.forEach((key, child) -> copy.put(key, immutable(child)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> values) return values.stream().map(Json::immutable).toList();
        return value;
    }
    /** Validate without materializing a second tree, and preserve ASCII transport storage. */
    public static void appendObjectDocument(StringBuilder destination, String text) {
        new Reader(text, false).readDocument(true);
        boolean quoted = false, escaped = false;
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            if (quoted && c > 127) {
                destination.append(text, start, index).append("\\u");
                for (int shift = 12; shift >= 0; shift -= 4) destination.append("0123456789abcdef".charAt((c >>> shift) & 15));
                start = index + 1;
            }
            if (escaped) escaped = false;
            else if (quoted && c == '\\') escaped = true;
            else if (c == '"') quoted = !quoted;
        }
        destination.append(text, start, text.length());
    }
    private static void appendJson(StringBuilder destination, Object value) {
        if (value == null) destination.append("null");
        else if (value instanceof String text) {
            destination.append('"');
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"' -> destination.append("\\\"");
                    case '\\' -> destination.append("\\\\");
                    case '\n' -> destination.append("\\n");
                    case '\r' -> destination.append("\\r");
                    case '\t' -> destination.append("\\t");
                    default -> {
                        if (c < 32) destination.append("\\u00").append("0123456789abcdef".charAt(c >>> 4)).append("0123456789abcdef".charAt(c & 15));
                        else destination.append(c);
                    }
                }
            }
            destination.append('"');
        } else if (value instanceof Boolean || value instanceof Number) destination.append(value);
        else if (value instanceof Map<?, ?> fields) {
            destination.append('{');
            boolean first = true;
            for (var field : fields.entrySet()) {
                require(field.getKey() instanceof String, "JSON object key must be a string");
                if (!first) destination.append(',');
                first = false;
                appendJson(destination, field.getKey());
                destination.append(':');
                appendJson(destination, field.getValue());
            }
            destination.append('}');
        } else if (value instanceof Iterable<?> values) appendArray(destination, values.iterator());
        else if (value instanceof Object[] values) appendArray(destination, Arrays.asList(values).iterator());
        else throw new IllegalStateException("Unsupported JSON value: " + value.getClass().getName());
    }
    private static void appendArray(StringBuilder destination, Iterator<?> values) {
        destination.append('[');
        boolean first = true;
        while (values.hasNext()) {
            if (!first) destination.append(',');
            first = false;
            appendJson(destination, values.next());
        }
        destination.append(']');
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    private static final class Reader {
        final String text;
        final boolean materializeValues;
        int at;
        Reader(String text, boolean materializeValues) { this.text = text; this.materializeValues = materializeValues; }
        Object readDocument(boolean objectOnly) {
            whitespace();
            require(!objectOnly || at < text.length() && text.charAt(at) == '{', "Expected JSON object document");
            Object value = value();
            whitespace();
            require(at == text.length(), "Trailing JSON at " + at);
            return value;
        }
        void whitespace() { while (at < text.length() && " \n\r\t".indexOf(text.charAt(at)) >= 0) at++; }
        Object value() {
            whitespace();
            require(at < text.length(), "Unexpected end of JSON");
            return switch (text.charAt(at)) {
                case '{' -> objectValue();
                case '[' -> arrayValue();
                case '"' -> string(materializeValues);
                case 't' -> keyword("true", true);
                case 'f' -> keyword("false", false);
                case 'n' -> keyword("null", null);
                case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> number();
                default -> throw new IllegalStateException("Unexpected JSON character at " + at);
            };
        }
        Object keyword(String word, Object value) {
            require(text.startsWith(word, at), "Expected " + word + " at " + at);
            at += word.length();
            return value;
        }
        boolean take(char c) {
            whitespace();
            if (at < text.length() && text.charAt(at) == c) { at++; return true; }
            return false;
        }
        void need(char c) { require(take(c), "Expected " + c + " at " + at); }
        Map<String, Object> objectValue() {
            need('{');
            Map<String, Object> result = materializeValues ? new LinkedHashMap<>() : null;
            Set<String> keys = materializeValues ? null : new HashSet<>();
            if (take('}')) return result;
            do {
                whitespace();
                String key = string(true);
                require(result != null ? !result.containsKey(key) : keys.add(key), "Duplicate JSON key: " + key);
                need(':');
                Object field = value();
                if (result != null) result.put(key, field);
                if (take('}')) return result;
                need(',');
            } while (true);
        }
        List<Object> arrayValue() {
            need('[');
            List<Object> result = materializeValues ? new ArrayList<>() : null;
            if (take(']')) return result;
            do {
                Object element = value();
                if (result != null) result.add(element);
                if (take(']')) return result;
                need(',');
            } while (true);
        }
        String string(boolean materialize) {
            need('"');
            StringBuilder result = materialize ? new StringBuilder() : null;
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') return result == null ? null : result.toString();
                require(c >= 32, "Control character in JSON string");
                if (c != '\\') { if (result != null) result.append(c); continue; }
                require(at < text.length(), "Incomplete JSON escape");
                char escape = text.charAt(at++);
                char decoded = switch (escape) {
                    case '"', '\\', '/' -> escape;
                    case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                    case 'u' -> {
                        require(at + 4 <= text.length(), "Incomplete unicode escape");
                        char value = (char) Integer.parseInt(text.substring(at, at + 4), 16);
                        at += 4;
                        yield value;
                    }
                    default -> throw new IllegalStateException("Unknown JSON escape: " + escape);
                };
                if (result != null) result.append(decoded);
            }
            throw new IllegalStateException("Unterminated JSON string");
        }
        Number number() {
            int start = at;
            if (text.charAt(at) == '-') at++;
            require(at < text.length() && Character.isDigit(text.charAt(at)), "Invalid JSON number");
            if (text.charAt(at) == '0') at++;
            else while (at < text.length() && Character.isDigit(text.charAt(at))) at++;
            boolean floating = false;
            if (at < text.length() && text.charAt(at) == '.') {
                floating = true; at++;
                require(at < text.length() && Character.isDigit(text.charAt(at)), "Invalid fraction");
                while (at < text.length() && Character.isDigit(text.charAt(at))) at++;
            }
            if (at < text.length() && "eE".indexOf(text.charAt(at)) >= 0) {
                floating = true; at++;
                if (at < text.length() && "+-".indexOf(text.charAt(at)) >= 0) at++;
                require(at < text.length() && Character.isDigit(text.charAt(at)), "Invalid exponent");
                while (at < text.length() && Character.isDigit(text.charAt(at))) at++;
            }
            String value = text.substring(start, at);
            if (!floating) return Long.parseLong(value);
            double result = Double.parseDouble(value);
            require(Double.isFinite(result), "Failed requirement.");
            return result;
        }
    }
}
