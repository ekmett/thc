// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.source.Source;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import thc.Language;

/** Cached per context, with no integer handles or process-wide foreign roots. */
public final class JavaScriptImports {
    private record Key(String source, int arity) {}
    private final ConcurrentHashMap<Key, Object> functions = new ConcurrentHashMap<>();
    private final Pattern unicodeEscape = Pattern.compile("\\\\u(?:\\{([0-9a-fA-F]{1,6})\\}|([0-9a-fA-F]{4}))");
    private String parameters(String source, int arity) {
        // A parameter must not capture a name in the foreign expression, even
        // when that name uses JavaScript's Unicode identifier escapes.
        var matcher = unicodeEscape.matcher(source);
        var decoded = new StringBuilder();
        while (matcher.find()) {
            int code = Integer.parseInt(matcher.group(1) != null ? matcher.group(1) : matcher.group(2), 16);
            matcher.appendReplacement(decoded, Matcher.quoteReplacement(Character.isValidCodePoint(code)
                ? new String(Character.toChars(code)) : matcher.group()));
        }
        matcher.appendTail(decoded);
        var identifiers = decoded.toString();
        String prefix = "a";
        while (true) {
            boolean collision = false;
            for (int i = 0; i < arity; i++) if (identifiers.contains(prefix + i)) { collision = true; break; }
            if (!collision) break;
            prefix = "_" + prefix;
        }
        var parameters = new StringBuilder();
        for (int i = 0; i < arity; i++) { if (i != 0) parameters.append(','); parameters.append(prefix).append(i); }
        return parameters.toString();
    }
    @TruffleBoundary public Object resolve(Language.State owner, JavaScriptImport declaration) {
        var key = new Key(declaration.getSource(), declaration.getArguments().length);
        var cached = functions.get(key);
        if (cached != null) return cached;
        var arguments = parameters(declaration.getSource(), declaration.getArguments().length);
        // Preserve lookup/evaluation of the import expression on every invocation.
        var text = "((" + arguments + ") => (" + declaration.getSource() + ")(" + arguments + "))";
        var target = owner.getEnv().parsePublic(Source.newBuilder("js", text, "foreign-import.js").build());
        var value = Calls.target(target, new Object[0]);
        if (value == null) throw RuntimeFault.fault("JavaScript import produced a host null");
        // Never execute another language while holding our cache's publication lock.
        var previous = functions.putIfAbsent(key, value);
        return previous != null ? previous : value;
    }
}
