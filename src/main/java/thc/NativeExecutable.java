// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
            Main.main(launcherArguments(new String(input.readAllBytes(), StandardCharsets.UTF_8), guest));
        }
    }

    static String[] launcherArguments(String configuration, String[] guest) {
        if (!(Json.parse(configuration) instanceof List<?> fixed) || fixed.size() < 6 ||
                !"--run-executable".equals(fixed.get(0)) || !"--".equals(fixed.get(4)))
            throw new IllegalArgumentException("Expected a bound --run-executable prefix");
        var result = new String[fixed.size() + guest.length];
        for (int i = 0; i < fixed.size(); i++) {
            if (!(fixed.get(i) instanceof String value) || value.indexOf('\0') >= 0 ||
                    i < 6 && value.isBlank())
                throw new IllegalArgumentException("Invalid native executable argument " + i);
            result[i] = value;
        }
        System.arraycopy(guest, 0, result, fixed.size(), guest.length);
        return result;
    }
}
