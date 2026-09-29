// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Native Image entry bound to an executable's ordinary Core loader and IO lifecycle.
 * The embedded JSON array supplies Main's fixed executable prefix and optional
 * default guest arguments. This mode lowers guest Core at runtime; it is not a
 * persisted compiled-guest cache or a claim of whole-program guest AOT.
 */
public final class NativeExecutable {
    private NativeExecutable() {}

    public static void main(String[] guest) throws IOException {
        try (var input = NativeExecutable.class.getResourceAsStream("/thc-native-executable.json")) {
            if (input == null) throw new IllegalStateException("Native executable binding is missing");
            var configuration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            var arguments = launcherArguments(configuration, guest);
            initializeProperties(configuration);
            Main.main(arguments);
        }
    }

    static String[] launcherArguments(String configuration, String[] guest) {
        var value = Json.parse(configuration);
        if (value instanceof Map<?, ?> binding) value = binding.get("arguments");
        if (!(value instanceof List<?> fixed) || fixed.size() < 6 ||
                !"--run-executable".equals(fixed.get(0)) || !"--".equals(fixed.get(4)))
            throw new IllegalArgumentException("Expected a bound --run-executable prefix");
        var result = new String[fixed.size() + guest.length];
        for (int i = 0; i < fixed.size(); i++) {
            if (!(fixed.get(i) instanceof String argument) || argument.indexOf('\0') >= 0 ||
                    i < 6 && argument.isBlank())
                throw new IllegalArgumentException("Invalid native executable argument " + i);
            result[i] = argument;
        }
        System.arraycopy(guest, 0, result, fixed.size(), guest.length);
        return result;
    }

    /** The trusted image binding configures THC, never consuming guest -D flags. */
    static void initializeProperties(String configuration) {
        if (!(Json.parse(configuration) instanceof Map<?, ?> binding) || !binding.containsKey("properties")) return;
        if (!(binding.get("properties") instanceof Map<?, ?> properties))
            throw new IllegalArgumentException("Expected native executable properties");
        for (var entry : properties.entrySet())
            if (!(entry.getKey() instanceof String key) || key.isEmpty() || !(entry.getValue() instanceof String))
                throw new IllegalArgumentException("Invalid native executable property");
        properties.forEach((key, value) -> System.setProperty((String) key, (String) value));
    }
}
